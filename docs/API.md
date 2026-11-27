# FraudShield REST API

Base URL: `http://localhost:8080/api`. Requests and responses are JSON. Errors look like this:

```json
{ "status": 422, "code": "INSUFFICIENT_FUNDS", "message": "Insufficient funds", "timestamp": "..." }
```

## Authentication and roles

Every endpoint except `POST /auth/login` needs `Authorization: Bearer <token>`. A missing or expired token gives
401. A role that may not use an endpoint gives 403. ADMIN may use every staff endpoint but not `/me`.

| Method | Path | Body | Result |
|---|---|---|---|
| POST | `/auth/login` | `{username, password}` | `{token, user: {username, displayName, role, accountNumber}}` |
| GET | `/auth/me` | – | The signed-in user |
| POST | `/auth/logout` | – | 204; the token stops working |

Roles: `CUSTOMER` (own account via `/me`), `ANALYST`, `INVESTIGATOR`, `ADMIN`.

## Customer portal (`CUSTOMER` only, own account)

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/me/overview` | – | Account, open warnings (e.g. money-mule warning), recent activity |
| GET | `/me/transactions` | – | Own transfers with plain-language status (no model internals) |
| GET | `/me/payees/{accountNumber}` | – | Masked payee name and state, to confirm the recipient |
| POST | `/me/transfers` | Header `Idempotency-Key`; `{toAccount, amount, description?}` | Same decisions as below; `demoOtp` in demo mode |
| POST | `/me/transfers/{id}/otp/verify` | `{code}` | Verified / attempts left |
| POST | `/me/transfers/{id}/otp/resend` | – | New OTP |
| POST | `/me/transfers/{id}/cancel` | – | Cancels a transfer waiting for OTP or review |
| POST | `/me/reports` | `{type, transactionId?, details}`; type is ASKED_TO_MOVE_MONEY, UNAUTHORISED_TRANSACTION, SUSPICIOUS_CREDIT or OTHER | Alert plus investigation case; returns `caseReference` |

## Investigation cases

| Method | Path | Roles | Result |
|---|---|---|---|
| GET | `/cases` | ANALYST, INVESTIGATOR | All cases |
| POST | `/cases` | ANALYST | `{accountNumber, title, summary?, priority}`: escalate an account |
| GET | `/cases/{id}` | INVESTIGATOR | Case file: subject account, trace from 14 days before the case was opened (victims, paths, endpoints, roles), alerts, transactions, account audit, `traceHours`. Opening it is itself audited |
| PATCH | `/cases/{id}` | INVESTIGATOR | `{status?, assignedTo?, findings?}` |

## Administration (`ADMIN`)

| Method | Path | Result |
|---|---|---|
| GET / POST | `/admin/users` | List users / create `{username, password, displayName, role, accountNumber (customers)}` |
| PATCH | `/admin/users/{id}/active` | `{active}`: enable or disable a login |
| GET | `/admin/settings` | Current risk weights, thresholds, OTP and hold settings |

## Staff endpoints and their roles

Accounts and transactions: read by ANALYST and INVESTIGATOR. Opening accounts, changing account status and
reviewing held transfers: ANALYST. Operations transfers and their OTP (`POST /transactions...`): ADMIN only.
Dashboard and model: ANALYST. Alerts: read by ANALYST and INVESTIGATOR; resolved by ANALYST. Graph and trace: ANALYST
and INVESTIGATOR. Audit log: INVESTIGATOR. Scenarios: ADMIN. ADMIN may call all of them.

## Accounts

| Method | Path | Body / params | Result |
|---|---|---|---|
| GET | `/accounts` | – | All accounts |
| POST | `/accounts` | `{holderName, state, phone?, initialBalance}` | 201, new account (number `FS##########`) |
| GET | `/accounts/{number}` | – | One account |
| PATCH | `/accounts/{number}/status` | `{status: ACTIVE\|UNDER_REVIEW\|FROZEN, note?}` | Updated account |
| GET | `/accounts/{number}/alerts` | – | Alerts about this account (including mule warnings) |

## Transactions

| Method | Path | Body / params | Result |
|---|---|---|---|
| POST | `/transactions` | Header `Idempotency-Key` (or body `idempotencyKey`); body `{fromAccount, toAccount, amount, description?}` | 201 new, or 200 + `Idempotent-Replay: true` for a duplicate |
| GET | `/transactions` | `status?`, `account?`, `page`, `size` | Page of transactions with risk details |
| GET | `/transactions/{id}` | – | One transaction with its risk assessment |
| GET | `/transactions/{id}/audit` | – | Audit trail of the transaction |
| POST | `/transactions/{id}/otp/verify` | `{code: "123456"}` | `{transaction, verified, attemptsRemaining, message}` |
| POST | `/transactions/{id}/otp/resend` | – | New challenge (demo mode returns `demoOtp`) |
| POST | `/transactions/{id}/review` | `{action: APPROVE\|REJECT, note?}` | Only for HELD; APPROVE executes |

Example transfer response:

```json
{
  "transaction": {
    "id": 42, "reference": "TXN3F9A1C2B7D10", "fromAccount": "FS5244536032", "toAccount": "FS5538273124",
    "amount": 189592.87, "status": "PENDING_MFA", "decision": "MFA", "riskScore": 0.2503,
    "risk": {
      "txnFraudProbability": 0.0125, "senderMuleProbability": 0.07, "receiverMuleProbability": 0.08,
      "anomalyScore": 0.14, "ruleScore": 0.35, "finalScore": 0.2503, "decision": "MFA", "mlAvailable": true,
      "rulesFired": ["RECEIVER_FAN_IN", "NEW_PAYEE_LARGE"],
      "reasons": ["Step-up policy: large new payee, account-draining transfer or fan-in receiver requires OTP",
                  "Receiver has been paid by 3 different accounts in 24h", "Large first-ever payment to this receiver"]
    }
  },
  "duplicate": false,
  "demoOtp": "481207",
  "message": "Additional verification required: enter the OTP sent to the registered phone"
}
```

Status codes: 400 for validation, missing idempotency key or same account. 404 for an unknown account or transaction.
409 for key reuse with a different payload, or a state that does not allow the action (for example, approving a
blocked transfer). 422 for insufficient funds or a frozen sender.

## Alerts, audit, graph, dashboard

| Method | Path | Params | Result |
|---|---|---|---|
| GET | `/alerts` | `status=OPEN\|RESOLVED`, `page`, `size` | Explained alerts |
| POST | `/alerts/{id}/resolve` | `{note?}` | Resolved alert |
| GET | `/audit` | `page`, `size` | Audit log, newest first |
| GET | `/graph` | `account?`, `hours=72` (max 2160 = 90 days), `depth=3` (max 6) | `{nodes[], edges[]}`. Nodes carry role, reasons and fan-in/out. Edges carry amount, timestamp, status and decision |
| GET | `/graph/trace/{number}` | `hours=72` (max 2160) | Upstream sources, time-ordered downstream paths, endpoints, roles, summary |
| GET | `/dashboard` | – | KPIs, decisions per hour, risk bands, risky accounts, ML status |
| GET | `/model` | – | Model version, thresholds, all evaluation metrics (proxied from ML) |
| GET | `/scenarios` | – | Available attack scenarios |
| POST | `/scenarios/{name}` | `mule-fan-in`, `layering-chain`, `normal-activity` | Step-by-step report of decisions |

## ML service (internal, port 8000)

| Method | Path | Body | Result |
|---|---|---|---|
| GET | `/health` | – | `{status, modelVersion}` |
| GET | `/model-info` | – | Metadata and `reports/metrics.json` |
| POST | `/score` | `{transaction: {sender, receiver, amount, type, timestamp, senderBalance, receiverBalance}, recentTransactions: [{src, dst, amount, timestamp}]}` | Probabilities, anomaly score, graph features of both parties, reasons |

Interactive docs for the ML service: http://localhost:8000/docs.
