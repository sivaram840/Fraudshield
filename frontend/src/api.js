// Thin wrapper over the Spring Boot REST API. Errors carry the backend's {code, message}.
const TOKEN_KEY = 'fraudshield.token';

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `HTTP ${status}`);
    this.status = status;
    this.code = body?.code;
  }
}

export const session = {
  get: () => {
    try { return sessionStorage.getItem(TOKEN_KEY); } catch { return null; }
  },
  set: (t) => {
    try { t ? sessionStorage.setItem(TOKEN_KEY, t) : sessionStorage.removeItem(TOKEN_KEY); } catch { /* private mode */ }
  },
};

async function request(method, path, body, headers = {}) {
  const token = session.get();
  const res = await fetch(`/api${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (res.status === 401 && path !== '/auth/login') {
    session.set(null);
    window.dispatchEvent(new Event('fraudshield:signed-out'));
  }
  if (!res.ok) throw new ApiError(res.status, data);
  return data;
}

export const api = {
  // auth
  login: (username, password) => request('POST', '/auth/login', { username, password }),
  me: () => request('GET', '/auth/me'),
  logout: () => request('POST', '/auth/logout'),
  // staff
  dashboard: () => request('GET', '/dashboard'),
  accounts: () => request('GET', '/accounts'),
  account: (n) => request('GET', `/accounts/${n}`),
  accountAlerts: (n) => request('GET', `/accounts/${n}/alerts`),
  createAccount: (body) => request('POST', '/accounts', body),
  setAccountStatus: (n, status, note) => request('PATCH', `/accounts/${n}/status`, { status, note }),
  transactions: (params = {}) => request('GET', `/transactions?${new URLSearchParams(params)}`),
  transaction: (id) => request('GET', `/transactions/${id}`),
  transactionAudit: (id) => request('GET', `/transactions/${id}/audit`),
  transfer: (body, idempotencyKey) => request('POST', '/transactions', body, { 'Idempotency-Key': idempotencyKey }),
  verifyOtp: (id, code) => request('POST', `/transactions/${id}/otp/verify`, { code }),
  resendOtp: (id) => request('POST', `/transactions/${id}/otp/resend`),
  review: (id, action, note) => request('POST', `/transactions/${id}/review`, { action, note }),
  alerts: (params = {}) => request('GET', `/alerts?${new URLSearchParams(params)}`),
  resolveAlert: (id, note) => request('POST', `/alerts/${id}/resolve`, { note }),
  audit: () => request('GET', '/audit?size=300'),
  graph: (params) => request('GET', `/graph?${new URLSearchParams(params)}`),
  trace: (n, hours) => request('GET', `/graph/trace/${n}?hours=${hours}`),
  model: () => request('GET', '/model'),
  scenarios: () => request('GET', '/scenarios'),
  runScenario: (name) => request('POST', `/scenarios/${name}`),
  // cases
  cases: () => request('GET', '/cases'),
  caseDetail: (id) => request('GET', `/cases/${id}`),
  escalate: (body) => request('POST', '/cases', body),
  updateCase: (id, body) => request('PATCH', `/cases/${id}`, body),
  // admin
  users: () => request('GET', '/admin/users'),
  createUser: (body) => request('POST', '/admin/users', body),
  setUserActive: (id, active) => request('PATCH', `/admin/users/${id}/active`, { active }),
  settings: () => request('GET', '/admin/settings'),
  // customer portal
  myOverview: () => request('GET', '/me/overview'),
  myTransactions: () => request('GET', '/me/transactions'),
  payee: (n) => request('GET', `/me/payees/${encodeURIComponent(n)}`),
  myTransfer: (body, key) => request('POST', '/me/transfers', body, { 'Idempotency-Key': key }),
  myVerifyOtp: (id, code) => request('POST', `/me/transfers/${id}/otp/verify`, { code }),
  myResendOtp: (id) => request('POST', `/me/transfers/${id}/otp/resend`),
  myCancel: (id) => request('POST', `/me/transfers/${id}/cancel`),
  myReport: (body) => request('POST', '/me/reports', body),
};

export const inr = (v) =>
  v == null ? '—' : '₹' + Number(v).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export const when = (iso) => (iso ? new Date(iso).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' }) : '—');

export const pct = (v) => (v == null ? '—' : `${(Number(v) * 100).toFixed(1)}%`);
