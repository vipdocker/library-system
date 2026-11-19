// AC-BOOK-01：新建图书（ISBN 规范化 + 严格校验位、initialCopies 默认 1、fieldErrors 显示）。
// AC-UI-02：前端必填/格式校验、提交中禁用防重复、服务端 fieldErrors 映射到字段。
// AC-COPY-01/02（UI）：编辑页副本追加、状态切换（二次确认）、删除（二次确认）、409 中文文案、在借副本禁用操作。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../../api/client';
import {
  addCopies,
  createBook,
  deleteCopy,
  getBook,
  listCategories,
  listCopies,
  updateBook,
  updateCopyStatus,
} from '../api';
import BookFormPage from '../pages/BookFormPage';
import { categoriesFixture, makeBookDetail, makeCopy, renderCatalog } from './testUtils';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    getBook: vi.fn(),
    createBook: vi.fn(),
    updateBook: vi.fn(),
    listCategories: vi.fn(),
    listCopies: vi.fn(),
    addCopies: vi.fn(),
    updateCopyStatus: vi.fn(),
    deleteCopy: vi.fn(),
  };
});

const copiesFixture = [
  makeCopy({ id: 11, barcode: 'C00000011', status: 'AVAILABLE' }),
  makeCopy({ id: 12, barcode: 'C00000012', status: 'BORROWED' }),
  makeCopy({ id: 13, barcode: 'C00000013', status: 'UNAVAILABLE' }),
];

function renderCreate() {
  return renderCatalog(<BookFormPage />, { route: '/admin/books/new', path: '/admin/books/new' });
}

function renderEdit() {
  return renderCatalog(<BookFormPage />, {
    route: '/admin/books/5/edit',
    path: '/admin/books/:id/edit',
  });
}

async function fillValidBook() {
  await userEvent.type(screen.getByLabelText('ISBN'), '978-0-306-40615-7');
  await userEvent.type(screen.getByLabelText('书名'), '测试之书');
  await userEvent.type(screen.getByLabelText('作者'), '作者甲');
}

beforeEach(() => {
  vi.mocked(listCategories).mockResolvedValue(categoriesFixture);
  vi.mocked(getBook).mockResolvedValue(makeBookDetail());
  vi.mocked(createBook).mockResolvedValue(makeBookDetail({ id: 9 }));
  vi.mocked(updateBook).mockResolvedValue(makeBookDetail());
  vi.mocked(listCopies).mockResolvedValue(copiesFixture);
  vi.mocked(addCopies).mockResolvedValue([makeCopy({ id: 14, barcode: 'C00000014' })]);
  vi.mocked(updateCopyStatus).mockResolvedValue(makeCopy({ status: 'UNAVAILABLE' }));
  vi.mocked(deleteCopy).mockResolvedValue(undefined);
});

describe('AC-BOOK-01 / AC-UI-02 新建图书表单', () => {
  it('AC-UI-02 必填校验：空提交显示字段错误且不调用接口', async () => {
    renderCreate();
    await userEvent.click(await screen.findByRole('button', { name: /创建图书/ }));
    expect(await screen.findByText('请输入 ISBN')).toBeInTheDocument();
    expect(screen.getByText('请输入书名')).toBeInTheDocument();
    expect(screen.getByText('请输入作者')).toBeInTheDocument();
    expect(vi.mocked(createBook)).not.toHaveBeenCalled();
  });

  it('AC-BOOK-01 ISBN 校验位错误在前端被拦截（D4③ 严格校验）', async () => {
    renderCreate();
    await screen.findByRole('button', { name: /创建图书/ });
    await userEvent.type(screen.getByLabelText('ISBN'), '1234567890');
    await userEvent.type(screen.getByLabelText('书名'), '测试之书');
    await userEvent.type(screen.getByLabelText('作者'), '作者甲');
    await userEvent.click(screen.getByRole('button', { name: /创建图书/ }));
    expect(await screen.findByText(/ISBN 格式或校验位不正确/)).toBeInTheDocument();
    expect(vi.mocked(createBook)).not.toHaveBeenCalled();
  });

  it('AC-BOOK-01 合法提交：ISBN 去连字符规范化，initialCopies 默认 1，可选空字段传 null', async () => {
    renderCreate();
    await screen.findByRole('button', { name: /创建图书/ });
    await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: /创建图书/ }));
    await waitFor(() => {
      expect(vi.mocked(createBook)).toHaveBeenCalledWith({
        isbn: '9780306406157',
        title: '测试之书',
        author: '作者甲',
        publisher: null,
        publishYear: null,
        categoryId: null,
        description: null,
        initialCopies: 1,
      });
    });
    // 创建成功后进入编辑页，便于继续管理副本
    await waitFor(() => {
      expect(screen.getByTestId('location-probe').textContent).toBe('/admin/books/9/edit');
    });
  });

  it('AC-BOOK-01 / AC-UI-02 服务端 fieldErrors 映射到对应表单项', async () => {
    vi.mocked(createBook).mockRejectedValue(
      new ApiError(400, 'VALIDATION_FAILED', 'validation', [
        { field: 'isbn', message: 'ISBN 校验位错误' },
        { field: 'title', message: '书名长度超出限制' },
      ]),
    );
    renderCreate();
    await screen.findByRole('button', { name: /创建图书/ });
    await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: /创建图书/ }));
    expect(await screen.findByText('ISBN 校验位错误')).toBeInTheDocument();
    expect(screen.getByText('书名长度超出限制')).toBeInTheDocument();
  });

  it('AC-BOOK-01 ISBN 重复（409）显示中文文案', async () => {
    vi.mocked(createBook).mockRejectedValue(new ApiError(409, 'ISBN_DUPLICATE', 'dup'));
    renderCreate();
    await screen.findByRole('button', { name: /创建图书/ });
    await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: /创建图书/ }));
    expect(await screen.findByText('该 ISBN 的图书已存在')).toBeInTheDocument();
  });

  it('AC-UI-02 提交中按钮禁用，不会重复提交', async () => {
    vi.mocked(createBook).mockReturnValue(new Promise(() => {}));
    renderCreate();
    await screen.findByRole('button', { name: /创建图书/ });
    await fillValidBook();
    const submit = screen.getByRole('button', { name: /创建图书/ });
    await userEvent.click(submit);
    await waitFor(() => expect(submit).toBeDisabled());
    await userEvent.click(submit);
    expect(vi.mocked(createBook)).toHaveBeenCalledTimes(1);
  });
});

describe('AC-BOOK-01 编辑图书表单', () => {
  it('回填书目信息；保存调用 PUT 语义且不携带 initialCopies', async () => {
    renderEdit();
    const titleInput = await screen.findByLabelText('书名');
    expect(titleInput).toHaveValue('深入理解计算机系统');
    expect(screen.getByLabelText('ISBN')).toHaveValue('9780306406157');
    expect(screen.queryByLabelText('初始副本数量')).not.toBeInTheDocument();
    await userEvent.clear(titleInput);
    await userEvent.type(titleInput, '新标题');
    await userEvent.click(screen.getByRole('button', { name: /保存修改/ }));
    await waitFor(() => {
      expect(vi.mocked(updateBook)).toHaveBeenCalledTimes(1);
    });
    const [idArg, inputArg] = vi.mocked(updateBook).mock.calls[0];
    expect(idArg).toBe(5);
    expect(inputArg).toMatchObject({ title: '新标题', isbn: '9780306406157' });
    expect(inputArg).not.toHaveProperty('initialCopies');
  });

  it('AC-UI-01 加载图书失败时显示错误与重试', async () => {
    vi.mocked(getBook)
      .mockRejectedValueOnce(new ApiError(503, 'DB_BUSY', 'busy'))
      .mockResolvedValue(makeBookDetail());
    renderEdit();
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByLabelText('书名')).toHaveValue('深入理解计算机系统');
  });
});

describe('AC-COPY-01 / AC-COPY-02 编辑页副本管理', () => {
  it('AC-COPY-01 追加副本：数量传给 POST /books/{id}/copies', async () => {
    renderEdit();
    await screen.findByText('C00000011');
    const countInput = screen.getByLabelText('追加数量');
    await userEvent.clear(countInput);
    await userEvent.type(countInput, '3');
    await userEvent.click(screen.getByRole('button', { name: /追加副本/ }));
    await waitFor(() => {
      expect(vi.mocked(addCopies)).toHaveBeenCalledWith(5, { count: 3 });
    });
    expect(await screen.findByText('已追加 1 个副本')).toBeInTheDocument();
  });

  it('AC-COPY-02 设为不可用需二次确认，确认后 PATCH UNAVAILABLE', async () => {
    renderEdit();
    await screen.findByText('C00000011');
    await userEvent.click(screen.getByRole('button', { name: /设为不可用/ }));
    // 未确认前不调用接口
    expect(vi.mocked(updateCopyStatus)).not.toHaveBeenCalled();
    await userEvent.click(await screen.findByRole('button', { name: /确认设为不可用/ }));
    await waitFor(() => {
      expect(vi.mocked(updateCopyStatus)).toHaveBeenCalledWith(11, { status: 'UNAVAILABLE' });
    });
  });

  it('AC-COPY-02 恢复可借直接 PATCH AVAILABLE', async () => {
    renderEdit();
    await screen.findByText('C00000013');
    await userEvent.click(screen.getByRole('button', { name: /恢复可借/ }));
    await waitFor(() => {
      expect(vi.mocked(updateCopyStatus)).toHaveBeenCalledWith(13, { status: 'AVAILABLE' });
    });
  });

  it('AC-COPY-02 删除需二次确认；在借副本没有状态/删除操作', async () => {
    renderEdit();
    await screen.findByText('C00000012');
    expect(screen.getByText('在借中，归还后可操作')).toBeInTheDocument();
    const deleteButtons = screen.getAllByRole('button', { name: /删\s*除$/ });
    expect(deleteButtons).toHaveLength(2); // AVAILABLE 与 UNAVAILABLE 各一，BORROWED 无
    await userEvent.click(deleteButtons[0]);
    expect(vi.mocked(deleteCopy)).not.toHaveBeenCalled();
    await userEvent.click(await screen.findByRole('button', { name: /确认删除/ }));
    await waitFor(() => {
      expect(vi.mocked(deleteCopy)).toHaveBeenCalledWith(11);
    });
  });

  it('AC-COPY-02 删除有借阅历史的副本：409 显示中文文案', async () => {
    vi.mocked(deleteCopy).mockRejectedValue(new ApiError(409, 'COPY_HAS_LOAN_HISTORY', 'hist'));
    renderEdit();
    await screen.findByText('C00000011');
    await userEvent.click(screen.getAllByRole('button', { name: /删\s*除$/ })[0]);
    await userEvent.click(await screen.findByRole('button', { name: /确认删除/ }));
    expect(await screen.findByText('副本存在借阅历史，不能删除')).toBeInTheDocument();
  });

  it('AC-COPY-02 修改在借副本状态被服务端拒绝时显示 COPY_ON_LOAN 文案', async () => {
    vi.mocked(updateCopyStatus).mockRejectedValue(new ApiError(409, 'COPY_ON_LOAN', 'on loan'));
    renderEdit();
    await screen.findByText('C00000011');
    await userEvent.click(screen.getByRole('button', { name: /设为不可用/ }));
    await userEvent.click(await screen.findByRole('button', { name: /确认设为不可用/ }));
    expect(await screen.findByText('副本正在借出中，不能执行该操作')).toBeInTheDocument();
  });

  it('AC-UI-01 副本列表空态给出追加引导', async () => {
    vi.mocked(listCopies).mockResolvedValue([]);
    renderEdit();
    expect(await screen.findByText(/暂无副本，请在右上方追加副本/)).toBeInTheDocument();
  });
});