import { useEffect, useState } from 'react';
import { Link, Navigate, NavLink, Route, Routes, useNavigate, useSearchParams } from 'react-router-dom';
import { api, inr, when } from '../api.js';
import { useAuth } from '../auth.jsx';
import { ErrorBox, Loading, useLoad } from '../components.jsx';
import Icon from '../icons.jsx';

const STATUS_TONE = { COMPLETED: 'ok', PENDING_MFA: 'warn', HELD: 'warn', BLOCKED: 'bad', REJECTED: 'muted',
  EXPIRED: 'muted', FAILED: 'bad', APPROVED: 'warn' };

function TxnRow({ t, onChanged }) {
  const out = t.direction === 'SENT';
  async function cancel() {
    if (!window.confirm('Cancel this transfer? It will not be sent.')) return;
    await api.myCancel(t.id);
    onChanged?.();
  }
  return (
    <li className="c-txn">
      <span className={`c-txn-icon ${out ? 'out' : 'in'}`}>{out ? '↑' : '↓'}</span>
      <div className="c-txn-main">
        <b>{out ? `To ${t.counterpartyName}` : `From ${t.counterpartyName}`}</b>
        <span>{t.counterpartyAccount} · {when(t.createdAt)}{t.description ? ` · ${t.description}` : ''}</span>
        <span className={`c-status ${STATUS_TONE[t.status]}`}>{t.statusText}</span>
      </div>
      <div className="c-txn-side">
        <span className={`c-amount ${out ? 'out' : 'in'}`}>{out ? '−' : '+'}{inr(t.amount)}</span>
        {t.canVerifyOtp && <Link className="c-link" to={`/send?resume=${t.id}`}>Enter OTP</Link>}
        {t.canCancel && <button className="c-link" onClick={cancel}>Cancel</button>}
      </div>
    </li>
  );
}

function Home() {
  const [o, err, reload] = useLoad(() => api.myOverview());
  if (err) return <ErrorBox error={err} />;
  if (!o) return <Loading />;
  const a = o.account;
  return (
    <>
      {o.warnings.map((w) => (
        <div key={w.id} className="c-alert">
          <Icon name="alert" size={22} />
          <div>
            <b>{w.type === 'MULE_WARNING' ? 'Important: you may be the target of a money-mule scam' : 'Security notice'}</b>
            <p>{w.message}</p>
            <Link to="/report" className="c-button light">Report what happened</Link>
          </div>
        </div>
      ))}
      <section className={`c-balance ${a.status !== 'ACTIVE' ? 'restricted' : ''}`}>
        <div>
          <span className="c-label">Savings account · {a.accountNumber}</span>
          <div className="c-big">{inr(a.balance)}</div>
          <span className="c-label">Available balance</span>
        </div>
        <div className="c-balance-side">
          {a.status !== 'ACTIVE' && (
            <span className="c-restricted">
              {a.status === 'FROZEN' ? 'Account frozen: contact the bank' : 'Account under security review'}
            </span>
          )}
          <Link to="/send" className="c-button"><Icon name="send" size={16} /> Send money</Link>
        </div>
      </section>
      {o.pendingActions > 0 && (
        <div className="c-note">You have {o.pendingActions} transfer(s) waiting for your OTP.</div>
      )}
      <section className="c-card">
        <div className="c-card-head">
          <h3>Recent activity</h3>
          <Link to="/activity">See all</Link>
        </div>
        {o.recent.length === 0 ? <p className="muted">No transactions yet.</p> : (
          <ul className="c-txns">{o.recent.map((t) => <TxnRow key={t.id} t={t} onChanged={reload} />)}</ul>
        )}
      </section>
      <section className="c-tips">
        <h3>Stay safe</h3>
        <ul>
          <li>Never accept money from strangers to "pass on" for a commission. That is money-mule fraud and is a crime.</li>
          <li>The bank never asks for your OTP on a call. Never share it.</li>
          <li>Lost money to fraud? Call the national cybercrime helpline <b>1930</b> or visit cybercrime.gov.in.</li>
        </ul>
      </section>
    </>
  );
}

function Send() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [to, setTo] = useState('');
  const [payee, setPayee] = useState(null);
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const [key, setKey] = useState(() => crypto.randomUUID());
  const [result, setResult] = useState(null);
  const [otp, setOtp] = useState('');
  const [msg, setMsg] = useState(null);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const id = params.get('resume');
    if (!id) return;
    api.myTransactions().then((list) => {
      const t = list.find((x) => String(x.id) === id);
      if (t) setResult({ transaction: t, demoOtp: null, message: 'Enter the OTP sent to your phone, or request a new one.' });
    });
  }, [params]);

  async function lookup() {
    setPayee(null);
    setError(null);
    if (!to.trim()) return;
    try {
      setPayee(await api.payee(to.trim()));
    } catch (e) {
      setError(e);
    }
  }

  async function submit(e) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setResult(await api.myTransfer({ toAccount: to.trim(), amount: Number(amount), description: note }, key));
    } catch (er) {
      setError(er);
    } finally {
      setBusy(false);
    }
  }

  async function verify(e) {
    e.preventDefault();
    try {
      const r = await api.myVerifyOtp(result.transaction.id, otp);
      setResult({ ...result, transaction: r.transaction, demoOtp: r.verified ? null : result.demoOtp });
      setMsg(r.message);
      setOtp('');
    } catch (er) {
      setMsg(er.message);
    }
  }

  async function resend() {
    const r = await api.myResendOtp(result.transaction.id);
    setResult({ ...result, demoOtp: r.demoOtp });
    setMsg('A new OTP was sent.');
  }

  if (result) {
    const t = result.transaction;
    const icon = { COMPLETED: '✓', PENDING_MFA: '•••', HELD: '⏸', BLOCKED: '⛔' }[t.status] || '!';
    return (
      <section className="c-card c-result">
        <div className={`c-result-icon ${STATUS_TONE[t.status]}`}>{icon}</div>
        <h2>{t.statusText}</h2>
        <p className="c-result-amount">{inr(t.amount)} to {t.counterpartyName}</p>
        <p>{result.duplicate ? 'This request was already received: you were not charged twice.' : result.message}</p>
        {t.status === 'PENDING_MFA' && (
          <div className="c-otp">
            {result.demoOtp && (
              <div className="sms">
                <div className="muted small">SMS simulator (demo mode)</div>
                Your FraudShield Bank OTP is <b>{result.demoOtp}</b>. Never share it with anyone, even bank staff.
              </div>
            )}
            <form onSubmit={verify} className="row">
              <input inputMode="numeric" placeholder="6-digit OTP" pattern="\d{6}" required value={otp}
                onChange={(e) => setOtp(e.target.value)} />
              <button className="primary">Confirm</button>
              <button type="button" onClick={resend}>Resend OTP</button>
            </form>
            {msg && <p>{msg}</p>}
          </div>
        )}
        {t.status !== 'PENDING_MFA' && msg && <p>{msg}</p>}
        {t.status === 'BLOCKED' && <Link className="c-button light" to="/report">Report a scam</Link>}
        <div className="row c-center">
          <button onClick={() => { setResult(null); setKey(crypto.randomUUID()); setAmount(''); setNote(''); setMsg(null); }}>
            New transfer
          </button>
          <button className="primary" onClick={() => navigate('/')}>Back to home</button>
        </div>
      </section>
    );
  }

  return (
    <form className="c-card c-form" onSubmit={submit}>
      <h2>Send money</h2>
      <label>
        Recipient account number
        <div className="row">
          <input required value={to} placeholder="FS0000000000"
            onChange={(e) => { setTo(e.target.value); setPayee(null); setKey(crypto.randomUUID()); }} onBlur={lookup} />
          <button type="button" onClick={lookup}>Verify</button>
        </div>
      </label>
      {payee && <div className="c-payee">✓ {payee.maskedName} · {payee.state}</div>}
      <label>
        Amount (₹)
        <input required type="number" min="0.01" step="0.01" value={amount}
          onChange={(e) => { setAmount(e.target.value); setKey(crypto.randomUUID()); }} />
      </label>
      <label>
        Note (optional)
        <input maxLength={255} value={note} onChange={(e) => setNote(e.target.value)} />
      </label>
      <ErrorBox error={error} />
      <button className="primary block" disabled={busy}>{busy ? 'Checking for fraud…' : 'Send'}</button>
      <p className="muted small">Every transfer is checked for fraud before it leaves your account. Large or unusual
        transfers ask for an OTP.</p>
    </form>
  );
}

function Activity() {
  const [list, err, reload] = useLoad(() => api.myTransactions());
  if (err) return <ErrorBox error={err} />;
  if (!list) return <Loading />;
  return (
    <section className="c-card">
      <h2>All transactions</h2>
      {list.length === 0 ? <p className="muted">No transactions yet.</p> : (
        <ul className="c-txns">{list.map((t) => <TxnRow key={t.id} t={t} onChanged={reload} />)}</ul>
      )}
    </section>
  );
}

const REPORT_TYPES = [
  ['ASKED_TO_MOVE_MONEY', 'Someone asked me to receive money and pass it on (for a commission)'],
  ['UNAUTHORISED_TRANSACTION', 'A transaction I did not make'],
  ['SUSPICIOUS_CREDIT', 'I received money I was not expecting'],
  ['OTHER', 'Something else'],
];

function Report() {
  const [list] = useLoad(() => api.myTransactions());
  const [type, setType] = useState('ASKED_TO_MOVE_MONEY');
  const [txn, setTxn] = useState('');
  const [details, setDetails] = useState('');
  const [done, setDone] = useState(null);
  const [error, setError] = useState(null);

  async function submit(e) {
    e.preventDefault();
    setError(null);
    try {
      setDone(await api.myReport({ type, transactionId: txn ? Number(txn) : null, details }));
    } catch (er) {
      setError(er);
    }
  }

  if (done) {
    return (
      <section className="c-card c-result">
        <div className="c-result-icon ok">✓</div>
        <h2>Report received</h2>
        <p>{done.message}</p>
        <Link className="c-button" to="/">Back to home</Link>
      </section>
    );
  }
  return (
    <form className="c-card c-form" onSubmit={submit}>
      <h2>Report fraud or a scam</h2>
      <p className="muted">Reporting early protects you. If you were tricked into receiving money for someone else,
        telling us now shows you acted in good faith.</p>
      <fieldset className="c-options">
        {REPORT_TYPES.map(([v, label]) => (
          <label key={v} className={type === v ? 'on' : ''}>
            <input type="radio" name="type" value={v} checked={type === v} onChange={() => setType(v)} /> {label}
          </label>
        ))}
      </fieldset>
      <label>
        Related transaction (optional)
        <select value={txn} onChange={(e) => setTxn(e.target.value)}>
          <option value="">None</option>
          {(list || []).map((t) => (
            <option key={t.id} value={t.id}>
              {t.direction === 'SENT' ? 'To' : 'From'} {t.counterpartyName} · {inr(t.amount)} · {when(t.createdAt)}
            </option>
          ))}
        </select>
      </label>
      <label>
        What happened?
        <textarea required maxLength={1000} rows={5} value={details} onChange={(e) => setDetails(e.target.value)}
          placeholder="For example: a caller said money would arrive in my account and I should send it back within an hour for a commission." />
      </label>
      <ErrorBox error={error} />
      <button className="primary block">Send report</button>
    </form>
  );
}

export default function CustomerApp() {
  const { user, logout } = useAuth();
  return (
    <div className="c-shell">
      <header className="c-header">
        <div className="c-header-in">
          <div className="c-brand"><span className="brand-mark"><Icon name="shield" size={18} /></span> FraudShield Bank</div>
          <nav className="c-nav">
            <NavLink to="/" end><Icon name="home" size={16} /> Home</NavLink>
            <NavLink to="/send"><Icon name="send" size={16} /> Send money</NavLink>
            <NavLink to="/activity"><Icon name="list" size={16} /> Activity</NavLink>
            <NavLink to="/report"><Icon name="flag" size={16} /> Report fraud</NavLink>
          </nav>
          <div className="c-user">
            <span>Hello, <b>{user.displayName.split(' ')[0]}</b></span>
            <button className="ghost light" onClick={logout} title="Sign out"><Icon name="logout" size={16} /></button>
          </div>
        </div>
      </header>
      <main className="c-main">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/send" element={<Send />} />
          <Route path="/activity" element={<Activity />} />
          <Route path="/report" element={<Report />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      <footer className="c-footer">FraudShield Bank is a simulation for a capstone project. No real money moves.</footer>
    </div>
  );
}
