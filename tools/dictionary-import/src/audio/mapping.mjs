import { parseFragment } from 'parse5';
import { normalize } from '../normalize.mjs';

export function literalResourceKey(key) {
  if (!key || /[\u0000-\u001f\u007f:]/u.test(key) || /^[\\/]/u.test(key) || key.split(/[\\/]/u).some(x => x === '..' || x === '.' || !x)) throw new Error('AUDIO_LINK_INVALID');
  return key;
}
export function resourceKey(uri) {
  if (!uri.startsWith('sound://')) throw new Error('AUDIO_LINK_INVALID');
  return literalResourceKey(decodeURIComponent(uri.slice(8))); // '+' is literal; never decode twice.
}
function nodes(root) { return [root, ...(root.childNodes ?? []).flatMap(nodes)]; }
function attr(node, key) { return node.attrs?.find(a => a.name === key)?.value; }
function text(node) { return node.nodeName === '#text' ? node.value : (node.childNodes ?? []).filter(n => !['script','style'].includes(n.tagName)).map(text).join(''); }
export function parseMapping(record) {
  const dom = parseFragment(record.rawDecodedText, { sourceCodeLocationInfo: true });
  const all = nodes(dom).filter(n => !['script','style'].includes(n.tagName));
  const titles = all.filter(n => n.tagName === 'b').map(text);
  const displayHeadword = titles.length === 1 ? titles[0].trim() : null;
  let wordKey = null;
  try { if (displayHeadword) {wordKey = normalize(displayHeadword);if(wordKey!==displayHeadword.normalize('NFKC').toLowerCase().replace(/[‘’]/g,"'"))wordKey=null;} } catch { /* Source affixes/contractions must not masquerade as bare words. */ }
  const links = []; const issues = [];
  for (const a of all.filter(n => n.tagName === 'a' && attr(n,'href')?.startsWith('sound:'))) {
    const linkOrder = links.length; const href = attr(a,'href');
    const markers = nodes(a).filter(n => n.tagName === 'img').map(n => attr(n,'src'));
    const accents = [...new Set(markers.map(x => x === '/snd_us.png' ? 'en-US' : x === '/snd_uk.png' ? 'en-GB' : null).filter(Boolean))];
    let target = null;
    try { target = resourceKey(href); } catch { issues.push({linkOrder,code:'AUDIO_LINK_INVALID'}); }
    const accent = accents.length === 1 ? accents[0] : null;
    if (!accent) issues.push({linkOrder,code:'AUDIO_ACCENT_UNKNOWN'});
    links.push({ sourceOrdinal:record.sourceOrdinal,linkOrder,indexKey:record.indexKey,displayHeadword,wordKey,accent,target,
      evidence:{href,markers,sourceRange:a.sourceCodeLocation ? {startOffset:a.sourceCodeLocation.startOffset,endOffset:a.sourceCodeLocation.endOffset,unit:'UTF16_CODE_UNIT'} : null} });
  }
  if (!displayHeadword) issues.push({code:'AUDIO_HEADWORD_UNKNOWN'});
  return { links, issues, outcome:!displayHeadword ? 'FAIL' : !wordKey ? 'OUT_OF_SCOPE' : links.length ? 'MAPPED' : 'NO_AUDIO' };
}
