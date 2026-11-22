import { mkdir, readFile, writeFile, appendFile, rename, rm, stat, truncate,copyFile,realpath,lstat,readdir } from 'node:fs/promises';
import { join,sep } from 'node:path';
import { performance } from 'node:perf_hooks';
import { ParserAdapter, fingerprint, sha256, parserVersion } from '../parser-adapter.mjs';
import { uuid8 } from '../identity.mjs';
import { rows, strictJson } from '../bundle.mjs';
import { absent, sources, hash, writer, audioConverterVersion } from './inspect.mjs';
import { run, transcodeBatch, cancelled } from './probe.mjs';
import {quarantinable,subsetPolicy,fullPolicy,approvalRef} from './policy.mjs';
import {safeFile} from './validate.mjs';

export const encoding={sampleRate:22050,channels:1,bitRate:48000,codec:'libmp3lame',metadata:'removed'};
export async function convert(config,inspection,out,resume,allowLocalSubset=false){
  await absent(out);const start=performance.now();const tmp=out+'.partial';
  const evidence=strictJson(await readFile(join(inspection,'inspection.json'),'utf8'));
  if(evidence.mappingRulesVersion!=='sound-map-v3')throw new Error('AUDIO_INSPECTION_RULES');
  const before=await sources(config);if(JSON.stringify(evidence.sources)!==JSON.stringify(before))throw new Error('SOURCE_CHANGED');
  const tool=await fingerprint(config.ffmpeg),probe=await fingerprint(config.ffprobe);
  const version=(await run(config.ffmpeg,['-version'])).stdout.toString().split('\n')[0];
  const decoders=(await run(config.ffmpeg,['-decoders'])).stdout.toString(),encoders=(await run(config.ffmpeg,['-encoders'])).stdout.toString();
  if(!decoders.includes('speex')||!encoders.includes('libmp3lame'))throw new Error('AUDIO_TOOL_CAPABILITY');
  const converterVersion=audioConverterVersion+(allowLocalSubset?'/local-subset-v1':'/full-v1');
  const tuple=['audio-dataset-v1',evidence.sourceIdentitySha256,converterVersion,parserVersion,tool.sha256,hash(encoding),'col-normalize-v1'];
  const id=uuid8(tuple);const datasetId=id.id;
  if(!resume)await mkdir(tmp);else if(!(await stat(tmp)).isDirectory())throw new Error('RESUME_NOT_FOUND');
  if((await lstat(tmp)).isSymbolicLink())throw new Error('AUDIO_PATH_ESCAPE');const root=await realpath(tmp);
  await mkdir(join(tmp,'media'),{recursive:true});await mkdir(join(tmp,'work'),{recursive:true});
  await safeFile(root,'work');await safeFile(root,'media');
  for(let i=0;i<256;i++){const part=i.toString(16).padStart(2,'0');await mkdir(join(tmp,'media',part),{recursive:true});await safeFile(root,'media/'+part);}
  const checkpoint=join(tmp,'checkpoint.jsonl');const stateFile=join(tmp,'state.json');
  const state={datasetId,identitySha256:id.fullHash,tuple,stateVersion:2,ffprobeSha256:probe.sha256};
  const completed=new Map();
  if(resume){
    try{await stat(stateFile);}catch(e){if(e.code!=='ENOENT')throw e;const previous=strictJson(await readFile(join(tmp,'manifest.json'),'utf8'));if(previous.datasetId!==datasetId||JSON.stringify(previous.identityTuple)!==JSON.stringify(tuple))throw new Error('AUDIO_RESUME_IDENTITY');await writeFile(stateFile,JSON.stringify(state)+'\n',{flag:'wx'});}
    const savedState=strictJson(await readFile(await safeFile(root,'state.json'),'utf8'));
    if(savedState.stateVersion===undefined){
      // Upgrade only a completed legacy scan whose captured tool/source evidence still matches.
      let previous;try{previous=strictJson(await readFile(await safeFile(root,'manifest.json'),'utf8'));}catch{throw new Error('AUDIO_RESUME_TOOL_UNBOUND');}
      if(savedState.datasetId!==datasetId||JSON.stringify(savedState.tuple)!==JSON.stringify(tuple)||previous.datasetId!==datasetId||previous.ffprobe?.sha256!==probe.sha256||previous.ffmpeg?.sha256!==tool.sha256||JSON.stringify(previous.sources)!==JSON.stringify(before))throw new Error('AUDIO_RESUME_TOOL_CHANGED');
      const upgrade=stateFile+'.upgrade';try{await writeFile(upgrade,JSON.stringify(state)+'\n',{flag:'wx'});}catch(e){if(e.code!=='EEXIST'||JSON.stringify(strictJson(await readFile(await safeFile(root,'state.json.upgrade'),'utf8')))!==JSON.stringify(state))throw e;}
      await rename(upgrade,stateFile);
    }else if(JSON.stringify(savedState)!==JSON.stringify(state))throw new Error('AUDIO_RESUME_IDENTITY');
    try{await stat(checkpoint);}catch(e){if(e.code!=='ENOENT')throw e;await copyFile(out+'.checkpoint.jsonl',checkpoint);}
    const data=await readFile(checkpoint);const end=data.lastIndexOf(10)+1;if(end!==data.length)await truncate(checkpoint,end);
    for await(const row of rows(checkpoint)){
      if(completed.has(row.sourceOrdinal))throw new Error('AUDIO_CHECKPOINT_DUPLICATE');
      if(row.asset){const f=await fingerprint(await safeFile(root,row.asset.storageKey));if(f.sha256!==row.asset.outputSha256||f.sizeBytes!==row.asset.bytes)throw new Error('AUDIO_CHECKPOINT_MEDIA_CHANGED');}
      completed.set(row.sourceOrdinal,row);
    }
  }else{await writeFile(stateFile,JSON.stringify(state)+'\n',{flag:'wx'});await writeFile(checkpoint,'',{flag:'wx'});}
  const p=new ParserAdapter(config.mdd,true);let cursor=0,processed=0,journal=Promise.resolve();
  if(resume){
    let retry=false;
    for(const [ordinal,row]of completed){
      if(!Number.isSafeInteger(ordinal)||ordinal<0||ordinal>=p.keys.length)throw new Error('AUDIO_CHECKPOINT_ORDINAL');
      const key=p.keys[ordinal],bytes=p.readBytes(key);
      if(row.asset&&(row.asset.sourceResourceKey!==key.keyText||row.asset.sourceSha256!==sha256(bytes)||row.asset.audioId!==uuid8(['audio-asset-v1',datasetId,key.keyText,row.asset.sourceSha256,row.asset.outputSha256]).id))throw new Error('AUDIO_CHECKPOINT_SOURCE_CHANGED');
      if(row.issue?.retryable||['AUDIO_PROCESS_LOG_LIMIT','AUDIO_PROCESS_OUTPUT_LIMIT'].includes(row.issue?.code)){completed.delete(ordinal);retry=true;}
      else if(row.issue&&row.issue.sourceSha256!==null&&row.issue.sourceSha256!==sha256(bytes))throw new Error('AUDIO_CHECKPOINT_SOURCE_CHANGED');
    }
    if(retry)await writeFile(checkpoint,[...completed.values()].map(r=>JSON.stringify(r)+'\n').join(''));
  }
  async function worker(workerId){
    const work=join(tmp,'work',String(workerId));await mkdir(work,{recursive:true});
    while(cursor<p.keys.length){
      if(cancelled())throw new Error('AUDIO_CANCELLED');
      const jobs=[];
      while(jobs.length<128&&cursor<p.keys.length){
        const sourceOrdinal=cursor++,key=p.keys[sourceOrdinal];if(!/\.spx$/iu.test(key.keyText)||completed.has(sourceOrdinal))continue;
        try{jobs.push({sourceOrdinal,key,bytes:p.readBytes(key)});}catch(e){jobs.push({sourceOrdinal,key,error:e.message});}
      }
      const valid=jobs.filter(j=>!j.error);
      const outputs=valid.length?await transcodeBatch(config,valid.map(j=>j.bytes),work,true):[];
      valid.forEach((j,i)=>j.converted=outputs[i]);
      const batchRows=await Promise.all(jobs.map(async job=>{
        if(cancelled())throw new Error('AUDIO_CANCELLED');
        const {sourceOrdinal,key}=job;let row;
        try{
          if(job.error||job.converted.error){const e=new Error(job.error??job.converted.error);if(job.converted?.evidence)e.sourceEvidence=job.converted.evidence;throw e;}
          const sourceSha256=sha256(job.bytes),converted=job.converted;
          const audioId=uuid8(['audio-asset-v1',datasetId,key.keyText,sourceSha256,converted.outputSha256]).id;
          const storageKey=`media/${converted.outputSha256.slice(0,2)}/${converted.outputSha256}.mp3`;
          const target=join(tmp,storageKey),staged=converted.mediaPath??target+'.'+workerId+'.tmp';if(!converted.mediaPath)await writeFile(staged,converted.media);try{await rename(staged,target);}catch(e){if((await fingerprint(target)).sha256!==converted.outputSha256)throw e;await rm(staged,{force:true});}
          row={sourceOrdinal,asset:{audioId,sourceOrdinal,sourceResourceKey:key.keyText,sourceSha256,outputSha256:converted.outputSha256,storageKey,bytes:converted.media.length,mime:'audio/mpeg',sampleRate:22050,channels:1,durationMs:converted.durationMs},sourceSampleRate:converted.sourceSampleRate,warning:converted.warning};
        }catch(e){row={sourceOrdinal,issue:{stage:'CONVERSION',sourceOrdinal,resourceKey:key.keyText,sourceSha256:job.bytes?sha256(job.bytes):null,sizeBytes:job.bytes?.length??null,code:e.message,severity:'ERROR',disposition:allowLocalSubset&&quarantinable.has(e.message)?'QUARANTINED':'BLOCKED',retryable:/TIMEOUT|PROCESS_FAILED|PROCESS_LOG_LIMIT|PROCESS_OUTPUT_LIMIT/u.test(e.message),...(e.sourceEvidence?{evidence:e.sourceEvidence}:{})}};}
        return row;
      }));
      journal=journal.then(()=>appendFile(checkpoint,batchRows.map(r=>JSON.stringify(r)+'\n').join('')));await journal;
      for(const row of batchRows){completed.set(row.sourceOrdinal,row);processed++;
        if(processed%1000===0)console.error(JSON.stringify({stage:'CONVERSION',processed,total:evidence.counts.audioCandidates,elapsedMs:Math.round(performance.now()-start)}));
      }
    }
  }
  const workers=await Promise.allSettled([worker(0),worker(1)]);p.close();p.keys=[];p.dictionary=null;
  const rejected=workers.find(r=>r.status==='rejected');if(rejected)throw rejected.reason;

  if(JSON.stringify(before)!==JSON.stringify(await sources(config)))throw new Error('SOURCE_CHANGED');
  if((await fingerprint(config.ffmpeg)).sha256!==tool.sha256||(await fingerprint(config.ffprobe)).sha256!==probe.sha256)throw new Error('AUDIO_TOOL_CHANGED');
  if(completed.size!==evidence.counts.audioCandidates)throw new Error('AUDIO_CANDIDATE_COVERAGE');
  // Re-emission is safe after an interruption in any metadata/publish phase.
  for(const name of ['assets.jsonl','mappings.jsonl','issues.jsonl','quality.json','dispositions.jsonl','manifest.json']){try{const file=await safeFile(root,name);if((await stat(file)).isFile())await rm(file);}catch(e){if(e.code!=='ENOENT')throw e;}}
  for(let i=0;i<256;i++){const part=i.toString(16).padStart(2,'0');for(const name of await readdir(join(tmp,'media',part)))if(/^[0-9a-f]{64}\.[01]\.tmp$/u.test(name))await rm(await safeFile(root,`media/${part}/${name}`));}
  const assets=await writer(join(tmp,'assets.jsonl')),mappings=await writer(join(tmp,'mappings.jsonl')),issues=await writer(join(tmp,'issues.jsonl'));
  let assetCount=0,mappingCount=0,failed=0,mappingErrors=0,mappingQuarantined=0,quarantinedCount=0,blockingErrors=0,warnings=0;const byOrdinal=new Map();
  for(const row of [...completed.values()].sort((a,b)=>a.sourceOrdinal-b.sourceOrdinal)){
    if(row.asset){await assets.put(row.asset);byOrdinal.set(row.sourceOrdinal,row.asset);assetCount++;if(row.warning)warnings++;}else{await issues.put(row.issue);failed++;if(row.issue.disposition==='QUARANTINED')quarantinedCount++;else blockingErrors++;}
  }
  for await(const issue of rows(join(inspection,'issues.jsonl'))){await issues.put(issue);if(issue.severity==='ERROR')mappingErrors++;}
  for await(const link of rows(join(inspection,'links.jsonl'))){
    if(link.status!=='RESOLVED')continue;
    const asset=link.resource.role==='mdd'?byOrdinal.get(link.resource.sourceOrdinal):null;
    if(!asset){await issues.put({stage:'MAPPING',sourceOrdinal:link.sourceOrdinal,linkOrder:link.linkOrder,resourceOrdinal:link.resource.sourceOrdinal,resourceKey:link.resource.sourceResourceKey,code:'AUDIO_RESOURCE_QUARANTINED',severity:'WARNING',disposition:'QUARANTINED',retryable:false});mappingQuarantined++;continue;}
    await mappings.put({sourceOrdinal:link.sourceOrdinal,linkOrder:link.linkOrder,indexKey:link.indexKey,displayHeadword:link.displayHeadword,wordKey:link.wordKey,normalizeVersion:'col-normalize-v1',accent:link.accent,audioId:asset.audioId,evidence:link.evidence});mappingCount++;
  }
  await assets.close();await mappings.close();await issues.close();
  blockingErrors+=mappingErrors;
  const counts={...evidence.counts,assetCount,mappingCount,conversionFailed:failed,quarantinedCount,blockingErrors,mappingErrors,mappingQuarantined,warnings};
  const technicalEligible=assetCount>0&&blockingErrors===0&&mappingErrors===0&&evidence.counts.unknownResources===0&&evidence.counts.sourceFailed===0&&evidence.counts.linksMissing===0&&evidence.counts.linksInvalid===0;
  const durations=[...byOrdinal.values()].map(a=>a.durationMs).sort((a,b)=>a-b);const sourceRates={};for(const r of completed.values())if(r.asset)sourceRates[r.sourceSampleRate]=(sourceRates[r.sourceSampleRate]??0)+1;
  await writeFile(join(tmp,'quality.json'),JSON.stringify({durationStats:durations.length?{min:durations[0],p50:durations[Math.ceil(durations.length*.5)-1],p95:durations[Math.ceil(durations.length*.95)-1],max:durations.at(-1)}:null,decodeStats:{sourceSampleRates:sourceRates},schemaVersion:'readict-audio-quality-1',counts,technicalEligible,humanReview:'NOT_PERFORMED',rightsReview:'UNVERIFIED',releaseEligible:false})+'\n');
  await copyFile(join(inspection,'dispositions.jsonl'),join(tmp,'dispositions.jsonl'));
  const files=[];
  for(const name of ['assets.jsonl','mappings.jsonl','issues.jsonl','quality.json','dispositions.jsonl'])files.push({name,...await fingerprint(join(tmp,name))});
  const manifest={schemaVersion:'readict-audio-bundle-1',diagnosticOnly:false,usageProfile:allowLocalSubset?'LOCAL_MVP':'FULL',adoptionPolicy:allowLocalSubset?subsetPolicy:fullPolicy,approvalRef:allowLocalSubset?approvalRef:null,datasetId,version:'audio-v1/'+datasetId,identitySha256:id.fullHash,identityTuple:tuple,sources:before,resourceCounts:evidence.resourceCounts,nonAudioResources:evidence.nonAudioResources,converterVersion,parserVersion,ffmpeg:{...tool,version},ffprobe:probe,encoding,normalizeVersion:'col-normalize-v1',files,counts,technicalEligible,completeSourceEligible:failed===0,releaseEligible:false,rights:{status:'UNVERIFIED',snapshotAllowed:false,distributionAllowed:false}};
  await writeFile(join(tmp,'manifest.json'),JSON.stringify(manifest)+'\n');completed.clear();byOrdinal.clear();const work=await safeFile(root,'work');if(!(await realpath(work)).startsWith(root+sep))throw new Error('AUDIO_PATH_ESCAPE');await rm(work,{recursive:true});
  // Failed full scans stay inspectable but are never published or importable.
  if(!technicalEligible)throw new Error('AUDIO_EXCLUSION_REQUIRED');
  if(process.resourceUsage().maxRSS*1024>1024**3)throw new Error('AUDIO_RSS_BUDGET');
  const {validate}=await import('./validate.mjs');await validate(tmp,true,allowLocalSubset);
  await rename(checkpoint,out+'.checkpoint.jsonl');await rm(stateFile);await rename(tmp,out);
  return {datasetId,counts,durationMs:Math.round(performance.now()-start),peakRssBytes:process.resourceUsage().maxRSS*1024};
}
