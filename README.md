# FraudShield

**Graph-based money-flow tracing and AI-assisted money-mule detection and prevention.**

A full working prototype that tackles a common Indian cyber-fraud pattern. A criminal takes over victims' bank
accounts in several states and pushes the stolen money into an innocent person's account, promising them a small
commission. That person (the "money mule") forwards the money onward. The police then trace the money only as far
as the mule, who knows nothing about the real criminal.

FraudShield gives **each person involved their own portal**:

| Portal | Who uses it | What they do | How they benefit |
|---|---|---|---|
| **Customer banking** | Bank customers: potential victims and the person being used as a go-between | Send money (with OTP), see their transactions, get a money-mule warning, cancel pending transfers, report a scam | Victims' money is stopped before it disappears. The go-between is warned *before* forwarding, and their self-report is on record. |
| **Fraud Analyst console** | The bank's fraud-monitoring team | Dashboard, review queue for held transfers, explained alerts, freeze or review accounts, escalate cases | They catch mule networks early and focus on real cases. |
| **Investigation portal** | Police / cyber-crime cell (1930 helpline, state cyber cells) | Case files, money-flow tracing past the mule, victims and leads, findings, printable case report | They follow the money to the organiser instead of stopping at the innocent go-between. |
| **Administration** | Bank IT / compliance | Users and roles, risk settings, audit log, simulation lab, ML model metrics | Every decision is explainable and audited. |

FraudShield scores every transfer with machine learning, graph analysis and explainable rules before any money moves.
It decides **ALLOW / MFA (OTP) / HOLD / BLOCK** and never executes blocked or unresolved held transfers. It traces
money across accounts, warns the likely unwitting mule, and shows investigators every victim and every downstream
account.

> Simulated data only. FraudShield never connects to real bank accounts. Metrics measured on synthetic data do **not**
> prove real-world money-mule detection accuracy (see [Limitations](#limitations)).

---

## 1. What you need installed

| Tool | Version used to verify | Check with |
|---|---|---|
| Java JDK | 17 or newer (verified on 21) | `java -version` |
| Python | 3.10 or newer (verified on 3.14) | `python --version` |
| Node.js | 18 or newer (verified on 22) | `node -v` |
| MySQL Server | 8.x (verified on 8.0.43) | `mysql --version` |

Maven is **not** needed: the backend includes the Maven wrapper (`mvnw` / `mvnw.cmd`).

## 2. Run it (Windows, about 5 minutes the first time)

All commands are run from the `fraudshield` folder.

**Step 1: Create the database and its user** (once). Enter your MySQL root password when asked:

```bash
scripts\setup-database.bat
```

The script finds MySQL even when `mysql` is not on your PATH. Alternatively, open `database/setup.sql` in MySQL
Workbench and run it.

This creates database `fraudshield` and user `fraudshield` / password `fraudshield_dev`. The tables are created
automatically when the backend starts, and 12 demo customers with transaction history are seeded.

**Step 2: Install packages and train the models** (once):

```bash
scripts\setup.bat
```

This installs the Python packages, trains and evaluates all models (writes `ml-service/models/` and
`ml-service/reports/metrics.md`) and installs the frontend packages. Trained models are already included in the
ZIP, so this step mainly installs dependencies.

**Step 3: Start everything:**

```bash
scripts\start-all.bat
```

Three windows open: the ML service (port 8000), the backend (port 8080) and the frontend (port 5173). The browser opens
**http://127.0.0.1:5173** automatically (http://localhost:5173 works too). The backend takes about 30 seconds the
first time.

**Step 4: Sign in.** The sign-in page lists the four portals; click one to fill in its demo login.

| Username | Portal | Notes |
|---|---|---|
| `aarav` (also `diya`, `kabir`, `lakshmi`, `rohan`, `sneha`, `vikram`, `fatima`, `neha`, `kiran`) | Customer banking | Seeded customers with transaction history |
| `analyst` | Fraud Analyst console | |
| `investigator` | Investigation portal | |
| `admin` | Administration (can also use every staff screen) | |

All demo logins use the password **`Demo@123`** (local prototype only). The admin can create more logins under
**Users & roles**.

**Step 5: Demonstrate the problem statement, portal by portal:**

1. **Admin → Simulation lab →** *Money-mule fan-in*, then select **Run scenario**. Five victims in five states lose
   money to an account takeover. The money pools in "Ravi Kumar (recruited intermediary)", and his onward transfer
   is **BLOCKED**. The report shows two new customer logins (for example `ravi-1a2b3c` and `anita-1a2b3c`).
2. **Sign in as the intermediary** (`ravi-…`). His home page shows the money-mule scam warning, the account is "under
   security review", and the forwarding attempt shows "Blocked for your safety". Use **Report fraud → Someone asked
   me to receive money and pass it on** to report the caller.
3. **Sign in as `analyst`.** The dashboard, alerts and review queue show the case. Open the intermediary's account and
   use **Escalate to investigators**.
4. **Sign in as `investigator`.** Both cases appear (the customer's own report and the analyst's escalation). Open
   one: you see the subject's details, the *likely unwitting mule* assessment and its reasons, all 5 victims with
   states and amounts, the graph, the blocked onward path and every alert. Update the status and findings, then
   **Print case report**.
5. **Sign in as a customer** (`aarav`), send a large amount to a new payee and enter the OTP from the SMS simulator.
   Then try a wrong OTP three times, or cancel a pending transfer.

### Run each service by hand (any OS)

```bash
cd ml-service && python -m uvicorn fraudshield_ml.service:app --port 8000
```

```bash
cd backend && mvnw spring-boot:run
```

```bash
cd frontend && npm install && npm run dev
```

On macOS / Linux, `scripts/start-all.sh` starts all three. Use `./mvnw` instead of `mvnw`.

### Configuration

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/fraudshield?...` | MySQL connection |
| `DB_USER` / `DB_PASSWORD` | `fraudshield` / `fraudshield_dev` | MySQL credentials |
| `ML_URL` | `http://localhost:8000` | ML service address |

Risk weights, decision thresholds, OTP lifetime, hold expiry and demo OTP mode are set in
`backend/src/main/resources/application.yml`.

### If something does not work

| Problem | Fix |
|---|---|
| The page does not open | Use http://127.0.0.1:5173 and check that all three windows are still open |
| "Access denied" for MySQL in the backend window | Run `scripts\setup-database.bat` again |
| Money-flow graph is empty | The graph shows the last 72 hours by default. When that is empty it widens automatically (up to 90 days) and says so. Run a scenario in the Simulation lab for fresh activity |
| "ML offline" on the dashboard | Check the ML service window; decisions continue on rules only, with OTP for every transfer |
| Port already in use | Close old FraudShield windows and run `start-all.bat` again |

## 3. Run the tests

```bash
scripts\run-tests.bat
```

This runs the ML unit tests, the backend integration tests and the end-to-end suite (all services must be running for
the end-to-end part). Latest verified results:

| Suite | Result |
|---|---|
| ML service (pytest): features, scoring API, validation | **7 / 7 passed** |
| Backend (JUnit + MockMvc, H2 in MySQL mode, ML mocked), including login and role-access tests | **27 / 27 passed** |
| End-to-end on the live stack (React proxy → Spring Boot → Python ML → MySQL 8), all four portals | **34 / 34 passed** |

Details: [docs/TESTING.md](docs/TESTING.md) and [tests/e2e/e2e_results.md](tests/e2e/e2e_results.md).

## 4. Using the real Kaggle datasets

Put the files in `ml-service/data/raw/` ([instructions](ml-service/data/raw/README.md)) and retrain:

```bash
cd ml-service && python -m fraudshield_ml.train
```

The trainer picks the real files up automatically. Without them it uses the project's synthetic generators, which
follow the same column formats, and says so in every report.

## 5. Project layout

```
fraudshield/
├── frontend/        React 18 + Vite: sign-in, customer banking portal (src/customer), analyst / investigator / admin consoles
├── backend/         Java 17 / Spring Boot 3.5: REST API, login and roles (security/), risk engine, MFA, holds, cases, tracing
├── ml-service/      Python: dataset prep, synthetic data, training/evaluation, FastAPI scoring service
│   ├── fraudshield_ml/   features.py, synthetic.py, datasets.py, train.py, scoring.py, service.py
│   ├── models/           trained Random Forests, Isolation Forest, metadata.json
│   └── reports/          metrics.json, metrics.md (precision, recall, F1, PR-AUC, confusion matrices, ablation)
├── database/        setup.sql, schema.sql, sample_data.sql
├── tests/e2e/       run_e2e.py + latest results
├── scripts/         setup.bat, start-all.bat, run-tests.bat, start-all.sh
└── docs/            ARCHITECTURE.md, API.md, TESTING.md, PROJECT_REPORT.md
```

## 6. Documentation

- [Architecture and design](docs/ARCHITECTURE.md): components, request flow, risk scoring, state machine.
- [REST API reference](docs/API.md).
- [Testing and results](docs/TESTING.md).
- [Project report material](docs/PROJECT_REPORT.md): abstract, methodology, results, limitations, references.
- [Demo guide](docs/DEMO_GUIDE.md): a 15-minute presentation script, viva questions and troubleshooting.
- [Learning guide](docs/LEARNING_GUIDE.md): every concept behind the project (fraud, ML, graphs, backend,
  security, frontend, database), a code map, a glossary and self-test questions.

## Limitations

- All transactions, accounts and attack scenarios are simulated. PaySim and the IBM AML dataset are themselves
  synthetic, and this repository's default models are trained on the project's own generators (identical schema)
  until you add the Kaggle files. The metrics show the pipeline works. They are not evidence of real-world accuracy.
- The rule thresholds and score weights were chosen and tuned on simulated data.
- OTPs appear on screen in demo mode instead of being sent by SMS. Sign-in uses PBKDF2-hashed passwords and
  8-hour bearer tokens, which is suitable for a prototype. A real deployment would add HTTPS, MFA for staff, account
  lockout and a real identity provider.
- Role labels such as "likely unwitting mule" describe behaviour, not guilt. They support an investigator and do not
  replace one.
