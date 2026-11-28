import { useEffect } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { Brand } from '../components/ui/Brand';
import { UserMenu } from './UserMenu';
export function RouteEffects() {
  const { pathname } = useLocation();
  useEffect(() => {
    const title = ({ '/books':'我的书架', '/favorites':'我的收藏', '/account':'个人账户', '/settings/reading':'阅读设置', '/login':'登录', '/register':'注册', '/forgot-password':'找回密码', '/reset-password':'设置新密码', '/status':'服务状态' } as Record<string,string>)[pathname] ?? '阅读';
    document.title = 'Readict · ' + title;
    if (!pathname.startsWith('/reader/')) window.scrollTo(0,0);
  }, [pathname]);
  return null;
}
export function AppLayout() {
  return <div className="app-shell app-surface"><header className="app-header"><div className="app-header-inner"><Brand /><nav aria-label="主导航"><NavLink to="/books">我的书架</NavLink><NavLink to="/favorites">我的收藏</NavLink></nav><UserMenu /></div></header><main className="app-content"><Outlet /></main><footer className="app-footer"><span>Readict</span><span>读原文，也读懂每一个词。</span><NavLink to="/account">账户与设置</NavLink></footer></div>;
}
export function AuthLayout() {
  return <div className="auth-shell app-surface"><header className="auth-header"><Brand /></header><main className="auth-content"><Outlet /></main><footer className="app-footer"><span>Readict</span><span>把阅读的时间留给自己。</span></footer></div>;
}
