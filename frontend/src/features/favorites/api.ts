import { request, voidResponse } from '../../lib/http';
import type { SelectionSource } from '../reader/engine/selection';
import { decodePronunciation } from '../dictionary/model';
import { decodeState, decodeSaved, decodeDetail, decodePage, decodeSources, decodeStats } from './model';
function wireSource(s: SelectionSource): SelectionSource {
  return { artifactId: s.artifactId, spineHref: s.spineHref, blockId: s.blockId, sentenceStart: s.sentenceStart, sentenceEnd: s.sentenceEnd,
    sentence: s.sentence, surface: s.surface, wordStart: s.wordStart, wordEnd: s.wordEnd, wordRangeCfi: s.wordRangeCfi, sentenceRangeCfi: s.sentenceRangeCfi,
    quote: { exact: s.quote.exact, prefix: s.quote.prefix, suffix: s.quote.suffix } };
}
export const favoriteApi = {
  state: (lookupId: string, senseId: string, signal?: AbortSignal) => request(`/favorites/state?${new URLSearchParams({ lookupId, senseId })}`, decodeState, { signal }),
  stateSource: (bookId: string, source: SelectionSource, signal?: AbortSignal) => request('/favorites/state-source', decodeState, { method: 'POST', body: { bookId, source }, signal }),
  save: (lookupId: string, senseId: string, source: SelectionSource, key: string) => request('/favorites', decodeSaved, { method: 'POST', body: { lookupId, senseId, source: wireSource(source) }, headers: { 'Idempotency-Key': key } }),
  remove: (id: string) => request(`/favorites/${id}`, voidResponse, { method: 'DELETE' }),
  detail: (id: string, signal?: AbortSignal) => request(`/favorites/${id}`, decodeDetail, { signal }),
  list: (q: string, source: string, sort: string, cursor: string, signal?: AbortSignal) => request(`/favorites?${new URLSearchParams({ view: 'words', q, source, sort, cursor })}`, decodePage, { signal }),
  sources: (signal?: AbortSignal) => request('/favorites/sources', decodeSources, { signal }),
  stats: (signal?: AbortSignal) => request('/favorites/stats', decodeStats, { signal }),
  pronunciations: (id: string, signal?: AbortSignal) => request(`/favorites/${id}/pronunciations`, decodePronunciation, { signal }),
};
