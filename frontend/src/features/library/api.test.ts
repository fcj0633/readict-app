import { test } from 'node:test';
import assert from 'node:assert/strict';
import { decodeBook, decodeImport, decodeShelf, newRequestId } from './api';

const book = {
  id: 'b0000000-0000-4000-8000-000000000001',
  title: 'Test Book',
  author: null,
  coverUrl: null,
  status: 'READY',
  progressRatio: null,
  lastReadAt: '2026-11-23T02:00:00Z',
  createdAt: '2026-11-23T01:00:00Z',
};

test('decodes a shelf response and rejects malformed items', () => {
  assert.deepEqual(decodeShelf({ items: [book], nextCursor: null }).items[0]?.title, 'Test Book');
  assert.equal(decodeShelf({ items: [], nextCursor: 'abc' }).nextCursor, 'abc');
  assert.throws(() => decodeShelf({ items: [{ ...book, status: 'UNKNOWN' }] }));
  assert.throws(() => decodeShelf({ items: [{ ...book, id: 'not-a-uuid' }] }));
  assert.throws(() => decodeShelf({ items: 'nope' }));
  assert.throws(() => decodeBook({ ...book, title: 42 }));
});

test('decodes import tasks with and without safe errors', () => {
  const running = decodeImport({
    importId: 'a0000000-0000-4000-8000-000000000001', bookId: book.id, state: 'RUNNING',
    stage: 'SANITIZING', attempt: 1, error: null, reused: false,
  });
  assert.equal(running.state, 'RUNNING');
  assert.equal(running.error, null);

  const failed = decodeImport({
    importId: null, bookId: book.id, state: 'FAILED', stage: 'VALIDATING', attempt: 1,
    error: { code: 'EPUB_INVALID_MIMETYPE', message: '不是有效的 EPUB', retryable: true }, reused: false,
  });
  assert.equal(failed.error?.code, 'EPUB_INVALID_MIMETYPE');
  assert.equal(failed.importId, null);

  assert.throws(() => decodeImport({ bookId: book.id, state: 'RUNNING', stage: 'NOPE', attempt: 1 }));
  assert.throws(() => decodeImport({ importId: 'bad', bookId: book.id, state: 'QUEUED', stage: 'PARSING', attempt: 1 }));
});

test('generates RFC 4122 version 4 request ids', () => {
  const first = newRequestId();
  const second = newRequestId();
  assert.match(first, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  assert.notEqual(first, second);
});
