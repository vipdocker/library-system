// 检索条件与 URL query 的双向换算（AC-SRCH-02：刷新后检索条件不丢失）。
// URL 中省略默认值，保持地址简洁；解析时对非法值回退到默认，避免向后端发送越界参数。
import {
  BOOK_SORT_FIELDS,
  DEFAULT_BOOK_QUERY,
  DEFAULT_BOOK_SORT,
  type BookQuery,
} from './api';
import type { BookSearchField } from '../../api/types';

const SEARCH_FIELDS: BookSearchField[] = ['all', 'title', 'author', 'isbn'];
const PAGE_SIZES = [10, 20, 50, 100];

function parsePositiveInt(raw: string | null, fallback: number): number {
  if (raw === null) return fallback;
  const value = Number(raw);
  return Number.isInteger(value) && value >= 0 ? value : fallback;
}

function parseSort(raw: string | null): string {
  if (!raw) return DEFAULT_BOOK_SORT;
  const [field, dir] = raw.split(',');
  if (!field || !dir) return DEFAULT_BOOK_SORT;
  if (!(BOOK_SORT_FIELDS as readonly string[]).includes(field)) return DEFAULT_BOOK_SORT;
  if (dir !== 'asc' && dir !== 'desc') return DEFAULT_BOOK_SORT;
  return `${field},${dir}`;
}

export function parseBookQuery(params: URLSearchParams): BookQuery {
  const fieldRaw = params.get('field');
  const field = SEARCH_FIELDS.includes(fieldRaw as BookSearchField)
    ? (fieldRaw as BookSearchField)
    : 'all';
  const categoryIdRaw = params.get('categoryId');
  const categoryIdNum = categoryIdRaw === null ? NaN : Number(categoryIdRaw);
  const sizeRaw = parsePositiveInt(params.get('size'), DEFAULT_BOOK_QUERY.size);
  return {
    q: (params.get('q') ?? '').slice(0, 100),
    field,
    categoryId:
      Number.isInteger(categoryIdNum) && categoryIdNum > 0 ? categoryIdNum : undefined,
    availableOnly: params.get('availableOnly') === 'true',
    page: parsePositiveInt(params.get('page'), 0),
    size: PAGE_SIZES.includes(sizeRaw) ? sizeRaw : DEFAULT_BOOK_QUERY.size,
    sort: parseSort(params.get('sort')),
  };
}

export function bookQueryToParams(query: BookQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (query.q.trim() !== '') params.set('q', query.q.trim());
  if (query.field !== 'all') params.set('field', query.field);
  if (query.categoryId !== undefined) params.set('categoryId', String(query.categoryId));
  if (query.availableOnly) params.set('availableOnly', 'true');
  if (query.page > 0) params.set('page', String(query.page));
  if (query.size !== DEFAULT_BOOK_QUERY.size) params.set('size', String(query.size));
  if (query.sort !== DEFAULT_BOOK_SORT) params.set('sort', query.sort);
  return params;
}