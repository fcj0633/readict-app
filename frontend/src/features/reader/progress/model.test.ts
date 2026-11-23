import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ProgressSaver, type ProgressApi } from './model';
import type { ReadingAnchor, ReadingProgress } from '../engine/types';

const anchor = (cfi: string): ReadingAnchor => ({
  artifactId: 'a0000000-0000-4000-8000-000000000001',
  artifactHash: 'a'.repeat(64), pipelineVersion: 'epub-safe-v1',
  spineHref: 'OEBPS/Text/ch1.xhtml', cfi, blockId: 'b-1', blockOffset: 0,
  quote: { exact: 'abc', prefix: '', suffix: '' },
});

const progress = (version: number, cfi: string): ReadingProgress => ({
  bookId: 'b0000000-0000-4000-8000-000000000001', anchor: anchor(cfi),
  progressRatio: 0.1, version, updatedAt: '2026-11-23T00:00:00Z',
});

function memoryStorage(): Storage {
  const map = new Map<string, string>();
  return {
    get length() { return map.size; },
    clear: () => map.clear(),
    getItem: (key: string) => map.get(key) ?? null,
    key: (index: number) => Array.from(map.keys())[index] ?? null,
    removeItem: (key: string) => { map.delete(key); },
    setItem: (key: string, value: string) => { map.set(key, value); },
  };
}

test('initializes from the server and saves with expectedVersion 0', async () => {
  Object.defineProperty(globalThis, 'localStorage', { value: memoryStorage(), configurable: true });
  const saved: { expectedVersion: number; requestId: string }[] = [];
  const api: ProgressApi = {
    load: async () => ({ progress: null }),
    save: async (_bookId, body) => { saved.push(body); return progress(1, body.anchor.cfi); },
  };
  const states: string[] = [];
  const saver = new ProgressSaver('b0000000-0000-4000-8000-000000000001',
    'a0000000-0000-4000-8000-000000000001', 'u1', state => states.push(state.status), api);
  saver.initialize(null);
  saver.update(anchor('epubcfi(/6/2!/4/2/1:0)'));
  await saver.flush();
  assert.equal(saved.length, 1);
  assert.equal(saved[0]!.expectedVersion, 0);
  assert.equal(saver.currentVersion, 1);
  assert.ok(states.includes('saved'));
  saver.dispose();
});

test('stops automatic saving on conflict and can resume from the server version', async () => {
  Object.defineProperty(globalThis, 'localStorage', { value: memoryStorage(), configurable: true });
  const versions: number[] = [];
  const api: ProgressApi = {
    load: async () => ({ progress: progress(3, 'server') }),
    save: async (_bookId, body) => {
      versions.push(body.expectedVersion);
      if (body.expectedVersion === 3) {
        const error = new Error('conflict') as Error & { currentVersion: number; serverProgress: ReadingProgress | null };
        error.currentVersion = 7; error.serverProgress = progress(7, 'server-7');
        throw error;
      }
      return progress(8, body.anchor.cfi);
    },
  };
  const saver = new ProgressSaver('b0000000-0000-4000-8000-000000000001',
    'a0000000-0000-4000-8000-000000000001', 'u1', () => {}, api);
  saver.initialize(progress(3, 'server'));
  saver.update(anchor('local'));
  await saver.flush();
  assert.equal(saver.state.status, 'conflict');
  assert.equal(saver.state.conflict?.currentVersion, 7);
  await saver.resolveKeepLocal();
  assert.deepEqual(versions, [3, 7]);
  assert.equal(saver.state.status, 'saved');
  saver.dispose();
});

test('keeps a local pending anchor when the network fails', async () => {
  Object.defineProperty(globalThis, 'localStorage', { value: memoryStorage(), configurable: true });
  const api: ProgressApi = {
    load: async () => ({ progress: null }),
    save: async () => { throw new Error('offline'); },
  };
  const saver = new ProgressSaver('b0000000-0000-4000-8000-000000000001',
    'a0000000-0000-4000-8000-000000000001', 'u1', () => {}, api);
  saver.initialize(null);
  saver.update(anchor('epubcfi(/6/2!/4/2/1:0)'));
  await saver.flush();
  assert.equal(saver.state.status, 'unsynced');
  assert.ok(globalThis.localStorage.getItem('readict:progress:v1:u1:b0000000-0000-4000-8000-000000000001:a0000000-0000-4000-8000-000000000001'));
  saver.dispose();
});
