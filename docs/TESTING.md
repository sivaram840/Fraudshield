# Testing and verified results

Verified on 30 Oct 2026: Windows 11, Java 21 (compiled for Java 17), Python 3.14, scikit-learn 1.9.1, Node 22,
MySQL 8.0.43.

## 1. ML service: `ml-service/tests/test_ml.py` (pytest), 7 / 7 passed

| Test | What it proves |
|---|---|
| fan-in and rapid-forward features | 3 senders → fan_in 3, pass-through 0.97, hold 10 minutes, all counterparties new |
| chain depth and window | A→B→C→D gives depth 3. Edges older than 24h are excluded |
| old counterparty is not new | Relationship history is respected |
| transaction features exclude post balances | No leaky PaySim columns reach the model |
| health endpoint | Models load |
| mule pattern scores higher than normal | Live `/score` ranks a fan-in + forward pattern above a normal payment and explains it |
| invalid amount rejected | Request validation (HTTP 422) |

## 2. Backend: 27 / 27 passed

`PortalAccessTest.java` (6 tests): login and sign-out, wrong password, forged token; each role limited to its own
functions; customer transfer from own account with OTP and plain statuses; another customer cannot touch it; customer
cancels a pending transfer (no money moves); customer report reaches investigators as a case; analyst escalation;
admin user creation with the account-link rule.

`CaseTraceWindowTest.java` (3 tests): a case file keeps its evidence as it ages, because the trace window runs from
14 days before the case was opened until now, capped at 90 days.

`TransactionFlowTest.java` (18 tests):

Spring Boot integration tests with H2 in MySQL mode. The ML client is mocked so each risk level can be forced.

| Test | Requirement |
|---|---|
| normalTransferIsAllowedAndBalancesMoveExactlyOnce | Normal transactions, consistent balances |
| largeFirstPaymentToNewPayeeRequiresOtp | Step-up MFA policy |
| frozenReceiverIsBlockedAndNoMoneyMoves | BLOCK decision, prevention |
| blockedTransactionCanNeverBeExecutedOrReviewed | Blocked transfers never execute (approve / OTP return 409) |
| mfaWithCorrectOtpExecutes | MFA / OTP verification |
| failedMfaThreeTimesRejectsAndRaisesAlert | Failed MFA |
| mlUnavailableFallsBackToRulesWithMinimumMfa | Fail-safe without ML |
| heldTransactionDoesNotExecuteAndExpiresUnresolved | Unresolved holds never execute |
| heldTransactionExecutesOnlyAfterAnalystApproval | HOLD review workflow |
| duplicateRequestReturnsOriginalAndChargesOnce | Duplicate prevention (HTTP layer) |
| concurrentDuplicatesCreateOneTransaction | 8 parallel replays → 1 transaction, 1 debit |
| concurrentTransfersNeverOverdrawAndConserveMoney | 25 parallel Rs 1,000 transfers from Rs 10,000 → exactly 10 succeed, total conserved |
| validationErrors | Missing key, negative amount, self-transfer, insufficient funds |
| fanInFollowedByRapidOnwardTransferIsBlockedAndMuleIsWarned | Fan-in + rapid onward transfer → BLOCK, account under review, mule warning, trace role |
| receiverFanInRuleFiresOnThirdDistinctSender | Fan-in pattern |
| multiStageChainIsDetected | Multi-stage money flow |
| muleScenarioStopsTheOnwardTransfer | Attack scenario end to end in the backend |
| normalScenarioIsFrictionless | No false alarms on ordinary activity |

## 3. End-to-end on the live stack: `tests/e2e/run_e2e.py`, 34 / 34 passed

Includes the portal story: the API refuses unsigned requests; each role is limited; a customer sends money with OTP;
the scenario's intermediary signs in and sees the mule warning, then reports the scam; the report becomes an
investigator case; the analyst escalates; the investigator's case file shows the role, 5 victims and alerts; the
investigator updates the case.

React dev server proxy → Spring Boot → Python ML service (real trained models) → MySQL 8. Full output:
[tests/e2e/e2e_results.md](../tests/e2e/e2e_results.md). Highlights from the verified run:

- The mule fan-in scenario moved Rs 10.7 lakh from 5 victims (5 states) into the intermediary. The onward transfer of
  Rs 10.2 lakh was **BLOCKED**. The intermediary was classified `LIKELY_UNWITTING_MULE`, warned and put under review,
  and the trace listed all 5 sources.
- The layering chain was stopped at hop 2 (HOLD, score 0.49).
- Normal activity: 3 of 3 transfers completed with scores between 0.05 and 0.17.
- MFA: 3 wrong codes gave attempts remaining [2, 1, 0], then REJECTED. The correct code gave COMPLETED.
- Rapid onward transfer of fresh inflow: HOLD (rules RAPID_ONWARD, NEW_ACCOUNT_PASS_THROUGH, DRAINS_ACCOUNT,
  NEW_PAYEE_LARGE). It did not execute while unresolved, and an analyst rejection kept the money in place.

## 4. Model evaluation: `ml-service/reports/metrics.md`

Time-based splits (train on earlier data, test on later data). The decision threshold is chosen on the validation
split only.

**Transaction model (synthetic PaySim schema, leaky columns removed):**

| Model | Precision | Recall | F1 | PR-AUC | ROC-AUC | Recall@1%FPR | TP / FP / FN / TN |
|---|---|---|---|---|---|---|---|
| Random Forest | 0.564 | 0.626 | 0.593 | 0.596 | 0.984 | 0.641 | 251 / 194 / 150 / 21302 |
| Logistic Regression baseline | 0.476 | 0.424 | 0.449 | 0.381 | 0.962 | 0.431 | 170 / 187 / 231 / 21309 |

**Money-mule account model, ablation (synthetic IBM-AML schema with hard negatives):**

| Model | Precision | Recall | F1 | PR-AUC | ROC-AUC | Recall@1%FPR |
|---|---|---|---|---|---|---|
| Rules only | 0.008 | 0.618 | 0.016 | 0.007 | 0.587 | 0.007 |
| Isolation Forest only | 0.037 | 0.212 | 0.063 | 0.026 | 0.761 | 0.115 |
| Random Forest on graph features | 0.257 | 0.582 | 0.357 | 0.381 | 0.966 | 0.606 |
| Combined (0.6 RF + 0.2 IF + 0.2 rules) | **0.333** | 0.558 | **0.417** | **0.404** | 0.879 | 0.600 |

How to read this:

- Accuracy is not reported because it is meaningless at about 0.5% positives. PR-AUC and positive-class F1 are the
  main metrics.
- Rules alone catch many mules but flood analysts with false positives. The learned graph model is far more precise,
  and combining all three gives the best F1. This is the core argument for the hybrid design.
- These numbers come from data generated by this project. They show the pipeline works; they are **not** real-world
  accuracy. Retrain with the Kaggle files for dataset-backed results.

## Issues found and fixed during testing

1. **Concurrency:** under parallel load, Hibernate raised optimistic-lock failures because a transaction row loaded a
   stale account before the row lock was taken. Fixed by locking the account rows first. The concurrency test now
   proves exactly-once execution.
2. **Label noise and train/serve skew in the mule model:** every account's first-ever receipt scored about 0.99. A
   mule's first laundered inflow had been labelled positive even though it is indistinguishable at that moment, and
   the backend sent only 48h of history. Fixed by dropping those ambiguous samples, sending 30-day party history, and
   adding realistic look-alikes (one-off large payments, small businesses) to the normal data. Metrics dropped as a
   result, and they are more honest for it.
3. **Step-up policy:** with live ML scores, large new-payee payments reached ALLOW. An explicit MFA policy was added.
4. **Read-only transaction on MySQL:** opening a case file writes an audit entry, which MySQL refused inside a
   read-only database transaction (H2 had allowed it). Found by the end-to-end run and fixed.
5. **Graph usability:** scrolling the page over an embedded graph zoomed it out of view. Embedded graphs no longer
   zoom on scroll, and every graph has a Fit button.
