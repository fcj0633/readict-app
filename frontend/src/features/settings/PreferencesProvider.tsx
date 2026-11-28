import {createContext,useCallback,useContext,useEffect,useRef,useState,type ReactNode} from 'react';
import {useAuth} from '../auth/AuthProvider';
import {PreferenceSaver,type Preferences,type SaveState} from './model';
export type ReadingPatch=Partial<Pick<Preferences,'font'|'fontSize'|'lineHeight'|'width'|'theme'>>;
type ContextValue={state:SaveState;origin:string|null;editReading:(patch:ReadingPatch,source?:string)=>void;retry:()=>void;resolve:(keep:boolean)=>void;flush:()=>Promise<void>;reload:()=>Promise<void>;releaseReaderPreview:()=>void};
const Context=createContext<ContextValue|null>(null);
export function PreferencesProvider({children}:{children:ReactNode}){
 const auth=useAuth(),userId=auth.status==='authenticated'?auth.user?.id:undefined;
 const saver=useRef<PreferenceSaver|null>(null),generation=useRef(0);
 const[value,setValue]=useState<{userId?:string;state:SaveState}>({state:{status:'loading'}});
 const[origin,setOrigin]=useState<string|null>(null);
 const announce=useRef(auth.announce);announce.current=auth.announce;
 useEffect(()=>{const current=++generation.current;setOrigin(null);setValue({userId,state:{status:'loading'}});
 if(!userId)return;
 const instance=new PreferenceSaver(state=>{if(generation.current===current)setValue({userId,state});},undefined,()=>announce.current('settings'));
 saver.current=instance;void instance.load();
 return()=>{generation.current++;instance.dispose();if(saver.current===instance)saver.current=null;};
 },[userId]);
 const previous=useRef(auth.revision);
 useEffect(()=>{if(previous.current===auth.revision)return;previous.current=auth.revision;void saver.current?.load();},[auth.revision]);
 const editReading=useCallback((patch:ReadingPatch,source='settings')=>{const instance=saver.current;if(!instance?.state.draft)return;
 const allowed:ReadingPatch={};for(const key of ['font','fontSize','lineHeight','width','theme'] as const)if(patch[key]!==undefined)Object.assign(allowed,{[key]:patch[key]});
 setOrigin(source);instance.edit({...instance.state.draft,...allowed});
 },[]);
 const retry=useCallback(()=>saver.current?.retry(),[]),resolve=useCallback((keep:boolean)=>saver.current?.resolve(keep),[]);
 const flush=useCallback(async()=>{await saver.current?.flushAll();},[]),reload=useCallback(async()=>{await saver.current?.load();},[]);
 const releaseReaderPreview=useCallback(()=>setOrigin(null),[]);
 const state=value.userId===userId?value.state:{status:'loading' as const};
 useEffect(()=>{if(!userId||!state.draft||state.status==='saved')return;const before=(e:BeforeUnloadEvent)=>{e.preventDefault();e.returnValue='';};window.addEventListener('beforeunload',before);return()=>window.removeEventListener('beforeunload',before);},[userId,state.status,state.draft]);
 return <Context.Provider value={{state,origin,editReading,retry,resolve,flush,reload,releaseReaderPreview}}>{children}</Context.Provider>;
}
export function useReadingPreferences(){const value=useContext(Context);if(!value)throw Error('PreferencesProvider missing');return value;}
