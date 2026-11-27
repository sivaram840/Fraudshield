"""Synthetic data generators.

* generate_paysim_like  - rows in the exact PaySim column schema, used when the
  real Kaggle file is not present so the pipeline stays reproducible.
* generate_aml_like     - rows in the IBM "Transactions for AML" schema with
  labelled laundering typologies (fan-in mules, chains, scatter-gather,
  fan-out, cycles) plus hard negatives (merchants, payroll, people who forward
  rent quickly).

Synthetic data only demonstrates that the pipeline works. Metrics measured on
it must NOT be read as real-world money-mule detection accuracy.
"""
from __future__ import annotations

from datetime import datetime, timedelta

import numpy as np
import pandas as pd

PAYSIM_COLUMNS = ["step", "type", "amount", "nameOrig", "oldbalanceOrg", "newbalanceOrig",
                  "nameDest", "oldbalanceDest", "newbalanceDest", "isFraud", "isFlaggedFraud"]

AML_COLUMNS = ["Timestamp", "From Bank", "Account", "To Bank", "Account.1", "Amount Received",
               "Receiving Currency", "Amount Paid", "Payment Currency", "Payment Format",
               "Is Laundering"]


def _hour_of_day(rng, n, night_share):
    day = rng.normal(14, 3.5, n).clip(6, 23).astype(int)
    night = rng.integers(0, 6, n)
    return np.where(rng.random(n) < night_share, night, day)


def generate_paysim_like(n_normal: int = 200_000, n_fraud_pairs: int = 900,
                         steps: int = 720, seed: int = 7) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    n = n_normal
    types = rng.choice(["PAYMENT", "CASH_OUT", "CASH_IN", "TRANSFER", "DEBIT"], n,
                       p=[0.34, 0.35, 0.22, 0.08, 0.01])
    scale = np.select([types == "TRANSFER", types == "CASH_OUT", types == "CASH_IN"],
                      [11.8, 11.3, 11.4], 9.0)
    amount = np.round(rng.lognormal(scale, 1.1), 2)
    ob = np.round(amount * rng.lognormal(0.9, 1.0, n), 2)
    ob[rng.random(n) < 0.18] = 0.0                     # PaySim has many zero-balance originators
    drain = rng.random(n) < 0.04                       # some honest users empty their account
    ob[drain] = amount[drain]
    od = np.round(rng.lognormal(11.5, 1.6, n), 2)
    od[rng.random(n) < 0.25] = 0.0
    is_merchant = types == "PAYMENT"
    od[is_merchant] = 0.0
    day = rng.integers(0, steps // 24, n)
    step = day * 24 + _hour_of_day(rng, n, 0.07)
    orig = np.char.add("C", rng.integers(10**8, 10**9, n).astype(str))
    dest = np.where(is_merchant, np.char.add("M", rng.integers(10**8, 10**9, n).astype(str)),
                    np.char.add("C", rng.integers(10**8, 10**9, n).astype(str)))
    normal = pd.DataFrame({"step": step, "type": types, "amount": amount, "nameOrig": orig,
                           "oldbalanceOrg": ob, "nameDest": dest, "oldbalanceDest": od,
                           "isFraud": 0})

    # Fraud: account takeover -> TRANSFER (usually draining the victim) to a mule,
    # then the mule CASHES OUT the same amount shortly afterwards.
    m = n_fraud_pairs
    v_bal = np.round(rng.lognormal(11.9, 1.2, m), 2)
    share = np.where(rng.random(m) < 0.75, 1.0, rng.uniform(0.4, 0.9, m))
    f_amt = np.round(v_bal * share, 2)
    f_step = rng.integers(0, steps // 24, m) * 24 + _hour_of_day(rng, m, 0.35)
    mule = np.char.add("C", rng.integers(10**8, 10**9, m).astype(str))
    mule_bal = np.where(rng.random(m) < 0.7, 0.0, np.round(rng.lognormal(8, 1, m), 2))
    transfer = pd.DataFrame({"step": f_step, "type": "TRANSFER", "amount": f_amt,
                             "nameOrig": np.char.add("C", rng.integers(10**8, 10**9, m).astype(str)),
                             "oldbalanceOrg": v_bal, "nameDest": mule, "oldbalanceDest": mule_bal,
                             "isFraud": 1})
    cashout = pd.DataFrame({"step": f_step + rng.integers(0, 2, m), "type": "CASH_OUT",
                            "amount": f_amt, "nameOrig": mule,
                            "oldbalanceOrg": mule_bal + f_amt,
                            "nameDest": np.char.add("C", rng.integers(10**8, 10**9, m).astype(str)),
                            "oldbalanceDest": np.round(rng.lognormal(11, 1.5, m), 2), "isFraud": 1})
    df = pd.concat([normal, transfer, cashout], ignore_index=True)
    df["newbalanceOrig"] = (df["oldbalanceOrg"] - df["amount"]).clip(lower=0).round(2)
    df["newbalanceDest"] = (df["oldbalanceDest"] + df["amount"]).round(2)
    df["isFlaggedFraud"] = ((df["type"] == "TRANSFER") & (df["amount"] > 200_000)
                            & (df["isFraud"] == 1)).astype(int)
    df = df.sort_values("step", kind="stable").reset_index(drop=True)
    return df[PAYSIM_COLUMNS]


class _AmlWriter:
    FORMATS = ["ACH", "Wire", "Credit Card", "Cheque", "Cash", "Reinvestment"]

    def __init__(self, rng, start):
        self.rng, self.start, self.rows = rng, start, []

    def add(self, minute, src, dst, amount, laundering, fmt=None):
        ts = self.start + timedelta(minutes=float(minute))
        fmt = fmt or self.FORMATS[self.rng.integers(0, len(self.FORMATS))]
        amt = round(float(amount), 2)
        self.rows.append((ts.strftime("%Y/%m/%d %H:%M"), src[0], src[1], dst[0], dst[1], amt,
                          "US Dollar", amt, "US Dollar", fmt, int(laundering)))


def generate_aml_like(n_accounts: int = 3000, days: int = 14, n_patterns: int = 260,
                      seed: int = 11) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    w = _AmlWriter(rng, datetime(2022, 9, 1))
    minutes = days * 24 * 60
    accts = [(f"{rng.integers(1, 80):03d}", f"8{i:08X}") for i in range(n_accounts)]
    merchants = accts[:120]
    companies = accts[120:150]
    people = accts[150:]
    contacts = {p: [people[j] for j in rng.integers(0, len(people), rng.integers(3, 12))]
                for p in people}

    def at(day_minute_base=None):
        d = rng.integers(0, days) if day_minute_base is None else day_minute_base
        return d * 1440 + int(rng.normal(14, 3.5) * 60) % 1440

    # --- normal behaviour --------------------------------------------------
    for p in people:
        for _ in range(rng.poisson(days * 1.4)):
            if rng.random() < 0.65:
                w.add(at(), p, merchants[rng.integers(0, len(merchants))], rng.lognormal(4, 1), 0,
                      "Credit Card")
            else:
                c = contacts[p][rng.integers(0, len(contacts[p]))]
                if c != p:
                    w.add(at(), p, c, rng.lognormal(5.5, 1.2), 0)
    # merchants: heavy fan-in, settle to a supplier once a day (slow pass-through)
    for mch in merchants:
        supplier = companies[rng.integers(0, len(companies))]
        for d in range(days):
            w.add(d * 1440 + 23 * 60 + rng.integers(0, 50), mch, supplier, rng.lognormal(8, 0.6), 0, "ACH")
    # payroll: companies fan-out salaries on two dates
    for comp in companies:
        staff = [people[j] for j in rng.integers(0, len(people), rng.integers(10, 30))]
        for d in (0, days // 2):
            for s in staff:
                w.add(d * 1440 + 9 * 60 + rng.integers(0, 30), comp, s, rng.lognormal(8, 0.3), 0, "ACH")
    # hard negative: people who receive salary and forward rent within hours
    for p in [people[j] for j in rng.integers(0, len(people), 150)]:
        landlord = people[rng.integers(0, len(people))]
        base = rng.integers(0, days - 1) * 1440 + 10 * 60
        amt = rng.lognormal(7.5, 0.4)
        w.add(base, companies[rng.integers(0, len(companies))], p, amt, 0, "ACH")
        w.add(base + rng.integers(60, 360), p, landlord, amt * rng.uniform(0.4, 0.8), 0)

    # hard negative: large one-off payments to a brand-new counterparty with no
    # matching inflow (house deposit, car, tuition) - honest but unusual
    for p in [people[j] for j in rng.integers(0, len(people), 400)]:
        w.add(at(), p, people[rng.integers(0, len(people))], rng.lognormal(10, 0.8), 0,
              rng.choice(["Wire", "ACH", "Cheque"]))
    # hard negative: small businesses collecting from several customers, paying out slowly
    for p in [people[j] for j in rng.integers(0, len(people), 120)]:
        base = rng.integers(0, days - 1) * 1440 + 9 * 60
        for _ in range(rng.integers(3, 7)):
            w.add(base + rng.integers(0, 480), people[rng.integers(0, len(people))], p, rng.lognormal(7, 0.6), 0)
        w.add(base + rng.integers(900, 1400), p, companies[rng.integers(0, len(companies))],
              rng.lognormal(7.5, 0.5), 0, "ACH")

    # --- laundering typologies --------------------------------------------
    def fresh(k):
        return [people[j] for j in rng.integers(0, len(people), k)]

    for _ in range(n_patterns):
        kind = rng.choice(["fan_in_mule", "chain", "scatter_gather", "fan_out", "cycle"],
                          p=[0.35, 0.2, 0.2, 0.15, 0.1])
        t0 = rng.integers(0, minutes - 2 * 1440)
        if kind == "fan_in_mule":
            victims = fresh(rng.integers(3, 9))
            mule, *down = fresh(1 + rng.integers(1, 3))
            total = 0.0
            for v in victims:
                a = rng.lognormal(8.5, 0.8)
                total += a
                w.add(t0 + rng.integers(0, 180), v, mule, a, 1)
            t_fwd = t0 + 180 + rng.integers(10, 120)
            for d in down:
                w.add(t_fwd, mule, d, total * rng.uniform(0.85, 0.98) / len(down), 1)
                if rng.random() < 0.6:   # second stage
                    w.add(t_fwd + rng.integers(15, 240), d, fresh(1)[0],
                          total * 0.8 / len(down), 1, "Cash")
        elif kind == "chain":
            hops = fresh(rng.integers(4, 7))
            amt, t = rng.lognormal(9, 0.7), t0
            for a, b in zip(hops, hops[1:]):
                w.add(t, a, b, amt, 1)
                amt *= rng.uniform(0.96, 0.995)
                t += rng.integers(5, 90)
        elif kind == "scatter_gather":
            src, sink = fresh(2)
            mids = fresh(rng.integers(3, 7))
            amt = rng.lognormal(9.5, 0.6)
            for m_ in mids:
                t = t0 + rng.integers(0, 60)
                w.add(t, src, m_, amt / len(mids), 1)
                w.add(t + rng.integers(20, 300), m_, sink, amt / len(mids) * 0.97, 1)
        elif kind == "fan_out":
            src, mule = fresh(2)
            outs = fresh(rng.integers(4, 10))
            amt = rng.lognormal(9.5, 0.6)
            w.add(t0, src, mule, amt, 1)
            for o in outs:
                w.add(t0 + rng.integers(10, 90), mule, o, amt * 0.95 / len(outs), 1)
        else:
            ring = fresh(rng.integers(3, 6))
            amt, t = rng.lognormal(9, 0.5), t0
            for a, b in zip(ring, ring[1:] + ring[:1]):
                w.add(t, a, b, amt, 1)
                t += rng.integers(10, 120)

    df = pd.DataFrame(w.rows, columns=AML_COLUMNS)
    return df.sort_values("Timestamp", kind="stable").reset_index(drop=True)
