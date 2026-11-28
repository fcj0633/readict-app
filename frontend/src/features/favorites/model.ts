import {parseReaderPath, safeReturnTo} from '../../app/routes';
import { ApiError } from '../../lib/api';
import { object } from '../../lib/http';
import type { ReadingAnchor } from '../reader/engine/types';

export interface FavoriteState { state: 'NONE' | 'WORD' | 'CONTEXT'; favoriteId: string | null; contextId: string | null; contextCount: number }
export interface Saved extends FavoriteState { lemmaKey: string; createdWord: boolean; createdContext: boolean; resourceState: 'active' | 'deleted' }
export interface FavoriteWord { id: string; lemma: string; ipa: string | null; pos: string; meaning: string; contextCount: number; createdAt: string }
export interface FavoriteContext { contextId: string; favoriteId: string; lemma: string; sentence: string; surface: string; wordStart: number; wordEnd: number; bookTitle: string; chapterTitle: string; sourceKey: string; sourceAvailable: boolean; bookId: string | null; anchor: ReadingAnchor | null; translation: string | null; createdAt: string }
export interface FavoriteDetail { favorite: FavoriteWord; contexts: FavoriteContext[] }
export interface FavoriteSource { originKey: string; bookTitle: string; sourceAvailable: boolean; contextCount: number }
export interface FavoritePage { items: (FavoriteWord | FavoriteContext)[]; nextCursor: string | null }
function invalid(): never { throw new ApiError(0, 'INVALID_RESPONSE', '收藏响应无法识别'); }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function str(v: unknown): string { if (typeof v !== 'string') invalid(); return v; }
function id(v: unknown): string { const text = str(v); if (!uuid.test(text)) invalid(); return text; }
function count(v: unknown): number { if (!Number.isSafeInteger(v) || (v as number) < 0) invalid(); return v as number; }
function bool(v: unknown): boolean { if (typeof v !== 'boolean') invalid(); return v; }
function date(v: unknown): string { const s = str(v); if (!Number.isFinite(Date.parse(s))) invalid(); return s; }
export function decodeState(v: unknown): FavoriteState {
  if (!object(v) || !['NONE', 'WORD', 'CONTEXT'].includes(String(v.state))) invalid();
  const state = v.state as FavoriteState['state'];
  const favoriteId = v.favoriteId === null ? null : id(v.favoriteId);
  const contextId = v.contextId === null ? null : id(v.contextId);
  const contextCount = count(v.contextCount);
  if (state === 'NONE' ? favoriteId !== null || contextId !== null || contextCount !== 0 : !favoriteId || contextCount < 1 || (state === 'CONTEXT' ? !contextId : contextId !== null)) invalid();
  return { state, favoriteId, contextId, contextCount };
}
export function decodeSaved(v: unknown): Saved {
  if (!object(v) || (v.resourceState !== 'active' && v.resourceState !== 'deleted')) invalid();
  // Deleted receipts retain the original IDs without claiming an active state.
  const base = v.resourceState === 'deleted' ? { state: 'NONE' as const, favoriteId: id(v.favoriteId), contextId: id(v.contextId), contextCount: 0 } : decodeState(v);
  return { ...base, lemmaKey: str(v.lemmaKey), createdWord: bool(v.createdWord), createdContext: bool(v.createdContext), resourceState: v.resourceState };
}
export function decodeWord(v: unknown): FavoriteWord {
  if (!object(v)) invalid();
  return { id: id(v.id), lemma: str(v.lemma), ipa: v.ipa === null ? null : str(v.ipa), pos: str(v.pos), meaning: str(v.meaning), contextCount: count(v.contextCount), createdAt: date(v.createdAt) };
}
export function decodeContext(v: unknown): FavoriteContext {
  if (!object(v)) invalid();
  const sentence = str(v.sentence), surface = str(v.surface), wordStart = count(v.wordStart), wordEnd = count(v.wordEnd);
  if (wordEnd <= wordStart || wordEnd > sentence.length || sentence.slice(wordStart, wordEnd) !== surface) invalid();
  const sourceAvailable = bool(v.sourceAvailable);
  let anchor: ReadingAnchor | null = null;
  if (sourceAvailable) {
    const a = v.anchor;
    if (!object(a) || !object(a.quote)) invalid();
    anchor = { artifactId: id(a.artifactId), artifactHash: str(a.artifactHash), pipelineVersion: str(a.pipelineVersion), spineHref: str(a.spineHref), cfi: str(a.cfi), blockId: str(a.blockId), blockOffset: count(a.blockOffset), quote: { exact: str(a.quote.exact), prefix: str(a.quote.prefix), suffix: str(a.quote.suffix) } };
    if (anchor.quote?.exact !== sentence || !anchor.cfi.startsWith('epubcfi(')) invalid();
  } else if (v.anchor !== null) invalid();
  return { contextId: id(v.contextId), favoriteId: id(v.favoriteId), lemma: str(v.lemma), sentence, surface, wordStart, wordEnd, bookTitle: str(v.bookTitle), chapterTitle: str(v.chapterTitle), sourceKey: id(v.sourceKey), sourceAvailable, bookId: v.bookId === null ? null : id(v.bookId), anchor, translation: v.translation === null ? null : str(v.translation), createdAt: date(v.createdAt) };
}
export function decodeDetail(v: unknown): FavoriteDetail {
  if (!object(v) || !Array.isArray(v.contexts)) invalid();
  return { favorite: decodeWord(v.favorite), contexts: v.contexts.map(decodeContext) };
}
export function decodePage(view: 'words' | 'sentences', v: unknown): FavoritePage {
  if (!object(v) || !Array.isArray(v.items)) invalid();
  return { items: v.items.map(item => view === 'words' ? decodeWord(item) : decodeContext(item)), nextCursor: v.nextCursor === null ? null : str(v.nextCursor) };
}
export function decodeSources(v: unknown): FavoriteSource[] {
  if (!object(v) || !Array.isArray(v.items)) invalid();
  return v.items.map(r => { if (!object(r)) invalid(); return { originKey: id(r.originKey), bookTitle: str(r.bookTitle), sourceAvailable: bool(r.sourceAvailable), contextCount: count(r.contextCount) }; });
}
export function decodeStats(v: unknown): { words: number; contexts: number } {
  if (!object(v)) invalid(); return { words: count(v.words), contexts: count(v.contexts) };
}
export function contextLink(c: FavoriteContext): string | null {
  return c.sourceAvailable && c.bookId && c.anchor ? safeReturnTo(`/reader/${c.bookId}?favorite=${c.favoriteId}&context=${c.contextId}`) : null;
}
export function returnPath(from: string | null): string | null {return from ? parseReaderPath(from) : null;}
export function favoriteTarget(search: string): { favoriteId: string; contextId: string } | null {
  const p = new URLSearchParams(search); const f = p.get('favorite'), c = p.get('context');
  if (!f && !c) return null;
  if (!f || !c || !uuid.test(f) || !uuid.test(c) || p.getAll('favorite').length !== 1 || p.getAll('context').length !== 1) return null;
  return { favoriteId: f, contextId: c };
}
