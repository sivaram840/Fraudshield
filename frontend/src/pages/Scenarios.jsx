import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, inr } from '../api.js';
import { Badge, ErrorBox, Loading, useLoad } from '../components.jsx';

export default function Scenarios() {
  const [list, err] = useLoad(() => api.scenarios());
  const [running, setRunning] = useState(null);
  const [report, setReport] = useState(null);
  const [error, setError] = useState(null);

  async function run(id) {
    setRunning(id);
    setError(null);
    setReport(null);
    try {
      setReport(await api.runScenario(id));
    } catch (e) {
      setError(e);
    } finally {
      setRunning(null);
    }
  }

  return (
    <>
      <header className="page-head">
        <h1>Attack scenarios</h1>
      </header>
      <p className="muted">
        Each scenario creates fresh simulated accounts and sends every transfer through the real pipeline (ML scoring,
        rules, MFA, holds and execution), then reports what FraudShield decided.
      </p>
      <ErrorBox error={err || error} />
      {!list ? <Loading /> : (
        <div className="grid3">
          {list.map((s) => (
            <section key={s.id} className="card">
              <h3>{s.title}</h3>
              <p>{s.description}</p>
              <button className="primary" disabled={!!running} onClick={() => run(s.id)}>
                {running === s.id ? 'Running…' : 'Run scenario'}
              </button>
            </section>
          ))}
        </div>
      )}
      {report && (
        <section className="card">
          <h2>{report.title}</h2>
          <p className="outcome">{report.outcome}</p>
          {report.demoLogins?.length > 0 && (
            <p className="muted">
              See the customer side: sign out and sign in as {report.demoLogins.map((l, i) => <span key={l}>{i ? ' or ' : ''}<code>{l.split(' ')[0]}</code> {l.substring(l.indexOf(' ') + 1)}</span>)} with the demo password.
            </p>
          )}
          <div className="row gap">
            <Link className="button primary" to={`/graph?account=${report.focusAccount}&hours=24`}>Open money-flow trace</Link>
            <Link className="button" to="/alerts">See alerts</Link>
            <Link className="button" to="/review">Review queue</Link>
          </div>
          <table>
            <thead><tr><th>#</th><th>Step</th><th className="num">Amount</th><th className="num">Risk</th><th>Decision</th><th>Status</th><th>Note</th></tr></thead>
            <tbody>
              {report.steps.map((s, i) => (
                <tr key={s.reference}>
                  <td>{i + 1}</td>
                  <td>{s.label}<div className="muted small mono">{s.reference}</div></td>
                  <td className="num">{inr(s.amount)}</td>
                  <td className="num">{s.riskScore == null ? '—' : Number(s.riskScore).toFixed(2)}</td>
                  <td><Badge value={s.decision} /></td>
                  <td><Badge value={s.status} /></td>
                  <td className="small">{s.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </>
  );
}
