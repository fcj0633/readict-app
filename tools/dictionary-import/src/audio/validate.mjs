import { readFile, realpath, lstat,readdir } from 'node:fs/promises';
import { join, sep } from 'node:path';
import { fingerprint } from '../parser-adapter.mjs';
import { rows, strictJson } from '../bundle.mjs';
import { uuid8 } from '../identity.mjs';
import { normalize } from '../normalize.mjs';
import { hash } from './inspect.mjs';
import {quarantinable,subsetPolicy,fullPolicy,approvalRef} from './policy.mjs';

export const names=['assets.jsonl','mappings.jsonl','issues.jsonl','quality.json','dispositions.jsonl'];
function check(ok,code){if(!ok)throw new Error(code);}
export async function safeFile(root,key){
  check(typeof key==='string'&&!key.includes('\\')&&!key.split('/').some(x=>!x||x==='.'||x==='..')&&!/[:\u0000-\u001f]/u.test(key),'AUDIO_PATH_INVALID');
  let path=root;for(const part of key.split('/')){path=join(path,part);check(!(await lstat(path)).isSymbolicLink(),'AUDIO_LINK_REJECTED');}
  const actual=await realpath(path);check(actual.startsWith(root+sep),'AUDIO_PATH_ESCAPE');return actual;
}
function field(s,max){return typeof s==='string'&&s.length>0&&[...s].length<=max;}
export async function validate(bundle,internalPartial=false,allowLocalSubset=false){
  const root=await realpath(bundle);check(internalPartial||!root.toLowerCase().endsWith('.partial'),'AUDIO_PARTIAL');
  try{await lstat(join(root,'state.json'));check(internalPartial,'AUDIO_PARTIAL');}catch(e){if(e.code!=='ENOENT')throw e;}
  const raw=await readFile(await safeFile(root,'manifest.json'));check(raw.length<=512*1024&&raw[0]!==239&&!raw.includes(13),'AUDIO_MANIFEST_INVALID');
  const m=strictJson(new TextDecoder('utf-8',{fatal:true}).decode(raw));
  check(m.schemaVersion==='readict-audio-bundle-1'&&m.diagnosticOnly===false&&m.normalizeVersion==='col-normalize-v1','AUDIO_SCHEMA_INVALID');
  check(Array.isArray(m.sources)&&m.sources.length===3&&m.sources.every((s,i)=>s.role===['icons','mdd','mdx'][i]&&Number.isSafeInteger(s.sizeBytes)&&s.sizeBytes>0&&/^[0-9a-f]{64}$/u.test(s.sha256)),'AUDIO_SOURCE_INVALID');
  check(field(m.converterVersion,100)&&field(m.parserVersion,100)&&m.encoding?.sampleRate===22050&&m.encoding.channels===1&&m.encoding.bitRate===48000&&m.encoding.codec==='libmp3lame'&&m.encoding.metadata==='removed'&&Object.keys(m.encoding).length===5,'AUDIO_ENCODING_INVALID');
  for(const tool of [m.ffmpeg,m.ffprobe])check(tool&&Number.isSafeInteger(tool.sizeBytes)&&tool.sizeBytes>0&&/^[0-9a-f]{64}$/u.test(tool.sha256),'AUDIO_TOOL_INVALID');
  const subset=m.usageProfile==='LOCAL_MVP';
  check(subset?allowLocalSubset&&m.adoptionPolicy===subsetPolicy&&m.approvalRef===approvalRef&&m.converterVersion.endsWith('/local-subset-v1'):m.usageProfile==='FULL'&&m.adoptionPolicy===fullPolicy&&m.approvalRef===null&&m.converterVersion.endsWith('/full-v1'),'AUDIO_POLICY_NOT_ALLOWED');
  check(Array.isArray(m.identityTuple)&&m.identityTuple.length===7&&m.identityTuple[0]==='audio-dataset-v1'&&m.identityTuple[1]===hash(m.sources.map(s=>[s.role,s.sizeBytes,s.sha256]))&&m.identityTuple[2]===m.converterVersion&&m.identityTuple[3]===m.parserVersion&&m.identityTuple[4]===m.ffmpeg.sha256&&m.identityTuple[5]===hash(m.encoding)&&m.identityTuple[6]===m.normalizeVersion,'AUDIO_IDENTITY_INVALID');
  const identity=uuid8(m.identityTuple);check(identity.id===m.datasetId&&identity.fullHash===m.identitySha256&&m.version==='audio-v1/'+m.datasetId,'AUDIO_IDENTITY_INVALID');
  check(m.files.length===names.length&&m.files.every((f,i)=>f.name===names[i]),'AUDIO_FILES_INVALID');
  for(const f of m.files){const actual=await fingerprint(await safeFile(root,f.name));check(actual.sha256===f.sha256&&actual.sizeBytes===f.sizeBytes,'AUDIO_DIGEST_INVALID');}
  check(m.resourceCounts&&Object.keys(m.resourceCounts).sort().join(',')==='icons,mdd'&&Object.values(m.resourceCounts).every(v=>Number.isSafeInteger(v)&&v>=0)&&Array.isArray(m.nonAudioResources),'AUDIO_RESOURCE_COVERAGE');
  const covered=new Set();for(const r of m.nonAudioResources){const k=r.role+':'+r.sourceOrdinal;check(['icons','mdd'].includes(r.role)&&r.kind==='IMAGE'&&field(r.sourceResourceKey,512)&&Number.isSafeInteger(r.sourceOrdinal)&&r.sourceOrdinal>=0&&r.sourceOrdinal<m.resourceCounts[r.role]&&!covered.has(k),'AUDIO_RESOURCE_COVERAGE');covered.add(k);}
  const ids=new Map(),ordinals=new Set(),mappingSources=new Map(),quarantined=new Set(),quarantinedMappings=new Map();let assets=0,mappings=0,errors=0,failed=0,mappingQuarantined=0;const keys=new Set();let previous=-1;
  for await(const a of rows(join(root,'assets.jsonl'))){
    check(field(a.sourceResourceKey,512)&&a.mime==='audio/mpeg'&&a.sampleRate===22050&&a.channels===1&&a.durationMs>0&&a.durationMs<=60080&&a.bytes>0&&a.bytes<=2*1024*1024,'AUDIO_ASSET_INVALID');
    check(/^[0-9a-f]{64}$/u.test(a.sourceSha256)&&/^[0-9a-f]{64}$/u.test(a.outputSha256)&&a.storageKey===`media/${a.outputSha256.slice(0,2)}/${a.outputSha256}.mp3`,'AUDIO_ASSET_INVALID');
    check(uuid8(['audio-asset-v1',m.datasetId,a.sourceResourceKey,a.sourceSha256,a.outputSha256]).id===a.audioId&&!ids.has(a.audioId)&&!ordinals.has(a.sourceOrdinal)&&a.sourceOrdinal>previous&&a.sourceOrdinal<m.resourceCounts.mdd&&!covered.has('mdd:'+a.sourceOrdinal),'AUDIO_ASSET_IDENTITY');previous=a.sourceOrdinal;covered.add('mdd:'+a.sourceOrdinal);
    const f=await fingerprint(await safeFile(root,a.storageKey));check(f.sha256===a.outputSha256&&f.sizeBytes===a.bytes,'AUDIO_MEDIA_INVALID');
    ids.set(a.audioId,a);ordinals.add(a.sourceOrdinal);assets++;
  }
  const pending=[];
  for await(const r of rows(join(root,'issues.jsonl'))){
    check(field(r.code,128)&&['ERROR','WARNING'].includes(r.severity),'AUDIO_ISSUE_INVALID');
    if(r.stage==='CONVERSION'){
      failed++;const key='mdd:'+r.sourceOrdinal;
      check(subset&&r.severity==='ERROR'&&r.disposition==='QUARANTINED'&&quarantinable.has(r.code)&&Number.isSafeInteger(r.sourceOrdinal)&&r.sourceOrdinal>=0&&r.sourceOrdinal<m.resourceCounts.mdd&&!covered.has(key)&&field(r.resourceKey,512)&&/^[0-9a-f]{64}$/u.test(r.sourceSha256)&&Number.isSafeInteger(r.sizeBytes)&&r.sizeBytes>0&&r.sizeBytes<=2097152,'AUDIO_UNAPPROVED_FAILURE');
      if(r.code==='AUDIO_SOURCE_DECODE')check(r.evidence?.ruleId==='ffmpeg-strict-speex-v1'&&r.evidence.decoder==='speex'&&r.evidence.result==='INVALID_PACKET','AUDIO_DECODE_EVIDENCE');
      covered.add(key);quarantined.add(r.sourceOrdinal);
    }else if(r.stage==='MAPPING'&&r.code==='AUDIO_RESOURCE_QUARANTINED'){pending.push(r);}
    else if(r.severity==='ERROR')errors++;
  }
  for(const r of pending){const key=JSON.stringify([r.sourceOrdinal,r.linkOrder]);check(r.severity==='WARNING'&&r.disposition==='QUARANTINED'&&quarantined.has(r.resourceOrdinal)&&!keys.has(key),'AUDIO_QUARANTINE_REFERENCE');keys.add(key);quarantinedMappings.set(r.sourceOrdinal,(quarantinedMappings.get(r.sourceOrdinal)??0)+1);mappingQuarantined++;}
  previous='';
  for await(const r of rows(join(root,'mappings.jsonl'))){
    const key=JSON.stringify([r.sourceOrdinal,r.linkOrder]);check(!keys.has(key)&&ids.has(r.audioId)&&r.normalizeVersion===m.normalizeVersion&&r.wordKey===normalize(r.displayHeadword)&&field(r.displayHeadword,255)&&field(r.indexKey,255)&&['en-GB','en-US'].includes(r.accent)&&r.evidence?.href?.startsWith('sound://'),'AUDIO_MAPPING_INVALID');
    const order=String(r.sourceOrdinal).padStart(12,'0')+':'+String(r.linkOrder).padStart(8,'0');check(order>previous,'AUDIO_MAPPING_ORDER');previous=order;keys.add(key);mappings++;mappingSources.set(r.sourceOrdinal,(mappingSources.get(r.sourceOrdinal)??0)+1);
  }
  check(covered.size===m.resourceCounts.mdd+m.resourceCounts.icons&&m.nonAudioResources.length===m.counts.nonAudio,'AUDIO_RESOURCE_COVERAGE');
  let sourceCount=0;const sourceTotals={MAPPED:0,OUT_OF_SCOPE:0,NO_AUDIO:0,FAIL:0},linkTotals=[0,0,0,0];
  for await(const d of rows(join(root,'dispositions.jsonl'))){
    check(d.sourceOrdinal===sourceCount++&&Object.hasOwn(sourceTotals,d.outcome)&&d.resolvedCount===(mappingSources.get(d.sourceOrdinal)??0)+(quarantinedMappings.get(d.sourceOrdinal)??0)&&d.linkCount===d.resolvedCount+d.missingCount+d.invalidCount+d.outOfScopeCount,'AUDIO_SOURCE_COVERAGE');
    sourceTotals[d.outcome]++;for(const [i,k]of ['resolvedCount','missingCount','invalidCount','outOfScopeCount'].entries())linkTotals[i]+=d[k];
  }
  const q=[];for await(const r of rows(join(root,'quality.json')))q.push(r);
  const c=m.counts;
  check(sourceCount===c.mdxTotal&&sourceTotals.MAPPED===c.sourceMapped&&sourceTotals.OUT_OF_SCOPE===c.sourceOutOfScope&&sourceTotals.NO_AUDIO===c.sourceNoAudio&&sourceTotals.FAIL===c.sourceFailed&&linkTotals.every((v,i)=>v===[c.linksResolved,c.linksMissing,c.linksInvalid,c.linksOutOfScope][i])&&covered.size===c.mddTotal,'AUDIO_SOURCE_COVERAGE');
  check(q.length===1&&q[0].schemaVersion==='readict-audio-quality-1'&&q[0].humanReview==='NOT_PERFORMED'&&q[0].rightsReview==='UNVERIFIED'&&JSON.stringify(q[0].counts)===JSON.stringify(c)&&q[0].releaseEligible===false&&m.releaseEligible===false&&m.rights.status==='UNVERIFIED'&&m.rights.snapshotAllowed===false&&m.rights.distributionAllowed===false,'AUDIO_QUALITY_INVALID');
  const eligible=assets>0&&assets===c.assetCount&&mappings===c.mappingCount&&c.audioCandidates===assets+failed&&failed===c.conversionFailed&&quarantined.size===c.quarantinedCount&&c.blockingErrors===0&&c.mddTotal===c.audioCandidates+c.nonAudio+c.unknownResources&&c.mdxTotal===c.sourceMapped+c.sourceOutOfScope+c.sourceNoAudio+c.sourceFailed&&c.linksTotal===c.linksResolved+c.linksMissing+c.linksInvalid+c.linksOutOfScope&&mappings+mappingQuarantined===c.linksResolved&&mappingQuarantined===c.mappingQuarantined&&errors===0&&c.mappingErrors===0&&c.unknownResources===0&&c.linksMissing===0&&c.linksInvalid===0&&c.sourceFailed===0&&m.completeSourceEligible===(failed===0);
  check(eligible&&m.technicalEligible===true&&q[0].technicalEligible===true,'AUDIO_QUALITY_REJECTED');
  const allowed=new Set(['manifest.json',...names,'media',...(internalPartial?['state.json','checkpoint.jsonl']:[])]);
  for(const item of await readdir(root,{withFileTypes:true}))check(allowed.has(item.name)&&!item.isSymbolicLink(),'AUDIO_UNEXPECTED_FILE');
  const media=new Set([...ids.values()].map(a=>a.storageKey));
  for(const dir of await readdir(join(root,'media'),{withFileTypes:true})){
    check(dir.isDirectory()&&!dir.isSymbolicLink()&&/^[0-9a-f]{2}$/u.test(dir.name),'AUDIO_UNEXPECTED_FILE');
    for(const file of await readdir(join(root,'media',dir.name),{withFileTypes:true}))check(file.isFile()&&!file.isSymbolicLink()&&media.has(`media/${dir.name}/${file.name}`),'AUDIO_UNEXPECTED_FILE');
  }
  return {datasetId:m.datasetId,status:'AUDIO_LOCAL_TECHNICAL_VALIDATED',assetCount:assets,mappingCount:mappings,releaseEligible:false};
}
