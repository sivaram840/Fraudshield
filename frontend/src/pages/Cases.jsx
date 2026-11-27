import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, when } from '../api.js';
import { useAuth } from '../auth.jsx';
import { Badge, ErrorBox, Kpi, Loading, PageHeader, useLoad } from '../components.jsx';

export default function Cases() {
  const { user } = useAuth();
  const [list, err, reload] = useLoad(() => api.cases());
  const [filter, setFilter] = useState('ACTIVE');
  const canOpen = user.role !== 'ANALYST';
  const isInvestigator = user.role === 'INVESTIGATOR';

  if (err) return <ErrorBox error={err} />;
  if (!list) return <Loading />;
  const shown = list.filter((c) => filter === 'ALL' || (filter === 'ACTIVE' ? c.status !== 'CLOSED' : c.status === filter));
  const count = (s) => list.filter((c) => c.status === s).length;

  return (
    <>
      <PageHeader
        title={isInvestigator ? 'Case files' : 'Escalated cases'}
        subtitle={isInvestigator
          ? 'Cases escalated by bank fraud analysts or reported by customers. Open a case to trace the money and print the report.'
          : 'Cases you sent to investigators. Escalate a suspicious account from its account page.'}>
        <select value={filter} onChange={(e) => setFilter(e.target.value)}>
          <option value="ACTIVE">Open and in progress</option>
          <option value="OPEN">Open</option>
          <option value="IN_PROGRESS">In progress</option>
          <option value="CLOSED">Closed</option>
          <option value="ALL">All</option>
        </select>
        <button onClick={reload}>Refresh</button>
      </PageHeader>
      <div className="kpis">
        <Kpi icon="case" label="Open" value={count('OPEN')} tone={count('OPEN') ? 'bad' : ''} />
        <Kpi icon="review" label="In progress" value={count('IN_PROGRESS')} />
        <Kpi icon="lock" label="Closed" value={count('CLOSED')} tone="good" />
        <Kpi icon="flag" label="From customer reports" value={list.filter((c) => c.source === 'CUSTOMER_REPORT').length} />
      </div>
      <section className="card">
        {shown.length === 0 ? <p className="muted">No cases.</p> : (
          <table>
            <thead>
              <tr><th>Case</th><th>Account</th><th>Title</th><th>Source</th><th>Priority</th><th>Status</th><th>Assigned</th><th>Opened</th></tr>
            </thead>
            <tbody>
              {shown.map((c) => (
                <tr key={c.id}>
                  <td className="mono nowrap">{canOpen ? <Link to={`/cases/${c.id}`}>{c.reference}</Link> : c.reference}</td>
                  <td>
                    <Link to={`/accounts/${c.accountNumber}`}>{c.accountHolder}</Link>
                    <div className="muted small mono">{c.accountNumber}</div>
                  </td>
                  <td>{c.title}</td>
                  <td><Badge value={c.source} /></td>
                  <td><Badge value={c.priority} /></td>
                  <td><Badge value={c.status} /></td>
                  <td>{c.assignedTo || <span className="muted">unassigned</span>}</td>
                  <td className="nowrap">{when(c.createdAt)}<div className="muted small">by {c.createdBy}</div></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}
