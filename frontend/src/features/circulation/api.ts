// circulation feature 接口函数。契约来源：docs/design/solution-design.md 第 8.3 / 8.4 节（design-v0.1）。
// 共享 client / types / errors 只读（T02 产出）；本文件只负责借阅相关路径、参数与返回类型。
// 后端借阅接口由 T04–T07 实现中，本 feature 的测试一律 mock 本文件。
import { ApiError, apiGet, apiPost } from '../../api/client';
import { errorMessage } from '../../api/errors';
import type {
  BorrowInput,
  Loan,
  LoanStatusFilter,
  Page,
  Reader,
  ReturnByBarcodeInput,
} from '../../api/types';

/** 借阅排序白名单（第 8.4 节）：borrowDate、dueDate；默认 borrowDate,desc。 */
export const LOAN_SORT_FIELDS = ['borrowDate', 'dueDate'] as const;
export type LoanSortField = (typeof LOAN_SORT_FIELDS)[number];
export const DEFAULT_LOAN_SORT = 'borrowDate,desc';

export interface LoanQuery {
  readerId?: number;
  /** CURRENT = ACTIVE + OVERDUE；不传表示全部 */
  status?: LoanStatusFilter;
  copyBarcode?: string;
  /** 书名子串 */
  q?: string;
  /** 从 0 起 */
  page: number;
  size: number;
  /** `${field},${'asc'|'desc'}`，字段必须在白名单内 */
  sort: string;
}

export const DEFAULT_LOAN_QUERY: LoanQuery = {
  readerId: undefined,
  status: undefined,
  copyBarcode: '',
  q: '',
  page: 0,
  size: 20,
  sort: DEFAULT_LOAN_SORT,
};

/** GET /api/loans/me 只接受 status / page / size（第 8.4 节）。 */
export interface MyLoanQuery {
  status: LoanStatusFilter;
  page: number;
  size: number;
}

/** circulation 的 Query Key 约定：统一前缀 'circulation'，借/还/续借后按前缀失效。 */
export const circulationKeys = {
  all: ['circulation'] as const,
  loans: (query: LoanQuery) => [...circulationKeys.all, 'loans', query] as const,
  myLoans: (query: MyLoanQuery) => [...circulationKeys.all, 'my-loans', query] as const,
  readerLoans: (readerId: number) => [...circulationKeys.all, 'reader-loans', readerId] as const,
  readerSearch: (q: string) => [...circulationKeys.all, 'reader-search', q] as const,
};

// ---------- 借书 / 还书 / 续借（第 8.4 节借阅接口） ----------

export function borrowLoan(input: BorrowInput): Promise<Loan> {
  return apiPost<Loan>('/api/loans', input);
}

export function returnLoan(loanId: number): Promise<Loan> {
  return apiPost<Loan>(`/api/loans/${loanId}/return`);
}

export function returnLoanByBarcode(input: ReturnByBarcodeInput): Promise<Loan> {
  return apiPost<Loan>('/api/loans/return-by-barcode', input);
}

export function renewLoan(loanId: number): Promise<Loan> {
  return apiPost<Loan>(`/api/loans/${loanId}/renew`);
}

// ---------- 列表 ----------

export function listLoans(query: LoanQuery): Promise<Page<Loan>> {
  return apiGet<Page<Loan>>('/api/loans', {
    readerId: query.readerId,
    status: query.status,
    copyBarcode: query.copyBarcode?.trim() || undefined,
    q: query.q?.trim() || undefined,
    page: query.page,
    size: query.size,
    sort: query.sort,
  });
}

export function listMyLoans(query: MyLoanQuery): Promise<Page<Loan>> {
  return apiGet<Page<Loan>>('/api/loans/me', {
    status: query.status,
    page: query.page,
    size: query.size,
  });
}

// ---------- 读者查询（第 8.4 节读者接口，供柜台按卡号定位读者、借阅列表按读者过滤） ----------

export function searchReaders(q: string): Promise<Page<Reader>> {
  return apiGet<Page<Reader>>('/api/readers', { q: q.trim(), page: 0, size: 20 });
}

/**
 * 按卡号精确定位读者：GET /api/readers?q=<cardNo>（q 对卡号是精确匹配，但也可能命中
 * 姓名子串 / 用户名精确的其他读者，因此在结果中再按 cardNo 精确筛选）。
 * 找不到时抛出 404 READER_NOT_FOUND，与借书接口的错误语义一致。
 */
export async function findReaderByCardNo(cardNo: string): Promise<Reader> {
  const trimmed = cardNo.trim();
  const page = await apiGet<Page<Reader>>('/api/readers', { q: trimmed, page: 0, size: 20 });
  const reader = page.items.find((item) => item.cardNo === trimmed);
  if (!reader) {
    throw new ApiError(404, 'READER_NOT_FOUND', errorMessage('READER_NOT_FOUND'));
  }
  return reader;
}