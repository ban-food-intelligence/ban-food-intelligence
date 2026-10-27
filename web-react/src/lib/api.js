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
  const [state, setState] = useState({ data: null, error: null, loading: !!path });
  const [tick, setTick] = useState(0);
  const reload = useCallback(() => setTick((t) => t + 1), []);
  useEffect(() => {
    if (!path) return undefined;
    let alive = true;
    setState((s) => ({ ...s, loading: true, error: null }));
    apiFetch(path)
      .then((data) => alive && setState({ data, error: null, loading: false }))
      .catch((error) => alive && setState({ data: null, error, loading: false }));
    return () => {
      alive = false;
    };
  }, [path, tick]);
  return { ...state, reload };
}
