import { createContext, useContext, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../lib/api';
import { useAuth } from './AuthProvider';
import * as api from './api';
import {authHref} from '../../app/routes';

type Recovery = { resetToken: string; expiresAt: string; simulation: boolean };
const RecoveryContext = createContext<{ value?: Recovery; set: (value?: Recovery) => void }>({ set: () => {} });
export function RecoveryProvider({ children }: { children: ReactNode }) {
 const [value,set]=useState<Recovery>(),auth=useAuth(),previous=useRef(auth.user?.id);
 useEffect(()=>{if(previous.current&&previous.current!==auth.user?.id)set(undefined);previous.current=auth.user?.id;},[auth.user?.id]);
 useEffect(()=>{const clear=()=>set(undefined);window.addEventListener('readict-session-expired',clear);return()=>window.removeEventListener('readict-session-expired',clear);},[]);
 return <RecoveryContext.Provider value={{value,set}}>{children}</RecoveryContext.Provider>;
}
export function useAction() {
  const [pending, setPending] = useState(false); const [error, setError] = useState(''); const lock = useRef(false);
  async function run(action: () => Promise<void>) { if (lock.current) return; lock.current = true; setPending(true); setError(''); try { await action(); } catch (e) { setError(e instanceof ApiError ? e.title + (e.requestId ? '（请求编号：' + e.requestId + '）' : '') : '操作未完成，请稍后重试'); } finally { lock.current = false; setPending(false); } }
  return { pending, error, run };
}
export function ErrorNotice({ message }: { message: string }) { return message ? <p className="form-error" role="alert" id="form-error">{message}</p> : null; }
export function IdentifierFields({ type, setType, value, setValue, disabled }: { type: 'email' | 'phone'; setType: (type: 'email' | 'phone') => void; value: string; setValue: (value: string) => void; disabled: boolean }) {
  return <><div className="identifier-tabs" role="group" aria-label="账号类型"><button type="button" aria-pressed={type === 'email'} disabled={disabled} onClick={() => { setType('email'); setValue(''); }}>邮箱</button><button type="button" aria-pressed={type === 'phone'} disabled={disabled} onClick={() => { setType('phone'); setValue(''); }}>手机号</button></div>
    <label htmlFor="identifier">{type === 'email' ? '邮箱地址' : '中国大陆手机号'}</label><div className="identifier-input">{type === 'phone' && <span className="phone-prefix" aria-label="固定区号 +86">+86</span>}<input id="identifier" name="username" type={type === 'email' ? 'email' : 'tel'} inputMode={type === 'phone' ? 'tel' : 'email'} autoComplete="username" value={value} onChange={e => setValue(type === 'phone' ? api.phoneInput(e.target.value) : e.target.value)} maxLength={type === 'phone' ? 32 : 254} required disabled={disabled} aria-describedby="identifier-hint form-error" placeholder={type === 'phone' ? '11 位手机号' : 'name@example.com'} /></div>
    <p id="identifier-hint" className="field-hint">{type === 'phone' ? '仅支持中国大陆 +86，可粘贴带 +86 的完整号码。' : '邮箱登录不区分大小写。'}</p></>;
}
export function CredentialPage({ registration = false }: { registration?: boolean }) {
  const [type, setType] = useState<'email' | 'phone'>('email'); const [identifier, setIdentifier] = useState(''); const [password, setPassword] = useState(''); const [confirmation, setConfirmation] = useState('');
  const action = useAction(); const auth = useAuth(); const navigate = useNavigate(); const [query] = useSearchParams(); const location = useLocation();
  if (auth.status === 'checking') return <p role="status">正在确认登录状态…</p>;
  if (auth.status === 'authenticated') return <Navigate to={api.safeReturnTo(query.get('returnTo'))} replace />;
  const submit = (event: FormEvent) => { event.preventDefault(); void action.run(async () => {
    if (type === 'phone' && !/^1[3-9][0-9]{9}$/.test(identifier.trim())) throw new ApiError(422, 'IDENTIFIER_INVALID', '请输入正确的 11 位中国大陆手机号');
    if (registration && (!api.passwordValid(password) || confirmation !== password)) throw new ApiError(422, 'PASSWORD_POLICY', confirmation !== password ? '两次输入的密码不一致' : api.passwordHint);
    const input = { identifierType: type, identifier, password };
    if (registration) { await api.register(input); setPassword(''); setConfirmation(''); navigate(authHref('/login',query.get('returnTo')), { state: { registered: true } }); }
    else { await api.login(input); setPassword(''); await auth.refresh(); auth.announce(); }
  }); };
  return <section className="auth-panel"><p className="section-intro">{registration ? '建立你的阅读账号' : '欢迎回来'}</p><h1>{registration ? '从这里开始。' : '继续你的阅读旅程。'}</h1><p className="panel-description">{registration ? '使用邮箱或中国大陆手机号注册。' : '登录后，你的阅读偏好会随账号保存。'}</p>{!registration && location.state?.registered && <p className="form-success" role="status">注册成功，请登录。联系方式仍未验证。</p>}
    <form onSubmit={submit}><IdentifierFields type={type} setType={setType} value={identifier} setValue={setIdentifier} disabled={action.pending} />
      <label htmlFor="password">密码</label><input id="password" name="password" type="password" autoComplete={registration ? 'new-password' : 'current-password'} required maxLength={256} value={password} onChange={e => setPassword(e.target.value)} disabled={action.pending} aria-describedby="password-hint form-error" />
      {registration && <><p className="field-hint" id="password-hint">{api.passwordHint}</p><label htmlFor="confirmation">确认密码</label><input id="confirmation" type="password" autoComplete="new-password" required value={confirmation} onChange={e => setConfirmation(e.target.value)} disabled={action.pending} /></>}
      <ErrorNotice message={action.error} /><button className="primary-button" disabled={action.pending}>{action.pending ? '正在处理…' : registration ? '创建账号' : '登录'}</button>
    </form><div className="form-links"><Link to={authHref(registration ? '/login' : '/register',query.get('returnTo'))}>{registration ? '已有账号，去登录' : '创建新账号'}</Link>{!registration && <Link to={authHref('/forgot-password',query.get('returnTo'))}>忘记密码</Link>}</div></section>;
}
export function useCooldown() { const [until, setUntil] = useState(0); const [now, setNow] = useState(Date.now()); useEffect(() => { const id = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(id); }, []); return { seconds: Math.max(0, Math.ceil((until - now) / 1000)), start: (seconds: number) => { setNow(Date.now()); setUntil(Date.now() + seconds * 1000); } }; }
export function ForgotPage() {
  const [type, setType] = useState<'email' | 'phone'>('email'); const [identifier, setIdentifier] = useState(''); const [challenge, setChallenge] = useState<api.Challenge>(); const [proof, setProof] = useState('');
  const action = useAction(); const cooldown = useCooldown(); const recovery = useContext(RecoveryContext); const navigate = useNavigate(); const [query] = useSearchParams();
  useEffect(() => { recovery.set(undefined); }, []);
  return <section className="auth-panel"><p className="section-intro">找回账号</p><h1>重新连接你的阅读。</h1><p className="panel-description">通过账号绑定的联系方式验证后，设置新密码。</p><p className="simulation-note">真实短信／邮件通道尚未接入。仅显式开启本机模拟时可验证流程，模拟不证明真实归属。</p>
    <form onSubmit={e => { e.preventDefault(); void action.run(async () => { const value = await api.requestReset(type, identifier); setChallenge(value); setProof(''); cooldown.start(value.retryAfter); }); }}><IdentifierFields type={type} setType={setType} value={identifier} setValue={setIdentifier} disabled={action.pending || !!challenge} /><button className="secondary-button" disabled={action.pending || cooldown.seconds > 0}>{cooldown.seconds > 0 ? `${cooldown.seconds} 秒后可重发` : challenge ? '重新获取验证码' : '获取验证码'}</button></form>
    {challenge && <form onSubmit={e => { e.preventDefault(); void action.run(async () => { const value = await api.verifyReset(challenge.challengeId, proof); recovery.set(value); setProof(''); navigate(authHref('/reset-password',query.get('returnTo'))); }); }}><p role="status" className="field-hint">若账号可用，请查收；可在冷却后重试。</p><label htmlFor="proof">六位验证码</label><input id="proof" inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required value={proof} onChange={e => setProof(e.target.value)} disabled={action.pending} /><button className="primary-button" disabled={action.pending}>{action.pending ? '正在验证…' : '验证并继续'}</button></form>}
    <ErrorNotice message={action.error} /><div className="form-links"><Link to={authHref('/login',query.get('returnTo'))}>返回登录</Link></div></section>;
}
export function ResetPage() {
  const recovery = useContext(RecoveryContext); const [password, setPassword] = useState(''); const [confirmation, setConfirmation] = useState(''); const [done, setDone] = useState(false); const action = useAction(); const auth = useAuth(); const [query] = useSearchParams();
  if (done) return <section className="auth-panel"><h1>密码已更新。</h1><p>请使用新密码重新登录。</p><Link to={authHref('/login',query.get('returnTo'))}>返回登录</Link></section>;
  if (!recovery.value || Date.parse(recovery.value.expiresAt) <= Date.now()) return <Navigate to={authHref('/forgot-password',query.get('returnTo'))} replace/>;
  return <section className="auth-panel"><h1>设置新的密码。</h1>{recovery.value.simulation && <p className="simulation-note">当前为本地模拟恢复，真实联系方式仍未验证。</p>}<form onSubmit={e => { e.preventDefault(); void action.run(async () => { if (!api.passwordValid(password) || password !== confirmation) throw new ApiError(422, 'PASSWORD_POLICY', password !== confirmation ? '两次输入的密码不一致' : api.passwordHint); await api.completeReset(recovery.value!.resetToken, password); recovery.set(undefined); setPassword(''); setConfirmation(''); auth.clear(); auth.announce(); setDone(true); }); }}><label htmlFor="new-password">新密码</label><input id="new-password" type="password" autoComplete="new-password" required value={password} onChange={e => setPassword(e.target.value)} aria-describedby="password-hint form-error" /><p id="password-hint" className="field-hint">{api.passwordHint}</p><label htmlFor="confirmation">确认新密码</label><input id="confirmation" type="password" autoComplete="new-password" required value={confirmation} onChange={e => setConfirmation(e.target.value)} /><ErrorNotice message={action.error} /><button className="primary-button" disabled={action.pending}>{action.pending ? '正在更新…' : '更新密码'}</button></form></section>;
}
