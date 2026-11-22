import { parseArgs } from 'node:util';
import { configFile, inspect } from './inspect.mjs';
import { cancelProcesses } from './probe.mjs';

process.once('SIGINT',cancelProcesses);process.once('SIGTERM',cancelProcesses);

try {
  const {values,positionals}=parseArgs({options:{config:{type:'string'},out:{type:'string'},inspection:{type:'string'},bundle:{type:'string'},resume:{type:'boolean'},'allow-local-subset':{type:'boolean'}},allowPositionals:true,strict:true});
  if(positionals.length!==1)throw new Error('AUDIO_USAGE');
  const command=positionals[0];let result;
  const allowed={inspect:['config','out'],convert:['config','out','inspection','resume','allow-local-subset'],validate:['bundle','allow-local-subset']}[command];
  if(!allowed||Object.keys(values).some(k=>!allowed.includes(k)))throw new Error('AUDIO_USAGE');
  if(command==='inspect'&&values.config&&values.out)result=await inspect(await configFile(values.config),values.out);
  else if(command==='convert'&&values.config&&values.out&&values.inspection){const {convert}=await import('./convert.mjs');result=await convert(await configFile(values.config),values.inspection,values.out,!!values.resume,!!values['allow-local-subset']);}
  else if(command==='validate'&&values.bundle){const {validate}=await import('./validate.mjs');result=await validate(values.bundle,false,!!values['allow-local-subset']);}
  else throw new Error('AUDIO_USAGE');
  console.log(JSON.stringify(result,null,2));
}catch(e){const code=/^[A-Z][A-Z0-9_]{0,127}$/u.test(e.message??'')?e.message:({ENOENT:'AUDIO_INPUT_NOT_FOUND',EACCES:'AUDIO_ACCESS_DENIED',EPERM:'AUDIO_ACCESS_DENIED'}[e.code]??'AUDIO_FAILED');console.error(JSON.stringify({code}));process.exitCode=/QUALITY|SIGNAL|EXCLUSION_REQUIRED|POLICY_NOT_ALLOWED/u.test(code)?3:2;}
