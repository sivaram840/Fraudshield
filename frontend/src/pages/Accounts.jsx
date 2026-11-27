import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, inr, when } from '../api.js';
import { useCan } from '../auth.jsx';
import { Badge, ErrorBox, Loading, useLoad } from '../components.jsx';

const STATES = ['Andhra Pradesh', 'Bihar', 'Delhi', 'Gujarat', 'Jharkhand', 'Karnataka', 'Kerala', 'Madhya Pradesh',
  'Maharashtra', 'Odisha', 'Punjab', 'Rajasthan', 'Tamil Nadu', 'Telangana', 'Uttar Pradesh', 'West Bengal'];

export default function Accounts() {
  const [list, err, reload] = useLoad(() => api.accounts());
  const [form, setForm] = useState({ holderName: '', state: 'Delhi', phone: '', initialBalance: '' });
  const [error, setError] = useState(null);
  const [q, setQ] = useState('');
  const canCreate = useCan('ANALYST');

  async function create(e) {
    e.preventDefault();
    setError(null);
    try {
      await api.createAccount({ ...form, initialBalance: Number(form.initialBalance) });
      setForm({ holderName: '', state: 'Delhi', phone: '', initialBalance: '' });
      reload();
    } catch (er) {
      setError(er);
    }
  }

  const shown = (list || []).filter((a) =>
    `${a.holderName} ${a.accountNumber} ${a.state}`.toLowerCase().includes(q.toLowerCase()));

  return (
    <>
      <header className="page-head">
        <h1>Accounts</h1>
      </header>
      {canCreate && <form className="card form inline" onSubmit={create}>
        <h3>Open an account</h3>
        <input required placeholder="Holder name" value={form.holderName}
          onChange={(e) => setForm({ ...form, holderName: e.target.value })} />
        <select value={form.state} onChange={(e) => setForm({ ...form, state: e.target.value })}>
          {STATES.map((s) => <option key={s}>{s}</option>)}
        </select>
        <input placeholder="Phone (for OTP)" value={form.phone}
          onChange={(e) => setForm({ ...form, phone: e.target.value })} />
        <input required type="number" min="0" step="0.01" placeholder="Opening balance ₹" value={form.initialBalance}
          onChange={(e) => setForm({ ...form, initialBalance: e.target.value })} />
        <button className="primary">Create</button>
        <ErrorBox error={error} />
      </form>}

      <section className="card">
        <input className="search" placeholder="Search name, number or state" value={q} onChange={(e) => setQ(e.target.value)} />
        <ErrorBox error={err} />
        {!list ? <Loading /> : (
          <table>
            <thead>
              <tr><th>Holder</th><th>Account</th><th>State</th><th className="num">Balance</th><th>Status</th><th>Opened</th></tr>
            </thead>
            <tbody>
              {shown.map((a) => (
                <tr key={a.id}>
                  <td><Link to={`/accounts/${a.accountNumber}`}>{a.holderName}</Link></td>
                  <td className="mono">{a.accountNumber}</td>
                  <td>{a.state}</td>
                  <td className="num">{inr(a.balance)}</td>
                  <td><Badge value={a.status} /></td>
                  <td>{when(a.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}
