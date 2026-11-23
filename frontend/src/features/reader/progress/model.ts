import { ApiError } from '../../../lib/api';
import { loadProgress, saveProgress, type ProgressConflict } from '../api';
import type { ReadingAnchor, ReadingProgress } from '../engine/types';

export type ProgressStatus = 'idle' | 'saving' | 'saved' | 'unsynced' | 'conflict';

export interface ProgressState {
  status: ProgressStatus;
  version: number;
  anchor: ReadingAnchor | null;
  ratio: number;
  error?: string;
  conflict?: { currentVersion: number; serverProgress: ReadingProgress | null };
  dirty: boolean;
}

export interface ProgressApi {
  load(bookId: string, signal?: AbortSignal): Promise<{ progress: ReadingProgress | null }>;
  save(bookId: string, body: { expectedVersion: number; requestId: string; anchor: ReadingAnchor },
       signal?: AbortSignal): Promise<ReadingProgress>;
}

const DEBOUNCE_MS = 1000;
const MIN_INTERVAL_MS = 5000;

interface Pending { anchor: ReadingAnchor; expectedVersion: number; requestId: string }

export class ProgressSaver {
  state: ProgressState = { status: 'idle', version: 0, anchor: null, ratio: 0, dirty: false };
  private timer: ReturnType<typeof setTimeout> | undefined;
  private lastSentAt = 0;
  private busy = false;
  private pending: Pending | null = null;
  private latest: ReadingAnchor | null = null;
  private disposed = false;
  private controller = new AbortController();
  private readonly key: string;

  constructor(private readonly bookId: string, artifactId: string, userId: string,
              private readonly emit: (state: ProgressState) => void,
              private readonly api: ProgressApi = { load: loadProgress, save: saveProgress }) {
    this.key = `readict:progress:v1:${userId}:${bookId}:${artifactId}`;
  }

  private commit(state: ProgressState): void {
    this.state = state;
    this.emit(state);
  }

  /** Seeds state from the server response and prefers a same-artifact local pending anchor. */
  initialize(progress: ReadingProgress | null): void {
    const version = progress?.version ?? 0;
    this.state = {
      status: progress ? 'saved' : 'idle', version,
      anchor: progress?.anchor ?? null, ratio: progress?.progressRatio ?? 0, dirty: false,
    };
    const local = this.readLocal();
    if (local && local.expectedVersion >= version) {
      this.latest = local.anchor;
      this.state = { ...this.state, anchor: local.anchor, dirty: true, status: 'unsynced' };
      this.pending = local;
    }
    this.commit(this.state);
  }

  get currentVersion(): number { return this.state.version; }
  get currentAnchor(): ReadingAnchor | null { return this.state.anchor; }

  update(anchor: ReadingAnchor): void {
    if (this.disposed) return;
    this.latest = anchor;
    this.state = { ...this.state, anchor, dirty: true };
    if (this.state.status === 'conflict') { this.commit(this.state); return; }
    this.commit({ ...this.state, status: 'saving' });
    clearTimeout(this.timer);
    const wait = Math.max(DEBOUNCE_MS, MIN_INTERVAL_MS - (Date.now() - this.lastSentAt));
    this.timer = setTimeout(() => { void this.flush(); }, wait);
  }

  async flush(): Promise<void> {
    if (this.disposed || this.busy || this.state.status === 'conflict') return;
    const anchor = this.latest ?? this.state.anchor;
    if (!anchor || !this.state.dirty) return;
    this.pending ??= { anchor, expectedVersion: this.state.version, requestId: crypto.randomUUID() };
    const request = this.pending;
    this.busy = true;
    this.commit({ ...this.state, status: 'saving', error: undefined });
    try {
      const value = await this.api.save(this.bookId, request, this.controller.signal);
      if (this.disposed) return;
      this.pending = null;
      this.lastSentAt = Date.now();
      this.writeLocal(null);
      const dirty = this.latest !== null && this.latest.cfi !== value.anchor.cfi;
      this.commit({ status: dirty ? 'saving' : 'saved', version: value.version, anchor: value.anchor,
        ratio: value.progressRatio, dirty });
      if (dirty) { clearTimeout(this.timer); this.timer = setTimeout(() => { void this.flush(); }, DEBOUNCE_MS); }
    } catch (error) {
      if (this.disposed) return;
      if (isConflict(error)) {
        this.writeLocal(request);
        this.commit({ ...this.state, status: 'conflict', error: '阅读位置已在其他窗口更新',
          conflict: { currentVersion: error.currentVersion, serverProgress: error.serverProgress } });
      } else if (error instanceof ApiError && error.status === 409 && error.code === 'IDEMPOTENCY_KEY_REUSED') {
        this.commit({ ...this.state, status: 'unsynced', error: '保存请求冲突，请刷新页面后重试' });
      } else {
        this.writeLocal(request);
        this.commit({ ...this.state, status: 'unsynced', error: '尚未同步，请检查连接后重试' });
      }
    } finally {
      this.busy = false;
    }
  }

  retry(): void { clearTimeout(this.timer); void this.flush(); }

  resolveServer(): void {
    const server = this.state.conflict?.serverProgress ?? null;
    this.pending = null;
    this.writeLocal(null);
    this.commit({ status: 'saved', version: server?.version ?? this.state.version,
      anchor: server?.anchor ?? null, ratio: server?.progressRatio ?? 0, dirty: false });
  }

  async resolveKeepLocal(): Promise<void> {
    const conflict = this.state.conflict;
    if (!conflict) return;
    this.pending = { anchor: this.state.anchor!, expectedVersion: conflict.currentVersion, requestId: crypto.randomUUID() };
    this.state = { ...this.state, status: 'unsynced', version: conflict.currentVersion, conflict: undefined, dirty: true };
    this.commit(this.state);
    await this.flush();
  }

  dispose(): void {
    this.disposed = true;
    clearTimeout(this.timer);
    this.controller.abort();
  }

  private readLocal(): Pending | null {
    try {
      const raw = globalThis.localStorage?.getItem(this.key);
      if (!raw) return null;
      const value = JSON.parse(raw) as Partial<Pending>;
      if (!value || !value.anchor || typeof value.expectedVersion !== 'number' || typeof value.requestId !== 'string') return null;
      return value as Pending;
    } catch { return null; }
  }

  private writeLocal(pending: Pending | null): void {
    try {
      if (pending) globalThis.localStorage?.setItem(this.key, JSON.stringify(pending));
      else globalThis.localStorage?.removeItem(this.key);
    } catch { /* storage may be unavailable; server state still applies */ }
  }
}

function isConflict(error: unknown): error is ProgressConflict {
  return error instanceof Error && typeof (error as ProgressConflict).currentVersion === 'number';
}
