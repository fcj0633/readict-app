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
    assert.equal(report.diagnosticOnly, true); assert.equal(report.releaseEligible, false); assert.deepEqual(report.counts, { sourceTotal: 2, sourceSucceeded: 1, sourceSkipped: 0, sourceFailed: 1, candidateEntryCount: 1, candidateSenseCount: 1, sourceAliased: 0, referenceCount: 0, derivationNoteCount: 0 });
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

// Integration through the real parser and CLI: graph failures must alter source
// dispositions, not just annotate a separate report while leaving it usable.
test('CLI graph quarantines cycles/dangling and preserves eight-hop redirect limit', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'readict-graph-'));
  try {
    const path = join(dir, 'synthetic.mdx'); const out = join(dir, 'result');
    const entry = '<div class="wt-container"><h4><span class="title">target</span></h4><div class="collinsMajorTrans"><p><span class="chn">自建释义</span></p></div></div>';
    const rows = [{key:'target',text:entry},{key:'loop',text:'@@@LINK=loop'},{key:'lost',text:'@@@LINK=absent'}];
    for(let i=0;i<9;i++) rows.push({key:'w'+i,text:'@@@LINK='+(i===8?'target':'w'+(i+1))});
    await writeFile(path,makeMdx(rows));
    const run=spawnSync(process.execPath,[cli,'diagnose','--mdx',path,'--out',out],{encoding:'utf8'});assert.equal(run.status,3,run.stderr);
    const dispositions=(await readFile(join(out,'dispositions.jsonl'),'utf8')).trim().split('\n').map(JSON.parse);
    const byKey=new Map(dispositions.map(r=>[r.indexKey,r]));
    assert.equal(byKey.get('loop').outcome,'FAIL');assert.ok(byKey.get('loop').issueCodes.includes('REFERENCE_CYCLE'));
    assert.ok(byKey.get('lost').issueCodes.includes('REFERENCE_DANGLING'));assert.ok(byKey.get('w0').issueCodes.includes('REFERENCE_HOP_LIMIT'));assert.equal(byKey.get('w1').outcome,'ALIAS');
    const report=JSON.parse(await readFile(join(out,'quality-report.json'),'utf8'));assert.equal(report.schemaVersion,'readict-col-extraction-diagnostic-2');
  } finally {await rm(dir,{recursive:true});}
});

test('CLI optional see-also preserves independent meaning and redirect availability', async () => {
 const dir=await mkdtemp(join(tmpdir(),'readict-dependencies-'));
 try {
  const path=join(dir,'fixture.mdx'),out=join(dir,'out');
  const html=(word,body)=>`<div class="wt-container"><h4><span class="title">${word}</span></h4><div class="collinsMajorTrans"><p>${body}</p></div></div>`;
  await writeFile(path,makeMdx([{key:'broken',text:html('broken','English only')},{key:'middle',text:html('middle','自建释义 → see also <a href="entry://broken">broken</a>')},{key:'outer',text:'@@@LINK=middle'}]));
  const run=spawnSync(process.execPath,[cli,'diagnose','--mdx',path,'--out',out],{encoding:'utf8'});assert.equal(run.status,3,run.stderr);
  const rows=(await readFile(join(out,'dispositions.jsonl'),'utf8')).trim().split('\n').map(JSON.parse);assert.deepEqual(rows.map(r=>r.outcome),['FAIL','ENTRY','ALIAS']);assert.ok(rows[1].issueCodes.includes('REFERENCE_TARGET_QUARANTINED'));
 } finally {await rm(dir,{recursive:true});}
});
