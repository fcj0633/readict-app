import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { LookupRegion } from './model';
import { candidateGroups, decodeSenseGroups, groupSenses } from './sense-groups';

function region(entryId: string, sourceOrdinal: number, sectionOrder: number, senses: { id: string; zh: string; raw?: string }[]): LookupRegion {
  return {
    entryId, headword: 'accept', lemma: 'accept', lemmaKey: 'accept', sourceOrdinal, sectionOrder, componentOrder: 0,
    phonetics: [], pronunciations: null, raw: {},
    senses: senses.map((sense, providerOrder) => ({
      senseId: sense.id, pos: 'verb', rawPos: sense.raw ?? null, zh: sense.zh, labels: [], providerOrder,
    })),
  };
}

test('uses one source record and preserves native sense groups', () => {
  const regions = [
    region('a', 503, 0, [{ id: 'a1', zh: '接受;领受', raw: 'V-T' }, { id: 'a2', zh: '承认;认可', raw: 'V-T' }]),
    region('b', 510, 0, [{ id: 'b1', zh: '接受;领受', raw: 'V-T' }, { id: 'b2', zh: '承认;认可', raw: 'V-T' }]),
  ];
  assert.deepEqual(candidateGroups(regions).map(candidate => candidate.label), ['accept']);
  assert.deepEqual(groupSenses(regions, 0).map(group => group.senseId), ['a1', 'a2']);
});

test('collects multiple regions from the richest source record', () => {
  const regions = [
    region('a0', 10, 0, [{ id: 'a1', zh: 'adj', raw: 'ADJ' }]),
    region('a1', 10, 1, [{ id: 'a2', zh: 'noun', raw: 'N-SING' }]),
    region('b0', 20, 0, [{ id: 'b1', zh: 'other', raw: 'V-T' }]),
  ];
  assert.deepEqual(groupSenses(regions, 0).map(group => group.senseId), ['a1', 'a2']);
});

test('decodes backend groups and keeps nullable rawPos', () => {
  assert.deepEqual(decodeSenseGroups([{ senseId: 's', pos: 'verb', rawPos: null, zh: '做' }]),
    [{ senseId: 's', pos: 'verb', rawPos: null, zh: '做' }]);
  assert.throws(() => decodeSenseGroups([{ zh: 'missing' }]));
});
