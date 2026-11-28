import { useEffect, useRef, useState } from 'react';
import { Bookmark } from 'lucide-react';
import type { SelectionSource } from '../reader/engine/selection';
import { favoriteApi } from './api';
import type { FavoriteState } from './model';

export function FavoriteButton({ lookupId, senseId, source, lemma, bookId }: { lookupId?: string; senseId?: string; source: SelectionSource; lemma: string; bookId?: string }) {
  const [state, setState] = useState<FavoriteState | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const key = useRef<string | null>(null);
  const locked = useRef(false);
  const mounted = useRef(true);
  const canSave = Boolean(lookupId && senseId);

  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  useEffect(() => { key.current = null; }, [lookupId, senseId]);

  useEffect(() => {
    const controller = new AbortController();
    setState(null); setError(''); setPending(false);
    const load = lookupId && senseId
      ? favoriteApi.state(lookupId, senseId, controller.signal)
        .catch(() => bookId ? favoriteApi.stateSource(bookId, source, controller.signal) : Promise.reject(new Error('收藏状态暂不可用')))
      : favoriteApi.stateSource(bookId!, source, controller.signal);
    void load.then(value => { if (!controller.signal.aborted && mounted.current) setState(value); })
      .catch(caught => { if (!controller.signal.aborted && mounted.current) setError(caught instanceof Error ? caught.message : '收藏状态暂不可用'); });
    return () => controller.abort();
  }, [lookupId, senseId, bookId, source, reload]);

  async function mutate() {
    if (!state || locked.current) return;
    const previous = state;
    locked.current = true; setPending(true); setError('');
    try {
      if (state.state === 'FAVORITED' && state.favoriteId) {
        await favoriteApi.remove(state.favoriteId);
        key.current = null;
        if (mounted.current) setState({ state: 'NOT_FAVORITED', favoriteId: null });
      } else if (canSave) {
        key.current ??= crypto.randomUUID();
        const saved = await favoriteApi.save(lookupId!, senseId!, source, key.current);
        if (!mounted.current) return;
        key.current = null;
        setState(saved.resourceState === 'deleted' ? { state: 'NOT_FAVORITED', favoriteId: null } : { state: 'FAVORITED', favoriteId: saved.favoriteId });
      }
    } catch (caught) {
      if (mounted.current) {
        setState(previous);
        setError(caught instanceof Error ? caught.message : '操作未确认，请重试');
      }
    } finally {
      locked.current = false;
      if (mounted.current) setPending(false);
    }
  }

  const label = pending ? (state?.state === 'FAVORITED' ? '正在取消…' : '正在收藏…')
    : !state ? '收藏状态暂不可用'
    : state.state === 'FAVORITED' ? '已收藏'
    : canSave ? '收藏' : '收藏不可用';

  return <div className="favorite-button-area">
    <button type="button" className="dictionary-action" disabled={!state || pending || (!canSave && state.state === 'NOT_FAVORITED')}
      aria-pressed={state?.state === 'FAVORITED'} aria-label={`${state?.state === 'FAVORITED' ? '取消收藏' : '收藏'} ${lemma}`}
      onClick={() => void mutate()}>
      <Bookmark size={15} aria-hidden="true" />{label}
    </button>
    {!state && !error && <span role="status">收藏状态加载中…</span>}
    {error && <div role="alert">{error}<button type="button" className="dictionary-action" onClick={() => setReload(value => value + 1)}>重试状态</button></div>}
  </div>;
}
