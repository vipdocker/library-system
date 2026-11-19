// AC-AUTH-01 / AC-AUTH-09：LoginPage 提交、失败文案、成功后回到站内 redirect。
import { beforeEach, describe, expect, it, vi, type Mock } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { ApiError } from '../../api/client';
import type { UserInfo } from '../../api/types';
import type { AuthContextValue } from '../AuthContext';
import { useAuth } from '../AuthContext';
import LoginPage from '../LoginPage';

vi.mock('../AuthContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../AuthContext')>();
  return { ...actual, useAuth: vi.fn() };
});

const mockUseAuth = vi.mocked(useAuth);

const admin: UserInfo = {
  id: 1,
  username: 'admin',
  role: 'ADMIN',
  displayName: '管理员',
  readerId: null,
  cardNo: null,
};

function LocationProbe({ name }: { name: string }) {
  const location = useLocation();
  return <div data-testid={`probe-${name}`}>{`${location.pathname}${location.search}`}</div>;
}

function renderLogin(initialEntry = '/login') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/" element={<LocationProbe name="root" />} />
        <Route path="/my/loans" element={<LocationProbe name="loans" />} />
        <Route path="/admin/dashboard" element={<LocationProbe name="dash" />} />
      </Routes>
    </MemoryRouter>,
  );
}

type LoginFn = (username: string, password: string) => Promise<UserInfo>;
let loginMock: Mock<LoginFn>;

beforeEach(() => {
  loginMock = vi.fn<LoginFn>();
  const value: AuthContextValue = {
    status: 'anonymous',
    user: null,
    login: loginMock,
    logout: vi.fn(),
    markLoggedOut: vi.fn(),
  };
  mockUseAuth.mockReturnValue(value);
});

async function submitCredentials(username = 'admin', password = 'Admin@12345') {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('用户名'), username);
  await user.type(screen.getByLabelText('密码'), password);
  await user.click(screen.getByRole('button', { name: /登\s*录/ }));
}

describe('AC-AUTH-01 LoginPage 登录流程', () => {
  it('提交用户名和密码调用 login；成功后回到 redirect 指向的站内页面', async () => {
    loginMock.mockResolvedValue(admin);
    renderLogin('/login?redirect=%2Fadmin%2Fdashboard');
    await submitCredentials();

    expect(loginMock).toHaveBeenCalledWith('admin', 'Admin@12345');
    await waitFor(() => {
      expect(screen.getByTestId('probe-dash').textContent).toBe('/admin/dashboard');
    });
  });

  it('无 redirect 时登录后回到 /', async () => {
    loginMock.mockResolvedValue(admin);
    renderLogin();
    await submitCredentials();
    await waitFor(() => {
      expect(screen.getByTestId('probe-root').textContent).toBe('/');
    });
  });

  it('AC-AUTH-09 redirect 为外部地址时拒绝回跳，落到 /', async () => {
    loginMock.mockResolvedValue(admin);
    renderLogin(`/login?redirect=${encodeURIComponent('https://evil.example')}`);
    await submitCredentials();
    await waitFor(() => {
      expect(screen.getByTestId('probe-root').textContent).toBe('/');
    });
  });

  it('AC-AUTH-02 凭据错误时显示统一中文文案，不跳转', async () => {
    loginMock.mockRejectedValue(new ApiError(401, 'AUTH_INVALID_CREDENTIALS', 'server message'));
    renderLogin();
    await submitCredentials('admin', 'wrong-pass1');

    expect(await screen.findByRole('alert')).toHaveTextContent('用户名或密码错误');
    expect(screen.queryByTestId('probe-root')).not.toBeInTheDocument();
  });

  it('停用账号显示对应中文文案', async () => {
    loginMock.mockRejectedValue(new ApiError(401, 'AUTH_ACCOUNT_DISABLED', 'server message'));
    renderLogin();
    await submitCredentials('reader3', 'Reader@123');
    expect(await screen.findByRole('alert')).toHaveTextContent('账号已停用，请联系管理员');
  });

  it('必填校验：空提交不触发 login', async () => {
    const user = userEvent.setup();
    renderLogin();
    await user.click(screen.getByRole('button', { name: /登\s*录/ }));
    expect(await screen.findByText('请输入用户名')).toBeInTheDocument();
    expect(screen.getByText('请输入密码')).toBeInTheDocument();
    expect(loginMock).not.toHaveBeenCalled();
  });
});