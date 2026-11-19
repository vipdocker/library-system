// AC-STAT-01..04（接口层）：请求方法、路径、参数与设计 8.4 节一致；错误按 ApiError 传播。
import { describe, expect, it } from 'vitest';
import { ApiError } from '../../../api/client';
import { fetchLoanSeries, fetchOverdueReport, fetchStatsOverview, fetchTopBooks } from '../api';
import { makeOverduePage, makeSeries, overviewFixture, topBooksFixture } from './fixtures';
import { errorResponse, jsonResponse, mockFetch } from './testUtils';

describe('AC-STAT-01 fetchStatsOverview', () => {
  it('GET /api/stats/overview，无参数，返回 StatsOverview', async () => {
    const spy = mockFetch(() => jsonResponse(overviewFixture));
    const data = await fetchStatsOverview();
    expect(data).toEqual(overviewFixture);
    expect(spy).toHaveBeenCalledTimes(1);
    const [input, init] = spy.mock.calls[0];
    expect(String(input)).toBe('/api/stats/overview');
    expect(init?.method).toBe('GET');
  });
});

describe('AC-STAT-02 fetchLoanSeries', () => {
  it('GET /api/stats/loans?from&to&granularity，参数原样传递', async () => {
    const series = makeSeries(3);
    const spy = mockFetch(() => jsonResponse(series));
    const data = await fetchLoanSeries({ from: '2026-11-01', to: '2026-11-03', granularity: 'DAY' });
    expect(data).toEqual(series);
    const url = new URL(String(spy.mock.calls[0][0]), 'http://localhost');
    expect(url.pathname).toBe('/api/stats/loans');
    expect(url.searchParams.get('from')).toBe('2026-11-01');
    expect(url.searchParams.get('to')).toBe('2026-11-03');
    expect(url.searchParams.get('granularity')).toBe('DAY');
  });

  it('MONTH 粒度传递 granularity=MONTH', async () => {
    const spy = mockFetch(() => jsonResponse([]));
    await fetchLoanSeries({ from: '2026-01-01', to: '2026-11-01', granularity: 'MONTH' });
    const url = new URL(String(spy.mock.calls[0][0]), 'http://localhost');
    expect(url.searchParams.get('granularity')).toBe('MONTH');
  });

  it('服务端 400 VALIDATION_FAILED 解析为 ApiError（code/status 保留）', async () => {
    mockFetch(() => errorResponse(400, 'VALIDATION_FAILED'));
    const promise = fetchLoanSeries({ from: '2026-11-10', to: '2026-11-01', granularity: 'DAY' });
    await expect(promise).rejects.toBeInstanceOf(ApiError);
    await expect(promise).rejects.toMatchObject({ status: 400, code: 'VALIDATION_FAILED' });
  });
});

describe('AC-STAT-03 fetchTopBooks', () => {
  it('GET /api/stats/top-books?from&to&limit', async () => {
    const spy = mockFetch(() => jsonResponse(topBooksFixture));
    const data = await fetchTopBooks({ from: '2026-10-01', to: '2026-10-31', limit: 20 });
    expect(data).toEqual(topBooksFixture);
    const url = new URL(String(spy.mock.calls[0][0]), 'http://localhost');
    expect(url.pathname).toBe('/api/stats/top-books');
    expect(url.searchParams.get('from')).toBe('2026-10-01');
    expect(url.searchParams.get('to')).toBe('2026-10-31');
    expect(url.searchParams.get('limit')).toBe('20');
  });
});

describe('AC-STAT-04 fetchOverdueReport', () => {
  it('GET /api/reports/overdue?page&size&sort', async () => {
    const spy = mockFetch(() => jsonResponse(makeOverduePage(1, 20)));
    const data = await fetchOverdueReport({ page: 1, size: 20, sort: 'overdueDays,desc' });
    expect(data.items[0].loanId).toBe(121);
    const url = new URL(String(spy.mock.calls[0][0]), 'http://localhost');
    expect(url.pathname).toBe('/api/reports/overdue');
    expect(url.searchParams.get('page')).toBe('1');
    expect(url.searchParams.get('size')).toBe('20');
    expect(url.searchParams.get('sort')).toBe('overdueDays,desc');
  });

  it('403 FORBIDDEN 解析为 ApiError，不清除登录态（由共享 client 保证）', async () => {
    mockFetch(() => errorResponse(403, 'FORBIDDEN'));
    await expect(fetchOverdueReport({ page: 0, size: 20, sort: 'overdueDays,desc' })).rejects.toMatchObject({
      status: 403,
      code: 'FORBIDDEN',
    });
  });
});