import time

import pytest
from fastapi.testclient import TestClient

from fraudshield_ml.features import GraphFeatureBuilder, txn_features_single
from fraudshield_ml.service import app

H = 3600


def test_fan_in_and_rapid_forward_features():
    t0 = 1_700_000_000
    src = ["V1", "V2", "V3", "M"]
    dst = ["M", "M", "M", "D"]
    ts = [t0, t0 + 600, t0 + 1200, t0 + 1800]
    amt = [1000, 2000, 3000, 5800]
    f = GraphFeatureBuilder(src, dst, ts, amt).features("M", t0 + 1800)
    assert f["fan_in"] == 3 and f["fan_out"] == 1
    assert f["pass_through"] == pytest.approx(5800 / 6000)
    assert f["min_hold_minutes"] == pytest.approx(10)
    assert f["new_counterparty_share"] == 1.0


def test_chain_depth_and_window():
    t0 = 1_700_000_000
    b = GraphFeatureBuilder(["A", "B", "C", "X"], ["B", "C", "D", "D"],
                            [t0, t0 + 60, t0 + 120, t0 - 3 * 24 * H], [100, 99, 98, 5])
    assert b.features("D", t0 + 120)["upstream_depth"] == 3
    assert b.features("D", t0 + 120)["fan_in"] == 1     # X->D is outside the 24h window


def test_old_counterparty_is_not_new():
    t0 = 1_700_000_000
    b = GraphFeatureBuilder(["A", "A"], ["B", "B"], [t0 - 5 * 24 * H, t0], [10, 10])
    assert b.features("B", t0)["new_counterparty_share"] == 0.0


def test_txn_features_exclude_post_balances():
    X = txn_features_single(5000, "TRANSFER", 2, 5000, 0)
    assert X.loc[0, "drains_account"] == 1 and X.loc[0, "is_night"] == 1
    assert not any("newbalance" in c for c in X.columns)


@pytest.fixture(scope="module")
def client():
    return TestClient(app)


def test_health(client):
    assert client.get("/health").json()["status"] == "UP"


def test_mule_pattern_scores_higher_than_normal(client):
    now = time.time()
    normal = client.post("/score", json={"transaction": {
        "sender": "A", "receiver": "B", "amount": 50, "timestamp": now,
        "senderBalance": 10000, "receiverBalance": 5000}}).json()
    recent = [{"src": f"V{i}", "dst": "MULE", "amount": 20000, "timestamp": now - 3000 + i * 300}
              for i in range(6)]
    mule = client.post("/score", json={"transaction": {
        "sender": "MULE", "receiver": "OUT", "amount": 115000, "timestamp": now,
        "senderBalance": 120000, "receiverBalance": 0}, "recentTransactions": recent}).json()
    assert mule["senderFeatures"]["fan_in"] == 6
    assert mule["muleProbability"] > normal["muleProbability"]
    assert mule["reasons"]


def test_invalid_amount_rejected(client):
    r = client.post("/score", json={"transaction": {"sender": "A", "receiver": "B", "amount": -5,
                                                    "timestamp": 0, "senderBalance": 1, "receiverBalance": 1}})
    assert r.status_code == 422
