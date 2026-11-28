import { Navigate, Outlet, useLocation, Link } from 'react-router-dom';
import { useAuth } from '../features/auth/AuthProvider';
import { loginHref, UUID } from './routes';
import { useParams } from 'react-router-dom';
export function RouteGuard() {
  const auth = useAuth(), location = useLocation();
  if (auth.status === 'checking') return <div className="route-feedback" role="status">正在确认登录状态…</div>;
  if (auth.status === 'unavailable') return <div className="route-feedback"><h1>暂时无法确认账号状态</h1><button onClick={() => void auth.refresh()}>重新检查</button></div>;
  if (auth.status === 'guest') return <Navigate to={loginHref(location)} replace />;
  return <Outlet key={auth.user?.id} />;
}
export function ReaderGuard({ children }: { children: React.ReactNode }) {
  const { bookId = '' } = useParams();
  return UUID.test(bookId) ? children : <div className="route-feedback"><h1>阅读地址无效</h1><Link to="/books">返回书架</Link></div>;
}
