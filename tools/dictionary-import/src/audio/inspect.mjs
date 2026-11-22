import { mkdir, readFile, writeFile, rename, stat } from 'node:fs/promises';
import { createWriteStream } from 'node:fs';
import { once } from 'node:events';
import { join } from 'node:path';
import { ParserAdapter, fingerprint, sha256, parserVersion } from '../parser-adapter.mjs';
import { strictJson } from '../bundle.mjs';
import { parseMapping, literalResourceKey } from './mapping.mjs';

export const audioConverterVersion='readict-audio-converter-4';
export const hash=v=>sha256(JSON.stringify(v));
export async function configFile(path){const c=strictJson(await readFile(path,'utf8'));for(const k of ['mdx','mdd','icons','ffmpeg','ffprobe'])if(typeof c[k]!=='string'||!c[k])throw new Error('AUDIO_CONFIG_INVALID');return c;}
export async function sources(config){const out=[];for(const role of ['icons','mdd','mdx'])out.push({role,...await fingerprint(config[role])});return out;}
export async function writer(path){const s=createWriteStream(path,{flags:'wx'});return {async put(v){if(!s.write(JSON.stringify(v)+'\n'))await once(s,'drain');},async close(){s.end();await once(s,'finish');}};}
export async function absent(path){try{await stat(path);throw new Error('OUTPUT_EXISTS');}catch(e){if(e.code!=='ENOENT')throw e;}}
export async function inspect(config,out){
  await absent(out);const tmp=out+'.partial';await mkdir(tmp);const before=await sources(config);
  const resources=await writer(join(tmp,'resources.jsonl')),links=await writer(join(tmp,'links.jsonl')),issues=await writer(join(tmp,'issues.jsonl')),dispositions=await writer(join(tmp,'dispositions.jsonl'));
  const resourceIndex=new Map(),foldedIndex=new Map();const headers={},resourceCounts={},nonAudioResources=[];const counts={mdxTotal:0,mddTotal:0,audioCandidates:0,nonAudio:0,unknownResources:0,sourceMapped:0,sourceOutOfScope:0,sourceNoAudio:0,sourceFailed:0,linksTotal:0,linksResolved:0,linksMissing:0,linksInvalid:0,linksOutOfScope:0};
  for(const role of ['mdd','icons']){
    const p=new ParserAdapter(config[role],true);
    headers[role]={KeyCaseSensitive:p.dictionary.header.KeyCaseSensitive,StripKey:p.dictionary.header.StripKey};
    resourceCounts[role]=p.keys.length;
    try{for(let i=0;i<p.keys.length;i++){
      const key=p.keys[i].keyText;const kind=/\.spx$/iu.test(key)?'AUDIO':/\.png$/iu.test(key)?'IMAGE':'UNKNOWN';
      counts.mddTotal++;counts[kind==='AUDIO'?'audioCandidates':kind==='IMAGE'?'nonAudio':'unknownResources']++;
      const row={role,sourceOrdinal:i,sourceResourceKey:key,kind};await resources.put(row);
      if(kind!=='AUDIO')nonAudioResources.push(row);
      const old=resourceIndex.get(key)??[];old.push(row);resourceIndex.set(key,old);
      if(p.dictionary.header.KeyCaseSensitive==='No'){const fold=key.replace(/[A-Z]/g,c=>c.toLowerCase());const oldFold=foldedIndex.get(fold)??[];oldFold.push(row);foldedIndex.set(fold,oldFold);}
    }}finally{p.close();}
  }
  const p=new ParserAdapter(config.mdx);
  try{for await(const record of p.enumerate()){
    counts.mdxTotal++;const parsed=parseMapping(record);counts[{MAPPED:'sourceMapped',OUT_OF_SCOPE:'sourceOutOfScope',NO_AUDIO:'sourceNoAudio',FAIL:'sourceFailed'}[parsed.outcome]]++;
    const disposition={sourceOrdinal:record.sourceOrdinal,outcome:parsed.outcome,linkCount:parsed.links.length,resolvedCount:0,missingCount:0,invalidCount:0,outOfScopeCount:0};
    for(const issue of parsed.issues.filter(x=>x.code==='AUDIO_HEADWORD_UNKNOWN'))await issues.put({stage:'MAPPING',sourceOrdinal:record.sourceOrdinal,code:issue.code,linkOrder:null,severity:'ERROR',retryable:false});
    for(const link of parsed.links){
      counts.linksTotal++;let matchRule='EXACT';
      // A few source links are literal MDD keys, not valid URI encoding.
      // Accept only an exact existing container key; never repair arbitrary input.
      if(!link.target){try{const raw=literalResourceKey(link.evidence.href.slice(8));if(resourceIndex.has('\\'+raw)||resourceIndex.has(raw)){link.target=raw;matchRule='EXACT_SOURCE_LITERAL';}}catch{/* remains invalid */}}
      let targets=link.target?resourceIndex.get(link.target)??resourceIndex.get('\\'+link.target)??[]:[];
      if(link.target){const folded=foldedIndex.get(('\\'+link.target).replace(/[A-Z]/g,c=>c.toLowerCase()))??[];if(folded.length>1||!targets.length){targets=folded;if(targets.length)matchRule='SOURCE_HEADER_CASE_INSENSITIVE';}}
      link.evidence.matchRule=matchRule;
      let status=!link.target||!link.accent||!link.displayHeadword?'INVALID':targets.length===0?'MISSING':targets.length!==1||targets[0].kind!=='AUDIO'?'INVALID':!link.wordKey?'OUT_OF_SCOPE':'RESOLVED';
      counts[{INVALID:'linksInvalid',MISSING:'linksMissing',OUT_OF_SCOPE:'linksOutOfScope',RESOLVED:'linksResolved'}[status]]++;
      disposition[{INVALID:'invalidCount',MISSING:'missingCount',OUT_OF_SCOPE:'outOfScopeCount',RESOLVED:'resolvedCount'}[status]]++;
      await links.put({...link,status,resource:targets.length===1?targets[0]:null});
      if(status==='INVALID'||status==='MISSING')await issues.put({stage:'MAPPING',sourceOrdinal:record.sourceOrdinal,linkOrder:link.linkOrder,code:'AUDIO_LINK_'+status,severity:'ERROR',retryable:false});
    }
    await dispositions.put(disposition);
  }}finally{p.close();await resources.close();await links.close();await issues.close();await dispositions.close();}
  if(JSON.stringify(before)!==JSON.stringify(await sources(config)))throw new Error('SOURCE_CHANGED');
  const result={schemaVersion:'readict-audio-inspection-1',mappingRulesVersion:'sound-map-v3',sources:before,sourceIdentitySha256:hash(before.map(s=>[s.role,s.sizeBytes,s.sha256])),parserVersion,headers,resourceCounts,nonAudioResources,counts};
  await writeFile(join(tmp,'inspection.json'),JSON.stringify(result)+'\n');await rename(tmp,out);return result;
}
