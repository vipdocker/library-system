// AC-NTF-01/03、AC-REM-04、AC-UI-07 v1.1；模拟冻结 API，不是后端联调。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import { MemoryRouter } from 'react-router';
import type { Notification, Page } from '../../../api/types';
import { ApiError } from '../../../api/client';
import NotificationInboxPage from '../pages/NotificationInboxPage';
import { listMyNotifications, markNotificationRead, markAllNotificationsRead, notificationKeys } from '../api';

vi.mock('../api', async (original) => ({ ...await original<typeof import('../api')>(), listMyNotifications: vi.fn(), markNotificationRead: vi.fn(), markAllNotificationsRead: vi.fn() }));
const notice = (overrides: Partial<Notification> = {}): Notification => ({
  id: 3, type: 'DUE_SOON', title: '到期前提醒', content: '请按时归还《活着》。', loanId: 10,
  book: { id: 5, title: '活着' }, copyBarcode: 'C00000011', dueDate: '2026-11-22',
  overdueDays: null, seq: null, createdAt: '2026-11-19T08:00:00Z', readAt: null,
  loanState: { status: 'ACTIVE', currentDueDate: '2026-11-22' }, ...overrides,
});
const pageOf = (items: Notification[], page = 0, totalItems = items.length): Page<Notification> => ({ items, page, size: 20, totalItems, totalPages: Math.ceil(totalItems / 20) });
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  const view = render(<QueryClientProvider client={client}><AntdApp><MemoryRouter><NotificationInboxPage /></MemoryRouter></AntdApp></QueryClientProvider>);
  return { client, ...view };
}
beforeEach(() => {
  vi.mocked(listMyNotifications).mockResolvedValue(pageOf([notice()]));
  vi.mocked(markNotificationRead).mockResolvedValue(undefined);
  vi.mocked(markAllNotificationsRead).mockResolvedValue({ updated: 1 });
});

describe('通知收件箱', () => {
  it('加载态，无自动已读，加载期间禁用全部已读', () => {
    vi.mocked(listMyNotifications).mockReturnValue(new Promise(() => {}));
    mount();
    expect(screen.getByText('正在加载提醒…')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '全部标为已读' })).toBeDisabled();
    expect(markNotificationRead).not.toHaveBeenCalled();
  });
  it('显示书名、条码、快照日和当前状态，说明登录触达及已读含义', async () => {
    mount();
    expect(await screen.findByText('活着')).toBeInTheDocument();
    expect(screen.getByText('C00000011')).toBeInTheDocument();
    expect(screen.getByText(/当前应还日：2026-11-22/)).toBeInTheDocument();
    expect(screen.getByText(/仅在登录系统后可查看/)).toBeInTheDocument();
    expect(screen.getByText(/不表示短信、邮件或电话已送达/)).toBeInTheDocument();
    expect(markNotificationRead).not.toHaveBeenCalled();
  });
  it('按服务端新到旧顺序渲染，同时间 id 顺序不被破坏', async () => {
    vi.mocked(listMyNotifications).mockResolvedValue(pageOf([notice({ id: 9, book: { id: 1, title: '最新图书' } }), notice({ id: 8 })]));
    mount();
    await screen.findByText('最新图书');
    const articles = screen.getAllByRole('article');
    expect(within(articles[0]).getByText('最新图书')).toBeInTheDocument();
    expect(within(articles[1]).getByText('活着')).toBeInTheDocument();
  });
  it('未读过滤与分页：0基分页，切换过滤回第一页', async () => {
    vi.mocked(listMyNotifications).mockImplementation(async (params) => pageOf([notice()], params.page, 42));
    mount();
    await screen.findByText('活着');
    fireEvent.click(screen.getByRole('button', { name: 'Page 2' }));
    await waitFor(() => expect(listMyNotifications).toHaveBeenLastCalledWith({ unreadOnly: false, page: 1, size: 20 }, expect.anything()));
    await userEvent.click(screen.getByRole('checkbox', { name: '仅看未读' }));
    await waitFor(() => expect(listMyNotifications).toHaveBeenLastCalledWith({ unreadOnly: true, page: 0, size: 20 }, expect.anything()));
  });
  it('单条已读刷新通知及角标缓存，不删除历史记录', async () => {
    const { client } = mount();
    // 保留未观察的计数查询以检查失效；布局测试另覆盖真实计数订阅的刷新。
    client.setQueryDefaults(notificationKeys.unreadCount, { gcTime: Infinity });
    client.setQueryData(notificationKeys.unreadCount, { unreadCount: 1 });
    await screen.findByText('活着');
    vi.mocked(listMyNotifications).mockResolvedValue(pageOf([notice({ readAt: '2026-11-19T09:00:00Z' })]));
    await userEvent.click(screen.getByRole('button', { name: '标为已读' }));
    await screen.findByText('已读', { exact: true });
    expect(markNotificationRead).toHaveBeenCalledWith(3);
    expect(client.getQueryState(notificationKeys.unreadCount)?.isInvalidated).toBe(true);
    expect(screen.getByText('活着')).toBeInTheDocument();
  });
  it('全部已读并刷新未读过滤到空态，禁重复提交', async () => {
    let finish!: (data: { updated: number }) => void;
    vi.mocked(markAllNotificationsRead).mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    mount();
    await screen.findByText('活着');
    await userEvent.click(screen.getByRole('checkbox', { name: '仅看未读' }));
    const button = screen.getByRole('button', { name: '全部标为已读' });
    await userEvent.click(button);
    expect(button).toBeDisabled();
    expect(screen.getByRole('button', { name: '标为已读' })).toBeDisabled();
    fireEvent.click(button);
    expect(markAllNotificationsRead).toHaveBeenCalledOnce();
    vi.mocked(listMyNotifications).mockResolvedValue(pageOf([]));
    finish({ updated: 1 });
    expect(await screen.findByText('暂无未读提醒')).toBeInTheDocument();
  });
  it('单条已读期间禁用所有写操作', async () => {
    vi.mocked(markNotificationRead).mockReturnValue(new Promise(() => {}));
    mount();
    await screen.findByText('活着');
    await userEvent.click(screen.getByRole('button', { name: '标为已读' }));
    expect(screen.getByRole('button', { name: '标为已读' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '全部标为已读' })).toBeDisabled();
  });
  it('续借旧通知保留未读，显示新应还日和历史提示，不提供续借按钮', async () => {
    vi.mocked(listMyNotifications).mockResolvedValue(pageOf([notice({ loanState: { status: 'ACTIVE', currentDueDate: '2026-12-22' } })]));
    mount();
    expect(await screen.findByText(/应还日已调整，请以当前应还日为准/)).toBeInTheDocument();
    expect(screen.getByText(/当前应还日：2026-12-22/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '标为已读' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '续借' })).not.toBeInTheDocument();
    expect(markNotificationRead).not.toHaveBeenCalled();
  });
  it('归还后的催还仅显示历史内容，明确无需再还书，仍不自动已读', async () => {
    vi.mocked(listMyNotifications).mockResolvedValue(pageOf([notice({ type: 'DUNNING', title: '催还通知', loanState: { status: 'RETURNED', currentDueDate: '2026-11-22' } })]));
    mount();
    expect(await screen.findByText(/该借阅已归还，无需再还书/)).toBeInTheDocument();
    expect(screen.getByText('历史通知内容（生成时）')).toBeInTheDocument();
    expect(screen.getByText('当前状态：已归还')).toBeInTheDocument();
    expect(markNotificationRead).not.toHaveBeenCalled();
  });
  it('逾期天数明确为生成时历史值，UTC 时间按上海显示', async () => {
    vi.mocked(listMyNotifications).mockResolvedValue(pageOf([notice({ type: 'OVERDUE', overdueDays: 8, seq: 2, createdAt: '2026-11-18T16:00:00Z', loanState: { status: 'OVERDUE', currentDueDate: '2026-11-22' } })]));
    mount();
    expect(await screen.findByText('当前状态：逾期未还')).toBeInTheDocument();
    expect(screen.getByText(/生成时逾期：8 天/)).toBeInTheDocument();
    expect(screen.getByText(/2026-11-19 00:00/)).toBeInTheDocument();
  });
  it('空态：保留触达说明与过滤，不错误显示加载', async () => {
    vi.mocked(listMyNotifications).mockResolvedValue(pageOf([]));
    mount();
    expect(await screen.findByText('暂无提醒')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '仅看未读' })).toBeInTheDocument();
  });
  it.each([[503, 'DB_BUSY', '系统繁忙'], [0, 'NETWORK_ERROR', '网络异常']])('错误 %s 可显式重试恢复，无自动重试', async (status, code, text) => {
    vi.mocked(listMyNotifications).mockRejectedValue(new ApiError(Number(status), code, 'raw'));
    mount();
    expect(await screen.findByText(new RegExp(text))).toBeInTheDocument();
    expect(listMyNotifications).toHaveBeenCalledOnce();
    vi.mocked(listMyNotifications).mockResolvedValue(pageOf([notice()]));
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('活着')).toBeInTheDocument();
  });
  it.each([[404, 'NOTIFICATION_NOT_FOUND', '通知不存在'], [403, 'CSRF_INVALID', '安全令牌'], [503, 'DB_BUSY', '系统繁忙']])('写操作错误 %s 不乐观已读，中文错误可重试', async (status, code, text) => {
    vi.mocked(markNotificationRead).mockRejectedValue(new ApiError(Number(status), code, 'raw'));
    mount();
    await screen.findByText('活着');
    await userEvent.click(screen.getByRole('button', { name: '标为已读' }));
    expect(await screen.findByText(new RegExp(text))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '标为已读' })).toBeEnabled();
    vi.mocked(markNotificationRead).mockResolvedValue(undefined);
    await userEvent.click(screen.getByRole('button', { name: '标为已读' }));
    await waitFor(() => expect(markNotificationRead).toHaveBeenCalledTimes(2));
  });
  it('外部文本不解析 HTML', async () => {
    vi.mocked(listMyNotifications).mockResolvedValue(pageOf([notice({ content: '<img src=x onerror=alert(1)>', title: '<script>evil</script>' })]));
    const { container } = mount();
    expect(await screen.findByText('<img src=x onerror=alert(1)>')).toBeInTheDocument();
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('article img')).toBeNull();
  });
});
