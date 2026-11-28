import { useEffect, useRef, useState } from 'react';
import { Bookmark, Search, Volume2 } from 'lucide-react';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import { Dialog } from '../../components/ui/Dialog';
import { useReadingPreferences } from '../settings/PreferencesProvider';
import { SenseGroups } from '../dictionary/SenseGroups';
import { favoriteApi } from './api';
import { returnPath, type FavoriteDetail, type FavoriteSource, type FavoriteWord } from './model';
import { useFavoriteAudio } from './useFavoriteAudio';
import './favorites.css';

const message = (error: unknown) => error instanceof Error ? error.message : '收藏暂不可用，请重试';

export default function FavoritesPage() {
  const navigation = useLocation();
  const [params] = useSearchParams();
  const back = params.getAll('from').length === 1 ? returnPath(params.get('from')) : null;
  const preferencesState = useReadingPreferences().state;
  const preferences = preferencesState.committed?.preferences ?? preferencesState.draft;
  const audio = useFavoriteAudio(preferences?.pronunciation ?? 'en-GB');
  const [q, setQ] = useState('');
  const [search, setSearch] = useState('');
  const [source, setSource] = useState('');
  const [sort, setSort] = useState('recent');
  const [items, setItems] = useState<FavoriteWord[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [sources, setSources] = useState<FavoriteSource[]>([]);
  const [stats, setStats] = useState<{ words: number } | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  const [detail, setDetail] = useState<FavoriteDetail | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [actionError, setActionError] = useState('');
  const [removing, setRemoving] = useState<string | null>(null);
  const [compact, setCompact] = useState(false);
  const generation = useRef(0);
  const metaGeneration = useRef(0);
  const detailRequest = useRef<AbortController | null>(null);
  const moreLock = useRef(false);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    const media = window.matchMedia('(max-width: 720px)');
    const update = () => setCompact(media.matches);
    update();
    media.addEventListener('change', update);
    return () => { alive.current = false; media.removeEventListener('change', update); detailRequest.current?.abort(); audio.stop(); };
  }, []);

  useEffect(() => () => audio.stop(), []);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(''); setItems([]); setCursor(null);
    const current = ++generation.current;
    void favoriteApi.list(search, source, sort, '', controller.signal)
      .then(page => { if (current === generation.current && !controller.signal.aborted) { setItems(page.items); setCursor(page.nextCursor); } })
      .catch(caught => { if (!controller.signal.aborted && current === generation.current) setError(message(caught)); })
      .finally(() => { if (!controller.signal.aborted && current === generation.current) setLoading(false); });
    return () => controller.abort();
  }, [search, source, sort, reload]);

  useEffect(() => {
    const controller = new AbortController();
    const current = ++metaGeneration.current;
    void favoriteApi.stats(controller.signal).then(value => { if (current === metaGeneration.current) setStats(value); })
      .catch(() => { if (current === metaGeneration.current) setStats(null); });
    void favoriteApi.sources(controller.signal).then(value => { if (current === metaGeneration.current) setSources(value); })
      .catch(() => { if (current === metaGeneration.current) setSources([]); });
    return () => controller.abort();
  }, [reload]);

  async function more() {
    if (!cursor || loading || moreLock.current) return;
    moreLock.current = true;
    const current = generation.current;
    setLoading(true); setError('');
    try {
      const page = await favoriteApi.list(search, source, sort, cursor);
      if (current === generation.current) { setItems(value => [...value, ...page.items]); setCursor(page.nextCursor); }
    } catch (caught) { if (current === generation.current) setError(message(caught)); }
    finally { moreLock.current = false; if (current === generation.current) setLoading(false); }
  }

  async function open(id: string) {
    detailRequest.current?.abort();
    const controller = new AbortController();
    detailRequest.current = controller;
    setDetailOpen(true); setDetail(null); setDetailLoading(true); setActionError('');
    try {
      const value = await favoriteApi.detail(id, controller.signal);
      if (!controller.signal.aborted && alive.current) setDetail(value);
    } catch (caught) { if (!controller.signal.aborted && alive.current) setActionError(message(caught)); }
    finally { if (!controller.signal.aborted) setDetailLoading(false); }
  }

  function closeDetail() {
    audio.stop();
    detailRequest.current?.abort();
    setDetailOpen(false); setDetail(null); setActionError('');
  }

  async function remove(word: FavoriteWord) {
    if (removing) return;
    setRemoving(word.id); setActionError('');
    try {
      await favoriteApi.remove(word.id);
      if (!alive.current) return;
      audio.stop();
      setItems(value => value.filter(item => item.id !== word.id));
      setStats(value => value ? { words: Math.max(0, value.words - 1) } : value);
      if (detail?.favorite.id === word.id) closeDetail();
      setReload(value => value + 1);
    } catch (caught) {
      if (alive.current) setActionError(message(caught));
    } finally {
      if (alive.current) setRemoving(null);
    }
  }

  const limit = compact ? 2 : 3;
  const clearFilters = () => { setQ(''); setSearch(''); setSource(''); };

  return <section className="favorites-page">
    {back && <Link className="favorite-back" to={back} state={navigation.state}>返回阅读</Link>}
    <header className="favorites-heading">
      <div><h1>我的收藏</h1><p>把遇到的词，留在它的语境里。</p></div>
      <span>{stats ? `${stats.words} 个单词` : '正在读取收藏…'}</span>
    </header>
    <div className="favorites-controls">
      <form className="favorites-search" onSubmit={event => { event.preventDefault(); setSearch(q.trim()); }}>
        <Search size={18} aria-hidden="true" />
        <input aria-label="搜索收藏" placeholder="搜索单词或例句" maxLength={100} value={q} onChange={event => setQ(event.target.value)} />
        <button type="submit">搜索</button>
      </form>
      <select aria-label="来源筛选" value={source} onChange={event => setSource(event.target.value)}>
        <option value="">全部来源</option>
        {sources.map(item => <option key={item.bookTitle} value={item.bookTitle}>{item.bookTitle}</option>)}
      </select>
      <select aria-label="收藏排序" value={sort} onChange={event => setSort(event.target.value)}>
        <option value="recent">最近收藏</option><option value="old">最早收藏</option>
      </select>
    </div>
    {error && <p role="alert" className="favorite-error">{error}<button type="button" onClick={() => cursor ? void more() : setReload(value => value + 1)}>重试</button></p>}
    {actionError && !detailOpen && <p role="alert" className="favorite-error">{actionError}</p>}
    {loading && items.length === 0 && <p role="status">正在读取收藏…</p>}
    {!loading && !error && items.length === 0 && <div className="favorites-empty">
      <Bookmark size={32} strokeWidth={1.2} aria-hidden="true" />
      <h2>{search || source ? '没有找到匹配的收藏' : '把值得记住的词留在这里'}</h2>
      <p>{search || source ? '试试其他关键词，或查看全部来源。' : '阅读时点击单词，在词典卡片中选择“收藏”，当前原句会一起保存。'}</p>
      {search || source ? <button type="button" onClick={clearFilters}>清除筛选</button> : <Link to="/books">去书架阅读</Link>}
    </div>}
    <div className="favorite-words">
      {items.map(word => {
        const playing = audio.loadingWord === word.id;
        const audioError = audio.errors[word.id];
        return <article className="favorite-word-row" key={word.id}>
          <button type="button" className="favorite-word-main" onClick={() => void open(word.id)}>
            <span className="favorite-word-heading"><strong lang="en">{word.lemma}</strong>{word.ipa && <em>{word.ipa}</em>}</span>
            <SenseGroups groups={word.groups.slice(0, limit)} />
            {word.definitionStatus === 'snapshot' && <small className="favorite-snapshot-note">当前词典不可用，显示已保存释义</small>}
          </button>
          <div className="favorite-word-actions">
            <button type="button" className={`favorite-audio${playing ? ' active' : ''}`} aria-label={`播放 ${word.lemma} 发音`}
              onClick={() => void audio.play(word.id)}>
              <Volume2 size={16} aria-hidden="true" />{playing ? '播放中' : '发音'}
            </button>
            <button type="button" className="favorite-remove" aria-label={`取消收藏 ${word.lemma}`}
              disabled={removing === word.id} onClick={() => void remove(word)}>
              {removing === word.id ? '取消中…' : '取消收藏'}
            </button>
          </div>
          {audioError && <small role="alert" className="favorite-audio-error">{audioError}</small>}
        </article>;
      })}
    </div>
    {cursor && <button type="button" className="favorite-load" disabled={loading} onClick={() => void more()}>{loading ? '正在加载…' : '加载更多'}</button>}
    <Dialog open={detailOpen} onClose={closeDetail} title="收藏详情" className="favorite-detail-dialog">
      {detailLoading && <p role="status">正在读取收藏详情…</p>}
      {actionError && detailOpen && <p role="alert" className="favorite-error">{actionError}</p>}
      {detail && <>
        <div className="favorite-detail-head">
          <div><h2 lang="en">{detail.favorite.lemma}</h2>{detail.favorite.ipa && <span>{detail.favorite.ipa}</span>}</div>
          <button type="button" className="favorite-audio" onClick={() => void audio.play(detail.favorite.id)}>
            <Volume2 size={16} aria-hidden="true" />{audio.loadingWord === detail.favorite.id ? '播放中' : '发音'}
          </button>
        </div>
        <SenseGroups groups={detail.favorite.groups} />
        {detail.favorite.definitionStatus === 'snapshot' && <p className="favorite-snapshot-note">词典暂不可用，以下为首次收藏时保存的释义。</p>}
        <h3 className="favorite-example-heading">原文例句</h3>
        {detail.examples.length ? detail.examples.map(example => <article className="favorite-example" key={example.id}>
          <p lang="en">{example.sentence.slice(0, example.wordStart)}<mark>{example.surface}</mark>{example.sentence.slice(example.wordEnd)}</p>
          <span>{example.bookTitle}</span>
        </article>) : <p className="favorite-empty-example">这条收藏暂时没有保存原文例句。</p>}
        <div className="dialog-actions">
          <button type="button" className="secondary-button" onClick={closeDetail}>关闭详情</button>
          <button type="button" className="danger-button" disabled={removing === detail.favorite.id} onClick={() => void remove(detail.favorite)}>
            {removing === detail.favorite.id ? '取消中…' : '取消收藏'}
          </button>
        </div>
      </>}
      {!detail && !detailLoading && <div className="dialog-actions"><button type="button" className="secondary-button" onClick={closeDetail}>关闭</button></div>}
    </Dialog>
  </section>;
}
