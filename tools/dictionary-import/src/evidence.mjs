// Offsets are parse5 UTF-16 character offsets into the original HTML, not bytes.
export const classes = n => (n.attrs?.find(a => a.name === 'class')?.value ?? '').split(/\s+/u);
export const has = (n, name) => classes(n).includes(name);
export const attr = (n, name) => n.attrs?.find(a => a.name === name)?.value ?? null;
export const compact = s => s.replace(/\s+/gu, ' ').trim();
const forbidden = new Set(['script', 'style', 'iframe', 'object', 'embed', 'link', 'img', 'audio', 'video', 'template']);
export const blocked = n => forbidden.has(n.tagName) || has(n, 'exampleLists');
export function nodes(root, predicate) {
  const found = [];
  function visit(n) { if (blocked(n)) return; if (predicate(n)) found.push(n); for (const c of n.childNodes ?? []) visit(c); }
  visit(root); return found;
}
export function text(n, omit = () => false) {
  if (blocked(n) || omit(n)) return '';
  return n.nodeName === '#text' ? n.value : (n.childNodes ?? []).map(c => text(c, omit)).join('');
}
export const range = n => n.sourceCodeLocation ? { startOffset: n.sourceCodeLocation.startOffset, endOffset: n.sourceCodeLocation.endOffset, unit: 'UTF16_CODE_UNIT' } : null;
export function sourceHead(value) {
  if (!value || !value.isWellFormed() || /[\p{Cc}\p{Cf}]/u.test(value) || [...value].length > 128 || Buffer.byteLength(value) > 512) return { type: 'INVALID', key: null };
  const key = value.normalize('NFKC').toLowerCase().replace(/[‘’]/gu, "'");
  if (/\s/u.test(key)) return { type: 'PHRASE', key: null };
  if (/^[-']/u.test(key) || /[-']$/u.test(key)) return { type: 'BOUND_FORM', key: null };
  if (/^[\p{L}\p{N}\p{M}]+(?:[-'][\p{L}\p{N}\p{M}]+)*$/u.test(key) && /\p{L}/u.test(key)) return { type: 'WORD', key };
  return { type: 'SYMBOL_OR_ABBREVIATION', key: null };
}
