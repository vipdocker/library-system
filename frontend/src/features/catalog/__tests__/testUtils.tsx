// catalog 测试共用：Provider 组合（QueryClient + antd App + MemoryRouter）与契约样例数据。
// 后端此时尚未实现（T04–T07 开发中），所有测试一律 mock features/catalog/api.ts。
import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type {
  BookDetail,
  BookSummary,
  Category,
  Copy,
  Page,
  UserInfo,
} from '../../../api/types';

export function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{`${location.pathname}${location.search}`}</div>;
}

export function renderCatalog(
  ui: ReactElement,
  { route = '/', path = '/' }: { route?: string; path?: string } = {},
) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  return render(
    <QueryClientProvider client={client}>
      <AntdApp>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route
              path={path}
              element={
                <>
                  {ui}
                  <LocationProbe />
                </>
              }
            />
            <Route path="*" element={<LocationProbe />} />
          </Routes>
        </MemoryRouter>
      </AntdApp>
    </QueryClientProvider>,
  );
}

export const adminUser: UserInfo = {
  id: 1,
  username: 'admin',
  role: 'ADMIN',
  displayName: '管理员',
  readerId: null,
  cardNo: null,
};

export const readerUser: UserInfo = {
  id: 2,
  username: 'reader1',
  role: 'READER',
  displayName: '读者一',
  readerId: 1,
  cardNo: 'R000001',
};

export const categoryFixture: Category = {
  id: 2,
  name: '文学',
  description: '小说与散文',
  bookCount: 3,
};

export const categoriesFixture: Category[] = [
  categoryFixture,
  { id: 3, name: '科技', description: null, bookCount: 0 },
];

export function makeBook(overrides: Partial<BookSummary> = {}): BookSummary {
  return {
    id: 5,
    isbn: '9780306406157',
    title: '深入理解计算机系统',
    author: 'Randal E. Bryant',
    publisher: '机械工业出版社',
    publishYear: 2016,
    category: { id: 3, name: '科技' },
    totalCopies: 3,
    availableCopies: 2,
    ...overrides,
  };
}

export function makeBookDetail(overrides: Partial<BookDetail> = {}): BookDetail {
  return {
    ...makeBook(),
    description: '从程序员视角讲解计算机系统的经典教材。',
    createdAt: '2026-11-01T08:00:00Z',
    updatedAt: '2026-11-10T08:00:00Z',
    ...overrides,
  };
}

export function makePage<T>(items: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return {
    items,
    page: 0,
    size: 20,
    totalItems: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    ...overrides,
  };
}

export function makeCopy(overrides: Partial<Copy> = {}): Copy {
  return {
    id: 11,
    bookId: 5,
    barcode: 'C00000011',
    status: 'AVAILABLE',
    createdAt: '2026-11-01T08:00:00Z',
    ...overrides,
  };
}