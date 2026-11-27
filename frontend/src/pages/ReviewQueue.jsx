import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api, inr, when } from '../api.js';
import { ErrorBox, Loading, RiskBreakdown, useLoad } from '../components.jsx';

export default function ReviewQueue() {
  const [page, err, reload] = useLoad(() => api.transactions({ status: 'HELD', size: 100 }));
  const [notes, setNotes] = useState({});
  const [error, setError] = useState(null);

  async function act(id, action) {
    setError(null);
    try {
      await api.review(id, action, notes[id] || null);
      reload();
    } catch (e) {
      setError(e);
    }
  }

  return (
    <>
      <header className="page-head">
        <h1>Review queue</h1>
        <button onClick={reload}>Refresh</button>
      </header>
      <p className="muted">
        Held transfers never execute on their own. An analyst must approve them before they expire; unresolved holds expire
        and the money never moves.
      </p>
      <ErrorBox error={error || err} />
      {!page ? <Loading /> : page.items.length === 0 ? <p className="card muted">Nothing is waiting for review.</p> : (
        page.items.map((t) => (
          <section key={t.id} className="card">
            <div className="result-head">
              <h3>
                {inr(t.amount)}: <Link to={`/accounts/${t.fromAccount}`}>{t.fromHolder}</Link> →{' '}
                <Link to={`/accounts/${t.toAccount}`}>{t.toHolder}</Link>
              </h3>
              <span className="muted small">{t.reference} · expires {when(t.holdExpiresAt)}</span>
            </div>
            <RiskBreakdown risk={t.risk} />
            <div className="row gap">
              <input placeholder="Analyst note" value={notes[t.id] || ''}
                onChange={(e) => setNotes({ ...notes, [t.id]: e.target.value })} />
              <button className="primary" onClick={() => act(t.id, 'APPROVE')}>Approve and execute</button>
              <button className="danger" onClick={() => act(t.id, 'REJECT')}>Reject</button>
              <Link className="button" to={`/graph?account=${t.fromAccount}`}>Trace sender</Link>
            </div>
          </section>
        ))
      )}
    </>
  );
}
