import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { ApiError } from '../../lib/api';
import { request } from '../../lib/http';
import { useAuth } from '../auth/AuthProvider';
import { decodeBook } from '../library/api';
import { PreferenceSaver, settingsApi, type Preferences, type SaveState } from '../settings/model';
import { downloadEpub, loadChapterIndex, loadManifest, loadProgress } from './api';
import { EpubJsEngine } from './engine/EpubJsEngine';
import type {
  ReaderManifest, ReaderPhase, ReaderPreferences, ReadingAnchor,
} from './engine/types';
import { ProgressSaver, type ProgressState } from './progress/model';
import { ContentsPanel, SettingsPanel } from './ReaderPanels';
import { ReaderPaperHeading, ReaderRail, ReaderTopbar, SaveStatus, type ReaderPanelName } from './ReaderLayout';
import type { TextSelection } from './engine/selection';
import { lookupWord } from '../dictionary/api';
import type { LookupViewState } from '../dictionary/model';
import { DictionaryCard } from '../dictionary/DictionaryCard';
import { PronunciationPlayer, type PlaybackState } from '../dictionary/PronunciationPlayer';
import { findTocLabel, groupToc, sameChapter } from './toc';
import './reader.css';

const EMPTY_PROGRESS: ProgressState = { status: 'idle', version: 0, anchor: null, ratio: 0, dirty: false };

function toReaderPreferences(preferences: Preferences): ReaderPreferences {
  const theme = preferences.theme === 'white' || preferences.theme === 'dark' ? preferences.theme : 'warm';
  return {
    font: preferences.font, fontSize: preferences.fontSize,
    lineHeight: preferences.lineHeight, width: preferences.width, theme,
  };
}

async function sha256Hex(buffer: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', buffer);
  return Array.from(new Uint8Array(digest)).map(value => value.toString(16).padStart(2, '0')).join('');
}

function firstAnchor(manifest: ReaderManifest): ReadingAnchor | null {
  const first = manifest.spine[0];
  if (!first) return null;
  return {
    artifactId: manifest.artifactId, artifactHash: manifest.artifactHash,
    pipelineVersion: manifest.pipelineVersion, spineHref: first.href, cfi: '',
  };
}

export default function ReaderPage() {
  const { bookId = '' } = useParams();
  const auth = useAuth();
  const navigate = useNavigate();
  const hostRef = useRef<HTMLDivElement | null>(null);
  const engineRef = useRef<EpubJsEngine | null>(null);
  const saverRef = useRef<ProgressSaver | null>(null);
  const settingsRef = useRef<PreferenceSaver | null>(null);
  const phaseRef = useRef<ReaderPhase>('idle');
  const appliedPrefs = useRef('');
  const lastAnchor = useRef<string | null>(null);
  const readyAt = useRef(0);

  const [phase, setPhase] = useState<ReaderPhase>('loading');
  const [manifest, setManifest] = useState<ReaderManifest | null>(null);
  const [title, setTitle] = useState('阅读');
  const [spineHref, setSpineHref] = useState<string | null>(null);
  const [error, setError] = useState<{ code: string; message: string } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [page, setPage] = useState({ page: 0, total: 0 });
  const [boundary, setBoundary] = useState({ atStart: true, atEnd: false });
  const [panel, setPanel] = useState<ReaderPanelName>('none');
  const [progress, setProgress] = useState<ProgressState>(EMPTY_PROGRESS);
  const [settingsState, setSettingsState] = useState<SaveState>({ status: 'loading' });
  const [reloadToken, setReloadToken] = useState(0);
  const [lookup, setLookup] = useState<{ selection: TextSelection; state: LookupViewState } | null>(null);
  const [activeRegion, setActiveRegion] = useState(0);
  const [playback, setPlayback] = useState<PlaybackState>({ audioId: null, status: 'idle' });
  const lookupIdRef = useRef(0);
  const lookupAbortRef = useRef<AbortController | null>(null);
  const lookupOpenRef = useRef(false);
  const playerRef = useRef<PronunciationPlayer | null>(null);

  const enterPhase = useCallback((value: ReaderPhase) => { phaseRef.current = value; setPhase(value); }, []);
  const syncDisplayedPage = useCallback((engine: EpubJsEngine) => {
    if (engineRef.current !== engine) return;
    const info = engine.pageInfo();
    if (!info) return;
    setPage({ page: info.page, total: info.total });
    setBoundary({ atStart: info.atStart, atEnd: info.atEnd });
  }, []);

  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => setNotice(null), 6000);
    return () => window.clearTimeout(timer);
  }, [notice]);

  useEffect(() => {
    if (!bookId) return;
    const controller = new AbortController();
    const cleanups: (() => void)[] = [];
    let engine: EpubJsEngine | null = null;
    let saver: ProgressSaver | null = null;
    let preferenceSaver: PreferenceSaver | null = null;
    enterPhase('loading');
    setError(null); setNotice(null); setManifest(null); setProgress(EMPTY_PROGRESS);
    setPanel('none'); setSpineHref(null);
    closeLookup();

    void (async () => {
      try {
        void request(`/books/${encodeURIComponent(bookId)}`, decodeBook, { signal: controller.signal })
          .then(book => { if (!controller.signal.aborted) setTitle(book.title); })
          .catch(() => { /* the manifest error surfaces the real problem */ });
        const loaded = await loadManifest(bookId, controller.signal);
        if (controller.signal.aborted) return;
        setManifest(loaded);
        const bytes = await downloadEpub(loaded.epubUrl, controller.signal);
        if (controller.signal.aborted) return;
        if (await sha256Hex(bytes) !== loaded.artifactHash) {
          throw new ApiError(0, 'ARTIFACT_HASH_MISMATCH', '阅读文件校验失败，请重新导入');
        }
        enterPhase('rendering');
        const host = hostRef.current;
        if (!host) throw new ApiError(0, 'ENGINE_INIT_FAILED', '阅读区域未就绪');
        const preferences = toReaderPreferences(loaded.settings);
        appliedPrefs.current = JSON.stringify(preferences);
        engine = new EpubJsEngine({
          host, manifest: loaded, preferences,
          loadIndex: (href: string) => loadChapterIndex(loaded, href, controller.signal),
          onKey: key => {
            if (key === 'next' && !lookupOpenRef.current) void engineRef.current?.next();
            else if (key === 'previous' && !lookupOpenRef.current) void engineRef.current?.previous();
            else if (key === 'escape') { closeLookup(); setPanel('none'); }
          },
        });
        engineRef.current = engine;
        cleanups.push(engine.subscribe(event => {
          if (event.type === 'relocated') {
            setPage({ page: event.page, total: event.totalPages });
            setBoundary({ atStart: event.atStart, atEnd: event.atEnd });
            setSpineHref(event.spineHref || null);
            closeLookup();
            if (phaseRef.current === 'ready' && event.anchor && event.anchor.cfi !== lastAnchor.current
                && Date.now() - readyAt.current > 800) {
              lastAnchor.current = event.anchor.cfi;
              saverRef.current?.update(event.anchor);
            }
          } else if (event.type === 'selection') {
            if (event.selection) startLookup(event.selection);
            else if (event.error) {
              closeLookup();
              setNotice('当前位置无法精确查词，请翻页后重试');
            }
          } else if (event.type === 'resized') {
            if (engine) syncDisplayedPage(engine);
            closeLookup();
          } else if (event.type === 'error') {
            setError({ code: event.code, message: event.message });
          }
        }));
        await engine.open(bytes);
        if (controller.signal.aborted) return;

        const fetched = await loadProgress(bookId, controller.signal).catch(() => ({ progress: null }));
        if (auth.user) {
          saver = new ProgressSaver(bookId, loaded.artifactId, auth.user.id, setProgress);
          saver.initialize(fetched.progress);
          saverRef.current = saver;
        }
        enterPhase('restoring');
        const anchor = fetched.progress?.anchor ?? firstAnchor(loaded);
        if (anchor) {
          const result = await engine.display(anchor);
          if (result === 'quote-fallback') setNotice('已按文本匹配恢复上次位置');
          else if (result === 'chapter-start' && fetched.progress) setNotice('位置无法唯一恢复，已回到本章开头');
        }
        lastAnchor.current = engine.anchor()?.cfi ?? null;
        readyAt.current = Date.now();
        setSpineHref(engine.currentSpine()?.href ?? loaded.spine[0]?.href ?? null);
        const initialPage = engine.pageInfo();
        if (initialPage) {
          setPage({ page: initialPage.page, total: initialPage.total });
          setBoundary({ atStart: initialPage.atStart, atEnd: initialPage.atEnd });
        }
        enterPhase('ready');
        preferenceSaver = new PreferenceSaver(setSettingsState, settingsApi);
        settingsRef.current = preferenceSaver;
        void preferenceSaver.load();
      } catch (caught) {
        if (controller.signal.aborted) return;
        const code = caught instanceof ApiError ? caught.code : 'ENGINE_INIT_FAILED';
        const message = caught instanceof Error ? caught.message : '阅读器暂时无法打开这本书';
        setError({ code, message });
        enterPhase('error');
      }
    })();

    return () => {
      controller.abort();
      for (const cleanup of cleanups) cleanup();
      saverRef.current?.dispose(); saverRef.current = null;
      settingsRef.current?.dispose(); settingsRef.current = null;
      engineRef.current = null;
      void engine?.destroy();
      enterPhase('idle');
    };
  }, [bookId, auth.user?.id, reloadToken, enterPhase, syncDisplayedPage]);

  useEffect(() => {
    const draft = settingsState.draft;
    const engine = engineRef.current;
    if (!draft || !engine || phaseRef.current !== 'ready') return;
    const next = toReaderPreferences(draft);
    const key = JSON.stringify(next);
    if (key === appliedPrefs.current) return;
    appliedPrefs.current = key;
    closeLookup();
    void (async () => {
      const anchor = engine.anchor();
      await engine.applyPreferences(next);
      if (anchor) await engine.display(anchor);
      syncDisplayedPage(engine);
    })();
  }, [settingsState.draft, syncDisplayedPage]);

  useEffect(() => {
    const flush = () => { void saverRef.current?.flush(); };
    window.addEventListener('pagehide', flush);
    return () => { window.removeEventListener('pagehide', flush); flush(); };
  }, []);

  useEffect(() => () => { playerRef.current?.dispose(); }, []);

  useEffect(() => {
    const close = () => closeLookup();
    window.addEventListener('resize', close);
    return () => window.removeEventListener('resize', close);
  }, []);

  function getPlayer(): PronunciationPlayer {
    if (!playerRef.current) playerRef.current = new PronunciationPlayer(setPlayback);
    return playerRef.current;
  }

  function closeLookup() {
    lookupAbortRef.current?.abort();
    lookupAbortRef.current = null;
    lookupIdRef.current++;
    lookupOpenRef.current = false;
    playerRef.current?.stop();
    setLookup(null);
    setActiveRegion(0);
  }

  function startLookup(selection: TextSelection) {
    const id = ++lookupIdRef.current;
    lookupAbortRef.current?.abort();
    const controller = new AbortController();
    lookupAbortRef.current = controller;
    lookupOpenRef.current = true;
    playerRef.current?.stop();
    setActiveRegion(0);
    setLookup({ selection, state: { phase: 'loading' } });
    void lookupWord({ bookId, source: selection.source }, controller.signal).then(data => {
      if (controller.signal.aborted || id !== lookupIdRef.current) return;
      setLookup({ selection, state: { phase: 'ready', data } });
    }).catch(caught => {
      if (controller.signal.aborted || id !== lookupIdRef.current) return;
      const error = caught instanceof ApiError ? caught : null;
      setLookup({
        selection,
        state: {
          phase: 'error',
          code: error?.code ?? 'NETWORK_ERROR',
          message: error?.message ?? '查词暂时不可用，请稍后重试。',
          retryable: error?.retryable ?? true,
        },
      });
    });
  }

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (lookupOpenRef.current) { closeLookup(); return; }
        if (panel !== 'none') setPanel('none');
        return;
      }
      if (phaseRef.current !== 'ready') return;
      const target = event.target as HTMLElement | null;
      if (target && (target.isContentEditable || ['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON'].includes(target.tagName))) return;
      if (panel !== 'none' || lookupOpenRef.current) return;
      if (event.key === 'ArrowRight') { event.preventDefault(); void engineRef.current?.next(); }
      else if (event.key === 'ArrowLeft') { event.preventDefault(); void engineRef.current?.previous(); }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [panel]);


  const leave = async () => { closeLookup(); await saverRef.current?.flush(); navigate('/books'); };

  function togglePanel(next: Exclude<ReaderPanelName, 'none'>) {
    closeLookup();
    setPanel(current => current === next ? 'none' : next);
  }

  function closePanel() { setPanel('none'); }

  async function goToTocHref(href: string) {
    if (!manifest || !engineRef.current) return;
    const target = manifest.spine.find(item => sameChapter(item.href, href));
    const nextHref = target?.href ?? href.split('#')[0] ?? href;
    setPanel('none');
    closeLookup();
    setSpineHref(nextHref);
    await engineRef.current.display({
      artifactId: manifest.artifactId, artifactHash: manifest.artifactHash,
      pipelineVersion: manifest.pipelineVersion, spineHref: nextHref, cfi: '',
    });
    if (engineRef.current) syncDisplayedPage(engineRef.current);
  }

  async function adoptServerPosition() {
    const server = progress.conflict?.serverProgress;
    saverRef.current?.resolveServer();
    if (server?.anchor && engineRef.current) {
      await engineRef.current.display(server.anchor);
      if (engineRef.current) syncDisplayedPage(engineRef.current);
    }
  }

  const draft = settingsState.draft;
  const theme = draft?.theme ?? manifest?.settings.theme ?? 'warm';
  const readingWidth = (draft?.width ?? manifest?.settings.width) === 720 ? 'wide' : 'standard';
  const tocTree = useMemo(() => groupToc(manifest?.toc ?? []), [manifest?.toc]);
  const chapterLabel = spineHref
    ? findTocLabel(tocTree, spineHref) ?? manifest?.spine.find(item => item.href === spineHref)?.title
    : undefined;

  if (phase === 'error') {
    return <div className="reader-shell" data-theme={theme}>
      <ReaderTopbar onBack={() => void leave()} />
      <main className="reader-stage"><div className="reader-error">
        <p className="reader-error-kicker">阅读器</p>
        <h2>这本书暂时无法打开</h2>
        <p>{error?.message ?? '请稍后重试。'}</p>
        <p className="reader-error-code">错误码：{error?.code ?? 'ENGINE_INIT_FAILED'}</p>
        <div className="reader-error-actions">
          <button type="button" className="reader-choice" onClick={() => setReloadToken(value => value + 1)}>重新加载</button>
          <button type="button" className="reader-choice" onClick={() => void leave()}>返回书架</button>
        </div>
      </div></main>
    </div>;
  }

  return <div className="reader-shell" data-theme={theme} data-panel={panel === 'none' ? 'closed' : 'open'}
    data-reading-width={readingWidth}>
    <ReaderTopbar onBack={() => void leave()} />

    <main className="reader-stage">
      <article className="reader-paper">
        <div className="reader-paper-inner">
          <ReaderPaperHeading title={title} chapter={chapterLabel} />
          <ReaderRail active={panel} onToc={() => togglePanel('contents')}
            onFavorites={() => { /* M06 */ }} onSettings={() => togglePanel('settings')} />
          <div ref={hostRef} className="reader-canvas" data-phase={phase} />
          {progress.status === 'conflict' && <div className="reader-conflict" role="alert">
            <p>阅读位置已在其他窗口更新。</p>
            <div className="reader-conflict-actions">
              <button type="button" className="reader-choice" onClick={() => void adoptServerPosition()}>用服务器位置</button>
              <button type="button" className="reader-choice" onClick={() => void saverRef.current?.resolveKeepLocal()}>保留此处</button>
            </div>
          </div>}
          <footer className="reader-pagebar">
            <button type="button" className="reader-turn" onClick={() => void engineRef.current?.previous()}
              disabled={phase !== 'ready' || boundary.atStart} aria-label="上一页">
              <ChevronLeft size={16} strokeWidth={1.7} aria-hidden="true" />上一页
            </button>
            <div className="reader-meta">
              <span className="reader-page-number">{page.total > 0 ? `第 ${page.page} / ${page.total} 页` : '展开书页…'}</span>
              <div className="reader-status-line">
                <SaveStatus status={progress.status} onRetry={() => saverRef.current?.retry()} />
                {notice && <span className="reader-restore-note" role="status">{notice}</span>}
              </div>
            </div>
            <button type="button" className="reader-turn" onClick={() => void engineRef.current?.next()}
              disabled={phase !== 'ready' || boundary.atEnd} aria-label="下一页">
              下一页<ChevronRight size={16} strokeWidth={1.7} aria-hidden="true" />
            </button>
          </footer>
        </div>
      </article>

      {panel !== 'none' && <>
        <div className="reader-scrim" aria-hidden="true" onClick={closePanel} />
        {panel === 'contents'
          ? <ContentsPanel toc={tocTree} spine={manifest?.spine ?? []}
              currentHref={spineHref} onSelect={href => void goToTocHref(href)} onClose={closePanel} />
          : draft
            ? <SettingsPanel draft={draft} state={settingsState}
                onEdit={next => settingsRef.current?.edit(next)}
                onRetry={() => settingsRef.current?.retry()}
                onResolve={keep => settingsRef.current?.resolve(keep)}
                onClose={closePanel} />
            : <div className="reader-panel reader-panel-loading" role="dialog" aria-label="阅读设置">
                <p>正在读取阅读设置…</p>
                <button type="button" className="reader-choice" onClick={closePanel}>关闭</button>
              </div>}
      </>}

      {lookup && <>
        <span className="reader-word-highlight" aria-hidden="true" style={{
          left: lookup.selection.rect.left, top: lookup.selection.rect.top,
          width: lookup.selection.rect.width, height: lookup.selection.rect.height,
        }} />
        <DictionaryCard selection={lookup.selection} state={lookup.state}
          activeRegion={activeRegion} playback={playback}
          onRegion={index => { setActiveRegion(index); playerRef.current?.stop(); }}
          onRetry={() => startLookup(lookup.selection)}
          onPlay={audioId => getPlayer().play(audioId)}
          onClose={closeLookup} />
      </>}
    </main>
  </div>;
}
