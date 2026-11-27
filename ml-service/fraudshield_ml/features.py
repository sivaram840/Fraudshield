"""Feature engineering shared by training and online scoring.

Both the training pipeline and the FastAPI service call the functions in this
module, so the features a model sees at serving time are computed exactly the
way they were computed when it was trained.
"""
from __future__ import annotations

import bisect
from collections import defaultdict

import numpy as np
import pandas as pd

# --------------------------------------------------------------------------
# Transaction-level features (PaySim model)
# --------------------------------------------------------------------------
# Only information that is known BEFORE the transaction executes is used.
# PaySim's post-transaction balances (newbalanceOrig/newbalanceDest) and the
# isFlaggedFraud column leak the label and are deliberately excluded.
TXN_FEATURES = [
    "amount_log",
    "is_transfer",
    "hour",
    "is_night",
    "amt_to_balance",
    "drains_account",
    "orig_balance_log",
    "orig_balance_zero",
    "dest_balance_zero",
    "dest_balance_log",
]


def txn_features_frame(df: pd.DataFrame) -> pd.DataFrame:
    """df needs columns: type, amount, step, oldbalanceOrg, oldbalanceDest."""
    amount = df["amount"].astype(float).to_numpy()
    ob = df["oldbalanceOrg"].astype(float).to_numpy()
    od = df["oldbalanceDest"].astype(float).to_numpy()
    hour = df["step"].astype(int).to_numpy() % 24
    out = pd.DataFrame(index=df.index)
    out["amount_log"] = np.log1p(amount)
    out["is_transfer"] = (df["type"].to_numpy() == "TRANSFER").astype(int)
    out["hour"] = hour
    out["is_night"] = (hour < 6).astype(int)
    out["amt_to_balance"] = np.clip(amount / (ob + 1.0), 0, 10)
    out["drains_account"] = ((ob > 0) & (amount >= 0.95 * ob)).astype(int)
    out["orig_balance_log"] = np.log1p(ob)
    out["orig_balance_zero"] = (ob <= 0).astype(int)
    out["dest_balance_zero"] = (od <= 0).astype(int)
    out["dest_balance_log"] = np.log1p(od)
    return out[TXN_FEATURES]


def txn_features_single(amount: float, txn_type: str, hour: int,
                        sender_balance: float, receiver_balance: float) -> pd.DataFrame:
    row = pd.DataFrame([{
        "type": txn_type, "amount": amount, "step": hour,
        "oldbalanceOrg": sender_balance, "oldbalanceDest": receiver_balance,
    }])
    return txn_features_frame(row)


# --------------------------------------------------------------------------
# Account-level graph features (money-mule model + Isolation Forest)
# --------------------------------------------------------------------------
WINDOW_SECONDS = 24 * 3600

GRAPH_FEATURES = [
    "fan_in",                 # distinct senders in the window
    "fan_out",                # distinct receivers in the window
    "in_count",
    "out_count",
    "in_amount_log",
    "out_amount_log",
    "pass_through",           # out_amount / in_amount (capped at 2)
    "min_hold_minutes",       # fastest receive -> send-on gap
    "upstream_depth",         # longest time-respecting chain ending here
    "new_counterparty_share", # counterparties never seen before the window
]


class GraphFeatureBuilder:
    """Builds per-account behavioural features from a directed, timestamped
    transaction graph (edges = transfers, nodes = accounts).

    Edge "depth" is computed with dynamic programming in time order: an edge
    u->v at time t has depth 1 + max(depth of edges x->u in [t - window, t]).
    A depth of 3 therefore means money moved through at least three hops in
    quick succession, the multi-stage layering pattern used by mule networks.
    """

    def __init__(self, src, dst, ts, amount, window: int = WINDOW_SECONDS,
                 max_depth: int = 6, scan_cap: int = 50):
        src = np.asarray(src, dtype=object)
        dst = np.asarray(dst, dtype=object)
        ts = np.asarray(ts, dtype=float)
        amount = np.asarray(amount, dtype=float)
        order = np.argsort(ts, kind="stable")
        self.src, self.dst = src[order], dst[order]
        self.ts, self.amount = ts[order], amount[order]
        self.window = window
        self.max_depth = max_depth

        self.in_edges: dict = defaultdict(list)
        self.out_edges: dict = defaultdict(list)
        self.pair_first: dict = {}
        for i in range(len(self.ts)):
            s, d = self.src[i], self.dst[i]
            self.out_edges[s].append(i)
            self.in_edges[d].append(i)
            key = (s, d) if s <= d else (d, s)
            if key not in self.pair_first:
                self.pair_first[key] = self.ts[i]
        self.in_ts = {a: [self.ts[i] for i in idx] for a, idx in self.in_edges.items()}
        self.out_ts = {a: [self.ts[i] for i in idx] for a, idx in self.out_edges.items()}

        self.depth = np.ones(len(self.ts), dtype=np.int16)
        for i in range(len(self.ts)):
            s, t = self.src[i], self.ts[i]
            incoming = self.in_edges.get(s)
            if not incoming:
                continue
            hi = bisect.bisect_right(self.in_ts[s], t)
            lo = bisect.bisect_left(self.in_ts[s], t - window)
            best = 0
            for j in incoming[max(lo, hi - scan_cap):hi]:
                if j < i and self.depth[j] > best:
                    best = self.depth[j]
            self.depth[i] = min(best + 1, max_depth)

    def _window(self, account, t, edges, times):
        idx = edges.get(account)
        if not idx:
            return []
        tl = times[account]
        lo = bisect.bisect_left(tl, t - self.window)
        hi = bisect.bisect_right(tl, t)
        return idx[lo:hi]

    def features(self, account, t: float) -> dict:
        ins = self._window(account, t, self.in_edges, self.in_ts)
        outs = self._window(account, t, self.out_edges, self.out_ts)
        in_amt = float(self.amount[ins].sum()) if ins else 0.0
        out_amt = float(self.amount[outs].sum()) if outs else 0.0
        senders = {self.src[i] for i in ins}
        receivers = {self.dst[i] for i in outs}

        hold = self.window / 60.0
        if ins and outs:
            in_times = sorted(self.ts[i] for i in ins)
            for o in outs:
                k = bisect.bisect_right(in_times, self.ts[o]) - 1
                if k >= 0:
                    hold = min(hold, (self.ts[o] - in_times[k]) / 60.0)

        counterparties = senders | receivers
        start = t - self.window
        new = 0
        for c in counterparties:
            key = (account, c) if account <= c else (c, account)
            if self.pair_first.get(key, start) >= start:
                new += 1

        return {
            "fan_in": len(senders),
            "fan_out": len(receivers),
            "in_count": len(ins),
            "out_count": len(outs),
            "in_amount_log": float(np.log1p(in_amt)),
            "out_amount_log": float(np.log1p(out_amt)),
            "pass_through": float(min(out_amt / in_amt, 2.0)) if in_amt > 0 else 0.0,
            "min_hold_minutes": float(hold),
            "upstream_depth": int(max((self.depth[i] for i in ins), default=0)),
            "new_counterparty_share": new / len(counterparties) if counterparties else 0.0,
        }

    def features_frame(self, accounts, times) -> pd.DataFrame:
        rows = [self.features(a, t) for a, t in zip(accounts, times)]
        return pd.DataFrame(rows, columns=GRAPH_FEATURES)
