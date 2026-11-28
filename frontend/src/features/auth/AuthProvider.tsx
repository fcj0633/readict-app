import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError } from '../../lib/api';
import { discardCsrf } from '../../lib/http';
import { me, type User } from './api';
type State = { status: 'checking' | 'guest' | 'authenticated' | 'unavailable'; user?: User };
type Auth = State & { refresh: () => Promise<void>; clear: () => void; announce: (kind?: string) => void; revision: number };
const Context = createContext<Auth | null>(null);
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<State>({ status: 'checking' });
  const [revision, setRevision] = useState(0);
  const userRef = useRef<string | undefined>(undefined); userRef.current = state.user?.id;
  const generation = useRef(0); const controller = useRef<AbortController | undefined>(undefined); const channel = useRef<BroadcastChannel | undefined>(undefined);
  const clear = useCallback(() => { generation.current++; controller.current?.abort(); discardCsrf(); setState({ status: 'guest' }); setRevision(n => n + 1); }, []);
  const refresh = useCallback(async () => {
    const current = ++generation.current; controller.current?.abort(); controller.current = new AbortController();
    try { const user = await me(controller.current.signal); if (current === generation.current) setState({ status: 'authenticated', user }); }
    catch (error) { if (current !== generation.current) return; if (error instanceof Error && error.name === 'AbortError') return; setState({ status: error instanceof ApiError && error.status === 401 ? 'guest' : 'unavailable' }); }
  }, []);
  const announce = useCallback((kind = 'auth') => channel.current?.postMessage({ kind, userId: userRef.current }), []);
  useEffect(() => {
    const expired = () => clear(); window.addEventListener('readict-session-expired', expired);
    const bus = new BroadcastChannel('readict-account'); channel.current = bus;
    bus.onmessage = event => { if (event.data?.kind === 'settings') { if (event.data.userId === userRef.current) setRevision(n => n + 1); } else { setRevision(n => n + 1); void refresh(); } };
    void refresh();
    return () => { generation.current++; controller.current?.abort(); window.removeEventListener('readict-session-expired', expired); bus.close(); };
  }, [clear, refresh]);
  return <Context.Provider value={{ ...state, refresh, clear, announce, revision }}>{children}</Context.Provider>;
}
export function useAuth() { const value = useContext(Context); if (!value) throw new Error('AuthProvider missing'); return value; }
