import test from 'node:test';
import assert from 'node:assert/strict';
import { parse } from 'parse5';
import { extract } from '../src/extract.mjs';
import { lexicalUnits, segment } from '../src/segment.mjs';
import { nodes, range, sourceHead } from '../src/evidence.mjs';
import { entryIdentity } from '../src/identity.mjs';
import { entryTarget, reference, resolveReference } from '../src/references.mjs';
const wrap = body => `<div class="wt-container"><h4><span class="title">sample</span><em class="phonetic">/x/</em></h4>${body}</div>`;
const major = body => `<div class="collinsMajorTrans">${body}</div>`;
const pos = '<span class="additional" title="词性">ADV</span>';
const src = html => ({sourceOrdinal:7,indexKey:'sample',sourceRecordHash:'f'.repeat(64),rawDecodedText:html});
test('bilingual units retain full Chinese parentheses, inline English and digits without period',()=>{
 assert.deepEqual(segment('an invented fragment (自建括注) 示例 sample 42'),{zh:'(自建括注) 示例 sample 42',ruleId:'COL_BILINGUAL_LEXICAL_UNITS',boundary:21});
 assert.equal(segment('an invented fragment 示例 “inline words” 42').zh,'示例 “inline words” 42');
});
test('unbalanced, unseparated and multiple ambiguous transitions remain unknown',()=>{
 for(const s of ['unclosed (示例','a syntheticfragment示例','one (bad] 示例','English only'])assert.equal(segment(s),null);
 assert.equal(lexicalUnits('a “two words” 示例').length,3);
});
test('subentry has own identity, no inherited IPA, and groups continuation inside major',()=>{
 const r=extract(src(wrap(major(`<p>${pos}主词释义</p>`)+major(`<p>samplely ${pos}子词释义</p><p>${pos}另一义</p>`))));
 assert.equal(r.outcome,'ENTRY');assert.deepEqual(r.regions.map(e=>[e.entryKey,e.headword,e.senses.length,e.phonetics.length]),[['s7:r0:c0','sample',1,1],['s7:r0:c1','samplely',2,0]]);
});
test('same spelling in separate explicit groups and regions is not merged',()=>{
 const part=major(`<p>samplely ${pos}子词义</p>`);
 const r=extract(src(wrap(part+part)+wrap(part)));
 assert.deepEqual(r.regions.map(e=>e.entryKey),['s7:r0:c1','s7:r0:c2','s7:r1:c1']);
});
test('derivation-only note preserves POS and labels without fictional sense',()=>{
 const r=extract(src(wrap(major(`<p>${pos}主词义</p>`)+major(`<p>samplely ${pos}<span class="additional">[非正式]</span></p>`))));
 assert.equal(r.outcome,'ENTRY');assert.equal(r.regions.length,1);assert.equal(r.notes[0].kind,'DERIVATION_NOTE');assert.equal(r.notes[0].labels[0].raw,'[非正式]');
});
test('labels and supplementary POS remain ordered metadata, never zh',()=>{
 const r=extract(src(wrap(major(`<p>${pos}示例<span class="additional">[英]</span><span class="additional" title="补充">N-COUNT</span><span class="additional">[new label]</span></p>`))));
 const s=r.regions[0].senses[0];assert.equal(s.zh,'示例');assert.deepEqual(s.labels.map(l=>l.raw),['[英]','N-COUNT','[new label]']);assert.equal(s.labels[2].kind,'UNCLASSIFIED');
});
test('see-also is a relation even when Chinese translation precedes it',()=>{
 const r=extract(src(wrap(major(`<p>${pos}自建释义 → see also <a href="entry://other">other</a></p>`))));
 assert.equal(r.regions[0].senses[0].zh,'自建释义');assert.equal(r.references[0].targets[0].target,'other');
});
test('ordinary English see is not a cross-reference',()=>{
 assert.equal(reference(parse('<p>If you see something. 自建释义</p>'),'If you see something. 自建释义'),null);
});
test('raw href retains terminal dot, decodes once, leaves plus, and rejects remote/control',()=>{
 assert.equal(entryTarget('entry://ibid.'),'ibid.');assert.equal(entryTarget('entry://a%2520b#anchor'),'a%20b');assert.equal(entryTarget('entry://a+b'),'a+b');
 for(const href of ['https://example.test','javascript:evil()','entry://%00','entry://%ZZ'])assert.equal(entryTarget(href),null);
});
test('conflicting display and target are quarantined',()=>{
 const r=extract(src(wrap('<p>→ see <a href="entry://one">two</a></p>')));assert.equal(r.outcome,'FAIL');assert.equal(r.issues[0].issueCode,'REFERENCE_DISPLAY_CONFLICT');
});
test('root references and raw MDX redirects are aliases, no empty entry',()=>{
 for(const html of [wrap('<p>→ see <a href="entry://other">other</a></p>'),'@@@LINK=other']){const r=extract(src(html));assert.equal(r.outcome,'ALIAS');assert.equal(r.regions.length,0);assert.equal(r.references[0].root,true);}
});
test('missing target remains defect despite an explicit Chinese hint',()=>{
 const r=extract(src(wrap(major(`<p>${pos}past tense of . (sample)的过去式</p>`))));assert.equal(r.outcome,'FAIL');assert.equal(r.issues[0].issueCode,'SOURCE_TEXT_DEFECT');
});
test('None and empty are source defects; failed sibling atomically isolates candidates',()=>{
 for(const s of ['None','\u0000',''])assert.equal(extract(src(s)).issues[0].issueCode,'SOURCE_EMPTY_RECORD');
 const r=extract(src(wrap(major(`<p>${pos}示例</p>`))+wrap(major('<p>English only</p>'))));assert.equal(r.outcome,'FAIL');assert.equal(r.regions.length,0);assert.equal(r.quarantinedRegions.length,1);
});
test('source head classification does not change query normalizer or erase bound forms',()=>{
 assert.equal(sourceHead('-sample').type,'BOUND_FORM');assert.equal(sourceHead('2.5X').type,'SYMBOL_OR_ABBREVIATION');assert.equal(sourceHead("babies'-breath").key,null);assert.equal(sourceHead('two words').type,'PHRASE');assert.equal(sourceHead('sample-word').key,'sample-word');
});
test('evidence ranges point to original HTML in UTF16 units; inert nodes excluded',()=>{
 globalThis.__readictProbe=0;
 const html=wrap(`<script>globalThis.__readictProbe=1</script><div class="exampleLists">${major(`<p>${pos}例句</p>`)}</div>${major(`<p>${pos}自建<img src="https://invalid.test/a"></p>`)}`);
 const r=extract(src(html));assert.equal(globalThis.__readictProbe,0);assert.equal(r.regions[0].senses[0].zh,'自建');const s=r.regions[0].senses[0].sourceRange;assert.equal(s.unit,'UTF16_CODE_UNIT');assert.ok(html.slice(s.startOffset,s.endOffset).startsWith('<p>'));
 assert.equal(nodes(parse(html,{sourceCodeLocationInfo:true}),n=>n.tagName==='script').length,0);assert.equal(range({}),null);delete globalThis.__readictProbe;
});
test('graph preserves duplicate keys, multiple candidates, quarantine and phrase status',()=>{
 const map=new Map([['other',[{sourceOrdinal:1,available:true,redirects:[]},{sourceOrdinal:2,available:false,redirects:[]}]]]);
 assert.deepEqual(resolveReference('other',map).map(r=>r.status),['TARGET_PRESENT','TARGET_QUARANTINED']);assert.equal(resolveReference('two words',map)[0].status,'OUT_OF_SCOPE_PHRASE');assert.equal(resolveReference('absent',map)[0].status,'DANGLING');
});
test('graph distinguishes eight redirects, ninth hop, cycles and constructor',()=>{
 const map=new Map();for(let i=0;i<9;i++)map.set('w'+i,[{sourceOrdinal:i,available:true,redirects:i<8?['w'+(i+1)]:[]}]);
 assert.equal(resolveReference('w0',map)[0].status,'TARGET_PRESENT');map.set('w8',[{available:false,redirects:['w9']}]);map.set('w9',[{available:true,redirects:[]}]);assert.equal(resolveReference('w0',map)[0].status,'HOP_LIMIT');map.set('loop',[{redirects:['loop']}]);assert.equal(resolveReference('loop',map)[0].status,'CYCLE');map.set('constructor',[{available:true}]);assert.equal(resolveReference('constructor',map)[0].status,'TARGET_PRESENT');
});
test('identity-v2 fixed vector and component isolation',()=>{
 const a=entryIdentity('00000000-0000-8000-8000-000000000001',7,0,0,'sample');
 assert.equal(a.id,'3512e547-8803-80ea-bb49-9d4874b45598');assert.equal(a.fullHash,'3512e547880390ea7b499d4874b455980b5a85714d9a0e66a1f5b5f9094caa6e');assert.equal(entryIdentity('00000000-0000-8000-8000-000000000001',7,0,1,'sample').id,'5cebfd6a-6508-812d-8707-07c6827e09d1');assert.notEqual(a.id,entryIdentity('00000000-0000-8000-8000-000000000001',7,0,1,'sample').id);
});

test('missing same-as/compare target is quarantined, not hidden by readable Chinese',()=>{
 for(const body of ['A word is the same as . 自建','A word is the same as a . 同sample','A definition. Compare . 自建'])assert.equal(extract(src(wrap(major(`<p>${pos}${body}</p>`)))).issues[0].issueCode,'SOURCE_TEXT_DEFECT');
});
test('explicit variant spelling and inflection evidence do not invent mappings',()=>{
 const alias=extract(src(wrap(major('<p>→ a variant spelling of <a href="entry://other">other</a></p>'))));assert.equal(alias.outcome,'ALIAS');
 const entry=extract(src(wrap(major(`<p>${pos}past tense of other 自建说明</p>`))));assert.equal(entry.notes[0].kind,'EXPLICIT_INFLECTION');assert.equal(entry.notes[0].target,'other');assert.equal(entry.notes[0].approvedMapping,false);
});

test('compare is relation data; unknown arrow syntax never leaks into Chinese',()=>{
 const a=extract(src(wrap(major(`<p>${pos}定义片段 示例 → compare <a href="entry://other">other</a></p>`))));assert.equal(a.regions[0].senses[0].zh,'定义片段 示例');assert.equal(a.references[0].kind,'COMPARE');
 const b=extract(src(wrap(major(`<p>${pos}示例 → undocumented other</p>`))));assert.equal(b.outcome,'FAIL');assert.equal(b.issues[0].issueCode,'REFERENCE_STRUCTURE_UNKNOWN');
});

test('editorial cross-reference wording retains authoritative href without guessing lemma',()=>{
 for(const wording of ['a slang word for','a variant spelling (esp us) of']){
 const r=extract(src(wrap(major(`<p>→ ${wording} <a href="entry://other">other</a></p>`))));assert.equal(r.outcome,'ALIAS');assert.equal(r.references[0].kind,'SOURCE_REFERENCE');assert.equal(r.references[0].targets[0].target,'other');}
 assert.equal(extract(src(wrap(major(`<p>${pos}means the same as to . 自建</p>`)))).issues[0].issueCode,'SOURCE_TEXT_DEFECT');
});

test('ordinary bilingual another-name definition is not converted into an alias',()=>{
 const r=extract(src(wrap(major(`<p>${pos}another name for the invented year (in a synthetic context) 自建年份</p>`))));assert.equal(r.outcome,'ENTRY');assert.equal(r.regions[0].senses[0].zh,'自建年份');assert.equal(r.references.length,0);
});

test('unresolved English-led localized units stay isolated instead of losing their form',()=>{
 for(const body of ['a synthetic definition (sample form)的形式','sample的过去式 → see <a href="entry://sample">sample</a>']){
  const r=extract(src(wrap(major(`<p>${pos}${body}</p>`))));assert.equal(r.outcome,'FAIL');assert.equal(r.regions.length,0);assert.ok(r.issues.some(i=>i.issueCode==='MEANING_BOUNDARY_UNKNOWN'));
 }
});
