import { api } from '../api.js';
import { ErrorBox, Loading, useLoad } from '../components.jsx';

function MetricsTable({ rows }) {
  return (
    <table>
      <thead>
        <tr><th>Model</th><th className="num">Precision</th><th className="num">Recall</th><th className="num">F1</th>
          <th className="num">PR-AUC</th><th className="num">ROC-AUC</th><th className="num">Recall @1% FPR</th><th>Confusion matrix (TP / FP / FN / TN)</th></tr>
      </thead>
      <tbody>
        {Object.entries(rows).map(([name, m]) => (
          <tr key={name}>
            <td>{name.replaceAll('_', ' ')}</td>
            <td className="num">{m.precision}</td>
            <td className="num">{m.recall}</td>
            <td className="num">{m.f1}</td>
            <td className="num">{m.pr_auc}</td>
            <td className="num">{m.roc_auc}</td>
            <td className="num">{m.recall_at_1pct_fpr}</td>
            <td className="mono">{m.confusion_matrix.tp} / {m.confusion_matrix.fp} / {m.confusion_matrix.fn} / {m.confusion_matrix.tn}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export default function Model() {
  const [info, err] = useLoad(() => api.model());
  if (err) return <ErrorBox error={err} />;
  if (!info) return <Loading />;
  const m = info.metrics;
  return (
    <>
      <header className="page-head">
        <h1>ML models</h1>
        <span className="muted">version {info.modelVersion}</span>
      </header>
      {m && <div className="caveat"><b>Data caveat.</b> {m.caveat}</div>}
      <section className="card">
        <h3>Transaction fraud model: Random Forest</h3>
        <p className="muted small">
          {m.transaction_model.source}. Split: {m.transaction_model.split}. Leaky columns removed:{' '}
          {m.transaction_model.excluded_leaky_columns.join(', ')}. Decision threshold {info.thresholds.txn.toFixed(3)}.
        </p>
        <MetricsTable rows={{ random_forest: m.transaction_model.random_forest,
          logistic_regression_baseline: m.transaction_model.logistic_regression_baseline }} />
      </section>
      <section className="card">
        <h3>Money-mule account model and ablation</h3>
        <p className="muted small">
          {m.graph_models.source}. {m.graph_models.edges} transfers, {m.graph_models.laundering_edges} laundering,{' '}
          {m.graph_models.mule_accounts} mule accounts. Split: {m.graph_models.split}.
        </p>
        <MetricsTable rows={m.graph_models.ablation} />
      </section>
      <section className="card">
        <h3>Cross-dataset test</h3>
        {m.graph_models.cross_dataset ? <MetricsTable rows={{ random_forest: m.graph_models.cross_dataset.random_forest }} />
          : <p className="muted">Not run yet: add SAML-D.csv to ml-service/data/raw and retrain.</p>}
      </section>
    </>
  );
}
