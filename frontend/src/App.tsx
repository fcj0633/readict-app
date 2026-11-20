import { useEffect } from 'react';
import { BrowserRouter, Link, NavLink, Route, Routes, Navigate, useLocation } from 'react-router-dom';
import StatusPage from './StatusPage';
import { AuthProvider, useAuth } from './features/auth/AuthProvider';
import { AccountPage, CredentialPage, ForgotPage, Protected, RecoveryProvider, ResetPage } from './features/auth/Pages';
import SettingsPage from './features/settings/SettingsPage';
import './account.css';
function Shell() {
  const location = useLocation(); const auth = useAuth();
  useEffect(() => { const titles: Record<string, string> = { '/': '服务状态', '/login': '登录', '/register': '注册', '/forgot-password': '找回密码', '/reset-password': '设置新密码', '/account': '个人账号', '/settings': '阅读偏好' }; document.title = 'Readict · ' + (titles[location.pathname] ?? '服务状态'); }, [location.pathname]);
  if (location.pathname === '/') return <><div className="status-navigation"><Link to={auth.status === 'authenticated' ? '/account' : '/login'}>{auth.status === 'authenticated' ? '进入账号' : '登录 / 注册'}</Link></div><StatusPage /></>;
  const form = ['/login', '/register', '/forgot-password', '/reset-password'].includes(location.pathname);
  return <div className="account-shell"><header className="account-header"><Link to="/" className="brand"><span className="book" aria-hidden="true">r</span>Readict</Link><nav aria-label="主导航">{auth.status === 'authenticated' && <><NavLink to="/account">个人账号</NavLink><NavLink to="/settings">阅读偏好</NavLink></>}<Link to="/">服务状态</Link></nav></header><main className={form ? 'auth-layout' : 'member-layout'}>
    {form && <aside className="welcome-aside"><span className="book-shape" aria-hidden="true" /><p>给自己留一段<br />安静阅读的时间。</p><span>Readict，让理解随阅读生长。</span></aside>}
    <Routes><Route path="/login" element={<CredentialPage />} /><Route path="/register" element={<CredentialPage registration />} /><Route path="/forgot-password" element={<ForgotPage />} /><Route path="/reset-password" element={<ResetPage />} /><Route path="/account" element={<Protected><AccountPage /></Protected>} /><Route path="/settings" element={<Protected><SettingsPage /></Protected>} /><Route path="*" element={<Navigate to="/" replace />} /></Routes>
  </main><footer className="account-footer">Readict<span>在阅读中，遇见新的理解。</span></footer></div>;
}
export default function App() { return <BrowserRouter><AuthProvider><RecoveryProvider><Shell /></RecoveryProvider></AuthProvider></BrowserRouter>; }
