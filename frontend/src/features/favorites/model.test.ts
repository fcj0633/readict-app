import { test } from 'node:test';
import assert from 'node:assert/strict';
import { decodeDetail, decodePage, decodeSaved, decodeSources, decodeState, decodeWord, returnPath } from './model';

const f = '00000000-0000-4000-8000-000000000001';
const groups = [{ senseId: 's1', pos: 'verb', rawPos: 'V-T', zh: '接受;领受' }];

test('favorite state has exactly two subject-based states', () => {
  assert.deepEqual(decodeState({ state: 'NOT_FAVORITED', favoriteId: null }), { state: 'NOT_FAVORITED', favoriteId: null });
  assert.deepEqual(decodeState({ state: 'FAVORITED', favoriteId: f }), { state: 'FAVORITED', favoriteId: f });
  assert.throws(() => decodeState({ state: 'NOT_FAVORITED', favoriteId: f }));
  assert.throws(() => decodeState({ state: 'CONTEXT', favoriteId: f }));
});

test('deleted receipt keeps its historical id but is not an active state', () => {
  const value = decodeSaved({ state: 'FAVORITED', favoriteId: f, resourceState: 'deleted', lemmaKey: 'child', createdWord: false });
  assert.equal(value.resourceState, 'deleted');
  assert.equal(value.state, 'NOT_FAVORITED');
  assert.equal(value.favoriteId, f);
});

test('word groups are real sense groups and examples only carry sentence metadata', () => {
  const word = decodeWord({ id: f, lemma: 'accept', ipa: '/əkˈsept/', groups, definitionStatus: 'complete', dictionaryChanged: false, createdAt: '2026-11-28T00:00:00Z' });
  assert.equal(word.groups[0]?.rawPos, 'V-T');
  const example = { id: '00000000-0000-4000-8000-000000000002', sentence: 'I accept it.', surface: 'accept', wordStart: 2, wordEnd: 8, bookTitle: 'Book' };
  assert.equal(decodeDetail({ favorite: { id: f, lemma: 'accept', ipa: null, groups, definitionStatus: 'snapshot', dictionaryChanged: true, createdAt: '2026-11-28T00:00:00Z' }, examples: [example] }).examples[0]?.bookTitle, 'Book');
  assert.throws(() => decodeDetail({ favorite: word, examples: [{ ...example, wordStart: 3 }] }));
  assert.deepEqual(decodeSources({ items: [{ bookTitle: 'Book' }] }), [{ bookTitle: 'Book' }]);
  assert.deepEqual(decodePage({ items: [word], nextCursor: null }).items, [word]);
});

test('only local reader paths are valid return targets', () => {
  assert.equal(returnPath('https://example.com'), null);
  assert.equal(returnPath('//evil.test'), null);
  assert.equal(returnPath(`/reader/${f}`), `/reader/${f}`);
});
