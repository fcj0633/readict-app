import { test } from 'node:test';
import assert from 'node:assert/strict';
import { decodeProgress, decodeProgressValue } from './api';

const progress = {
  bookId: 'b0000000-0000-4000-8000-000000000001',
  anchor: {
    artifactId: 'a0000000-0000-4000-8000-000000000001',
    artifactHash: 'a'.repeat(64), pipelineVersion: 'epub-safe-v1',
    spineHref: 'OEBPS/Text/ch1.xhtml', cfi: 'epubcfi(/6/2!/4/2/1:0)',
    blockId: 'b-1', blockOffset: 0, quote: { exact: 'abc', prefix: '', suffix: '' },
  },
  progressRatio: 0.12, version: 3, updatedAt: '2026-11-23T00:00:00Z',
};

test('decodes the wrapped GET response', () => {
  assert.equal(decodeProgress({ progress: null }).progress, null);
  const value = decodeProgress({ progress }).progress;
  assert.equal(value?.version, 3);
  assert.equal(value?.anchor.blockId, 'b-1');
});

test('decodes the direct PUT response body', () => {
  const value = decodeProgressValue(progress);
  assert.equal(value.progressRatio, 0.12);
  assert.equal(value.anchor.quote?.exact, 'abc');
});
