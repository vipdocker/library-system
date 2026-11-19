// catalog api.ts 与契约（solution-design 8.4）的路径/方法/参数映射核对，供 T13 联调对照。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiDelete, apiGet, apiPatch, apiPost, apiPut } from '../../../api/client';
import {
  DEFAULT_BOOK_QUERY,
  addCopies,
  createBook,
  createCategory,
  deleteBook,
  deleteCategory,
  deleteCopy,
  getBook,
  listCategories,
  listCopies,
  searchBooks,
  updateBook,
  updateCategory,
  updateCopyStatus,
} from '../api';

vi.mock('../../../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/client')>();
  return {
    ...actual,
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    apiPut: vi.fn(),
    apiPatch: vi.fn(),
    apiDelete: vi.fn(),
  };
});

beforeEach(() => {
  vi.mocked(apiGet).mockResolvedValue(undefined);
  vi.mocked(apiPost).mockResolvedValue(undefined);
  vi.mocked(apiPut).mockResolvedValue(undefined);
  vi.mocked(apiPatch).mockResolvedValue(undefined);
  vi.mocked(apiDelete).mockResolvedValue(undefined);
});

describe('AC-CAT-01 分类接口映射', () => {
  it('GET/POST/PUT/DELETE /api/categories', async () => {
    await listCategories();
    expect(apiGet).toHaveBeenCalledWith('/api/categories');
    await createCategory({ name: '文学', description: null });
    expect(apiPost).toHaveBeenCalledWith('/api/categories', { name: '文学', description: null });
    await updateCategory(2, { name: '文学经典' });
    expect(apiPut).toHaveBeenCalledWith('/api/categories/2', { name: '文学经典' });
    await deleteCategory(2);
    expect(apiDelete).toHaveBeenCalledWith('/api/categories/2');
  });
});

describe('AC-SRCH-02 / AC-BOOK-01 图书接口映射', () => {
  it('searchBooks：默认查询省略空 q 与 false 的 availableOnly', async () => {
    await searchBooks(DEFAULT_BOOK_QUERY);
    expect(apiGet).toHaveBeenCalledWith('/api/books', {
      q: undefined,
      field: 'all',
      categoryId: undefined,
      availableOnly: undefined,
      page: 0,
      size: 20,
      sort: 'title,asc',
    });
  });

  it('searchBooks：完整条件逐项传递，q 去首尾空白', async () => {
    await searchBooks({
      q: ' 三体 ',
      field: 'title',
      categoryId: 2,
      availableOnly: true,
      page: 1,
      size: 10,
      sort: 'author,desc',
    });
    expect(apiGet).toHaveBeenCalledWith('/api/books', {
      q: '三体',
      field: 'title',
      categoryId: 2,
      availableOnly: true,
      page: 1,
      size: 10,
      sort: 'author,desc',
    });
  });

  it('详情 / 新建 / 更新 / 删除路径与请求体', async () => {
    await getBook(5);
    expect(apiGet).toHaveBeenCalledWith('/api/books/5');
    const input = { isbn: '9780306406157', title: 't', author: 'a' };
    await createBook({ ...input, initialCopies: 2 });
    expect(apiPost).toHaveBeenCalledWith('/api/books', { ...input, initialCopies: 2 });
    await updateBook(5, input);
    expect(apiPut).toHaveBeenCalledWith('/api/books/5', input);
    await deleteBook(5);
    expect(apiDelete).toHaveBeenCalledWith('/api/books/5');
  });
});

describe('AC-COPY-01 / AC-COPY-02 副本接口映射', () => {
  it('列表 / 追加 / 改状态 / 删除', async () => {
    await listCopies(5);
    expect(apiGet).toHaveBeenCalledWith('/api/books/5/copies');
    await addCopies(5, { count: 3 });
    expect(apiPost).toHaveBeenCalledWith('/api/books/5/copies', { count: 3 });
    await updateCopyStatus(11, { status: 'UNAVAILABLE' });
    expect(apiPatch).toHaveBeenCalledWith('/api/copies/11', { status: 'UNAVAILABLE' });
    await deleteCopy(11);
    expect(apiDelete).toHaveBeenCalledWith('/api/copies/11');
  });
});