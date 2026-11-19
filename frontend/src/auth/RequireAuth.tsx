// 未登录访问受保护路由时跳转 /login?redirect=<原路径>（AC-AUTH-09）。
// I-09（approval-record §1.1）：主动登出（loggedOutExplicitly）时跳 /login 不带 redirect。
// 意图由 LoginPage 挂载时消费（导航级联结束后），保证登出过程中任何滞后的匿名渲染都不追加 redirect；
// 之后"未登录直接访问受保护页"仍保留 redirect。会话过期由 SessionGuard 带 redirect 跳转。
import type { ReactNode } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router';
import { PageLoading } from '../components/PageLoading';
import { useAuth } from './AuthContext';

export function RequireAuth({ children }: { children?: ReactNode }) {
  const { status, loggedOutExplicitly } = useAuth();
  const location = useLocation();

  if (status === 'loading') return <PageLoading tip="正在确认登录状态…" />;
  if (status === 'anonymous') {
    if (loggedOutExplicitly) {
      return <Navigate to="/login" replace />;
    }
    const redirect = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?redirect=${redirect}`} replace />;
  }
  return children ? <>{children}</> : <Outlet />;
}