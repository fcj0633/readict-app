import { spawn } from 'node:child_process';
import { readFile, writeFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { sha256 } from '../parser-adapter.mjs';

const children=new Set();let aborted=false;
export const cancelled=()=>aborted;
export function cancelProcesses(){aborted=true;for(const child of children)child.kill('SIGKILL');}

export function run(exe, args, { timeoutMs = 30000, maxBytes = 12 * 1024 * 1024,cwd } = {}) {
  return new Promise((resolve,reject) => {
    if(aborted){reject(new Error('AUDIO_CANCELLED'));return;}
    const child = spawn(exe,args,{shell:false,windowsHide:true,stdio:['ignore','pipe','pipe'],cwd});
    children.add(child);
    const chunks=[];const errors=[];let bytes=0,stderrBytes=0,reason=null;
    const stop=code=>{reason??=code;child.kill('SIGKILL');};
    const timer=setTimeout(()=>stop('AUDIO_PROCESS_TIMEOUT'),timeoutMs);
    child.stdout.on('data',b=>{bytes+=b.length;if(bytes>maxBytes)stop('AUDIO_PROCESS_OUTPUT_LIMIT');else chunks.push(b);});
    child.stderr.on('data',b=>{stderrBytes+=b.length;if(stderrBytes>65536)stop('AUDIO_PROCESS_LOG_LIMIT');else errors.push(b);});
    child.once('error',e=>{clearTimeout(timer);children.delete(child);reject(e);});
    child.once('close',code=>{clearTimeout(timer);children.delete(child);if(aborted||reason||code!==0){const error=new Error(aborted?'AUDIO_CANCELLED':reason??'AUDIO_PROCESS_FAILED');error.exitCode=code;error.diagnostics=Buffer.concat(errors).toString('utf8');reject(error);}else resolve({stdout:Buffer.concat(chunks),stderr:Buffer.concat(errors).toString('utf8')});});
  });
}
export function confirmedSpeexDefect(error){
  return error.message==='AUDIO_PROCESS_FAILED'&&Number.isInteger(error.exitCode)&&error.exitCode>0&&/\[dec:speex[^\]]*\].*(?:Error submitting packet to decoder|Error processing packet in decoder): Invalid data found when processing input/u.test(error.diagnostics??'');
}
async function strictSourceDefect(config,file){
  try{await run(config.ffmpeg,['-nostdin','-hide_banner','-v','error','-xerror','-err_detect','explode','-i',file,'-map','0:a:0','-vn','-f','null','-']);return false;}
  catch(error){return confirmedSpeexDefect(error);}
}
export function signal(bytes) {
  if (!bytes.length || bytes.length%4) throw new Error('AUDIO_PCM_INVALID');
  const view=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);
  let squares=0,peak=0;
  for(let i=0;i<bytes.length;i+=4){const x=view.getFloat32(i,true);if(!Number.isFinite(x))throw new Error('AUDIO_PCM_INVALID');squares+=x*x;peak=Math.max(peak,Math.abs(x));}
  return {samples:bytes.length/4,rms:Math.sqrt(squares/(bytes.length/4)),peak};
}
async function info(config,file) {
  const r=await run(config.ffprobe,['-v','error','-select_streams','a:0','-show_entries','stream=codec_name,sample_rate,channels,duration','-of','json',file]);
  const streams=JSON.parse(r.stdout).streams;
  if(streams?.length!==1)throw new Error('AUDIO_STREAM_INVALID');
  return streams[0];
}
async function pcm(config,file){return run(config.ffmpeg,['-nostdin','-hide_banner','-v','warning','-i',file,'-map','0:a:0','-vn','-ac','1','-ar','22050','-f','f32le','pipe:1'],{maxBytes:22050*4*60});}
export async function transcode(config, bytes, dir) {
  if(bytes.length>2*1024*1024)throw new Error('AUDIO_SOURCE_TOO_LARGE');
  const source=join(dir,'source.spx'),output=join(dir,'output.mp3');await writeFile(source,bytes);
  try {
    const before=await info(config,source);
    if(before.codec_name!=='speex'||Number(before.channels)!==1)throw new Error('AUDIO_SOURCE_FORMAT');
    const decoded=await pcm(config,source),a=signal(decoded.stdout);const duration=a.samples/22050;
    if(duration>60||a.rms<=1e-5||a.peak<=.001)throw new Error('AUDIO_SOURCE_SIGNAL');
    const encoded=await run(config.ffmpeg,['-nostdin','-hide_banner','-v','warning','-y','-i',source,'-map_metadata','-1','-vn','-ac','1','-ar','22050','-c:a','libmp3lame','-b:a','48k',output]);
    const after=await info(config,output),again=await pcm(config,output),b=signal(again.stdout);
    if(Number(after.sample_rate)!==22050||Number(after.channels)!==1||after.codec_name!=='mp3'||b.rms<=1e-5||b.peak<=.001||Math.abs(b.samples/22050-duration)>Math.max(.08,duration*.05))throw new Error('AUDIO_OUTPUT_QUALITY');
    const media=await readFile(output);
    return {media,outputSha256:sha256(media),durationMs:Math.round(b.samples/22.05),sourceSampleRate:Number(before.sample_rate),warning:!!(decoded.stderr||encoded.stderr||again.stderr)};
  } catch(error){
    if(['AUDIO_PROCESS_FAILED','AUDIO_PROCESS_LOG_LIMIT','AUDIO_PROCESS_OUTPUT_LIMIT'].includes(error.message)&&await strictSourceDefect(config,source)){
      const defect=new Error('AUDIO_SOURCE_DECODE');defect.sourceEvidence={ruleId:'ffmpeg-strict-speex-v1',decoder:'speex',result:'INVALID_PACKET'};throw defect;
    }
    throw error;
  } finally { await rm(source,{force:true});await rm(output,{force:true}); }
}

export function speexInfo(bytes){
  const at=bytes.indexOf(Buffer.from('Speex   '));
  if(bytes.length>2*1024*1024||bytes.subarray(0,4).toString()!=='OggS'||at<0||bytes.length<at+80||bytes.readInt32LE(at+48)!==1)throw new Error('AUDIO_SOURCE_FORMAT');
  return {sampleRate:bytes.readInt32LE(at+36)};
}
export function mp3Info(bytes){
  let at=0;
  if(bytes.subarray(0,3).toString()==='ID3'){if(bytes.length<10||bytes.subarray(6,10).some(x=>x>=128))throw new Error('AUDIO_MP3_HEADER');at=10+bytes[6]*2097152+bytes[7]*16384+bytes[8]*128+bytes[9];}
  if(at+4>bytes.length||bytes[at]!==255||(bytes[at+1]&0xe0)!==0xe0||((bytes[at+1]>>3)&3)!==2||((bytes[at+1]>>1)&3)!==1)throw new Error('AUDIO_MP3_HEADER');
  return {sampleRate:[22050,24000,16000][(bytes[at+2]>>2)&3],channels:(bytes[at+3]>>6)===3?1:2};
}

/** Two bounded FFmpeg processes amortize startup without concatenating recordings.
 * Each input has explicit, independent outputs. Batch failure falls back to the
 * one-resource ffprobe path, retaining precise errors and the 30-second bound.
 */
export async function transcodeBatch(config,inputs,dir,retainMedia=false){
  if(inputs.length<1||inputs.length>128)throw new Error('AUDIO_BATCH_LIMIT');
  const result=new Array(inputs.length),valid=[];
  for(let i=0;i<inputs.length;i++)try{const sourceInfo=speexInfo(inputs[i]);await writeFile(join(dir,`${i}.spx`),inputs[i]);valid.push({i,...sourceInfo});}catch(e){result[i]={error:e.message};}
  if(!valid.length)return result;
  const first=['-nostdin','-hide_banner','-v','warning','-y','-filter_threads','1'];
  for(const {i} of valid)first.push('-threads','1','-i',`${i}.spx`);
  valid.forEach(({i},index)=>first.push('-map',`${index}:a:0`,'-vn','-ac','1','-ar','22050','-f','f32le','-fs','5292004',`${i}.source.pcm`,'-map',`${index}:a:0`,'-map_metadata','-1','-vn','-ac','1','-ar','22050','-c:a','libmp3lame','-b:a','48k','-threads','1','-fs','2097153',`${i}.mp3`));
  try{
    const encoded=await run(config.ffmpeg,first,{cwd:dir});
    const second=['-nostdin','-hide_banner','-v','warning','-y','-filter_threads','1'];
    for(const {i} of valid)second.push('-threads','1','-i',`${i}.mp3`);
    valid.forEach(({i},index)=>second.push('-map',`${index}:a:0`,'-vn','-ac','1','-ar','22050','-f','f32le','-fs','5299044',`${i}.target.pcm`));
    await run(config.ffmpeg,second,{cwd:dir});
    for(const {i,sampleRate} of valid)try{
      const source=signal(await readFile(join(dir,`${i}.source.pcm`))),target=signal(await readFile(join(dir,`${i}.target.pcm`)));
      if(source.samples/22050>60||source.rms<=1e-5||source.peak<=.001)throw new Error('AUDIO_SOURCE_SIGNAL');
      const media=await readFile(join(dir,`${i}.mp3`)),format=mp3Info(media);
      if(media.length>2097152||format.sampleRate!==22050||format.channels!==1||target.rms<=1e-5||target.peak<=.001||Math.abs(target.samples/22050-source.samples/22050)>Math.max(.08,source.samples/22050*.05))throw new Error('AUDIO_OUTPUT_QUALITY');
      result[i]={media,outputSha256:sha256(media),durationMs:Math.round(target.samples/22.05),sourceSampleRate:sampleRate,warning:!!encoded.stderr,...(retainMedia?{mediaPath:join(dir,`${i}.mp3`)}:{})};
    }catch(e){result[i]={error:e.message};}
  }catch{
    if(aborted)throw new Error('AUDIO_CANCELLED');
    for(const {i} of valid)try{result[i]=await transcode(config,inputs[i],dir);}catch(e){result[i]={error:e.message,...(e.sourceEvidence?{evidence:e.sourceEvidence}:{})};}
  }finally{
    for(let i=0;i<inputs.length;i++)for(const suffix of ['spx','source.pcm','mp3','target.pcm'])if(!(suffix==='mp3'&&result[i]?.mediaPath))await rm(join(dir,`${i}.${suffix}`),{force:true});
  }
  return result;
}
