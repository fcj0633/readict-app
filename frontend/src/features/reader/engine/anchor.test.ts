import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildQuote, findQuote, quoteMatches } from './anchor';
import type { BlockText } from './blocks';

test('builds a bounded quote starting at the offset', () => {
  const text = `${'a'.repeat(100)}TARGET${'b'.repeat(100)}`;
  const quote = buildQuote(text, 100);
  assert.ok(quote.exact.startsWith('TARGET'));
  assert.ok(quote.exact.length <= 256);
  assert.ok(quote.prefix.length <= 128);
  assert.ok(quote.suffix.length <= 128);
});

test('never splits a surrogate pair at quote boundaries', () => {
  const text = '😀😀abc😀😀';
  const quote = buildQuote(text, 2);
  const first = quote.exact.charCodeAt(0);
  const last = quote.exact.charCodeAt(quote.exact.length - 1);
  assert.ok(first < 0xdc00 || first > 0xdfff, 'exact must not start with a low surrogate');
  assert.ok(last < 0xd800 || last > 0xdbff, 'exact must not end with a high surrogate');
});

test('finds a unique quote and refuses ambiguous ones', () => {
  const blocks: BlockText[] = [
    { text: 'The children are running toward the house.', globalStart: 0 },
    { text: 'They kept running.', globalStart: 44 },
  ];
  const unique = findQuote(blocks, { exact: 'children are running', prefix: 'The ', suffix: ' toward' });
  assert.deepEqual(unique, { blockIndex: 0, offset: 4 });
  const ambiguous = findQuote(blocks, { exact: 'running', prefix: '', suffix: '' });
  assert.equal(ambiguous, null);
});

test('compares the persisted quote with the current block text', () => {
  const text = 'The children are running toward the house.';
  assert.equal(quoteMatches(text, 4, { exact: 'children are running', prefix: 'The ', suffix: ' toward' }), true);
  assert.equal(quoteMatches(text, 5, { exact: 'children are running', prefix: 'The ', suffix: ' toward' }), false);
  assert.equal(quoteMatches(text, 0, undefined), true);
  assert.equal(quoteMatches(text, 0, { exact: 'x'.repeat(300), prefix: '', suffix: '' }), false);
});
