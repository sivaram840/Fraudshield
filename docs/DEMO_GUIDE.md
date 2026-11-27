# FraudShield demo guide (about 15 minutes)

## The evening before

1. Run `scripts\setup-database.bat` once if you have not already (it asks for your MySQL root password).
2. Run `scripts\start-all.bat` and sign in once with each demo login, to check everything works.
3. In **Admin → Simulation lab**, run *Money-mule fan-in* once. Note the two logins it prints (for example
   `ravi-1a2b3c`). Running it fresh tomorrow also works, because each run creates new accounts.
4. Backup plan: take screenshots of each portal, in case the projector laptop has problems.

## 30 minutes before

- Start `scripts\start-all.bat`. Wait until the backend window says `Started FraudShieldApplication`.
- Open http://127.0.0.1:5173 (or http://localhost:5173). Use full-screen browser zoom at 90–100%.
- Keep the sign-in page open. All demo logins use the password `Demo@123`.

---

## 1. The problem (1 minute)

Say:
> "Criminals take over victims' bank accounts in different states and push the stolen money into an innocent
> person's account. They promise that person a commission to forward it within an hour. When police trace the money,
> the trail stops at that person. They get arrested but cannot identify the criminal. The real offender
> disappears. This is called a money mule scam."

Make three points:
1. Detection happens *after* the money has already moved on.
2. Investigators see one hop, not the whole flow.
3. The middle person, often a victim of social engineering, becomes the main suspect.

## 2. The solution (2 minutes): open `docs/ARCHITECTURE.md` or your slide

> "FraudShield checks every transfer *before* money moves. It combines three machine-learning models with explainable
> rules, and decides ALLOW, ask for OTP, HOLD for review, or BLOCK. Blocked and unreviewed transfers can never execute.
> It also traces money across accounts as a graph."

- **Architecture:** React frontend → Java Spring Boot backend → Python ML service → MySQL.
- **Models:**
  - A Random Forest on transactions (PaySim format) catches account takeover.
  - A Random Forest on graph features catches mule behaviour: many senders, fast forwarding, chains.
  - An Isolation Forest flags unusual behaviour.
- **Four portals**, because four kinds of people are involved: customers, bank analysts, police investigators and
  administrators.

## 3. Live demo (8 minutes)

### Step A: Admin runs the attack (1 min). Sign in as `admin`

- Go to **Simulation lab → Money-mule fan-in → Run scenario**.
- Say: *"This recreates the exact problem. Five victims in Maharashtra, Tamil Nadu, Karnataka, Delhi and West Bengal
  lose money through account takeover. Even if the attacker phished their OTP, the money reaches the intermediary,
  Ravi."*
- Point at the last step: **Intermediary forwards the money → BLOCKED**. *"When Ravi tries to forward it, as the
  criminal told him to, FraudShield blocks it. About ₹10 lakh stays in one traceable account instead of vanishing."*
- Note the `ravi-…` login it shows. Click **Open money-flow trace** and show the graph: five blue victims → orange
  intermediary → red dashed blocked transfer.

### Step B: The intermediary's view (1.5 min). Sign out, sign in as `ravi-…`

- Show the orange warning: *"Ravi is warned in plain language that this is a money-mule scam, before he commits a
  crime without knowing it."*
- Show "Account under security review" and the onward transfer marked "Blocked for your safety".
- Click **Report fraud → Someone asked me to receive money and pass it on**, type a sentence, and submit.
  *"His own report goes straight to investigators as evidence that he acted in good faith."*

### Step C: The bank's fraud analyst (2 min). Sign in as `analyst`

- **Dashboard:** funds stopped, open alerts, decisions per hour, suspicious accounts.
- **Alerts:** each alert explains itself, e.g. *"Receiver has been paid by 5 different accounts in 24h"* and
  *"Forwarding 95% of money received 0 minutes ago"*.
- Open **Transactions**, click the blocked transfer, and show the **risk breakdown**: the score from each model, the
  rules that fired, and the audit trail. *"Every decision is explainable, which regulators expect."*
- Open Ravi's account and click **Escalate to investigators**.

### Step D: The investigator (2 min). Sign in as `investigator`

- **Case files:** both cases appear, Ravi's own report and the analyst's escalation.
- Open a case:
  - Subject details and the assessment **Likely unwitting mule**, with reasons: a 6-day-old account, no history,
    money from 5 strangers.
  - **Likely victims (5)**, with states and amounts. *"The police know exactly whom to contact for refunds."*
  - The graph and the blocked onward path. *"Instead of stopping at Ravi, investigators see the whole flow."*
- Update the status to In progress, add findings, and click **Print case report**.

### Step E: A normal customer (1.5 min). Sign in as `aarav`

- Send ₹500 to Diya's account (look it up in Admin → Accounts, or use any seeded account number). It goes through
  instantly. *"Normal customers feel no friction."*
- Send ₹75,000 to a new payee. An **OTP** is requested. Show the SMS simulator and enter it. Optionally show that
  three wrong OTPs reject the transfer, or cancel a pending transfer.

## 4. Results (2 minutes): open `docs/TESTING.md`

- **Tests:** 7 ML unit tests, 27 backend tests and 34 end-to-end tests, all passing on the integrated stack with MySQL.
- **Proven guarantees:** blocked and held transfers never execute. No duplicate payments (idempotency keys). With 25
  simultaneous transfers, exactly the right number succeed and no money is lost or created.
- **Model results** (say honestly that this is synthetic data):
  - Transaction Random Forest: F1 0.59 vs 0.45 for logistic regression.
  - Mule detection: rules alone F1 0.02, Random Forest 0.36, **combined 0.42**. *"Rules alone flag almost everyone; the
    learned graph model is far more precise, and the combination is best. That is why the design is hybrid."*

## 5. Limitations and future work (1 minute)

- All data is simulated. PaySim and IBM AML are synthetic too. The numbers show the system works, **not** real-world
  accuracy.
- Thresholds were tuned on simulated data. A single-bank view misses transfers across banks.
- **Future work:** train on the real IBM AML and SAML-D files; graph neural networks; SHAP explanations; sharing data
  across banks (e.g. NPCI); a real SMS gateway.

---

## Likely viva questions

| Question | Answer |
|---|---|
| Why not just a classifier? | The evidence of a mule is in the *relationships and timing*: many senders, fast forwarding, chains. One transaction row does not contain it, so we use graph features. |
| Why are your accuracy numbers lower than PaySim papers (around 99%)? | Those papers use post-transaction balance columns that leak the answer. We removed them deliberately, so our numbers are honest. |
| How do you stop double payments? | Every transfer carries an idempotency key with a unique database constraint, and execution locks both accounts in a fixed order. This is tested with concurrent requests. |
| What if the ML service is down? | It fails safe: decisions use rules only, and every transfer needs at least an OTP. |
| Why HOLD instead of BLOCK? | A hold stops the money while a human checks. If nobody approves it, it expires without executing, so honest customers are not hurt by a wrong block. |
| How do you know Ravi is a victim and not the criminal? | Behaviour: a new, quiet account that suddenly receives from strangers and forwards at once. It is a *likely* label that supports an investigator; it is not a verdict. His own report adds evidence. |
| Is this secure? | Passwords are PBKDF2-hashed, sessions are 8-hour tokens, every API checks the role, and customers can only touch their own account. A real bank would add HTTPS, staff MFA and an identity provider. |
| Which datasets? | PaySim for transactions, and IBM Transactions for AML (NeurIPS 2023) for laundering patterns. SAML-D is planned for a cross-dataset test. The scripts load the real files when they are placed in `ml-service/data/raw`. |

## If something goes wrong

| Problem | Fix |
|---|---|
| Backend window shows "Access denied" for MySQL | Run `scripts\setup-database.bat` again |
| Dashboard says "ML offline" | Check the ML window. The app still works on rules (point out the fail-safe) |
| Port already in use | Close old FraudShield windows, then run `start-all.bat` again |
| Signed out unexpectedly | Sessions last 8 hours; sign in again |
| Browser cannot open the page | Use http://127.0.0.1:5173 instead of localhost |
| Money-flow graph empty or shows a yellow "window widened" notice | The demo data is older than 72 hours. Run *Money-mule fan-in* in the Simulation lab just before the demo for fresh activity |
