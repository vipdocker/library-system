// AC-STAT-01（UI 部分）/ AC-UI-01 / AC-UI-06：仪表盘四种状态 + 7 天趋势请求参数 + 逾期快捷入口。
import { describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import dayjs from 'dayjs';
import DashboardPage from '../pages/DashboardPage';
import { makeSeries, makeZeroSeries, overviewFixture, zeroOverviewFixture } from './fixtures';
import { callsTo, errorResponse, jsonResponse, mockFetch, pendingForever, renderPage } from './testUtils';

function mockDashboardApi({
  overview = () => jsonResponse(overviewFixture),
  loans = () => jsonResponse(makeSeries(7)),
}: {
  overview?: () => Response | Promise<Response>;
  loans?: () => Response | Promise<Response>;
} = {}) {
  return mockFetch((url) => {
    if (url.pathname === '/api/stats/overview') return overview();
    if (url.pathname === '/api/stats/loans') return loans();
    throw new Error(`unexpected request: ${url.pathname}`);
  });
}

describe('AC-UI-01 DashboardPage 四种状态', () => {
  it('加载态：首次加载显示骨架屏与趋势加载提示', async () => {
    mockDashboardApi({ overview: () => pendingForever(), loans: () => pendingForever() });
    renderPage(<DashboardPage />);
    expect(screen.getByTestId('dashboard-loading')).toBeInTheDocument();
    expect(await screen.findByText('趋势加载中…')).toBeInTheDocument();
  });

  it('错误态：503 显示“系统繁忙”，点击“重试”后 refetch 成功渲染', async () => {
    let overviewCalls = 0;
    const spy = mockDashboardApi({
      overview: () => {
        overviewCalls += 1;
        return overviewCalls === 1 ? errorResponse(503, 'DB_BUSY') : jsonResponse(overviewFixture);
      },
    });
    renderPage(<DashboardPage />);
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('图书种类')).toBeInTheDocument();
    expect(callsTo(spy, '/api/stats/overview')).toHaveLength(2);
  });

  it('空态：最近 7 天全为 0 时显示引导文案与柜台入口', async () => {
    mockDashboardApi({
      overview: () => jsonResponse(zeroOverviewFixture),
      loans: () => jsonResponse(makeZeroSeries(7)),
    });
    renderPage(<DashboardPage />);
    expect(await screen.findByText('最近 7 天还没有借还记录')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '去流通柜台办理借阅' })).toHaveAttribute(
      'href',
      '/admin/circulation',
    );
  });

  it('正常态：渲染全部概览计数与趋势条形图', async () => {
    mockDashboardApi();
    renderPage(<DashboardPage />);
    expect(await screen.findByText('图书种类')).toBeInTheDocument();

    const expectations: Array<[string, number]> = [
      ['图书种类', overviewFixture.totalBooks],
      ['副本总数', overviewFixture.totalCopies],
      ['可借副本', overviewFixture.availableCopies],
      ['借出副本', overviewFixture.borrowedCopies],
      ['不可用副本', overviewFixture.unavailableCopies],
      ['读者总数', overviewFixture.totalReaders],
      ['活跃读者', overviewFixture.activeReaders],
      ['当前在借', overviewFixture.activeLoans],
      ['逾期未还', overviewFixture.overdueLoans],
      ['今日借出', overviewFixture.borrowsToday],
      ['今日归还', overviewFixture.returnsToday],
    ];
    for (const [label, value] of expectations) {
      const card = screen.getByText(label).closest('.dash-card');
      expect(card).not.toBeNull();
      expect(within(card as HTMLElement).getByText(String(value))).toBeInTheDocument();
    }
    expect(screen.getByRole('img', { name: /借还趋势图/ })).toBeInTheDocument();
  });

  it('趋势单独失败：概览仍显示，趋势区显示错误与重试', async () => {
    let loanCalls = 0;
    mockDashboardApi({
      loans: () => {
        loanCalls += 1;
        return loanCalls === 1 ? errorResponse(500, 'INTERNAL_ERROR') : jsonResponse(makeSeries(7));
      },
    });
    renderPage(<DashboardPage />);
    expect(await screen.findByText('图书种类')).toBeInTheDocument();
    expect(screen.getByText('系统内部错误，请稍后再试')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByRole('img', { name: /借还趋势图/ })).toBeInTheDocument();
  });
});

describe('AC-STAT-01 / AC-UI-06 DashboardPage 请求与入口', () => {
  it('趋势请求为最近 7 天、granularity=DAY；逾期卡片链接到逾期报表', async () => {
    const spy = mockDashboardApi();
    renderPage(<DashboardPage />);
    await screen.findByText('图书种类');
    await waitFor(() => {
      const calls = callsTo(spy, '/api/stats/loans');
      expect(calls.length).toBeGreaterThan(0);
      const params = calls[0].searchParams;
      expect(params.get('granularity')).toBe('DAY');
      expect(params.get('from')).toBe(dayjs().subtract(6, 'day').format('YYYY-MM-DD'));
      expect(params.get('to')).toBe(dayjs().format('YYYY-MM-DD'));
    });
    expect(screen.getByRole('link', { name: '查看逾期报表' })).toHaveAttribute(
      'href',
      '/admin/reports/overdue',
    );
  });
});