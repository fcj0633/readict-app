import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams, useLocation } from 'react-router-dom';
import { Bookmark, Search, X } from 'lucide-react';
import { favoriteApi } from './api';
import { contextLink, returnPath, type FavoriteWord, type FavoriteContext, type FavoriteDetail, type FavoriteSource } from './model';
import './favorites.css';
const message = (e: unknown) => e instanceof Error ? e.message : '收藏暂不可用，请重试';
function Context({ value }: { value: FavoriteContext }) {
  const link = contextLink(value);
  return <article className="favorite-context">
    <p lang="en" className="favorite-sentence">{value.sentence.slice(0, value.wordStart)}<mark>{value.surface}</mark>{value.sentence.slice(value.wordEnd)}</p>
    <p className="favorite-origin">{value.bookTitle} / {value.chapterTitle}</p>
    <div className="favorite-context-foot"><span>{value.lemma}</span>{link ? <Link to={link}>回原文</Link> : <span>原书已删除或来源不可用</span>}</div>
    {value.translation && <p>{value.translation}</p>}
  </article>;
}
export default function FavoritesPage() {
  const navigation = useLocation();
  const [params] = useSearchParams(); const back = returnPath(params.get('from'));
  const [view, setView] = useState<'words' | 'sentences'>('words');
  const [q, setQ] = useState(''); const [search, setSearch] = useState('');
  const [source, setSource] = useState(''); const [sort, setSort] = useState('recent');
  const [items, setItems] = useState<(FavoriteWord | FavoriteContext)[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [sources, setSources] = useState<FavoriteSource[]>([]);
  const [stats, setStats] = useState<{ words: number; contexts: number } | null>(null);
  const [loading, setLoading] = useState(true); const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [detail, setDetail] = useState<FavoriteDetail | null>(null);
  const [confirm, setConfirm] = useState<FavoriteWord | null>(null);
  const [busy, setBusy] = useState(false); const [actionError, setActionError] = useState('');
  const generation = useRef(0); const action = useRef(0);
  useEffect(() => {
    if (!detail && !confirm) return;
    const previous = document.activeElement;
    const modal = document.querySelector<HTMLElement>(confirm ? '.favorite-delete' : '.favorite-dialog');
    const keydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) { event.preventDefault(); if (confirm) setConfirm(null); else setDetail(null); }
      if (event.key !== 'Tab' || !modal) return;
      const controls = [...modal.querySelectorAll<HTMLElement>('button:not(:disabled),a[href],input,select,[tabindex="0"]')];
      const first = controls[0], last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    };
    document.addEventListener('keydown', keydown);
    return () => { document.removeEventListener('keydown', keydown); if (previous instanceof HTMLElement && previous.isConnected) previous.focus(); };
  }, [detail, confirm, busy]);
  useEffect(() => {
    const controller = new AbortController(); const current = ++generation.current;
    setLoading(true); setError(''); setItems([]); setCursor(null);
    void Promise.all([favoriteApi.list(view, search, source, sort, '', controller.signal), favoriteApi.sources(controller.signal), favoriteApi.stats(controller.signal)])
      .then(([page, origins, totals]) => { if (current === generation.current && !controller.signal.aborted) { setItems(page.items); setCursor(page.nextCursor); setSources(origins); setStats(totals); } })
      .catch(e => { if (!controller.signal.aborted) setError(message(e)); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [view, search, source, sort, reload]);
  async function more() {
    if (!cursor || loading) return;
    const current = generation.current; setLoading(true); setError('');
    try { const page = await favoriteApi.list(view, search, source, sort, cursor); if (current === generation.current) { setItems(v => [...v, ...page.items]); setCursor(page.nextCursor); } }
    catch (e) { if (current === generation.current) setError(message(e)); }
    finally { if (current === generation.current) setLoading(false); }
  }
  async function open(id: string) {
    const current = ++action.current; setActionError(''); setBusy(true);
    try { const value = await favoriteApi.detail(id); if (current === action.current) setDetail(value); }
    catch (e) { if (current === action.current) setActionError(message(e)); }
    finally { if (current === action.current) setBusy(false); }
  }
  async function remove() {
    if (!confirm || busy) return;
    setBusy(true); setActionError('');
    try { await favoriteApi.remove(confirm.id); setConfirm(null); setDetail(null); setReload(v => v + 1); }
    catch (e) { setActionError(message(e)); } finally { setBusy(false); }
  }
  return <section className="favorites-page">
    {back && <Link className="favorite-back" to={back} state={navigation.state}>返回阅读</Link>}
    <header className="favorites-heading"><div><h1>收藏</h1><p>留下单词，也留下遇见它的那句话。</p></div><span>{stats ? `${stats.words} 个单词 / ${stats.contexts} 条语境` : '正在读取收藏…'}</span></header>
    <div className="favorites-controls">
      <div className="favorites-tabs" role="group" aria-label="收藏视图"><button type="button" aria-pressed={view === 'words'} onClick={() => setView('words')}>单词</button><button type="button" aria-pressed={view === 'sentences'} onClick={() => setView('sentences')}>句子</button></div>
      <form className="favorites-search" onSubmit={e => { e.preventDefault(); setSearch(q.trim()); }}><Search size={18} aria-hidden="true" /><input aria-label="搜索单词或原句" placeholder="搜索单词或原句" maxLength={100} value={q} onChange={e => setQ(e.target.value)} /><button type="submit">搜索</button></form>
      <select aria-label="来源筛选" value={source} onChange={e => setSource(e.target.value)}><option value="">全部来源</option>{sources.map(s => <option key={s.originKey + s.bookTitle} value={s.originKey}>{s.bookTitle}{!s.sourceAvailable ? '（已删除）' : ''}</option>)}</select>
      <select aria-label="时间排序" value={sort} onChange={e => setSort(e.target.value)}><option value="recent">最近收藏</option><option value="old">最早收藏</option></select>
    </div>
    {error && <p role="alert">{error} <button type="button" onClick={() => cursor ? void more() : setReload(v => v + 1)}>重试</button></p>}
    {actionError && <p role="alert">{actionError}</p>}
    {loading && items.length === 0 && <p role="status">正在读取收藏…</p>}
    {!loading && !error && items.length === 0 && <div className="favorites-empty"><Bookmark size={32} strokeWidth={1.2} aria-hidden="true" /><h2>{search || source ? '没有找到匹配的收藏' : '把值得记住的词留在这里'}</h2><p>{search || source ? '试试其他关键词，或查看全部来源。' : '阅读时点击单词，在查词卡片中选择“收藏”，原句会一起保存。'}</p>{search || source ? <button type="button" onClick={() => { setQ(''); setSearch(''); setSource(''); }}>清除筛选</button> : <Link to="/books">去书架阅读</Link>}</div>}
    <div className={view === 'words' ? 'favorite-words' : 'favorite-sentences'}>{items.map(item => 'contextId' in item ? <Context key={item.contextId} value={item} /> : <article className="favorite-word" key={item.id}><div><h2 lang="en">{item.lemma}</h2>{item.ipa && <span className="favorite-ipa">{item.ipa}</span>}</div><p><span className="favorite-pos">{item.pos}</span>{item.meaning}</p><div className="favorite-word-foot"><span>{item.contextCount} 条语境 · {new Date(item.createdAt).toLocaleDateString('zh-CN')}</span><button type="button" disabled={busy} onClick={() => void open(item.id)}>详情</button><button type="button" disabled={busy} onClick={() => { setActionError(''); setConfirm(item); }}>删除</button></div></article>)}</div>
    {cursor && <button type="button" className="favorite-load" disabled={loading} onClick={() => void more()}>{loading ? '正在加载…' : '加载更多'}</button>}
    {detail && <div className="favorite-overlay" onKeyDown={e => { if (e.key === 'Escape' && !confirm) setDetail(null); }}><section className="favorite-dialog" role="dialog" aria-modal="true" aria-label={`${detail.favorite.lemma} 收藏详情`}><button type="button" autoFocus className="favorite-close" aria-label="关闭详情" onClick={() => setDetail(null)}><X size={20} /></button><h2 lang="en">{detail.favorite.lemma}</h2><p>{detail.favorite.ipa} {detail.favorite.pos}</p><p>{detail.favorite.meaning}</p><h3>原文语境（{detail.contexts.length}）</h3>{detail.contexts.map(c => <Context key={c.contextId} value={c} />)}<button type="button" disabled={busy} onClick={() => setConfirm(detail.favorite)}>删除单词及全部语境</button></section></div>}
    {confirm && <div className="favorite-overlay"><section className="favorite-dialog favorite-delete" role="alertdialog" aria-modal="true" aria-label="删除收藏确认"><h2>删除 {confirm.lemma}？</h2><p>将删除该单词及全部 {confirm.contextCount} 条原文语境。</p><p>书架不受影响。</p>{actionError && <p role="alert">{actionError}</p>}<div><button type="button" autoFocus disabled={busy} onClick={() => setConfirm(null)}>保留收藏</button><button type="button" disabled={busy} onClick={() => void remove()}>{busy ? '正在删除…' : '确认删除'}</button></div></section></div>}
  </section>;
}
