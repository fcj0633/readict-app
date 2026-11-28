import { ApiError } from '../../lib/api';
import { request, upload } from '../../lib/http';
import type { Book, BookStatus, ImportState, ImportTask, Shelf } from './model';

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value);
const BOOK_STATUS: BookStatus[] = ['IMPORTING', 'READY', 'FAILED', 'BROKEN'];
const IMPORT_STATE: ImportState[] = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'];
const STAGES: ImportTask['stage'][] = ['VALIDATING', 'PARSING', 'SANITIZING', 'PUBLISHING'];

function invalid(): never { throw new ApiError(0, 'INVALID_RESPONSE', '服务响应无法识别'); }

function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || value.length > max) invalid();
  return value;
}

function nullableText(value: unknown, max: number): string | null {
  if (value === null || value === undefined) return null;
  return text(value, max);
}

export function decodeBook(value: unknown): Book {
  if (!record(value) || typeof value.id !== 'string' || !uuid.test(value.id)) invalid();
  if (!BOOK_STATUS.includes(value.status as BookStatus)) invalid();
  return {
    id: value.id,
    title: text(value.title, 512),
    author: nullableText(value.author, 512),
    coverUrl: nullableText(value.coverUrl, 512),
    status: value.status as BookStatus,
    progressRatio: typeof value.progressRatio === 'number' ? value.progressRatio : null,
    lastReadAt: text(value.lastReadAt, 40),
    createdAt: text(value.createdAt, 40),
  };
}

export function decodeShelf(body: unknown): Shelf {
  if (!record(body) || !Array.isArray(body.items)) invalid();
  const items = body.items.map(decodeBook);
  const nextCursor = body.nextCursor === null || body.nextCursor === undefined ? null : text(body.nextCursor, 512);
  return { items, nextCursor };
}

export function decodeImport(body: unknown): ImportTask {
  if (!record(body) || !IMPORT_STATE.includes(body.state as ImportState)
    || !STAGES.includes(body.stage as ImportTask['stage']) || typeof body.attempt !== 'number'
    || typeof body.bookId !== 'string' || !uuid.test(body.bookId)) invalid();
  const importId = body.importId === null || body.importId === undefined ? null
    : typeof body.importId === 'string' && uuid.test(body.importId) ? body.importId : invalid();
  const error = record(body.error) && typeof body.error.code === 'string' && typeof body.error.message === 'string'
    ? { code: body.error.code, message: body.error.message, retryable: body.error.retryable === true }
    : null;
  return {
    importId, bookId: body.bookId, state: body.state as ImportState,
    stage: body.stage as ImportTask['stage'], attempt: body.attempt, error,
    reused: body.reused === true,
  };
}

export function listBooks(query: string, cursor: string | null, signal?: AbortSignal, limit?: number): Promise<Shelf> {
  const params = new URLSearchParams();
  if (query.trim().length > 0) params.set('q', query.trim());
  if (cursor) params.set('cursor', cursor);
  if (limit) params.set('limit', String(limit));
  const suffix = params.toString();
  return request('/books' + (suffix ? '?' + suffix : ''), decodeShelf, { signal });
}

export function fetchImport(importId: string, signal?: AbortSignal): Promise<ImportTask> {
  return request('/books/imports/' + importId, decodeImport, { signal });
}

export function createImport(file: File, idempotencyKey: string,
  onProgress: (loaded: number, total: number) => void, signal?: AbortSignal): Promise<ImportTask> {
  return upload('/books/imports', file, decodeImport, { idempotencyKey, onProgress, signal });
}

export function removeBook(bookId: string, idempotencyKey: string): Promise<void> {
  return request('/books/' + bookId, body => decodeDeleteAccepted(body, bookId),
    { method: 'DELETE', headers: { 'Idempotency-Key': idempotencyKey } });
}

export function newRequestId(): string {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes).map(value => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function decodeDeleteAccepted(body: unknown, bookId: string): void {
  if (!record(body) || body.bookId !== bookId || body.state !== 'DELETING') invalid();
}
