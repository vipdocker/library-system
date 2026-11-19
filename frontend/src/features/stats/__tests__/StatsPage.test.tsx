// AC-STAT-02/03（UI 部分）/ AC-UI-01 / AC-UI-06：统计页四种状态、粒度切换请求参数、
// 日期区间前端校验、热门图书 limit、条件写入 URL。
import { describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import dayjs from 'dayjs';
import { useSearchParams } from 'react-router';
import StatsPage from '../pages/StatsPage';
import { makeSeries, makeZeroSeries, topBooksFixture } from './fixtures';
import { callsTo, errorResponse, jsonResponse, mockFetch, pendingForever, renderPage } from './testUtils';

function mockStatsApi({
  loans = () => jsonResponse(makeSeries(30)),
  top = () => jsonResponse(topBooksFixture),
}: {
  loans?: () => Response | Promise<Response>;
  top?: () => Response | Promise<Response>;
} = {}) {
  return mockFetch((url) => {
    if (url.pathname === '/api/stats/loans') return loans();
    if (url.pathname === '/api/stats/top-books') return top();
    throw new Error(`unexpected request: ${url.pathname}`);
  });
}

function LocationProbe() {
  const [params] = useSearchParams();
  return <div data-testid="location-probe">{params.toString()}</div>;
}

describe('AC-UI-01 StatsPage 四种状态', () => {
  it('加载态：首次加载显示统计与热门图书加载提示', async () => {
    mockStatsApi({ loans: () => pendingForever(), top: () => pendingForever() });
    renderPage(<StatsPage />, { route: '/admin/stats' });
    expect(await screen.findByText('统计加载中…')).toBeInTheDocument();
    expect(screen.getByText('热门图书加载中…')).toBeInTheDocument();
  });

  it('错误态：503 两个区块都显示“系统繁忙”，重试后恢复', async () => {
    let loanCalls = 0;
    const spy = mockStatsApi({
      loans: () => {
        loanCalls += 1;
        return loanCalls === 1 ? errorResponse(503, 'DB_BUSY') : jsonResponse(makeSeries(30));
      },
      top: () => errorResponse(503, 'DB_BUSY'),
    });
    renderPage(<StatsPage />, { route: '/admin/stats' });
    await waitFor(() => {
      expect(screen.getAllByText('系统繁忙，请稍后再试')).toHaveLength(2);
    });
    const retryButtons = screen.getAllByRole('button', { name: /重\s*试/ });
    expect(retryButtons).toHaveLength(2);
    fireEvent.click(retryButtons[0]);
    expect(await screen.findByRole('img', { name: /借还趋势图/ })).toBeInTheDocument();
    expect(callsTo(spy, '/api/stats/loans').length).toBeGreaterThanOrEqual(2);
  });

  it('空态：区间内全 0 序列与空热门榜显示引导文案', async () => {
    mockStatsApi({ loans: () => jsonResponse(makeZeroSeries(30)), top: () => jsonResponse([]) });
    renderPage(<StatsPage />, { route: '/admin/stats' });
    expect(await screen.findByText('该区间内没有借阅记录，换一个日期区间试试')).toBeInTheDocument();
    expect(screen.getByText('该区间内没有借阅记录，暂无热门图书')).toBeInTheDocument();
  });

  it('正常态：渲染趋势图、汇总与热门图书榜', async () => {
    mockStatsApi();
    renderPage(<StatsPage />, { route: '/admin/stats' });
    expect(await screen.findByRole('img', { name: /借还趋势图/ })).toBeInTheDocument();
    expect(screen.getByText('借出合计')).toBeInTheDocument();
    expect(screen.getByText('归还合计')).toBeInTheDocument();
    for (const book of topBooksFixture) {
      expect(screen.getByText(book.title)).toBeInTheDocument();
    }
    expect(screen.getByText('9 次')).toBeInTheDocument();
  });
});

describe('AC-STAT-02 StatsPage 粒度与区间', () => {
  it('默认请求：最近 30 天、granularity=DAY', async () => {
    const spy = mockStatsApi();
    renderPage(<StatsPage />, { route: '/admin/stats' });
    await screen.findByRole('img', { name: /借还趋势图/ });
    const params = callsTo(spy, '/api/stats/loans')[0].searchParams;
    expect(params.get('granularity')).toBe('DAY');
    expect(params.get('from')).toBe(dayjs().subtract(29, 'day').format('YYYY-MM-DD'));
    expect(params.get('to')).toBe(dayjs().format('YYYY-MM-DD'));
  });

  it('切换到“按月”后请求 granularity=MONTH，并写入 URL', async () => {
    const spy = mockStatsApi();
    renderPage(
      <>
        <StatsPage />
        <LocationProbe />
      </>,
      { route: '/admin/stats' },
    );
    await screen.findByRole('img', { name: /借还趋势图/ });
    fireEvent.click(screen.getByText('按月'));
    await waitFor(() => {
      const calls = callsTo(spy, '/api/stats/loans');
      expect(calls[calls.length - 1].searchParams.get('granularity')).toBe('MONTH');
    });
    expect(screen.getByTestId('location-probe').textContent).toContain('granularity=MONTH');
  });

  it('URL 中 from 晚于 to：显示中文提示且不发起统计请求', async () => {
    const spy = mockStatsApi();
    renderPage(<StatsPage />, { route: '/admin/stats?from=2026-11-10&to=2026-11-01' });
    expect(await screen.findByText('开始日期不能晚于结束日期')).toBeInTheDocument();
    expect(callsTo(spy, '/api/stats/loans')).toHaveLength(0);
    expect(callsTo(spy, '/api/stats/top-books')).toHaveLength(0);
  });

  it('URL 中区间跨度超过 366 天：显示中文提示且不发起统计请求', async () => {
    const spy = mockStatsApi();
    renderPage(<StatsPage />, { route: '/admin/stats?from=2025-01-01&to=2026-11-01' });
    expect(await screen.findByText('统计区间跨度不能超过 366 天')).toBeInTheDocument();
    expect(callsTo(spy, '/api/stats/loans')).toHaveLength(0);
  });

  it('跨度恰为 366 天的区间合法，正常发起请求', async () => {
    const spy = mockStatsApi();
    renderPage(<StatsPage />, { route: '/admin/stats?from=2025-11-01&to=2026-11-02' });
    await screen.findByRole('img', { name: /借还趋势图/ });
    const params = callsTo(spy, '/api/stats/loans')[0].searchParams;
    expect(params.get('from')).toBe('2025-11-01');
    expect(params.get('to')).toBe('2026-11-02');
    expect(screen.queryByText('日期区间不合法')).not.toBeInTheDocument();
  });

  it('通过日期选择器修改区间后按新区间请求', async () => {
    const spy = mockStatsApi();
    renderPage(<StatsPage />, { route: '/admin/stats' });
    await screen.findByRole('img', { name: /借还趋势图/ });

    const start = screen.getByPlaceholderText('开始日期');
    fireEvent.mouseDown(start);
    fireEvent.click(start);
    fireEvent.change(start, { target: { value: '2026-11-01' } });
    fireEvent.keyDown(start, { key: 'Enter', code: 'Enter', charCode: 13 });
    const end = screen.getByPlaceholderText('结束日期');
    fireEvent.change(end, { target: { value: '2026-11-05' } });
    fireEvent.keyDown(end, { key: 'Enter', code: 'Enter', charCode: 13 });

    await waitFor(() => {
      const calls = callsTo(spy, '/api/stats/loans');
      const params = calls[calls.length - 1].searchParams;
      expect(params.get('from')).toBe('2026-11-01');
      expect(params.get('to')).toBe('2026-11-05');
    });
  });

  it('服务端 400 VALIDATION_FAILED：显示 errors.ts 中文文案与重试', async () => {
    mockStatsApi({ loans: () => errorResponse(400, 'VALIDATION_FAILED') });
    renderPage(<StatsPage />, { route: '/admin/stats' });
    expect(await screen.findByText('请求参数不合法，请检查后重试')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /重\s*试/ }).length).toBeGreaterThanOrEqual(1);
  });
});

describe('AC-STAT-03 StatsPage 热门图书', () => {
  it('默认 limit=10；切换为“前 20 本”后请求 limit=20', async () => {
    const spy = mockStatsApi();
    const user = userEvent.setup();
    renderPage(<StatsPage />, { route: '/admin/stats' });
    await screen.findByText(topBooksFixture[0].title);
    expect(callsTo(spy, '/api/stats/top-books')[0].searchParams.get('limit')).toBe('10');

    await user.click(screen.getByRole('combobox', { name: '热门图书数量' }));
    await user.click(await screen.findByTitle('前 20 本'));
    await waitFor(() => {
      const calls = callsTo(spy, '/api/stats/top-books');
      expect(calls[calls.length - 1].searchParams.get('limit')).toBe('20');
    });
  });

  it('按服务端返回顺序展示排名（借阅次数降序）', async () => {
    mockStatsApi();
    renderPage(<StatsPage />, { route: '/admin/stats' });
    await screen.findByText(topBooksFixture[0].title);
    const items = screen.getAllByRole('listitem');
    const titles = items.map((item) => item.textContent ?? '');
    expect(titles[0]).toContain('深入理解计算机系统');
    expect(titles[1]).toContain('百年孤独');
    expect(titles[2]).toContain('算法导论');
  });
});

describe('AC-UI-06 StatsPage 图表/数据表切换', () => {
  it('切换到“数据表”后以表格展示周期数据', async () => {
    mockStatsApi();
    renderPage(<StatsPage />, { route: '/admin/stats' });
    await screen.findByRole('img', { name: /借还趋势图/ });
    fireEvent.click(screen.getByText('数据表'));
    await waitFor(() => {
      expect(screen.queryByRole('img', { name: /借还趋势图/ })).not.toBeInTheDocument();
    });
    // scroll.y 时 antd 将表头/表体拆成两个 table 元素
    expect(screen.getAllByRole('table').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(dayjs().format('YYYY-MM-DD'))).toBeInTheDocument();
  });
});