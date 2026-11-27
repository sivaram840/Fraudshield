import { api, when } from '../api.js';
import { ErrorBox, Loading, useLoad } from '../components.jsx';

export default function Audit() {
  const [page, err, reload] = useLoad(() => api.audit());
  return (
    <>
      <header className="page-head">
        <h1>Audit log</h1>
        <button onClick={reload}>Refresh</button>
      </header>
      <ErrorBox error={err} />
      {!page ? <Loading /> : (
        <section className="card">
          <p className="muted small">Latest {page.items.length} of {page.total} entries. Append-only.</p>
          <table>
            <thead><tr><th>When</th><th>Action</th><th>Entity</th><th>Actor</th><th>Details</th></tr></thead>
            <tbody>
              {page.items.map((l) => (
                <tr key={l.id}>
                  <td className="nowrap">{when(l.createdAt)}</td>
                  <td><code>{l.action}</code></td>
                  <td className="nowrap">{l.entityType} #{l.entityId}</td>
                  <td>{l.actor}</td>
                  <td className="small">{l.details}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}
    </>
  );
}
