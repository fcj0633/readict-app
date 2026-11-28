import {Link} from 'react-router-dom';
import {BookOpen,ArrowLeft,Check} from 'lucide-react';
import {useReadingPreferences,type ReadingPatch} from './PreferencesProvider';
import type {Preferences} from './model';
import './settings.css';
const groups=[
 {key:'font',label:'正文字体',values:[['Georgia','Georgia'],['Palatino Linotype','Palatino'],['Arial','Arial']]},
 {key:'fontSize',label:'字号',values:[[18,'18 px'],[20,'20 px'],[22,'22 px'],[24,'24 px']]},
 {key:'lineHeight',label:'行距',values:[[1.6,'1.6 倍'],[1.8,'1.8 倍'],[2,'2.0 倍']]},
 {key:'width',label:'正文宽度',values:[[660,'660 px'],[720,'720 px']]},
 {key:'theme',label:'阅读纸面',values:[['white','白色'],['warm','暖纸'],['dark','夜读']]},
] as const;
export default function SettingsPage(){
 const{state,editReading,retry,resolve}=useReadingPreferences(),p=state.draft;
 const status=({loading:'正在读取…',saved:'已同步',saving:'正在保存…',unsynced:'尚未同步',conflict:'需要处理冲突'})[state.status];
 return <section className="reading-settings"><div className="settings-heading member-heading"><div><h1>阅读设置</h1><p>选择适合你的字体与阅读纸面。</p></div><span role="status"><Check size={15}/>{status}</span></div>
 {state.error&&<p className="form-error" role="alert">{state.error}</p>}{state.status==='unsynced'&&<button className="secondary-button" onClick={retry}>重新同步</button>}{state.status==='conflict'&&<div className="settings-conflict" role="alert"><p>设置已在其他页面更新，当前修改已保留。</p>{state.server?<><button className="secondary-button" onClick={()=>resolve(false)}>使用已保存设置</button><button className="primary-button" onClick={()=>resolve(true)}>保留当前修改并重新保存</button></>:<button className="secondary-button" onClick={retry}>重新读取冲突版本</button>}</div>}
 <div className="reading-settings-layout"><aside className="reading-settings-nav"><span><BookOpen size={18}/>阅读设置</span><Link to="/account"><ArrowLeft size={15}/>个人账户</Link></aside>{p?<div className="reading-settings-main"><div className="reading-controls">{groups.map(group=><fieldset key={group.key}><legend>{group.label}</legend><div className={'reading-options options-'+group.key}>{group.values.map(([value,label])=><button type="button" key={value} aria-label={String(label)} aria-pressed={p[group.key]===value} onClick={()=>editReading({[group.key]:value} as ReadingPatch)}>{group.key==='font'&&<span className="font-sample" style={{fontFamily:String(value)}}>Aa</span>}{group.key==='theme'&&<span className="paper-swatch" data-theme={value}>The quiet<br/>of a page.</span>}<span>{label}</span></button>)}</div></fieldset>)}</div><ReadingPreview preferences={p}/></div>:<p role="status">正在读取阅读设置…</p>}</div></section>;
}
export function ReadingPreview({preferences:p}:{preferences:Preferences}){
 return <section className="reading-preview-section"><div className="preview-label"><span>阅读预览</span><span>即时呈现你的偏好</span></div><div className="reading-preview" data-theme={p.theme} style={{fontFamily:p.font,fontSize:p.fontSize,lineHeight:p.lineHeight}}><article style={{width:p.width===720?'100%':'91.67%'}}><p className="preview-book-label">A quiet moment</p><h2>Chapter One</h2><p>There is a pleasure in opening a book and finding a sentence that stays with you.</p><p>Read slowly. Let the words make room for a new thought. The quiet of a page offers a little space to notice what matters.</p></article><small>1</small></div><p className="preview-note">此段文字仅用于排版预览。</p></section>;
}
