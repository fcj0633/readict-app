import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../../lib/api';
import { request } from '../../lib/http';
import { useAuth } from '../auth/AuthProvider';
import { decodeBook } from '../library/api';
import { PreferenceSaver, settingsApi, type Preferences, type SaveState } from '../settings/model';
import { downloadEpub, loadChapterIndex, loadManifest, loadProgress } from './api';
import { EpubJsEngine } from './engine/EpubJsEngine';
import type {
  ManifestTocNode, ReaderManifest, ReaderPhase, ReaderPreferences, ReadingAnchor,
} from './engine/types';
import { ProgressSaver, type ProgressState } from './progress/model';
import { ReaderHeader, ReaderRail } from './ReaderLayout';
import './reader.css';

type Panel = 'none' | 'contents' | 'settings';

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

function flattenToc(nodes: ManifestTocNode[]): ManifestTocNode[] {
  const result: ManifestTocNode[] = [];
  const walk = (items: ManifestTocNode[]) => {
    for (const item of items) { result.push(item); walk(item.children); }
  };
  walk(nodes);
  return result;
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
  const [error, setError] = useState<{ code: string; message: string } | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [page, setPage] = useState({ page: 0, total: 0 });
  const [boundary, setBoundary] = useState({ atStart: true, atEnd: false });
  const [panel, setPanel] = useState<Panel>('none');
  const [progress, setProgress] = useState<ProgressState>(EMPTY_PROGRESS);
  const [settingsState, setSettingsState] = useState<SaveState>({ status: 'loading' });
  const [reloadToken, setReloadToken] = useState(0);

  const enterPhase = useCallback((value: ReaderPhase) => { phaseRef.current = value; setPhase(value); }, []);

  useEffect(() => {
    if (!bookId) return;
    const controller = new AbortController();
    const cleanups: (() => void)[] = [];
    let engine: EpubJsEngine | null = null;
    let saver: ProgressSaver | null = null;
    let preferenceSaver: PreferenceSaver | null = null;
    enterPhase('loading');
    setError(null); setNotice(null); setManifest(null); setProgress(EMPTY_PROGRESS);

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
            if (key === 'next') void engineRef.current?.next();
            else if (key === 'previous') void engineRef.current?.previous();
            else if (key === 'escape') setPanel('none');
          },
        });
        engineRef.current = engine;
        cleanups.push(engine.subscribe(event => {
          if (event.type === 'relocated') {
            setPage({ page: event.page, total: event.totalPages });
            setBoundary({ atStart: event.atStart, atEnd: event.atEnd });
            if (phaseRef.current === 'ready' && event.anchor && event.anchor.cfi !== lastAnchor.current
                && Date.now() - readyAt.current > 800) {
              lastAnchor.current = event.anchor.cfi;
              saverRef.current?.update(event.anchor);
            }
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
  }, [bookId, auth.user?.id, reloadToken, enterPhase]);

  useEffect(() => {
    const draft = settingsState.draft;
    const engine = engineRef.current;
    if (!draft || !engine || phaseRef.current !== 'ready') return;
    const next = toReaderPreferences(draft);
    const key = JSON.stringify(next);
    if (key === appliedPrefs.current) return;
    appliedPrefs.current = key;
    void (async () => {
      const anchor = engine.anchor();
      await engine.applyPreferences(next);
      if (anchor) await engine.display(anchor);
    })();
  }, [settingsState.draft]);

  useEffect(() => {
    const flush = () => { void saverRef.current?.flush(); };
    window.addEventListener('pagehide', flush);
    return () => { window.removeEventListener('pagehide', flush); flush(); };
  }, []);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if (phaseRef.current !== 'ready') return;
      const target = event.target as HTMLElement | null;
      if (target && (target.isContentEditable || ['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON'].includes(target.tagName))) return;
      if (panel !== 'none') { if (event.key === 'Escape') setPanel('none'); return; }
      if (event.key === 'ArrowRight') { event.preventDefault(); void engineRef.current?.next(); }
      else if (event.key === 'ArrowLeft') { event.preventDefault(); void engineRef.current?.previous(); }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [panel]);

  const leave = async () => { await saverRef.current?.flush(); navigate('/books'); };

  async function goToTocHref(href: string) {
    if (!manifest || !engineRef.current) return;
    setPanel('none');
    await engineRef.current.display({
      artifactId: manifest.artifactId, artifactHash: manifest.artifactHash,
      pipelineVersion: manifest.pipelineVersion, spineHref: href, cfi: '',
    });
  }

  async function adoptServerPosition() {
    const server = progress.conflict?.serverProgress;
    saverRef.current?.resolveServer();
    if (server?.anchor) await engineRef.current?.display(server.anchor);
  }

  const pct = progress.version > 0 ? Math.max(0, Math.min(100, Math.round(progress.ratio * 100))) : null;
  const draft = settingsState.draft;

  if (phase === 'error') {
    return <div className="reader-shell">
      <div className="reader-body"><div className="reader-content"><div className="reader-error">
        <h2>这本书暂时无法打开</h2>
        <p>{error?.message ?? '请稍后重试。'}</p>
        <p className="reader-error-code">错误码：{error?.code ?? 'ENGINE_INIT_FAILED'}</p>
        <div className="reader-error-actions">
          <button type="button" onClick={() => setReloadToken(value => value + 1)}>重新加载</button>
          <button type="button" onClick={() => void leave()}>返回书架</button>
        </div>
      </div></div></div>
    </div>;
  }

  return <div className="reader-shell">
    <ReaderHeader title={title} onToc={() => setPanel(panel === 'contents' ? 'none' : 'contents')}
      onSettings={() => setPanel(panel === 'settings' ? 'none' : 'settings')} />
    {notice && <p className="reader-notice" role="status">{notice}</p>}
    {progress.status === 'conflict' && <div className="reader-banner" role="alert">
      <span>阅读位置已在其他窗口更新。</span>
      <button type="button" onClick={() => void adoptServerPosition()}>以服务器位置为准</button>
      <button type="button" onClick={() => void saverRef.current?.resolveKeepLocal()}>继续此处</button>
    </div>}
    {progress.status === 'unsynced' && !progress.conflict && <div className="reader-banner" role="status">
      <span>阅读位置尚未同步。{progress.error ? `（${progress.error}）` : ''}</span>
      <button type="button" onClick={() => saverRef.current?.retry()}>重试保存</button>
    </div>}

    <div className="reader-body">
      <div className="reader-content">
        <div ref={hostRef} className="reader-canvas" data-phase={phase}
          style={{ maxWidth: manifest ? `${manifest.settings.width}px` : undefined }} />
      </div>
      <ReaderRail onToc={() => setPanel(panel === 'contents' ? 'none' : 'contents')}
        onFavorites={() => setNotice('收藏功能将在 M06 接入')}
        onSettings={() => setPanel(panel === 'settings' ? 'none' : 'settings')} />
      {panel === 'contents' && <aside className="reader-panel" role="dialog" aria-label="章节目录">
        <h2>章节目录</h2>
        {manifest && flattenToc(manifest.toc).length > 0
          ? <ul>{flattenToc(manifest.toc).map((node, index) => <li key={`${node.id}-${index}`}>
              <button type="button" onClick={() => void goToTocHref(node.href)}>{node.label}</button>
            </li>)}</ul>
          : <ul>{manifest?.spine.map(item => <li key={item.chapterId}>
              <button type="button" onClick={() => void goToTocHref(item.href)}>{item.title}</button>
            </li>)}</ul>}
      </aside>}
      {panel === 'settings' && <aside className="reader-panel" role="dialog" aria-label="阅读设置">
        <h2>阅读设置</h2>
        {draft ? <>
          <label>字号<select value={draft.fontSize} onChange={event => settingsRef.current?.edit({ ...draft, fontSize: Number(event.target.value) })}>
            {[18, 20, 22, 24].map(size => <option key={size} value={size}>{size}px</option>)}</select></label>
          <label>行距<select value={draft.lineHeight} onChange={event => settingsRef.current?.edit({ ...draft, lineHeight: Number(event.target.value) })}>
            {[1.6, 1.8, 2].map(value => <option key={value} value={value}>{value}</option>)}</select></label>
          <label>主题<select value={draft.theme} onChange={event => settingsRef.current?.edit({ ...draft, theme: event.target.value })}>
            <option value="white">清白</option><option value="warm">暖纸</option><option value="dark">夜读</option></select></label>
          <label>正文宽度<select value={draft.width} onChange={event => settingsRef.current?.edit({ ...draft, width: Number(event.target.value) })}>
            <option value={660}>适中</option><option value={720}>宽松</option></select></label>
          {settingsState.status === 'conflict' && <p className="reader-banner">设置已在其他页面更新，请到设置页处理。</p>}
          {settingsState.status === 'unsynced' && <p className="reader-notice">设置尚未同步。</p>}
        </> : <p>正在读取设置…</p>}
      </aside>}
    </div>

    <footer className="reader-footer">
      <button type="button" onClick={() => void engineRef.current?.previous()} disabled={phase !== 'ready' || boundary.atStart}>上一页</button>
      <span className="reader-progress">{page.total > 0 ? `第 ${page.page} / ${page.total} 页 · ` : ''}全书 {pct === null ? '—' : `${pct}%`}</span>
      <button type="button" onClick={() => void engineRef.current?.next()} disabled={phase !== 'ready' || boundary.atEnd}>下一页</button>
    </footer>
  </div>;
}
