import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import { api, session } from './api.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [ready, setReady] = useState(false);

  useEffect(() => {
    if (!session.get()) {
      setReady(true);
      return;
    }
    api.me().then(setUser).catch(() => session.set(null)).finally(() => setReady(true));
  }, []);

  useEffect(() => {
    const out = () => setUser(null);
    window.addEventListener('fraudshield:signed-out', out);
    return () => window.removeEventListener('fraudshield:signed-out', out);
  }, []);

  const login = useCallback(async (username, password) => {
    const r = await api.login(username, password);
    session.set(r.token);
    setUser(r.user);
    return r.user;
  }, []);

  const logout = useCallback(async () => {
    try { await api.logout(); } catch { /* already expired */ }
    session.set(null);
    setUser(null);
  }, []);

  return <AuthContext.Provider value={{ user, ready, login, logout }}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);

/** True when the signed-in user may perform staff actions reserved for these roles (ADMIN always may). */
export function useCan(...roles) {
  const { user } = useAuth();
  return !!user && (user.role === 'ADMIN' || roles.includes(user.role));
}

export const ROLE_LABEL = {
  CUSTOMER: 'Customer',
  ANALYST: 'Fraud Analyst',
  INVESTIGATOR: 'Investigator',
  ADMIN: 'Administrator',
};
