import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { ParserAdapter, fingerprint, sha256 } from '../src/parser-adapter.mjs';

function fixture() {
  const bytes = Buffer.from('甲乙丙丁'); let closed = false;
  return {
    meta: { encoding: 'UTF-8' }, keyHeader: { keywordNum: 3 }, keyInfoList: [{}, {}],
    recordInfoList: [{ unpackAccumulatorOffset: 0, unpackSize: 4 }, { unpackAccumulatorOffset: 4, unpackSize: 8 }],
    keywordList: [{ keyText: 'sorted-is-not-source-order' }],
    lookupPartialKeyBlockListByKeyInfoId(i) { return i === 0 ? [{ keyText: 'z', recordStartOffset: 0 }, { keyText: 'same', recordStartOffset: 3 }] : [{ keyText: 'same', recordStartOffset: 9 }]; },
    lookupRecordByKeyBlock({ recordStartOffset, recordEndOffset }) { return bytes.subarray(recordStartOffset, recordEndOffset); },
    close() { closed = true; }, get closed() { return closed; }
  };
}
test('physical source order, duplicate keys and a multibyte record spanning blocks are preserved', async () => {
  const fake = fixture(); const adapter = new ParserAdapter('synthetic', false, () => fake); const records = [];
  try { for await (const r of adapter.enumerate()) records.push(r); } finally { adapter.close(); }
  assert.deepEqual(records.map(r => [r.sourceOrdinal, r.indexKey, r.rawDecodedText]), [[0, 'z', '甲'], [1, 'same', '乙丙'], [2, 'same', '丁']]);
  assert.equal(records[1].sourceRecordHash, sha256(Buffer.from('乙丙'))); assert.equal(fake.closed, true);
});
test('count mismatches and impossible ranges fail without silently dropping keys', () => {
  for (const mutate of [d => d.keyHeader.keywordNum++, d => d.lookupPartialKeyBlockListByKeyInfoId = () => [{ keyText: 'bad', recordStartOffset: -1 }]]) {
    const d = fixture(); mutate(d); assert.throws(() => new ParserAdapter('synthetic', false, () => d)); assert.equal(d.closed, true);
  }
});
test('block truncation fails rather than returning a partial definition', async () => {
  const d = fixture(); d.lookupRecordByKeyBlock = () => Buffer.from('x'); const adapter = new ParserAdapter('synthetic', false, () => d);
  try { await assert.rejects(async () => { for await (const r of adapter.enumerate()) void r; }, /RECORD_BLOCK_SIZE_MISMATCH/); } finally { adapter.close(); }
});
test('invalid Unicode bytes fail strict decoding', async () => {
  const d = fixture(); const original = d.lookupRecordByKeyBlock; d.lookupRecordByKeyBlock = key => { const bytes = Buffer.from(original(key)); bytes[0] = 0xff; return bytes; }; const adapter = new ParserAdapter('synthetic', false, () => d);
  try { await assert.rejects(async () => { for await (const r of adapter.enumerate()) void r; }, /encoded data/); } finally { adapter.close(); }
});
test('fingerprints use original file bytes, not decoded text', async () => {
  const path = await mkdtemp(join(tmpdir(), 'readict-col-'));
  try { const file = join(path, 'synthetic'); await writeFile(file, Buffer.from([0xff, 0, 1])); assert.deepEqual(await fingerprint(file), { sizeBytes: 3, sha256: sha256(Buffer.from([0xff, 0, 1])) }); } finally { await rm(path, { recursive: true }); }
});
