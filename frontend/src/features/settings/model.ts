import { ApiError } from '../../lib/api';
import { request, object, invalid } from '../../lib/http';
export type Preferences = { font: string; fontSize: number; lineHeight: number; width: number; theme: string; pronunciation: string };
export type View = { preferences: Preferences; version: number };
export function decode(value: unknown): View {
  if (!object(value) || !object(value.preferences) || !Number.isSafeInteger(value.version) || Number(value.version) < 1) return invalid();
  const p = value.preferences;
  if (!['Georgia', 'Palatino Linotype', 'Arial'].includes(String(p.font)) || ![18, 20, 22, 24].includes(Number(p.fontSize))
    || ![1.6, 1.8, 2].includes(Number(p.lineHeight)) || ![660, 720].includes(Number(p.width)) || !['white', 'warm', 'dark'].includes(String(p.theme)) || !['en-GB', 'en-US'].includes(String(p.pronunciation))
    || typeof p.font !== 'string' || typeof p.fontSize !== 'number' || typeof p.lineHeight !== 'number' || typeof p.width !== 'number' || typeof p.theme !== 'string' || typeof p.pronunciation !== 'string') return invalid();
  return { preferences: { font: p.font, fontSize: p.fontSize, lineHeight: p.lineHeight, width: p.width, theme: p.theme, pronunciation: p.pronunciation }, version: Number(value.version) };
}
export const settingsApi = {
  get: (signal?: AbortSignal) => request('/me/settings', decode, { signal }),
  save: (body: { preferences: Preferences; expectedVersion: number; requestId: string }, signal?: AbortSignal) => request('/me/settings', decode, { method: 'PUT', body, signal }),
};
export type SaveState = { draft?: Preferences; version?: number; committed?: View; status: 'loading' | 'saved' | 'saving' | 'unsynced' | 'conflict'; error?: string; server?: View };
const same = (a?: Preferences, b?: Preferences) => JSON.stringify(a) === JSON.stringify(b);
/** One user-scoped writer. Unknown responses retain the exact payload and request identity. */
export class PreferenceSaver {
 state: SaveState = { status:'loading' };
 private timer?:ReturnType<typeof setTimeout>;
 private pending?:{preferences:Preferences;expectedVersion:number;requestId:string};
 private disposed=false; private dirty=false; private generation=0; private writeEpoch=0;
 private flight?:Promise<void>; private controller=new AbortController();
 constructor(private emit:(state:SaveState)=>void,private api=settingsApi,private changed:(view:View)=>void=()=>{}){}
 private conflicted(){return this.state.status==='conflict';}
 private update(state:SaveState){if(this.disposed)return;this.state=state;this.emit(state);}
 async load():Promise<void>{
   const current=++this.generation;
   if(this.flight){await this.flight;if(!this.disposed&&current===this.generation)return this.load();return;}
   const epoch=this.writeEpoch;
   try{const value=await this.api.get(this.controller.signal);if(this.disposed||current!==this.generation)return;
     if(epoch!==this.writeEpoch){await this.load();return;}
     if(value.version<(this.state.committed?.version??0))return;
     if(this.dirty||this.pending){
       const applied=this.pending&&value.version===this.pending.expectedVersion+1&&same(value.preferences,this.pending.preferences);
       if(value.version===this.state.version||applied)this.update({...this.state,committed:value});
       else this.update({...this.state,committed:value,status:'conflict',server:value,error:'设置已变化，请选择如何处理当前草稿。'});
     }else this.update({draft:{...value.preferences},version:value.version,committed:value,status:'saved'});
   }catch(error){if(!this.disposed&&current===this.generation)this.update({...this.state,status:this.state.status==='conflict'?'conflict':'unsynced',error:error instanceof Error?error.message:'暂时无法读取设置'});}
 }
 edit(draft:Preferences){
   if(!this.state.version||!this.state.draft||this.disposed)return;
   this.dirty=true;this.update({...this.state,draft:{...draft},status:this.state.status==='conflict'?'conflict':this.flight?'saving':'unsynced'});
   clearTimeout(this.timer);if(this.state.status!=='conflict')this.timer=setTimeout(()=>void this.flush(),500);
 }
 flush():Promise<void>{
   if(this.flight)return this.flight;
   if(this.disposed||this.state.status==='conflict'||!this.state.draft||!this.state.version||(!this.dirty&&!this.pending))return Promise.resolve();
   clearTimeout(this.timer);
   this.pending??={preferences:{...this.state.draft},expectedVersion:this.state.version,requestId:crypto.randomUUID()};
   const current=this.pending;this.writeEpoch++;this.update({...this.state,status:'saving',error:undefined});
   this.flight=this.save(current).finally(()=>{this.flight=undefined;});
   return this.flight;
 }
 private async save(current:NonNullable<PreferenceSaver['pending']>){
   try{const value=await this.api.save(current,this.controller.signal);if(this.disposed)return;
     this.writeEpoch++;this.pending=undefined;const unchanged=same(this.state.draft,current.preferences);this.dirty=!unchanged;
     const server=this.state.server;
     if(server&&server.version>value.version){
       this.update({...this.state,version:value.version,committed:server,status:'conflict',server});
     }else{
       this.update({...this.state,version:value.version,committed:value,server:undefined,status:unchanged?'saved':'unsynced',error:undefined});
       if(!unchanged)this.timer=setTimeout(()=>void this.flush(),500);
     }
     this.changed(value);
   }catch(error){if(this.disposed)return;this.writeEpoch++;
     if(error instanceof ApiError&&error.status===409){
       this.update({...this.state,status:'conflict',server:undefined,error:'设置已在其他页面更新。当前草稿已保留。'});
       try{const server=await this.api.get(this.controller.signal);if(!this.disposed)this.update({...this.state,status:'conflict',server,committed:server});}catch{/* Explicit reload is required before choosing a version. */}
     }else this.update({...this.state,status:'unsynced',error:'尚未同步。请检查连接后重试。'});
   }
 }
 resolve(keepDraft:boolean){
   if(!this.state.server){void this.load();return;}
   const server=this.state.server;this.pending=undefined;this.dirty=keepDraft;this.generation++;
   this.update({draft:keepDraft?this.state.draft:{...server.preferences},version:server.version,committed:server,status:keepDraft?'unsynced':'saved'});
   if(keepDraft)void this.flush();
 }
 retry(){if(this.state.status==='conflict'||!this.state.draft||(!this.dirty&&!this.pending))void this.load();else void this.flush();}
 async flushAll():Promise<void>{
   const deadline=Date.now()+10000;
   while(this.state.status!=='saved'){
     if(this.state.status==='conflict'||!this.state.version)throw new Error('请先处理设置冲突或重新读取设置');
     let timer:ReturnType<typeof setTimeout>|undefined;
     try{await Promise.race([this.flush(),new Promise<never>((_,reject)=>{timer=setTimeout(()=>reject(new Error('等待保存超时，请重试或放弃未同步修改')),Math.max(1,deadline-Date.now()));})]);}finally{clearTimeout(timer);}
     if(this.state.error||this.conflicted()||Date.now()>=deadline)throw new Error(this.state.error||'设置尚未同步');
   }
 }
 dispose(){this.disposed=true;this.generation++;clearTimeout(this.timer);this.controller.abort();}
}
