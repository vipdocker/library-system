// 全部 DTO 与请求/响应类型。契约来源：docs/design/solution-design.md 第 8.3 / 8.4 节（design-v0.1）。
// 本文件由 T02 一次写全，之后对其他任务只读（T13 除外）。
// 约定：日期为 YYYY-MM-DD 字符串；时间戳为 ISO-8601 UTC 字符串；空值为 null，不省略字段。

export type Role = 'ADMIN' | 'READER';

export interface UserInfo {
  id: number;
  username: string;
  role: Role;
  displayName: string;
  readerId: number | null;
  cardNo: string | null;
}

export interface Category {
  id: number;
  name: string;
  description: string | null;
  bookCount: number;
}

export interface BookSummary {
  id: number;
  isbn: string;
  title: string;
  author: string;
  publisher: string | null;
  publishYear: number | null;
  category: { id: number; name: string } | null;
  totalCopies: number;
  availableCopies: number;
}

export interface BookDetail extends BookSummary {
  description: string | null;
  createdAt: string;
  updatedAt: string;
}

export type CopyStatus = 'AVAILABLE' | 'BORROWED' | 'UNAVAILABLE';

export interface Copy {
  id: number;
  bookId: number;
  barcode: string;
  status: CopyStatus;
  createdAt: string;
}

export type ReaderStatus = 'ACTIVE' | 'DISABLED';

export interface Reader {
  id: number;
  cardNo: string;
  name: string;
  phone: string | null;
  email: string | null;
  status: ReaderStatus;
  username: string;
  activeLoanCount: number;
  overdueLoanCount: number;
  createdAt: string;
}

export type LoanStatus = 'ACTIVE' | 'OVERDUE' | 'RETURNED';

export interface Loan {
  id: number;
  reader: { id: number; cardNo: string; name: string };
  copy: { id: number; barcode: string };
  book: { id: number; title: string; author: string; isbn: string };
  borrowDate: string;
  dueDate: string;
  returnDate: string | null;
  renewCount: number;
  maxRenewals: number;
  status: LoanStatus;
  overdueDays: number;
  /** 当前调用者在当前时间点能否续借（仅 UI 提示，服务端仍会完整校验） */
  renewable: boolean;
}

export interface StatsOverview {
  totalBooks: number;
  totalCopies: number;
  availableCopies: number;
  borrowedCopies: number;
  unavailableCopies: number;
  totalReaders: number;
  activeReaders: number;
  activeLoans: number;
  overdueLoans: number;
  borrowsToday: number;
  returnsToday: number;
}

export interface LoanSeriesPoint {
  /** YYYY-MM-DD（DAY）或 YYYY-MM（MONTH） */
  period: string;
  borrowCount: number;
  returnCount: number;
}

export interface TopBook {
  bookId: number;
  title: string;
  author: string;
  isbn: string;
  borrowCount: number;
}

export interface OverdueItem {
  loanId: number;
  reader: { id: number; cardNo: string; name: string; phone: string | null };
  book: { id: number; title: string; isbn: string };
  copyBarcode: string;
  borrowDate: string;
  dueDate: string;
  overdueDays: number;
}

/** 分页响应（第 8.1 节） */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface FieldError {
  field: string;
  message: string;
}

/** 统一错误响应（第 8.1 节） */
export interface ErrorResponse {
  code: string;
  message: string;
  status: number;
  path: string;
  timestamp: string;
  fieldErrors: FieldError[];
  details: Record<string, unknown>;
}

// ---------- 请求体类型（第 8.4 节） ----------

export interface LoginRequest {
  username: string;
  password: string;
}

export interface ChangePasswordRequest {
  oldPassword: string;
  newPassword: string;
}

export interface CategoryInput {
  name: string;
  description?: string | null;
}

export interface BookInput {
  isbn: string;
  title: string;
  author: string;
  publisher?: string | null;
  publishYear?: number | null;
  categoryId?: number | null;
  description?: string | null;
}

export interface BookCreateInput extends BookInput {
  /** 0..50，默认 1 */
  initialCopies?: number;
}

export interface CopyBatchInput {
  /** 1..50 */
  count: number;
}

export interface CopyStatusInput {
  status: 'AVAILABLE' | 'UNAVAILABLE';
}

export interface ReaderCreateInput {
  name: string;
  phone?: string | null;
  email?: string | null;
  username: string;
  initialPassword: string;
}

export interface ReaderUpdateInput {
  name: string;
  phone?: string | null;
  email?: string | null;
  status: ReaderStatus;
}

export interface ResetPasswordInput {
  newPassword: string;
}

export interface BorrowInput {
  readerCardNo: string;
  copyBarcode: string;
}

export interface ReturnByBarcodeInput {
  copyBarcode: string;
}

// ---------- 查询参数枚举 ----------

export type BookSearchField = 'all' | 'title' | 'author' | 'isbn';
/** CURRENT = ACTIVE + OVERDUE */
export type LoanStatusFilter = LoanStatus | 'CURRENT';
export type StatsGranularity = 'DAY' | 'MONTH';