// 角色守卫：角色不符时跳转 /403（AC-AUTH-09）。必须置于 RequireAuth 之内。
import type { ReactNode } from 'react';
import { Navigate, Outlet } from 'react-router';
import { PageLoading } from '../components/PageLoading';
import type { Role } from '../api/types';
import { useAuth } from './AuthContext';

export function RequireRole({ role, children }: { role: Role; children?: ReactNode }) {
  const { status, user } = useAuth();

  if (status === 'loading') return <PageLoading />;
  if (status === 'anonymous' || !user) return <Navigate to="/login" replace />;
  if (user.role !== role) return <Navigate to="/403" replace />;
  return children ? <>{children}</> : <Outlet />;
}