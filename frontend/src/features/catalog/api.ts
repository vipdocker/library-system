// catalog feature 接口函数。契约来源：docs/design/solution-design.md 第 8.3 / 8.4 节（design-v0.1）。
// 共享 client / types 只读（T02 产出）；本文件只负责 catalog 的路径、参数与返回类型。
import { apiDelete, apiGet, apiPatch, apiPost, apiPut } from '../../api/client';
import type {
  BookCreateInput,
  BookDetail,
  BookInput,
  BookSearchField,
  BookSummary,
  Category,
  CategoryInput,
  Copy,
  CopyBatchInput,
  CopyStatusInput,
  Page,
} from '../../api/types';

/** 图书检索排序白名单（第 8.4 节）：title、author、publishYear、createdAt；默认 title,asc。 */
export const BOOK_SORT_FIELDS = ['title', 'author', 'publishYear', 'createdAt'] as const;
export type BookSortField = (typeof BOOK_SORT_FIELDS)[number];
export const DEFAULT_BOOK_SORT = 'title,asc';

export interface BookQuery {
  q: string;
  field: BookSearchField;
  categoryId?: number;
  availableOnly: boolean;
  /** 从 0 起 */
  page: number;
  size: number;
  /** `${field},${'asc'|'desc'}`，字段必须在白名单内 */
  sort: string;
}

export const DEFAULT_BOOK_QUERY: BookQuery = {
  q: '',
  field: 'all',
  categoryId: undefined,
  availableOnly: false,
  page: 0,
  size: 20,
  sort: DEFAULT_BOOK_SORT,
};

/** catalog 的 Query Key 约定：统一前缀 'catalog'，mutation 后按前缀失效。 */
export const catalogKeys = {
  all: ['catalog'] as const,
  categories: () => [...catalogKeys.all, 'categories'] as const,
  books: (query: BookQuery) => [...catalogKeys.all, 'books', query] as const,
  book: (id: number) => [...catalogKeys.all, 'book', id] as const,
  copies: (bookId: number) => [...catalogKeys.all, 'copies', bookId] as const,
};

// ---------- 分类 ----------

export function listCategories(): Promise<Category[]> {
  return apiGet<Category[]>('/api/categories');
}

export function createCategory(input: CategoryInput): Promise<Category> {
  return apiPost<Category>('/api/categories', input);
}

export function updateCategory(id: number, input: CategoryInput): Promise<Category> {
  return apiPut<Category>(`/api/categories/${id}`, input);
}

export function deleteCategory(id: number): Promise<void> {
  return apiDelete<void>(`/api/categories/${id}`);
}

// ---------- 图书 ----------

export function searchBooks(query: BookQuery): Promise<Page<BookSummary>> {
  return apiGet<Page<BookSummary>>('/api/books', {
    q: query.q.trim() || undefined,
    field: query.field,
    categoryId: query.categoryId,
    availableOnly: query.availableOnly ? true : undefined,
    page: query.page,
    size: query.size,
    sort: query.sort,
  });
}

export function getBook(id: number): Promise<BookDetail> {
  return apiGet<BookDetail>(`/api/books/${id}`);
}

export function createBook(input: BookCreateInput): Promise<BookDetail> {
  return apiPost<BookDetail>('/api/books', input);
}

export function updateBook(id: number, input: BookInput): Promise<BookDetail> {
  return apiPut<BookDetail>(`/api/books/${id}`, input);
}

export function deleteBook(id: number): Promise<void> {
  return apiDelete<void>(`/api/books/${id}`);
}

// ---------- 副本 ----------

export function listCopies(bookId: number): Promise<Copy[]> {
  return apiGet<Copy[]>(`/api/books/${bookId}/copies`);
}

export function addCopies(bookId: number, input: CopyBatchInput): Promise<Copy[]> {
  return apiPost<Copy[]>(`/api/books/${bookId}/copies`, input);
}

export function updateCopyStatus(copyId: number, input: CopyStatusInput): Promise<Copy> {
  return apiPatch<Copy>(`/api/copies/${copyId}`, input);
}

export function deleteCopy(copyId: number): Promise<void> {
  return apiDelete<void>(`/api/copies/${copyId}`);
}