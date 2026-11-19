// AC-AUTH-09：RequireAuth 未登录跳转 /login?redirect=...；RequireRole 角色不符跳 /403。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type { AuthContextValue } from '../AuthContext';
import { useAuth } from '../AuthContext';
import { RequireAuth } from '../RequireAuth';
import { RequireRole } from '../RequireRole';
import type { UserInfo } from '../../api/types';

vi.mock('../AuthContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../AuthContext')>();
  return { ...actual, useAuth: vi.fn() };
});

const mockUseAuth = vi.mocked(useAuth);

const reader: UserInfo = {
  id: 2,
  username: 'reader1',
  role: 'READER',
  displayName: '读者一',
  readerId: 1,
  cardNo: 'R000001',
};
const admin: UserInfo = {
  id: 1,
  username: 'admin',
  role: 'ADMIN',
  displayName: '管理员',
  readerId: null,
  cardNo: null,
};

function authValue(overrides: Partial<AuthContextValue>): AuthContextValue {
  return {
    status: 'anonymous',
    user: null,
    login: vi.fn(),
    logout: vi.fn(),
    markLoggedOut: vi.fn(),
    ...overrides,
  };
}

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="probe">{`${location.pathname}${location.search}`}</div>;
}

function renderAt(path: string, ui: ReactNode) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<div>登录页<LocationProbe /></div>} />
        <Route path="/403" element={<div>403页<LocationProbe /></div>} />
        <Route path="/admin/books" element={ui} />
        <Route path="/books" element={ui} />
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  mockUseAuth.mockReturnValue(authValue({}));
});

describe('AC-AUTH-09 RequireAuth', () => {
  it('未登录访问受保护路由 → /login?redirect=<原路径（含 query）>', () => {
    renderAt('/admin/books', <RequireAuth><div>受保护内容</div></RequireAuth>);
    expect(screen.getByText('登录页')).toBeInTheDocument();
    expect(screen.getByTestId('probe').textContent).toBe(
      '/login?redirect=%2Fadmin%2Fbooks',
    );
    expect(screen.queryByText('受保护内容')).not.toBeInTheDocument();
  });

  it('loading 时显示加载态，不跳转', () => {
    mockUseAuth.mockReturnValue(authValue({ status: 'loading' }));
    renderAt('/books', <RequireAuth><div>受保护内容</div></RequireAuth>);
    expect(screen.getByText('正在确认登录状态…')).toBeInTheDocument();
    expect(screen.queryByText('登录页')).not.toBeInTheDocument();
  });

  it('已登录时渲染受保护内容', () => {
    mockUseAuth.mockReturnValue(authValue({ status: 'authenticated', user: reader }));
    renderAt('/books', <RequireAuth><div>受保护内容</div></RequireAuth>);
    expect(screen.getByText('受保护内容')).toBeInTheDocument();
  });

  it('I-09：主动登出（loggedOutExplicitly）→ /login 不带 redirect', () => {
    mockUseAuth.mockReturnValue(authValue({ loggedOutExplicitly: true }));
    renderAt('/admin/books', <RequireAuth><div>受保护内容</div></RequireAuth>);
    expect(screen.getByText('登录页')).toBeInTheDocument();
    expect(screen.getByTestId('probe').textContent).toBe('/login');
  });
});

describe('AC-AUTH-09 RequireRole', () => {
  it('READER 访问 ADMIN 守卫 → /403', () => {
    mockUseAuth.mockReturnValue(authValue({ status: 'authenticated', user: reader }));
    renderAt('/admin/books', <RequireRole role="ADMIN"><div>管理内容</div></RequireRole>);
    expect(screen.getByText('403页')).toBeInTheDocument();
    expect(screen.queryByText('管理内容')).not.toBeInTheDocument();
  });

  it('ADMIN 访问 ADMIN 守卫 → 正常渲染', () => {
    mockUseAuth.mockReturnValue(authValue({ status: 'authenticated', user: admin }));
    renderAt('/admin/books', <RequireRole role="ADMIN"><div>管理内容</div></RequireRole>);
    expect(screen.getByText('管理内容')).toBeInTheDocument();
  });

  it('ADMIN 访问 READER 守卫 → /403（/my/* 仅读者）', () => {
    mockUseAuth.mockReturnValue(authValue({ status: 'authenticated', user: admin }));
    renderAt('/books', <RequireRole role="READER"><div>读者内容</div></RequireRole>);
    expect(screen.getByText('403页')).toBeInTheDocument();
  });
});