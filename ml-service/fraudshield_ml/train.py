"""Train and evaluate every FraudShield model.

    python -m fraudshield_ml.train            # real Kaggle files if present, else synthetic
    python -m fraudshield_ml.train --max-rows 2000000

Outputs:
    models/txn_rf.joblib, models/mule_rf.joblib, models/behaviour_iforest.joblib
    models/metadata.json   thresholds, feature lists, explanation statistics
    reports/metrics.json   every metric, machine readable
    reports/metrics.md     the same as tables for the project report
"""
from __future__ import annotations

import argparse
import json
import platform
import time
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
import sklearn
from sklearn.ensemble import IsolationForest, RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import (average_precision_score, confusion_matrix, f1_score,
                             precision_score, recall_score, roc_auc_score, roc_curve)
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from . import datasets
from .features import GRAPH_FEATURES, TXN_FEATURES, GraphFeatureBuilder, txn_features_frame

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"
REPORTS = ROOT / "reports"
SEED = 42


def evaluate(y_true, score, threshold) -> dict:
    y_true = np.asarray(y_true)
    pred = (score >= threshold).astype(int)
    tn, fp, fn, tp = confusion_matrix(y_true, pred, labels=[0, 1]).ravel()
    fpr, tpr, _ = roc_curve(y_true, score)
    return {
        "threshold": round(float(threshold), 4),
        "precision": round(float(precision_score(y_true, pred, zero_division=0)), 4),
        "recall": round(float(recall_score(y_true, pred, zero_division=0)), 4),
        "f1": round(float(f1_score(y_true, pred, zero_division=0)), 4),
        "pr_auc": round(float(average_precision_score(y_true, score)), 4),
        "roc_auc": round(float(roc_auc_score(y_true, score)), 4),
        "recall_at_1pct_fpr": round(float(np.interp(0.01, fpr, tpr)), 4),
        "confusion_matrix": {"tn": int(tn), "fp": int(fp), "fn": int(fn), "tp": int(tp)},
        "support": {"positive": int(y_true.sum()), "negative": int(len(y_true) - y_true.sum())},
    }


def best_f1_threshold(y_true, score) -> float:
    grid = np.unique(np.quantile(score, np.linspace(0.5, 0.9995, 400)))
    f1s = [f1_score(y_true, (score >= t).astype(int), zero_division=0) for t in grid]
    return float(grid[int(np.argmax(f1s))])


def time_split(df: pd.DataFrame, time_col: str, train=0.6, val=0.15):
    df = df.sort_values(time_col, kind="stable").reset_index(drop=True)
    a, b = int(len(df) * train), int(len(df) * (train + val))
    return df.iloc[:a], df.iloc[a:b], df.iloc[b:]


def explanation_stats(X: pd.DataFrame, y, importances) -> dict:
    y = np.asarray(y)
    return {f: {"importance": round(float(imp), 4),
                "median_pos": float(np.median(X[f].to_numpy()[y == 1])) if y.sum() else 0.0,
                "median_neg": float(np.median(X[f].to_numpy()[y == 0]))}
            for f, imp in zip(X.columns, importances)}


def rf() -> RandomForestClassifier:
    return RandomForestClassifier(n_estimators=200, min_samples_leaf=2, class_weight="balanced_subsample",
                                  n_jobs=-1, random_state=SEED)


def train_transaction_model(max_rows):
    df, source = datasets.load_paysim(max_rows=max_rows)
    df = df.assign(_y=df["isFraud"].astype(int))
    tr, va, te = time_split(df, "step", 0.6, 0.15)
    Xtr, Xva, Xte = (txn_features_frame(d) for d in (tr, va, te))
    model = rf().fit(Xtr, tr["_y"])
    thr = best_f1_threshold(va["_y"], model.predict_proba(Xva)[:, 1])
    rf_metrics = evaluate(te["_y"], model.predict_proba(Xte)[:, 1], thr)

    lr = make_pipeline(StandardScaler(), LogisticRegression(max_iter=2000, class_weight="balanced"))
    lr.fit(Xtr, tr["_y"])
    lr_thr = best_f1_threshold(va["_y"], lr.predict_proba(Xva)[:, 1])
    lr_metrics = evaluate(te["_y"], lr.predict_proba(Xte)[:, 1], lr_thr)

    stats = explanation_stats(Xtr, tr["_y"], model.feature_importances_)
    info = {"source": source, "rows": {"train": len(tr), "validation": len(va), "test": len(te)},
            "split": "time-based on PaySim step (60% / 15% / 25%)",
            "excluded_leaky_columns": ["newbalanceOrig", "newbalanceDest", "isFlaggedFraud"],
            "random_forest": rf_metrics, "logistic_regression_baseline": lr_metrics}
    return model, thr, stats, info


def rule_score(X: pd.DataFrame) -> np.ndarray:
    """Hand-written mule rule used as a baseline in the ablation."""
    return ((X["fan_in"] >= 3).astype(float) * 0.4
            + (X["pass_through"] >= 0.8).astype(float) * 0.3
            + (X["min_hold_minutes"] <= 120).astype(float) * 0.2
            + (X["upstream_depth"] >= 2).astype(float) * 0.1).to_numpy()


def anomaly_norm(raw, p50, p99):
    return np.clip((raw - p50) / max(p99 - p50, 1e-9), 0, 1)


def train_graph_models(max_rows):
    edges, source = datasets.load_aml_edges(max_rows=max_rows)
    t0 = time.time()
    builder = GraphFeatureBuilder(edges["src"], edges["dst"], edges["ts"], edges["amount"])
    snaps = datasets.account_snapshots(edges, builder)
    build_secs = time.time() - t0
    tr, va, te = time_split(snaps, "ts", 0.6, 0.15)
    Xtr, Xva, Xte = tr[GRAPH_FEATURES], va[GRAPH_FEATURES], te[GRAPH_FEATURES]

    mule = rf().fit(Xtr, tr["label"])
    thr = best_f1_threshold(va["label"], mule.predict_proba(Xva)[:, 1])
    p_te = mule.predict_proba(Xte)[:, 1]

    iso = IsolationForest(n_estimators=300, contamination="auto", random_state=SEED)
    iso.fit(Xtr[tr["label"] == 0])
    raw_tr = -iso.score_samples(Xtr[tr["label"] == 0])
    p50, p99 = float(np.quantile(raw_tr, 0.5)), float(np.quantile(raw_tr, 0.99))
    a_va = anomaly_norm(-iso.score_samples(Xva), p50, p99)
    a_te = anomaly_norm(-iso.score_samples(Xte), p50, p99)

    r_va, r_te = rule_score(Xva), rule_score(Xte)
    c_va = 0.6 * mule.predict_proba(Xva)[:, 1] + 0.2 * a_va + 0.2 * r_va
    c_te = 0.6 * p_te + 0.2 * a_te + 0.2 * r_te

    ablation = {
        "rules_only": evaluate(te["label"], r_te, best_f1_threshold(va["label"], r_va)),
        "isolation_forest_only": evaluate(te["label"], a_te, best_f1_threshold(va["label"], a_va)),
        "random_forest_graph_features": evaluate(te["label"], p_te, thr),
        "combined_0.6rf_0.2if_0.2rules": evaluate(te["label"], c_te, best_f1_threshold(va["label"], c_va)),
    }

    cross = None
    samld = datasets.load_samld_edges(max_rows=max_rows)
    if samld is not None:
        b2 = GraphFeatureBuilder(samld["src"], samld["dst"], samld["ts"], samld["amount"])
        s2 = datasets.account_snapshots(samld, b2)
        cross = {"note": "trained on IBM AML, tested on SAML-D without retraining",
                 "random_forest": evaluate(s2["label"], mule.predict_proba(s2[GRAPH_FEATURES])[:, 1], thr)}

    stats = explanation_stats(Xtr, tr["label"], mule.feature_importances_)
    info = {"source": source, "edges": len(edges), "laundering_edges": int(edges["label"].sum()),
            "mule_accounts": len(datasets.mule_accounts(edges)),
            "snapshots": {"train": len(tr), "validation": len(va), "test": len(te)},
            "feature_build_seconds": round(build_secs, 1),
            "split": "time-based on transaction timestamp (60% / 15% / 25%)",
            "ablation": ablation, "cross_dataset": cross}
    return mule, thr, iso, (p50, p99), stats, info


def write_markdown(report: dict, path: Path):
    def table(rows: dict) -> str:
        head = "| Model | Precision | Recall | F1 | PR-AUC | ROC-AUC | Recall@1%FPR | TP | FP | FN | TN |\n" \
               "|---|---|---|---|---|---|---|---|---|---|---|\n"
        body = ""
        for name, m in rows.items():
            cm = m["confusion_matrix"]
            body += (f"| {name} | {m['precision']} | {m['recall']} | {m['f1']} | {m['pr_auc']} | "
                     f"{m['roc_auc']} | {m['recall_at_1pct_fpr']} | {cm['tp']} | {cm['fp']} | "
                     f"{cm['fn']} | {cm['tn']} |\n")
        return head + body

    t, g = report["transaction_model"], report["graph_models"]
    md = [f"# FraudShield model evaluation\n\nGenerated {report['generated_at']} "
          f"(scikit-learn {report['environment']['sklearn']}, seed {SEED}).\n",
          "> **Data caveat:** " + report["caveat"] + "\n",
          "## 1. Transaction fraud model (Random Forest)\n",
          f"Data: {t['source']}. Split: {t['split']}. Rows: {t['rows']}.\n",
          f"Leaky columns excluded: {', '.join(t['excluded_leaky_columns'])}.\n",
          table({"Random Forest": t["random_forest"],
                 "Logistic Regression (baseline)": t["logistic_regression_baseline"]}),
          "\n## 2. Money-mule account model and ablation\n",
          f"Data: {g['source']}. Edges: {g['edges']} ({g['laundering_edges']} laundering), "
          f"mule accounts: {g['mule_accounts']}. Snapshots: {g['snapshots']}. Split: {g['split']}.\n",
          table(g["ablation"])]
    if g["cross_dataset"]:
        md += ["\n## 3. Cross-dataset test\n", g["cross_dataset"]["note"] + "\n",
               table({"Random Forest": g["cross_dataset"]["random_forest"]})]
    else:
        md += ["\n## 3. Cross-dataset test\n",
               "Not run: place SAML-D.csv in data/raw/ and retrain to produce it.\n"]
    path.write_text("\n".join(md), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--max-rows", type=int, default=None, help="cap rows read from real CSVs")
    args = ap.parse_args()
    MODELS.mkdir(exist_ok=True)
    REPORTS.mkdir(exist_ok=True)

    print("Training transaction model ...")
    txn, txn_thr, txn_stats, txn_info = train_transaction_model(args.max_rows)
    print("  RF test:", {k: txn_info["random_forest"][k] for k in ("precision", "recall", "f1", "pr_auc")})
    print("Training graph models ...")
    mule, mule_thr, iso, (p50, p99), mule_stats, g_info = train_graph_models(args.max_rows)
    print("  Mule RF test:", {k: g_info["ablation"]["random_forest_graph_features"][k]
                              for k in ("precision", "recall", "f1", "pr_auc")})

    synthetic_used = "synthetic" in txn_info["source"] or "synthetic" in g_info["source"]
    caveat = ("At least one model was trained on SYNTHETIC data generated by this project. These numbers "
              "show the pipeline works end to end; they are not evidence of real-world accuracy."
              if synthetic_used else
              "PaySim and IBM AML are themselves simulated datasets; results may not transfer to real banks.")

    joblib.dump(txn, MODELS / "txn_rf.joblib", compress=3)
    joblib.dump(mule, MODELS / "mule_rf.joblib", compress=3)
    joblib.dump(iso, MODELS / "behaviour_iforest.joblib", compress=3)
    version = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    meta = {"model_version": version,
            "txn": {"features": TXN_FEATURES, "threshold": txn_thr, "stats": txn_stats,
                    "source": txn_info["source"]},
            "mule": {"features": GRAPH_FEATURES, "threshold": mule_thr, "stats": mule_stats,
                     "source": g_info["source"]},
            "iforest": {"features": GRAPH_FEATURES, "p50": p50, "p99": p99}}
    (MODELS / "metadata.json").write_text(json.dumps(meta, indent=2), encoding="utf-8")

    report = {"generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
              "model_version": version, "caveat": caveat,
              "environment": {"python": platform.python_version(), "sklearn": sklearn.__version__},
              "transaction_model": txn_info, "graph_models": g_info}
    (REPORTS / "metrics.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    write_markdown(report, REPORTS / "metrics.md")
    print(f"Saved models (version {version}) and reports/metrics.md")


if __name__ == "__main__":
    main()
