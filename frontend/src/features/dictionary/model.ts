import { ApiError } from '../../lib/api';

export type LookupStatus = 'FOUND' | 'AMBIGUOUS' | 'NOT_FOUND' | 'UNSUPPORTED_QUERY';

export interface LookupDataset {
  datasetId: string; datasetVersion: string; normalizationVersion: string; provider: string; policyVersion: string;
}

export interface LookupQuote { exact: string; prefix: string; suffix: string }

export interface LookupSource {
  artifactId: string; artifactHash: string; pipelineVersion: string;
  spineHref: string; blockId: string; sentenceStart: number; sentenceEnd: number;
  contextKind: 'sentence' | 'fragment'; sentence: string; surface: string;
  wordStart: number; wordEnd: number; wordRangeCfi: string; sentenceRangeCfi: string; quote: LookupQuote;
}

export interface PronunciationItem { audioId: string; accent: string; mime: string; durationMs: number }

export interface Pronunciation {
  status: string; word: string; source: string; audioDatasetId: string | null;
  audioDatasetVersion: string | null; items: PronunciationItem[];
}

export interface DictionarySense {
  senseId: string; pos: string; zh: string; labels: string[]; providerOrder: number;
}

export interface Phonetic { ipa: string; accent: string }

export interface LookupRegion {
  entryId: string; headword: string; lemma: string; lemmaKey: string;
  phonetics: Phonetic[]; senses: DictionarySense[]; pronunciations: Pronunciation | null;
  raw: Record<string, unknown>;
}

export interface LookupResult {
  lookupId: string | null; expiresAt: string | null; status: LookupStatus; queryKey: string | null;
  dataset: LookupDataset | null; resolvedLemmaKey: string | null; entry: LookupRegion | null;
  regions: LookupRegion[]; source: LookupSource; pronunciations: Pronunciation | null;
  audioError: string | null; favoriteState: null;
}

export interface LookupViewState {
  phase: 'loading' | 'ready' | 'error';
  data?: LookupResult;
  message?: string;
  code?: string;
  retryable?: boolean;
}

const STATUSES = new Set<LookupStatus>(['FOUND', 'AMBIGUOUS', 'NOT_FOUND', 'UNSUPPORTED_QUERY']);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function invalid(): never { throw new ApiError(0, 'INVALID_RESPONSE', '服务响应无法识别'); }
function object(value: unknown): value is Record<string, unknown> { return !!value && typeof value === 'object' && !Array.isArray(value); }
function text(value: unknown, max = 512): string {
  if (typeof value !== 'string' || value.length > max) invalid(); return value;
}
function nullableText(value: unknown, max = 512): string | null {
  return value === null || value === undefined ? null : text(value, max);
}
function integer(value: unknown, min = 0): number {
  if (!Number.isSafeInteger(value) || (value as number) < min) invalid(); return value as number;
}
function array(value: unknown): unknown[] { if (!Array.isArray(value)) invalid(); return value; }

function decodeDataset(value: unknown): LookupDataset {
  if (!object(value)) invalid();
  return {
    datasetId: text(value.datasetId, 64), datasetVersion: text(value.datasetVersion, 64),
    normalizationVersion: text(value.normalizationVersion, 64), provider: text(value.provider, 64),
    policyVersion: text(value.policyVersion, 64),
  };
}

function decodeSource(value: unknown): LookupSource {
  if (!object(value)) invalid();
  const quote = value.quote;
  if (!object(quote)) invalid();
  const contextKind = value.contextKind === 'fragment' ? 'fragment' : value.contextKind === 'sentence' ? 'sentence' : invalid();
  const artifactId = text(value.artifactId, 64);
  if (!uuid.test(artifactId)) invalid();
  return {
    artifactId, artifactHash: text(value.artifactHash, 64), pipelineVersion: text(value.pipelineVersion, 64),
    spineHref: text(value.spineHref, 512), blockId: text(value.blockId, 128),
    sentenceStart: integer(value.sentenceStart), sentenceEnd: integer(value.sentenceEnd),
    contextKind, sentence: text(value.sentence, 10000), surface: text(value.surface, 128),
    wordStart: integer(value.wordStart), wordEnd: integer(value.wordEnd),
    wordRangeCfi: text(value.wordRangeCfi, 4096), sentenceRangeCfi: text(value.sentenceRangeCfi, 4096),
    quote: { exact: text(quote.exact, 10000), prefix: text(quote.prefix, 128), suffix: text(quote.suffix, 128) },
  };
}

function decodePronunciation(value: unknown): Pronunciation | null {
  if (value === null || value === undefined) return null;
  if (!object(value)) invalid();
  return {
    status: text(value.status, 32), word: text(value.word, 128), source: text(value.source, 64),
    audioDatasetId: nullableText(value.audioDatasetId, 64), audioDatasetVersion: nullableText(value.audioDatasetVersion, 64),
    items: array(value.items).map(item => {
      if (!object(item)) invalid();
      const audioId = text(item.audioId, 64);
      if (!uuid.test(audioId)) invalid();
      return { audioId, accent: text(item.accent, 16), mime: text(item.mime, 64), durationMs: integer(item.durationMs) };
    }),
  };
}

function decodeRegion(value: unknown): LookupRegion {
  if (!object(value)) invalid();
  const headword = text(value.headword, 128);
  return {
    entryId: text(value.entryId, 64), headword,
    lemma: typeof value.lemma === 'string' ? text(value.lemma, 128) : headword,
    lemmaKey: typeof value.lemmaKey === 'string' ? text(value.lemmaKey, 128) : headword,
    phonetics: array(value.phonetics ?? []).map(item => {
      if (!object(item)) invalid();
      return { ipa: typeof item.ipa === 'string' ? item.ipa : '', accent: typeof item.accent === 'string' ? item.accent : '' };
    }),
    senses: array(value.senses ?? []).map(item => {
      if (!object(item)) invalid();
      return {
        senseId: typeof item.senseId === 'string' ? item.senseId : '', pos: typeof item.pos === 'string' ? item.pos : 'unknown',
        zh: text(item.zh, 10000), labels: array(item.labels ?? []).filter(label => typeof label === 'string') as string[],
        providerOrder: typeof item.providerOrder === 'number' ? item.providerOrder : 0,
      };
    }),
    pronunciations: decodePronunciation(value.pronunciations),
    raw: value,
  };
}

export function decodeLookup(value: unknown): LookupResult {
  if (!object(value) || typeof value.status !== 'string' || !STATUSES.has(value.status as LookupStatus)) invalid();
  const regions = array(value.regions ?? []).map(decodeRegion);
  const entry = value.entry === null || value.entry === undefined ? null : decodeRegion(value.entry);
  return {
    lookupId: nullableText(value.lookupId, 64), expiresAt: nullableText(value.expiresAt, 40),
    status: value.status as LookupStatus, queryKey: nullableText(value.queryKey, 128),
    dataset: value.dataset === null || value.dataset === undefined ? null : decodeDataset(value.dataset),
    resolvedLemmaKey: nullableText(value.resolvedLemmaKey, 128), entry, regions,
    source: decodeSource(value.source),
    pronunciations: decodePronunciation(value.pronunciations),
    audioError: nullableText(value.audioError, 64), favoriteState: null,
  };
}

export function decodeLookupRequest(value: unknown): LookupResult { return decodeLookup(value); }
