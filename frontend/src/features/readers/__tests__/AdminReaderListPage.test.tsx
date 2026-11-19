// AC-RDR-01/02/03/04（UI）、AC-UI-01/02：读者列表页四种状态、检索分页、新建表单、停用/删除确认与 409 文案。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../../api/client';
import { createReader, deleteReader, listReaders, updateReader } from '../api';
import AdminReaderListPage from '../pages/AdminReaderListPage';
import { pageOf, reader1, reader2, reader3, renderPage } from './helpers';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    listReaders: vi.fn(),
    createReader: vi.fn(),
    updateReader: vi.fn(),
    deleteReader: vi.fn(),
  };
});

const mockList = vi.mocked(listReaders);
const mockCreate = vi.mocked(createReader);
const mockUpdate = vi.mocked(updateReader);
const mockDelete = vi.mocked(deleteReader);

beforeEach(() => {
  mockList.mockResolvedValue(pageOf([reader1, reader2]));
  mockCreate.mockResolvedValue(reader3);
  mockUpdate.mockResolvedValue({ ...reader1, status: 'DISABLED' });
  mockDelete.mockResolvedValue(undefined);
});

function renderList() {
  return renderPage(<AdminReaderListPage />);
}

async function openCreateModal(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getAllByRole('button', { name: /新建读者/ })[0]);
  // 表单查询限定在弹窗内，避免命中表头排序列的 aria-label
  return within(await screen.findByRole('dialog'));
}

async function fillCreateForm(
  user: ReturnType<typeof userEvent.setup>,
  dialog: ReturnType<typeof within>,
  overrides: Partial<Record<'name' | 'phone' | 'email' | 'username' | 'password', string>> = {},
) {
  const values = {
    name: '王五',
    phone: '13900000000',
    email: 'wang@example.com',
    username: 'reader3',
    password: 'Reader@123',
    ...overrides,
  };
  await user.type(dialog.getByLabelText('姓名'), values.name);
  if (values.phone) await user.type(dialog.getByLabelText('电话'), values.phone);
  if (values.email) await user.type(dialog.getByLabelText('邮箱'), values.email);
  await user.type(dialog.getByLabelText('用户名'), values.username);
  await user.type(dialog.getByLabelText('初始口令'), values.password);
}

describe('AC-UI-01 AdminReaderListPage 页面状态', () => {
  it('首次加载显示加载态', () => {
    mockList.mockReturnValue(new Promise(() => {}));
    renderList();
    expect(screen.getByText('加载中…')).toBeInTheDocument();
  });

  it('错误态显示中文文案，点击重试重新请求', async () => {
    mockList.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'boom'));
    const user = userEvent.setup();
    renderList();
    expect(await screen.findByText('系统内部错误，请稍后再试')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(mockList).toHaveBeenCalledTimes(2));
  });

  it('503 DB_BUSY 显示"系统繁忙"', async () => {
    mockList.mockRejectedValue(new ApiError(503, 'DB_BUSY', 'busy'));
    renderList();
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
  });

  it('无筛选且列表为空时提供"新建读者"引导', async () => {
    mockList.mockResolvedValue(pageOf([]));
    renderList();
    expect(await screen.findByText('暂无读者')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /新建读者/ }).length).toBeGreaterThanOrEqual(2);
  });

  it('有数据时渲染卡号、姓名、状态与在借/逾期数', async () => {
    renderList();
    expect(await screen.findByText('R000001')).toBeInTheDocument();
    expect(screen.getByText('张三')).toBeInTheDocument();
    expect(screen.getByText('R000002')).toBeInTheDocument();
    expect(screen.getByText('启用')).toBeInTheDocument();
    expect(screen.getByText('停用')).toBeInTheDocument();
    expect(screen.getByText('reader1')).toBeInTheDocument();
  });

  it('AC-RDR-04 翻页时保留旧数据并显示表格 loading', async () => {
    mockList.mockResolvedValue(pageOf([reader1], 0, 20, 25));
    const user = userEvent.setup();
    renderList();
    expect(await screen.findByText('张三')).toBeInTheDocument();

    mockList.mockReturnValue(new Promise(() => {}));
    await user.click(within(screen.getByTitle('2')).getByRole('button'));
    await waitFor(() =>
      expect(mockList).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 })),
    );
    expect(screen.getByText('张三')).toBeInTheDocument();
  });
});

describe('AC-RDR-04 检索与分页', () => {
  it('关键词检索提交 q 并重置页码', async () => {
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    await user.type(screen.getByLabelText('读者检索'), '王');
    await user.click(screen.getByRole('button', { name: /查\s*询/ }));
    await waitFor(() =>
      expect(mockList).toHaveBeenLastCalledWith(expect.objectContaining({ q: '王', page: 0 })),
    );
  });

  it('状态过滤提交 status=DISABLED', async () => {
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    const select = within(screen.getByTestId('reader-status-filter')).getByRole('combobox');
    await user.click(select);
    await user.click(await screen.findByTitle('停用'));
    await user.click(screen.getByRole('button', { name: /查\s*询/ }));
    await waitFor(() =>
      expect(mockList).toHaveBeenLastCalledWith(
        expect.objectContaining({ status: 'DISABLED', page: 0 }),
      ),
    );
  });

  it('检索无结果时提示"没有找到匹配的读者"并可清除筛选', async () => {
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    mockList.mockResolvedValue(pageOf([]));
    await user.type(screen.getByLabelText('读者检索'), '不存在的人');
    await user.click(screen.getByRole('button', { name: /查\s*询/ }));
    expect(await screen.findByText('没有找到匹配的读者')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /清除筛选/ }));
    await waitFor(() =>
      expect(mockList).toHaveBeenLastCalledWith(expect.objectContaining({ q: '', page: 0 })),
    );
  });
});

describe('AC-RDR-01 / AC-UI-02 新建读者', () => {
  it('合法输入提交创建请求，成功后提示卡号并关闭弹窗', async () => {
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    const dialog = await openCreateModal(user);
    await fillCreateForm(user, dialog);
    await user.click(dialog.getByRole('button', { name: /^创\s*建$/ }));

    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith({
        name: '王五',
        phone: '13900000000',
        email: 'wang@example.com',
        username: 'reader3',
        initialPassword: 'Reader@123',
      }),
    );
    expect(await screen.findByText('读者已创建，卡号 R000003')).toBeInTheDocument();
  });

  it('AC-UI-02 入参去首尾空白；空白可选字段按 null 提交（第 8.1 节）', async () => {
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    const dialog = await openCreateModal(user);
    await fillCreateForm(user, dialog, { name: '  王五  ', phone: '   ', email: '' });
    await user.click(dialog.getByRole('button', { name: /^创\s*建$/ }));
    await waitFor(() =>
      expect(mockCreate).toHaveBeenCalledWith(
        expect.objectContaining({ name: '王五', phone: null, email: null }),
      ),
    );
  });

  it('AC-UI-02 前端校验：口令缺数字、用户名过短、电话/邮箱格式错误时不提交', async () => {
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    const dialog = await openCreateModal(user);
    await fillCreateForm(user, dialog, {
      username: 'ab',
      password: 'abcdefgh',
      phone: 'abc',
      email: 'not-an-email',
    });
    await user.click(dialog.getByRole('button', { name: /^创\s*建$/ }));

    expect(await screen.findByText('口令必须同时包含字母和数字')).toBeInTheDocument();
    expect(screen.getByText('用户名为 3–32 位字母、数字、_、. 或 -')).toBeInTheDocument();
    expect(screen.getByText('电话只能包含数字、+、- 和空格')).toBeInTheDocument();
    expect(screen.getByText('邮箱格式不正确')).toBeInTheDocument();
    expect(mockCreate).not.toHaveBeenCalled();
  });

  it('AC-UI-02 服务端 fieldErrors 映射到对应表单项', async () => {
    mockCreate.mockRejectedValue(
      new ApiError(400, 'VALIDATION_FAILED', 'validation', [
        { field: 'name', message: '姓名长度必须在 1–50 之间' },
      ]),
    );
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    const dialog = await openCreateModal(user);
    await fillCreateForm(user, dialog);
    await user.click(dialog.getByRole('button', { name: /^创\s*建$/ }));
    expect(await screen.findByText('姓名长度必须在 1–50 之间')).toBeInTheDocument();
  });

  it('AC-RDR-01 用户名重复（409 USERNAME_DUPLICATE）显示"用户名已存在"', async () => {
    mockCreate.mockRejectedValue(new ApiError(409, 'USERNAME_DUPLICATE', 'duplicate'));
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    const dialog = await openCreateModal(user);
    await fillCreateForm(user, dialog);
    await user.click(dialog.getByRole('button', { name: /^创\s*建$/ }));
    expect(await screen.findByText('用户名已存在')).toBeInTheDocument();
  });

  it('AC-UI-02 提交中按钮禁用，重复点击不会重复提交', async () => {
    mockCreate.mockReturnValue(new Promise(() => {}));
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    const dialog = await openCreateModal(user);
    await fillCreateForm(user, dialog);
    const okButton = dialog.getByRole('button', { name: /^创\s*建$/ });
    await user.click(okButton);
    await waitFor(() => expect(okButton).toBeDisabled());
    await user.click(okButton);
    expect(mockCreate).toHaveBeenCalledTimes(1);
  });
});

describe('AC-RDR-02 停用 / 启用（二次确认）', () => {
  it('停用需 Popconfirm 确认；取消不调用接口', async () => {
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    await user.click(screen.getAllByRole('button', { name: /^停\s*用$/ })[0]);
    expect(await screen.findByText(/停用后「张三」不能登录、借书或续借/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^取\s*消$/ }));
    expect(mockUpdate).not.toHaveBeenCalled();
  });

  it('确认停用后 PUT status=DISABLED，并提示账号同步禁用', async () => {
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    await user.click(screen.getAllByRole('button', { name: /^停\s*用$/ })[0]);
    await user.click(await screen.findByRole('button', { name: /^确\s*认$/ }));
    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith(1, {
        name: '张三',
        phone: '13800000000',
        email: 'zhang@example.com',
        status: 'DISABLED',
      }),
    );
    expect(await screen.findByText('读者已停用，账号同步禁用')).toBeInTheDocument();
  });

  it('已停用读者显示"启用"，确认后 PUT status=ACTIVE', async () => {
    mockUpdate.mockResolvedValue({ ...reader2, status: 'ACTIVE' });
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000002');
    await user.click(screen.getByRole('button', { name: /^启\s*用$/ }));
    await user.click(await screen.findByRole('button', { name: /^确\s*认$/ }));
    await waitFor(() =>
      expect(mockUpdate).toHaveBeenCalledWith(
        2,
        expect.objectContaining({ name: '李四', status: 'ACTIVE' }),
      ),
    );
    expect(await screen.findByText('读者已启用，账号恢复登录')).toBeInTheDocument();
  });
});

describe('AC-RDR-03 删除（二次确认与 409 文案）', () => {
  it('确认删除后调用 DELETE，成功提示账号一并删除', async () => {
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    await user.click(screen.getAllByRole('button', { name: /^删\s*除$/ })[0]);
    expect(await screen.findByText(/删除后账号一并删除，且不可恢复/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^确\s*认$/ }));
    await waitFor(() => expect(mockDelete).toHaveBeenCalledWith(1));
    expect(await screen.findByText('读者已删除，账号一并删除')).toBeInTheDocument();
  });

  it('有在借记录时 409 READER_HAS_ACTIVE_LOANS 显示对应中文文案', async () => {
    mockDelete.mockRejectedValue(new ApiError(409, 'READER_HAS_ACTIVE_LOANS', 'active loans'));
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    await user.click(screen.getAllByRole('button', { name: /^删\s*除$/ })[0]);
    await user.click(await screen.findByRole('button', { name: /^确\s*认$/ }));
    expect(
      await screen.findByText('该读者有在借图书，不能删除，可改为停用'),
    ).toBeInTheDocument();
  });

  it('有借阅历史时 409 READER_HAS_LOAN_HISTORY 显示对应中文文案', async () => {
    mockDelete.mockRejectedValue(new ApiError(409, 'READER_HAS_LOAN_HISTORY', 'history'));
    const user = userEvent.setup();
    renderList();
    await screen.findByText('R000001');
    await user.click(screen.getAllByRole('button', { name: /^删\s*除$/ })[0]);
    await user.click(await screen.findByRole('button', { name: /^确\s*认$/ }));
    expect(
      await screen.findByText('该读者存在借阅历史，不能删除，可改为停用'),
    ).toBeInTheDocument();
  });
});