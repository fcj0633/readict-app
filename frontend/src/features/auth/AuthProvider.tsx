import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../../lib/api';
import { discardCsrf } from '../../lib/http';
import { me, type User } from './api';
import { settingsApi } from '../settings/model';
type State = { status: 'checking' | 'guest' | 'authenticated' | 'unavailable'; user?: User };
type Auth = State & { refresh: () => Promise<void>; clear: () => void; announce: (kind?: string) => void; revision: number };
const Context = createContext<Auth | null>(null);
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<State>({ status: 'checking' });
  const [revision, setRevision] = useState(0);
  const savedTheme = useRef("warm");
  const generation = useRef(0); const controller = useRef<AbortController | undefined>(undefined); const channel = useRef<BroadcastChannel | undefined>(undefined);
  const clear = useCallback(() => { generation.current++; controller.current?.abort(); discardCsrf(); document.documentElement.removeAttribute('data-theme'); setState({ status: 'guest' }); setRevision(n => n + 1); }, []);
  const refresh = useCallback(async () => {
    const current = ++generation.current; controller.current?.abort(); controller.current = new AbortController();
    try { const user = await me(controller.current.signal); if (current === generation.current) setState({ status: 'authenticated', user }); }
    catch (error) { if (current !== generation.current) return; if (error instanceof Error && error.name === 'AbortError') return; setState({ status: error instanceof ApiError && error.status === 401 ? 'guest' : 'unavailable' }); }
  }, []);
  const announce = useCallback((kind = 'auth') => channel.current?.postMessage({ kind }), []);
  useEffect(() => {
    if (state.status !== 'authenticated' || !state.user) return;
    const controller = new AbortController();
    void settingsApi.get(controller.signal).then(value => {
      savedTheme.current = value.preferences.theme;
      if (window.location.pathname !== '/settings') document.documentElement.dataset.theme = savedTheme.current;
    }).catch(() => {});
    return () => controller.abort();
  }, [state.status, state.user?.id, revision]);
  useEffect(() => {
    const saved = (event: Event) => { const theme = (event as CustomEvent<unknown>).detail; if (typeof theme === 'string' && ['white', 'warm', 'dark'].includes(theme)) savedTheme.current = theme; };
    const ended = () => { document.documentElement.dataset.theme = savedTheme.current; };
    window.addEventListener('readict-settings-saved', saved); window.addEventListener('readict-preview-ended', ended);
    return () => { window.removeEventListener('readict-settings-saved', saved); window.removeEventListener('readict-preview-ended', ended); };
  }, []);
  useEffect(() => {
    const expired = () => clear(); window.addEventListener('readict-session-expired', expired);
    const bus = new BroadcastChannel('readict-account'); channel.current = bus;
    bus.onmessage = () => { setRevision(n => n + 1); void refresh(); };
    void refresh();
    return () => { generation.current++; controller.current?.abort(); window.removeEventListener('readict-session-expired', expired); bus.close(); };
  }, [clear, refresh]);
  return <Context.Provider value={{ ...state, refresh, clear, announce, revision }}>{children}</Context.Provider>;
}
export function useAuth() { const value = useContext(Context); if (!value) throw new Error('AuthProvider missing'); return value; }
