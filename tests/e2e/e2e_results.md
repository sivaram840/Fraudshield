# End-to-end test results

Run 2026-11-26T12:35:56 against http://localhost:8080 (frontend http://127.0.0.1:5173).

**34 / 34 passed.**

| # | Test | Result | Detail |
|---|---|---|---|
| 1 | Security: API refuses requests without sign-in | PASS | status=401 |
| 2 | Security: wrong password refused | PASS | status=401 |
| 3 | Stack: backend reachable, MySQL seeded, ML online | PASS | accounts=40 mlOnline=True |
| 4 | Stack: backend proxies ML model metrics | PASS | model version 20261030151526 |
| 5 | Stack: React dev server proxies /api to backend | PASS | http://127.0.0.1:5173/api/dashboard -> 200 |
| 6 | Normal transfer is allowed and executed | PASS | decision=ALLOW status=COMPLETED score=0.0097 mlAvailable=True |
| 7 | Balances updated consistently | PASS | A=48500.0 B=2500.0 |
| 8 | Duplicate request returns original, charges once | PASS | first=201 second=200 replay-header=true balanceA=47800.0 |
| 9 | Same key with different payload is rejected | PASS | status=409 code=IDEMPOTENCY_KEY_REUSED |
| 10 | Insufficient funds refused | PASS | status=422 |
| 11 | Self-transfer refused | PASS | status=400 code=SAME_ACCOUNT |
| 12 | Transfer to frozen account is BLOCKED | PASS | decision=BLOCK status=BLOCKED |
| 13 | Blocked transaction can never execute | PASS | approve attempt -> 409, balance unchanged=True |
| 14 | Setup inflow completes (with MFA if requested) | PASS | decision=MFA status=COMPLETED |
| 15 | Rapid onward transfer is held or blocked | PASS | decision=HOLD status=HELD rules=['RAPID_ONWARD', 'NEW_ACCOUNT_PASS_THROUGH', 'DRAINS_ACCOUNT', 'NEW_PAYEE_LARGE'] |
| 16 | Unresolved hold does not execute | PASS | dest balance=0.0 |
| 17 | Analyst reject keeps money in place | PASS | status=REJECTED |
| 18 | Failed MFA x3 rejects the transfer | PASS | remaining=[2, 1, 0] final=REJECTED |
| 19 | Correct OTP executes the transfer | PASS | status=COMPLETED |
| 20 | Scenario: mule fan-in onward transfer is stopped | PASS | Rs 724054.82 reached the intermediary, but the onward transfer was BLOCKED, so the money stays in a traceable account. The intermediary was warned and flagged as a likely unwitting mule; the trace lists every victim for refunds. [MFA/COMPLETED@0.1683; MFA/COMPLETED@0.082; MFA/COMPLETED@0.2806; MFA/COMPLETED@0.3334; MFA/COMPLETED@0.3938; BLOCK/BLOCKED@0.5518] |
| 21 | Graph trace: intermediary classified, sources listed | PASS | role=LIKELY_UNWITTING_MULE upstream=5 :: FS8852973351 received Rs 724054.82 from 5 account(s); Rs 0 moved on, Rs 687852.08 was stopped (blocked/held). 0 downstream endpoint(s) identified for investigators. |
| 22 | Explained alerts raised incl. mule warning to intermediary | PASS | types=['FAN_IN', 'HIGH_RISK_TRANSACTION', 'MULE_WARNING', 'RAPID_ONWARD_TRANSFER'] |
| 23 | Money-flow graph returns accounts, directions, amounts, timestamps | PASS | nodes=7 edges=6 |
| 24 | Scenario: multi-stage layering chain is interrupted | PASS | Chain stopped at hop 2 (HELD): the layering was interrupted. [MFA/COMPLETED@0.0788; HOLD/HELD@0.4751] |
| 25 | Scenario: normal customer activity passes | PASS | 3 of 3 ordinary transfers completed without friction. [ALLOW/COMPLETED@0.054; ALLOW/COMPLETED@0.0573; ALLOW/COMPLETED@0.1721] |
| 26 | Portals: each role limited to its own functions | PASS | customer sees own account; customer->accounts 403, investigator->review 403, analyst->admin 403 |
| 27 | Customer portal: send money from own account | PASS | status=COMPLETED text='Sent' balance 150097.0 -> 149597.0 |
| 28 | Customer portal: no model internals exposed | PASS | customer response has plain status only |
| 29 | Customer portal: intermediary sees the mule-scam warning | PASS | ravi-16df3a: warnings=['MULE_WARNING'] status=UNDER_REVIEW |
| 30 | Customer self-report becomes an investigator case | PASS | CASE-D3D8B4 visible to investigator=True |
| 31 | Analyst escalates the mule account to investigators | PASS | CASE-CE3201 by analyst |
| 32 | Investigator case file: role, victims, alerts, trail | PASS | role=LIKELY_UNWITTING_MULE victims=5 alerts=8 |
| 33 | Investigator updates case status and findings | PASS | status=IN_PROGRESS |
| 34 | Audit log records requests, decisions, executions, duplicates, alerts | PASS | missing=[] |
