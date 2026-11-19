// 全局会话过期处理（设计 10.2 节）：任何接口返回 401（探测/登录除外）时，
// 清空 Query 缓存、清除登录态、提示"登录已过期"并跳转 /login?redirect=<原路径>。
// I-09（approval-record §1.1）：主动登出期间/之后到达的迟到 401（在途请求被失效会话拒绝）
// 必须忽略，否则会用旧 location 把 ?redirect=... 追加回 /login（E2E E-01）。
// 用 ref 读取最新登录态与位置，避免事件监听器闭包里的过期值。
import { useEffect, useRef } from 'react';
import { App } from 'antd';
import { useLocation, useNavigate } from 'react-router';
import { UNAUTHORIZED_EVENT } from '../api/client';
import { queryClient } from '../api/queryClient';
import { useAuth } from './AuthContext';

export function SessionGuard() {
  const { markLoggedOut, status, loggedOutExplicitly } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const { message } = App.useApp();

  const latest = useRef({ status, loggedOutExplicitly, pathname: location.pathname, search: location.search });
  latest.current = { status, loggedOutExplicitly, pathname: location.pathname, search: location.search };

  useEffect(() => {
    const handler = () => {
      const cur = latest.current;
      // 已登出（主动登出意图或已处于匿名态）：迟到 401 不再触发跳转/提示
      if (cur.loggedOutExplicitly || cur.status !== 'authenticated') return;
      queryClient.clear();
      markLoggedOut();
      message.warning('登录已过期，请重新登录');
      const target =
        cur.pathname === '/login'
          ? '/login'
          : `/login?redirect=${encodeURIComponent(cur.pathname + cur.search)}`;
      navigate(target, { replace: true });
    };
    window.addEventListener(UNAUTHORIZED_EVENT, handler);
    return () => window.removeEventListener(UNAUTHORIZED_EVENT, handler);
  }, [markLoggedOut, message, navigate]);

  return null;
}