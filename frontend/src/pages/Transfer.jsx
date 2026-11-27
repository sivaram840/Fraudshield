import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, inr } from '../api.js';
import { Badge, ErrorBox, RiskBreakdown } from '../components.jsx';

const newKey = () => crypto.randomUUID();

export default function Transfer() {
  const [accounts, setAccounts] = useState([]);
  const [form, setForm] = useState({ fromAccount: '', toAccount: '', amount: '', description: '' });
  // One key per intended transfer: a retry or double-click reuses it, so it cannot pay twice.
  const [key, setKey] = useState(newKey);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [result, setResult] = useState(null);
  const [otp, setOtp] = useState('');
  const [otpMsg, setOtpMsg] = useState(null);

  useEffect(() => {
    api.accounts().then(setAccounts).catch(setError);
  }, []);

  const set = (k) => (e) => {
    setForm({ ...form, [k]: e.target.value });
    setKey(newKey());
  };

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    setOtpMsg(null);
    try {
      const r = await api.transfer({ ...form, amount: Number(form.amount) }, key);
      setResult(r);
      setOtp('');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  async function verify(e) {
    e.preventDefault();
    try {
      const r = await api.verifyOtp(result.transaction.id, otp);
      setResult({ ...result, transaction: r.transaction, demoOtp: r.verified ? null : result.demoOtp });
      setOtpMsg(r.message);
      setOtp('');
    } catch (err) {
      setOtpMsg(err.message);
    }
  }

  async function resend() {
    const r = await api.resendOtp(result.transaction.id);
    setResult({ ...result, demoOtp: r.demoOtp });
    setOtpMsg('A new code was sent.');
  }

  function startOver() {
    setResult(null);
    setKey(newKey());
    setForm({ fromAccount: '', toAccount: '', amount: '', description: '' });
    api.accounts().then(setAccounts);
  }

  const t = result?.transaction;
  const opt = (a) => (
    <option key={a.accountNumber} value={a.accountNumber}>
      {a.holderName} · {a.accountNumber} · {inr(a.balance)} {a.status !== 'ACTIVE' ? `(${a.status})` : ''}
    </option>
  );

  return (
    <>
      <header className="page-head">
        <h1>New transfer</h1>
      </header>
      {!result && (
        <form className="card form" onSubmit={submit}>
          <label>
            From account
            <select required value={form.fromAccount} onChange={set('fromAccount')}>
              <option value="">Select…</option>
              {accounts.map(opt)}
            </select>
          </label>
          <label>
            To account
            <select required value={form.toAccount} onChange={set('toAccount')}>
              <option value="">Select…</option>
              {accounts.filter((a) => a.accountNumber !== form.fromAccount).map(opt)}
            </select>
          </label>
          <label>
            Amount (₹)
            <input required type="number" min="0.01" step="0.01" value={form.amount} onChange={set('amount')} />
          </label>
          <label>
            Description
            <input maxLength={255} value={form.description} onChange={set('description')} />
          </label>
          <p className="muted small">Idempotency key: {key}</p>
          <ErrorBox error={error} />
          <button className="primary" disabled={busy}>
            {busy ? 'Assessing risk…' : 'Send money'}
          </button>
        </form>
      )}

      {t && (
        <section className="card">
          <div className="result-head">
            <h2>
              {inr(t.amount)} to {t.toHolder}
            </h2>
            <Badge value={t.status} />
          </div>
          <p>{result.duplicate ? 'Duplicate request: original transaction returned, nothing charged again.' : result.message}</p>
          <p className="muted small">
            Reference {t.reference} · <Link to={`/transactions?focus=${t.id}`}>open in history</Link>
          </p>

          {t.status === 'PENDING_MFA' && (
            <div className="otp-box">
              {result.demoOtp && (
                <div className="sms">
                  <div className="muted small">SMS simulator (demo mode, stands in for a real SMS)</div>
                  Your FraudShield OTP is <b>{result.demoOtp}</b>. Do not share it with anyone.
                </div>
              )}
              <form onSubmit={verify} className="row">
                <input
                  placeholder="6-digit OTP"
                  value={otp}
                  onChange={(e) => setOtp(e.target.value)}
                  pattern="\d{6}"
                  required
                />
                <button className="primary">Verify</button>
                <button type="button" onClick={resend}>
                  Resend
                </button>
              </form>
              {otpMsg && <p>{otpMsg}</p>}
            </div>
          )}
          {t.status !== 'PENDING_MFA' && otpMsg && <p>{otpMsg}</p>}

          <RiskBreakdown risk={t.risk} />
          <button onClick={startOver}>New transfer</button>
        </section>
      )}
    </>
  );
}
