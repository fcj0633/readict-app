import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import BookCard from './BookCard';
import { createImport, fetchImport, listBooks, newRequestId, removeBook } from './api';
import type { Book, ImportTask, Shelf } from './model';
import { errorMessage, isTerminal, localFileError, pollDelay, stageLabel, uploadProgress } from './model';
import './library.css';

type Phase = 'idle' | 'uploading' | 'polling' | 'failed' | 'done';

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => { clearTimeout(timer); reject(Object.assign(new Error('aborted'), { name: 'AbortError' })); }, { once: true });
  });
}

export default function BookshelfPage() {
  const [shelf, setShelf] = useState<Shelf | null>(null);
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [localError, setLocalError] = useState<string | null>(null);
  const [phase, setPhase] = useState<Phase>('idle');
  const [progress, setProgress] = useState(0);
  const [task, setTask] = useState<ImportTask | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Book | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const navigate = useNavigate();
  const generation = useRef(0);
  const request = useRef<AbortController | undefined>(undefined);
  const key = useRef<string | null>(null);
  const picker = useRef<HTMLInputElement | null>(null);

  const load = useCallback(async (text: string) => {
    const current = ++generation.current;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    try {
      const value = await listBooks(text, null, controller.signal);
      if (current !== generation.current || controller.signal.aborted) return;
      setShelf(value);
      setError(null);
    } catch (caught) {
      if (current !== generation.current || controller.signal.aborted) return;
      setError(caught instanceof Error ? caught.message : '书架加载失败');
    } finally {
      if (current === generation.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    const timer = setTimeout(() => { void load(query); }, 250);
    return () => clearTimeout(timer);
  }, [query, load]);

  useEffect(() => () => request.current?.abort(), []);

  function choose(next: File | null) {
    setNotice(null);
    setLocalError(null);
    setTask(null);
    setPhase('idle');
    setProgress(0);
    key.current = next ? newRequestId() : null;
    if (next) {
      const problem = localFileError(next);
      if (problem) { setFile(null); setLocalError(problem); return; }
    }
    setFile(next);
  }

  async function startImport() {
    if (!file || !key.current) return;
    const controller = new AbortController();
    request.current?.abort();
    request.current = controller;
    setLocalError(null);
    setPhase('uploading');
    setProgress(0);
    setTask(null);
    try {
      const created = await createImport(file, key.current,
        (loaded, total) => setProgress(uploadProgress(loaded, total)), controller.signal);
      setTask(created);
      if (isTerminal(created.state)) { await finish(created); return; }
      if (!created.importId) { setPhase('failed'); setLocalError('导入任务未能创建，请重试'); return; }
      setPhase('polling');
      const began = Date.now();
      while (!controller.signal.aborted) {
        await sleep(pollDelay(Date.now() - began), controller.signal);
        const next = await fetchImport(created.importId, controller.signal);
        setTask(next);
        if (isTerminal(next.state)) { await finish(next); return; }
      }
    } catch (caught) {
      if ((caught as Error)?.name === 'AbortError') return;
      setPhase('failed');
      setLocalError(caught instanceof Error ? caught.message : errorMessage(undefined));
    }
  }

  async function finish(result: ImportTask) {
    if (result.state === 'SUCCEEDED') {
      setPhase('done');
      setNotice(result.reused ? '这本书已在书架中' : '导入完成');
      setFile(null);
      key.current = null;
      await load(query);
      return;
    }
    setPhase('failed');
    setLocalError(errorMessage(result.error?.code));
  }

  function cancelImport() {
    request.current?.abort();
    setFile(null);
    setTask(null);
    key.current = null;
    setPhase('idle');
    setProgress(0);
    setLocalError(null);
  }

  async function confirmDelete() {
    if (!deleteTarget) return;
    const target = deleteTarget;
    setDeleteTarget(null);
    try {
      await removeBook(target.id, newRequestId());
      setNotice('已从书架删除，已收藏的原文快照仍会保留');
      await load(query);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '删除失败，请重试');
    }
  }

  const dialogOpen = file !== null || phase !== 'idle';
  const items = shelf?.items ?? [];

  return <section className="shelf-content">
    <div className="page-heading">
      <div><p className="section-intro">我的书架</p><h1>把想读的书，放在手边。</h1></div>
      <div className="shelf-tools">
        <label className="search-field"><span>搜索</span>
          <input value={query} onChange={event => setQuery(event.target.value)} placeholder="书名或作者" maxLength={100} />
        </label>
        <button type="button" className="primary-button" onClick={() => picker.current?.click()}>导入 EPUB</button>
        <input ref={picker} type="file" accept=".epub,application/epub+zip" hidden
          onChange={event => { const next = event.target.files?.[0] ?? null; event.target.value = ''; choose(next); }} />
      </div>
    </div>

    {notice && <p className="form-notice" role="status">{notice}</p>}
    {error && <p className="form-error" role="alert">{error}</p>}
    {localError && file === null && <p className="form-error" role="alert">{localError}</p>}

    {loading && shelf === null && <p className="shelf-empty" role="status">正在加载书架…</p>}
    {!loading && items.length === 0 && <div className="shelf-empty">
      <p>{query.trim() ? '没有找到匹配的书。' : '书架还是空的。'}</p>
      <p className="field-hint">支持 EPUB 2 与可重排 EPUB 3，单个文件不超过 25 MB。</p>
    </div>}
    {items.length > 0 && <div className="shelf-grid">
      {items.map(book => <BookCard key={book.id} book={book}
        onOpen={() => navigate(`/reader/${book.id}`)}
        onDelete={setDeleteTarget}
        onRetry={() => picker.current?.click()} />)}
    </div>}

    {dialogOpen && <div className="shelf-dialog-backdrop">
      <div className="shelf-dialog" role="dialog" aria-modal="true" aria-labelledby="import-title">
        <h2 id="import-title">导入 EPUB</h2>
        {file && <p className="dialog-file">{file.name} · {Math.max(1, Math.round(file.size / 1024))} KB</p>}
        {phase === 'uploading' && <div className="import-progress" role="status" aria-live="polite">
          正在上传 {progress}%<progress max={100} value={progress} />
        </div>}
        {phase === 'polling' && <div className="import-progress" role="status" aria-live="polite">
          {task ? stageLabel(task.stage) : '正在排队'}<progress max={100} value={100} />
        </div>}
        {phase === 'done' && <p className="form-notice" role="status">导入完成，已加入书架。</p>}
        {phase === 'failed' && <p className="form-error" role="alert">{localError ?? '导入失败，请重试'}</p>}
        {phase !== 'done' && <p className="field-hint">导入期间可以关闭此窗口，任务会在后台继续。</p>}
        <div className="dialog-actions">
          {phase === 'uploading' || phase === 'polling'
            ? <button type="button" className="secondary-button" onClick={cancelImport}>取消导入</button>
            : <button type="button" className="secondary-button" onClick={() => { choose(null); setNotice(null); }}>关闭</button>}
          {(phase === 'idle' || phase === 'failed') && file &&
            <button type="button" className="primary-button" onClick={() => void startImport()}>
              {phase === 'failed' ? '重新导入' : '开始导入'}
            </button>}
        </div>
      </div>
    </div>}

    {deleteTarget && <div className="shelf-dialog-backdrop">
      <div className="shelf-dialog" role="dialog" aria-modal="true" aria-labelledby="delete-title">
        <h2 id="delete-title">删除《{deleteTarget.title}》？</h2>
        <p>将删除原始文件与阅读资源，阅读进度一并清除；已收藏的单词和原文快照会保留。</p>
        <div className="dialog-actions">
          <button type="button" className="secondary-button" onClick={() => setDeleteTarget(null)}>取消</button>
          <button type="button" className="danger-button" onClick={() => void confirmDelete()}>确认删除</button>
        </div>
      </div>
    </div>}
  </section>;
}
