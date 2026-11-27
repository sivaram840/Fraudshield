import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, when } from '../api.js';
import { useCan } from '../auth.jsx';
import { Badge, ErrorBox, Loading, useLoad } from '../components.jsx';

export default function Alerts() {
  const [status, setStatus] = useState('OPEN');
  const canResolve = useCan('ANALYST');
  const [page, err, reload] = useLoad(() => api.alerts(status ? { status } : {}), [status]);

  async function resolve(id) {
    const note = window.prompt('Resolution note');
    if (note === null) return;
    await api.resolveAlert(id, note);
    reload();
  }

  return (
    <>
      <header className="page-head">
        <h1>Suspicious-activity alerts</h1>
        <div className="row">
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="OPEN">Open</option>
            <option value="RESOLVED">Resolved</option>
            <option value="">All</option>
          </select>
          <button onClick={reload}>Refresh</button>
        </div>
      </header>
      <ErrorBox error={err} />
      {!page ? <Loading /> : (
        <section className="card">
          <table>
            <thead><tr><th>When</th><th>Type</th><th>Severity</th><th>Account</th><th>Explanation</th><th>Status</th><th /></tr></thead>
            <tbody>
              {page.items.map((a) => (
                <tr key={a.id}>
                  <td>{when(a.createdAt)}</td>
                  <td><Badge value={a.type} /></td>
                  <td><Badge value={a.severity} /></td>
                  <td>{a.accountNumber ? <Link to={`/accounts/${a.accountNumber}`}>{a.accountHolder}</Link> : '—'}</td>
                  <td>{a.message}</td>
                  <td><Badge value={a.status} /></td>
                  <td>{canResolve && a.status === 'OPEN' && <button onClick={() => resolve(a.id)}>Resolve</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {page.items.length === 0 && <p className="muted">No alerts.</p>}
        </section>
      )}
    </>
  );
}
