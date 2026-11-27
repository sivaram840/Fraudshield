import cytoscape from 'cytoscape';
import { useEffect, useRef } from 'react';
import { inr } from './api.js';

export const ROLE_COLORS = {
  NORMAL: '#8a94a6',
  POSSIBLE_VICTIM: '#3b82c4',
  SOURCE_REVIEW: '#6b7fd1',
  LIKELY_UNWITTING_MULE: '#e07b2e',
  MULE_PATTERN_REVIEW: '#c9384a',
  LAYERING_HOP: '#b4549c',
  LIKELY_COLLECTOR: '#7a1f2b',
};
const EDGE_COLORS = { COMPLETED: '#4a5568', BLOCKED: '#c9384a', HELD: '#e07b2e', EXPIRED: '#a0aec0',
  REJECTED: '#a0aec0', PENDING_MFA: '#d9a02b', APPROVED: '#4a5568' };
const shortTime = (iso) => new Date(iso).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });

export function GraphLegend() {
  return (
    <div className="legend">
      {Object.entries(ROLE_COLORS).map(([r, c]) => (
        <span key={r}><i style={{ background: c }} />{r.replaceAll('_', ' ').toLowerCase()}</span>
      ))}
      <span><i className="line" /> completed</span>
      <span><i className="line dashed red" /> blocked / held (did not move)</span>
    </div>
  );
}

/**
 * Directed money-flow graph: accounts as nodes, transfers as arrows labelled with amount, time and status.
 * wheelZoom=false for graphs embedded in long pages, so scrolling the page never moves the graph.
 */
export default function FlowGraph({ graph, focus, onSelect, height = 660, wheelZoom = true }) {
  const box = useRef(null);
  const cy = useRef(null);

  useEffect(() => {
    if (!graph || !box.current) return undefined;
    cy.current = cytoscape({
      container: box.current,
      minZoom: 0.2,
      maxZoom: 1.6,
      wheelSensitivity: 0.2,
      userZoomingEnabled: wheelZoom,
      elements: [
        ...graph.nodes.map((n) => ({ data: { id: n.id, label: `${n.holder}\n${n.state}`, role: n.role, focus: n.id === focus } })),
        ...graph.edges.map((e) => ({
          data: { id: e.id, source: e.source, target: e.target, status: e.status,
            label: `${inr(e.amount)} · ${shortTime(e.timestamp)}${e.status !== 'COMPLETED' ? ` · ${e.status}` : ''}` },
        })),
      ],
      style: [
        { selector: 'node', style: {
          'background-color': (n) => ROLE_COLORS[n.data('role')] || '#8a94a6', label: 'data(label)',
          'text-wrap': 'wrap', 'font-size': 10, 'text-valign': 'bottom', 'text-margin-y': 4, width: 26, height: 26,
          color: '#1f2937' } },
        { selector: 'node[?focus]', style: { 'border-width': 4, 'border-color': '#111827', width: 36, height: 36 } },
        { selector: 'edge', style: {
          width: 2, 'curve-style': 'bezier', 'target-arrow-shape': 'triangle', label: 'data(label)', 'font-size': 8,
          'text-rotation': 'autorotate', 'text-background-color': '#fff', 'text-background-opacity': 0.85,
          'line-color': (e) => EDGE_COLORS[e.data('status')] || '#4a5568',
          'target-arrow-color': (e) => EDGE_COLORS[e.data('status')] || '#4a5568',
          'line-style': (e) => (e.data('status') === 'COMPLETED' ? 'solid' : 'dashed') } },
      ],
      layout: graph.nodes.length > 60
        ? { name: 'cose', animate: false }
        : { name: 'breadthfirst', directed: true, spacingFactor: 1.3, animate: false, padding: 30 },
    });
    const refit = () => { cy.current?.resize(); cy.current?.fit(undefined, 30); };
    const ro = new ResizeObserver(refit);
    ro.observe(box.current);
    if (onSelect) cy.current.on('tap', 'node', (evt) => onSelect(evt.target.id()));
    return () => {
      ro.disconnect();
      cy.current?.destroy();
    };
  }, [graph, focus, onSelect, wheelZoom]);

  return (
    <div className="graph-wrap" style={{ height }}>
      <div className="graph-box" ref={box} style={{ height }} />
      {graph && graph.nodes.length === 0 && (
        <p className="graph-empty muted">No transfers in this window. Increase Hours, or run a scenario in the Simulation lab.</p>
      )}
      <button type="button" className="graph-fit no-print" onClick={() => cy.current?.fit(undefined, 30)}>Fit</button>
    </div>
  );
}
