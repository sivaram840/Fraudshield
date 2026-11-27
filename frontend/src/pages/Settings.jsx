import { api } from '../api.js';
import { ErrorBox, Loading, PageHeader, useLoad } from '../components.jsx';

export default function Settings() {
  const [s, err] = useLoad(() => api.settings());
  if (err) return <ErrorBox error={err} />;
  if (!s) return <Loading />;
  const r = s.risk;
  const rows = [
    ['Weight: transaction fraud model', r.weightTxn],
    ['Weight: money-mule graph model', r.weightMule],
    ['Weight: behaviour anomaly (Isolation Forest)', r.weightAnomaly],
    ['Weight: rule score', r.weightRules],
  ];
  return (
    <>
      <PageHeader title="Risk settings"
        subtitle="Current decision configuration. Change values in backend/src/main/resources/application.yml and restart." />
      <div className="grid2">
        <section className="card">
          <h3>Score weights</h3>
          <table className="kv"><tbody>{rows.map(([k, v]) => <tr key={k}><th>{k}</th><td className="num">{v.toFixed(2)}</td></tr>)}</tbody></table>
          <h4>Decision thresholds on the final score</h4>
          <div className="threshold-bar">
            <span className="b-allow" style={{ flex: r.mfaThreshold }}>ALLOW &lt; {r.mfaThreshold}</span>
            <span className="b-mfa" style={{ flex: r.holdThreshold - r.mfaThreshold }}>MFA</span>
            <span className="b-hold" style={{ flex: r.blockThreshold - r.holdThreshold }}>HOLD</span>
            <span className="b-block" style={{ flex: 1 - r.blockThreshold }}>BLOCK ≥ {r.blockThreshold}</span>
          </div>
        </section>
        <section className="card">
          <h3>Safety controls</h3>
          <table className="kv">
            <tbody>
              <tr><th>OTP lifetime</th><td>{s.otp.ttlSeconds / 60} minutes</td></tr>
              <tr><th>Wrong OTP attempts before rejection</th><td>{s.otp.maxAttempts}</td></tr>
              <tr><th>OTP shown on screen (demo mode)</th><td>{s.otp.demoMode ? 'Yes (prototype only)' : 'No'}</td></tr>
              <tr><th>Held transfer expires after</th><td>{s.hold.expiryHours} hours, never executed</td></tr>
              <tr><th>ML service</th><td className="mono">{s.ml.baseUrl}</td></tr>
              <tr><th>ML timeout</th><td>{s.ml.timeoutMs} ms, then rules-only fail-safe (minimum MFA)</td></tr>
            </tbody>
          </table>
        </section>
      </div>
    </>
  );
}
