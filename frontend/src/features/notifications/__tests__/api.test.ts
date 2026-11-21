// AC-NTF-01 / v1.1：冻结通知协议；真实 client + fetch 边界模拟，不连接后端。
import { afterEach, describe, expect, it, vi } from 'vitest';
import { listMyNotifications, getUnreadCount, markNotificationRead, markAllNotificationsRead } from '../api';
import { ApiError } from '../../../api/client';

afterEach(() => { vi.unstubAllGlobals(); document.cookie = 'XSRF-TOKEN=; Max-Age=0'; });

describe('AC-NTF-01 通知 API', () => {
  it('分页只携带冻结查询，不传 readerId 或自行排序参数', async () => {
    const data = { items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 };
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify(data)));
    vi.stubGlobal('fetch', fetcher);
    expect(await listMyNotifications({ unreadOnly: true, page: 1, size: 20 })).toEqual(data);
    expect(fetcher).toHaveBeenCalledWith('/api/notifications/me?unreadOnly=true&page=1&size=20', expect.objectContaining({ method: 'GET', credentials: 'same-origin' }));
  });
  it('读取未读总数', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"unreadCount":3}')));
    expect(await getUnreadCount()).toEqual({ unreadCount: 3 });
  });
  it('单条已读接受 204 且使用既有 client CSRF', async () => {
    document.cookie = 'XSRF-TOKEN=mock-token';
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetcher);
    await expect(markNotificationRead(9)).resolves.toBeUndefined();
    expect(fetcher).toHaveBeenCalledWith('/api/notifications/me/9/read', expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'mock-token' }) }));
  });
  it('全部已读返回 updated', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response('{"updated":2}'));
    vi.stubGlobal('fetch', fetcher);
    expect(await markAllNotificationsRead()).toEqual({ updated: 2 });
    expect(fetcher).toHaveBeenCalledWith('/api/notifications/me/read-all', expect.objectContaining({ method: 'POST' }));
  });
  it.each([[404, 'NOTIFICATION_NOT_FOUND'], [403, 'FORBIDDEN'], [403, 'CSRF_INVALID'], [503, 'DB_BUSY']])('原样保留 %s %s，POST 不自动重试', async (status, code) => {
    const fetcher = vi.fn().mockImplementation(async (path: string) => path === '/api/auth/csrf'
      ? new Response(null, { status: 204 })
      : new Response(JSON.stringify({ code, message: '服务端信息' }), { status: Number(status) }));
    vi.stubGlobal('fetch', fetcher);
    await expect(markNotificationRead(9)).rejects.toMatchObject({ status, code });
    expect(fetcher.mock.calls.filter(([path]) => path === '/api/notifications/me/9/read')).toHaveLength(1);
  });
  it('401 沿用全局过期事件', async () => {
    const expired = vi.fn();
    window.addEventListener('library:unauthorized', expired);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"code":"AUTH_REQUIRED"}', { status: 401 })));
    await expect(getUnreadCount()).rejects.toBeInstanceOf(ApiError);
    expect(expired).toHaveBeenCalledOnce();
    window.removeEventListener('library:unauthorized', expired);
  });
});
