export interface SelectionRect {
  left: number; top: number; right: number; bottom: number; width: number; height: number;
}

export interface SelectionQuote { exact: string; prefix: string; suffix: string }

export interface SelectionSource {
  artifactId: string;
  spineHref: string;
  blockId: string;
  sentenceStart: number;
  sentenceEnd: number;
  sentence: string;
  surface: string;
  /** UTF-16 offsets within sentence; sentenceStart/End remain relative to the block. */
  wordStart: number;
  wordEnd: number;
  wordRangeCfi: string;
  sentenceRangeCfi: string;
  quote: SelectionQuote;
}

export interface TextSelection {
  source: SelectionSource;
  rect: SelectionRect;
  contextKind: 'sentence' | 'fragment';
}

const WORD_CHAR = /[A-Za-z'’-]/;

export interface WordSpan { start: number; end: number; surface: string }

/** Finds the ASCII English token under a block offset; punctuation-only and numeric tokens are rejected. */
export function wordAt(text: string, offset: number): WordSpan | null {
  if (offset < 0 || offset > text.length) return null;
  let at = Math.min(offset, Math.max(0, text.length - 1));
  if (!WORD_CHAR.test(text.charAt(at)) && at > 0 && WORD_CHAR.test(text.charAt(at - 1))) at--;
  if (!WORD_CHAR.test(text.charAt(at))) return null;
  let start = at;
  let end = at + 1;
  while (start > 0 && WORD_CHAR.test(text.charAt(start - 1))) start--;
  while (end < text.length && WORD_CHAR.test(text.charAt(end))) end++;
  while (start < end && (text.charAt(start) === '-' || text.charAt(start) === "'" || text.charAt(start) === '’')) start++;
  while (end > start && (text.charAt(end - 1) === '-' || text.charAt(end - 1) === "'" || text.charAt(end - 1) === '’')) end--;
  if (start >= end) return null;
  const surface = text.slice(start, end);
  return /[A-Za-z]/.test(surface) ? { start, end, surface } : null;
}

export interface SentenceRange { start: number; end: number; kind: 'SENTENCE' | 'FRAGMENT' }

/** Lookup word offsets are relative to the selected sentence, unlike indexed block offsets. */
export function sentenceWordRange(word: WordSpan, sentence: SentenceRange): { wordStart: number; wordEnd: number } {
  return { wordStart: word.start - sentence.start, wordEnd: word.end - sentence.start };
}

/** Finds the indexed sentence/fragment that fully contains the token; never invents a browser-side split. */
export function sentenceAt(sentences: SentenceRange[], wordStart: number, wordEnd: number): SentenceRange | null {
  for (const sentence of sentences) {
    if (sentence.start <= wordStart && sentence.end >= wordEnd) return sentence;
  }
  return null;
}

/** Canonical quote prefixes used by the M05 source contract; never splits a surrogate pair. */
export function quoteParts(text: string, sentenceStart: number, sentenceEnd: number): SelectionQuote {
  let prefixStart = Math.max(0, sentenceStart - 128);
  if (prefixStart > 0 && prefixStart < text.length && isLowSurrogate(text.charCodeAt(prefixStart))) prefixStart--;
  let suffixEnd = Math.min(text.length, sentenceEnd + 128);
  if (suffixEnd > 0 && suffixEnd < text.length && isHighSurrogate(text.charCodeAt(suffixEnd - 1))) suffixEnd++;
  return {
    exact: text.slice(sentenceStart, sentenceEnd),
    prefix: text.slice(prefixStart, sentenceStart),
    suffix: text.slice(sentenceEnd, suffixEnd),
  };
}

export function isHighSurrogate(code: number): boolean { return code >= 0xd800 && code <= 0xdbff; }
export function isLowSurrogate(code: number): boolean { return code >= 0xdc00 && code <= 0xdfff; }
