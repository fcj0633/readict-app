import { parseArgs } from 'node:util';
import { performance } from 'node:perf_hooks';
import { pathToFileURL } from 'node:url';
import { diagnose } from './diagnose.mjs';
import { fingerprint, ParserAdapter, parserVersion, sha256 } from './parser-adapter.mjs';

export async function inspect(values) {
  const started = performance.now(); const result = { parserVersion, nodeVersion: process.version, rights: { status: 'UNVERIFIED', snapshotAllowed: false, distributionAllowed: false }, sources: {} };
  for (const kind of ['mdx', 'mdd']) {
    if (!values[kind]) continue;
    const before = await fingerprint(values[kind]); const parser = new ParserAdapter(values[kind], kind === 'mdd');
    let count = 0, maxRecordBytes = 0, duplicates = 0, crossBlockRecords = 0; const seen = new Map(); const resources = []; const duplicateEvidence = [];
    const recordHashes = [];
    try {
      for await (const record of parser.enumerate()) {
        if (seen.has(record.indexKey)) { duplicates++; const first = seen.get(record.indexKey); duplicateEvidence.push({ firstOrdinal: first.sourceOrdinal, duplicateOrdinal: record.sourceOrdinal, firstOffset: first.recordOffset, duplicateOffset: record.recordOffset, firstHash: first.sourceRecordHash, duplicateHash: record.sourceRecordHash }); } else seen.set(record.indexKey, { sourceOrdinal: record.sourceOrdinal, recordOffset: record.recordOffset, sourceRecordHash: record.sourceRecordHash });
        maxRecordBytes = Math.max(maxRecordBytes, record.byteLength);
        if (kind === 'mdd') resources.push(record.indexKey);
        const key = parser.keys[record.sourceOrdinal];
        if (parser.dictionary.recordInfoList.some(b => b.unpackAccumulatorOffset > key.recordStartOffset && b.unpackAccumulatorOffset < key.recordEndOffset)) crossBlockRecords++;
        recordHashes.push(record.sourceRecordHash);
        count++;
      }
    } finally { parser.close(); }
    const after = await fingerprint(values[kind]);
    if (JSON.stringify(before) !== JSON.stringify(after)) throw new Error('SOURCE_CHANGED');
    result.sources[kind] = { ...before, sourceTotal: count, duplicates, duplicateEvidence, crossBlockRecords, maxRecordBytes, decodedRecordHashesSha256: sha256(recordHashes.join('\n')), ...(kind === 'mdd' ? { resources } : {}) };
  }
  result.sourceHash = sha256(JSON.stringify(['readict-col-source-1', result.sources.mdx.sha256, result.sources.mdd?.sha256 ?? null]));
  result.durationMs = Math.round(performance.now() - started); result.peakRssBytes = process.resourceUsage().maxRSS * 1024;
  return result;
}
export async function main(args = process.argv.slice(2)) {
try {
  const { values, positionals } = parseArgs({ args, options: { mdx: { type: 'string' }, mdd: { type: 'string' }, out: { type: 'string' } }, allowPositionals: true, strict: true });
  if (positionals.length !== 1 || !['inspect', 'diagnose'].includes(positionals[0]) || !values.mdx || positionals[0] === 'diagnose' && (!values.out || values.mdd) || positionals[0] === 'inspect' && values.out) throw new Error('USAGE: inspect --mdx <file> [--mdd <file>] | diagnose --mdx <file> --out <new-directory>');
  const result = positionals[0] === 'inspect' ? await inspect(values) : await diagnose(values);
  console.log(JSON.stringify(result, null, 2));
  if (positionals[0] === 'diagnose') process.exitCode = 3;
} catch (error) { console.error(JSON.stringify({ code: error.message.startsWith('USAGE:') ? 'INPUT_INVALID' : 'CONVERSION_FAILED', message: error.message })); process.exitCode = 2; }

}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
