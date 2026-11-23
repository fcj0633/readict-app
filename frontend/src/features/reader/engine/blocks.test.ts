import { test } from 'node:test';
import assert from 'node:assert/strict';
import { locate } from './blocks';

const blocks = [
  { text: 'abc', globalStart: 0 },
  { text: 'de', globalStart: 3 },
  { text: 'fgh', globalStart: 5 },
];

test('locates offsets at block starts, middles and boundaries', () => {
  assert.deepEqual(locate(blocks, 0), { blockIndex: 0, blockOffset: 0 });
  assert.deepEqual(locate(blocks, 2), { blockIndex: 0, blockOffset: 2 });
  assert.deepEqual(locate(blocks, 3), { blockIndex: 1, blockOffset: 0 });
  assert.deepEqual(locate(blocks, 6), { blockIndex: 2, blockOffset: 1 });
  assert.deepEqual(locate(blocks, 8), { blockIndex: 2, blockOffset: 3 });
});

test('rejects offsets outside the chapter', () => {
  assert.equal(locate(blocks, 9), null);
  assert.equal(locate([], 0), null);
});
