// 统计与报表接口函数（设计 8.4 节 stats 部分，仅 ADMIN）。
// 共享 client / types 只读；本文件是 stats feature 唯一的接口层。
import { apiGet } from '../../api/client';
import type {
  LoanSeriesPoint,
  OverdueItem,
  Page,
  StatsGranularity,
  StatsOverview,
  TopBook,
} from '../../api/types';

/** GET /api/stats/overview → StatsOverview */
export function fetchStatsOverview(signal?: AbortSignal): Promise<StatsOverview> {
  return apiGet<StatsOverview>('/api/stats/overview', undefined, { signal });
}

export interface LoanSeriesQuery {
  /** YYYY-MM-DD，含两端 */
  from: string;
  to: string;
  granularity: StatsGranularity;
}

/** GET /api/stats/loans?from&to&granularity → LoanSeriesPoint[]（无数据周期由服务端补 0） */
export function fetchLoanSeries(query: LoanSeriesQuery, signal?: AbortSignal): Promise<LoanSeriesPoint[]> {
  return apiGet<LoanSeriesPoint[]>(
    '/api/stats/loans',
    { from: query.from, to: query.to, granularity: query.granularity },
    { signal },
  );
}

export interface TopBooksQuery {
  from: string;
  to: string;
  /** 1..50，默认 10 */
  limit: number;
}

/** GET /api/stats/top-books?from&to&limit → TopBook[]（服务端按 borrowCount 降序、title 升序） */
export function fetchTopBooks(query: TopBooksQuery, signal?: AbortSignal): Promise<TopBook[]> {
  return apiGet<TopBook[]>(
    '/api/stats/top-books',
    { from: query.from, to: query.to, limit: query.limit },
    { signal },
  );
}

export interface OverdueReportQuery {
  page: number;
  size: number;
  /** 白名单 overdueDays、dueDate；默认 overdueDays,desc */
  sort: string;
}

/** GET /api/reports/overdue?page&size&sort → Page<OverdueItem> */
export function fetchOverdueReport(query: OverdueReportQuery, signal?: AbortSignal): Promise<Page<OverdueItem>> {
  return apiGet<Page<OverdueItem>>(
    '/api/reports/overdue',
    { page: query.page, size: query.size, sort: query.sort },
    { signal },
  );
}