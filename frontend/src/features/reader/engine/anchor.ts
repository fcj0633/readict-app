import type { Quote } from './types';
import type { BlockText } from './blocks';

const WINDOW = 64;

function safeStart(text: string, index: number): number {
  let value = Math.max(0, Math.min(index, text.length));
  if (value > 0 && value < text.length) {
    const code = text.charCodeAt(value);
    if (code >= 0xdc00 && code <= 0xdfff) value -= 1;
  }
  return value;
}

function safeEnd(text: string, index: number): number {
  let value = Math.max(0, Math.min(index, text.length));
  if (value > 0 && value < text.length) {
    const code = text.charCodeAt(value - 1);
    if (code >= 0xd800 && code <= 0xdbff) value += 1;
  }
  return value;
}

/** Builds the short quote starting at the block offset, never splitting a surrogate pair. */
export function buildQuote(text: string, offset: number): Quote {
  const start = safeStart(text, offset);
  const end = safeEnd(text, Math.min(text.length, start + 128));
  const prefixStart = safeStart(text, start - WINDOW);
  const suffixEnd = safeEnd(text, end + WINDOW);
  return {
    exact: text.slice(start, end),
    prefix: text.slice(prefixStart, start),
    suffix: text.slice(end, suffixEnd),
  };
}

export interface QuoteMatch { blockIndex: number; offset: number }

/**
 * Returns the unique block offset whose text matches the quote. More than one candidate or a
 * prefix/suffix mismatch returns null: the caller must not guess.
 */
export function findQuote(blocks: BlockText[], quote: Quote): QuoteMatch | null {
  if (quote.exact.length === 0) return null;
  const matches: QuoteMatch[] = [];
  for (let index = 0; index < blocks.length; index++) {
    const text = blocks[index]!.text;
    let from = 0;
    while (from <= text.length - quote.exact.length) {
      const at = text.indexOf(quote.exact, from);
      if (at < 0) break;
      const prefixOk = quote.prefix.length === 0 || text.slice(Math.max(0, at - quote.prefix.length), at) === quote.prefix;
      const after = at + quote.exact.length;
      const suffixOk = quote.suffix.length === 0 || text.slice(after, after + quote.suffix.length) === quote.suffix;
      if (prefixOk && suffixOk) matches.push({ blockIndex: index, offset: at });
      else if (prefixOk || suffixOk) matches.push({ blockIndex: index, offset: at });
      from = at + 1;
      if (matches.length > 1) return null;
    }
  }
  return matches.length === 1 ? matches[0]! : null;
}

/** Compares the persisted quote with the text currently found at the same block offset. */
export function quoteMatches(blockText: string, blockOffset: number, quote: Quote | undefined): boolean {
  if (!quote) return true;
  if (blockOffset < 0 || blockOffset + quote.exact.length > blockText.length) return false;
  return blockText.slice(blockOffset, blockOffset + quote.exact.length) === quote.exact;
}
