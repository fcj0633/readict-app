import { useEffect, useRef, useState } from 'react';
import { Bookmark } from 'lucide-react';
import type { SelectionSource } from '../reader/engine/selection';
import { favoriteApi } from './api';
import type { FavoriteState } from './model';

export function FavoriteButton({ lookupId, senseId, source, lemma, bookId }: { lookupId?: string; senseId?: string; source: SelectionSource; lemma: string; bookId?: string }) {
  const [state, setState] = useState<FavoriteState | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [reload, setReload] = useState(0);
  const key = useRef<string | null>(null);
  const locked = useRef(false);
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const loadState = (signal?: AbortSignal) => lookupId && senseId
    ? favoriteApi.state(lookupId, senseId, signal)
    : favoriteApi.stateSource(bookId!, source, signal);
  useEffect(() => {
    const controller = new AbortController();
    void loadState(controller.signal).then(value => { if (!controller.signal.aborted) { setState(value); setError(''); } })
      .catch(e => { if (!controller.signal.aborted) { setState(null); setError(e instanceof Error ? e.message : '收藏状态暂不可用'); } });
    return () => controller.abort();
  }, [lookupId, senseId, bookId, source, reload]);
  async function mutate(remove: boolean) {
    if (!state || locked.current) return;
    locked.current = true; setPending(true); setError(''); setNotice('');
    let resourceState: 'active' | 'deleted' = 'active';
    try {
      if (remove && state.favoriteId) { await favoriteApi.remove(state.favoriteId); key.current = null; }
      else {
        key.current ??= crypto.randomUUID();
        const result = lookupId && senseId
          ? await favoriteApi.save(lookupId, senseId, source, key.current)
          : await favoriteApi.append(state.favoriteId!, source, key.current);
        resourceState = result.resourceState;
        key.current = null;
      }
      if (!mounted.current) return;
      setConfirming(false);
      setNotice(remove || resourceState === 'deleted' ? '已取消收藏' : '已保存单词与原文语境');
      // Reconcile receipts whose resource has since been deleted.
      try {
        const value = await loadState();
        if (mounted.current) setState(value);
      } catch (e) {
        if (mounted.current) setState(null);
        throw e;
      }
    } catch (e) { if (mounted.current) setError(e instanceof Error ? e.message : '收藏未确认，请使用原请求重试'); }
    finally { locked.current = false; if (mounted.current) setPending(false); }
  }
  return <div className="favorite-button-area">
    <button type="button" className="dictionary-action" disabled={!state || pending}
      aria-pressed={state?.state === 'CONTEXT'} onClick={() => state?.state === 'CONTEXT' && !key.current ? setConfirming(true) : void mutate(false)}>
      <Bookmark size={15} aria-hidden="true" />{pending ? '保存中…' : key.current ? '重试收藏' : !state ? '收藏暂不可用' : state.state === 'CONTEXT' ? '已收藏' : state.state === 'WORD' ? '收藏本句' : '收藏'}
    </button>
    {!state && !error && <span role="status">收藏状态加载中…</span>}
    {error && <div role="alert">{error}{!state && <button type="button" className="dictionary-action" onClick={() => setReload(v => v + 1)}>重试状态</button>}</div>}
    {notice && <span role="status">{notice}</span>}
    {confirming && <div className="favorite-confirm" role="alertdialog" aria-label="取消收藏确认">
      <p>删除 {lemma} 及全部 {state?.contextCount} 条原文语境？</p>
      <button type="button" className="dictionary-action" disabled={pending} onClick={() => setConfirming(false)}>保留收藏</button>
      <button type="button" className="dictionary-action" disabled={pending} onClick={() => void mutate(true)}>确认删除</button>
    </div>}
  </div>;
}
