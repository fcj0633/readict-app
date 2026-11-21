import test from 'node:test';
import assert from 'node:assert/strict';
import { extract } from '../src/extract.mjs';
import { sha256 } from '../src/parser-adapter.mjs';
const source = html => ({ sourceOrdinal: 7, indexKey: 'sample', rawDecodedText: html, sourceRecordHash: sha256(html) });
const region = body => `<div class="wt-container"><h4><span class="title">sample</span><em class="phonetic">/sæmpəl/</em></h4>${body}</div>`;
const sense = (meaning, pos = 'N-COUNT', label = '4') => `<div class="collinsMajorTrans"><span class="collinsOrder">${label}</span><p><span title="synthetic POS">${pos}</span>${meaning}</p></div>`;

test('explicit translation nodes decode entities once and retain source sense order', () => {
  const result = extract(source(region(sense('<span class="chn">自建 &amp;lt; 标签</span>', 'N-COUNT', '9') + sense('<span class="chn">自建第二义</span>', 'V-T', '2'))));
  assert.equal(result.outcome, 'ENTRY'); assert.deepEqual(result.regions[0].senses.map(s => [s.zh, s.pos, s.providerOrder, s.sourceLabel]), [['自建 &lt; 标签', 'noun', 0, '9'], ['自建第二义', 'verb', 1, '2']]);
  assert.equal(result.regions[0].phonetics[0].ipa, '/sæmpəl/');
});
test('COL complete sentence layout preserves leading Chinese parentheses and English form', () => {
  const result = extract(source(region(sense('An invented definition. (自建括注) 示例 sample；保留 42'))));
  assert.equal(result.outcome, 'ENTRY'); assert.equal(result.regions[0].senses[0].zh, '(自建括注) 示例 sample；保留 42');
});
test('mixed text without an explicit sentence/translation boundary is quarantined', () => {
  const result = extract(source(region(sense('a synthetic fragment 自建释义'))));
  assert.equal(result.outcome, 'FAIL'); assert.equal(result.regions.length, 0); assert.equal(result.issues[0].issueCode, 'MEANING_BOUNDARY_UNKNOWN');
});
test('scripts, remote elements and nested examples never become a sense', () => {
  const html = region('<script>throw new Error("executed")</script><div class="exampleLists">' + sense('<span class="chn">不可进入输出</span>') + '</div>' + sense('<span class="chn" onclick="evil()">自建释义<img src="https://invalid.test/x" /></span>'));
  const result = extract(source(html)); assert.equal(result.outcome, 'ENTRY'); assert.equal(result.regions[0].senses.length, 1); assert.equal(result.regions[0].senses[0].zh, '自建释义');
});
test('one failed region rejects the complete source, not only its bad part', () => {
  const result = extract(source(region(sense('<span class="chn">自建释义</span>')) + region(sense('English only'))));
  assert.equal(result.outcome, 'FAIL'); assert.deepEqual(result.regions, []); assert.equal(result.issues[0].region, 1);
});
test('visible missing relation target is a source defect, never invented', () => {
  const result = extract(source(region(sense('Sample is the plural of . 自建复数'))));
  assert.equal(result.outcome, 'FAIL'); assert.equal(result.issues[0].issueCode, 'SOURCE_TEXT_DEFECT');
});
test('unknown POS remains unknown; missing IPA is allowed', () => {
  const result = extract(source(region(sense('<span class="chn">自建释义</span>', 'NEW-LABEL')).replace('<em class="phonetic">/sæmpəl/</em>', '')));
  assert.equal(result.outcome, 'ENTRY'); assert.equal(result.regions[0].senses[0].pos, 'unknown'); assert.deepEqual(result.regions[0].phonetics, []);
});
test('redirect and phrase dispositions are explicit, punctuation inside a word is retained', () => {
  assert.equal(extract(source('@@@LINK=target')).redirect, 'target');
  assert.equal(extract({ ...source('phrase'), indexKey: 'two words' }).reason, 'OUT_OF_SCOPE_PHRASE');
  assert.equal(extract({ ...source(region(sense('<span class="chn">自建释义</span>'))), indexKey: "sample-word" }).outcome, 'ENTRY');
});
test('duplicate titles fail and long meanings are not truncated', () => {
  assert.equal(extract(source(region(sense('<span class="chn">自建</span>')).replace('<h4>', '<h4><span class="title">other</span>'))).outcome, 'FAIL');
  const result = extract(source(region(sense('<span class="chn">' + '字'.repeat(10001) + '</span>'))));
  assert.equal(result.issues[0].issueCode, 'MEANING_TOO_LONG');
});

test('COL region teaser titles do not count as duplicate headwords; see-also is not a definition', () => {
  const result = extract(source(region(sense('<span class="chn">自建释义</span>') + '<div class="collinsMajorTrans"><p>→ see also reference</p></div>').replace('<h4>', '<div class="title trans-tip">preview</div><h4>')));
  assert.equal(result.outcome, 'ENTRY'); assert.equal(result.regions[0].senses.length, 1);
});
