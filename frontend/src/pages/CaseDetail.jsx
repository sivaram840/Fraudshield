import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, inr, when } from '../api.js';
import { useAuth } from '../auth.jsx';
import { Badge, ErrorBox, Loading, useLoad } from '../components.jsx';
import FlowGraph, { GraphLegend } from '../FlowGraph.jsx';
import Icon from '../icons.jsx';

/** An investigator's case file: who, what, where the money went, evidence, and a printable report. */
export default function CaseDetail() {
  const { id } = useParams();
  const { user } = useAuth();
  const [data, err, reload] = useLoad(async () => {
    const d = await api.caseDetail(id);
    // Same evidence window as the trace: from 14 days before the case was opened until now.
    const g = await api.graph({ account: d.account.accountNumber, hours: d.traceHours, depth: 3 });
    return { ...d, graph: g };
  }, [id]);
  const [form, setForm] = useState(null);
  const [saved, setSaved] = useState(null);

  useEffect(() => {
    if (data) {
      setForm({ status: data.caseInfo.status, assignedTo: data.caseInfo.assignedTo || '', findings: data.caseInfo.findings || '' });
    }
  }, [data]);

  if (err) return <ErrorBox error={err} />;
  if (!data || !form) return <Loading />;
  const c = data.caseInfo;
  const a = data.account;
  const t = data.trace;
  const victims = t.involved.filter((n) => n.role === 'POSSIBLE_VICTIM' || n.role === 'SOURCE_REVIEW');
  const suspects = t.involved.filter((n) => ['LIKELY_COLLECTOR', 'MULE_PATTERN_REVIEW', 'LAYERING_HOP'].includes(n.role));

  async function save(e) {
    e.preventDefault();
    await api.updateCase(id, form);
    setSaved('Saved');
    reload();
  }

  return (
    <div className="case-file">
      <header className="page-head">
        <div>
          <div className="muted small mono">{c.reference} · opened {when(c.createdAt)} by {c.createdBy}</div>
          <h1>{c.title}</h1>
          <div className="row" style={{ marginTop: '.5rem' }}>
            <Badge value={c.status} /><Badge value={c.priority} /><Badge value={c.source} />
          </div>
        </div>
        <div className="row no-print">
          <Link className="button" to={`/graph?account=${a.accountNumber}`}>Open in graph explorer</Link>
          <button className="primary" onClick={() => window.print()}><Icon name="print" size={15} /> Print case report</button>
        </div>
      </header>

      {c.summary && <div className="outcome">{c.summary}</div>}

      <div className="grid2" style={{ marginTop: '1rem' }}>
        <section className="card">
          <h3>Subject account</h3>
          <table className="kv">
            <tbody>
              <tr><th>Holder</th><td><b>{a.holderName}</b></td></tr>
              <tr><th>Account</th><td className="mono">{a.accountNumber}</td></tr>
              <tr><th>State</th><td>{a.state}</td></tr>
              <tr><th>Registered phone</th><td>{a.phone || '—'}</td></tr>
              <tr><th>Opened</th><td>{when(a.createdAt)}</td></tr>
              <tr><th>Status</th><td><Badge value={a.status} /></td></tr>
              <tr><th>Balance now</th><td>{inr(a.balance)} <span className="muted small">(funds still traceable here)</span></td></tr>
              <tr><th>Assessed role</th><td><Badge value={t.account?.role} /></td></tr>
            </tbody>
          </table>
          {t.account?.roleReasons?.length > 0 && (
            <ul className="reasons">{t.account.roleReasons.map((r, i) => <li key={i}>{r}</li>)}</ul>
          )}
        </section>
        <section className="card">
          <h3>Money trail summary</h3>
          <p>{t.summary}</p>
          <div className="pill-row">
            <span className="pill">Received {inr(t.totalIn)}</span>
            <span className="pill">Moved on {inr(t.totalForwarded)}</span>
            <span className="pill">Stopped {inr(t.stoppedAmount)}</span>
          </div>
          <h4>Likely victims ({victims.length}): contact for statements and refunds</h4>
          {victims.length === 0 ? <p className="muted">None identified in the window.</p> : (
            <ul className="reasons">
              {victims.map((n) => <li key={n.id}><b>{n.holder}</b> ({n.state}) · <span className="mono">{n.id}</span> · sent {inr(n.outAmount)}</li>)}
            </ul>
          )}
          <h4>Leads toward the organiser</h4>
          {suspects.length === 0 && t.endpoints.length === 0 ? (
            <p className="muted">The onward transfer was stopped, so no downstream account received the money.</p>
          ) : (
            <ul className="reasons">
              {[...suspects, ...t.endpoints.filter((e) => !suspects.some((s) => s.id === e.id))].map((n) => (
                <li key={n.id}><b>{n.holder}</b> ({n.state}) · <span className="mono">{n.id}</span> <Badge value={n.role} /></li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <section className="card">
        <h3>Money-flow graph (from 14 days before the case was opened)</h3>
        <GraphLegend />
        <FlowGraph graph={data.graph} focus={a.accountNumber} height={480} wheelZoom={false} />
      </section>

      <section className="card">
        <h3>Time-ordered money paths</h3>
        {t.downstreamPaths.length === 0 ? <p className="muted">No onward movement.</p> : t.downstreamPaths.map((p, i) => (
          <div key={i} className="path">
            <b>{a.holderName}</b>
            {p.map((h) => <span key={h.reference}> → {h.toHolder} {inr(h.amount)} <Badge value={h.status} /> <span className="muted small">{when(h.at)}</span></span>)}
          </div>
        ))}
      </section>

      <section className="card">
        <h3>Alerts on the accounts involved ({data.alerts.length})</h3>
        <table>
          <thead><tr><th>When</th><th>Type</th><th>Severity</th><th>Account</th><th>Explanation</th></tr></thead>
          <tbody>
            {data.alerts.map((x) => (
              <tr key={x.id}>
                <td className="nowrap">{when(x.createdAt)}</td>
                <td><Badge value={x.type} /></td>
                <td><Badge value={x.severity} /></td>
                <td>{x.accountHolder}</td>
                <td className="small">{x.message}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="card">
        <h3>Subject account transactions</h3>
        <table>
          <thead><tr><th>When</th><th>Reference</th><th>From</th><th>To</th><th className="num">Amount</th><th>Decision</th><th>Status</th></tr></thead>
          <tbody>
            {data.transactions.map((x) => (
              <tr key={x.id}>
                <td className="nowrap">{when(x.createdAt)}</td>
                <td className="mono">{x.reference}</td>
                <td>{x.fromHolder}</td>
                <td>{x.toHolder}</td>
                <td className="num">{inr(x.amount)}</td>
                <td><Badge value={x.decision} /></td>
                <td><Badge value={x.status} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <form className="card form no-print" onSubmit={save}>
        <h3>Investigation status</h3>
        <label>
          Status
          <select value={form.status} onChange={(e) => setForm({ ...form, status: e.target.value })}>
            <option value="OPEN">Open</option>
            <option value="IN_PROGRESS">In progress</option>
            <option value="CLOSED">Closed</option>
          </select>
        </label>
        <label>
          Assigned investigator
          <input value={form.assignedTo} placeholder={user.username} onChange={(e) => setForm({ ...form, assignedTo: e.target.value })} />
        </label>
        <label>
          Findings
          <textarea rows={5} maxLength={4000} value={form.findings} onChange={(e) => setForm({ ...form, findings: e.target.value })}
            placeholder="Statements taken, KYC checked, leads followed, outcome…" />
        </label>
        <div className="row">
          <button className="primary">Save case</button>
          {saved && <span className="muted">{saved}</span>}
        </div>
      </form>

      {c.findings && <section className="card print-only"><h3>Findings</h3><p>{c.findings}</p></section>}
      <p className="muted small">Report generated {when(data.generatedAt)} by {user.displayName}. Simulated data.</p>
    </div>
  );
}
