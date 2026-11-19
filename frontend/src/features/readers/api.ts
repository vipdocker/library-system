// readers feature 接口函数。契约来源：docs/design/solution-design.md 第 8.3 / 8.4 节（design-v0.1）。
// 共享 client / types 只读（T02 产出）；本文件只负责读者相关路径、参数与返回类型。
// 读者详情页的借阅列表来自 GET /api/loans?readerId（第 8.4 节借阅接口，T09 交接包指定）。
import { apiDelete, apiGet, apiPost, apiPut } from '../../api/client';
import type {
  Loan,
  LoanStatusFilter,
  Page,
  Reader,
  ReaderCreateInput,
  ReaderStatus,
  ReaderUpdateInput,
  ResetPasswordInput,
} from '../../api/types';

/** 读者列表排序白名单（第 8.4 节）：name、cardNo、createdAt。不传 sort 时用服务端默认。 */
export const READER_SORT_FIELDS = ['name', 'cardNo', 'createdAt'] as const;
export type ReaderSortField = (typeof READER_SORT_FIELDS)[number];

/** 借阅列表排序白名单（第 8.4 节）：borrowDate、dueDate；默认 borrowDate,desc（服务端）。 */
export const LOAN_SORT_FIELDS = ['borrowDate', 'dueDate'] as const;
export type LoanSortField = (typeof LOAN_SORT_FIELDS)[number];

export interface ReaderQuery {
  /** 姓名子串 / 卡号精确 / 用户名精确（第 8.4 节） */
  q: string;
  status?: ReaderStatus;
  /** 从 0 起 */
  page: number;
  size: number;
  /** `${field},${'asc'|'desc'}`，字段必须在 READER_SORT_FIELDS 内 */
  sort?: string;
}

export interface ReaderLoanQuery {
  status?: LoanStatusFilter;
  page: number;
  size: number;
  sort?: string;
}

/** readers 的 Query Key 约定：统一前缀 'readers'，mutation 后按前缀失效。 */
export const readersKeys = {
  all: ['readers'] as const,
  list: (query: ReaderQuery) => [...readersKeys.all, 'list', query] as const,
  detail: (id: number) => [...readersKeys.all, 'detail', id] as const,
  me: () => [...readersKeys.all, 'me'] as const,
  loans: (readerId: number, query: ReaderLoanQuery) =>
    [...readersKeys.all, 'loans', readerId, query] as const,
};

/** GET /api/readers：分页检索（AC-RDR-04）。 */
export function listReaders(query: ReaderQuery): Promise<Page<Reader>> {
  return apiGet<Page<Reader>>('/api/readers', {
    q: query.q || undefined,
    status: query.status,
    page: query.page,
    size: query.size,
    sort: query.sort,
  });
}

/** GET /api/readers/{id}（ADMIN）。 */
export function getReader(id: number): Promise<Reader> {
  return apiGet<Reader>(`/api/readers/${id}`);
}

/** POST /api/readers：同时创建 READER 账号，卡号由系统生成（AC-RDR-01）。 */
export function createReader(input: ReaderCreateInput): Promise<Reader> {
  return apiPost<Reader>('/api/readers', input);
}

/** PUT /api/readers/{id}：资料编辑；status 变更时账号 enabled 同步（AC-RDR-02）。 */
export function updateReader(id: number, input: ReaderUpdateInput): Promise<Reader> {
  return apiPut<Reader>(`/api/readers/${id}`, input);
}

/** PUT /api/readers/{id}/password：管理员重置口令，204（AC-RDR-05）。 */
export function resetReaderPassword(id: number, input: ResetPasswordInput): Promise<void> {
  return apiPut<void>(`/api/readers/${id}/password`, input);
}

/** DELETE /api/readers/{id}：204，账号一并删除；有借阅历史时 409（AC-RDR-03）。 */
export function deleteReader(id: number): Promise<void> {
  return apiDelete<void>(`/api/readers/${id}`);
}

/** GET /api/readers/me：READER 本人档案（AC-RDR-06）。 */
export function getMyReaderProfile(): Promise<Reader> {
  return apiGet<Reader>('/api/readers/me');
}

/** GET /api/loans?readerId：某读者的借阅列表（ADMIN，读者详情页用）。 */
export function listReaderLoans(readerId: number, query: ReaderLoanQuery): Promise<Page<Loan>> {
  return apiGet<Page<Loan>>('/api/loans', {
    readerId,
    status: query.status,
    page: query.page,
    size: query.size,
    sort: query.sort,
  });
}