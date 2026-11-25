import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError } from '../../lib/api';
import { decodeLookup } from './model';

const source = {
  artifactId: 'a0000000-0000-4000-8000-000000000001',
  artifactHash: 'a'.repeat(64), pipelineVersion: 'epub-safe-v1',
  spineHref: 'OEBPS/Text/ch1.xhtml', blockId: 'b-1',
  sentenceStart: 0, sentenceEnd: 19, contextKind: 'sentence',
  sentence: 'The children are running.', surface: 'children',
  wordStart: 4, wordEnd: 12,
  wordRangeCfi: 'epubcfi(/6/2!/4/2/1:4)', sentenceRangeCfi: 'epubcfi(/6/2!/4/2/1:0)',
  quote: { exact: 'The children are running.', prefix: '', suffix: '' },
};

const region = {
  entryId: 'e0000000-0000-4000-8000-000000000001', headword: 'children', lemma: 'children', lemmaKey: 'children',
  phonetics: [{ ipa: '/ˈtʃɪldrən/', accent: 'unknown' }],
  senses: [{ senseId: 's0000000-0000-4000-8000-000000000001', pos: 'noun', zh: '孩子们', labels: [], providerOrder: 0 }],
  pronunciations: null,
};

test('decodes a FOUND lookup and its canonical source', () => {
  const value = decodeLookup({
    lookupId: 'l0000000-0000-4000-8000-000000000001', expiresAt: '2026-11-25T00:00:00Z',
    status: 'FOUND', queryKey: 'children',
    dataset: { datasetId: 'd0000000-0000-4000-8000-000000000001', datasetVersion: 'v1', normalizationVersion: 'col-normalize-v1', provider: 'local-col', policyVersion: 'unverified' },
    resolvedLemmaKey: 'children', entry: region, regions: [region], source,
    pronunciations: { status: 'available', word: 'children', source: 'local-sound', audioDatasetId: null, audioDatasetVersion: null, items: [] },
    audioError: null, favoriteState: null,
  });
  assert.equal(value.status, 'FOUND');
  assert.equal(value.entry?.senses[0]?.zh, '孩子们');
  assert.equal(value.source.wordStart, 4);
});

test('decodes NOT_FOUND without inventing an entry', () => {
  const value = decodeLookup({
    status: 'NOT_FOUND', queryKey: 'zzzz', dataset: null, entry: null, regions: [], source,
    pronunciations: null, audioError: null, favoriteState: null,
  });
  assert.equal(value.entry, null);
  assert.deepEqual(value.regions, []);
});

test('rejects unknown status and malformed source', () => {
  assert.throws(() => decodeLookup({ status: 'MAYBE', source, regions: [] }), ApiError);
  assert.throws(() => decodeLookup({ status: 'FOUND', source: { ...source, artifactId: 'bad' }, regions: [region], entry: region }), ApiError);
});
