import {useState} from 'react';
import {Link,useNavigate} from 'react-router-dom';
import {UserRound,ChevronRight,LockKeyhole,BookOpen} from 'lucide-react';
import {useAuth} from './AuthProvider';
import * as api from './api';
import {ApiError} from '../../lib/api';
import {useAction,ErrorNotice,useCooldown} from './Pages';
import {accountPresentation,type ContactView} from './AccountPresentation';
import {Dialog} from '../../components/ui/Dialog';
import {PasswordField} from '../../components/ui/PasswordField';
import {authHref} from '../../app/routes';
export default function AccountPage(){
 const auth=useAuth(),user=auth.user!,view=accountPresentation(user),navigate=useNavigate(),action=useAction(),cooldown=useCooldown();
 const[open,setOpen]=useState(false),[current,setCurrent]=useState(''),[password,setPassword]=useState(''),[confirmation,setConfirmation]=useState('');
 const[contact,setContact]=useState<'email'|'phone'>('email'),[challenge,setChallenge]=useState<api.Challenge>(),[proof,setProof]=useState(''),[simulated,setSimulated]=useState(false);
 function close(){if(action.pending)return;setOpen(false);setCurrent('');setPassword('');setConfirmation('');}
 function row(kind:'email'|'phone',value:ContactView){return <div className="account-row"><span>{kind==='email'?'邮箱地址':'手机号码'}</span><div>{value.kind==='bound'?<><strong>{value.value}</strong><span className={'contact-status '+(value.verified?'verified':'')}>{value.verified?'已验证':'未验证'}</span></>:<span className="muted">尚未绑定</span>}</div>{value.kind==='bound'&&!value.verified&&<button className="link-button" disabled={action.pending||cooldown.seconds>0} onClick={()=>void action.run(async()=>{const next=await api.requestContact(kind);setContact(kind);setChallenge(next);setProof('');cooldown.start(next.retryAfter);})}>{cooldown.seconds>0?cooldown.seconds+' 秒后可重发':'验证'}</button>}</div>;}
 return <section className="account-page"><div className="member-heading"><h1>个人账户</h1><p>管理账户信息与安全，让阅读安心继续。</p></div><section className="account-identity"><span className="avatar large"><UserRound size={34} strokeWidth={1.2}/></span><div><h2>{view.displayName}</h2><p>默认头像 · 你的私人阅读空间</p></div></section><section className="account-details"><h2>账户与安全</h2>{row('email',view.email)}{row('phone',view.phone)}<div className="account-row"><span><LockKeyhole size={17}/>登录密码</span><div><span className="muted">定期更新密码，保护你的账户。</span></div><button className="link-button" onClick={()=>setOpen(true)}>修改密码<ChevronRight size={16}/></button></div></section>
 {simulated&&<p role="status" className="simulation-note">本地模拟已通过，真实联系方式仍未验证。</p>}
 {challenge&&<form className="contact-form" onSubmit={e=>{e.preventDefault();void action.run(async()=>{setSimulated(await api.verifyContact(challenge.challengeId,proof));setProof('');setChallenge(undefined);await auth.refresh();});}}><p>{challenge.simulation?'本地模拟验证码，不证明真实联系方式归属。':'请输入收到的验证码。'}</p><label htmlFor="contact-proof">{contact==='email'?'邮箱':'手机'}六位验证码</label><input id="contact-proof" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required value={proof} onChange={e=>setProof(e.target.value)}/><button className="primary-button" disabled={action.pending}>验证</button></form>}
 <ErrorNotice message={open?'':action.error}/><Link to="/settings/reading" className="account-settings-link"><BookOpen size={21}/><div><strong>阅读设置</strong><span>调整字体、排版与阅读纸面。</span></div><ChevronRight size={18}/></Link>
 <Dialog open={open} onClose={close} busy={action.pending} title="修改密码" description="更新成功后，所有设备都需要重新登录。"><form onSubmit={e=>{e.preventDefault();void action.run(async()=>{if(password!==confirmation||!api.passwordValid(password))throw new ApiError(422,'PASSWORD_POLICY',password!==confirmation?'两次输入的密码不一致':api.passwordHint);await api.changePassword(current,password);setCurrent('');setPassword('');setConfirmation('');auth.clear();auth.announce();navigate(authHref('/login','/account'),{replace:true});});}}>
 <PasswordField id="current-password" label="当前密码" value={current} onChange={setCurrent} disabled={action.pending} autoComplete="current-password"/><PasswordField id="replacement-password" label="新密码" value={password} onChange={setPassword} disabled={action.pending}/><p className="field-hint">{api.passwordHint}</p><PasswordField id="confirmation" label="确认新密码" value={confirmation} onChange={setConfirmation} disabled={action.pending}/><ErrorNotice message={action.error}/><div className="dialog-actions"><button type="button" className="secondary-button" disabled={action.pending} onClick={close}>取消</button><button className="primary-button" disabled={action.pending}>{action.pending?'正在更新…':'更新密码并重新登录'}</button></div></form></Dialog>
 </section>;
}
