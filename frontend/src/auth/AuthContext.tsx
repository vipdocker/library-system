// 登录态上下文。启动时依次请求 GET /api/auth/csrf、GET /api/auth/me（设计 10.4 节）。
// 探测请求使用 suppressAuthEvent，避免把"尚未登录"误报为"会话过期"。

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { apiGet, apiPost, bootstrapCsrf } from '../api/client';
import { queryClient } from '../api/queryClient';
import type { UserInfo } from '../api/types';

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous';

export interface AuthContextValue {
  status: AuthStatus;
  user: UserInfo | null;
  login: (username: string, password: string) => Promise<UserInfo>;
  logout: () => Promise<void>;
  /** 会话过期时由 SessionGuard 调用：仅清空本地登录态，不请求后端 */
  markLoggedOut: () => void;
  /**
   * I-09（approval-record §1.1）：主动登出后为 true（一次性意图）。
   * RequireAuth 据此跳 /login 时不带 redirect；会话过期（markLoggedOut）不设置。
   * 可选字段：测试 mock 不提供时按 false 处理，保持原有 redirect 行为。
   */
  loggedOutExplicitly?: boolean;
  /** LoginPage 挂载时消费掉登出意图，恢复"未登录直接访问受保护页保留 redirect"的默认行为 */
  clearLogoutIntent?: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [user, setUser] = useState<UserInfo | null>(null);
  const [loggedOutExplicitly, setLoggedOutExplicitly] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        await bootstrapCsrf();
        const me = await apiGet<UserInfo>('/api/auth/me', undefined, { suppressAuthEvent: true });
        if (!cancelled) {
          setUser(me);
          setStatus('authenticated');
        }
      } catch {
        if (!cancelled) {
          setUser(null);
          setStatus('anonymous');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const info = await apiPost<UserInfo>(
      '/api/auth/login',
      { username, password },
      { suppressAuthEvent: true },
    );
    setUser(info);
    setStatus('authenticated');
    setLoggedOutExplicitly(false);
    return info;
  }, []);

  const markLoggedOut = useCallback(() => {
    setUser(null);
    setStatus('anonymous');
  }, []);

  const clearLogoutIntent = useCallback(() => {
    setLoggedOutExplicitly(false);
  }, []);

  const logout = useCallback(async () => {
    // I-09：点击登出即同步记录意图（不等响应），让 SessionGuard 忽略登出期间
    // 在途请求的迟到 401，避免其用旧 location 追加 ?redirect=...（E2E E-01）。
    setLoggedOutExplicitly(true);
    try {
      await apiPost<void>('/api/auth/logout');
    } finally {
      queryClient.clear();
      markLoggedOut();
    }
  }, [markLoggedOut]);

  const value = useMemo(
    () => ({ status, user, login, logout, markLoggedOut, loggedOutExplicitly, clearLogoutIntent }),
    [status, user, login, logout, markLoggedOut, loggedOutExplicitly, clearLogoutIntent],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth 必须在 AuthProvider 内使用');
  }
  return ctx;
}