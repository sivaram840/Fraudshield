import { useCallback, useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, inr, when } from '../api.js';
import { Badge, ErrorBox } from '../components.jsx';
import FlowGraph, { GraphLegend } from '../FlowGraph.jsx';

/** Side panel: where money came from, where it went, roles and investigator leads. */
export function TracePanel({ trace }) {
  return (
    <aside className="card trace">
      <h3>Trace: {trace.account?.holder}</h3>
      <p>{trace.summary}</p>
      {trace.account && (
        <>
          <Badge value={trace.account.role} />
          <ul className="reasons">{trace.account.roleReasons.map((r, i) => <li key={i}>{r}</li>)}</ul>
        </>
      )}
      <h4>Where the money came from</h4>
      {trace.upstream.length === 0 ? <p className="muted">No incoming transfers in window.</p> : (
        <ul className="reasons">
          {trace.upstream.map((h) => (
            <li key={h.reference}>
              <Link to={`/accounts/${h.from}`}>{h.from}</Link> sent {inr(h.amount)} at {when(h.at)}
            </li>
          ))}
        </ul>
      )}
      <h4>Where it went (time-ordered paths)</h4>
      {trace.downstreamPaths.length === 0 ? <p className="muted">Nothing moved on.</p> : (
        trace.downstreamPaths.map((p, i) => (
          <div key={i} className="path">
            {p.map((h) => (
              <span key={h.reference}>
                → <Link to={`/accounts/${h.to}`}>{h.toHolder}</Link> {inr(h.amount)} <Badge value={h.status} />
              </span>
            ))}
          </div>
        ))
      )}
      {trace.endpoints.length > 0 && (
        <>
          <h4>Leads for investigators (where money rests)</h4>
          <ul className="reasons">
            {trace.endpoints.map((n) => (
              <li key={n.id}><Link to={`/accounts/${n.id}`}>{n.holder}</Link> ({n.state}) <Badge value={n.role} /></li>
            ))}
          </ul>
        </>
      )}
      <h4>Accounts involved</h4>
      <table>
        <tbody>
          {trace.involved.map((n) => (
            <tr key={n.id}>
              <td><Link to={`/accounts/${n.id}`}>{n.holder}</Link><div className="muted small">{n.id} · {n.ageDays}d old</div></td>
              <td><Badge value={n.role} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </aside>
  );
}

export default function MoneyFlow() {
  const [params, setParams] = useSearchParams();
  const [account, setAccount] = useState(params.get('account') || '');
  const [hours, setHours] = useState(Number(params.get('hours')) || 72);
  const [depth, setDepth] = useState(3);
  const [graph, setGraph] = useState(null);
  const [trace, setTrace] = useState(null);
  const [error, setError] = useState(null);
  const [notice, setNotice] = useState(null);

  async function load(acct = account) {
    setError(null);
    setNotice(null);
    try {
      // Fetch both first so the graph is laid out once, at its final width.
      const fetchWindow = (h) => Promise.all([
        api.graph({ ...(acct ? { account: acct } : {}), hours: h, depth }),
        acct ? api.trace(acct, h) : Promise.resolve(null),
      ]);
      let [g, t] = await fetchWindow(hours);
      // Demo data ages: if the window is empty, widen it to the most recent activity instead of showing a blank graph.
      for (const wider of [168, 720, 2160]) {
        if (g.nodes.length > 0 || wider <= Number(hours)) continue;
        [g, t] = await fetchWindow(wider);
        if (g.nodes.length > 0) {
          setNotice(`No transfers in the last ${hours} hours, so the window was widened to ${wider} hours. Run a scenario in the Simulation lab for fresh activity.`);
          setHours(wider);
          break;
        }
      }
      setTrace(t);
      setGraph(g);
    } catch (e) {
      setError(e);
    }
  }

  useEffect(() => {
    load(params.get('account') || '');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  const select = useCallback((id) => {
    setAccount(id);
    setParams({ account: id });
  }, [setParams]);

  return (
    <>
      <header className="page-head">
        <div>
          <h1>Money-flow graph</h1>
          <p className="page-sub">Click an account to trace its money upstream and downstream.</p>
        </div>
      </header>
      <form className="card row gap" onSubmit={(e) => { e.preventDefault(); setParams(account ? { account } : {}); }}>
        <input placeholder="Account number (blank = whole network)" value={account} onChange={(e) => setAccount(e.target.value)} />
        <label className="row">Hours <input type="number" min="1" max="2160" value={hours} onChange={(e) => setHours(e.target.value)} /></label>
        <label className="row">Hops <input type="number" min="1" max="6" value={depth} onChange={(e) => setDepth(e.target.value)} /></label>
        <button className="primary">Trace</button>
      </form>
      <ErrorBox error={error} />
      {notice && <p className="card graph-notice">{notice}</p>}
      <GraphLegend />
      <div className={`graph-layout${trace ? '' : ' full'}`}>
        <FlowGraph graph={graph} focus={params.get('account')} onSelect={select} />
        {trace && <TracePanel trace={trace} />}
      </div>
    </>
  );
}
