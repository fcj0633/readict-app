import { parse } from 'parse5';
import { attr, compact, has, nodes, range, sourceHead, text } from './evidence.mjs';
import { segment } from './segment.mjs';
import { entryKey } from './identity.mjs';
import { reference } from './references.mjs';
export { nodes, text } from './evidence.mjs';
export const rulesVersion = 'col-extract-r4';
export const posOf = raw => /^N(?:-|$)/u.test(raw) ? 'noun' : /^V(?:-|$)/u.test(raw) ? 'verb' : /^ADJ(?:-|$)/u.test(raw) ? 'adjective' : /^ADV(?:-|$)/u.test(raw) ? 'adverb' : ({ PRON: 'pronoun', PREP: 'preposition', CONJ: 'conjunction', DET: 'determiner', EXCLAM: 'interjection', INTERJ: 'interjection' })[raw] ?? 'unknown';
const typedPos = n => n.tagName === 'span' && attr(n, 'title') && !has(n, 'phonetic');
function labelKind(value) {
  if (/英|美|BRIT|AM|US|UK/u.test(value)) return 'REGION';
  if (/正式|口语|非正式|formal|slang/iu.test(value)) return 'REGISTER';
  if (/^(?:N|V|ADJ|ADV)(?:-|$)/u.test(value)) return 'SUPPLEMENTARY_POS';
  if (/after|before|with|通常|常用|搭配/iu.test(value)) return 'GRAMMAR';
  return 'UNCLASSIFIED';
}
export function extract(source) {
  const issues = []; const regions = []; const references = []; const notes = []; const evidence = [];
  const issue = (issueCode, region = null, field = null, sourceRange = null) => issues.push({ sourceOrdinal: source.sourceOrdinal, indexKey: source.indexKey, region, field, issueCode, severity: 'ERROR', rulesVersion, sourceRecordHash: source.sourceRecordHash, sourceRange, disposition: 'PENDING_REVIEW' });
  const finish = (outcome, extra = {}) => ({ outcome, regions: outcome === 'FAIL' ? [] : regions, quarantinedRegions: outcome === 'FAIL' ? regions : [], references, notes, evidence, issues, ...extra });
  const raw = source.rawDecodedText.replace(/\0+$/u, '');
  if (Buffer.byteLength(source.rawDecodedText, 'utf8') > 2 * 1024 ** 2) { issue('RECORD_TOO_LARGE'); return finish('FAIL'); }
  if (!raw.trim() || raw.trim() === 'None') { issue('SOURCE_EMPTY_RECORD'); return finish('FAIL'); }
  if (/^@@@LINK=/u.test(raw.trim())) {
    const redirect = raw.trim().slice(8).trim();
    if (!redirect || /[\p{Cc}\p{Cf}]/u.test(redirect)) { issue('REFERENCE_TARGET_INVALID'); return finish('FAIL'); }
    references.push({ kind: 'REDIRECT', targets: [{ target: redirect, display: redirect, sourceRange: null, evidenceType: 'MDX_LINK' }], conflict: false, region: null, root: true, ruleId: 'MDX_REDIRECT', sourceRange: null });
    return finish('ALIAS', { redirect });
  }
  const parseErrors = [];
  const doc = parse(raw, { sourceCodeLocationInfo: true, onParseError: e => { if (!['missing-doctype', 'unexpected-null-character'].includes(e.code)) parseErrors.push({ code: e.code, startOffset: e.startOffset }); } });
  const roots = nodes(doc, n => has(n, 'wt-container'));
  if (!roots.length) {
    if (/\s/u.test(source.indexKey.trim())) return finish('SKIP', { reason: 'OUT_OF_SCOPE_PHRASE', parseErrors });
    issue('REGION_STRUCTURE_UNKNOWN'); return finish('FAIL', { parseErrors });
  }
  let outOfScope = 0;
  for (const [region, root] of roots.entries()) {
    const titles = nodes(root, n => n.tagName === 'span' && n.parentNode?.tagName === 'h4' && has(n, 'title'));
    const headword = compact(titles[0] ? text(titles[0]) : '');
    if (titles.length !== 1 || !headword) { issue('HEADWORD_MISSING', region, 'headword', range(root)); continue; }
    const classification = sourceHead(headword);
    evidence.push({ kind: 'HEADWORD', region, value: headword, classification: classification.type, sourceRange: range(titles[0]) });
    if (classification.type === 'PHRASE') { outOfScope++; notes.push({ kind: 'OUT_OF_SCOPE_PHRASE', region, headword, sourceRange: range(titles[0]) }); continue; }
    if (!classification.key) { issue('HEADWORD_UNSUPPORTED', region, 'headword', range(titles[0])); continue; }
    const header = titles[0].parentNode;
    const phonetics = nodes(header, n => has(n, 'phonetic')).map(n => ({ accent: 'unknown', ipa: compact(text(n)), audioId: null, sourceRange: range(n) }));
    const createEntry = (word, componentOrder, sourceRange) => ({ entryKey: entryKey(source.sourceOrdinal, region, componentOrder), headword: word, lemmaKey: sourceHead(word).key, sectionOrder: region, componentOrder, parentEntryKey: componentOrder ? entryKey(source.sourceOrdinal, region, 0) : null, phonetics: componentOrder ? [] : phonetics, sourceRange, senses: [] });
    const main = createEntry(headword, 0, range(root)); const entries = [main]; let nextComponent = 1;
    const majors = nodes(root, n => has(n, 'collinsMajorTrans'));
    for (const [majorOrder, major] of majors.entries()) {
      const paragraphs = nodes(major, n => n.tagName === 'p');
      if (!paragraphs.length) { issue('MEANING_BOUNDARY_UNKNOWN', region, 'zh', range(major)); continue; }
      // A major is an explicit structure group. Repeated headings in another
      // major are not merged merely because the spelling happens to match.
      let current = main;
      for (const [paragraphOrder, p] of paragraphs.entries()) {
        const posNodes = nodes(p, typedPos); const posNode = posNodes[0] ?? null;
        const rawPos = posNode ? compact(text(posNode)) : null;
        const labels = nodes(p, n => n !== posNode && has(n, 'additional') && !has(n, 'phonetic'))
          .filter(n => !has(n.parentNode ?? {}, 'additional'))
          .map(n => ({ raw: compact(text(n)), kind: labelKind(compact(text(n))), sourceRange: range(n) })).filter(l => l.raw);
        const omitMetadata = n => n === posNode || has(n, 'additional');
        const children = p.childNodes ?? [];
        // Position comparison also works with an inline wrapper around POS.
        const before = posNode ? compact(children.filter(n => (range(n)?.endOffset ?? Infinity) <= range(posNode)?.startOffset).map(n => text(n, omitMetadata)).join('')) : '';
        let body = compact(text(p, omitMetadata));
        if (before) {
          const subhead = sourceHead(before);
          if (!subhead.key || before.toLowerCase() === headword.toLowerCase()) {
            issue('SUBENTRY_STRUCTURE_UNKNOWN', region, 'headword', range(p)); continue;
          }
          current = createEntry(before, nextComponent++, range(p)); entries.push(current);
          body = compact(body.slice(before.length));
        }
        const pEvidence = { kind: 'PARAGRAPH', region, majorOrder, paragraphOrder, entryKey: current.entryKey, rawPos, labels, sourceRange: range(p) };
        evidence.push(pEvidence);
        if (/\b(?:(?:plural|past tense|past participle|comparative|superlative|abbreviation) of|same as(?:\s+(?:a|an|the|to))*|compare)\s*\./iu.test(body)) { issue('SOURCE_TEXT_DEFECT', region, 'relation', range(p)); continue; }
        const escapedHead = current.headword.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
        const explicitForm = body.match(new RegExp(`^(?:${escapedHead}\\s+(?:is|are)\\s+(?:the\\s+)?|(?:the|a)\\s+)?(plural|past tense|past participle|comparative|superlative) of\\s+([A-Za-z][A-Za-z'-]*)(?=[.;]|$|\\s+in (?:American|British) English[.;]|\\s+\\p{Script=Han})`, 'iu'));
        if (explicitForm) notes.push({ kind: 'EXPLICIT_INFLECTION', relation: explicitForm[1].toLowerCase(), target: explicitForm[2], region, componentOrder: current.componentOrder, sourceRange: range(p), ruleId: 'COL_EXPLICIT_ENGLISH_FORM', approvedMapping: false });
        const ref = reference(p, body);
        if (ref) {
          const r = { ...ref, region, componentOrder: current.componentOrder, root: false }; references.push(r);
          if (ref.conflict && !['SEE_ALSO', 'COMPARE'].includes(ref.kind)) issue('REFERENCE_DISPLAY_CONFLICT', region, 'relation', ref.sourceRange);
          body = body.slice(0, ref.marker).trim();
          // A phrase label before an arrow is relation metadata, not a meaning.
          if (!/\p{Script=Han}/u.test(body)) continue;
        }
        if (/[→←]/u.test(body)) { issue('REFERENCE_STRUCTURE_UNKNOWN', region, 'relation', range(p)); continue; }
        if (!body && current !== main) { notes.push({ kind: 'DERIVATION_NOTE', headword: current.headword, rawPos, labels, region, componentOrder: current.componentOrder, sourceRange: range(p) }); continue; }
        if (!body) { issue('SENSE_MISSING', region, 'senses', range(p)); continue; }
        const marked = nodes(p, n => has(n, 'chn') || has(n, 'translation'));
        const split = marked.length === 1 ? segment(compact(text(marked[0])), { localized: true }) : marked.length > 1 ? null : segment(body);
        if (!split) { issue('MEANING_BOUNDARY_UNKNOWN', region, 'zh', range(p)); continue; }
        if ([...split.zh].length > 10000) { issue('MEANING_TOO_LONG', region, 'zh', range(p)); continue; }
        const label = nodes(major, n => has(n, 'collinsOrder'))[0];
        current.senses.push({ pos: posOf(rawPos ?? ''), rawPos, zh: split.zh, providerOrder: current.senses.length, sourceLabel: label ? compact(text(label)) : null, labels, sourceRange: range(p), boundaryEvidence: { ruleId: marked.length ? 'EXPLICIT_TRANSLATION_NODE' : split.ruleId, bodyCharacterOffset: split.boundary, offsetBasis: 'DECODED_COMPACT_BODY', sourceRange: marked.length ? range(marked[0]) : range(p) } });
      }
    }
    if (!majors.length) {
      const body = compact(text(root, n => n === header)); const ref = reference(root, body);
      if (ref) { references.push({ ...ref, region, componentOrder: 0, root: true }); if (ref.conflict) issue('REFERENCE_DISPLAY_CONFLICT', region, 'relation', range(root)); }
      else issue('SENSE_MISSING', region, 'senses', range(root));
    }
    const usable = entries.filter(e => e.senses.length);
    if (!usable.length && !references.some(r => r.region === region) && !issues.some(i => i.region === region)) issue('SENSE_MISSING', region, 'senses', range(root));
    // Intro and explicit inflection tables remain evidence, never corrections.
    for (const n of nodes(root, n => has(n, 'collins-intro') || has(n, 'pattern'))) {
      const value = compact(text(n));
      const candidates = [...value.matchAll(/\b(?:AM|also)\s+([A-Za-z][A-Za-z'-]*)/gu)].map(m => ({ surface: m[1], evidenceType: m[0].startsWith('AM') ? 'AM_VARIANT' : 'ALSO_VARIANT', approvedMapping: false }));
      notes.push({ kind: has(n, 'pattern') ? 'EXPLICIT_FORM_TABLE' : 'VARIANT_INTRO', region, raw: value, candidates, sourceRange: range(n) });
    }
    regions.push(...usable);
  }
  if (issues.length) return finish('FAIL', { parseErrors });
  if (!regions.length && references.length) {
    for (const r of references) { r.root = true; if(r.conflict && !issues.some(i=>i.issueCode==='REFERENCE_DISPLAY_CONFLICT')) issue('REFERENCE_DISPLAY_CONFLICT',r.region,'relation',r.sourceRange); }
    if(issues.length) return finish('FAIL', { parseErrors });
    return finish('ALIAS', { parseErrors });
  }
  if (!regions.length && outOfScope) return finish('SKIP', { reason: 'OUT_OF_SCOPE_PHRASE', parseErrors });
  return finish('ENTRY', { parseErrors });
}
