import { useEffect, useRef, useState } from 'react';
import { Menu } from '@base-ui/react/menu';
import { UserRound, ChevronDown, LogOut, Settings2, User } from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../features/auth/AuthProvider';
import {Dialog} from '../components/ui/Dialog';
import {useReadingPreferences} from '../features/settings/PreferencesProvider';
import {maskedContact} from '../features/auth/AccountPresentation';
import { logout } from '../features/auth/api';
export function UserMenu() {
  const logoutLock=useRef(false);
  const prefs=useReadingPreferences();
  const [confirm,setConfirm]=useState(false);
  const auth = useAuth(), navigate = useNavigate(), location = useLocation();
  const [open, setOpen] = useState(false), [busy, setBusy] = useState(false), [error,setError]=useState('');
  useEffect(() => { setOpen(false); }, [location.pathname]);
  async function exit(discard=false) {
    if (!discard && prefs.state.draft && prefs.state.status !== 'saved') {setConfirm(true);return;}
    if (logoutLock.current) return;
    logoutLock.current=true;
    setBusy(true); setError('');
    try { await logout(); auth.clear(); auth.announce(); navigate('/login', { replace:true }); }
    catch(e) { setError(e instanceof Error ? e.message : '退出失败，请重试'); }
    finally { logoutLock.current=false; setBusy(false); }
  }
  const contact=auth.user ? maskedContact(auth.user) : '';
  return <div className="app-user-menu"><Menu.Root open={open} onOpenChange={setOpen}><Menu.Trigger className="avatar-trigger" aria-label="打开账户菜单"><span className="avatar"><UserRound size={21} strokeWidth={1.4}/></span><ChevronDown size={14}/></Menu.Trigger>
    <Menu.Portal><Menu.Positioner sideOffset={12} align="end" className="menu-positioner"><Menu.Popup className="app-menu app-surface"><div className="menu-identity"><strong>读者</strong><span>{contact}</span></div><Menu.Item onClick={() => navigate('/account')}><User size={17}/>个人账户</Menu.Item><Menu.Item onClick={() => navigate('/settings/reading')}><Settings2 size={17}/>阅读设置</Menu.Item><Menu.Separator/><Menu.Item disabled={busy} onClick={() => void exit(false)}><LogOut size={17}/>{busy ? '正在退出…' : '退出登录'}</Menu.Item>{error && <p role="alert">{error}</p>}</Menu.Popup></Menu.Positioner></Menu.Portal>
  </Menu.Root>{error && <p role="alert" className="form-error">{error}</p>}<Dialog open={confirm} onClose={()=>setConfirm(false)} busy={busy} title="设置尚未同步" description="可以等待保存、重试，或放弃未同步修改后退出。">{error&&<p role="alert" className="form-error">{error}</p>}<div className="dialog-actions"><button className="secondary-button" disabled={busy} onClick={()=>setConfirm(false)}>取消</button><button className="secondary-button" disabled={busy} onClick={()=>void exit(true)}>放弃未同步修改并退出</button><button className="primary-button" disabled={busy} onClick={()=>{setBusy(true);setError('');void prefs.flush().then(()=>{setBusy(false);return exit(true);}).catch(e=>{setBusy(false);setError(e instanceof Error?e.message:'尚未同步，请重试');});}}>等待保存并退出</button></div></Dialog></div>;
}
