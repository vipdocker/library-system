// AC-RDR-02/05（UI）、AC-UI-01/02：读者详情页状态、资料编辑、重置口令、借阅列表（GET /api/loans?readerId）。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../../api/client';
import { getReader, listReaderLoans, resetReaderPassword, updateReader } from '../api';
import ReaderDetailPage from '../pages/ReaderDetailPage';
import { loanActive, loanOverdue, loanReturned, pageOf, reader1, renderPage } from './helpers';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    getReader: vi.fn(),
    updateReader: vi.fn(),
    resetReaderPassword: vi.fn(),
    listReaderLoans: vi.fn(),
  };
});

const mockGet = vi.mocked(getReader);
const mockUpdate = vi.mocked(updateReader);
const mockReset = vi.mocked(resetReaderPassword);
const mockLoans = vi.mocked(listReaderLoans);

beforeEach(() => {
  mockGet.mockResolvedValue(reader1);
  mockUpdate.mockResolvedValue(reader1);
  mockReset.mockResolvedValue(undefined);
  mockLoans.mockResolvedValue(pageOf([loanActive, loanOverdue, loanReturned]));
});

function renderDetail() {
  return renderPage(<ReaderDetailPage />, {
    route: '/admin/readers/1',
    path: '/admin/readers/:id',
  });
}

describe('AC-UI-01 ReaderDetailPage 页面状态', () => {
  it('首次加载显示加载态', () => {
    mockGet.mockReturnValue(new Promise(() => {}));
    renderDetail();
    expect(screen.getByText('加载中…')).toBeInTheDocument();
  });

  it('错误态显示中文文案并可重试', async () => {
    mockGet.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'boom'));
    const user = userEvent.setup();
    renderDetail();
    expect(await screen.findByText('系统内部错误，请稍后再试')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(mockGet).toHaveBeenCalledTimes(2));
  });

  it('404 READER_NOT_FOUND 显示"读者不存在或已被删除"并可返回列表', async () => {
    mockGet.mockRejectedValue(new ApiError(404, 'READER_NOT_FOUND', 'missing'));
    renderDetail();
    expect(await screen.findByText('读者不存在或已被删除')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /返回读者列表/ })).toBeInTheDocument();
  });

  it('有数据时展示卡号、用户名、在借与逾期数', async () => {
    renderDetail();
    expect(await screen.findByText(/卡号 R000001/)).toBeInTheDocument();
    expect(screen.getByText(/用户名 reader1/)).toBeInTheDocument();
    expect(screen.getByText(/在借 2 本/)).toBeInTheDocument();
    expect(screen.getByText(/逾期 1 本/)).toBeInTheDocument();
  });
});

describe('AC-RDR-02 资料编辑与停用', () => {
  it('保存资料调用 PUT，status 原样带回', async () => {
    const user = userEvent.setup();
    renderDetail();
    await screen.findByText(/卡号 R000001/);
    const nameInput = screen.getByLabelText('姓名');
    await user.clear(nameInput);
    await user.type(nameInput, '张三丰');
    await user.click(screen.getByRole('button', { name: /^保\s*存$/ }));
    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith(1, {
        name: '张三丰',
        phone: '13800000000',
        email: 'zhang@example.com',
        status: 'ACTIVE',
      }),
    );
    expect(await screen.findByText('资料已保存')).toBeInTheDocument();
  });

  it('AC-UI-02 服务端 fieldErrors 映射到对应表单项', async () => {
    mockUpdate.mockRejectedValue(
      new ApiError(400, 'VALIDATION_FAILED', 'validation', [
        { field: 'phone', message: '电话格式不正确' },
      ]),
    );
    const user = userEvent.setup();
    renderDetail();
    await screen.findByText(/卡号 R000001/);
    await user.click(screen.getByRole('button', { name: /^保\s*存$/ }));
    expect(await screen.findByText('电话格式不正确')).toBeInTheDocument();
  });

  it('AC-UI-02 姓名清空时前端校验拦截，不提交', async () => {
    const user = userEvent.setup();
    renderDetail();
    await screen.findByText(/卡号 R000001/);
    await user.clear(screen.getByLabelText('姓名'));
    await user.click(screen.getByRole('button', { name: /^保\s*存$/ }));
    expect(await screen.findByText('请输入姓名')).toBeInTheDocument();
    expect(mockUpdate).not.toHaveBeenCalled();
  });

  it('头部停用需二次确认，确认后 PUT status=DISABLED', async () => {
    mockUpdate.mockResolvedValue({ ...reader1, status: 'DISABLED' });
    const user = userEvent.setup();
    renderDetail();
    await screen.findByText(/卡号 R000001/);
    await user.click(screen.getByRole('button', { name: /^停\s*用$/ }));
    await user.click(await screen.findByRole('button', { name: /^确\s*认$/ }));
    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith(1, expect.objectContaining({ status: 'DISABLED' })),
    );
    expect(await screen.findByText('读者已停用，账号同步禁用')).toBeInTheDocument();
  });
});

describe('AC-RDR-05 重置口令', () => {
  it('合法新口令提交 PUT /password，成功提示', async () => {
    const user = userEvent.setup();
    renderDetail();
    await screen.findByText(/卡号 R000001/);
    await user.click(screen.getByRole('button', { name: /重置口令/ }));
    await user.type(screen.getByLabelText('新口令'), 'NewPass123');
    await user.type(screen.getByLabelText('确认新口令'), 'NewPass123');
    await user.click(screen.getByRole('button', { name: /^重\s*置$/ }));
    await waitFor(() => expect(mockReset).toHaveBeenCalledWith(1, { newPassword: 'NewPass123' }));
    expect(
      await screen.findByText('口令已重置，请通知读者使用新口令登录'),
    ).toBeInTheDocument();
  });

  it('AC-UI-02 两次输入不一致时拦截', async () => {
    const user = userEvent.setup();
    renderDetail();
    await screen.findByText(/卡号 R000001/);
    await user.click(screen.getByRole('button', { name: /重置口令/ }));
    await user.type(screen.getByLabelText('新口令'), 'NewPass123');
    await user.type(screen.getByLabelText('确认新口令'), 'OtherPass123');
    await user.click(screen.getByRole('button', { name: /^重\s*置$/ }));
    expect(await screen.findByText('两次输入的口令不一致')).toBeInTheDocument();
    expect(mockReset).not.toHaveBeenCalled();
  });

  it('AC-UI-02 口令不满足策略（缺数字）时拦截', async () => {
    const user = userEvent.setup();
    renderDetail();
    await screen.findByText(/卡号 R000001/);
    await user.click(screen.getByRole('button', { name: /重置口令/ }));
    await user.type(screen.getByLabelText('新口令'), 'abcdefgh');
    await user.type(screen.getByLabelText('确认新口令'), 'abcdefgh');
    await user.click(screen.getByRole('button', { name: /^重\s*置$/ }));
    expect(await screen.findByText('口令必须同时包含字母和数字')).toBeInTheDocument();
    expect(mockReset).not.toHaveBeenCalled();
  });
});

describe('读者借阅列表（GET /api/loans?readerId）', () => {
  it('按 readerId 请求并渲染书名、条码与逾期标红', async () => {
    renderDetail();
    expect(await screen.findByText('三体')).toBeInTheDocument();
    expect(mockLoans).toHaveBeenCalledWith(1, expect.objectContaining({ page: 0, size: 20 }));
    expect(screen.getByText('C00000101')).toBeInTheDocument();
    expect(screen.getByText('逾期 48 天')).toBeInTheDocument();
    expect(screen.getByText('已还')).toBeInTheDocument();
    expect(screen.getAllByText('0/1').length).toBeGreaterThan(0);
  });

  it('状态过滤提交 status=CURRENT', async () => {
    const user = userEvent.setup();
    renderDetail();
    await screen.findByText('三体');
    const select = within(screen.getByTestId('loan-status-filter')).getByRole('combobox');
    await user.click(select);
    await user.click(await screen.findByTitle('当前在借'));
    await waitFor(() =>
      expect(mockLoans).toHaveBeenLastCalledWith(
        1,
        expect.objectContaining({ status: 'CURRENT', page: 0 }),
      ),
    );
  });

  it('AC-UI-01 空态提示"该读者暂无借阅记录"', async () => {
    mockLoans.mockResolvedValue(pageOf([]));
    renderDetail();
    expect(await screen.findByText('该读者暂无借阅记录')).toBeInTheDocument();
  });

  it('AC-UI-01 借阅列表错误态显示"系统繁忙"并可重试', async () => {
    mockLoans.mockRejectedValue(new ApiError(503, 'DB_BUSY', 'busy'));
    const user = userEvent.setup();
    renderDetail();
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(mockLoans).toHaveBeenCalledTimes(2));
  });
});