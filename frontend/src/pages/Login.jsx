import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../auth.jsx';
import Icon from '../icons.jsx';

// Demo logins created by the backend seeder (local prototype only).
const DEMO_PASSWORD = 'Demo@123';
const PORTALS = [
  { role: 'Customer', username: 'aarav', icon: 'home',
    who: 'Bank customers', what: 'Send money with OTP, see transfers, get mule-scam warnings, report fraud.' },
  { role: 'Fraud Analyst', username: 'analyst', icon: 'review',
    who: "The bank's fraud team", what: 'Monitor risk, review held transfers, act on alerts, freeze and escalate.' },
  { role: 'Investigator', username: 'investigator', icon: 'case',
    who: 'Police / cyber-crime cell', what: 'Work case files, trace money past the mule, print case reports.' },
  { role: 'Administrator', username: 'admin', icon: 'settings',
    who: 'Bank IT / compliance', what: 'Manage users and roles, risk settings, audit log, simulation lab.' },
];

export default function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const u = await login(username, password);
      navigate(u.role === 'INVESTIGATOR' ? '/cases' : '/', { replace: true });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  function pick(p) {
    setUsername(p.username);
    setPassword(DEMO_PASSWORD);
    setError(null);
  }

  return (
    <div className="login">
      <section className="login-hero">
        <div className="brand">
          <span className="brand-mark"><Icon name="shield" size={22} /></span>
          <div>
            <div className="brand-name">FraudShield</div>
            <div className="brand-sub">Money-mule detection and prevention</div>
          </div>
        </div>
        <h1>Stop stolen money before it disappears behind a money mule.</h1>
        <p>
          One platform, four portals. Each person sees only what their job needs: customers protect their own money,
          the bank's analysts stop risky transfers, investigators follow the money to the real criminal, and
          administrators keep the system accountable.
        </p>
        <div className="portal-grid">
          {PORTALS.map((p) => (
            <button key={p.username} type="button" className="portal-card" onClick={() => pick(p)}>
              <span className="portal-icon"><Icon name={p.icon} size={18} /></span>
              <b>{p.role}</b>
              <span className="portal-who">{p.who}</span>
              <span className="portal-what">{p.what}</span>
              <span className="portal-login">Demo login: <code>{p.username}</code></span>
            </button>
          ))}
        </div>
        <p className="hero-foot">Simulation only. No real bank accounts are connected.</p>
      </section>

      <section className="login-panel">
        <form className="login-form" onSubmit={submit}>
          <h2>Sign in</h2>
          <p className="muted">Choose a portal on the left to fill in a demo account, or enter your own login.</p>
          <label>
            Username
            <input autoFocus required value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" />
          </label>
          <label>
            Password
            <input required type="password" value={password} onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password" />
          </label>
          {error && <div className="error">{error}</div>}
          <button className="primary block" disabled={busy}><Icon name="lock" size={15} /> {busy ? 'Signing in…' : 'Sign in'}</button>
          <p className="muted small">Demo password for all seeded accounts: <code>{DEMO_PASSWORD}</code></p>
        </form>
      </section>
    </div>
  );
}
