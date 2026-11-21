// AC-NTF-01 / AC-UI-05 v1.1：菜单角标只在打开/导航/写操作刷新，无轮询。
import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import { App as AntdApp } from 'antd';
import { AppLayout } from '../AppLayout';
import { useAuth } from '../../../auth/AuthContext';
import { getUnreadCount, notificationKeys } from '../../../features/notifications/api';

vi.mock('../../../auth/AuthContext', () => ({ useAuth: vi.fn() }));
vi.mock('../../../features/notifications/api', async (original) => ({ ...await original<typeof import('../../../features/notifications/api')>(), getUnreadCount: vi.fn() }));
function mount(role: 'READER' | 'ADMIN' = 'READER') {
  vi.mocked(useAuth).mockReturnValue({ status: 'authenticated', user: { id: 2, username: 'reader', displayName: '读者', role, readerId: role === 'READER' ? 1 : null, cardNo: null }, logout: vi.fn(), login: vi.fn(), markLoggedOut: vi.fn() });
  vi.mocked(getUnreadCount).mockResolvedValue({ unreadCount: 3 });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  const view = render(<QueryClientProvider client={client}><AntdApp><MemoryRouter initialEntries={['/my/notifications']}><Routes><Route element={<AppLayout />}><Route path="*" element={<div>页面</div>} /></Route></Routes></MemoryRouter></AntdApp></QueryClientProvider>);
  return { client, ...view };
}
describe('提醒角标', () => {
  it('读者打开与切换页面立即刷新，不触碰全局默认配置', async () => {
    const { client } = mount();
    expect(await screen.findByLabelText('未读提醒 3 条')).toBeInTheDocument();
    expect(screen.getByLabelText('未读提醒 3 条')).toHaveClass('ant-menu-item-icon');
    const calls = vi.mocked(getUnreadCount).mock.calls.length;
    vi.mocked(getUnreadCount).mockResolvedValue({ unreadCount: 7 });
    await userEvent.click(screen.getByText('我的借阅'));
    expect(await screen.findByLabelText('未读提醒 7 条')).toBeInTheDocument();
    expect(vi.mocked(getUnreadCount).mock.calls.length).toBeGreaterThan(calls);
    const query = client.getQueryCache().find({ queryKey: notificationKeys.unreadCount });
    expect(query?.options).toMatchObject({ refetchInterval: false, refetchOnWindowFocus: false, refetchOnReconnect: false, retry: false });
    expect(client.getDefaultOptions().queries?.staleTime).toBeUndefined();
  });
  it('已读失效会刷新角标', async () => {
    const { client } = mount();
    await screen.findByLabelText('未读提醒 3 条');
    vi.mocked(getUnreadCount).mockResolvedValue({ unreadCount: 0 });
    await client.invalidateQueries({ queryKey: notificationKeys.all });
    expect(await screen.findByLabelText('未读提醒 0 条')).toBeInTheDocument();
  });
  it('ADMIN 只有催还菜单，不渲染提醒或调用读者计数', async () => {
    mount('ADMIN');
    expect(screen.getByText('催还记录')).toBeInTheDocument();
    expect(screen.queryByText('我的提醒')).not.toBeInTheDocument();
    await waitFor(() => expect(getUnreadCount).not.toHaveBeenCalled());
  });
  it('ADMIN 不挂载读者查询组件，保留原布局不依赖 QueryClient 的使用方式', () => {
    vi.mocked(useAuth).mockReturnValue({ status: 'authenticated', user: { id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN', readerId: null, cardNo: null }, logout: vi.fn(), login: vi.fn(), markLoggedOut: vi.fn() });
    render(<AntdApp><MemoryRouter><AppLayout /></MemoryRouter></AntdApp>);
    expect(screen.getByText('催还记录')).toBeInTheDocument();
    expect(getUnreadCount).not.toHaveBeenCalled();
  });

  it('计数失败不伪装成 0，允许显式重试', async () => {
    const { client } = mount();
    await screen.findByLabelText('未读提醒 3 条');
    vi.mocked(getUnreadCount).mockRejectedValue(new Error('offline'));
    await client.invalidateQueries({ queryKey: notificationKeys.all });
    expect(await screen.findByRole('button', { name: '未读数加载失败，重试' })).toBeInTheDocument();
    vi.mocked(getUnreadCount).mockResolvedValue({ unreadCount: 4 });
    await userEvent.click(screen.getByRole('button', { name: '未读数加载失败，重试' }));
    expect(await screen.findByLabelText('未读提醒 4 条')).toBeInTheDocument();
  });
});
