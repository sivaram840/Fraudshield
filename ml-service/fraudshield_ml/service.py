"""FastAPI scoring service called by the Spring Boot backend.

    uvicorn fraudshield_ml.service:app --port 8000
"""
from __future__ import annotations

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .scoring import Scorer

app = FastAPI(title="FraudShield ML service", version="1.0")
_scorer: Scorer | None = None


def scorer() -> Scorer:
    global _scorer
    if _scorer is None:
        try:
            _scorer = Scorer()
        except FileNotFoundError as e:
            raise HTTPException(503, f"Models not trained yet: run python -m fraudshield_ml.train ({e})")
    return _scorer


class Txn(BaseModel):
    sender: str
    receiver: str
    amount: float = Field(gt=0)
    type: str = "TRANSFER"
    timestamp: float                      # epoch seconds
    senderBalance: float
    receiverBalance: float


class Edge(BaseModel):
    src: str
    dst: str
    amount: float
    timestamp: float


class ScoreRequest(BaseModel):
    transaction: Txn
    recentTransactions: list[Edge] = []


@app.get("/health")
def health():
    return {"status": "UP", "modelVersion": scorer().meta["model_version"]}


@app.get("/model-info")
def model_info():
    return scorer().model_info()


@app.post("/score")
def score(req: ScoreRequest):
    return scorer().score(req.transaction.model_dump(), [e.model_dump() for e in req.recentTransactions])
