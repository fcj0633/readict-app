import { ApiError } from '../../lib/api';
import { request, object, invalid } from '../../lib/http';
export type Preferences = { font: string; fontSize: number; lineHeight: number; width: number; theme: string; pronunciation: string };
export type View = { preferences: Preferences; version: number };
export function decode(value: unknown): View {
  if (!object(value) || !object(value.preferences) || !Number.isSafeInteger(value.version) || Number(value.version) < 1) return invalid();
  const p = value.preferences;
  if (!['Georgia', 'Palatino Linotype', 'Arial'].includes(String(p.font)) || ![18, 20, 22, 24].includes(Number(p.fontSize))
    || ![1.6, 1.8, 2].includes(Number(p.lineHeight)) || ![660, 720].includes(Number(p.width)) || !['white', 'warm', 'dark'].includes(String(p.theme)) || !['en-GB', 'en-US'].includes(String(p.pronunciation))
    || typeof p.font !== 'string' || typeof p.fontSize !== 'number' || typeof p.lineHeight !== 'number' || typeof p.width !== 'number' || typeof p.theme !== 'string' || typeof p.pronunciation !== 'string') return invalid();
  return { preferences: { font: p.font, fontSize: p.fontSize, lineHeight: p.lineHeight, width: p.width, theme: p.theme, pronunciation: p.pronunciation }, version: Number(value.version) };
}
export const settingsApi = {
  get: (signal?: AbortSignal) => request('/me/settings', decode, { signal }),
  save: (body: { preferences: Preferences; expectedVersion: number; requestId: string }, signal?: AbortSignal) => request('/me/settings', decode, { method: 'PUT', body, signal }),
};
export type SaveState = { draft?: Preferences; version?: number; status: 'loading' | 'saved' | 'saving' | 'unsynced' | 'conflict'; error?: string; server?: View };
const same = (a?: Preferences, b?: Preferences) => JSON.stringify(a) === JSON.stringify(b);
/** Serializes saves and preserves both edits and the exact request after an unknown network result. */
export class PreferenceSaver {
  state: SaveState = { status: 'loading' };
  private timer?: ReturnType<typeof setTimeout>;
  private pending?: { preferences: Preferences; expectedVersion: number; requestId: string };
  private disposed = false; private busy = false; private dirty = false; private generation = 0;
  private controller = new AbortController();
  constructor(private emit: (state: SaveState) => void, private api = settingsApi, private changed: (view: View) => void = () => {}) {}
  private isConflict() { return this.state.status === 'conflict'; }
  private update(state: SaveState) { if (this.disposed) return; this.state = state; this.emit(state); }
  async load() {
    const current = ++this.generation;
    try { const value = await this.api.get(this.controller.signal); if (this.disposed || current !== this.generation) return;
      if (this.dirty || this.busy) this.update({ ...this.state, status: 'conflict', server: value, error: '设置已变化，请选择如何处理当前草稿。' });
      else this.update({ draft: value.preferences, version: value.version, status: 'saved' });
    } catch (error) { if (!this.disposed && current === this.generation) this.update({ ...this.state, status: 'unsynced', error: error instanceof Error ? error.message : '暂时无法读取设置' }); }
  }
  edit(draft: Preferences) {
    this.dirty = true; this.update({ ...this.state, draft, status: this.state.status === 'conflict' ? 'conflict' : this.busy ? 'saving' : 'unsynced' });
    clearTimeout(this.timer);
    if (this.state.status !== 'conflict') this.timer = setTimeout(() => void this.flush(), 500);
  }
  async flush() {
    if (this.disposed || this.busy || this.state.status === 'conflict' || !this.state.draft || !this.state.version) return;
    this.pending ??= { preferences: { ...this.state.draft }, expectedVersion: this.state.version, requestId: crypto.randomUUID() };
    const current = this.pending; this.busy = true; this.update({ ...this.state, status: 'saving', error: undefined });
    try {
      const value = await this.api.save(current, this.controller.signal); if (this.disposed) return;
      this.pending = undefined; const unchanged = same(this.state.draft, current.preferences); this.dirty = !unchanged;
      this.update({ ...this.state, version: value.version, status: this.isConflict() ? 'conflict' : unchanged ? 'saved' : 'unsynced' }); this.changed(value);
      if (!unchanged && !this.isConflict()) { clearTimeout(this.timer); this.timer = setTimeout(() => void this.flush(), 500); }
    } catch (error) {
      if (this.disposed) return;
      if (error instanceof ApiError && error.status === 409) {
        this.update({ ...this.state, status: 'conflict', error: '设置已在其他页面更新。当前草稿已保留。' });
        try { const server = await this.api.get(this.controller.signal); this.update({ ...this.state, status: 'conflict', server }); } catch { /* Keep the draft; explicit retry can fetch again. */ }
      } else this.update({ ...this.state, status: 'unsynced', error: '尚未同步。请检查连接后重试。' });
    } finally { this.busy = false; }
  }
  resolve(keepDraft: boolean) {
    if (!this.state.server) { void this.load(); return; }
    const server = this.state.server; this.pending = undefined;
    this.dirty = keepDraft; this.update({ draft: keepDraft ? this.state.draft : server.preferences, version: server.version, status: keepDraft ? 'unsynced' : 'saved' });
    if (keepDraft) void this.flush();
  }
  retry() { if (!this.state.draft) void this.load(); else void this.flush(); }
  dispose() { this.disposed = true; this.generation++; clearTimeout(this.timer); this.controller.abort(); }
}
