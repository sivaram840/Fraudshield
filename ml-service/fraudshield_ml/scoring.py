"""Loads the trained models and scores one candidate transaction."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd

from .features import GRAPH_FEATURES, GraphFeatureBuilder, txn_features_single

MODELS = Path(__file__).resolve().parent.parent / "models"
REPORTS = Path(__file__).resolve().parent.parent / "reports"

LABELS = {
    "amount_log": "large amount", "is_transfer": "transfer type", "hour": "unusual hour",
    "is_night": "night-time transaction", "amt_to_balance": "amount is large relative to balance",
    "drains_account": "transfer empties the sender's account",
    "orig_balance_log": "sender balance profile", "orig_balance_zero": "sender balance was zero",
    "dest_balance_zero": "receiver account was empty", "dest_balance_log": "receiver balance profile",
    "fan_in": "many distinct senders in 24h (fan-in)", "fan_out": "many distinct receivers in 24h (fan-out)",
    "in_count": "many incoming transfers", "out_count": "many outgoing transfers",
    "in_amount_log": "large incoming volume", "out_amount_log": "large outgoing volume",
    "pass_through": "money forwarded on almost as fast as it arrives",
    "min_hold_minutes": "funds held only briefly before moving on",
    "upstream_depth": "part of a multi-hop transfer chain",
    "new_counterparty_share": "mostly new, never-seen counterparties",
}


class Scorer:
    def __init__(self, models_dir: Path = MODELS):
        self.meta = json.loads((models_dir / "metadata.json").read_text(encoding="utf-8"))
        self.txn = joblib.load(models_dir / "txn_rf.joblib")
        self.mule = joblib.load(models_dir / "mule_rf.joblib")
        self.iso = joblib.load(models_dir / "behaviour_iforest.joblib")

    @staticmethod
    def _factors(values: dict, stats: dict, top: int = 3) -> list[str]:
        """Features whose value sits on the fraud side of the training medians,
        ranked by Random Forest importance. A lightweight, model-agnostic
        explanation (not SHAP)."""
        scored = []
        for f, s in stats.items():
            pos, neg, v = s["median_pos"], s["median_neg"], values[f]
            if pos == neg:
                continue
            toward_fraud = (v - neg) / (pos - neg)
            if toward_fraud >= 0.5:
                scored.append((s["importance"] * min(toward_fraud, 2), LABELS.get(f, f)))
        return [label for _, label in sorted(scored, reverse=True)[:top]]

    def score(self, txn: dict, recent: list[dict]) -> dict:
        t = float(txn["timestamp"])
        hour = datetime.fromtimestamp(t, tz=timezone.utc).hour
        Xt = txn_features_single(txn["amount"], txn.get("type", "TRANSFER"), hour,
                                 txn["senderBalance"], txn["receiverBalance"])
        p_txn = float(self.txn.predict_proba(Xt)[:, 1][0])

        edges = [e for e in recent if float(e["timestamp"]) <= t]
        edges.append({"src": txn["sender"], "dst": txn["receiver"], "amount": txn["amount"], "timestamp": t})
        b = GraphFeatureBuilder([e["src"] for e in edges], [e["dst"] for e in edges],
                                [float(e["timestamp"]) for e in edges], [float(e["amount"]) for e in edges])
        fs = b.features(txn["sender"], t)
        fr = b.features(txn["receiver"], t)
        Xg = pd.DataFrame([fs, fr], columns=GRAPH_FEATURES)
        p_mule = self.mule.predict_proba(Xg)[:, 1]
        m = self.meta["iforest"]
        raw = -self.iso.score_samples(Xg)
        anomaly = np.clip((raw - m["p50"]) / max(m["p99"] - m["p50"], 1e-9), 0, 1)

        reasons = []
        if p_txn >= self.meta["txn"]["threshold"]:
            reasons += [f"Transaction model: {r}" for r in self._factors(Xt.iloc[0].to_dict(), self.meta["txn"]["stats"])]
        for who, p, f in (("Sender", p_mule[0], fs), ("Receiver", p_mule[1], fr)):
            if p >= self.meta["mule"]["threshold"]:
                reasons += [f"{who} mule model: {r}" for r in self._factors(f, self.meta["mule"]["stats"])]
        return {
            "txnFraudProbability": round(p_txn, 4),
            "senderMuleProbability": round(float(p_mule[0]), 4),
            "receiverMuleProbability": round(float(p_mule[1]), 4),
            "muleProbability": round(float(p_mule.max()), 4),
            "anomalyScore": round(float(anomaly.max()), 4),
            "senderFeatures": fs, "receiverFeatures": fr,
            "reasons": reasons,
            "modelVersion": self.meta["model_version"],
        }

    def model_info(self) -> dict:
        report = REPORTS / "metrics.json"
        return {"modelVersion": self.meta["model_version"],
                "txnSource": self.meta["txn"]["source"], "muleSource": self.meta["mule"]["source"],
                "thresholds": {"txn": self.meta["txn"]["threshold"], "mule": self.meta["mule"]["threshold"]},
                "metrics": json.loads(report.read_text(encoding="utf-8")) if report.exists() else None}
