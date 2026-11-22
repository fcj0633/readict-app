import { mkdir, readFile, writeFile, rename, stat, realpath, lstat, unlink } from 'node:fs/promises';
import { createReadStream, createWriteStream } from 'node:fs';
import { once } from 'node:events';
import {createHash} from 'node:crypto';
import { createInterface } from 'node:readline';
import { join, resolve } from 'node:path';
import { TextDecoder } from 'node:util';
import { diagnose, optionalReference } from './diagnose.mjs';
import { fingerprint, sha256 } from './parser-adapter.mjs';
import { normalize } from './normalize.mjs';
import { entryIdentity, uuid8 } from './identity.mjs';
import { rulesVersion } from './extract.mjs';
import { literalKey, resolveReference } from './references.mjs';
export const names = ['entries.jsonl','senses.jsonl','forms.jsonl','dispositions.jsonl','issues.jsonl','quality-report.json'];
export const converterVersion = 'readict-col-converter-4';
export const policy = 'col-local-mvp-v1';
export const compareEvidence=(a,b)=>a.sourceOrdinal-b.sourceOrdinal||(a.region??-1)-(b.region??-1)||Buffer.compare(Buffer.from(a.ruleId),Buffer.from(b.ruleId))||(a.sourceRange?.startOffset??-1)-(b.sourceRange?.startOffset??-1)||Buffer.compare(Buffer.from(JSON.stringify(a)),Buffer.from(JSON.stringify(b)));
export const hash = value => sha256(JSON.stringify(value));
const line = value => JSON.stringify(value) + '\n';
function requireThat(ok, code) { if (!ok) throw new Error(code); }
// Validate duplicate keys before JSON.parse can overwrite them, including nested objects.
export function strictJson(raw) {
  let i = 0;
  const space = () => { while (/\s/u.test(raw[i] ?? '') && i < raw.length) i++; };
  function value() {
    space(); const c = raw[i];
    if (c === '{') { i++; const seen = new Set(); space(); if (raw[i] === '}') { i++; return; } while (true) { space(); const k = string(); requireThat(!seen.has(k),'DUPLICATE_JSON_KEY'); seen.add(k); space(); requireThat(raw[i++] === ':','INVALID_JSON'); value(); space(); const end = raw[i++]; if (end === '}') return; requireThat(end === ',','INVALID_JSON'); } }
    if (c === '[') { i++; space(); if (raw[i] === ']') { i++; return; } while (true) { value(); space(); const end = raw[i++]; if (end === ']') return; requireThat(end === ',','INVALID_JSON'); } }
    if (c === '"') { string(); return; }
    const m = raw.slice(i).match(/^(?:true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/u); requireThat(m,'INVALID_JSON'); i += m[0].length;
  }
  function string() { requireThat(raw[i] === '"','INVALID_JSON'); const start = i++; while (i < raw.length) { if (raw[i++] === '"') return JSON.parse(raw.slice(start,i)); if (raw[i-1] === '\\') i++; } throw new Error('INVALID_JSON'); }
  value(); space(); requireThat(i === raw.length,'INVALID_JSON'); const parsed=JSON.parse(raw);const unicode=v=>{if(typeof v==='string')requireThat(v.isWellFormed(),'UNPAIRED_SURROGATE');else if(v&&typeof v==='object')for(const [k,x]of Object.entries(v)){requireThat(k.isWellFormed(),'UNPAIRED_SURROGATE');unicode(x);}};unicode(parsed);return parsed;
}
function shape(value) {
  if(typeof value==='number')requireThat(Number.isSafeInteger(value)&&value>=0,'INTEGER_INVALID');
  if(value&&typeof value==='object')for(const [key,child]of Object.entries(value)) {
    if(key==='sourceRange'&&child!==null)requireThat(child.unit==='UTF16_CODE_UNIT'&&Number.isSafeInteger(child.startOffset)&&Number.isSafeInteger(child.endOffset)&&child.startOffset>=0&&child.endOffset>=child.startOffset&&child.endOffset<=2*1024*1024&&Object.keys(child).every(k=>['startOffset','endOffset','unit'].includes(k)),'SOURCE_RANGE_INVALID');
    shape(child);
  }
}
export async function* rows(file) {
  const decoder = new TextDecoder('utf-8',{fatal:true}); let pending = ''; let first = true;
  for await (const chunk of createReadStream(file)) {
    const text = decoder.decode(chunk,{stream:true}); if (first) { requireThat(chunk[0] !== 239,'BOM_REJECTED'); first = false; }
    pending += text; let idx;
    while ((idx = pending.indexOf('\n')) >= 0) { const current = pending.slice(0,idx); pending = pending.slice(idx+1); requireThat(current && !current.includes('\r') && Buffer.byteLength(current) <= 512*1024,'LINE_INVALID'); const parsed=strictJson(current);shape(parsed);yield parsed; }
    requireThat(Buffer.byteLength(pending) <= 512*1024,'LINE_BUDGET');
  }
  pending += decoder.decode(); requireThat(!pending,'FINAL_LF_REQUIRED');
}
async function writer(file) { const stream = createWriteStream(file,{flags:'wx'}); return { async put(v) { const s = line(v); requireThat(Buffer.byteLength(s)<=512*1024,'LINE_BUDGET'); if (!stream.write(s)) await once(stream,'drain'); }, async close() { stream.end(); await once(stream,'finish'); } }; }
async function fileInfo(dir,name) { let sizeBytes=0,lineCount=0;const digest=createHash('sha256');for await(const chunk of createReadStream(join(dir,name))){sizeBytes+=chunk.length;digest.update(chunk);for(const byte of chunk)if(byte===10)lineCount++;}return {name,sizeBytes,lineCount,sha256:digest.digest('hex')}; }

function keyOrNull(v) { try { return normalize(v); } catch { return null; } }
export function explicitForms(raw) {
  if(typeof raw!=='string' || !/^\(\s*[\p{L}\p{N}\p{M}]+(?:[-'][\p{L}\p{N}\p{M}]+)*(?:\s*,\s*[\p{L}\p{N}\p{M}]+(?:[-'][\p{L}\p{N}\p{M}]+)*)*\s*\)$/u.test(raw)) return [];
  return raw.slice(1,-1).split(',').map(s=>s.trim()).filter(s=>keyOrNull(s));
}
export async function convert({mdx,mdd,out,'dataset-version':datasetVersion}) {
  requireThat(/^[A-Za-z0-9._-]{1,64}$/u.test(datasetVersion ?? ''),'DATASET_VERSION_REQUIRED');
  try { await stat(out); throw new Error('OUTPUT_EXISTS'); } catch (e) { if(e.code !== 'ENOENT') throw e; }
  const start = performance.now(); const mddBefore = mdd ? await fingerprint(mdd) : null; const tmp = out + '.partial'; await mkdir(tmp);await writeFile(join(tmp,'.partial'),'conversion in progress\n',{flag:'wx'}); const diagnostic = join(tmp,'diagnostic'); const report = await diagnose({mdx,out:diagnostic});
  const source = {mdxSha256:report.source.sha256,mddSha256:mddBefore?.sha256 ?? null}; source.sourceHash=hash(['readict-col-source-1',source.mdxSha256,source.mddSha256]);
  const identity = ['readict-col-bundle-2',source.sourceHash,datasetVersion,converterVersion,'js-mdict@6.0.6','col-normalize-v1',rulesVersion];
  const id = uuid8(identity); const datasetId=id.id;
  const output = new Map(); for (const name of names.filter(n=>n.endsWith('.jsonl'))) output.set(name,await writer(join(tmp,name)));
  const entries=[]; const entryByKey=new Map(); const literalIndex=new Map(); let senseCount=0; const sourceCounts=new Map();
  for await (const candidate of rows(join(diagnostic,'candidates.jsonl'))) {
    const entryId=entryIdentity(datasetId,candidate.sourceOrdinal,candidate.sectionOrder,candidate.componentOrder,candidate.indexKey).id;
    const {senses,...meta}=candidate;
    const entry={entryId,entryKey:candidate.entryKey,sourceOrdinal:candidate.sourceOrdinal,indexKey:candidate.indexKey,sectionOrder:candidate.sectionOrder,componentOrder:candidate.componentOrder,parentEntryKey:candidate.parentEntryKey,headword:candidate.headword,lemmaKey:normalize(candidate.headword),lemmaMethod:'EXACT_HEADWORD',phonetics:candidate.phonetics,sourceRecordHash:candidate.sourceRecordHash,sourceRange:candidate.sourceRange,notes:candidate.notes,references:candidate.references.map(r=>({...r,usable:r.targets.every(t=>r.resolutions?.find(x=>x.target===t.target)?.results.every(x=>x.status==='TARGET_PRESENT')) && !r.conflict}))};
    await output.get('entries.jsonl').put(entry); entries.push({entryId,sourceOrdinal:entry.sourceOrdinal,headword:entry.headword,componentOrder:entry.componentOrder,notes:entry.notes}); entryByKey.set(entry.entryKey,entryId);
    const k=literalKey(entry.headword); if(!literalIndex.has(k)) literalIndex.set(k,[]); literalIndex.get(k).push({entryId,sourceOrdinal:entry.sourceOrdinal,available:true,redirects:[]});
    for(const sense of senses) { await output.get('senses.jsonl').put({senseId:uuid8(['sense-v1',entryId,sense.providerOrder]).id,entryId,senseKey:'p'+sense.providerOrder,...sense}); senseCount++; }
    const c=sourceCounts.get(entry.sourceOrdinal)??{entryCount:0,senseCount:0,formCount:0}; c.entryCount++; c.senseCount+=senses.length; sourceCounts.set(entry.sourceOrdinal,c);
  }
  const disposition=[]; for await(const row of rows(join(diagnostic,'dispositions.jsonl'))) disposition.push(row);
  const refs=[]; for await(const row of rows(join(diagnostic,'references.jsonl'))) refs.push(row);
  for(const d of disposition.filter(d=>d.outcome==='ALIAS')) { const redirect=refs.filter(r=>r.sourceOrdinal===d.sourceOrdinal && r.root).flatMap(r=>r.targets.map(t=>t.target)); const k=literalKey(d.indexKey); if(!literalIndex.has(k)) literalIndex.set(k,[]); literalIndex.get(k).push({sourceOrdinal:d.sourceOrdinal,available:false,redirects:redirect}); }
  const formMap=new Map(); let generated=0;
  function add(surface,target,relation,evidence) { const surfaceKey=keyOrNull(surface); if(!surfaceKey) return; const results=resolveReference(target,literalIndex); if(results.some(r=>r.status!=='TARGET_PRESENT')) return;
    for(const ordinal of [...new Set(results.map(r=>r.sourceOrdinal))]) for(const targetEntry of entriesByOrdinal.get(ordinal)??[]) { if(literalKey(targetEntry.headword)!==literalKey(target) && !results.some(r=>r.path.at(-1)===literalKey(targetEntry.headword))) continue; const key=JSON.stringify([surfaceKey,targetEntry.entryId,relation]); generated++; const existing=formMap.get(key); if(existing) existing.evidence.push(evidence); else formMap.set(key,{surfaceKey,entryId:targetEntry.entryId,relation,evidence:[evidence],priority:relation==='INFLECTION'?10:20}); const c=sourceCounts.get(evidence.sourceOrdinal)??{entryCount:0,senseCount:0,formCount:0}; c.formCount++; sourceCounts.set(evidence.sourceOrdinal,c); }
  }
  const entriesByOrdinal=new Map(); for(const e of entries) { if(!entriesByOrdinal.has(e.sourceOrdinal)) entriesByOrdinal.set(e.sourceOrdinal,[]); entriesByOrdinal.get(e.sourceOrdinal).push(e); }
  for(const d of disposition.filter(d=>d.outcome==='ALIAS')) for(const ref of refs.filter(r=>r.sourceOrdinal===d.sourceOrdinal && r.root && !r.conflict)) for(const t of ref.targets) add(d.indexKey,t.target,'REDIRECT',{sourceOrdinal:d.sourceOrdinal,region:ref.region,sourceLabel:null,ruleId:ref.ruleId});
  for(const e of entries) for(const n of e.notes.filter(n=>n.kind==='EXPLICIT_INFLECTION' && (n.componentOrder??0)===e.componentOrder)) add(e.headword,n.target,'INFLECTION',{sourceOrdinal:e.sourceOrdinal,region:n.region,sourceLabel:n.relation,ruleId:n.ruleId});
  // A source pattern list explicitly names forms of this physical main entry.
  // It does not justify assigning the parent's forms to an embedded subentry.
  for(const e of entries.filter(e=>e.componentOrder===0)) for(const n of e.notes.filter(n=>n.kind==='EXPLICIT_FORM_TABLE')) for(const surface of explicitForms(n.raw)) {
    const surfaceKey=normalize(surface);if(surfaceKey===normalize(e.headword))continue;
    const key=JSON.stringify([surfaceKey,e.entryId,'INFLECTION']); const evidence={sourceOrdinal:e.sourceOrdinal,region:n.region,sourceLabel:null,ruleId:'COL_EXPLICIT_FORM_TABLE',sourceRange:n.sourceRange};generated++;
    const existing=formMap.get(key);if(existing)existing.evidence.push(evidence);else formMap.set(key,{surfaceKey,entryId:e.entryId,relation:'INFLECTION',evidence:[evidence],priority:10});
    sourceCounts.get(e.sourceOrdinal).formCount++;
  }
  const forms=[...formMap.values()].sort((a,b)=>Buffer.compare(Buffer.from(a.surfaceKey),Buffer.from(b.surfaceKey))||Buffer.compare(Buffer.from(a.entryId),Buffer.from(b.entryId))||Buffer.compare(Buffer.from(a.relation),Buffer.from(b.relation)));
  for(const f of forms)f.evidence.sort(compareEvidence);
  for(const f of forms) await output.get('forms.jsonl').put(f);
  for(const d of disposition) { const c=sourceCounts.get(d.sourceOrdinal)??{entryCount:0,senseCount:0,formCount:0}; await output.get('dispositions.jsonl').put({sourceOrdinal:d.sourceOrdinal,indexKey:d.indexKey,sourceRecordHash:d.sourceRecordHash,outcome:d.outcome,...c,issueCodes:d.issueCodes}); }
  for await(const issue of rows(join(diagnostic,'issues.jsonl'))) await output.get('issues.jsonl').put(issue);
  for(const w of output.values()) await w.close();
  const counts={sourceTotal:report.counts.sourceTotal,sourceSucceeded:report.counts.sourceSucceeded,sourceSkipped:report.counts.sourceSkipped,sourceFailed:report.counts.sourceFailed,entryCount:entries.length,senseCount,formGenerated:generated,formDeduplicated:generated-forms.length,formCount:forms.length};
  const quality={schemaVersion:'readict-col-quality-2',usageProfile:'LOCAL_MVP',adoptionPolicy:policy,counts,errors:report.errors,releaseEligible:false,localMvpEligible:entries.length>0,qualityReview:'EXTERNAL_ACCEPTANCE_REQUIRED'};
  await writeFile(join(tmp,'quality-report.json'),line(quality),{flag:'wx'}); const files=[];for(const n of names)files.push(await fileInfo(tmp,n));
  const manifest={schemaVersion:identity[0],diagnosticOnly:false,usageProfile:'LOCAL_MVP',adoptionPolicy:policy,source,datasetVersion,converterVersion,parserVersion:identity[4],normalizationVersion:identity[5],rulesVersion,identityHash:id.fullHash,datasetId,counts,files,contentHash:hash(files.map(f=>[f.name,f.sizeBytes,f.sha256])),quality:{reportSha256:files.at(-1).sha256,exclusionApprovalHash:null,releaseEligible:false,localMvpEligible:true},rights:{status:'UNVERIFIED',policyVersion:'unverified',attribution:'本地 COL；来源及使用权利待核实',snapshotAllowed:false,distributionAllowed:false}};
  await writeFile(join(tmp,'manifest.json'),line(manifest),{flag:'wx'});
  requireThat(JSON.stringify(report.source)===JSON.stringify(await fingerprint(mdx)) && JSON.stringify(mddBefore)===JSON.stringify(mdd?await fingerprint(mdd):null),'SOURCE_CHANGED');
  await validate({bundle:tmp,'allow-local-mvp':true,internalPartial:true});
  // The diagnostic subdirectory is deliberately outside the final bundle and not imported.
  requireThat(process.resourceUsage().maxRSS*1024<=1024**3,'RSS_BUDGET_EXCEEDED');
  await unlink(join(tmp,'.partial'));
  await rename(diagnostic,out+'.diagnostic'); await rename(tmp,out);
  return {datasetId,counts,usageProfile:'LOCAL_MVP',durationMs:Math.round(performance.now()-start),peakRssBytes:process.resourceUsage().maxRSS*1024};
}
export async function validate(args){try{return await validateInternal(args);}catch(error){if(error instanceof TypeError||error instanceof SyntaxError)throw new Error('BUNDLE_STRUCTURE_OR_UTF8_INVALID');throw error;}}
async function validateInternal({bundle,'allow-local-mvp':allow=false,internalPartial=false}) {
  const root=await realpath(bundle);let marker=false;try{await stat(join(root,'.partial'));marker=true;}catch(error){if(error.code!=='ENOENT')throw error;} requireThat(internalPartial||!root.endsWith('.partial')&&!marker,'PARTIAL_BUNDLE');
  const manifestPath=join(root,'manifest.json'); requireThat(!(await lstat(manifestPath)).isSymbolicLink() && await realpath(manifestPath)===manifestPath,'PATH_INVALID');
  const manifests=[];for await(const m of rows(manifestPath))manifests.push(m);requireThat(manifests.length===1,'MANIFEST_INVALID');const m=manifests[0];
  requireThat(m.schemaVersion==='readict-col-bundle-2' && m.diagnosticOnly===false,'SCHEMA_OR_DIAGNOSTIC_INVALID');
  requireThat(m.usageProfile==='LOCAL_MVP' && m.adoptionPolicy===policy && allow,'LOCAL_MVP_OPT_IN_REQUIRED');
  requireThat(m.normalizationVersion==='col-normalize-v1' && m.rulesVersion===rulesVersion,'VERSION_UNSUPPORTED');
  requireThat(m.source.sourceHash===hash(['readict-col-source-1',m.source.mdxSha256,m.source.mddSha256]),'SOURCE_HASH');
  const id=uuid8([m.schemaVersion,m.source.sourceHash,m.datasetVersion,m.converterVersion,m.parserVersion,m.normalizationVersion,m.rulesVersion]);requireThat(id.id===m.datasetId&&id.fullHash===m.identityHash,'IDENTITY_MISMATCH');
  requireThat(JSON.stringify(m.files.map(f=>f.name))===JSON.stringify(names),'FILE_WHITELIST');let total=0;
  for(const f of m.files) { const path=join(root,f.name);requireThat(!(await lstat(path)).isSymbolicLink()&&await realpath(path)===path,'PATH_INVALID');requireThat((await stat(path)).size<=1024**3,'FILE_BUDGET');const actual=await fileInfo(root,f.name);requireThat(JSON.stringify(actual)===JSON.stringify(f),'FILE_HASH_MISMATCH');total+=actual.sizeBytes; }requireThat(total<=1024**3,'BUNDLE_BUDGET');
  requireThat(m.contentHash===hash(m.files.map(f=>[f.name,f.sizeBytes,f.sha256]))&&m.quality.reportSha256===m.files.at(-1).sha256,'CONTENT_HASH');
  const allowed=(n,keys)=>requireThat(Object.keys(n).every(k=>keys.includes(k)),'UNKNOWN_FIELD');
  allowed(m,['schemaVersion','diagnosticOnly','usageProfile','adoptionPolicy','source','datasetVersion','converterVersion','parserVersion','normalizationVersion','rulesVersion','identityHash','datasetId','counts','files','contentHash','quality','rights']);
  requireThat(m.rights.status==='UNVERIFIED'&&m.rights.snapshotAllowed===false&&m.rights.distributionAllowed===false,'RIGHTS_INVALID');
  const entries=new Map(),senseCounts=new Map(),senseIds=new Set(),forms=new Set(),formOrigins=new Map(),perSource=new Map();let senses=0,formCount=0;
  for await(const e of rows(join(root,names[0]))) {allowed(e,['entryId','entryKey','sourceOrdinal','indexKey','sectionOrder','componentOrder','parentEntryKey','headword','lemmaKey','lemmaMethod','phonetics','sourceRecordHash','sourceRange','notes','references']);requireThat([e.sourceOrdinal,e.sectionOrder,e.componentOrder].every(n=>Number.isSafeInteger(n)&&n>=0),'INTEGER_INVALID');requireThat(!entries.has(e.entryId)&&e.entryId===entryIdentity(m.datasetId,e.sourceOrdinal,e.sectionOrder,e.componentOrder,e.indexKey).id&&e.entryKey===`s${e.sourceOrdinal}:r${e.sectionOrder}:c${e.componentOrder}`,'ENTRY_IDENTITY'); requireThat(e.sectionOrder<=2147483647&&e.componentOrder<=2147483647&&e.lemmaMethod==='EXACT_HEADWORD','ENTRY_INVALID'); requireThat(e.lemmaKey===normalize(e.headword)&&[...e.headword].length<=128&&/^[a-f0-9]{64}$/u.test(e.sourceRecordHash),'ENTRY_INVALID');entries.set(e.entryId,e);const c=perSource.get(e.sourceOrdinal)??{entryCount:0,senseCount:0};c.entryCount++;perSource.set(e.sourceOrdinal,c); }
  for await(const s of rows(join(root,names[1]))) {allowed(s,['senseId','entryId','senseKey','pos','rawPos','zh','providerOrder','sourceLabel','labels','sourceRange','boundaryEvidence']);const e=entries.get(s.entryId);const n=senseCounts.get(s.entryId)??0;requireThat(e&&!senseIds.has(s.senseId)&&s.senseId===uuid8(['sense-v1',s.entryId,s.providerOrder]).id&&s.providerOrder===n&&s.senseKey==='p'+n,'SENSE_IDENTITY');requireThat(typeof s.zh==='string'&&s.zh.trim()&&[...s.zh].length<=10000&&Array.isArray(s.labels),'SENSE_INVALID');senseIds.add(s.senseId);senseCounts.set(s.entryId,n+1);perSource.get(e.sourceOrdinal).senseCount++;senses++;}
  requireThat([...entries.keys()].every(id=>senseCounts.has(id)),'EMPTY_ENTRY');
  for await(const f of rows(join(root,names[2]))) {allowed(f,['surfaceKey','entryId','relation','evidence','priority']);const k=JSON.stringify([f.surfaceKey,f.entryId,f.relation]);requireThat(entries.has(f.entryId)&&f.surfaceKey===normalize(f.surfaceKey)&&['INFLECTION','REDIRECT'].includes(f.relation)&&f.priority===(f.relation==='INFLECTION'?10:20)&&f.evidence?.length&&!forms.has(k),'FORM_INVALID');requireThat(f.evidence.every((e,i)=>i===0||compareEvidence(f.evidence[i-1],e)<=0),'EVIDENCE_ORDER');for(const e of f.evidence){requireThat(Number.isSafeInteger(e.sourceOrdinal)&&e.sourceOrdinal>=0&&(e.region===null||Number.isSafeInteger(e.region)&&e.region>=0)&&['MDX_REDIRECT','COL_EXPLICIT_REFERENCE','COL_EXPLICIT_ENGLISH_FORM','COL_EXPLICIT_FORM_TABLE'].includes(e.ruleId),'EVIDENCE_INVALID');formOrigins.set(e.sourceOrdinal,(formOrigins.get(e.sourceOrdinal)??0)+1);}forms.add(k);formCount++;}
  let ordinal=0,succeeded=0,skipped=0,failed=0,formGenerated=0;const dispositionBySource=new Map();const failedOrdinals=new Set();
  for await(const d of rows(join(root,names[3]))) {requireThat(d.sourceOrdinal===ordinal++&&['ENTRY','ALIAS','SKIP','FAIL'].includes(d.outcome),'DISPOSITION_COVERAGE');dispositionBySource.set(d.sourceOrdinal,d);requireThat(d.formCount===(formOrigins.get(d.sourceOrdinal)??0)&&(['ENTRY','ALIAS'].includes(d.outcome)||!d.formCount),'FORM_SOURCE_COUNT');formOrigins.delete(d.sourceOrdinal);formGenerated+=d.formCount;const c=perSource.get(d.sourceOrdinal)??{entryCount:0,senseCount:0};requireThat(c.entryCount===d.entryCount&&c.senseCount===d.senseCount,'DISPOSITION_COUNTS');if(d.outcome==='FAIL'){failed++;failedOrdinals.add(d.sourceOrdinal);requireThat(!d.entryCount&&d.issueCodes.length,'QUARANTINE_INVALID');}else if(d.outcome==='SKIP'){skipped++;requireThat(!d.entryCount,'SKIP_INVALID');}else succeeded++; }
  for(const e of entries.values()){const d=dispositionBySource.get(e.sourceOrdinal);requireThat(d&&d.outcome==='ENTRY'&&d.indexKey===e.indexKey&&d.sourceRecordHash===e.sourceRecordHash,'SOURCE_COVERAGE');}requireThat(formOrigins.size===0&&formGenerated===m.counts.formGenerated,'FORM_GENERATED_COUNT');
  const errors=new Set();for await(const issue of rows(join(root,names[4]))) {requireThat(Number.isInteger(issue.sourceOrdinal)&&issue.sourceOrdinal>=0&&issue.sourceOrdinal<ordinal&&['ERROR','WARNING'].includes(issue.severity),'ISSUE_INVALID');if(issue.severity==='ERROR'){requireThat(failedOrdinals.has(issue.sourceOrdinal),'INCLUDED_BLOCKING_ERROR');errors.add(issue.sourceOrdinal);}}
  requireThat([...failedOrdinals].every(n=>errors.has(n)),'FAIL_WITHOUT_ERROR');
  const actual={sourceTotal:ordinal,sourceSucceeded:succeeded,sourceSkipped:skipped,sourceFailed:failed,entryCount:entries.size,senseCount:senses,formCount};for(const[k,v]of Object.entries(actual))requireThat(m.counts[k]===v,'COUNT_MISMATCH');requireThat(m.counts.formGenerated-m.counts.formDeduplicated===formCount&&entries.size>0,'COUNT_MISMATCH');
  const q=[];for await(const row of rows(join(root,names[5])))q.push(row);requireThat(q.length===1&&q[0].schemaVersion==='readict-col-quality-2'&&q[0].usageProfile==='LOCAL_MVP'&&q[0].adoptionPolicy===policy&&JSON.stringify(q[0].counts)===JSON.stringify(m.counts)&&q[0].localMvpEligible===true&&q[0].releaseEligible===false&&m.quality.localMvpEligible===true&&m.quality.releaseEligible===false,'QUALITY_INVALID');
  return {datasetId:m.datasetId,usageProfile:m.usageProfile,counts:m.counts,status:'LOCAL_MVP_VALIDATED',releaseEligible:false};
}
