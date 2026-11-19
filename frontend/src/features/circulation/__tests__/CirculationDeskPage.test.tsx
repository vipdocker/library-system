// 流通柜台组件测试：AC-LOAN-04/05（UI）、AC-UI-01/03/04。
// mock features/circulation/api.ts（后端 T04–T07 开发中），错误文案与 errors.ts 逐项比对。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../../api/client';
import type { Loan } from '../../../api/types';
import {
  borrowLoan,
  findReaderByCardNo,
  listLoans,
  renewLoan,
  returnLoanByBarcode,
} from '../api';
import CirculationDeskPage from '../pages/CirculationDeskPage';
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
    borrowLoan: vi.fn(),
    returnLoanByBarcode: vi.fn(),
    renewLoan: vi.fn(),
    listLoans: vi.fn(),
    findReaderByCardNo: vi.fn(),
    searchReaders: vi.fn(),
  };
});

const mockBorrow = vi.mocked(borrowLoan);
const mockReturn = vi.mocked(returnLoanByBarcode);
const mockRenew = vi.mocked(renewLoan);
const mockList = vi.mocked(listLoans);
const mockFindReader = vi.mocked(findReaderByCardNo);

beforeEach(() => {
  mockBorrow.mockResolvedValue(makeLoan());
  mockReturn.mockResolvedValue(makeReturnedLoan());
  mockRenew.mockResolvedValue(makeLoan({ dueDate: '2026-12-31', renewCount: 1, renewable: false }));
  mockList.mockResolvedValue(makePage<Loan>([makeLoan()]));
  mockFindReader.mockResolvedValue(readerFixture);
});

function renderDesk() {
  return renderCirculation(<CirculationDeskPage />, {
    route: '/admin/circulation',
    path: '/admin/circulation',
  });
}

async function fillBorrowAndSubmit(card: string, code: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('读者卡号'), card);
  const barcodeInput = screen.getByLabelText('副本条码');
  await user.type(barcodeInput, `${code}{Enter}`);
  return { user, barcodeInput };
}

describe('AC-UI-04 柜台借书', () => {
  it('输入卡号和条码后回车借书：成功显示应还日、清空条码并保持焦点、显示该读者在借列表', async () => {
    renderDesk();
    const { barcodeInput } = await fillBorrowAndSubmit('R000001', 'C00000011');

    expect(mockBorrow).toHaveBeenCalledWith({
      readerCardNo: 'R000001',
      copyBarcode: 'C00000011',
    });
    expect(await screen.findByText('借出成功')).toBeInTheDocument();
    // 结果摘要与在借列表都显示应还日
    expect((await screen.findAllByText('2026-12-01')).length).toBeGreaterThan(0);
    await waitFor(() => expect(barcodeInput).toHaveValue(''));
    expect(barcodeInput).toHaveFocus();
    // 读者在借列表：按 readerId + CURRENT 查询
    await waitFor(() =>
      expect(mockList).toHaveBeenCalledWith(
        expect.objectContaining({ readerId: 1, status: 'CURRENT' }),
      ),
    );
    const panel = screen.getByRole('region', { name: '读者在借列表' });
    expect(within(panel).getByText('读者一')).toBeInTheDocument();
    expect(within(panel).getByText('活着')).toBeInTheDocument();
  });

  it('卡号和条码先去掉首尾空白再提交（第 8.1 节）', async () => {
    renderDesk();
    await fillBorrowAndSubmit('  R000001  ', '  C00000011  ');
    expect(mockBorrow).toHaveBeenCalledWith({
      readerCardNo: 'R000001',
      copyBarcode: 'C00000011',
    });
  });

  it('未输入卡号时提示校验错误，不发起借书', async () => {
    renderDesk();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /借\s*书/ }));
    expect(await screen.findByText('请输入读者卡号')).toBeInTheDocument();
    expect(mockBorrow).not.toHaveBeenCalled();
  });

  it('只输入卡号回车 = 查看在借列表，不发起借书', async () => {
    renderDesk();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('读者卡号'), 'R000001{Enter}');
    await waitFor(() => expect(mockFindReader).toHaveBeenCalledWith('R000001'));
    expect(mockBorrow).not.toHaveBeenCalled();
    const panel = await screen.findByRole('region', { name: '读者在借列表' });
    expect(within(panel).getByText('活着')).toBeInTheDocument();
  });

  it('查无此卡号时显示 READER_NOT_FOUND 中文文案', async () => {
    mockFindReader.mockRejectedValueOnce(new ApiError(404, 'READER_NOT_FOUND', 'server'));
    renderDesk();
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('读者卡号'), 'R999999{Enter}');
    expect(await screen.findByText('读者不存在或已被删除')).toBeInTheDocument();
  });

  it('提交中借书按钮禁用，防止重复提交（10.2 节）', async () => {
    let resolveBorrow: (loan: Loan) => void = () => {};
    mockBorrow.mockImplementationOnce(
      () => new Promise<Loan>((resolve) => { resolveBorrow = resolve; }),
    );
    renderDesk();
    const { user } = await fillBorrowAndSubmit('R000001', 'C00000011');
    const button = screen.getByRole('button', { name: /借\s*书/ });
    await waitFor(() => expect(button).toBeDisabled());
    // 提交中再次回车不会重复请求
    await user.type(screen.getByLabelText('副本条码'), '{Enter}');
    expect(mockBorrow).toHaveBeenCalledTimes(1);
    resolveBorrow(makeLoan());
    expect(await screen.findByText('借出成功')).toBeInTheDocument();
  });
});

describe('AC-UI-03 柜台借书业务错误显示 errors.ts 中文文案', () => {
  const cases: [string, number, string][] = [
    ['COPY_NOT_AVAILABLE', 409, '副本不可借（已借出或不可用）'],
    ['DUPLICATE_BOOK_LOAN', 409, '该读者已在借同一书目的其他副本'],
    ['READER_HAS_OVERDUE', 409, '读者有逾期未还图书，不能借书或续借'],
    ['READER_DISABLED', 409, '读者已停用，不能借书或续借'],
    ['READER_NOT_FOUND', 404, '读者不存在或已被删除'],
    ['COPY_NOT_FOUND', 404, '副本不存在或已被删除'],
  ];

  it.each(cases)('借书失败 %s → 「%s」', async (code, status, text) => {
    mockBorrow.mockRejectedValueOnce(new ApiError(status, code, 'server message'));
    renderDesk();
    await fillBorrowAndSubmit('R000001', 'C00000011');
    expect(await screen.findByText('操作失败')).toBeInTheDocument();
    expect(screen.getByText(text)).toBeInTheDocument();
  });

  it('LOAN_LIMIT_EXCEEDED 显示上限文案并附 details（D1：上限 5 本）', async () => {
    mockBorrow.mockRejectedValueOnce(
      new ApiError(409, 'LOAN_LIMIT_EXCEEDED', 'server', [], { limit: 5, current: 5 }),
    );
    renderDesk();
    await fillBorrowAndSubmit('R000001', 'C00000011');
    expect(
      await screen.findByText('读者在借数量已达上限（上限 5 本，当前在借 5 本）'),
    ).toBeInTheDocument();
  });

  it('503 DB_BUSY 显示「系统繁忙，请稍后再试」', async () => {
    mockBorrow.mockRejectedValueOnce(new ApiError(503, 'DB_BUSY', 'server'));
    renderDesk();
    await fillBorrowAndSubmit('R000001', 'C00000011');
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
  });
});

describe('AC-LOAN-04 柜台还书', () => {
  async function gotoReturnTab() {
    const user = userEvent.setup();
    await user.click(screen.getByRole('tab', { name: '还书' }));
    return user;
  }

  it('输入条码回车还书：显示归还结果与逾期天数，清空条码并保持焦点', async () => {
    mockReturn.mockResolvedValueOnce(
      makeLoan({
        status: 'RETURNED',
        dueDate: '2026-11-08',
        returnDate: '2026-11-18',
        overdueDays: 10,
        renewable: false,
      }),
    );
    renderDesk();
    const user = await gotoReturnTab();
    const barcodeInput = screen.getByLabelText('副本条码');
    await user.type(barcodeInput, 'C00000011{Enter}');

    expect(mockReturn).toHaveBeenCalledWith({ copyBarcode: 'C00000011' });
    expect(await screen.findByText('归还成功')).toBeInTheDocument();
    expect(screen.getByText('逾期 10 天')).toBeInTheDocument();
    expect(screen.getByText('归还日 2026-11-18')).toBeInTheDocument();
    await waitFor(() => expect(barcodeInput).toHaveValue(''));
    expect(barcodeInput).toHaveFocus();
  });

  it('按期归还显示「按期归还」', async () => {
    renderDesk();
    const user = await gotoReturnTab();
    await user.type(screen.getByLabelText('副本条码'), 'C00000011{Enter}');
    expect(await screen.findByText('按期归还')).toBeInTheDocument();
  });

  it('条码无在借记录 → ACTIVE_LOAN_NOT_FOUND 中文文案', async () => {
    mockReturn.mockRejectedValueOnce(new ApiError(404, 'ACTIVE_LOAN_NOT_FOUND', 'server'));
    renderDesk();
    const user = await gotoReturnTab();
    await user.type(screen.getByLabelText('副本条码'), 'C00000099{Enter}');
    expect(await screen.findByText('该条码没有对应的在借记录')).toBeInTheDocument();
  });

  it('重复还书 → LOAN_ALREADY_RETURNED 中文文案', async () => {
    mockReturn.mockRejectedValueOnce(new ApiError(409, 'LOAN_ALREADY_RETURNED', 'server'));
    renderDesk();
    const user = await gotoReturnTab();
    await user.type(screen.getByLabelText('副本条码'), 'C00000011{Enter}');
    expect(await screen.findByText('该借阅已归还，请勿重复操作')).toBeInTheDocument();
  });

  it('未输入条码时提示校验错误，不发起还书', async () => {
    renderDesk();
    const user = await gotoReturnTab();
    await user.click(screen.getByRole('button', { name: /还\s*书/ }));
    expect(await screen.findByText('请输入副本条码')).toBeInTheDocument();
    expect(mockReturn).not.toHaveBeenCalled();
  });
});

describe('AC-LOAN-05 柜台在借列表续借', () => {
  async function lookupReader(bookTitle = '活着') {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('读者卡号'), 'R000001{Enter}');
    const panel = await screen.findByRole('region', { name: '读者在借列表' });
    await within(panel).findByText(bookTitle);
    return { user, panel };
  }

  it('续借成功：调用 renew 接口并显示顺延后的应还日', async () => {
    renderDesk();
    const { user, panel } = await lookupReader();
    await user.click(within(panel).getByRole('button', { name: /续\s*借/ }));
    expect(mockRenew).toHaveBeenCalledWith(101);
    expect(await screen.findByText('续借成功')).toBeInTheDocument();
    expect(
      await screen.findByText('续借成功：《活着》应还日顺延至 2026-12-31'),
    ).toBeInTheDocument();
  });

  it('renewable=false 的行续借按钮禁用', async () => {
    mockList.mockResolvedValue(makePage<Loan>([makeOverdueLoan()]));
    renderDesk();
    const { panel } = await lookupReader('百年孤独');
    expect(within(panel).getByRole('button', { name: /续\s*借/ })).toBeDisabled();
    expect(mockRenew).not.toHaveBeenCalled();
  });

  it.each([
    ['RENEW_LIMIT_REACHED', '续借次数已达上限'],
    ['LOAN_OVERDUE', '该借阅已逾期，不能续借'],
    ['READER_HAS_OVERDUE', '读者有逾期未还图书，不能借书或续借'],
    ['READER_DISABLED', '读者已停用，不能借书或续借'],
    ['LOAN_ALREADY_RETURNED', '该借阅已归还，请勿重复操作'],
  ] as [string, string][])(
    'AC-UI-03 续借失败 %s → 「%s」显示在结果区',
    async (code, text) => {
      mockRenew.mockRejectedValueOnce(new ApiError(409, code, 'server'));
      renderDesk();
      const { user, panel } = await lookupReader();
      await user.click(within(panel).getByRole('button', { name: /续\s*借/ }));
      expect(await screen.findByText('操作失败')).toBeInTheDocument();
      expect(screen.getByText(text)).toBeInTheDocument();
    },
  );
});

describe('AC-UI-01 柜台在借列表四种状态', () => {
  async function lookupReader() {
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('读者卡号'), 'R000001{Enter}');
    return user;
  }

  it('加载状态显示加载提示', async () => {
    mockList.mockImplementationOnce(() => new Promise(() => {}));
    renderDesk();
    await lookupReader();
    expect(await screen.findByText('正在加载在借列表…')).toBeInTheDocument();
  });

  it('空态显示「该读者当前没有在借图书」', async () => {
    mockList.mockResolvedValue(makePage<Loan>([]));
    renderDesk();
    await lookupReader();
    expect(await screen.findByText('该读者当前没有在借图书')).toBeInTheDocument();
  });

  it('错误状态显示中文文案与「重试」，点击重试后恢复数据', async () => {
    mockList
      .mockRejectedValueOnce(new ApiError(503, 'DB_BUSY', 'server'))
      .mockResolvedValue(makePage<Loan>([makeLoan()]));
    renderDesk();
    const user = await lookupReader();
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('活着')).toBeInTheDocument();
  });

  it('有数据状态显示书目与状态 Tag', async () => {
    mockList.mockResolvedValue(makePage<Loan>([makeLoan(), makeOverdueLoan()]));
    renderDesk();
    await lookupReader();
    const panel = await screen.findByRole('region', { name: '读者在借列表' });
    expect(within(panel).getByText('活着')).toBeInTheDocument();
    expect(within(panel).getByText('百年孤独')).toBeInTheDocument();
    expect(within(panel).getByText('逾期 10 天')).toBeInTheDocument();
    expect(within(panel).getByText('在借 2 册')).toBeInTheDocument();
    expect(within(panel).getByText('其中逾期 1 册')).toBeInTheDocument();
  });
});