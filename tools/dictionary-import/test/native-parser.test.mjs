import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { makeMdx } from './mdx-fixture.mjs';
import { ParserAdapter } from '../src/parser-adapter.mjs';
const cli = fileURLToPath(new URL('../src/cli.mjs', import.meta.url));

test('actual 6.0.6 parses self-authored duplicate keys and a UTF-8 record spanning blocks', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'readict-mdx-'));
  try {
    const path = join(dir, 'synthetic.mdx'); await writeFile(path, makeMdx([{ key: 'z', text: '甲' }, { key: 'same', text: '乙丙' }, { key: 'same', text: '丁' }], 4));
    const parser = new ParserAdapter(path); const found = [];
    try { for await (const r of parser.enumerate()) found.push([r.sourceOrdinal, r.indexKey, r.rawDecodedText]); } finally { parser.close(); }
    assert.deepEqual(found, [[0, 'z', '甲'], [1, 'same', '乙丙'], [2, 'same', '丁']]);
  } finally { await rm(dir, { recursive: true }); }
});
test('CLI diagnostic counts every record, returns quality exit 3, never emits an import manifest and refuses overwrite', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'readict-diagnostic-'));
  try {
    const path = join(dir, 'synthetic.mdx'); const out = join(dir, 'result');
    await writeFile(path, makeMdx([{ key: 'book', text: '<div class="wt-container"><h4><span class="title">book</span></h4><div class="collinsMajorTrans"><p><span class="chn">自建释义</span></p></div></div>' }, { key: 'missing', text: '<p>unrecognized</p>' }]));
    const run = spawnSync(process.execPath, [cli, 'diagnose', '--mdx', path, '--out', out], { encoding: 'utf8' });
    assert.equal(run.status, 3, run.stderr); const report = JSON.parse(await readFile(join(out, 'quality-report.json'), 'utf8'));
    assert.equal(report.diagnosticOnly, true); assert.equal(report.releaseEligible, false); assert.deepEqual(report.counts, { sourceTotal: 2, sourceSucceeded: 1, sourceSkipped: 0, sourceFailed: 1, candidateEntryCount: 1, candidateSenseCount: 1 });
    await assert.rejects(readFile(join(out, 'manifest.json'))); await assert.rejects(readFile(join(out, '.partial')));
    const again = spawnSync(process.execPath, [cli, 'diagnose', '--mdx', path, '--out', out], { encoding: 'utf8' }); assert.equal(again.status, 2);
    assert.equal(JSON.parse(await readFile(join(out, 'quality-report.json'), 'utf8')).counts.sourceTotal, 2);
  } finally { await rm(dir, { recursive: true }); }
});
test('CLI inspect validates required flags and rejects unknown arguments before reading source', () => {
  for (const args of [[], ['inspect'], ['inspect', '--mdx', 'unused', '--unknown', 'x'], ['inspect', '--mdx', 'unused', '--out', 'unused']]) {
    const run = spawnSync(process.execPath, [cli, ...args], { encoding: 'utf8' }); assert.equal(run.status, 2); assert.equal(run.stdout, '');
  }
});
