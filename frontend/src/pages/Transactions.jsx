import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, inr, when } from '../api.js';
import { Badge, ErrorBox, Loading, RiskBreakdown, useLoad } from '../components.jsx';

const STATUSES = ['', 'COMPLETED', 'PENDING_MFA', 'HELD', 'BLOCKED', 'REJECTED', 'EXPIRED', 'FAILED'];

export function TransactionDetail({ id, onClose }) {
  const [data, err] = useLoad(() => Promise.all([api.transaction(id), api.transactionAudit(id)]), [id]);
  return (
    <div className="drawer">
      <button className="close" onClick={onClose}>×</button>
      <ErrorBox error={err} />
      {!data ? <Loading /> : (
        <>
          <h2>{data[0].reference}</h2>
          <p>
            {data[0].fromHolder} → {data[0].toHolder} · <b>{inr(data[0].amount)}</b> · <Badge value={data[0].status} />
          </p>
          {data[0].failureReason && <p className="warn-text">{data[0].failureReason}</p>}
          <RiskBreakdown risk={data[0].risk} />
          <h4>Audit trail</h4>
          <ol className="timeline">
            {data[1].map((l) => (
              <li key={l.id}>
                <span className="muted small">{when(l.createdAt)} · {l.actor}</span>
                <div><b>{l.action}</b> {l.details}</div>
              </li>
            ))}
          </ol>
        </>
      )}
    </div>
  );
}

export default function Transactions() {
  const [params] = useSearchParams();
  const [status, setStatus] = useState('');
  const [focus, setFocus] = useState(params.get('focus'));
  const [page, err, reload] = useLoad(() => api.transactions({ size: 200, ...(status ? { status } : {}) }), [status]);
  useEffect(() => setFocus(params.get('focus')), [params]);

  return (
    <>
      <header className="page-head">
        <h1>Transaction history</h1>
        <div className="row">
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            {STATUSES.map((s) => <option key={s} value={s}>{s || 'All statuses'}</option>)}
          </select>
          <button onClick={reload}>Refresh</button>
        </div>
      </header>
      <ErrorBox error={err} />
      {!page ? <Loading /> : (
        <section className="card">
          <p className="muted small">{page.total} transactions. Click a row for the risk decision record and audit trail.</p>
          <table>
            <thead>
              <tr><th>When</th><th>Reference</th><th>From</th><th>To</th><th className="num">Amount</th><th className="num">Risk</th><th>Decision</th><th>Status</th><th>Origin</th></tr>
            </thead>
            <tbody>
              {page.items.map((t) => (
                <tr key={t.id} className="clickable" onClick={() => setFocus(String(t.id))}>
                  <td>{when(t.createdAt)}</td>
                  <td className="mono">{t.reference}</td>
                  <td>{t.fromHolder}</td>
                  <td>{t.toHolder}</td>
                  <td className="num">{inr(t.amount)}</td>
                  <td className="num">{t.riskScore == null ? '—' : Number(t.riskScore).toFixed(2)}</td>
                  <td><Badge value={t.decision} /></td>
                  <td><Badge value={t.status} /></td>
                  <td className="muted small">{t.origin}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
      {focus && <TransactionDetail id={focus} onClose={() => setFocus(null)} />}
    </>
  );
}
