// Self-authored tiny MDX v2 writer, for parser integration tests only.
// It contains no dictionary source text and deliberately exercises duplicate
// keys and a record spanning uncompressed record-block boundaries.
import { deflateSync } from 'node:zlib';
const n64 = n => { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(n)); return b; };
const n16 = n => { const b = Buffer.alloc(2); b.writeUInt16BE(n); return b; };
const checksum = bytes => {
  let a = 1, b = 0;
  for (const byte of bytes) { a = (a + byte) % 65521; b = (b + a) % 65521; }
  const result = Buffer.alloc(4); result.writeUInt32BE(((b << 16) | a) >>> 0); return result;
};
const plainBlock = bytes => Buffer.concat([Buffer.alloc(4), checksum(bytes), bytes]);
export function makeMdx(records, splitAt = null) {
  const header = Buffer.from('<Dictionary GeneratedByEngineVersion="2.0" RequiredEngineVersion="2.0" Encoding="UTF-8" Encrypted="No" KeyCaseSensitive="Yes" StripKey="No"/>\0', 'utf16le');
  const headerLength = Buffer.alloc(4); headerLength.writeUInt32BE(header.length);
  const content = Buffer.concat(records.map(r => Buffer.from(r.text))); let offset = 0;
  const keys = Buffer.concat(records.map(r => { const part = Buffer.concat([n64(offset), Buffer.from(r.key), Buffer.alloc(1)]); offset += Buffer.byteLength(r.text); return part; }));
  const keyBlock = plainBlock(keys); const first = records[0].key, last = records.at(-1).key;
  const keyInfo = Buffer.concat([n64(records.length), n16(Buffer.byteLength(first)), Buffer.from(first), Buffer.alloc(1), n16(Buffer.byteLength(last)), Buffer.from(last), Buffer.alloc(1), n64(keyBlock.length), n64(keys.length)]);
  const packedInfo = Buffer.concat([Buffer.from([2,0,0,0]), checksum(keyInfo), deflateSync(keyInfo)]);
  const keyHeader = Buffer.concat([n64(1), n64(records.length), n64(keyInfo.length), n64(packedInfo.length), n64(keyBlock.length)]);
  const segments = splitAt == null ? [content] : [content.subarray(0, splitAt), content.subarray(splitAt)]; const blocks = segments.map(plainBlock);
  const recordHeader = Buffer.concat([n64(blocks.length), n64(records.length), n64(blocks.length * 16), n64(blocks.reduce((n,b) => n + b.length, 0))]);
  return Buffer.concat([headerLength, header, checksum(header), keyHeader, checksum(keyHeader), packedInfo, keyBlock, recordHeader, ...blocks.map((b,i) => Buffer.concat([n64(b.length), n64(segments[i].length)])), ...blocks]);
}
