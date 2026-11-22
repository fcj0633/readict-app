import { mkdir, open, writeFile, unlink } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { createInterface } from 'node:readline';
import { performance } from 'node:perf_hooks';
import { join } from 'node:path';
import { ParserAdapter, fingerprint } from './parser-adapter.mjs';
import { extract, rulesVersion } from './extract.mjs';
import { literalKey, resolveReference } from './references.mjs';
export const optionalReference = ref => ['SEE_ALSO', 'COMPARE'].includes(ref.kind) && !ref.root;
export const representativeWords = "book books read reads reading run runs ran running child children saw see seen went go better good best feet foot mice mouse thought think left leave lie lay lying set light well fast hard record present lead wind bank spring close open beautiful quickly the an can't mother-in-law colour".split(' ');
function jsonLine(value) {
  const line = JSON.stringify(value) + '\n';
  if (Buffer.byteLength(line, 'utf8') > 512 * 1024) throw new Error('JSONL_LINE_BUDGET_EXCEEDED');
  return line;
}
export async function diagnose({ mdx, out }) {
  const started = performance.now(); const before = await fingerprint(mdx);
  await mkdir(out); await writeFile(join(out, '.partial'), 'diagnostic in progress\n', { flag: 'wx' });
  const stagePath = join(out, 'records.partial.jsonl'); const stage = await open(stagePath, 'wx');
  const index = new Map(); let peakRssBytes = 0; const parser = new ParserAdapter(mdx);
  const watch = () => { peakRssBytes = Math.max(peakRssBytes, process.memoryUsage().rss); if (peakRssBytes > 1024 ** 3) throw new Error('RSS_BUDGET_EXCEEDED'); };
  try {
    for await (const record of parser.enumerate()) {
      const result = extract(record);
      await stage.write(jsonLine({ sourceOrdinal: record.sourceOrdinal, indexKey: record.indexKey, sourceRecordHash: record.sourceRecordHash, result }));
      const redirects = result.outcome === 'ALIAS' ? result.references.filter(r => r.root).flatMap(r => r.targets.map(t => t.target)) : [];
      const row = { sourceOrdinal: record.sourceOrdinal, available: result.outcome === 'ENTRY', redirects, dependencies: [...result.references.filter(r => !optionalReference(r)).flatMap(r => r.targets.map(t => t.target)), ...result.notes.filter(n=>n.kind==='EXPLICIT_INFLECTION').map(n=>n.target)] };
      const keys = new Set([literalKey(record.indexKey), ...result.evidence.filter(e => e.kind === 'HEADWORD').map(e => literalKey(e.value)), ...result.regions.map(e => literalKey(e.headword))]);
      for (const key of keys) { if (!index.has(key)) index.set(key, []); index.get(key).push(row); }
      watch();
    }
  } finally { parser.close(); await stage.close(); }
  if (JSON.stringify(before) !== JSON.stringify(await fingerprint(mdx))) throw new Error('SOURCE_CHANGED');
  // An otherwise readable target may itself reference a failed source. Reach a
  // monotone fixed point before describing any dependency as available.
  const indexedRows = new Set([...index.values()].flat());
  let changed;
  do {
    changed = false;
    for (const row of indexedRows) if (row.available && row.dependencies.some(target => resolveReference(target, index).some(r => !['TARGET_PRESENT', 'OUT_OF_SCOPE_PHRASE'].includes(r.status)))) { row.available = false; changed = true; }
    watch();
  } while (changed);
  const names = ['dispositions', 'issues', 'candidates', 'quarantine', 'references', 'representative-review']; const files = new Map();
  const counts = { sourceTotal: 0, sourceSucceeded: 0, sourceSkipped: 0, sourceFailed: 0, candidateEntryCount: 0, candidateSenseCount: 0, sourceAliased: 0, referenceCount: 0, derivationNoteCount: 0 };
  const errors = Object.create(null); const skipReasons = Object.create(null); const referenceStatuses = Object.create(null); const review = new Set(); const usableReview = new Set();
  try {
    for (const name of names) files.set(name, await open(join(out, name + '.jsonl'), 'wx'));
    const lines = createInterface({ input: createReadStream(stagePath), crlfDelay: Infinity });
    for await (const line of lines) {
      const row = JSON.parse(line); const result = row.result;
      for (const ref of result.references) {
        if(ref.conflict && optionalReference(ref)) result.issues.push({sourceOrdinal:row.sourceOrdinal,indexKey:row.indexKey,region:ref.region,field:'relation',issueCode:'REFERENCE_DISPLAY_CONFLICT',severity:'WARNING',rulesVersion,sourceRecordHash:row.sourceRecordHash,sourceRange:ref.sourceRange,disposition:'PENDING_REVIEW'});
        ref.resolutions = ref.targets.map(t => ({ target: t.target, results: resolveReference(t.target, index, ref.root ? 7 : 8) }));
        for (const resolution of ref.resolutions) for (const r of resolution.results) {
          referenceStatuses[r.status] = (referenceStatuses[r.status] ?? 0) + 1;
          if (['CYCLE', 'DANGLING', 'HOP_LIMIT', 'BRANCH_BUDGET', 'TARGET_QUARANTINED'].includes(r.status)) {
            const code = 'REFERENCE_' + r.status;
            if (!result.issues.some(i => i.issueCode === code && i.region === ref.region)) result.issues.push({ sourceOrdinal: row.sourceOrdinal, indexKey: row.indexKey, region: ref.region, field: 'relation', issueCode: code, severity: optionalReference(ref) ? 'WARNING' : 'ERROR', rulesVersion, sourceRecordHash: row.sourceRecordHash, sourceRange: ref.sourceRange, disposition: 'PENDING_REVIEW' });
          }
        }
      }
      for(const note of result.notes.filter(n=>n.kind==='EXPLICIT_INFLECTION')) for(const r of resolveReference(note.target,index)) if(r.status!=='TARGET_PRESENT') {
        const code='INFLECTION_'+r.status;
        if(!result.issues.some(i=>i.issueCode===code&&i.region===note.region)) result.issues.push({sourceOrdinal:row.sourceOrdinal,indexKey:row.indexKey,region:note.region,field:'relation',issueCode:code,severity:'ERROR',rulesVersion,sourceRecordHash:row.sourceRecordHash,sourceRange:note.sourceRange,disposition:'PENDING_REVIEW'});
      }
      if (result.issues.some(i => i.severity === 'ERROR') && result.outcome !== 'FAIL') { result.quarantinedRegions = result.regions; result.regions = []; result.outcome = 'FAIL'; }
      counts.sourceTotal++;
      if (result.outcome === 'FAIL') counts.sourceFailed++;
      else if (result.outcome === 'SKIP') { counts.sourceSkipped++; skipReasons[result.reason] = (skipReasons[result.reason] ?? 0) + 1; }
      else { counts.sourceSucceeded++; if (result.outcome === 'ALIAS') counts.sourceAliased++; }
      counts.candidateEntryCount += result.regions.length;
      counts.candidateSenseCount += result.regions.reduce((sum, r) => sum + r.senses.length, 0);
      counts.referenceCount += result.references.length;
      counts.derivationNoteCount += result.notes.filter(n => n.kind === 'DERIVATION_NOTE').length;
      const disposition = { sourceOrdinal: row.sourceOrdinal, indexKey: row.indexKey, sourceRecordHash: row.sourceRecordHash, outcome: result.outcome, reason: result.reason ?? null, candidateEntryCount: result.regions.length, candidateSenseCount: result.regions.reduce((sum, r) => sum + r.senses.length, 0), issueCodes: [...new Set(result.issues.map(i => i.issueCode))] };
      await files.get('dispositions').write(jsonLine(disposition));
      for (const issue of result.issues) { errors[issue.issueCode] = (errors[issue.issueCode] ?? 0) + 1; await files.get('issues').write(jsonLine(issue)); }
      for (const entry of result.regions) await files.get('candidates').write(jsonLine({ sourceOrdinal: row.sourceOrdinal, sourceRecordHash: row.sourceRecordHash, indexKey: row.indexKey, notes: result.notes.filter(n => n.region === entry.sectionOrder), references: result.references.filter(r => r.region === entry.sectionOrder && (r.componentOrder ?? 0) === entry.componentOrder), ...entry }));
      if (result.outcome === 'FAIL') await files.get('quarantine').write(jsonLine({ ...disposition, regions: result.quarantinedRegions, evidence: result.evidence, notes: result.notes }));
      for (const ref of result.references) await files.get('references').write(jsonLine({ sourceOrdinal: row.sourceOrdinal, ...ref }));
      const observedWords = [row.indexKey, ...result.regions.map(r => r.headword), ...result.quarantinedRegions.map(r => r.headword)];
      if (observedWords.some(w => representativeWords.includes(w.toLowerCase()))) {
        for (const word of observedWords) if (representativeWords.includes(word.toLowerCase())) review.add(word.toLowerCase());
        for (const word of result.regions.map(r => r.headword)) if (representativeWords.includes(word.toLowerCase())) usableReview.add(word.toLowerCase());
        await files.get('representative-review').write(jsonLine({ ...disposition, regions: result.regions, quarantinedRegions: result.quarantinedRegions, notes: result.notes, references: result.references, reviewStatus: 'PENDING' }));
      }
      watch();
    }
  } finally { await Promise.all([...files.values()].map(f => f.close())); }
  if (counts.sourceTotal !== counts.sourceSucceeded + counts.sourceSkipped + counts.sourceFailed) throw new Error('DISPOSITION_COUNT_MISMATCH');
  const report = { schemaVersion: 'readict-col-extraction-diagnostic-2', diagnosticOnly: true, rulesVersion, source: before, counts, errors, skipReasons, referenceStatuses,
    representativeReview: { required: representativeWords.length, present: review.size, missing: representativeWords.filter(w => !review.has(w)), usableExactHeadwords: [...usableReview].sort(), coverageMeaning: 'present includes index keys and quarantined subentries; usableExactHeadwords is separate; aliases need reference review', reviewed: 0 },
    approvedExclusions: [], releaseEligible: false, nextStage: 'BLOCKED_PENDING_QUALITY_AND_SOURCE_REVIEW' };
  await writeFile(join(out, 'quality-report.json'), jsonLine(report), { flag: 'wx' });
  await writeFile(join(out, 'run.json'), jsonLine({ peakRssBytes, durationMs: Math.round(performance.now() - started), parserVersion: 'js-mdict@6.0.6', nodeVersion: process.version }), { flag: 'wx' });
  await unlink(stagePath); await unlink(join(out, '.partial')); return report;
}
