import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, inr, when } from '../api.js';
import { useCan } from '../auth.jsx';
import { Badge, ErrorBox, Loading, useLoad } from '../components.jsx';

export default function AccountDetail() {
  const { number } = useParams();
  const [data, err, reload] = useLoad(
    () => Promise.all([api.account(number), api.accountAlerts(number), api.transactions({ account: number, size: 100 })]),
    [number],
  );
  const canAct = useCan('ANALYST');
  const [escalate, setEscalate] = useState(null);
  const [escalated, setEscalated] = useState(null);
  if (err) return <ErrorBox error={err} />;
  if (!data) return <Loading />;
  const [a, alerts, txns] = data;
  const muleWarning = alerts.find((x) => x.type === 'MULE_WARNING' && x.status === 'OPEN');

  async function sendCase(e) {
    e.preventDefault();
    const c = await api.escalate({ accountNumber: a.accountNumber, ...escalate });
    setEscalated(`Case ${c.reference} sent to investigators.`);
    setEscalate(null);
  }

  async function setStatus(status) {
    await api.setAccountStatus(number, status, 'Changed from account page');
    reload();
  }

  return (
    <>
      <header className="page-head">
        <div>
          <h1>{a.holderName}</h1>
          <p className="muted">
            {a.accountNumber} · {a.state} · opened {when(a.createdAt)}
          </p>
        </div>
        <div className="row">
          <Badge value={a.status} />
          <span className="big">{inr(a.balance)}</span>
        </div>
      </header>

      {muleWarning && (
        <div className="mule-warning">
          <b>Warning shown to this customer:</b> {muleWarning.message}
        </div>
      )}

      <div className="row gap">
        <Link className="button" to={`/graph?account=${a.accountNumber}`}>Trace money flow</Link>
        {canAct && a.status !== 'ACTIVE' && <button onClick={() => setStatus('ACTIVE')}>Mark active</button>}
        {canAct && a.status !== 'UNDER_REVIEW' && <button onClick={() => setStatus('UNDER_REVIEW')}>Put under review</button>}
        {canAct && a.status !== 'FROZEN' && <button className="danger" onClick={() => setStatus('FROZEN')}>Freeze</button>}
        {canAct && !escalate && (
          <button className="primary" onClick={() => setEscalate({ title: `Suspected money-mule activity: ${a.holderName}`,
            summary: alerts.slice(0, 3).map((x) => x.message).join(' | '), priority: 'HIGH' })}>
            Escalate to investigators
          </button>
        )}
        {escalated && <span className="status-chip ok"><i />{escalated}</span>}
      </div>

      {escalate && (
        <form className="card form" onSubmit={sendCase}>
          <h3>Escalate to investigators</h3>
          <label>Title<input required maxLength={200} value={escalate.title}
            onChange={(e) => setEscalate({ ...escalate, title: e.target.value })} /></label>
          <label>Summary for the investigator<textarea rows={4} maxLength={2000} value={escalate.summary}
            onChange={(e) => setEscalate({ ...escalate, summary: e.target.value })} /></label>
          <label>Priority
            <select value={escalate.priority} onChange={(e) => setEscalate({ ...escalate, priority: e.target.value })}>
              <option>CRITICAL</option><option>HIGH</option><option>MEDIUM</option><option>LOW</option>
            </select>
          </label>
          <div className="row"><button className="primary">Send case</button><button type="button" onClick={() => setEscalate(null)}>Cancel</button></div>
        </form>
      )}

      <section className="card">
        <h3>Alerts ({alerts.length})</h3>
        {alerts.length === 0 ? <p className="muted">None.</p> : (
          <table>
            <thead><tr><th>Type</th><th>Severity</th><th>Message</th><th>Status</th><th>When</th></tr></thead>
            <tbody>
              {alerts.map((x) => (
                <tr key={x.id}>
                  <td><Badge value={x.type} /></td>
                  <td><Badge value={x.severity} /></td>
                  <td>{x.message}</td>
                  <td><Badge value={x.status} /></td>
                  <td>{when(x.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card">
        <h3>Transactions</h3>
        <table>
          <thead><tr><th>When</th><th>Direction</th><th>Counterparty</th><th className="num">Amount</th><th>Decision</th><th>Status</th></tr></thead>
          <tbody>
            {txns.items.map((t) => {
              const out = t.fromAccount === a.accountNumber;
              return (
                <tr key={t.id}>
                  <td>{when(t.createdAt)}</td>
                  <td>{out ? 'Sent' : 'Received'}</td>
                  <td>
                    <Link to={`/accounts/${out ? t.toAccount : t.fromAccount}`}>{out ? t.toHolder : t.fromHolder}</Link>
                  </td>
                  <td className="num">{inr(t.amount)}</td>
                  <td><Badge value={t.decision} /></td>
                  <td><Badge value={t.status} /></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </section>
    </>
  );
}
