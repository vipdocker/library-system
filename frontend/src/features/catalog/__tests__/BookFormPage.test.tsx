// AC-BOOK-01：新建图书（ISBN 规范化 + 严格校验位、initialCopies 默认 1、fieldErrors 显示）。
// AC-UI-02：前端必填/格式校验、提交中禁用防重复、服务端 fieldErrors 映射到字段。
// AC-COPY-01/02（UI）：编辑页副本追加、状态切换（二次确认）、删除（二次确认）、409 中文文案、在借副本禁用操作。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError, UNAUTHORIZED_EVENT } from '../../../api/client';
import {
  addCopies,
  catalogKeys,
  createBook,
  deleteCopy,
  getBook,
  lookupOpenLibrary,
  lookupShowApi,
  searchBooks,
  listCategories,
  listCopies,
  updateBook,
  updateCopyStatus,
} from '../api';
import BookFormPage from '../pages/BookFormPage';
import { sourceRadioTheme } from '../components/OpenLibraryCandidatePanel';
import { claudeTheme, claudeTokens } from '../../../styles/claudeTheme';
import { categoriesFixture, makeBookDetail, makeCopy, renderCatalog } from './testUtils';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    getBook: vi.fn(),
    lookupOpenLibrary: vi.fn(),
    lookupShowApi: vi.fn(),
    searchBooks: vi.fn(),
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

// antd radio-button 的原生 input 为 pointer-events:none，真实用户点击的是外层 label。
async function chooseSource(name: 'Open Library' | 'ShowAPI') {
  await userEvent.click(screen.getByRole('radio', { name }).closest('label')!);
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

describe('AC-FE-01/02/05 draft and explicit lookup', () => {
  const candidate = { isbn: '9780306406157', found: true, sourceUrl: 'https://openlibrary.org/isbn/9780306406157', fields: { title: '候选标题', author: null, publisher: '候选出版社', description: null, publishYear: 2020 }, cover: null, warnings: [], retryAfterSeconds: null };
  it('query changes nothing; default selects only empty fields, cancel preserves manual input', async () => {
    vi.mocked(lookupOpenLibrary).mockResolvedValue(candidate);
    renderCreate();
    await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    expect(await screen.findByText('候选标题')).toBeInTheDocument();
    expect(screen.getByText('未提供封面')).toBeInTheDocument();
    expect(screen.queryByText('封面暂不可用，请查看下方提示')).not.toBeInTheDocument();
    expect(screen.getByLabelText('书名')).toHaveValue('测试之书');
    expect(screen.getByRole('checkbox', { name: '应用书名' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: '应用出版社' })).toBeChecked();
    await userEvent.click(screen.getByRole('button', { name: '取消候选' }));
    expect(screen.getByLabelText('出版社')).toHaveValue('');
    expect(createBook).not.toHaveBeenCalled();
  });
  it('cover warning distinguishes unavailable image from an upstream with no cover', async () => {
    const warning = '封面归档服务限流，请稍后手动重试，书目信息仍可应用';
    vi.mocked(lookupOpenLibrary).mockResolvedValue({ ...candidate, warnings: [{ code: 'OPEN_LIBRARY_RATE_LIMITED', field: 'cover', message: warning }], retryAfterSeconds: 7 });
    renderCreate(); await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    expect(await screen.findByText('候选标题')).toBeInTheDocument();
    expect(screen.getByText('封面暂不可用，请查看下方提示')).toBeInTheDocument();
    expect(screen.queryByText('未提供封面')).not.toBeInTheDocument();
    expect(screen.getByText(warning)).toBeInTheDocument();
    expect(screen.getByText('请等待 7 秒后手动重试')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '应用候选封面' })).toBeDisabled();
    expect(screen.getByLabelText('书名')).toHaveValue('测试之书');
    expect(lookupOpenLibrary).toHaveBeenCalledTimes(1); expect(createBook).not.toHaveBeenCalled();
  });
  it('explicit partial apply sets provenance only after application and submits with the sole save button', async () => {
    vi.mocked(lookupOpenLibrary).mockResolvedValue(candidate);
    renderCreate();
    await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    await screen.findByText('候选标题');
    await userEvent.click(screen.getByRole('button', { name: '应用选中项' }));
    expect(screen.getByLabelText('书名')).toHaveValue('测试之书');
    expect(screen.getByLabelText('出版社')).toHaveValue('候选出版社');
    expect(createBook).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: /创建图书/ }));
    await waitFor(() => expect(createBook).toHaveBeenCalledWith(expect.objectContaining({ title: '测试之书', publisher: '候选出版社', openLibraryIsbn: candidate.isbn })));
  });
  it('editing an explicitly selected field requires reselection, rather than restoring a stale snapshot', async () => {
    vi.mocked(lookupOpenLibrary).mockResolvedValue(candidate);
    renderCreate(); await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    await screen.findByText('候选标题');
    await userEvent.click(screen.getByRole('checkbox', { name: '应用书名' }));
    await userEvent.type(screen.getByLabelText('书名'), '新编辑');
    await userEvent.click(screen.getByRole('button', { name: '应用选中项' }));
    expect(screen.getByLabelText('书名')).toHaveValue('测试之书新编辑');
    expect(await screen.findByText(/已变化，请重新勾选/)).toBeInTheDocument();
  });
  it('ISBN change cancels old response and does not clear manually entered values', async () => {
    let resolve!: (value: typeof candidate) => void;
    vi.mocked(lookupOpenLibrary).mockReturnValue(new Promise((done) => { resolve = done; }));
    renderCreate(); await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    const signal = vi.mocked(lookupOpenLibrary).mock.calls[0][1];
    await userEvent.clear(screen.getByLabelText('ISBN'));
    await userEvent.type(screen.getByLabelText('ISBN'), '9780140328721');
    resolve(candidate);
    await waitFor(() => expect(signal?.aborted).toBe(true));
    expect(screen.queryByText('候选标题')).not.toBeInTheDocument();
    expect(screen.getByLabelText('书名')).toHaveValue('测试之书');
  });
  it('not found/null fields preserve manual fallback without invented author or source', async () => {
    vi.mocked(lookupOpenLibrary).mockResolvedValue({ ...candidate, found: false, fields: { title: null, author: null, publisher: null, description: null, publishYear: null } });
    renderCreate(); await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    expect(await screen.findByText('未找到书目，可继续手动录入')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '应用作者' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: /创建图书/ }));
    await waitFor(() => expect(createBook).toHaveBeenCalledTimes(1));
    expect(vi.mocked(createBook).mock.calls[0][0]).not.toHaveProperty('openLibraryIsbn');
  });
  it('fields entered while lookup is in flight are not preselected or overwritten on arrival', async () => {
    let resolve!: (value: typeof candidate) => void;
    vi.mocked(lookupOpenLibrary).mockReturnValue(new Promise((done) => { resolve = done; }));
    renderCreate(); await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    await userEvent.type(screen.getByLabelText('出版社'), '查询期间人工填写');
    resolve(candidate);
    await screen.findByText('候选标题');
    expect(screen.getByRole('checkbox', { name: '应用出版社' })).not.toBeChecked();
    expect(screen.getByLabelText('出版社')).toHaveValue('查询期间人工填写');
  });
  it('16-second timeout aborts once and preserves manual input with no automatic retries', async () => {
    vi.mocked(lookupOpenLibrary).mockImplementation((_isbn, signal) => new Promise((_resolve, reject) => {
      signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
    }));
    renderCreate(); await fillValidBook();
    vi.useFakeTimers();
    try {
      fireEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
      await act(async () => { await vi.advanceTimersByTimeAsync(16000); });
      expect(screen.getByText('书目查询超时，可继续手动录入或手动重试')).toBeInTheDocument();
      expect(lookupOpenLibrary).toHaveBeenCalledTimes(1);
      expect(screen.getByLabelText('书名')).toHaveValue('测试之书');
      expect(createBook).not.toHaveBeenCalled();
    } finally { vi.useRealTimers(); }
  });
  it('starting a save cancels lookup and rejects its late candidate response', async () => {
    let resolve!: (value: typeof candidate) => void;
    vi.mocked(lookupOpenLibrary).mockReturnValue(new Promise((done) => { resolve = done; }));
    vi.mocked(createBook).mockReturnValue(new Promise(() => {}));
    renderCreate(); await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    const signal = vi.mocked(lookupOpenLibrary).mock.calls[0][1];
    await userEvent.click(screen.getByRole('button', { name: /创建图书/ }));
    await waitFor(() => expect(signal?.aborted).toBe(true));
    resolve(candidate);
    expect(screen.queryByText('候选标题')).not.toBeInTheDocument();
    expect(screen.getByLabelText('书名')).toHaveValue('测试之书');
  });
  it.each([429, 502, 403])('lookup failure %s leaves manual form intact and no writes', async (status) => {
    vi.mocked(lookupOpenLibrary).mockRejectedValue(new ApiError(status, status === 429 ? 'OPEN_LIBRARY_RATE_LIMITED' : status === 403 ? 'FORBIDDEN' : 'OPEN_LIBRARY_UNAVAILABLE', 'failed', [], { retryAfterSeconds: 42 }));
    renderCreate(); await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    expect(await screen.findByText(status === 429 ? /42 秒/ : status === 403 ? /无权限执行/ : /暂不可用/)).toBeInTheDocument();
    expect(screen.getByLabelText('书名')).toHaveValue('测试之书');
    expect(createBook).not.toHaveBeenCalled();
  });
  it('OL-OPT-DIAG upstream 401 arrives as local 502 diagnostics, never a logout', async () => {
    const unauthorized = vi.fn(); window.addEventListener(UNAUTHORIZED_EVENT, unauthorized);
    try {
      vi.mocked(lookupOpenLibrary).mockRejectedValue(new ApiError(502, 'OPEN_LIBRARY_UNAVAILABLE', 'failed', [], { reason: 'HTTP_ERROR', stage: 'METADATA', provider: 'OPEN_LIBRARY', upstreamStatus: 401, lastRoute: 'SOCKS5', proxyOutcome: 'RESPONDED' }));
      renderCreate(); await fillValidBook();
      await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
      expect(await screen.findByText(/暂不可用/)).toBeInTheDocument();
      expect(screen.getByText('书目 · Open Library · 上游 HTTP 401 · 经代理（代理已响应）')).toBeInTheDocument();
      expect(unauthorized).not.toHaveBeenCalled();
      expect(screen.getByLabelText('书名')).toHaveValue('测试之书');
    } finally { window.removeEventListener(UNAUTHORIZED_EVENT, unauthorized); }
  });
  it('OL-OPT-DIAG transmission failure states no upstream response instead of a guessed status', async () => {
    vi.mocked(lookupOpenLibrary).mockRejectedValue(new ApiError(502, 'OPEN_LIBRARY_UNAVAILABLE', 'failed', [], { reason: 'CONNECT', stage: 'METADATA', provider: 'OPEN_LIBRARY', lastRoute: 'SOCKS5', proxyOutcome: 'FAILED' }));
    renderCreate(); await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    expect(await screen.findByText('书目 · Open Library · 未收到上游响应 · 经代理（代理连接失败）')).toBeInTheDocument();
  });
  it('OL-OPT-DIAG long upstream wait never shows a fake seconds value', async () => {
    vi.mocked(lookupOpenLibrary).mockRejectedValue(new ApiError(429, 'OPEN_LIBRARY_RATE_LIMITED', 'failed', [], { reason: 'COOLDOWN', longWait: true }));
    renderCreate(); await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    expect(await screen.findByText(/上游要求长时间等待/)).toBeInTheDocument();
    expect(screen.queryByText(/请等待 .* 秒/)).not.toBeInTheDocument();
  });
  it('OL-OPT-DIAG cover warning shows concise stage/provider/status diagnostics', async () => {
    vi.mocked(lookupOpenLibrary).mockResolvedValue({ ...candidate, warnings: [{ code: 'COVER_UNAVAILABLE', field: 'cover', message: '封面获取失败，书目信息仍可应用', details: { reason: 'HTTP_ERROR', stage: 'COVER', provider: 'ARCHIVE', upstreamStatus: 503, lastRoute: 'DIRECT', proxyOutcome: 'NOT_ATTEMPTED' } }] });
    renderCreate(); await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    expect(await screen.findByText('封面获取失败，书目信息仍可应用')).toBeInTheDocument();
    expect(screen.getByText('封面 · 封面归档服务 · 上游 HTTP 503 · 直连')).toBeInTheDocument();
    expect(screen.getByText('封面暂不可用，请查看下方提示')).toBeInTheDocument();
  });
  it('disconnected POST rereads the submitted ISBN even if draft ISBN has changed, never automatically resubmits', async () => {
    vi.mocked(createBook).mockRejectedValue(new TypeError('response disconnected'));
    vi.mocked(searchBooks).mockResolvedValue({ items: [makeBookDetail()], totalItems: 1, totalPages: 1, page: 0, size: 20 });
    renderCreate(); await fillValidBook();
    await userEvent.click(screen.getByRole('button', { name: /创建图书/ }));
    expect(await screen.findByText(/保存结果未知/)).toBeInTheDocument();
    await userEvent.clear(screen.getByLabelText('ISBN')); await userEvent.type(screen.getByLabelText('ISBN'), '9780140328721');
    await userEvent.click(screen.getByRole('button', { name: '重新读取核对' }));
    await waitFor(() => expect(searchBooks).toHaveBeenCalledWith(expect.objectContaining({ field: 'isbn', q: '9780306406157' })));
    expect(await screen.findByText(/服务器找到该 ISBN/)).toBeInTheDocument();
    expect(createBook).toHaveBeenCalledTimes(1);
  });
  it('server cover field error is associated with the file input and retains its pending draft', async () => {
    vi.mocked(updateBook).mockRejectedValue(new ApiError(400, 'VALIDATION_FAILED', 'invalid', [{ field: 'cover', message: '封面图片不完整' }]));
    renderEdit(); await screen.findByLabelText('书名');
    await userEvent.click(screen.getByRole('button', { name: '移除封面' }));
    await userEvent.click(screen.getByRole('button', { name: /保存修改/ }));
    await waitFor(() => expect(screen.getByLabelText('选择本地封面')).toHaveAttribute('aria-invalid', 'true'));
    expect(screen.getByLabelText('选择本地封面')).toHaveAttribute('aria-describedby', 'cover-help cover-error');
    expect(screen.getByText('待移除，保存后生效')).toBeInTheDocument();
  });
  it('invalid local file leaves earlier draft; remove is pending and revert omits cover', async () => {
    renderEdit(); await screen.findByLabelText('书名');
    const input = screen.getByLabelText('选择本地封面');
    fireEvent.change(input, { target: { files: [new File(['bad'], 'bad.png', { type: 'image/png' })] } });
    expect(await screen.findByText(/类型不一致|图片不完整/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '移除封面' }));
    expect(updateBook).not.toHaveBeenCalled();
    expect(screen.getByText('待移除，保存后生效')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '撤销封面修改' }));
    await userEvent.click(screen.getByRole('button', { name: /保存修改/ }));
    await waitFor(() => expect(updateBook).toHaveBeenCalledTimes(1));
    expect(vi.mocked(updateBook).mock.calls[0][1]).not.toHaveProperty('cover');
  });
});

describe('AC-FE-01 cover races and saved baseline', () => {
  const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC';
  const file = () => new File([Uint8Array.from(atob(png), (c) => c.charCodeAt(0))], 'one.png', { type: 'image/png' });
  beforeEach(() => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:preview');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    vi.stubGlobal('Image', class { naturalWidth = 1; naturalHeight = 1; onload?: () => void; set src(_value: string) { queueMicrotask(() => this.onload?.()); } });
  });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
  it('valid local preview survives invalid replacement and failed save; exact payload is frozen and all edits disabled', async () => {
    let reject!: (error: Error) => void;
    vi.mocked(updateBook).mockReturnValue(new Promise((_resolve, fail) => { reject = fail; }));
    renderEdit(); await screen.findByLabelText('书名');
    await userEvent.upload(screen.getByLabelText('选择本地封面'), file());
    await screen.findByText('待保存 · 本地上传');
    fireEvent.change(screen.getByLabelText('选择本地封面'), { target: { files: [new File(['bad'], 'bad.png', { type: 'image/png' })] } });
    await screen.findByText(/类型不一致|图片不完整/);
    expect(screen.getByText('待保存 · 本地上传')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /保存修改/ }));
    await waitFor(() => expect(updateBook).toHaveBeenCalledTimes(1));
    expect(vi.mocked(updateBook).mock.calls[0][1].cover).toEqual({ action: 'REPLACE', mediaType: 'image/png', base64: png, sourceIsbn: null });
    expect(screen.getByLabelText('书名')).toBeDisabled();
    expect(screen.getByLabelText('选择本地封面')).toBeDisabled();
    reject(new ApiError(0, 'NETWORK_ERROR', 'disconnected'));
    expect(await screen.findByText(/保存结果未知/)).toBeInTheDocument();
    expect(screen.getByText('待保存 · 本地上传')).toBeInTheDocument();
    expect(updateBook).toHaveBeenCalledTimes(1);
  });
  it('late file decode cannot resurrect a removed draft; same file can be chosen again', async () => {
    const loads: Array<() => void> = [];
    vi.stubGlobal('Image', class { naturalWidth = 1; naturalHeight = 1; onload?: () => void; set src(_value: string) { loads.push(() => this.onload?.()); } });
    renderEdit(); await screen.findByLabelText('书名');
    await userEvent.upload(screen.getByLabelText('选择本地封面'), file());
    await waitFor(() => expect(loads).toHaveLength(1));
    await userEvent.click(screen.getByRole('button', { name: '移除封面' }));
    loads[0]();
    await screen.findByText('待移除，保存后生效');
    expect(screen.queryByText('待保存 · 本地上传')).not.toBeInTheDocument();
    await userEvent.upload(screen.getByLabelText('选择本地封面'), file());
    await waitFor(() => expect(loads).toHaveLength(2));
    loads[1]();
    expect(await screen.findByText('待保存 · 本地上传')).toBeInTheDocument();
  });
  it('successful save resets to server baseline; refetch never overwrites dirty form or resets cover draft', async () => {
    const view = renderEdit(); await screen.findByLabelText('书名');
    await userEvent.type(screen.getByLabelText('书名'), '人工修改');
    await userEvent.click(screen.getByRole('button', { name: '移除封面' }));
    vi.mocked(getBook).mockResolvedValue(makeBookDetail({ title: '另一窗口修改' }));
    await view.client.invalidateQueries({ queryKey: catalogKeys.book(5) });
    expect(screen.getByLabelText('书名')).toHaveValue('深入理解计算机系统人工修改');
    expect(screen.getByText('待移除，保存后生效')).toBeInTheDocument();
    vi.mocked(updateBook).mockResolvedValue(makeBookDetail({ title: '服务器规范化' }));
    await userEvent.click(screen.getByRole('button', { name: /保存修改/ }));
    await waitFor(() => expect(screen.getByLabelText('书名')).toHaveValue('服务器规范化'));
    expect(screen.getByText('未修改封面')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /保存修改/ }));
    await waitFor(() => expect(updateBook).toHaveBeenCalledTimes(2));
    expect(vi.mocked(updateBook).mock.calls[1][1]).not.toHaveProperty('cover');
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

// showapi-design-v1 / showapi-ac-dod-v1：双来源候选（AC-S1/S2/S3/T1/T2/T3）。
describe('AC-S1/S2 ShowAPI 双来源候选', () => {
  const showApiCandidate = {
    provider: 'SHOWAPI' as const, isbn: '9780306406157', found: true,
    sourceUrl: 'https://www.showapi.com/apis/isbn-book-1626',
    fields: { title: 'ShowAPI候选标题', author: null, publisher: 'ShowAPI出版社', description: '<b>纯文本</b>简介', publishYear: 2021 },
    additional: { publicationDate: '2021-05', edition: '第2版', pageCountText: '300页', format: '16开', paper: '胶版纸', binding: '平装', priceText: '59.00元' },
    capabilities: { tableOfContents: false },
    cover: null, warnings: [], retryAfterSeconds: null,
  };
  it('AC-S1 默认 Open Library；切换来源本身不请求，查询按钮只调用选中来源', async () => {
    vi.mocked(lookupShowApi).mockResolvedValue(showApiCandidate);
    renderCreate(); await fillValidBook();
    expect(screen.getByRole('radio', { name: 'Open Library' })).toBeChecked();
    await chooseSource('ShowAPI');
    expect(lookupShowApi).not.toHaveBeenCalled();
    expect(lookupOpenLibrary).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    expect(await screen.findByText('ShowAPI候选标题')).toBeInTheDocument();
    expect(lookupShowApi).toHaveBeenCalledTimes(1);
    expect(lookupShowApi).toHaveBeenCalledWith('9780306406157', expect.anything());
    expect(lookupOpenLibrary).not.toHaveBeenCalled();
    expect(createBook).not.toHaveBeenCalled();
  });
  it('AC-S3/T2 附加字段只读、无目录、简介按纯文本渲染、来源链接为后端固定说明页', async () => {
    vi.mocked(lookupShowApi).mockResolvedValue(showApiCandidate);
    renderCreate(); await fillValidBook();
    await chooseSource('ShowAPI');
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    await screen.findByText('ShowAPI候选标题');
    expect(screen.getByText('此来源未提供目录')).toBeInTheDocument();
    for (const text of ['2021-05', '第2版', '300页', '16开', '胶版纸', '平装', '59.00元']) expect(screen.getByText(text)).toBeInTheDocument();
    expect(screen.queryByRole('checkbox', { name: /应用定价|应用版次|应用页数/ })).not.toBeInTheDocument();
    expect(screen.getByText('<b>纯文本</b>简介')).toBeInTheDocument();
    expect(document.querySelector('.ant-alert b, section[aria-label="ISBN 书目候选"] b')).toBeNull();
    expect(screen.getByRole('link', { name: 'ShowAPI 来源' })).toHaveAttribute('href', 'https://www.showapi.com/apis/isbn-book-1626');
  });
  it('AC-S2/T3 应用空字段后保存携带 metadataSource=SHOWAPI，不携带 openLibraryIsbn，已填书名不覆盖', async () => {
    vi.mocked(lookupShowApi).mockResolvedValue(showApiCandidate);
    renderCreate(); await fillValidBook();
    await chooseSource('ShowAPI');
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    await screen.findByText('ShowAPI候选标题');
    expect(screen.getByRole('checkbox', { name: '应用书名' })).not.toBeChecked();
    expect(screen.getByRole('checkbox', { name: '应用出版社' })).toBeChecked();
    await userEvent.click(screen.getByRole('button', { name: '应用选中项' }));
    expect(screen.getByLabelText('书名')).toHaveValue('测试之书');
    expect(screen.getByLabelText('出版社')).toHaveValue('ShowAPI出版社');
    expect(createBook).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole('button', { name: /创建图书/ }));
    await waitFor(() => expect(createBook).toHaveBeenCalledTimes(1));
    const input = vi.mocked(createBook).mock.calls[0][0];
    expect(input.metadataSource).toEqual({ provider: 'SHOWAPI', isbn: '9780306406157' });
    expect(input).not.toHaveProperty('openLibraryIsbn');
    expect(input.description).toBe('<b>纯文本</b>简介');
  });
  it('AC-S2 未找到/查询未成功文案不笃定缺书，且不捏造 HTTP 诊断', async () => {
    vi.mocked(lookupShowApi).mockResolvedValue({ ...showApiCandidate, found: false, fields: { title: null, author: null, publisher: null, description: null, publishYear: null }, warnings: [{ code: 'LOOKUP_UNSUCCESSFUL', field: null, message: '查询未成功或未收录，可继续手工录入' }] });
    renderCreate(); await fillValidBook();
    await chooseSource('ShowAPI');
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    expect(await screen.findByText('查询未成功或未收录，可继续手工录入')).toBeInTheDocument();
    expect(screen.queryByText(/上游 HTTP/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /创建图书/ }));
    await waitFor(() => expect(createBook).toHaveBeenCalledTimes(1));
    expect(vi.mocked(createBook).mock.calls[0][0]).not.toHaveProperty('metadataSource');
  });
  it('AC-S3 封面失败但书目可用：warning 可见、封面勾选禁用、无 OL 诊断行', async () => {
    vi.mocked(lookupShowApi).mockResolvedValue({ ...showApiCandidate, warnings: [{ code: 'SHOWAPI_RATE_LIMITED', field: 'cover', message: '封面请求限流，请稍后手动重试，书目信息仍可应用' }], retryAfterSeconds: 5 });
    renderCreate(); await fillValidBook();
    await chooseSource('ShowAPI');
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    await screen.findByText('ShowAPI候选标题');
    expect(screen.getByText('封面暂不可用，请查看下方提示')).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: '应用候选封面' })).toBeDisabled();
    expect(screen.getByText('请等待 5 秒后手动重试')).toBeInTheDocument();
    expect(screen.queryByText(/上游 HTTP|经代理|直连/)).not.toBeInTheDocument();
  });
  it.each([
    [409, 'SHOWAPI_NOT_CONFIGURED', /尚未启用或未配置/],
    [429, 'SHOWAPI_RATE_LIMITED', /9 秒/],
    [502, 'SHOWAPI_AUTH_FAILED', /凭据被上游拒绝/],
    [502, 'SHOWAPI_UNAVAILABLE', /暂不可用/],
  ])('AC-T1/T2 ShowAPI 错误 %s %s 保留人工录入且不登出', async (status, code, pattern) => {
    const unauthorized = vi.fn(); window.addEventListener(UNAUTHORIZED_EVENT, unauthorized);
    try {
      vi.mocked(lookupShowApi).mockRejectedValue(new ApiError(status as number, code as string, 'failed', [], code === 'SHOWAPI_RATE_LIMITED' ? { retryAfterSeconds: 9, reason: 'THROTTLED' } : {}));
      renderCreate(); await fillValidBook();
      await chooseSource('ShowAPI');
      await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
      expect(await screen.findByText(pattern as RegExp)).toBeInTheDocument();
      expect(screen.getByLabelText('书名')).toHaveValue('测试之书');
      expect(createBook).not.toHaveBeenCalled();
      expect(unauthorized).not.toHaveBeenCalled();
      expect(lookupShowApi).toHaveBeenCalledTimes(1);
    } finally { window.removeEventListener(UNAUTHORIZED_EVENT, unauthorized); }
  });
  it('AC-T1 来源切换拒绝迟到 ShowAPI 结果并中止请求', async () => {
    let resolve!: (value: typeof showApiCandidate) => void;
    vi.mocked(lookupShowApi).mockReturnValue(new Promise((done) => { resolve = done; }));
    renderCreate(); await fillValidBook();
    await chooseSource('ShowAPI');
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    const signal = vi.mocked(lookupShowApi).mock.calls[0][1];
    await chooseSource('Open Library');
    resolve(showApiCandidate);
    await waitFor(() => expect(signal?.aborted).toBe(true));
    expect(screen.queryByText('ShowAPI候选标题')).not.toBeInTheDocument();
    expect(screen.getByLabelText('书名')).toHaveValue('测试之书');
  });
  it('AC-T1 查询期间人工输入不被预选或覆盖（ShowAPI 路径）', async () => {
    let resolve!: (value: typeof showApiCandidate) => void;
    vi.mocked(lookupShowApi).mockReturnValue(new Promise((done) => { resolve = done; }));
    renderCreate(); await fillValidBook();
    await chooseSource('ShowAPI');
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    await userEvent.type(screen.getByLabelText('出版社'), '查询期间人工填写');
    resolve(showApiCandidate);
    await screen.findByText('ShowAPI候选标题');
    expect(screen.getByRole('checkbox', { name: '应用出版社' })).not.toBeChecked();
    expect(screen.getByLabelText('出版社')).toHaveValue('查询期间人工填写');
  });
  it('AC-S1 切回 Open Library 后仍走 OL 查询（回归）', async () => {
    vi.mocked(lookupOpenLibrary).mockResolvedValue({ isbn: '9780306406157', found: true, sourceUrl: 'https://openlibrary.org/isbn/9780306406157', fields: { title: 'OL候选', author: null, publisher: null, description: null, publishYear: null }, cover: null, warnings: [], retryAfterSeconds: null });
    renderCreate(); await fillValidBook();
    await chooseSource('ShowAPI');
    await chooseSource('Open Library');
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    expect(await screen.findByText('OL候选')).toBeInTheDocument();
    expect(lookupOpenLibrary).toHaveBeenCalledTimes(1);
    expect(lookupShowApi).not.toHaveBeenCalled();
    expect(screen.getByRole('link', { name: 'Open Library 来源' })).toBeInTheDocument();
  });
});

describe('AC-S3/T3 ShowAPI 封面草稿与来源独立', () => {
  const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC';
  const showApiCandidate = {
    provider: 'SHOWAPI' as const, isbn: '9780306406157', found: true,
    sourceUrl: 'https://www.showapi.com/apis/isbn-book-1626',
    fields: { title: 'ShowAPI候选标题', author: null, publisher: null, description: null, publishYear: null },
    additional: { publicationDate: null, edition: null, pageCountText: null, format: null, paper: null, binding: null, priceText: null },
    capabilities: { tableOfContents: false },
    cover: { mediaType: 'image/png' as const, base64: png, byteLength: 69, width: 1, height: 1 },
    warnings: [], retryAfterSeconds: null,
  };
  beforeEach(() => {
    vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:preview');
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
    vi.stubGlobal('Image', class { naturalWidth = 1; naturalHeight = 1; onload?: () => void; set src(_value: string) { queueMicrotask(() => this.onload?.()); } });
  });
  afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });
  it('封面默认不选；仅勾选封面保存时 cover.source=SHOWAPI 且不带书目来源', async () => {
    vi.mocked(lookupShowApi).mockResolvedValue(showApiCandidate);
    renderCreate(); await fillValidBook();
    await chooseSource('ShowAPI');
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    await screen.findByText('ShowAPI候选标题');
    expect(screen.getByRole('checkbox', { name: '应用候选封面' })).not.toBeChecked();
    await userEvent.click(screen.getByRole('checkbox', { name: '应用候选封面' }));
    await userEvent.click(screen.getByRole('button', { name: '应用选中项' }));
    expect(screen.getByText('待保存 · ShowAPI 候选')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /创建图书/ }));
    await waitFor(() => expect(createBook).toHaveBeenCalledTimes(1));
    const input = vi.mocked(createBook).mock.calls[0][0];
    expect(input.cover).toEqual({ action: 'REPLACE', mediaType: 'image/png', base64: png, source: { provider: 'SHOWAPI', isbn: '9780306406157' } });
    expect(input.cover).not.toHaveProperty('sourceIsbn');
    expect(input).not.toHaveProperty('metadataSource');
    expect(input).not.toHaveProperty('openLibraryIsbn');
  });
  it('只应用书目字段时保留先前本地上传封面草稿（来源独立）', async () => {
    vi.mocked(lookupShowApi).mockResolvedValue({ ...showApiCandidate, cover: null });
    renderCreate(); await fillValidBook();
    await userEvent.upload(screen.getByLabelText('选择本地封面'), new File([Uint8Array.from(atob(png), (c) => c.charCodeAt(0))], 'one.png', { type: 'image/png' }));
    await screen.findByText('待保存 · 本地上传');
    await chooseSource('ShowAPI');
    await userEvent.click(screen.getByRole('button', { name: '获取书目与封面' }));
    await screen.findByText('ShowAPI候选标题');
    await userEvent.click(screen.getByRole('checkbox', { name: '应用书名' }));
    await userEvent.click(screen.getByRole('button', { name: '应用选中项' }));
    expect(screen.getByText('待保存 · 本地上传')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /创建图书/ }));
    await waitFor(() => expect(createBook).toHaveBeenCalledTimes(1));
    const input = vi.mocked(createBook).mock.calls[0][0];
    expect(input.cover).toEqual({ action: 'REPLACE', mediaType: 'image/png', base64: png, sourceIsbn: null });
    expect(input.metadataSource).toEqual({ provider: 'SHOWAPI', isbn: '9780306406157' });
  });
});

// INT-F1 回归：选中来源按钮文字继承 Radio.colorPrimary，在 colorBgContainer 上须 ≥4.5:1。
// 断言真实导出的局部 theme 配置与真实 claude token 的 WCAG 比值；行为级证据由浏览器 computed 采样提供。
describe('INT-F1 来源选择控件对比度', () => {
  function luminance(hex: string): number {
    const n = hex.replace('#', '');
    const [r, g, b] = [0, 2, 4].map((i) => parseInt(n.slice(i, i + 2), 16) / 255)
      .map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }
  function ratio(fg: string, bg: string): number {
    const [l1, l2] = [luminance(fg), luminance(bg)].sort((a, b) => b - a);
    return (l1 + 0.05) / (l2 + 0.05);
  }
  it('局部 Radio.colorPrimary 使用既有 link token，选中/hover/active 文字均 ≥4.5，且不改背景', () => {
    const bg = claudeTheme.token?.colorBgContainer as string;
    expect(ratio(claudeTokens.colors.primary, bg)).toBeLessThan(4.5);
    const radio = sourceRadioTheme.components?.Radio as Record<string, string>;
    expect(radio.colorPrimary).toBe(claudeTokens.colors.link);
    expect(ratio(radio.colorPrimary, bg)).toBeGreaterThanOrEqual(4.5);
    expect(radio.colorPrimaryHover ?? (claudeTheme.token?.colorPrimaryHover as string)).toBe(claudeTokens.colors['primary-hover']);
    expect(ratio(claudeTheme.token?.colorPrimaryHover as string, bg)).toBeGreaterThanOrEqual(4.5);
    expect(ratio(claudeTheme.token?.colorPrimaryActive as string, bg)).toBeGreaterThanOrEqual(4.5);
    expect(radio.buttonCheckedBg ?? bg).toBe(bg);
    expect(Object.keys(radio)).toEqual(['colorPrimary']);
  });
});