// circulation 测试共用：Provider 组合（QueryClient + antd App + MemoryRouter）与契约样例数据。
// 后端借阅接口尚未实现（T04–T07 开发中），所有测试一律 mock features/circulation/api.ts。
import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router';
import type { Loan, Page, Reader } from '../../../api/types';

export function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location-probe">{`${location.pathname}${location.search}`}</div>;
}

export function renderCirculation(
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

export const readerFixture: Reader = {
  id: 1,
  cardNo: 'R000001',
  name: '读者一',
  phone: '13800000001',
  email: 'reader1@example.com',
  status: 'ACTIVE',
  username: 'reader1',
  activeLoanCount: 1,
  overdueLoanCount: 0,
  createdAt: '2026-10-01T08:00:00Z',
};

/** 契约样例（第 8.3 节 Loan DTO），可用 overrides 派生在借 / 逾期 / 已归还等形态。 */
export function makeLoan(overrides: Partial<Loan> = {}): Loan {
  return {
    id: 101,
    reader: { id: 1, cardNo: 'R000001', name: '读者一' },
    copy: { id: 11, barcode: 'C00000011' },
    book: { id: 5, title: '活着', author: '余华', isbn: '9787506365437' },
    borrowDate: '2026-11-01',
    dueDate: '2026-12-01',
    returnDate: null,
    renewCount: 0,
    maxRenewals: 1,
    status: 'ACTIVE',
    overdueDays: 0,
    renewable: true,
    ...overrides,
  };
}

export function makeOverdueLoan(overrides: Partial<Loan> = {}): Loan {
  return makeLoan({
    id: 102,
    copy: { id: 12, barcode: 'C00000012' },
    book: { id: 6, title: '百年孤独', author: '加西亚·马尔克斯', isbn: '9787544253994' },
    borrowDate: '2026-10-09',
    dueDate: '2026-11-08',
    status: 'OVERDUE',
    overdueDays: 10,
    renewable: false,
    ...overrides,
  });
}

export function makeReturnedLoan(overrides: Partial<Loan> = {}): Loan {
  return makeLoan({
    id: 103,
    copy: { id: 13, barcode: 'C00000013' },
    book: { id: 7, title: '围城', author: '钱钟书', isbn: '9787020024759' },
    status: 'RETURNED',
    returnDate: '2026-11-10',
    renewable: false,
    ...overrides,
  });
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