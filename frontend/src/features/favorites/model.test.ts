import { test } from 'node:test';
import assert from 'node:assert/strict';
import { decodeState, decodeSaved, decodeContext, favoriteTarget, returnPath } from './model';
const f = '00000000-0000-4000-8000-000000000001', c = '00000000-0000-4000-8000-000000000002';
test('favorite state enforces none word and context invariants', () => {
  assert.equal(decodeState({ state: 'NONE', favoriteId: null, contextId: null, contextCount: 0 }).state, 'NONE');
  assert.equal(decodeState({ state: 'WORD', favoriteId: f, contextId: null, contextCount: 2 }).contextCount, 2);
  assert.equal(decodeState({ state: 'CONTEXT', favoriteId: f, contextId: c, contextCount: 2 }).contextId, c);
  for (const v of [{ state: 'NONE', favoriteId: f, contextId: null, contextCount: 1 }, { state: 'CONTEXT', favoriteId: f, contextId: null, contextCount: 2 }]) assert.throws(() => decodeState(v));
});
test('deleted receipt preserves identifiers without restoring active state', () => {
  const v = decodeSaved({ state: 'NONE', favoriteId: f, contextId: c, contextCount: 0, resourceState: 'deleted', lemmaKey: 'child', createdWord: false, createdContext: false });
  assert.equal(v.resourceState, 'deleted'); assert.equal(v.favoriteId, f);
});
test('return links only accept matching UUID query pairs and local reader paths', () => {
  assert.deepEqual(favoriteTarget(`?favorite=${f}&context=${c}`), { favoriteId: f, contextId: c });
  assert.throws(() => favoriteTarget(`?favorite=${f}`));
  assert.equal(returnPath('https://example.com'), null); assert.equal(returnPath('//evil.test'), null);
  assert.equal(returnPath(`/reader/${f}`), `/reader/${f}`);
});
test('deleted source cannot expose an active anchor or invalid highlight', () => {
  const v = { contextId: c, favoriteId: f, lemma: 'child', sentence: 'The children.', surface: 'children', wordStart: 4, wordEnd: 12, bookTitle: 'Synthetic', chapterTitle: 'One', sourceKey: f, sourceAvailable: false, bookId: null, anchor: null, translation: null, createdAt: '2026-11-25T00:00:00Z' };
  assert.equal(decodeContext(v).anchor, null);
  assert.throws(() => decodeContext({ ...v, anchor: {} }));
  assert.throws(() => decodeContext({ ...v, wordStart: 5 }));
});
