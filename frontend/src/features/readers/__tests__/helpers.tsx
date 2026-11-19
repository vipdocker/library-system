// T09 测试共享夹具与渲染辅助（非测试文件，vitest 不会收集）。
import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { App as AntdApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { Loan, Page, Reader } from '../../../api/types';

export function pageOf<T>(items: T[], page = 0, size = 20, totalItems?: number): Page<T> {
  const total = totalItems ?? items.length;
  return {
    items,
    page,
    size,
    totalItems: total,
    totalPages: Math.ceil(total / size),
  };
}

export const reader1: Reader = {
  id: 1,
  cardNo: 'R000001',
  name: '张三',
  phone: '13800000000',
  email: 'zhang@example.com',
  status: 'ACTIVE',
  username: 'reader1',
  activeLoanCount: 2,
  overdueLoanCount: 1,
  createdAt: '2026-11-01T08:00:00Z',
};

export const reader2: Reader = {
  id: 2,
  cardNo: 'R000002',
  name: '李四',
  phone: null,
  email: null,
  status: 'DISABLED',
  username: 'reader2',
  activeLoanCount: 0,
  overdueLoanCount: 0,
  createdAt: '2026-11-02T08:00:00Z',
};

export const reader3: Reader = {
  ...reader1,
  id: 3,
  cardNo: 'R000003',
  name: '王五',
  username: 'reader3',
  activeLoanCount: 0,
  overdueLoanCount: 0,
};

export const loanActive: Loan = {
  id: 11,
  reader: { id: 1, cardNo: 'R000001', name: '张三' },
  copy: { id: 101, barcode: 'C00000101' },
  book: { id: 201, title: '三体', author: '刘慈欣', isbn: '9787536692930' },
  borrowDate: '2026-11-01',
  dueDate: '2026-12-01',
  returnDate: null,
  renewCount: 0,
  maxRenewals: 1,
  status: 'ACTIVE',
  overdueDays: 0,
  renewable: true,
};

export const loanOverdue: Loan = {
  ...loanActive,
  id: 12,
  copy: { id: 102, barcode: 'C00000102' },
  book: { id: 202, title: '活着', author: '余华', isbn: '9787506365437' },
  borrowDate: '2026-09-01',
  dueDate: '2026-10-01',
  status: 'OVERDUE',
  overdueDays: 48,
  renewable: false,
};

export const loanReturned: Loan = {
  ...loanActive,
  id: 13,
  copy: { id: 103, barcode: 'C00000103' },
  book: { id: 203, title: '围城', author: '钱锺书', isbn: '9787020024759' },
  borrowDate: '2026-09-20',
  dueDate: '2026-10-20',
  returnDate: '2026-10-15',
  status: 'RETURNED',
};

export interface RenderPageOptions {
  route?: string;
  path?: string;
}

/** 包裹 QueryClient（不重试）+ antd App（message 上下文）+ MemoryRouter 渲染页面。 */
export function renderPage(ui: ReactElement, { route = '/', path = '/' }: RenderPageOptions = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AntdApp>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route path={path} element={ui} />
          </Routes>
        </MemoryRouter>
      </AntdApp>
    </QueryClientProvider>,
  );
}