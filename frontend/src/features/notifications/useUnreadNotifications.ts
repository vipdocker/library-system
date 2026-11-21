import { useEffect, useRef } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useLocation } from 'react-router';
import { getUnreadCount, notificationKeys, notificationQueryOptions } from './api';

/** 仅读者启用。打开时 useQuery 查询；导航后失效重查；已读操作共享同一通知 key。 */
export function useUnreadNotifications(enabled: boolean) {
  const location = useLocation();
  const lastLocation = useRef(location.key);
  const client = useQueryClient();
  const query = useQuery({
    ...notificationQueryOptions,
    queryKey: notificationKeys.unreadCount,
    queryFn: ({ signal }) => getUnreadCount(signal),
    enabled,
  });

  useEffect(() => {
    if (lastLocation.current !== location.key) {
      lastLocation.current = location.key;
      if (enabled) void client.invalidateQueries({ queryKey: notificationKeys.unreadCount });
    }
  }, [client, enabled, location.key]);

  return query;
}
