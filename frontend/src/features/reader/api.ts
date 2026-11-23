import { ApiError } from '../../lib/api';
import { object } from '../../lib/http';
import type {
  ManifestSpineItem, ManifestTocNode, ReaderManifest, ReadingAnchor, ReadingProgress,
  TextIndexBlock, TextIndexChapter,
} from './engine/types';

const sha256 = /^[0-9a-f]{64}$/;

function invalid(): never { throw new ApiError(0, 'INVALID_RESPONSE', '服务响应无法识别'); }
function text(value: unknown, max: number): string {
  if (typeof value !== 'string' || value.length === 0 || value.length > max) invalid();
  return value;
}
function int(value: unknown, min: number, max: number): number {
  if (!Number.isSafeInteger(value) || (value as number) < min || (value as number) > max) invalid();
  return value as number;
}

function decodeSpine(value: unknown): ManifestSpineItem {
  if (!object(value)) invalid();
  return {
    chapterId: text(value.chapterId, 64),
    href: text(value.href, 512),
    index: int(value.index, 0, 10000),
    title: text(value.title, 512),
    textLength: int(value.textLength, 0, Number.MAX_SAFE_INTEGER),
  };
}

function decodeToc(value: unknown): ManifestTocNode {
  if (!object(value) || !Array.isArray(value.children)) invalid();
  return {
    id: text(value.id, 128), label: text(value.label, 512), href: text(value.href, 512),
    children: value.children.map(decodeToc),
  };
}

export function decodeManifest(value: unknown): ReaderManifest {
  if (!object(value)) invalid();
  const spineRaw = value.spine; const tocRaw = value.toc; const settingsRaw = value.settings;
  if (!Array.isArray(spineRaw) || !Array.isArray(tocRaw) || !object(settingsRaw)) invalid();
  const hash = text(value.artifactHash, 64);
  if (!sha256.test(hash)) invalid();
  const lineHeight = Number(settingsRaw.lineHeight);
  if (![1.6, 1.8, 2].includes(lineHeight)) invalid();
  return {
    bookId: text(value.bookId, 64),
    artifactId: text(value.artifactId, 64),
    artifactHash: hash,
    pipelineVersion: text(value.pipelineVersion, 64),
    epubUrl: text(value.epubUrl, 512),
    textIndexUrl: text(value.textIndexUrl, 1024),
    spine: spineRaw.map(decodeSpine),
    toc: tocRaw.map(decodeToc),
    settings: {
      font: text(settingsRaw.font, 32), fontSize: int(settingsRaw.fontSize, 8, 96),
      lineHeight, width: int(settingsRaw.width, 200, 2000),
      theme: text(settingsRaw.theme, 8), pronunciation: text(settingsRaw.pronunciation, 8),
    },
    settingsVersion: int(value.settingsVersion ?? 0, 0, Number.MAX_SAFE_INTEGER),
  };
}

function decodeBlock(value: unknown): TextIndexBlock {
  if (!object(value) || !Array.isArray(value.sentences)) invalid();
  return {
    blockId: text(value.blockId, 128),
    utf16Length: int(value.utf16Length, 0, Number.MAX_SAFE_INTEGER),
    textHash: text(value.textHash, 64),
    sentences: value.sentences.map(sentence => {
      if (!object(sentence)) invalid();
      return { start: int(sentence.start, 0, Number.MAX_SAFE_INTEGER), end: int(sentence.end, 0, Number.MAX_SAFE_INTEGER), kind: sentence.kind === 'FRAGMENT' ? 'FRAGMENT' as const : 'SENTENCE' as const };
    }),
  };
}

export function decodeChapter(value: unknown): TextIndexChapter {
  if (!object(value) || !Array.isArray(value.blocks)) invalid();
  return {
    spineHref: text(value.spineHref, 512),
    spineIndex: int(value.spineIndex, 0, 10000),
    totalUtf16: int(value.totalUtf16, 0, Number.MAX_SAFE_INTEGER),
    blocks: value.blocks.map(decodeBlock),
  };
}

function decodeAnchor(value: unknown): ReadingAnchor {
  if (!object(value)) invalid();
  const anchor: ReadingAnchor = {
    artifactId: text(value.artifactId, 64), artifactHash: text(value.artifactHash, 64),
    pipelineVersion: text(value.pipelineVersion, 64), spineHref: text(value.spineHref, 512),
    cfi: text(value.cfi, 4096),
  };
  if (typeof value.blockId === 'string' && value.blockId.length <= 128) anchor.blockId = value.blockId;
  if (typeof value.blockOffset === 'number' && value.blockOffset >= 0) anchor.blockOffset = value.blockOffset;
  if (object(value.quote)) {
    const quote = value.quote;
    if (typeof quote.exact === 'string' && quote.exact.length <= 256
      && typeof quote.prefix === 'string' && quote.prefix.length <= 128
      && typeof quote.suffix === 'string' && quote.suffix.length <= 128) {
      anchor.quote = { exact: quote.exact, prefix: quote.prefix, suffix: quote.suffix };
    }
  }
  return anchor;
}

export function decodeProgress(value: unknown): { progress: ReadingProgress | null } {
  if (!object(value)) invalid();
  if (value.progress === null || value.progress === undefined) return { progress: null };
  const progress = value.progress;
  if (!object(progress) || typeof progress.progressRatio !== 'number') invalid();
  return {
    progress: {
      bookId: text(progress.bookId, 64), anchor: decodeAnchor(progress.anchor),
      progressRatio: progress.progressRatio, version: int(progress.version, 1, Number.MAX_SAFE_INTEGER),
      updatedAt: text(progress.updatedAt, 40),
    },
  };
}

async function json(response: Response): Promise<unknown> {
  if (response.status === 204) return undefined;
  const type = response.headers.get('Content-Type')?.split(';')[0]?.trim().toLowerCase();
  if (type !== 'application/json' && type !== 'application/problem+json') {
    throw new ApiError(response.status, 'INVALID_RESPONSE', '服务响应无法识别');
  }
  return response.json() as Promise<unknown>;
}

export async function loadManifest(bookId: string, signal?: AbortSignal): Promise<ReaderManifest> {
  const response = await fetch(`/api/v1/books/${encodeURIComponent(bookId)}/reader-manifest`, {
    credentials: 'same-origin', headers: { Accept: 'application/json' }, signal,
  });
  if (!response.ok) throw problem(response, await json(response).catch(() => undefined));
  return decodeManifest(await json(response));
}

export async function downloadEpub(url: string, signal?: AbortSignal): Promise<ArrayBuffer> {
  const response = await fetch(url, { credentials: 'same-origin', signal });
  if (!response.ok) throw new ApiError(response.status, 'ARTIFACT_REVOKED', '阅读资源暂不可用');
  return response.arrayBuffer();
}

export async function loadChapterIndex(manifest: ReaderManifest, spineHref: string, signal?: AbortSignal): Promise<TextIndexChapter> {
  const url = manifest.textIndexUrl.replace('{spineHref}', encodeURIComponent(spineHref));
  const response = await fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' }, signal });
  if (!response.ok) throw problem(response, await json(response).catch(() => undefined));
  return decodeChapter(await json(response));
}

export interface ProgressConflict extends Error {
  currentVersion: number;
  serverProgress: ReadingProgress | null;
}

export async function loadProgress(bookId: string, signal?: AbortSignal): Promise<{ progress: ReadingProgress | null }> {
  const response = await fetch(`/api/v1/books/${encodeURIComponent(bookId)}/progress`, {
    credentials: 'same-origin', headers: { Accept: 'application/json' }, signal,
  });
  if (!response.ok) throw problem(response, await json(response).catch(() => undefined));
  return decodeProgress(await json(response));
}

export async function saveProgress(bookId: string, body: {
  expectedVersion: number; requestId: string; anchor: ReadingAnchor;
}, signal?: AbortSignal): Promise<ReadingProgress> {
  const csrf = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' }).then(r => r.json() as Promise<{ token: string; headerName: string }>);
  const response = await fetch(`/api/v1/books/${encodeURIComponent(bookId)}/progress`, {
    method: 'PUT', credentials: 'same-origin', signal,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json', [csrf.headerName]: csrf.token },
    body: JSON.stringify(body),
  });
  const payload = await json(response).catch(() => undefined);
  if (response.status === 409 && object(payload) && payload.code === 'PROGRESS_CONFLICT') {
    const conflict = new Error('阅读进度已被另一窗口更新') as ProgressConflict;
    conflict.currentVersion = typeof payload.currentVersion === 'number' ? payload.currentVersion : 0;
    conflict.serverProgress = object(payload.serverProgress)
      ? (decodeProgress({ progress: payload.serverProgress }).progress) : null;
    throw conflict;
  }
  if (!response.ok) throw problem(response, payload);
  return decodeProgress(payload).progress ?? invalid();
}

function problem(response: Response, payload: unknown): ApiError {
  if (object(payload) && typeof payload.code === 'string' && /^[A-Z_]{1,64}$/.test(payload.code)
    && typeof payload.title === 'string' && payload.title.length < 160) {
    return new ApiError(response.status, payload.code, payload.title,
      typeof payload.requestId === 'string' ? payload.requestId : undefined,
      payload.retryable === true);
  }
  return new ApiError(response.status, 'HTTP_ERROR', '请求未完成', undefined, response.status >= 500);
}
