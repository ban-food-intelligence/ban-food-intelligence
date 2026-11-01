import { useCallback, useEffect, useState } from 'react';

// Go API on the same host as the dashboard (works for localhost and for a LAN IP);
// override with VITE_API_BASE at build time.
export const API_BASE =
  import.meta.env.VITE_API_BASE || `${window.location.protocol}//${window.location.hostname}:8080`;

let currentLang = 'tr';
export function setApiLanguage(lang) {
  currentLang = lang;
}

/** Calls the API; throws an Error with .code/.details from the standard error body. */
export async function apiFetch(path, options = {}) {
  let res;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      ...options,
      headers: { 'Accept-Language': currentLang, ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers },
    });
  } catch (e) {
    const err = new Error('NETWORK');
    err.code = 'NETWORK';
    err.details = `${API_BASE}${path}: ${e.message}`;
    throw err;
  }
  const text = await res.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = null;
  }
  if (!res.ok) {
    const err = new Error(body?.message || `HTTP ${res.status}`);
    err.code = body?.code || `HTTP_${res.status}`;
    err.details = body?.details ?? text.slice(0, 300);
    err.status = res.status;
    throw err;
  }
  return body;
}

/** { data, error, loading, reload } for a GET path; path = null skips the call. */
export function useApi(path) {
  const [state, setState] = useState({ path: null, data: null, error: null, loading: !!path });
  const [tick, setTick] = useState(0);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  useEffect(() => {
    if (!path) {
      setState({ path: null, data: null, error: null, loading: false });
      return undefined;
    }
    let alive = true;
    setState((s) => ({ ...s, loading: true, error: null }));
    apiFetch(path)
      .then((data) => alive && setState({ path, data, error: null, loading: false }))
      .catch((error) => alive && setState({ path, data: null, error, loading: false }));
    return () => {
      alive = false;
    };
  }, [path, tick]);
  return state.path === path ? { ...state, reload } : { data: null, error: null, loading: !!path, reload };
}

// Back-office writes need a supervisor session: POST /auth/session with the PIN returns a
// 30-minute bearer token and the user's role. It is kept in memory only (gone on reload),
// never in storage, and belongs to the PIN that opened it: another typed PIN signs in again,
// so a write is always made as the person who typed the PIN.
let session = null; // { token, expiresAt, pin, username, displayName, role }

/** The current back-office session ({ username, displayName, role }) or null. */
export function currentSession() {
  if (!session || Date.parse(session.expiresAt) <= Date.now()) return null;
  return { username: session.username, displayName: session.displayName, role: session.role };
}

/** True while a supervisor session is open (apiWrite would not use a PIN now). */
export function hasSession() {
  return !!session && Date.parse(session.expiresAt) - Date.now() >= 30_000;
}

/** True while a supervisor session is open (apiWrite would not use a PIN now). */
export function hasSession() {
  return !!session && Date.parse(session.expiresAt) - Date.now() >= 30_000;
}

/** Calls a write endpoint as the supervisor; asks the API for a token first if needed. */
export async function apiWrite(path, body, pin) {
  // No PIN typed (the form hides the field while a session is open): reuse the session.
  // A typed PIN that differs from the session's signs in again as that person.
  if (!hasSession() || (pin && session.pin !== pin)) {
    session = null;
    const s = await apiFetch('/auth/session', { method: 'POST', body: JSON.stringify({ pin }) });
    session = { token: s.token, expiresAt: s.expires_at, pin, username: s.username, displayName: s.display_name, role: s.role };
    if (typeof window !== 'undefined') window.dispatchEvent(new Event('session:changed'));
  }
  try {
    const res = await apiFetch(path, {
      method: 'POST',
      body: JSON.stringify(body),
      headers: { Authorization: `Bearer ${session.token}` },
    });
    // a saved document may have raised an alert: the sidebar bell listens for this
    if (typeof window !== 'undefined') window.dispatchEvent(new Event('data:changed'));
    return res;
  } catch (e) {
    if (e.code === 'AUTH_REQUIRED') session = null; // API restarted: sign in again next time
    throw e;
  }
}
