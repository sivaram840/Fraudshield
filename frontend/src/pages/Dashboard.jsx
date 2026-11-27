import { Link } from 'react-router-dom';
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api, inr } from '../api.js';
import { Badge, ErrorBox, Kpi, Loading, PageHeader, useLoad } from '../components.jsx';
import Icon from '../icons.jsx';

const DECISION_COLORS = { completed: '#2f9e6b', mfa: '#d9a02b', held: '#e07b2e', blocked: '#c9384a' };

export default function Dashboard() {
  const [s, err, reload] = useLoad(() => api.dashboard());
  if (err) return <ErrorBox error={err} />;
  if (!s) return <Loading />;
  const decisions = Object.entries(s.byDecision).map(([k, v]) => ({ name: k, count: v }));
  const bands = Object.entries(s.riskBands).map(([k, v]) => ({ band: k, count: v }));
  return (
    <>
      <PageHeader title="Fraud operations overview"
        subtitle="Live view of transfers, risk decisions and money-mule alerts across the simulated network.">
        <button onClick={reload}><Icon name="refresh" size={15} /> Refresh</button>
      </PageHeader>
      <div className="kpis">
        <Kpi icon="accounts" label="Accounts monitored" value={s.accounts.toLocaleString('en-IN')} />
        <Kpi icon="list" label="Transactions" value={s.transactions.toLocaleString('en-IN')}
          sub={`${s.byStatus.COMPLETED} completed`} />
        <Kpi icon="money" label="Money moved" value={inr(s.completedVolume)} />
        <Kpi icon="stop" label="Funds stopped" value={inr(s.preventedVolume)} tone="good"
          sub={`${s.byStatus.BLOCKED} blocked · ${s.byStatus.HELD} held · ${s.byStatus.REJECTED} rejected`} />
        <Kpi icon="bell" label="Open alerts" value={s.openAlerts} tone={s.openAlerts ? 'bad' : ''}
          sub={`${s.openAlertsBySeverity.CRITICAL} critical · ${s.openAlertsBySeverity.HIGH} high`} />
        <Kpi icon="cpu" label="ML risk engine" value={s.mlOnline ? 'Online' : 'Offline'} tone={s.mlOnline ? 'good' : 'bad'}
          sub={s.mlOnline ? 'RF + graph model + Isolation Forest' : 'Rules-only fail-safe active'} />
      </div>

      <div className="grid2">
        <section className="card">
          <h3>Decisions in the last 24 hours</h3>
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={s.last24h}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="hour" fontSize={11} />
              <YAxis allowDecimals={false} fontSize={11} />
              <Tooltip />
              <Legend />
              <Bar dataKey="completed" name="ALLOW" stackId="a" fill={DECISION_COLORS.completed} />
              <Bar dataKey="mfa" name="MFA" stackId="a" fill={DECISION_COLORS.mfa} />
              <Bar dataKey="held" name="HOLD" stackId="a" fill={DECISION_COLORS.held} />
              <Bar dataKey="blocked" name="BLOCK" stackId="a" fill={DECISION_COLORS.blocked} />
            </BarChart>
          </ResponsiveContainer>
        </section>
        <section className="card">
          <h3>Risk score distribution</h3>
          <ResponsiveContainer width="100%" height={240}>
            <BarChart data={bands}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} />
              <XAxis dataKey="band" fontSize={11} />
              <YAxis allowDecimals={false} fontSize={11} />
              <Tooltip />
              <Bar dataKey="count" fill="#4b6cb7" />
            </BarChart>
          </ResponsiveContainer>
        </section>
      </div>

      <div className="grid2">
        <section className="card">
          <h3>All-time decisions and statuses</h3>
          <div className="pill-row">
            {decisions.map((d) => (
              <span key={d.name} className="pill">
                <Badge value={d.name} /> {d.count}
              </span>
            ))}
          </div>
          <div className="pill-row">
            {Object.entries(s.byStatus).map(([k, v]) => (
              <span key={k} className="pill">
                <Badge value={k} /> {v}
              </span>
            ))}
          </div>
          <h4>Open alerts by severity</h4>
          <div className="pill-row">
            {Object.entries(s.openAlertsBySeverity).map(([k, v]) => (
              <span key={k} className="pill">
                <Badge value={k} /> {v}
              </span>
            ))}
          </div>
        </section>
        <section className="card">
          <h3>Suspicious accounts</h3>
          {s.riskyAccounts.length === 0 ? (
            <p className="muted">No open alerts. Run an attack scenario to see detection in action.</p>
          ) : (
            <table>
              <thead>
                <tr>
                  <th>Account</th>
                  <th>Status</th>
                  <th>Open alerts</th>
                  <th>Top alert</th>
                </tr>
              </thead>
              <tbody>
                {s.riskyAccounts.map((a) => (
                  <tr key={a.accountNumber}>
                    <td>
                      <Link to={`/accounts/${a.accountNumber}`}>{a.holder}</Link>
                      <div className="muted small">
                        {a.accountNumber} · {a.state}
                      </div>
                    </td>
                    <td>
                      <Badge value={a.status} />
                    </td>
                    <td>{a.openAlerts}</td>
                    <td>
                      <Badge value={a.topAlert} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>
    </>
  );
}
