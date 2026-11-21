// reminder-increment.md v1.1：只使用已冻结的本人通知接口，不接受 readerId。
import { apiGet, apiPost } from '../../api/client';
import type { MarkAllNotificationsReadResult, Notification, NotificationQuery, Page, UnreadNotificationCount } from '../../api/types';

export const notificationKeys = {
  all: ['notifications', 'me'] as const,
  list: (params: NotificationQuery) => ['notifications', 'me', 'list', params] as const,
  unreadCount: ['notifications', 'me', 'unread-count'] as const,
};

// 本功能不进行后台轮询、重连查询或自动重试，避免延长空闲会话；不修改全局 QueryClient。
export const notificationQueryOptions = {
  staleTime: 0,
  refetchOnMount: 'always' as const,
  refetchInterval: false as const,
  refetchOnWindowFocus: false,
  refetchOnReconnect: false,
  retry: false as const,
};

export function listMyNotifications(params: NotificationQuery, signal?: AbortSignal): Promise<Page<Notification>> {
  return apiGet('/api/notifications/me', { unreadOnly: params.unreadOnly, page: params.page, size: params.size }, { signal });
}

export function getUnreadCount(signal?: AbortSignal): Promise<UnreadNotificationCount> {
  return apiGet('/api/notifications/me/unread-count', undefined, { signal });
}

export function markNotificationRead(id: number): Promise<void> {
  return apiPost(`/api/notifications/me/${id}/read`);
}

export function markAllNotificationsRead(): Promise<MarkAllNotificationsReadResult> {
  return apiPost('/api/notifications/me/read-all');
}
