// I-05 回归（AC-BOOK-03：借出或归还后检索结果与图书详情的可借数量立即反映变化）：
// 全局 queryClient staleTime=30s（api/queryClient.ts），借/还 mutation 只失效 circulation 前缀
// 会让 /books 检索结果与图书详情在 30s 内继续显示旧的 availableCopies。
// 本测试断言：柜台借书、柜台还书、借阅列表行还书成功后，catalog 前缀的查询也被失效。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router';
import type { Loan } from '../../../api/types';
import { DEFAULT_BOOK_QUERY, catalogKeys } from '../../catalog/api';
import {
  borrowLoan,
  findReaderByCardNo,
  listLoans,
  renewLoan,
  returnLoan,
  returnLoanByBarcode,
  searchReaders,
} from '../api';
import CirculationDeskPage from '../pages/CirculationDeskPage';
import AdminLoanListPage from '../pages/AdminLoanListPage';
import { makeLoan, makePage, makeReturnedLoan, readerFixture } from './testUtils';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    borrowLoan: vi.fn(),
    returnLoanByBarcode: vi.fn(),
    returnLoan: vi.fn(),
    renewLoan: vi.fn(),
    listLoans: vi.fn(),
    findReaderByCardNo: vi.fn(),
    searchReaders: vi.fn(),
  };
});

const booksKey = catalogKeys.books(DEFAULT_BOOK_QUERY);
const bookDetailKey = catalogKeys.book(5);

function renderWithClient(ui: ReactElement, path: string) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity, staleTime: 30_000 },
      mutations: { retry: false },
    },
  });
  // 预置检索结果与图书详情缓存（模拟用户先浏览过 /books 与 /books/5）
  client.setQueryData(booksKey, makePage([]));
  client.setQueryData(bookDetailKey, { id: 5, availableCopies: 2 });
  render(
    <QueryClientProvider client={client}>
      <AntdApp>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path={path} element={ui} />
          </Routes>
        </MemoryRouter>
      </AntdApp>
    </QueryClientProvider>,
  );
  return client;
}

async function expectCatalogInvalidated(client: QueryClient) {
  await waitFor(() => {
    expect(client.getQueryState(booksKey)?.isInvalidated).toBe(true);
    expect(client.getQueryState(bookDetailKey)?.isInvalidated).toBe(true);
  });
}

beforeEach(() => {
  vi.mocked(borrowLoan).mockResolvedValue(makeLoan());
  vi.mocked(returnLoanByBarcode).mockResolvedValue(makeReturnedLoan());
  vi.mocked(returnLoan).mockResolvedValue(makeReturnedLoan());
  vi.mocked(renewLoan).mockResolvedValue(makeLoan());
  vi.mocked(listLoans).mockResolvedValue(makePage<Loan>([makeLoan()]));
  vi.mocked(findReaderByCardNo).mockResolvedValue(readerFixture);
  vi.mocked(searchReaders).mockResolvedValue(makePage([readerFixture]));
});

describe('I-05 / AC-BOOK-03 借还后 catalog 缓存失效', () => {
  it('柜台借书成功后，检索结果与图书详情缓存被失效', async () => {
    const client = renderWithClient(<CirculationDeskPage />, '/admin/circulation');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('读者卡号'), 'R000001');
    await user.type(screen.getByLabelText('副本条码'), 'C00000011{Enter}');
    expect(await screen.findByText('借出成功')).toBeInTheDocument();
    await expectCatalogInvalidated(client);
  });

  it('柜台还书成功后，检索结果与图书详情缓存被失效', async () => {
    const client = renderWithClient(<CirculationDeskPage />, '/admin/circulation');
    const user = userEvent.setup();
    await user.click(screen.getByRole('tab', { name: '还书' }));
    const barcodeInput = screen.getByLabelText('副本条码');
    await user.type(barcodeInput, 'C00000013{Enter}');
    await waitFor(() => expect(vi.mocked(returnLoanByBarcode)).toHaveBeenCalled());
    await expectCatalogInvalidated(client);
  });

  it('借阅列表行还书成功后，检索结果与图书详情缓存被失效', async () => {
    const client = renderWithClient(<AdminLoanListPage />, '/admin/loans');
    await screen.findByText('活着');
    const user = userEvent.setup();
    const row = screen.getByText('活着').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: /还\s*书/ }));
    const confirm = await screen.findByRole('tooltip');
    await user.click(within(confirm).getByRole('button', { name: /还\s*书/ }));
    expect(await screen.findByText('《围城》已按期归还')).toBeInTheDocument();
    await expectCatalogInvalidated(client);
  });
});