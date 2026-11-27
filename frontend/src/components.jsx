import { useCallback, useEffect, useState } from 'react';
import { pct } from './api.js';
import Icon from './icons.jsx';

/** Loads data with an async function; returns [data, error, reload]. */
export function useLoad(fn, deps = []) {
  const [data, setData] = useState(null);
  const [error, setError] = useState(null);
  const load = useCallback(() => {
    setError(null);
    return fn().then(setData).catch(setError);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => {
    load();
  }, [load]);
  return [data, error, load];
}

export function Badge({ value }) {
  if (!value) return <span className="badge">—</span>;
  const label = String(value).replaceAll('_', ' ').toLowerCase();
  return <span className={`badge b-${String(value).toLowerCase()}`}><i />{label}</span>;
}

export function ErrorBox({ error }) {
  if (!error) return null;
  return (
    <div className="error">
      {error.code ? <b>{error.code}: </b> : null}
      {error.message}
    </div>
  );
}

export function Loading() {
  return <p className="muted">Loading…</p>;
}

export function Kpi({ label, value, tone, icon, sub }) {
  return (
    <div className={`kpi ${tone || ''}`}>
      <div className="kpi-top">
        <span className="kpi-label">{label}</span>
        {icon && <span className="kpi-icon"><Icon name={icon} size={16} /></span>}
      </div>
      <div className="kpi-value">{value}</div>
      {sub && <div className="kpi-sub">{sub}</div>}
    </div>
  );
}

export function PageHeader({ title, subtitle, children }) {
  return (
    <header className="page-head">
      <div>
        <h1>{title}</h1>
        {subtitle && <p className="page-sub">{subtitle}</p>}
      </div>
      {children && <div className="row">{children}</div>}
    </header>
  );
}

/** Score breakdown of one risk assessment. */
export function RiskBreakdown({ risk }) {
  if (!risk) return <p className="muted">No risk assessment (seeded history).</p>;
  const rows = [
    ['Transaction fraud (Random Forest, PaySim)', risk.txnFraudProbability],
    ['Sender money-mule probability (graph model)', risk.senderMuleProbability],
    ['Receiver money-mule probability (graph model)', risk.receiverMuleProbability],
    ['Behaviour anomaly (Isolation Forest)', risk.anomalyScore],
    ['Rule score (fan-in, rapid onward, chains…)', risk.ruleScore],
  ];
  return (
    <div className="risk">
      <div className="risk-head">
        <Badge value={risk.decision} />
        <span>
          Final score <b>{pct(risk.finalScore)}</b>
        </span>
        {!risk.mlAvailable && <span className="warn-text">ML offline: rules-only decision</span>}
      </div>
      {rows.map(([label, v]) => (
        <div key={label} className="bar-row">
          <span>{label}</span>
          <div className="bar">
            <div style={{ width: v == null ? 0 : `${Math.min(100, Number(v) * 100)}%` }} />
          </div>
          <span className="num">{pct(v)}</span>
        </div>
      ))}
      {risk.rulesFired?.length > 0 && (
        <p>
          Rules fired:{' '}
          {risk.rulesFired.map((r) => (
            <code key={r} className="chip">
              {r}
            </code>
          ))}
        </p>
      )}
      {risk.reasons?.length > 0 && (
        <>
          <h4>Why</h4>
          <ul className="reasons">
            {risk.reasons.map((r, i) => (
              <li key={i}>{r}</li>
            ))}
          </ul>
        </>
      )}
      {risk.modelVersion && <p className="muted small">Model version {risk.modelVersion}</p>}
    </div>
  );
}
