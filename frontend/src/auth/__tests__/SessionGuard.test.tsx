// AC-AUTH-09：任意接口 401 → 清 Query 缓存、清登录态、提示会话过期、跳转 /login?redirect=<原路径>。
import { describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import { UNAUTHORIZED_EVENT } from '../../api/client';
import { queryClient } from '../../api/queryClient';
import type { AuthContextValue } from '../AuthContext';
import { useAuth } from '../AuthContext';
import { SessionGuard } from '../SessionGuard';

vi.mock('../AuthContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../AuthContext')>();
  return { ...actual, useAuth: vi.fn() };
});

const mockUseAuth = vi.mocked(useAuth);

function LocationProbe() {
  const location = useLocation();
  return <div data-testid="probe">{`${location.pathname}${location.search}`}</div>;
}

describe('AC-AUTH-09 SessionGuard 会话过期处理', () => {
  it('401 全局事件 → 清缓存与登录态、提示过期、带 redirect 跳登录页', async () => {
    const markLoggedOut = vi.fn();
    const value: AuthContextValue = {
      status: 'authenticated',
      user: {
        id: 1,
        username: 'admin',
        role: 'ADMIN',
        displayName: '管理员',
        readerId: null,
        cardNo: null,
      },
      login: vi.fn(),
      logout: vi.fn(),
      markLoggedOut,
    };
    mockUseAuth.mockReturnValue(value);
    const clearSpy = vi.spyOn(queryClient, 'clear');

    render(
      <MemoryRouter initialEntries={['/books?q=java']}>
        <AntdApp>
          <SessionGuard />
          <Routes>
            <Route path="/books" element={<div>书页</div>} />
            <Route path="/login" element={<LocationProbe />} />
          </Routes>
        </AntdApp>
      </MemoryRouter>,
    );

    act(() => {
      window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT, { detail: { path: '/api/books' } }));
    });

    await waitFor(() => {
      expect(screen.getByTestId('probe').textContent).toBe('/login?redirect=%2Fbooks%3Fq%3Djava');
    });
    expect(markLoggedOut).toHaveBeenCalledTimes(1);
    expect(clearSpy).toHaveBeenCalled();
    expect(await screen.findByText('登录已过期，请重新登录')).toBeInTheDocument();
  });

  it('已在 /login 时不再叠加 redirect 参数', async () => {
    const value: AuthContextValue = {
      status: 'anonymous',
      user: null,
      login: vi.fn(),
      logout: vi.fn(),
      markLoggedOut: vi.fn(),
    };
    mockUseAuth.mockReturnValue(value);

    render(
      <MemoryRouter initialEntries={['/login']}>
        <AntdApp>
          <SessionGuard />
          <Routes>
            <Route path="/login" element={<LocationProbe />} />
          </Routes>
        </AntdApp>
      </MemoryRouter>,
    );

    act(() => {
      window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT));
    });

    await waitFor(() => {
      expect(screen.getByTestId('probe').textContent).toBe('/login');
    });
  });
});