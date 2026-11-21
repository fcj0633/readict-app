import { attr, compact, nodes, range, text } from './evidence.mjs';
export const literalKey = s => s.normalize('NFKC').toLowerCase();
export function entryTarget(href) {
  if (typeof href !== 'string' || !href.startsWith('entry://')) return null;
  try {
    const target = decodeURIComponent(href.slice(8).split('#', 1)[0]);
    return target && !/[\p{Cc}\p{Cf}]/u.test(target) ? target : null;
  } catch { return null; }
}
export function reference(node, body = compact(text(node))) {
  const links = nodes(node, n => n.tagName === 'a' && entryTarget(attr(n, 'href')) !== null);
  // An ordinary bilingual definition may begin "another name for ...".
  // Without an arrow/href it must keep its translation, not become an alias.
  if (!body.includes('→') && !links.length && /\p{Script=Han}/u.test(body)) return null;
  let marker = body.search(/(?:→\s*|^)(?:see(?: also)?\b|compare\b|same as\b|another (?:spelling of|name for)\b|a (?:less common term for|variant spelling of)\b|an obsolete word for\b)/iu);
  // A source arrow plus an entry href proves a cross-reference even if its
  // editorial wording is unfamiliar. It does NOT prove an inflection/lemma.
  if (marker < 0 && links.length) marker = body.indexOf('→');
  if (marker < 0) return null;
  const fragment = body.slice(marker).replace(/^→\s*/u, '');
  const match = fragment.match(/^(see(?: also)?|compare|same as|another spelling of|another name for|a less common term for|a variant spelling of|an obsolete word for)\s+(.+)$/iu);
  if (!match && !links.length) return null;
  const targets = links.map(n => ({ target: entryTarget(attr(n, 'href')), display: compact(text(n)), sourceRange: range(n), evidenceType: 'ENTRY_HREF' }));
  const kind = !match ? 'SOURCE_REFERENCE' : /^compare$/iu.test(match[1]) ? 'COMPARE' : /^see also$/iu.test(match[1]) ? 'SEE_ALSO' : /spelling|name for|common term|obsolete word/iu.test(match[1]) ? 'VARIANT' : /^same/iu.test(match[1]) ? 'SAME_AS' : 'SEE';
  // Text fallback deliberately retains punctuation. A target with terminal dot
  // cannot be silently rewritten to a different word.
  if (!targets.length) targets.push({ target: match[2].trim(), display: match[2].trim(), sourceRange: range(node), evidenceType: 'EXPLICIT_TEXT' });
  const conflict = targets.some(t => t.evidenceType === 'ENTRY_HREF' && literalKey(t.target) !== literalKey(t.display));
  return { kind, targets, conflict, sourceRange: range(node), ruleId: 'COL_EXPLICIT_REFERENCE', marker };
}
// Each key retains ALL physical records. Traversal is per branch, never a
// dictionary object that overwrites homographs or duplicate source keys.
export function resolveReference(target, index, maxHops = 8) {
  const results = []; let visits = 0;
  function walk(word, path, hops) {
    if (++visits > 1024) { if (!results.some(r => r.status === 'BRANCH_BUDGET')) results.push({ status: 'BRANCH_BUDGET', path }); return; }
    const key = literalKey(word);
    if (path.includes(key)) { results.push({ status: 'CYCLE', path: [...path, key] }); return; }
    if (/\s/u.test(word)) { results.push({ status: 'OUT_OF_SCOPE_PHRASE', path: [...path, key] }); return; }
    const rows = index.get(key);
    if (!rows?.length) { results.push({ status: 'DANGLING', path: [...path, key] }); return; }
    for (const row of rows) {
      if (!row.redirects?.length) results.push({ status: row.available ? 'TARGET_PRESENT' : 'TARGET_QUARANTINED', sourceOrdinal: row.sourceOrdinal, path: [...path, key] });
      else if (hops >= maxHops) results.push({ status: 'HOP_LIMIT', path: [...path, key] });
      else for (const next of row.redirects) walk(next, [...path, key], hops + 1);
    }
  }
  walk(target, [], 0); return results;
}
