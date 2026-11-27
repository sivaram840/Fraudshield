import { useState } from 'react';
import { api, when } from '../api.js';
import { ROLE_LABEL, useAuth } from '../auth.jsx';
import { Badge, ErrorBox, Loading, PageHeader, useLoad } from '../components.jsx';

const EMPTY = { username: '', password: '', displayName: '', role: 'CUSTOMER', accountNumber: '' };

export default function Users() {
  const { user: me } = useAuth();
  const [list, err, reload] = useLoad(() => api.users());
  const [form, setForm] = useState(EMPTY);
  const [error, setError] = useState(null);
  const [ok, setOk] = useState(null);

  async function create(e) {
    e.preventDefault();
    setError(null);
    setOk(null);
    try {
      const u = await api.createUser({ ...form, accountNumber: form.role === 'CUSTOMER' ? form.accountNumber : null });
      setOk(`Created ${u.username} (${ROLE_LABEL[u.role]})`);
      setForm(EMPTY);
      reload();
    } catch (er) {
      setError(er);
    }
  }

  async function toggle(u) {
    await api.setUserActive(u.id, !u.active);
    reload();
  }

  return (
    <>
      <PageHeader title="Users and roles" subtitle="Who can sign in, and which portal each person gets." />
      <form className="card form inline" onSubmit={create}>
        <h3>Create a login</h3>
        <input required placeholder="Username" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} />
        <input required placeholder="Full name" value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} />
        <input required type="password" minLength={8} placeholder="Password (8+ characters)" value={form.password}
          onChange={(e) => setForm({ ...form, password: e.target.value })} />
        <select value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value })}>
          {Object.entries(ROLE_LABEL).map(([r, l]) => <option key={r} value={r}>{l}</option>)}
        </select>
        {form.role === 'CUSTOMER' && (
          <input required placeholder="Linked account number" value={form.accountNumber}
            onChange={(e) => setForm({ ...form, accountNumber: e.target.value })} />
        )}
        <button className="primary">Create</button>
        <ErrorBox error={error} />
        {ok && <span className="muted">{ok}</span>}
      </form>
      <ErrorBox error={err} />
      {!list ? <Loading /> : (
        <section className="card">
          <table>
            <thead><tr><th>Username</th><th>Name</th><th>Role</th><th>Linked account</th><th>Status</th><th>Last sign-in</th><th /></tr></thead>
            <tbody>
              {list.map((u) => (
                <tr key={u.id}>
                  <td className="mono">{u.username}</td>
                  <td>{u.displayName}</td>
                  <td><Badge value={u.role} /></td>
                  <td className="mono">{u.accountNumber || '—'}</td>
                  <td><Badge value={u.active ? 'ACTIVE' : 'DISABLED'} /></td>
                  <td>{when(u.lastLoginAt)}</td>
                  <td>{u.id !== me.id && <button onClick={() => toggle(u)}>{u.active ? 'Disable' : 'Enable'}</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </>
  );
}
