import { useEffect, useState } from 'react';
import { Navigate, NavLink, Route, Routes, useLocation } from 'react-router-dom';
import { api } from './api.js';
import { ROLE_LABEL, useAuth } from './auth.jsx';
import Icon from './icons.jsx';
import Login from './pages/Login.jsx';
import CustomerApp from './customer/CustomerApp.jsx';
import Dashboard from './pages/Dashboard.jsx';
import Transfer from './pages/Transfer.jsx';
import Accounts from './pages/Accounts.jsx';
import AccountDetail from './pages/AccountDetail.jsx';
import Transactions from './pages/Transactions.jsx';
import ReviewQueue from './pages/ReviewQueue.jsx';
import Alerts from './pages/Alerts.jsx';
import MoneyFlow from './pages/MoneyFlow.jsx';
import Scenarios from './pages/Scenarios.jsx';
import Model from './pages/Model.jsx';
import Audit from './pages/Audit.jsx';
import Cases from './pages/Cases.jsx';
import CaseDetail from './pages/CaseDetail.jsx';
import Users from './pages/Users.jsx';
import Settings from './pages/Settings.jsx';

/** Navigation per staff role: each portal shows only the work that role does. */
const NAV = {
  ANALYST: {
    home: '/',
    title: 'Fraud Analyst Console',
    groups: [
      ['Overview', [['/', 'Dashboard', 'dashboard']]],
      ['Monitoring', [
        ['/review', 'Review queue', 'review'],
        ['/alerts', 'Alerts', 'alert'],
        ['/transactions', 'Transactions', 'list'],
        ['/accounts', 'Accounts', 'accounts'],
      ]],
      ['Investigation', [
        ['/graph', 'Money-flow graph', 'graph'],
        ['/cases', 'Escalated cases', 'case'],
      ]],
      ['Insight', [['/model', 'ML models', 'model']]],
    ],
  },
  INVESTIGATOR: {
    home: '/cases',
    title: 'Investigation Portal',
    groups: [
      ['Cases', [['/cases', 'Case files', 'case']]],
      ['Tracing', [
        ['/graph', 'Money-flow graph', 'graph'],
        ['/accounts', 'Account lookup', 'accounts'],
        ['/transactions', 'Transactions', 'list'],
        ['/alerts', 'Alerts', 'alert'],
      ]],
      ['Evidence', [['/audit', 'Audit log', 'audit']]],
    ],
  },
  ADMIN: {
    home: '/',
    title: 'Administration',
    groups: [
      ['Overview', [['/', 'Dashboard', 'dashboard']]],
      ['Administration', [
        ['/users', 'Users & roles', 'users'],
        ['/settings', 'Risk settings', 'settings'],
        ['/audit', 'Audit log', 'audit'],
      ]],
      ['Operations', [
        ['/accounts', 'Accounts', 'accounts'],
        ['/transfer', 'Operations transfer', 'transfer'],
        ['/review', 'Review queue', 'review'],
        ['/cases', 'Cases', 'case'],
      ]],
      ['Testing', [
        ['/scenarios', 'Simulation lab', 'scenario'],
        ['/model', 'ML models', 'model'],
        ['/graph', 'Money-flow graph', 'graph'],
      ]],
    ],
  },
};

function StatusBar({ role }) {
  const [s, setS] = useState(null);
  useEffect(() => {
    if (role === 'INVESTIGATOR') return undefined;
    const load = () => api.dashboard().then(setS).catch(() => setS({ down: true }));
    load();
    const t = setInterval(load, 15000);
    return () => clearInterval(t);
  }, [role]);
  const chip = (ok, label) => <span className={`status-chip ${ok ? 'ok' : 'down'}`}><i />{label}</span>;
  if (!s) return null;
  if (s.down) return chip(false, 'Backend offline');
  return (
    <>
      {chip(true, 'API')}
      {chip(true, 'MySQL')}
      {chip(s.mlOnline, s.mlOnline ? 'ML engine' : 'ML offline · rules only')}
      {s.byStatus?.HELD > 0 && <NavLink to="/review" className="status-chip warn"><i />{s.byStatus.HELD} awaiting review</NavLink>}
    </>
  );
}

function StaffConsole() {
  const { user, logout } = useAuth();
  const nav = NAV[user.role];
  const { pathname } = useLocation();
  const titles = Object.fromEntries(nav.groups.flatMap(([, items]) => items.map(([to, label]) => [to, label])));
  const title = titles['/' + pathname.split('/')[1]] || 'Details';
  const initials = user.displayName.replace(/\(.*\)/, '').split(' ').filter(Boolean).slice(0, 2).map((w) => w[0]).join('');

  return (
    <div className={`shell role-${user.role.toLowerCase()}`}>
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark"><Icon name="shield" size={20} /></span>
          <div>
            <div className="brand-name">FraudShield</div>
            <div className="brand-sub">{nav.title}</div>
          </div>
        </div>
        <nav>
          {nav.groups.map(([group, items]) => (
            <div key={group} className="nav-group">
              <div className="nav-label">{group}</div>
              {items.map(([to, label, icon]) => (
                <NavLink key={to} to={to} end={to === '/'}>
                  <Icon name={icon} /> {label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
        <div className="sidebar-foot">
          <span className="env-chip">SIMULATION</span>
          <p>Synthetic accounts and transfers only. Not connected to any real bank.</p>
        </div>
      </aside>
      <div className="main">
        <header className="topbar">
          <div className="crumbs">
            <span>{nav.title}</span><span className="sep">/</span><b>{title}</b>
          </div>
          <div className="topbar-right">
            <StatusBar role={user.role} />
            <div className="user-menu">
              <span className="avatar">{initials}</span>
              <div className="user-meta">
                <b>{user.displayName}</b>
                <span>{ROLE_LABEL[user.role]}</span>
              </div>
              <button className="ghost" onClick={logout} title="Sign out"><Icon name="logout" size={16} /></button>
            </div>
          </div>
        </header>
        <main className="content">
          <Routes>
            {user.role !== 'INVESTIGATOR' && <Route path="/" element={<Dashboard />} />}
            <Route path="/accounts" element={<Accounts />} />
            <Route path="/accounts/:number" element={<AccountDetail />} />
            <Route path="/transactions" element={<Transactions />} />
            <Route path="/alerts" element={<Alerts />} />
            <Route path="/graph" element={<MoneyFlow />} />
            <Route path="/cases" element={<Cases />} />
            {user.role !== 'ANALYST' && <Route path="/cases/:id" element={<CaseDetail />} />}
            {user.role !== 'INVESTIGATOR' && <Route path="/review" element={<ReviewQueue />} />}
            {user.role !== 'INVESTIGATOR' && <Route path="/model" element={<Model />} />}
            {user.role !== 'ANALYST' && <Route path="/audit" element={<Audit />} />}
            {user.role === 'ADMIN' && (
              <>
                <Route path="/users" element={<Users />} />
                <Route path="/settings" element={<Settings />} />
                <Route path="/scenarios" element={<Scenarios />} />
                <Route path="/transfer" element={<Transfer />} />
              </>
            )}
            <Route path="*" element={<Navigate to={nav.home} replace />} />
          </Routes>
        </main>
      </div>
    </div>
  );
}

export default function App() {
  const { user, ready } = useAuth();
  if (!ready) return <div className="boot">Loading FraudShield…</div>;
  if (!user) return <Login />;
  if (user.role === 'CUSTOMER') return <CustomerApp />;
  return <StaffConsole />;
}
