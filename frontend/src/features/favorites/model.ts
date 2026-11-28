import { parseReaderPath } from '../../app/routes';
import { ApiError } from '../../lib/api';
import { object } from '../../lib/http';
import { decodeSenseGroups, type SenseGroup } from '../dictionary/sense-groups';

export interface FavoriteState { state: 'NOT_FAVORITED' | 'FAVORITED'; favoriteId: string | null }
export interface Saved extends FavoriteState { lemmaKey: string; createdWord: boolean; resourceState: 'active' | 'deleted' }
export interface FavoriteWord {
  id: string; lemma: string; ipa: string | null; groups: SenseGroup[];
  definitionStatus: 'complete' | 'snapshot'; dictionaryChanged: boolean; createdAt: string;
}
export interface WordExample { id: string; sentence: string; surface: string; wordStart: number; wordEnd: number; bookTitle: string }
export interface FavoriteDetail { favorite: FavoriteWord; examples: WordExample[] }
export interface FavoritePage { items: FavoriteWord[]; nextCursor: string | null }
export interface FavoriteSource { bookTitle: string }

function invalid(): never { throw new ApiError(0, 'INVALID_RESPONSE', '收藏响应无法识别'); }
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function str(v: unknown, max = 10000): string { if (typeof v !== 'string' || v.length > max) invalid(); return v; }
function id(v: unknown): string { const text = str(v, 64); if (!uuid.test(text)) invalid(); return text; }
function count(v: unknown): number { if (!Number.isSafeInteger(v) || (v as number) < 0) invalid(); return v as number; }
function bool(v: unknown): boolean { if (typeof v !== 'boolean') invalid(); return v; }
function date(v: unknown): string { const value = str(v, 64); if (!Number.isFinite(Date.parse(value))) invalid(); return value; }
function groups(v: unknown): SenseGroup[] { try { return decodeSenseGroups(v); } catch { return invalid(); } }

export function decodeState(v: unknown): FavoriteState {
  if (!object(v) || !['NOT_FAVORITED', 'FAVORITED'].includes(String(v.state))) invalid();
  const state = v.state as FavoriteState['state'];
  const favoriteId = v.favoriteId === null ? null : id(v.favoriteId);
  if (state === 'NOT_FAVORITED' ? favoriteId !== null : !favoriteId) invalid();
  return { state, favoriteId };
}

export function decodeSaved(v: unknown): Saved {
  if (!object(v) || (v.resourceState !== 'active' && v.resourceState !== 'deleted')) invalid();
  const base = v.resourceState === 'deleted'
    ? { state: 'NOT_FAVORITED' as const, favoriteId: v.favoriteId === null ? null : id(v.favoriteId) }
    : decodeState({ state: v.state, favoriteId: v.favoriteId });
  return { ...base, lemmaKey: str(v.lemmaKey, 128), createdWord: bool(v.createdWord), resourceState: v.resourceState };
}

export function decodeWord(v: unknown): FavoriteWord {
  if (!object(v) || (v.definitionStatus !== 'complete' && v.definitionStatus !== 'snapshot')) invalid();
  return {
    id: id(v.id), lemma: str(v.lemma, 128), ipa: v.ipa === null ? null : str(v.ipa, 256),
    groups: groups(v.groups), definitionStatus: v.definitionStatus,
    dictionaryChanged: bool(v.dictionaryChanged), createdAt: date(v.createdAt),
  };
}

export function decodeExample(v: unknown): WordExample {
  if (!object(v)) invalid();
  const sentence = str(v.sentence), surface = str(v.surface, 128), wordStart = count(v.wordStart), wordEnd = count(v.wordEnd);
  if (wordEnd <= wordStart || wordEnd > sentence.length || sentence.slice(wordStart, wordEnd) !== surface) invalid();
  return { id: id(v.id), sentence, surface, wordStart, wordEnd, bookTitle: str(v.bookTitle, 512) };
}

export function decodeDetail(v: unknown): FavoriteDetail {
  if (!object(v) || !Array.isArray(v.examples)) invalid();
  return { favorite: decodeWord(v.favorite), examples: v.examples.map(decodeExample) };
}

export function decodePage(v: unknown): FavoritePage {
  if (!object(v) || !Array.isArray(v.items)) invalid();
  return { items: v.items.map(decodeWord), nextCursor: v.nextCursor === null ? null : str(v.nextCursor, 512) };
}

export function decodeSources(v: unknown): FavoriteSource[] {
  if (!object(v) || !Array.isArray(v.items)) invalid();
  return v.items.map(item => { if (!object(item)) invalid(); return { bookTitle: str(item.bookTitle, 512) }; });
}

export function decodeStats(v: unknown): { words: number } {
  if (!object(v)) invalid(); return { words: count(v.words) };
}

export function returnPath(from: string | null): string | null { return from ? parseReaderPath(from) : null; }
