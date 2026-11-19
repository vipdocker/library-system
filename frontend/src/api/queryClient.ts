// QueryClient 全局约定（T02 制定，feature 任务直接使用）：
// - 4xx（含 401/403/409）不自动重试：业务错误应立即呈现给用户；
// - 网络错误与 5xx 最多重试 2 次；
// - staleTime 30s，窗口重新聚焦不自动刷新；mutation 不重试。
// 会话过期 / 登出时由 SessionGuard / AuthContext 调用 queryClient.clear()。

import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './client';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => {
        if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
          return false;
        }
        return failureCount < 2;
      },
      staleTime: 30_000,
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: false,
    },
  },
});