// 管理员借阅记录组件测试：AC-LOAN-04/05（UI）、AC-LOAN-08、AC-UI-01。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../../api/client';
import type { Loan, Reader } from '../../../api/types';
import { listLoans, renewLoan, returnLoan, searchReaders } from '../api';
import AdminLoanListPage from '../pages/AdminLoanListPage';
import {
  makeLoan,
  makeOverdueLoan,
  makePage,
  makeReturnedLoan,
  readerFixture,
  renderCirculation,
} from './testUtils';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    listLoans: vi.fn(),
    returnLoan: vi.fn(),
    renewLoan: vi.fn(),
    searchReaders: vi.fn(),
    borrowLoan: vi.fn(),
    returnLoanByBarcode: vi.fn(),
    findReaderByCardNo: vi.fn(),
    listMyLoans: vi.fn(),
  };
});

const mockList = vi.mocked(listLoans);
const mockReturn = vi.mocked(returnLoan);
const mockRenew = vi.mocked(renewLoan);
const mockSearchReaders = vi.mocked(searchReaders);

beforeEach(() => {
  mockList.mockResolvedValue(makePage<Loan>([makeLoan(), makeOverdueLoan(), makeReturnedLoan()]));
  mockReturn.mockResolvedValue(makeLoan({ status: 'RETURNED', returnDate: '2026-11-18' }));
  mockRenew.mockResolvedValue(makeLoan({ dueDate: '2026-12-31', renewCount: 1 }));
  mockSearchReaders.mockResolvedValue(makePage<Reader>([readerFixture]));
});

function renderPage() {
  return renderCirculation(<AdminLoanListPage />, {
    route: '/admin/loans',
    path: '/admin/loans',
  });
}

describe('AC-LOAN-08 过滤、分页与排序', () => {
  it('默认按 borrowDate,desc 查询并渲染读者、书名、条码、状态', async () => {
    renderPage();
    expect(await screen.findByText('活着')).toBeInTheDocument();
    expect(mockList).toHaveBeenCalledWith(
      expect.objectContaining({ page: 0, size: 20, sort: 'borrowDate,desc' }),
    );
    expect(screen.getAllByText('读者一').length).toBeGreaterThan(0);
    expect(screen.getAllByText('C00000011').length).toBeGreaterThan(0);
    expect(screen.getByText('逾期 10 天')).toBeInTheDocument();
    expect(screen.getByText('已归还')).toBeInTheDocument();
  });

  it('按状态过滤：选择「逾期」后点查询，携带 status=OVERDUE 且回到第 0 页', async () => {
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('状态'));
    await user.click(await screen.findByTitle('逾期'));
    await user.click(screen.getByRole('button', { name: /查\s*询/ }));
    await waitFor(() =>
      expect(mockList).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'OVERDUE', page: 0 }),
      ),
    );
  });

  it('按副本条码过滤：输入后回车生效', async () => {
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('副本条码'), 'C00000012{Enter}');
    await waitFor(() =>
      expect(mockList).toHaveBeenCalledWith(
        expect.objectContaining({ copyBarcode: 'C00000012', page: 0 }),
      ),
    );
  });

  it('按书名过滤：输入 q 后点查询', async () => {
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('书名'), '百年孤独');
    await user.click(screen.getByRole('button', { name: /查\s*询/ }));
    await waitFor(() =>
      expect(mockList).toHaveBeenCalledWith(expect.objectContaining({ q: '百年孤独' })),
    );
  });

  it('按读者过滤：远程搜索读者并选中后携带 readerId', async () => {
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    await user.click(screen.getByLabelText('读者'));
    await user.type(screen.getByLabelText('读者'), '读者一');
    await waitFor(() => expect(mockSearchReaders).toHaveBeenCalled());
    await user.click(await screen.findByText('读者一（R000001）'));
    await user.click(screen.getByRole('button', { name: /查\s*询/ }));
    await waitFor(() =>
      expect(mockList).toHaveBeenCalledWith(expect.objectContaining({ readerId: 1, page: 0 })),
    );
  });

  it('重置按钮清空过滤条件', async () => {
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('书名'), '活着{Enter}');
    await waitFor(() =>
      expect(mockList).toHaveBeenCalledWith(expect.objectContaining({ q: '活着' })),
    );
    await user.click(screen.getByRole('button', { name: /重\s*置/ }));
    await waitFor(() =>
      expect(mockList).toHaveBeenCalledWith(expect.objectContaining({ q: '', page: 0 })),
    );
  });

  it('分页：点击第 2 页携带 page=1', async () => {
    mockList.mockResolvedValue(
      makePage<Loan>([makeLoan()], { page: 0, size: 20, totalItems: 45, totalPages: 3 }),
    );
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    await user.click(within(screen.getByTitle('2')).getByRole('button'));
    await waitFor(() =>
      expect(mockList).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })),
    );
  });

  it('排序：点击「应还日」列头携带 sort=dueDate,asc', async () => {
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    await user.click(screen.getByRole('columnheader', { name: /应还日/ }));
    await waitFor(() =>
      expect(mockList).toHaveBeenCalledWith(
        expect.objectContaining({ sort: 'dueDate,asc', page: 0 }),
      ),
    );
  });
});

describe('AC-LOAN-04 列表行还书', () => {
  it('在借行还书需 Popconfirm 确认，确认后调用 return 接口并提示成功', async () => {
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    const row = screen.getByText('活着').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: /还\s*书/ }));
    const confirm = await screen.findByRole('tooltip');
    expect(within(confirm).getByText('确认归还这本书？')).toBeInTheDocument();
    await user.click(within(confirm).getByRole('button', { name: /还\s*书/ }));
    expect(mockReturn).toHaveBeenCalledWith(101);
    expect(await screen.findByText('《活着》已按期归还')).toBeInTheDocument();
  });

  it('已归还行不显示还书 / 续借操作', async () => {
    mockList.mockResolvedValue(makePage<Loan>([makeReturnedLoan()]));
    renderPage();
    await screen.findByText('已归还');
    expect(screen.queryByRole('button', { name: /还\s*书/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /续\s*借/ })).not.toBeInTheDocument();
  });

  it('AC-UI-03 还书失败显示 LOAN_ALREADY_RETURNED 中文文案', async () => {
    mockReturn.mockRejectedValueOnce(new ApiError(409, 'LOAN_ALREADY_RETURNED', 'server'));
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    const row = screen.getByText('活着').closest('tr') as HTMLElement;
    await user.click(within(row).getByRole('button', { name: /还\s*书/ }));
    const confirm = await screen.findByRole('tooltip');
    await user.click(within(confirm).getByRole('button', { name: /还\s*书/ }));
    expect(await screen.findByText('该借阅已归还，请勿重复操作')).toBeInTheDocument();
  });
});

describe('AC-LOAN-05 列表行续借', () => {
  it('renewable=true 的行可续借，成功提示顺延后的应还日', async () => {
    mockList.mockResolvedValue(makePage<Loan>([makeLoan()]));
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /续\s*借/ }));
    expect(mockRenew).toHaveBeenCalledWith(101);
    expect(
      await screen.findByText('续借成功：《活着》应还日顺延至 2026-12-31'),
    ).toBeInTheDocument();
  });

  it('renewable=false 的行续借按钮禁用', async () => {
    mockList.mockResolvedValue(makePage<Loan>([makeOverdueLoan()]));
    renderPage();
    await screen.findByText('百年孤独');
    expect(screen.getByRole('button', { name: /续\s*借/ })).toBeDisabled();
  });

  it('AC-UI-03 续借失败显示 RENEW_LIMIT_REACHED 中文文案', async () => {
    mockList.mockResolvedValue(makePage<Loan>([makeLoan()]));
    mockRenew.mockRejectedValueOnce(new ApiError(409, 'RENEW_LIMIT_REACHED', 'server'));
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /续\s*借/ }));
    expect(await screen.findByText('续借次数已达上限')).toBeInTheDocument();
  });
});

describe('AC-UI-01 借阅记录页四种状态', () => {
  it('首次加载显示加载提示', async () => {
    mockList.mockImplementationOnce(() => new Promise(() => {}));
    renderPage();
    expect(await screen.findByText('正在加载借阅记录…')).toBeInTheDocument();
  });

  it('空态提供「前往流通柜台借书」入口并可跳转', async () => {
    mockList.mockResolvedValue(makePage<Loan>([]));
    renderPage();
    expect(await screen.findByText('没有符合条件的借阅记录')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '前往流通柜台借书' }));
    expect(screen.getByTestId('location-probe')).toHaveTextContent('/admin/circulation');
  });

  it('错误状态显示中文文案与「重试」，点击重试后恢复数据；503 提示系统繁忙', async () => {
    mockList
      .mockRejectedValueOnce(new ApiError(503, 'DB_BUSY', 'server'))
      .mockResolvedValue(makePage<Loan>([makeLoan()]));
    renderPage();
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('活着')).toBeInTheDocument();
  });

  it('403 显示「无权限执行该操作」（不清除登录态，由全局守卫负责）', async () => {
    mockList.mockRejectedValueOnce(new ApiError(403, 'FORBIDDEN', 'server'));
    renderPage();
    expect(await screen.findByText('无权限执行该操作')).toBeInTheDocument();
  });
});