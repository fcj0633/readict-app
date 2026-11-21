import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { realpath, stat } from 'node:fs/promises';
import { MDX, MDD } from 'js-mdict';

export const parserVersion = 'js-mdict@6.0.6';
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
export async function fingerprint(path) {
  const resolved = await realpath(path); const metadata = await stat(resolved);
  if (!metadata.isFile()) throw new Error('INPUT_NOT_FILE');
  const hash = createHash('sha256');
  for await (const bytes of createReadStream(resolved)) hash.update(bytes);
  return { sizeBytes: metadata.size, sha256: hash.digest('hex') };
}

// Read key blocks in their physical order: 6.0.6 always sorts keywordList.
// Its per-record lookup truncates spans at record-block boundaries, so read each
// complete block through the public API and assemble the requested byte range.
export class ParserAdapter {
  constructor(path, resource = false, factory = (file, binary) => new (binary ? MDD : MDX)(file)) {
    this.dictionary = factory(path, resource);
    this.resource = resource; this.cachedBlock = -1;
    try {
      this.keys = this.dictionary.keyInfoList.flatMap((_, i) => this.dictionary.lookupPartialKeyBlockListByKeyInfoId(i));
      if (this.keys.length !== this.dictionary.keyHeader.keywordNum) throw new Error('INDEX_COUNT_MISMATCH');
      this.totalBytes = this.dictionary.recordInfoList.reduce((sum, block) => sum + block.unpackSize, 0);
      this.keys.forEach((key, i) => {
        key.recordEndOffset = this.keys[i + 1]?.recordStartOffset ?? this.totalBytes;
        if (!Number.isSafeInteger(key.recordStartOffset) || key.recordStartOffset < 0 || key.recordEndOffset < key.recordStartOffset || key.recordEndOffset > this.totalBytes) throw new Error('INDEX_RANGE_INVALID');
      });
      this.decoder = new TextDecoder(this.dictionary.meta.encoding, { fatal: true });
    } catch (error) { this.close(); throw error; }
  }
  readBytes(key) {
    const start = key.recordStartOffset, end = key.recordEndOffset;
    if (end - start > 2 * 1024 * 1024) throw new Error('SOURCE_RECORD_TOO_LARGE');
    const chunks = [];
    for (let i = 0; i < this.dictionary.recordInfoList.length; i++) {
      const block = this.dictionary.recordInfoList[i]; const lo = block.unpackAccumulatorOffset, hi = lo + block.unpackSize;
      if (hi <= start) continue;
      if (lo >= end) break;
      if (this.cachedBlock !== i) {
        this.cachedBytes = this.dictionary.lookupRecordByKeyBlock({ recordStartOffset: lo, recordEndOffset: hi });
        if (this.cachedBytes.length !== block.unpackSize) throw new Error('RECORD_BLOCK_SIZE_MISMATCH');
        this.cachedBlock = i;
      }
      chunks.push(Buffer.from(this.cachedBytes.subarray(Math.max(start, lo) - lo, Math.min(end, hi) - lo)));
    }
    const result = Buffer.concat(chunks);
    if (result.length !== end - start) throw new Error('RECORD_RANGE_TRUNCATED');
    return result;
  }
  async *enumerate() {
    for (let i = 0; i < this.keys.length; i++) {
      const key = this.keys[i]; const bytes = this.readBytes(key);
      const rawDecodedText = this.resource ? undefined : this.decoder.decode(bytes);
      yield { sourceOrdinal: i, indexKey: key.keyText, recordOffset: key.recordStartOffset,
        rawDecodedText, sourceRecordHash: sha256(this.resource ? bytes : Buffer.from(rawDecodedText, 'utf8')), byteLength: bytes.length };
    }
  }
  close() { this.dictionary.close(); this.cachedBytes = undefined; }
}
