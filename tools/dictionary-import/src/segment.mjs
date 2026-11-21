const han = s => /\p{Script=Han}/u.test(s);
// COL lexical units: whitespace separates units only outside paired brackets
// and quotes. A Chinese parenthesis is kept with the translation it introduces.
export function lexicalUnits(value) {
  const pairs = new Map([['(', ')'], ['（', '）'], ['[', ']'], ['【', '】'], ['“', '”'], ['‘', '’'], ['《', '》'], ['"', '"']]);
  const stack = []; const units = []; let start = null;
  for (let i = 0; i < value.length; i++) {
    const c = value[i];
    if (/\s/u.test(c) && !stack.length) { if (start !== null) units.push({ start, end: i, value: value.slice(start, i) }); start = null; continue; }
    if (start === null) start = i;
    if (stack.at(-1) === c) stack.pop();
    else if (pairs.has(c)) stack.push(pairs.get(c));
    else if ([')', '）', ']', '】', '”', '》'].includes(c)) return null;
  }
  if (stack.length) return null;
  if (start !== null) units.push({ start, end: value.length, value: value.slice(start) });
  return units;
}
export function segment(value, { localized = false } = {}) {
  const units = lexicalUnits(value);
  if (!units?.length || !han(value)) return null;
  // Independently typed translation/subentry nodes already establish the start.
  const localStart = /^(?:[\p{Script=Han}“‘《…]|[（(][^）)]*\p{Script=Han})/u.test(units[0].value);
  if (localized || localStart) return { zh: value, ruleId: localized ? 'TYPED_LOCALIZED_BODY' : 'LOCALIZED_FIRST_UNIT', boundary: 0 };
  const candidates = [];
  for (let i = 1; i < units.length; i++) {
    if (!/^(?:[\p{Script=Han}“‘《…]|[（(][^）)]*\p{Script=Han})/u.test(units[i].value)) continue;
    const prefix = value.slice(0, units[i].start).trim();
    // A complete English prefix and a localized lexical unit are both needed.
    // No scan for the first Han code point, no split inside a bracket/word.
    if (!han(prefix) && /[A-Za-z]/u.test(prefix) && !/[→←]/u.test(prefix)) candidates.push(units[i].start);
  }
  if (candidates.length !== 1) return null;
  const boundary = candidates[0];
  return { zh: value.slice(boundary), ruleId: 'COL_BILINGUAL_LEXICAL_UNITS', boundary };
}
