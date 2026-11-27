"""Dataset preparation: loads the real Kaggle files when present, otherwise
falls back to the synthetic generators, and converts everything into the two
shapes the trainer uses.

Expected locations (download from Kaggle yourself, see README):
  data/raw/PS_20174392719_1491204439457_log.csv   PaySim (ealaxi/paysim1)
  data/raw/HI-Small_Trans.csv                     IBM AML (ealtman2019/...)
  data/raw/SAML-D.csv                             SAML-D (optional, cross-dataset test)
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from . import synthetic
from .features import WINDOW_SECONDS

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "data" / "raw"
PROCESSED = ROOT / "data" / "processed"

PAYSIM_FILE = RAW / "PS_20174392719_1491204439457_log.csv"
IBM_FILE = RAW / "HI-Small_Trans.csv"
SAMLD_FILE = RAW / "SAML-D.csv"

EDGE_COLUMNS = ["ts", "src", "dst", "amount", "label"]


def load_paysim(path: Path | None = None, max_rows: int | None = None) -> tuple[pd.DataFrame, str]:
    path = path or PAYSIM_FILE
    if path.exists():
        df = pd.read_csv(path, nrows=max_rows)
        source = f"PaySim (Kaggle) {path.name}"
    else:
        df = synthetic.generate_paysim_like()
        source = "synthetic PaySim-schema data (real file not found)"
    missing = set(synthetic.PAYSIM_COLUMNS) - set(df.columns)
    if missing:
        raise ValueError(f"PaySim file is missing columns: {sorted(missing)}")
    # All PaySim fraud happens in TRANSFER and CASH_OUT, so the model is trained there.
    df = df[df["type"].isin(["TRANSFER", "CASH_OUT"])].reset_index(drop=True)
    return df, source


def ibm_to_edges(df: pd.DataFrame) -> pd.DataFrame:
    need = {"Timestamp", "From Bank", "Account", "To Bank", "Account.1", "Amount Paid",
            "Payment Currency", "Receiving Currency", "Is Laundering"}
    missing = need - set(df.columns)
    if missing:
        raise ValueError(f"IBM AML file is missing columns: {sorted(missing)}")
    # Keep same-currency payments so amounts are comparable without FX tables.
    df = df[df["Payment Currency"] == df["Receiving Currency"]]
    ts = pd.to_datetime(df["Timestamp"], format="%Y/%m/%d %H:%M")
    return pd.DataFrame({
        "ts": (ts - pd.Timestamp("1970-01-01")).dt.total_seconds().to_numpy(),
        "src": (df["From Bank"].astype(str) + ":" + df["Account"].astype(str)).to_numpy(),
        "dst": (df["To Bank"].astype(str) + ":" + df["Account.1"].astype(str)).to_numpy(),
        "amount": df["Amount Paid"].astype(float).to_numpy(),
        "label": df["Is Laundering"].astype(int).to_numpy(),
    })


def samld_to_edges(df: pd.DataFrame) -> pd.DataFrame:
    need = {"Time", "Date", "Sender_account", "Receiver_account", "Amount", "Is_laundering"}
    missing = need - set(df.columns)
    if missing:
        raise ValueError(f"SAML-D file is missing columns: {sorted(missing)}")
    ts = pd.to_datetime(df["Date"].astype(str) + " " + df["Time"].astype(str))
    return pd.DataFrame({
        "ts": (ts - pd.Timestamp("1970-01-01")).dt.total_seconds().to_numpy(),
        "src": df["Sender_account"].astype(str).to_numpy(),
        "dst": df["Receiver_account"].astype(str).to_numpy(),
        "amount": df["Amount"].astype(float).to_numpy(),
        "label": df["Is_laundering"].astype(int).to_numpy(),
    })


def load_aml_edges(max_rows: int | None = None) -> tuple[pd.DataFrame, str]:
    if IBM_FILE.exists():
        edges = ibm_to_edges(pd.read_csv(IBM_FILE, nrows=max_rows))
        source = f"IBM Transactions for AML (Kaggle) {IBM_FILE.name}"
    else:
        edges = ibm_to_edges(synthetic.generate_aml_like())
        source = "synthetic IBM-AML-schema data with injected typologies (real file not found)"
    return edges.sort_values("ts", kind="stable").reset_index(drop=True), source


def load_samld_edges(max_rows: int | None = None) -> pd.DataFrame | None:
    if not SAMLD_FILE.exists():
        return None
    return samld_to_edges(pd.read_csv(SAMLD_FILE, nrows=max_rows)).sort_values("ts").reset_index(drop=True)


def mule_accounts(edges: pd.DataFrame) -> set:
    """Accounts that both receive and send laundered money (the intermediaries)."""
    bad = edges[edges["label"] == 1]
    return set(bad["dst"]) & set(bad["src"])


def account_snapshots(edges: pd.DataFrame, builder, max_negatives: int = 150_000,
                      seed: int = 0) -> pd.DataFrame:
    """One row per (account, moment) at which the account sends or receives.

    label = 1 when the moment is a laundering transfer and the account is an
    intermediary of laundered money. Dropped as ambiguous label noise:
      * laundering snapshots of pure sources and final sinks (they are not mules);
      * a mule's FIRST laundered inflow in a 24h window: at that instant it looks
        exactly like any ordinary first payment, and labelling it positive teaches
        the model that every first receipt is a mule.
    """
    mules = mule_accounts(edges)
    lab = edges["label"].to_numpy()
    ts = edges["ts"].to_numpy()
    first_inflow = np.zeros(len(edges), dtype=bool)
    last_seen: dict = {}
    for i in np.flatnonzero(lab == 1):          # edges are sorted by time
        d = edges["dst"].iat[i]
        prev = last_seen.get(d)
        first_inflow[i] = prev is None or ts[i] - prev > WINDOW_SECONDS
        last_seen[d] = ts[i]
    rows = []
    for role in ("src", "dst"):
        acct = edges[role].to_numpy()
        is_mule = np.fromiter((a in mules for a in acct), bool, len(acct))
        y = (lab == 1) & is_mule
        keep = ~((lab == 1) & ~is_mule)
        if role == "dst":
            keep &= ~(y & first_inflow)
        rows.append(pd.DataFrame({"account": acct[keep], "ts": edges["ts"].to_numpy()[keep],
                                  "label": y[keep].astype(int)}))
    snaps = pd.concat(rows, ignore_index=True).drop_duplicates(["account", "ts"])
    pos, neg = snaps[snaps.label == 1], snaps[snaps.label == 0]
    if len(neg) > max_negatives:
        neg = neg.sample(max_negatives, random_state=seed)
    snaps = pd.concat([pos, neg]).sort_values("ts", kind="stable").reset_index(drop=True)
    feats = builder.features_frame(snaps["account"], snaps["ts"])
    return pd.concat([snaps, feats], axis=1)
