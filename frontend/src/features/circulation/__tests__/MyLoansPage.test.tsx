// 我的借阅组件测试：AC-LOAN-05/07（UI）、AC-UI-01/05。
// D2 开关（renewal.ts，默认 false）用可控 mock 覆盖 true / false 两种取值。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../../api/client';
import type { Loan } from '../../../api/types';
import { listMyLoans, renewLoan } from '../api';
import MyLoansPage from '../pages/MyLoansPage';
import {
  makeLoan,
  makeOverdueLoan,
  makePage,
  makeReturnedLoan,
  renderCirculation,
} from './testUtils';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    listMyLoans: vi.fn(),
    renewLoan: vi.fn(),
    borrowLoan: vi.fn(),
    returnLoan: vi.fn(),
    returnLoanByBarcode: vi.fn(),
    listLoans: vi.fn(),
    searchReaders: vi.fn(),
    findReaderByCardNo: vi.fn(),
  };
});

// D2：默认 false；getter 使每次渲染读取最新值，便于在同一文件内覆盖两种开关状态。
const renewalState = vi.hoisted(() => ({ enabled: false }));
vi.mock('../renewal', () => ({
  get READER_SELF_RENEW_ENABLED() {
    return renewalState.enabled;
  },
}));

const mockList = vi.mocked(listMyLoans);
const mockRenew = vi.mocked(renewLoan);

beforeEach(() => {
  renewalState.enabled = false;
  mockList.mockResolvedValue(makePage<Loan>([makeLoan(), makeOverdueLoan()]));
  mockRenew.mockResolvedValue(makeLoan({ dueDate: '2026-12-31', renewCount: 1, renewable: false }));
});

function renderPage() {
  return renderCirculation(<MyLoansPage />, { route: '/my/loans', path: '/my/loans' });
}

describe('AC-LOAN-07 当前借阅与历史', () => {
  it('当前 Tab 用 status=CURRENT 查询本人借阅，显示书名、条码、借阅日、应还日、状态', async () => {
    renderPage();
    expect(await screen.findByText('活着')).toBeInTheDocument();
    expect(mockList).toHaveBeenCalledWith({ status: 'CURRENT', page: 0, size: 20 });
    expect(screen.getByText('C00000011')).toBeInTheDocument();
    expect(screen.getAllByText('2026-11-01').length).toBeGreaterThan(0);
    expect(screen.getByText('2026-12-01')).toBeInTheDocument();
    expect(screen.getByText('在借')).toBeInTheDocument();
  });

  it('逾期记录标红：红色 Tag 显示逾期天数，行与应还日带逾期样式', async () => {
    renderPage();
    await screen.findByText('百年孤独');
    const tag = screen.getByText('逾期 10 天');
    expect(tag.closest('.ant-tag')).toHaveClass('ant-tag-error');
    const row = screen.getByText('百年孤独').closest('tr') as HTMLElement;
    expect(row).toHaveClass('circ-row-overdue');
    expect(overdueDueCell(row)).toHaveClass('circ-due-overdue');
    // 逾期汇总提示
    expect(screen.getByText('您有 1 册图书已逾期')).toBeInTheDocument();
  });

  it('切换到历史 Tab 用 status=RETURNED 查询并显示归还日', async () => {
    mockList.mockImplementation(async (query) =>
      query.status === 'RETURNED'
        ? makePage<Loan>([makeReturnedLoan()])
        : makePage<Loan>([makeLoan()]),
    );
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    await user.click(screen.getByRole('tab', { name: '历史' }));
    await waitFor(() =>
      expect(mockList).toHaveBeenCalledWith({ status: 'RETURNED', page: 0, size: 20 }),
    );
    expect(await screen.findByText('2026-11-10')).toBeInTheDocument();
    expect(screen.getByText('已归还')).toBeInTheDocument();
    expect(screen.queryByText('逾期 10 天')).not.toBeInTheDocument();
  });

  it('翻页携带 page 参数', async () => {
    mockList.mockResolvedValue(
      makePage<Loan>([makeLoan()], { page: 0, size: 20, totalItems: 45, totalPages: 3 }),
    );
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    await user.click(within(screen.getByTitle('2')).getByRole('button'));
    await waitFor(() =>
      expect(mockList).toHaveBeenCalledWith({ status: 'CURRENT', page: 1, size: 20 }),
    );
  });
});

describe('AC-UI-05 / D2 读者自助续借开关', () => {
  it('D2=false（默认）时即使 renewable=true 也不显示续借按钮', async () => {
    renderPage();
    await screen.findByText('活着');
    expect(screen.queryByRole('button', { name: /续\s*借/ })).not.toBeInTheDocument();
  });

  it('D2=true 且 renewable=true 时显示续借按钮，点击调用 renew 并提示新应还日（AC-LOAN-05）', async () => {
    renewalState.enabled = true;
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

  it('D2=true 但 renewable=false 时不显示续借按钮', async () => {
    renewalState.enabled = true;
    mockList.mockResolvedValue(makePage<Loan>([makeOverdueLoan()]));
    renderPage();
    await screen.findByText('百年孤独');
    expect(screen.queryByRole('button', { name: /续\s*借/ })).not.toBeInTheDocument();
  });

  it('D2=true 时续借失败显示 RENEW_LIMIT_REACHED 中文文案（AC-UI-03）', async () => {
    renewalState.enabled = true;
    mockList.mockResolvedValue(makePage<Loan>([makeLoan()]));
    mockRenew.mockRejectedValueOnce(new ApiError(409, 'RENEW_LIMIT_REACHED', 'server'));
    renderPage();
    await screen.findByText('活着');
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /续\s*借/ }));
    expect(await screen.findByText('续借次数已达上限')).toBeInTheDocument();
  });

  it('历史 Tab 即使 D2=true 也不显示续借按钮', async () => {
    renewalState.enabled = true;
    mockList.mockResolvedValue(makePage<Loan>([makeReturnedLoan({ renewable: true })]));
    renderPage();
    await screen.findByText('围城');
    const user = userEvent.setup();
    await user.click(screen.getByRole('tab', { name: '历史' }));
    await screen.findByText('2026-11-10');
    expect(screen.queryByRole('button', { name: /续\s*借/ })).not.toBeInTheDocument();
  });
});

describe('AC-UI-01 我的借阅四种状态', () => {
  it('首次加载显示加载提示', async () => {
    mockList.mockImplementationOnce(() => new Promise(() => {}));
    renderPage();
    expect(await screen.findByText('正在加载借阅记录…')).toBeInTheDocument();
  });

  it('当前借阅空态提供「去借几本书」入口并可跳转 /books', async () => {
    mockList.mockResolvedValue(makePage<Loan>([]));
    renderPage();
    expect(await screen.findByText('您当前没有在借图书')).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: '去借几本书' }));
    expect(screen.getByTestId('location-probe')).toHaveTextContent('/books');
  });

  it('历史空态显示「暂无历史借阅记录」', async () => {
    mockList.mockResolvedValue(makePage<Loan>([]));
    renderPage();
    const user = userEvent.setup();
    await user.click(screen.getByRole('tab', { name: '历史' }));
    expect(await screen.findByText('暂无历史借阅记录')).toBeInTheDocument();
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
});

// 逾期行内的应还日单元格
function overdueDueCell(row: HTMLElement): HTMLElement {
  const cell = row.querySelector('.circ-due-overdue');
  if (!cell) throw new Error('未找到逾期应还日单元格');
  return cell as HTMLElement;
}