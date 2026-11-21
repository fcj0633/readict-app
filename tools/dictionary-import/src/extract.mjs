import { parse } from 'parse5';
import { normalize } from './normalize.mjs';

export const rulesVersion = 'col-extract-r2';
const banned = new Set(['script', 'style', 'iframe', 'object', 'embed', 'link', 'img', 'audio', 'video']);
const classes = n => (n.attrs?.find(a => a.name === 'class')?.value ?? '').split(/\s+/);
const has = (n, name) => classes(n).includes(name);
const compact = s => s.replace(/\s+/gu, ' ').trim();
export function nodes(root, predicate) {
  const found = [];
  function visit(n) { if (predicate(n)) found.push(n); for (const c of n.childNodes ?? []) visit(c); }
  visit(root); return found;
}
export function text(n) { return n.nodeName === '#text' ? n.value : (n.childNodes ?? []).map(text).join(''); }
function sanitize(n) {
  n.childNodes = (n.childNodes ?? []).filter(c => !banned.has(c.tagName) && !has(c, 'exampleLists'));
  n.attrs = (n.attrs ?? []).filter(a => !a.name.startsWith('on') && !['href', 'src', 'srcset', 'style'].includes(a.name));
  for (const child of n.childNodes) sanitize(child);
}
const posOf = raw => /^N(?:-|$)/.test(raw) ? 'noun' : /^V(?:-|$)/.test(raw) ? 'verb' : /^ADJ(?:-|$)/.test(raw) ? 'adjective' : /^ADV(?:-|$)/.test(raw) ? 'adverb' : ({ PRON: 'pronoun', PREP: 'preposition', CONJ: 'conjunction', DET: 'determiner', EXCLAM: 'interjection', INTERJ: 'interjection' })[raw] ?? 'unknown';

// Recognize the COL complete-English-sentence separator as a distinct layout.
// Keep the entire translation suffix (including leading parentheses/forms).
// Definitions without this explicit separator remain quarantined.
export function extract(source) {
  const issues = [];
  const issue = (issueCode, region = null, field = null) => issues.push({ sourceOrdinal: source.sourceOrdinal, indexKey: source.indexKey, region, field, issueCode, severity: 'ERROR', rulesVersion, sourceRecordHash: source.sourceRecordHash, disposition: 'PENDING_REVIEW' });
  const raw = source.rawDecodedText;
  if (!raw.trim()) return { outcome: 'SKIP', reason: 'EMPTY_RECORD', regions: [], issues };
  if (/^@@@LINK=/u.test(raw.trim())) return { outcome: 'ALIAS', redirect: raw.trim().slice(8).trim(), regions: [], issues };
  if (/\s/u.test(source.indexKey.trim())) return { outcome: 'SKIP', reason: 'OUT_OF_SCOPE_PHRASE', regions: [], issues };
  const doc = parse(raw); sanitize(doc);
  const roots = nodes(doc, n => has(n, 'wt-container'));
  if (!roots.length) { issue('REGION_STRUCTURE_UNKNOWN'); return { outcome: 'FAIL', regions: [], issues }; }
  const regions = [];
  for (const [region, root] of roots.entries()) {
    const titles = nodes(root, n => n.tagName === 'span' && n.parentNode?.tagName === 'h4' && has(n, 'title')); const headword = compact(titles[0] ? text(titles[0]) : '');
    if (titles.length !== 1 || !headword) { issue('HEADWORD_MISSING', region, 'headword'); continue; }
    let lemmaKey;
    try { lemmaKey = normalize(headword); } catch { issue('HEADWORD_UNSUPPORTED', region, 'headword'); continue; }
    const phonetics = nodes(root, n => has(n, 'phonetic')).map(n => ({ accent: 'unknown', ipa: compact(text(n)), audioId: null }));
    const senses = []; const majors = nodes(root, n => has(n, 'collinsMajorTrans'));
    if (!majors.length) issue('SENSE_MISSING', region, 'senses');
    for (const major of majors) {
      const p = nodes(major, n => n.tagName === 'p')[0];
      if (!p) { issue('MEANING_BOUNDARY_UNKNOWN', region, 'zh'); continue; }
      const rawText = compact(text(p));
      if (/^→\s*see(?: also)?\b/iu.test(rawText) && !/\p{Script=Han}/u.test(rawText)) continue;
      if (/\b(?:plural|past tense|past participle|comparative|superlative) of\s*\./iu.test(rawText)) { issue('SOURCE_TEXT_DEFECT', region, 'relation'); continue; }
      const posNode = nodes(p, n => n.tagName === 'span' && n.attrs?.some(a => a.name === 'title'))[0];
      const rawPos = posNode ? compact(text(posNode)) : null;
      // Supported layout: an explicit translation node, or a Chinese-only
      // paragraph after metadata. Other layouts await a source-specific rule.
      const marked = nodes(p, n => has(n, 'chn') || has(n, 'translation'));
      const bodyNodes = (p.childNodes ?? []).filter(n => n !== posNode && !has(n, 'additional'));
      const body = compact(bodyNodes.map(text).join(''));
      let zh = marked.length === 1 ? compact(text(marked[0])) : null;
      if (!zh && /\p{Script=Han}/u.test(body) && !/[A-Za-z]/u.test(body)) zh = body;
      if (!zh) {
        const separators = [...body.matchAll(/[.!?]\s+/gu)];
        for (const separator of separators) {
          const suffix = body.slice(separator.index + separator[0].length);
          if (/^(?:[（(][^）)]{1,80}[）)]\s*)?[\p{Script=Han}“‘《…]/u.test(suffix) && /\p{Script=Han}/u.test(suffix)) { zh = suffix; break; }
        }
      }
      if (!zh || !/\p{Script=Han}/u.test(zh)) { issue('MEANING_BOUNDARY_UNKNOWN', region, 'zh'); continue; }
      if ([...zh].length > 10000) { issue('MEANING_TOO_LONG', region, 'zh'); continue; }
      const label = nodes(major, n => has(n, 'collinsOrder'))[0];
      senses.push({ pos: posOf(rawPos ?? ''), rawPos, zh, providerOrder: senses.length, sourceLabel: label ? compact(text(label)) : null });
    }
    if (!senses.length && !issues.some(i => i.region === region)) issue('SENSE_MISSING', region, 'senses');
    regions.push({ headword, lemmaKey, sectionOrder: region, phonetics, senses });
  }
  return { outcome: issues.length ? 'FAIL' : 'ENTRY', regions: issues.length ? [] : regions, issues };
}
