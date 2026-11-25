import { test } from 'node:test';
import assert from 'node:assert/strict';
import { quoteParts, sentenceAt, wordAt } from './selection';

test('extracts ASCII words, apostrophes and hyphenated tokens', () => {
  assert.deepEqual(wordAt('The children are running.', 6), { start: 4, end: 12, surface: 'children' });
  assert.deepEqual(wordAt("don't stop", 3), { start: 0, end: 5, surface: "don't" });
  assert.deepEqual(wordAt('a well-known author', 5), { start: 2, end: 12, surface: 'well-known' });
  assert.deepEqual(wordAt('The children, are', 8), { start: 4, end: 12, surface: 'children' });
});

test('rejects whitespace, digits, Chinese and punctuation-only tokens', () => {
  assert.equal(wordAt('The  children are', 4), null);
  assert.equal(wordAt('123 456', 1), null);
  assert.equal(wordAt('中文测试', 1), null);
  assert.equal(wordAt('---', 1), null);
});

test('uses only indexed sentence ranges and builds canonical quotes', () => {
  const sentences = [
    { start: 0, end: 12, kind: 'SENTENCE' as const },
    { start: 13, end: 20, kind: 'FRAGMENT' as const },
  ];
  assert.deepEqual(sentenceAt(sentences, 4, 12), sentences[0]);
  assert.equal(sentenceAt(sentences, 10, 15), null);
  const quote = quoteParts('0123456789abcdefghij', 4, 12);
  assert.equal(quote.exact, '456789ab');
  assert.equal(quote.prefix, '0123');
  assert.equal(quote.suffix, 'cdefghij');
  assert.equal(quoteParts('a😀b', 1, 3).exact, '😀');
});
