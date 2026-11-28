import type { LookupRegion } from './model';

export interface SenseGroup {
  senseId: string;
  pos: string;
  rawPos: string | null;
  zh: string;
}

export interface Candidate {
  key: string;
  label: string;
  index: number;
}

function label(region: LookupRegion): string {
  return region.lemma || region.headword;
}

/** One candidate per real dictionary lemma; duplicate source records are not separate candidates. */
export function candidateGroups(regions: LookupRegion[]): Candidate[] {
  const seen = new Set<string>();
  const result: Candidate[] = [];
  regions.forEach((region, index) => {
    if (seen.has(region.lemmaKey)) return;
    seen.add(region.lemmaKey);
    result.push({ key: region.lemmaKey, label: label(region), index });
  });
  return result;
}

export function activeLemma(regions: LookupRegion[], activeIndex: number): LookupRegion | undefined {
  return regions[activeIndex] ?? regions[0];
}

/**
 * The source dictionary contains duplicate source records for some headwords. Pick one source
 * record for a lemma: the one containing the most senses, with the lowest sourceOrdinal as a
 * deterministic tie-breaker. Then keep every region in that record, in dictionary order.
 */
export function groupSenses(regions: LookupRegion[], activeIndex: number): SenseGroup[] {
  const active = activeLemma(regions, activeIndex);
  if (!active) return [];
  const sameLemma = regions.filter(region => region.lemmaKey === active.lemmaKey);
  const bySource = new Map<number, LookupRegion[]>();
  for (const region of sameLemma) {
    const list = bySource.get(region.sourceOrdinal) ?? [];
    list.push(region);
    bySource.set(region.sourceOrdinal, list);
  }
  let selected: LookupRegion[] = [];
  let selectedSenses = -1;
  let selectedOrdinal = Number.POSITIVE_INFINITY;
  for (const [ordinal, sourceRegions] of bySource) {
    const senses = sourceRegions.reduce((sum, region) => sum + region.senses.length, 0);
    if (senses > selectedSenses || (senses === selectedSenses && ordinal < selectedOrdinal)) {
      selected = sourceRegions;
      selectedSenses = senses;
      selectedOrdinal = ordinal;
    }
  }
  const seen = new Set<string>();
  const groups: SenseGroup[] = [];
  selected
    .slice()
    .sort((a, b) => a.sectionOrder - b.sectionOrder || a.componentOrder - b.componentOrder)
    .forEach(region => {
      region.senses
        .slice()
        .sort((a, b) => a.providerOrder - b.providerOrder)
        .forEach(sense => {
          if (!sense.senseId || seen.has(sense.senseId)) return;
          seen.add(sense.senseId);
          groups.push({ senseId: sense.senseId, pos: sense.pos, rawPos: sense.rawPos, zh: sense.zh });
        });
    });
  return groups;
}

/** Groups saved by the backend already have the source-record selection applied. */
export function decodeSenseGroups(value: unknown): SenseGroup[] {
  if (!Array.isArray(value)) throw new Error('INVALID_SENSE_GROUPS');
  return value.map(item => {
    if (!item || typeof item !== 'object') throw new Error('INVALID_SENSE_GROUPS');
    const group = item as Record<string, unknown>;
    if (typeof group.senseId !== 'string' || typeof group.zh !== 'string') throw new Error('INVALID_SENSE_GROUPS');
    return {
      senseId: group.senseId,
      pos: typeof group.pos === 'string' ? group.pos : 'unknown',
      rawPos: typeof group.rawPos === 'string' && group.rawPos ? group.rawPos : null,
      zh: group.zh,
    };
  });
}
