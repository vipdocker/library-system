// AC-AUTH-09 / DOD-04（前端）：router.tsx 注册了设计 10.1 的全部路由，
// 各路由按角色可达真实页面或正确跳转（403 / 登录页 / 角色重定向）。
// I-08 修订（approval-record §1.1，用户 2026-11-18 决定，T13 实施）：
// 原文件断言 T02 占位页（"建设中"），页面实现后已过期；现改为断言真实页面内容，
// 并补上 QueryClientProvider / antd App 包装；页面数据请求统一 mock api/client（不发真实请求）。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import { MemoryRouter } from 'react-router';
import type { BookDetail, Page, Reader, StatsOverview, UserInfo } from '../../api/types';
import type { AuthContextValue } from '../../auth/AuthContext';
import { useAuth } from '../../auth/AuthContext';
import { apiDelete, apiGet, apiPatch, apiPost, apiPut, bootstrapCsrf } from '../../api/client';
import { AppRoutes } from '../router';

vi.mock('../../auth/AuthContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../auth/AuthContext')>();
  return { ...actual, useAuth: vi.fn() };
});

// 页面数据请求全部走 mock：feature api.ts 内部调用 apiGet 等函数，这里按路径返回契约形状的空数据/夹具。
vi.mock('../../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api/client')>();
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
    apiPatch: vi.fn(),
    apiDelete: vi.fn(),
    bootstrapCsrf: vi.fn(),
  };
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

const emptyPage: Page<never> = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 };

function bookDetailFixture(id: number): BookDetail {
  return {
    id,
    isbn: '9780306406157',
    title: '深入理解计算机系统',
    author: 'Randal E. Bryant',
    publisher: '机械工业出版社',
    publishYear: 2016,
    category: { id: 3, name: '科技' },
    totalCopies: 3,
    availableCopies: 2,
    description: '经典教材。',
    createdAt: '2026-11-01T08:00:00Z',
    updatedAt: '2026-11-10T08:00:00Z',
  };
}

const readerProfile: Reader = {
  id: 2,
  cardNo: 'R000002',
  name: '演示读者二',
  phone: null,
  email: null,
  status: 'ACTIVE',
  username: 'reader2',
  activeLoanCount: 1,
  overdueLoanCount: 1,
  createdAt: '2026-10-01T08:00:00Z',
};

const overviewFixture: StatsOverview = {
  totalBooks: 0,
  totalCopies: 0,
  availableCopies: 0,
  borrowedCopies: 0,
  unavailableCopies: 0,
  totalReaders: 0,
  activeReaders: 0,
  activeLoans: 0,
  overdueLoans: 0,
  borrowsToday: 0,
  returnsToday: 0,
};

function loginAs(user: UserInfo | null) {
  const value: AuthContextValue = {
    status: user ? 'authenticated' : 'anonymous',
    user,
    login: vi.fn(),
    logout: vi.fn(),
    markLoggedOut: vi.fn(),
  };
  mockUseAuth.mockReturnValue(value);
}

function renderRoute(path: string) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={client}>
      <AntdApp>
        <MemoryRouter initialEntries={[path]}>
          <AppRoutes />
        </MemoryRouter>
      </AntdApp>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  loginAs(admin);
  vi.mocked(bootstrapCsrf).mockResolvedValue(undefined);
  vi.mocked(apiPost).mockResolvedValue(undefined as never);
  vi.mocked(apiPut).mockResolvedValue(undefined as never);
  vi.mocked(apiPatch).mockResolvedValue(undefined as never);
  vi.mocked(apiDelete).mockResolvedValue(undefined as never);
  vi.mocked(apiGet).mockImplementation(async (path: string) => {
    if (path === '/api/categories') return [] as never;
    if (path === '/api/books') return emptyPage as never;
    const bookMatch = /^\/api\/books\/(\d+)$/.exec(path);
    if (bookMatch) return bookDetailFixture(Number(bookMatch[1])) as never;
    if (/^\/api\/books\/\d+\/copies$/.test(path)) return [] as never;
    if (path === '/api/readers') return emptyPage as never;
    if (path === '/api/readers/me') return readerProfile as never;
    if (/^\/api\/readers\/\d+$/.test(path)) return readerProfile as never;
    if (path === '/api/loans' || path === '/api/loans/me') return emptyPage as never;
    if (path === '/api/stats/overview') return overviewFixture as never;
    if (path === '/api/stats/loans') return [] as never;
    if (path === '/api/stats/top-books') return [] as never;
    if (path === '/api/reports/overdue') return emptyPage as never;
    throw new Error(`router.test: unexpected GET ${path}`);
  });
});

describe('AC-AUTH-09 路由注册与守卫（设计 10.1 全表，真实页面）', () => {
  it('未登录访问 / → 登录页', async () => {
    loginAs(null);
    renderRoute('/');
    expect(await screen.findByText('欢迎回来')).toBeInTheDocument();
  });

  it('ADMIN 访问 / → 重定向到 /admin/dashboard', async () => {
    renderRoute('/');
    expect((await screen.findAllByText('仪表盘')).length).toBeGreaterThan(0);
  });

  it('READER 访问 / → 重定向到 /books', async () => {
    loginAs(reader);
    renderRoute('/');
    expect((await screen.findAllByText('图书检索')).length).toBeGreaterThan(0);
  });

  // [路径, 真实页面可断言文本, 是否正则]
  const adminRoutes: Array<[string, string | RegExp]> = [
    ['/admin/dashboard', '仪表盘'],
    ['/admin/books', '图书管理'],
    ['/admin/books/new', '新建图书'],
    ['/admin/books/3/edit', /编辑图书：/],
    ['/admin/categories', '分类管理'],
    ['/admin/readers', '读者管理'],
    ['/admin/readers/2', '演示读者二'],
    ['/admin/circulation', '流通柜台'],
    ['/admin/loans', '借阅记录'],
    ['/admin/reports/overdue', '逾期报表'],
    ['/admin/stats', '借阅统计'],
    ['/books', '图书检索'],
    ['/books/5', '深入理解计算机系统'],
    ['/account/password', '修改口令'],
  ];

  for (const [path, text] of adminRoutes) {
    it(`ADMIN 可达 ${path}（真实页面）`, async () => {
      renderRoute(path);
      expect((await screen.findAllByText(text)).length).toBeGreaterThan(0);
      expect(screen.queryByText('建设中')).not.toBeInTheDocument();
    });
  }

  const readerRoutes: Array<[string, string | RegExp]> = [
    ['/books', '图书检索'],
    ['/books/5', '深入理解计算机系统'],
    ['/my/loans', '我的借阅'],
    ['/my/profile', '我的资料'],
    ['/account/password', '修改口令'],
  ];

  for (const [path, text] of readerRoutes) {
    it(`READER 可达 ${path}（真实页面）`, async () => {
      loginAs(reader);
      renderRoute(path);
      expect((await screen.findAllByText(text)).length).toBeGreaterThan(0);
      expect(screen.queryByText('建设中')).not.toBeInTheDocument();
    });
  }

  it('READER 访问 /admin/books → 403 页', async () => {
    loginAs(reader);
    renderRoute('/admin/books');
    expect(await screen.findByText('无权限访问该页面')).toBeInTheDocument();
  });

  it('ADMIN 访问 /my/loans → 403 页', async () => {
    renderRoute('/my/loans');
    expect(await screen.findByText('无权限访问该页面')).toBeInTheDocument();
  });

  it('未知路由 → 404 页', async () => {
    renderRoute('/definitely-not-a-page');
    expect(await screen.findByText('页面不存在')).toBeInTheDocument();
  });

  it('/403 直接可达', async () => {
    renderRoute('/403');
    expect(await screen.findByText('无权限访问该页面')).toBeInTheDocument();
  });
});