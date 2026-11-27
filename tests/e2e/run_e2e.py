"""End-to-end test of the running stack: React proxy -> Spring Boot -> Python ML -> MySQL.

Start MySQL, the ML service, the backend (and optionally the React dev server), then:
    python tests/e2e/run_e2e.py [--backend http://localhost:8080] [--frontend http://localhost:5173]

Writes tests/e2e/e2e_results.md and e2e_results.json. Uses only the standard library.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS: list[dict] = []


class Api:
    def __init__(self, base, token=None):
        self.base = base.rstrip("/")
        self.token = token

    def login(self, username, password="Demo@123"):
        st, body, _ = self.call("POST", "/api/auth/login", {"username": username, "password": password})
        if st != 200:
            raise RuntimeError(f"login {username} failed: {st} {body}")
        return Api(self.base, body["token"])

    def call(self, method, path, body=None, key=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        if key:
            req.add_header("Idempotency-Key", key)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                text = r.read().decode()
                return r.status, json.loads(text) if text else None, dict(r.headers)
        except urllib.error.HTTPError as e:
            text = e.read().decode()
            return e.code, json.loads(text) if text else None, dict(e.headers)

    def get(self, path):
        return self.call("GET", path)[1]

    def account(self, name, balance, state="Delhi"):
        return self.call("POST", "/api/accounts", {"holderName": name, "state": state,
                                                   "initialBalance": balance})[1]

    def send(self, frm, to, amount, key=None):
        return self.call("POST", "/api/transactions", {"fromAccount": frm["accountNumber"],
                                                       "toAccount": to["accountNumber"], "amount": amount},
                         key or str(uuid.uuid4()))

    def send_cleared(self, frm, to, amount):
        """Setup transfer: completes the OTP step if the risk engine asks for one."""
        st, r, h = self.send(frm, to, amount)
        if r["transaction"]["status"] == "PENDING_MFA":
            r = {"transaction": self.call("POST", f"/api/transactions/{r['transaction']['id']}/otp/verify",
                                          {"code": r["demoOtp"]})[1]["transaction"]}
        return r["transaction"]

    def balance(self, acct):
        return float(self.get(f"/api/accounts/{acct['accountNumber']}")["balance"])


def check(name, ok, detail):
    RESULTS.append({"test": name, "passed": bool(ok), "detail": detail})
    print(("PASS " if ok else "FAIL ") + name + " :: " + detail)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", default="http://localhost:8080")
    ap.add_argument("--frontend", default="http://localhost:5173")
    args = ap.parse_args()
    public = Api(args.backend)
    st, _, _ = public.call("GET", "/api/dashboard")
    check("Security: API refuses requests without sign-in", st == 401, f"status={st}")
    st, _, _ = public.call("POST", "/api/auth/login", {"username": "admin", "password": "wrong-password"})
    check("Security: wrong password refused", st == 401, f"status={st}")
    api = public.login("admin")
    tag = uuid.uuid4().hex[:4]

    # 1. Stack health
    dash = api.get("/api/dashboard")
    check("Stack: backend reachable, MySQL seeded, ML online",
          dash["accounts"] > 0 and dash["mlOnline"], f"accounts={dash['accounts']} mlOnline={dash['mlOnline']}")
    model = api.get("/api/model")
    check("Stack: backend proxies ML model metrics", "modelVersion" in model, f"model version {model['modelVersion']}")
    try:
        proxied = urllib.request.Request(args.frontend + "/api/dashboard", headers={"Authorization": "Bearer " + api.token})
        with urllib.request.urlopen(proxied, timeout=10) as r:
            ok = r.status == 200 and "accounts" in json.loads(r.read())
        check("Stack: React dev server proxies /api to backend", ok, args.frontend + "/api/dashboard -> 200")
    except Exception as e:  # noqa: BLE001
        check("Stack: React dev server proxies /api to backend", False, f"frontend not reachable: {e}")

    # 2. Normal transfer
    a = api.account(f"E2E Normal A {tag}", 50000)
    b = api.account(f"E2E Normal B {tag}", 1000)
    st, r, _ = api.send(a, b, 1500)
    t = r["transaction"]
    check("Normal transfer is allowed and executed", st == 201 and t["status"] == "COMPLETED",
          f"decision={t['decision']} status={t['status']} score={t['riskScore']} mlAvailable={t['risk']['mlAvailable']}")
    check("Balances updated consistently", api.balance(a) == 48500 and api.balance(b) == 2500,
          f"A={api.balance(a)} B={api.balance(b)}")

    # 3. Duplicate request
    key = str(uuid.uuid4())
    st1, r1, _ = api.send(a, b, 700, key)
    st2, r2, h2 = api.send(a, b, 700, key)
    check("Duplicate request returns original, charges once",
          st2 == 200 and r2["duplicate"] and r1["transaction"]["id"] == r2["transaction"]["id"]
          and api.balance(a) == 47800,
          f"first={st1} second={st2} replay-header={h2.get('Idempotent-Replay')} balanceA={api.balance(a)}")
    st3, r3, _ = api.send(a, b, 999, key)
    check("Same key with different payload is rejected", st3 == 409, f"status={st3} code={r3['code']}")

    # 4. Validation
    st, r, _ = api.send(b, a, 10_000_000)
    check("Insufficient funds refused", st == 422 and r["code"] == "INSUFFICIENT_FUNDS", f"status={st}")
    st, r, _ = api.send(a, a, 10)
    check("Self-transfer refused", st == 400, f"status={st} code={r['code']}")

    # 5. BLOCK: frozen receiver
    f = api.account(f"E2E Frozen {tag}", 0)
    api.call("PATCH", f"/api/accounts/{f['accountNumber']}/status", {"status": "FROZEN", "note": "e2e"})
    before = api.balance(a)
    st, r, _ = api.send(a, f, 500)
    t = r["transaction"]
    check("Transfer to frozen account is BLOCKED", t["decision"] == "BLOCK" and t["status"] == "BLOCKED",
          f"decision={t['decision']} status={t['status']}")
    st_rev, _, _ = api.call("POST", f"/api/transactions/{t['id']}/review", {"action": "APPROVE"})
    check("Blocked transaction can never execute", api.balance(a) == before and st_rev == 409,
          f"approve attempt -> {st_rev}, balance unchanged={api.balance(a) == before}")

    # 6. HOLD: rapid onward transfer of fresh inflow
    h_src = api.account(f"E2E Hold Source {tag}", 200000)
    h_mid = api.account(f"E2E Hold Mid {tag}", 100)
    h_dst = api.account(f"E2E Hold Dest {tag}", 0)
    setup = api.send_cleared(h_src, h_mid, 60000)
    check("Setup inflow completes (with MFA if requested)", setup["status"] == "COMPLETED",
          f"decision={setup['decision']} status={setup['status']}")
    st, r, _ = api.send(h_mid, h_dst, 58000)
    t = r["transaction"]
    held = t["status"] in ("HELD", "BLOCKED")
    check("Rapid onward transfer is held or blocked", held,
          f"decision={t['decision']} status={t['status']} rules={t['risk']['rulesFired']}")
    if t["status"] == "HELD":
        check("Unresolved hold does not execute", api.balance(h_dst) == 0, f"dest balance={api.balance(h_dst)}")
        _, rr, _ = api.call("POST", f"/api/transactions/{t['id']}/review", {"action": "REJECT", "note": "e2e"})
        check("Analyst reject keeps money in place", rr["status"] == "REJECTED" and api.balance(h_dst) == 0,
              f"status={rr['status']}")

    # 7. MFA: an established account draining itself to a new large payee
    m_src = api.account(f"E2E Mfa Source {tag}", 120000)
    m_dst = api.account(f"E2E Mfa Dest {tag}", 0)
    mfa_seen = None
    for amount in (60000, 115000, 119000):
        st, r, _ = api.send(m_src, m_dst, amount)
        if r["transaction"]["status"] == "PENDING_MFA":
            mfa_seen = r
            break
        if r["transaction"]["status"] == "COMPLETED":
            break
    if mfa_seen:
        tid, code = mfa_seen["transaction"]["id"], mfa_seen["demoOtp"]
        wrong = "000000" if code != "000000" else "111111"
        outs = [api.call("POST", f"/api/transactions/{tid}/otp/verify", {"code": wrong})[1] for _ in range(3)]
        check("Failed MFA x3 rejects the transfer", outs[-1]["transaction"]["status"] == "REJECTED",
              f"remaining={[o['attemptsRemaining'] for o in outs]} final={outs[-1]['transaction']['status']}")
        st, r, _ = api.send(m_src, m_dst, mfa_seen["transaction"]["amount"])
        if r["transaction"]["status"] == "PENDING_MFA":
            _, ok, _ = api.call("POST", f"/api/transactions/{r['transaction']['id']}/otp/verify", {"code": r["demoOtp"]})
            check("Correct OTP executes the transfer", ok["transaction"]["status"] == "COMPLETED",
                  f"status={ok['transaction']['status']}")
    else:
        check("MFA path reached with live ML", False,
              "no PENDING_MFA decision for the draining transfers; MFA is still covered by backend tests")

    # 8. Attack scenarios through the full pipeline
    for name in ("mule-fan-in", "layering-chain", "normal-activity"):
        st, rep, _ = api.call("POST", f"/api/scenarios/{name}")
        steps = "; ".join(f"{s['decision']}/{s['status']}@{s['riskScore']}" for s in rep["steps"])
        if name == "mule-fan-in":
            fwd = [s for s in rep["steps"] if s["label"].startswith("Intermediary")]
            ok = (not fwd) or fwd[0]["status"] != "COMPLETED"
            check("Scenario: mule fan-in onward transfer is stopped", ok, f"{rep['outcome']} [{steps}]")
            trace = api.get(f"/api/graph/trace/{rep['focusAccount']}?hours=24")
            check("Graph trace: intermediary classified, sources listed",
                  trace["account"]["role"] in ("LIKELY_UNWITTING_MULE", "MULE_PATTERN_REVIEW") and len(trace["upstream"]) >= 3,
                  f"role={trace['account']['role']} upstream={len(trace['upstream'])} :: {trace['summary']}")
            alerts = api.get(f"/api/accounts/{rep['focusAccount']}/alerts")
            types = sorted({x['type'] for x in alerts})
            check("Explained alerts raised incl. mule warning to intermediary", "MULE_WARNING" in types, f"types={types}")
            g = api.get(f"/api/graph?account={rep['focusAccount']}&hours=24&depth=2")
            check("Money-flow graph returns accounts, directions, amounts, timestamps",
                  len(g["nodes"]) >= 5 and all({"source", "target", "amount", "timestamp"} <= e.keys() for e in g["edges"]),
                  f"nodes={len(g['nodes'])} edges={len(g['edges'])}")
        elif name == "layering-chain":
            ok = any(s["status"] != "COMPLETED" for s in rep["steps"])
            check("Scenario: multi-stage layering chain is interrupted", ok, f"{rep['outcome']} [{steps}]")
        else:
            ok = all(s["status"] == "COMPLETED" for s in rep["steps"])
            check("Scenario: normal customer activity passes", ok, f"{rep['outcome']} [{steps}]")

    # 9. Role-based portals
    customer = public.login("aarav")
    analyst = public.login("analyst")
    investigator = public.login("investigator")
    ov = customer.get("/api/me/overview")
    st_c, _, _ = customer.call("GET", "/api/accounts")
    st_i, _, _ = investigator.call("POST", "/api/transactions/1/review", {"action": "APPROVE"})
    st_a, _, _ = analyst.call("GET", "/api/admin/users")
    check("Portals: each role limited to its own functions",
          ov["account"]["holderName"] == "Aarav Sharma" and st_c == 403 and st_i == 403 and st_a == 403,
          f"customer sees own account; customer->accounts {st_c}, investigator->review {st_i}, analyst->admin {st_a}")

    diya = next(x for x in api.get("/api/accounts") if x["holderName"] == "Diya Patel")
    before = float(customer.get("/api/me/overview")["account"]["balance"])
    st, r, _ = customer.call("POST", "/api/me/transfers", {"toAccount": diya["accountNumber"], "amount": 500}, str(uuid.uuid4()))
    t = r["transaction"]
    if t["status"] == "PENDING_MFA":
        t = customer.call("POST", f"/api/me/transfers/{t['id']}/otp/verify", {"code": r["demoOtp"]})[1]["transaction"]
    after = float(customer.get("/api/me/overview")["account"]["balance"])
    check("Customer portal: send money from own account", t["status"] == "COMPLETED" and round(before - after, 2) == 500,
          f"status={t['status']} text='{t['statusText']}' balance {before} -> {after}")
    check("Customer portal: no model internals exposed", "ruleScore" not in json.dumps(r), "customer response has plain status only")

    st, rep, _ = api.call("POST", "/api/scenarios/mule-fan-in")
    mule_user = rep["demoLogins"][0].split(" ")[0]
    mule = public.login(mule_user)
    mov = mule.get("/api/me/overview")
    check("Customer portal: intermediary sees the mule-scam warning",
          any(w["type"] == "MULE_WARNING" for w in mov["warnings"]) and mov["account"]["status"] == "UNDER_REVIEW",
          f"{mule_user}: warnings={[w['type'] for w in mov['warnings']]} status={mov['account']['status']}")
    st, rr, _ = mule.call("POST", "/api/me/reports", {"type": "ASKED_TO_MOVE_MONEY",
                                                       "details": "A caller promised 2% commission to forward money within an hour"})
    cases = investigator.get("/api/cases")
    case = next((c for c in cases if c["reference"] == rr["caseReference"]), None)
    check("Customer self-report becomes an investigator case", st == 201 and case is not None and case["source"] == "CUSTOMER_REPORT",
          f"{rr['caseReference']} visible to investigator={case is not None}")
    st, esc, _ = analyst.call("POST", "/api/cases", {"accountNumber": rep["focusAccount"],
                                                      "title": "Fan-in mule pattern, onward transfer blocked", "priority": "HIGH"})
    check("Analyst escalates the mule account to investigators", st == 201, f"{esc['reference']} by {esc['createdBy']}")
    detail = investigator.get(f"/api/cases/{case['id']}")
    victims = [n for n in detail["trace"]["involved"] if n["role"] in ("POSSIBLE_VICTIM", "SOURCE_REVIEW")]
    check("Investigator case file: role, victims, alerts, trail",
          detail["trace"]["account"]["role"] == "LIKELY_UNWITTING_MULE" and len(victims) >= 5 and len(detail["alerts"]) > 0,
          f"role={detail['trace']['account']['role']} victims={len(victims)} alerts={len(detail['alerts'])}")
    upd = investigator.call("PATCH", f"/api/cases/{case['id']}", {"status": "IN_PROGRESS", "assignedTo": "investigator",
                                                                 "findings": "Statement recorded; intermediary cooperating"})[1]
    check("Investigator updates case status and findings", upd["status"] == "IN_PROGRESS", f"status={upd['status']}")

    # 10. Audit trail
    audit = api.get("/api/audit?size=500")
    actions = {x["action"] for x in audit["items"]}
    need = {"TRANSFER_REQUESTED", "RISK_DECISION", "EXECUTED", "DUPLICATE_REQUEST", "ALERT_RAISED"}
    check("Audit log records requests, decisions, executions, duplicates, alerts", need <= actions,
          f"missing={sorted(need - actions)}")

    passed = sum(r["passed"] for r in RESULTS)
    stamp = datetime.now().isoformat(timespec="seconds")
    (HERE / "e2e_results.json").write_text(json.dumps({"run_at": stamp, "passed": passed, "total": len(RESULTS),
                                                       "results": RESULTS}, indent=2), encoding="utf-8")
    md = [f"# End-to-end test results\n\nRun {stamp} against {args.backend} (frontend {args.frontend}).\n",
          f"**{passed} / {len(RESULTS)} passed.**\n", "| # | Test | Result | Detail |", "|---|---|---|---|"]
    for i, r in enumerate(RESULTS, 1):
        md.append(f"| {i} | {r['test']} | {'PASS' if r['passed'] else 'FAIL'} | {r['detail'].replace('|', '/')} |")
    (HERE / "e2e_results.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print(f"\n{passed}/{len(RESULTS)} passed")
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()
