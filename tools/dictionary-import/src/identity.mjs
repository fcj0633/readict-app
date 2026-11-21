import { createHash } from 'node:crypto';
export function uuid8(tuple) {
  const hash = createHash('sha256').update(JSON.stringify(tuple), 'utf8').digest();
  const bytes = Buffer.from(hash.subarray(0, 16)); bytes[6] = (bytes[6] & 15) | 128; bytes[8] = (bytes[8] & 63) | 128;
  const hex = bytes.toString('hex');
  return { id: `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`, fullHash: hash.toString('hex') };
}
export const entryIdentity = (datasetId, sourceOrdinal, sectionOrder, componentOrder, indexKey) => uuid8(['entry-v2', datasetId, sourceOrdinal, sectionOrder, componentOrder, indexKey]);
export const entryKey = (sourceOrdinal, sectionOrder, componentOrder) => `s${sourceOrdinal}:r${sectionOrder}:c${componentOrder}`;
