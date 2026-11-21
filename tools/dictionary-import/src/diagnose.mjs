import { mkdir, open, writeFile, unlink } from 'node:fs/promises';
import { join } from 'node:path';
import { ParserAdapter, fingerprint } from './parser-adapter.mjs';
import { extract, rulesVersion } from './extract.mjs';

export const representativeWords = "book books read reads reading run runs ran running child children saw see seen went go better good best feet foot mice mouse thought think left leave lie lay lying set light well fast hard record present lead wind bank spring close open beautiful quickly the an can't mother-in-law colour".split(' ');

// This is an extraction diagnostic, never an importable bundle or an approval.
// It preserves every disposition and blocks release while any rule/source error
// remains. Files containing extracted text stay in the ignored local directory.
export async function diagnose({ mdx, out }) {
  const before = await fingerprint(mdx);
  await mkdir(out); await writeFile(join(out, '.partial'), 'diagnostic in progress\n', { flag: 'wx' });
  const dispositions = await open(join(out, 'dispositions.jsonl'), 'wx');
  const issues = await open(join(out, 'issues.jsonl'), 'wx');
  const samples = await open(join(out, 'representative-review.jsonl'), 'wx');
  const parser = new ParserAdapter(mdx); const counts = { sourceTotal: 0, sourceSucceeded: 0, sourceSkipped: 0, sourceFailed: 0, candidateEntryCount: 0, candidateSenseCount: 0 };
  const errors = {}; const skipReasons = {}; const review = new Set(); let peakRssBytes = 0;
  try {
    for await (const record of parser.enumerate()) {
      const result = extract(record); counts.sourceTotal++;
      if (result.outcome === 'FAIL') counts.sourceFailed++;
      else if (result.outcome === 'SKIP') { counts.sourceSkipped++; skipReasons[result.reason] = (skipReasons[result.reason] ?? 0) + 1; }
      else counts.sourceSucceeded++;
      counts.candidateEntryCount += result.regions.length;
      const senseCount = result.regions.reduce((sum, r) => sum + r.senses.length, 0); counts.candidateSenseCount += senseCount;
      const disposition = { sourceOrdinal: record.sourceOrdinal, indexKey: record.indexKey, sourceRecordHash: record.sourceRecordHash, outcome: result.outcome, candidateEntryCount: result.regions.length, candidateSenseCount: senseCount, issueCodes: [...new Set(result.issues.map(i => i.issueCode))] };
      await dispositions.write(JSON.stringify(disposition) + '\n');
      for (const issue of result.issues) { errors[issue.issueCode] = (errors[issue.issueCode] ?? 0) + 1; await issues.write(JSON.stringify(issue) + '\n'); }
      if (representativeWords.includes(record.indexKey.toLowerCase())) {
        review.add(record.indexKey.toLowerCase());
        await samples.write(JSON.stringify({ ...disposition, regions: result.regions, reviewStatus: 'PENDING', redirect: result.redirect ?? null }) + '\n');
      }
      peakRssBytes = Math.max(peakRssBytes, process.memoryUsage().rss);
      if (peakRssBytes > 1024 ** 3) throw new Error('RSS_BUDGET_EXCEEDED');
    }
    const after = await fingerprint(mdx);
    if (JSON.stringify(before) !== JSON.stringify(after)) throw new Error('SOURCE_CHANGED');
    if (counts.sourceTotal !== counts.sourceSucceeded + counts.sourceSkipped + counts.sourceFailed) throw new Error('DISPOSITION_COUNT_MISMATCH');
    const report = { schemaVersion: 'readict-col-extraction-diagnostic-1', diagnosticOnly: true, rulesVersion, source: before, counts, errors, skipReasons,
      representativeReview: { required: representativeWords.length, present: review.size, missing: representativeWords.filter(w => !review.has(w)), reviewed: 0 },
      approvedExclusions: [], releaseEligible: false, nextStage: 'BLOCKED_PENDING_EXTRACTION_AND_REVIEW' };
    await writeFile(join(out, 'quality-report.json'), JSON.stringify(report) + '\n', { flag: 'wx' });
    await writeFile(join(out, 'run.json'), JSON.stringify({ peakRssBytes, parserVersion: 'js-mdict@6.0.6', nodeVersion: process.version }) + '\n', { flag: 'wx' });
    await unlink(join(out, '.partial')); return report;
  } finally { parser.close(); await Promise.all([dispositions.close(), issues.close(), samples.close()]); }
}
