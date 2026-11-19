// 根路径按角色重定向（设计 10.1 节）：ADMIN → /admin/dashboard，READER → /books。
import { Navigate } from 'react-router';
import { PageLoading } from '../components/PageLoading';
import { useAuth } from '../auth/AuthContext';

export function RootRedirect() {
  const { status, user } = useAuth();
  if (status === 'loading' || !user) return <PageLoading />;
  return <Navigate to={user.role === 'ADMIN' ? '/admin/dashboard' : '/books'} replace />;
}