import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { parseMapping, resourceKey } from '../../src/audio/mapping.mjs';
import { signal, run } from '../../src/audio/probe.mjs';
import { validate } from '../../src/audio/validate.mjs';
import { uuid8 } from '../../src/identity.mjs';
import { sha256, fingerprint } from '../../src/parser-adapter.mjs';
import { hash } from '../../src/audio/inspect.mjs';
import {speexInfo,mp3Info,transcodeBatch} from '../../src/audio/probe.mjs';

export async function fixture(root){
  await mkdir(root,{recursive:true});
  const sources=['icons','mdd','mdx'].map(role=>({role,sizeBytes:10,sha256:'0'.repeat(64)}));
  const encoding={sampleRate:22050,channels:1,bitRate:48000,codec:'libmp3lame',metadata:'removed'};
  const tuple=['audio-dataset-v1',hash(sources.map(s=>[s.role,s.sizeBytes,s.sha256])),'synthetic-audio-1/full-v1','js-mdict@6.0.6','1'.repeat(64),hash(encoding),'col-normalize-v1'];
  const id=uuid8(tuple),media=Buffer.from('self-authored transport fixture; not source dictionary audio');const sha=sha256(media),sourceSha='2'.repeat(64);
  const storageKey=`media/${sha.slice(0,2)}/${sha}.mp3`;await mkdir(join(root,'media',sha.slice(0,2)),{recursive:true});await writeFile(join(root,storageKey),media);
  const audioId=uuid8(['audio-asset-v1',id.id,'\\sample.spx',sourceSha,sha]).id;
  const asset={audioId,sourceOrdinal:0,sourceResourceKey:'\\sample.spx',sourceSha256:sourceSha,outputSha256:sha,storageKey,bytes:media.length,mime:'audio/mpeg',sampleRate:22050,channels:1,durationMs:500};
  const mapping={sourceOrdinal:0,linkOrder:0,indexKey:'sample',displayHeadword:'sample',wordKey:'sample',normalizeVersion:'col-normalize-v1',accent:'en-GB',audioId,evidence:{href:'sound://sample.spx',markers:['/snd_uk.png'],matchRule:'EXACT',sourceRange:{startOffset:0,endOffset:80,unit:'UTF16_CODE_UNIT'}}};
  const counts={mdxTotal:1,mddTotal:1,audioCandidates:1,nonAudio:0,unknownResources:0,sourceMapped:1,sourceOutOfScope:0,sourceNoAudio:0,sourceFailed:0,linksTotal:1,linksResolved:1,linksMissing:0,linksInvalid:0,linksOutOfScope:0,assetCount:1,mappingCount:1,conversionFailed:0,quarantinedCount:0,blockingErrors:0,mappingQuarantined:0,mappingErrors:0,warnings:0};
  const quality={schemaVersion:'readict-audio-quality-1',counts,technicalEligible:true,humanReview:'NOT_PERFORMED',rightsReview:'UNVERIFIED',releaseEligible:false};
  const disposition={sourceOrdinal:0,outcome:'MAPPED',linkCount:1,resolvedCount:1,missingCount:0,invalidCount:0,outOfScopeCount:0};
  for(const [name,value] of [['assets.jsonl',[asset]],['mappings.jsonl',[mapping]],['issues.jsonl',[]],['quality.json',[quality]],['dispositions.jsonl',[disposition]]])await writeFile(join(root,name),value.map(v=>JSON.stringify(v)+'\n').join(''));
  const files=[];for(const name of ['assets.jsonl','mappings.jsonl','issues.jsonl','quality.json','dispositions.jsonl'])files.push({name,...await fingerprint(join(root,name))});
  const manifest={schemaVersion:'readict-audio-bundle-1',diagnosticOnly:false,usageProfile:'FULL',adoptionPolicy:'audio-full-v1',approvalRef:null,datasetId:id.id,version:'audio-v1/'+id.id,identitySha256:id.fullHash,identityTuple:tuple,sources,resourceCounts:{mdd:1,icons:0},nonAudioResources:[],converterVersion:'synthetic-audio-1/full-v1',parserVersion:'js-mdict@6.0.6',ffmpeg:{sha256:'1'.repeat(64),sizeBytes:1,version:'synthetic'},ffprobe:{sha256:'3'.repeat(64),sizeBytes:1},encoding,normalizeVersion:'col-normalize-v1',files,counts,technicalEligible:true,completeSourceEligible:true,releaseEligible:false,rights:{status:'UNVERIFIED',snapshotAllowed:false,distributionAllowed:false}};
  await writeFile(join(root,'manifest.json'),JSON.stringify(manifest)+'\n');return {manifest,asset,mapping,media};
}
export async function subsetFixture(root){
  const f=await fixture(root),m=f.manifest;
  m.usageProfile='LOCAL_MVP';m.adoptionPolicy='audio-local-subset-v1';m.approvalRef='user-2026-11-22-allow-defective-recordings';m.converterVersion='synthetic-audio-1/local-subset-v1';m.identityTuple[2]=m.converterVersion;
  const id=uuid8(m.identityTuple);m.datasetId=id.id;m.identitySha256=id.fullHash;m.version='audio-v1/'+id.id;m.completeSourceEligible=false;
  f.asset.audioId=uuid8(['audio-asset-v1',id.id,f.asset.sourceResourceKey,f.asset.sourceSha256,f.asset.outputSha256]).id;f.mapping.audioId=f.asset.audioId;
  m.counts.mddTotal=2;m.counts.audioCandidates=2;m.counts.conversionFailed=1;m.counts.quarantinedCount=1;m.resourceCounts.mdd=2;
  const issue={stage:'CONVERSION',sourceOrdinal:1,resourceKey:'\\synthetic-silence.spx',sourceSha256:'3'.repeat(64),sizeBytes:100,code:'AUDIO_SOURCE_SIGNAL',severity:'ERROR',disposition:'QUARANTINED',retryable:false};
  const q=JSON.parse(await readFile(join(root,'quality.json'),'utf8'));q.counts=m.counts;
  for(const [name,value]of [['assets.jsonl',f.asset],['mappings.jsonl',f.mapping],['issues.jsonl',issue],['quality.json',q]])await writeFile(join(root,name),JSON.stringify(value)+'\n');
  for(const file of m.files)Object.assign(file,await fingerprint(join(root,file.name)));
  await writeFile(join(root,'manifest.json'),JSON.stringify(m)+'\n');return f;
}
test('sound links decode once, preserve plus, and reject path tricks',()=>{
  assert.equal(resourceKey('sound://a+b%20c.spx'),'a+b c.spx');assert.equal(resourceKey('sound://a%2520b.spx'),'a%20b.spx');
  for(const key of ['file://a','sound://../a','sound://%2e%2e/a','sound://C:/a','sound://%00','sound:////server/a'])assert.throws(()=>resourceKey(key));
});
test('actual DOM accents and positions are independent of filenames',()=>{
  const r=parseMapping({sourceOrdinal:0,indexKey:'sample',rawDecodedText:'<b>sample</b><a href="sound://misleading_Uk.spx"><img src="/snd_us.png"></a><script>fetch("https://invalid")</script>'});
  assert.equal(r.links[0].accent,'en-US');assert.equal(r.links[0].wordKey,'sample');assert.equal(r.links[0].evidence.sourceRange.unit,'UTF16_CODE_UNIT');
  assert.equal(parseMapping({sourceOrdinal:1,indexKey:'phrase',rawDecodedText:'<b>two words</b>'}).outcome,'OUT_OF_SCOPE');
  assert.equal(parseMapping({sourceOrdinal:1,indexKey:'x',rawDecodedText:'<b>x</b><a href="sound://x.spx"></a>'}).links[0].accent,null);
});
test('PCM signal checks handle silence and reject invalid samples',()=>{
  assert.deepEqual(signal(Buffer.alloc(8)),{samples:2,rms:0,peak:0});const b=Buffer.alloc(4);b.writeFloatLE(.5);assert.equal(signal(b).peak,.5);b.writeFloatLE(NaN);assert.throws(()=>signal(b));
});
test('process timeout waits for exit and subprocess failure remains failure',async()=>{
  await assert.rejects(run(process.execPath,['-e','setInterval(()=>{},1000)'],{timeoutMs:20}),/TIMEOUT/);
  await assert.rejects(run(process.execPath,['-e','process.exit(9)']),/FAILED/);
});
test('bundle independently checks identities, media, duplicate JSON and partial state',async()=>{
  const dir=await mkdtemp(join(tmpdir(),'readict-audio-test-'));
  try{
    const f=await fixture(dir);assert.equal((await validate(dir)).assetCount,1);
    await writeFile(join(dir,'state.json'),'{}');await assert.rejects(validate(dir),/PARTIAL/);await rm(join(dir,'state.json'));
    const manifest=await readFile(join(dir,'manifest.json'),'utf8');await writeFile(join(dir,'manifest.json'),manifest.replace('"diagnosticOnly":false','"diagnosticOnly":false,"diagnosticOnly":false'));await assert.rejects(validate(dir),/DUPLICATE/);await writeFile(join(dir,'manifest.json'),manifest);
    await writeFile(join(dir,f.asset.storageKey),'tampered');await assert.rejects(validate(dir),/MEDIA/);
  }finally{await rm(dir,{recursive:true,force:true});}
});
test('source-only affixes are out of scope and cannot become bare word audio',()=>{
  for(const word of ["'re","-'s",'-taker'])assert.equal(parseMapping({sourceOrdinal:0,indexKey:word,rawDecodedText:`<b>${word}</b>`}).outcome,'OUT_OF_SCOPE');
});
test('bounded batch and media headers reject invalid formats',async()=>{
  await assert.rejects(transcodeBatch({},new Array(129),''),/BATCH_LIMIT/);assert.throws(()=>speexInfo(Buffer.from('not ogg')));assert.throws(()=>mp3Info(Buffer.from('not mp3')));
  assert.deepEqual(mp3Info(Buffer.from([255,243,96,192])),{sampleRate:22050,channels:1});
});
test('subset opt-in permits accounted source silence but never an environment failure',async()=>{
  const dir=await mkdtemp(join(tmpdir(),'readict-audio-subset-'));
  try{
    const {manifest}=await subsetFixture(dir);await assert.rejects(validate(dir),/POLICY/);assert.equal((await validate(dir,false,true)).assetCount,1);
    const issue=JSON.parse(await readFile(join(dir,'issues.jsonl'),'utf8'));
    const update=async()=>{await writeFile(join(dir,'issues.jsonl'),JSON.stringify(issue)+'\n');Object.assign(manifest.files.find(f=>f.name==='issues.jsonl'),await fingerprint(join(dir,'issues.jsonl')));await writeFile(join(dir,'manifest.json'),JSON.stringify(manifest)+'\n');};
    issue.code='AUDIO_SOURCE_DECODE';await update();await assert.rejects(validate(dir,false,true),/DECODE_EVIDENCE/);
    issue.evidence={ruleId:'ffmpeg-strict-speex-v1',decoder:'speex',result:'INVALID_PACKET'};await update();assert.equal((await validate(dir,false,true)).assetCount,1);
    issue.code='AUDIO_PROCESS_TIMEOUT';await writeFile(join(dir,'issues.jsonl'),JSON.stringify(issue)+'\n');Object.assign(manifest.files.find(f=>f.name==='issues.jsonl'),await fingerprint(join(dir,'issues.jsonl')));await writeFile(join(dir,'manifest.json'),JSON.stringify(manifest)+'\n');await assert.rejects(validate(dir,false,true),/UNAPPROVED/);
  }finally{await rm(dir,{recursive:true,force:true});}
});

test('audio CLI rejects unused options and sanitizes filesystem errors',async()=>{
  const {spawn}=await import('node:child_process');
  const invoke=args=>new Promise(resolve=>{let stderr='';const child=spawn(process.execPath,['src/audio/cli.mjs',...args],{windowsHide:true});child.stderr.on('data',b=>stderr+=b);child.on('close',code=>resolve({code,stderr}));});
  const usage=await invoke(['inspect','--bundle','unused-secret-location']);assert.equal(usage.code,2);assert.equal(JSON.parse(usage.stderr).code,'AUDIO_USAGE');
  const missing=await invoke(['validate','--bundle','nonexistent-private-location']);assert.equal(missing.code,2);assert.equal(JSON.parse(missing.stderr).code,'AUDIO_INPUT_NOT_FOUND');assert.ok(!missing.stderr.includes('private-location'));
});

test('only strict Speex packet diagnostics classify a source decode defect',async()=>{
  const {confirmedSpeexDefect}=await import('../../src/audio/probe.mjs');
  const error=Object.assign(new Error('AUDIO_PROCESS_FAILED'),{exitCode:69,diagnostics:'[aist#0:0/speex @ 123] [dec:speex @ 456] Error submitting packet to decoder: Invalid data found when processing input'});
  assert.equal(confirmedSpeexDefect(error),true);
  assert.equal(confirmedSpeexDefect({...error,message:'AUDIO_PROCESS_TIMEOUT'}),false);
  assert.equal(confirmedSpeexDefect({...error,message:'AUDIO_PROCESS_FAILED',diagnostics:'No space left on device'}),false);
  assert.equal(confirmedSpeexDefect({...error,message:'AUDIO_PROCESS_FAILED',exitCode:0}),false);
});

test('bundle media cannot escape through a Windows junction or directory symlink',async()=>{
  const {symlink}=await import('node:fs/promises');
  const parent=await mkdtemp(join(tmpdir(),'readict-audio-path-'));const root=join(parent,'bundle'),outside=join(parent,'outside');
  try{
    const f=await fixture(root);await mkdir(outside);const prefix=f.asset.storageKey.split('/')[1];
    await writeFile(join(outside,f.asset.outputSha256+'.mp3'),await readFile(join(root,f.asset.storageKey)));
    await rm(join(root,'media',prefix),{recursive:true});await symlink(outside,join(root,'media',prefix),'junction');
    await assert.rejects(validate(root),/LINK_REJECTED|PATH_ESCAPE/);
  }finally{await rm(parent,{recursive:true,force:true});}
});

test('manifest uses strict booleans, integer source sizes and fixed encoding',async()=>{
  const root=await mkdtemp(join(tmpdir(),'readict-audio-schema-'));
  try{
    const {manifest}=await fixture(root);manifest.diagnosticOnly='false';await writeFile(join(root,'manifest.json'),JSON.stringify(manifest)+'\n');await assert.rejects(validate(root),/SCHEMA/);
    manifest.diagnosticOnly=false;manifest.sources[0].sizeBytes='10';await writeFile(join(root,'manifest.json'),JSON.stringify(manifest)+'\n');await assert.rejects(validate(root),/SOURCE_INVALID/);
    manifest.sources[0].sizeBytes=10;manifest.encoding.sampleRate=44100;await writeFile(join(root,'manifest.json'),JSON.stringify(manifest)+'\n');await assert.rejects(validate(root),/ENCODING_INVALID/);
  }finally{await rm(root,{recursive:true,force:true});}
});

test('PCM statistics retain little-endian values for an unaligned buffer view',()=>{
  const storage=Buffer.alloc(13);storage.writeFloatLE(1,1);storage.writeFloatLE(-2,5);storage.writeFloatLE(.5,9);
  assert.deepEqual(signal(storage.subarray(1)),{samples:3,rms:Math.sqrt(1.75),peak:2});
});
