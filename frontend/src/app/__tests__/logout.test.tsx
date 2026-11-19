// I-09 回归（approval-record §1.1，用户 2026-11-18 决定）：
// 管理员在受保护页点击"退出登录"后，URL 必须是 /login 且不带 redirect 参数；
// 未登录直接访问受保护页时 RequireAuth 仍保留 redirect（AC-AUTH-09，见 guards.test.tsx）。
// 使用真实 AuthProvider + RequireAuth + AppLayout（mock api/client），复现登出竞态。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';
import { AuthProvider } from '../../auth/AuthContext';
import { RequireAuth } from '../../auth/RequireAuth';
import { SessionGuard } from '../../auth/SessionGuard';
import LoginPage from '../../auth/LoginPage';
import { UNAUTHORIZED_EVENT } from '../../api/client';
import { AppLayout } from '../layouts/AppLayout';
import { queryClient } from '../../api/queryClient';
import { apiGet, apiPost, bootstrapCsrf } from '../../api/client';
import type { UserInfo } from '../../api/types';

vi.mock('../../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/client')>();
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    bootstrapCsrf: vi.fn(),
  };
});

const admin: UserInfo = {
  id: 1,
  username: 'admin',
  role: 'ADMIN',
  displayName: '管理员',
  readerId: null,
  cardNo: null,
};

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{`${location.pathname}${location.search}`}</div>;
}

function NavToProtected() {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => navigate('/admin/readers')}>
      去受保护页
    </button>
  );
}

function renderApp(initialPath: string) {
  return render(
    <AntdApp>
      <AuthProvider>
        <MemoryRouter initialEntries={[initialPath]}>
          <SessionGuard />
          <LocationProbe />
          <NavToProtected />
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route
              path="/"
              element={
                <RequireAuth>
                  <AppLayout />
                </RequireAuth>
              }
            >
              <Route path="admin/readers" element={<div>读者管理页内容</div>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </AntdApp>,
  );
}

beforeEach(() => {
  queryClient.clear();
  vi.mocked(bootstrapCsrf).mockResolvedValue(undefined);
  vi.mocked(apiGet).mockImplementation(async (path: string) => {
    if (path === '/api/auth/me') return admin as never;
    throw new Error(`unexpected GET ${path}`);
  });
  // 模拟真实网络延迟：logout 响应在独立宏任务中返回，
  // 让 markLoggedOut 的状态更新与 onLogout 的后续 navigate 交错（E2E E-01 竞态条件）。
  vi.mocked(apiPost).mockImplementation(async (path: string) => {
    if (path === '/api/auth/logout') {
      await new Promise((resolve) => setTimeout(resolve, 10));
      return undefined as never;
    }
    throw new Error(`unexpected POST ${path}`);
  });
});

describe('I-09 / AC-AUTH-07 / AC-AUTH-09 登出跳转', () => {
  it('管理员在 /admin/readers 登出后 URL 为 /login 且无 redirect 参数', async () => {
    renderApp('/admin/readers');
    expect(await screen.findByText('读者管理页内容')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /退出登录/ }));

    await waitFor(() => {
      expect(screen.getByTestId('location-probe').textContent).toBe('/login');
    });
    expect(await screen.findByText('欢迎回来')).toBeInTheDocument();
    expect(vi.mocked(apiPost)).toHaveBeenCalledWith('/api/auth/logout');
    // 再等一个宏任务，确认没有迟到的 Navigate 把 redirect 追加回来（E-01 竞态）
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(screen.getByTestId('location-probe').textContent).toBe('/login');
  });

  it('未登录直接访问受保护页仍保留 redirect（AC-AUTH-09 不变）', async () => {
    vi.mocked(apiGet).mockImplementation(async (path: string) => {
      if (path === '/api/auth/me') throw new Error('401');
      throw new Error(`unexpected GET ${path}`);
    });
    renderApp('/admin/readers');
    await waitFor(() => {
      expect(screen.getByTestId('location-probe').textContent).toBe(
        '/login?redirect=%2Fadmin%2Freaders',
      );
    });
  });

  it('I-09：登出期间与登出后到达的迟到 401 不追加 redirect、不提示会话过期', async () => {
    renderApp('/admin/readers');
    expect(await screen.findByText('读者管理页内容')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: /退出登录/ }));
    // logout 响应尚未返回（mock 延迟 10ms）：在途请求此刻收到 401
    window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT, { detail: { path: '/api/readers' } }));
    await waitFor(() => {
      expect(screen.getByTestId('location-probe').textContent).toBe('/login');
    });
    // 登出完成后再来一个迟到 401
    window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT, { detail: { path: '/api/readers' } }));
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(screen.getByTestId('location-probe').textContent).toBe('/login');
    expect(screen.queryByText('登录已过期，请重新登录')).not.toBeInTheDocument();
  });

  it('I-09：登出意图是一次性的——登出后再直接访问受保护页仍保留 redirect', async () => {
    renderApp('/admin/readers');
    expect(await screen.findByText('读者管理页内容')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /退出登录/ }));
    await waitFor(() => {
      expect(screen.getByTestId('location-probe').textContent).toBe('/login');
    });
    // 登出后再次直接访问受保护页：意图已被消费，恢复 redirect 行为
    await userEvent.click(screen.getByRole('button', { name: '去受保护页' }));
    await waitFor(() => {
      expect(screen.getByTestId('location-probe').textContent).toBe(
        '/login?redirect=%2Fadmin%2Freaders',
      );
    });
  });
});