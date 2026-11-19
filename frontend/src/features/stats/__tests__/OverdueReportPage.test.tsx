// AC-STAT-04（UI 部分）/ AC-UI-01 / AC-UI-06：逾期报表四种状态、默认请求参数、分页与排序。
import { describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import OverdueReportPage from '../pages/OverdueReportPage';
import { makeEmptyOverduePage, makeOverduePage, overdueItemFixture } from './fixtures';
import { callsTo, errorResponse, jsonResponse, mockFetch, pendingForever, renderPage } from './testUtils';

function mockOverdueApi(handler?: (url: URL) => Response | Promise<Response>) {
  return mockFetch((url) => {
    if (url.pathname === '/api/reports/overdue') {
      if (handler) return handler(url);
      const page = Number(url.searchParams.get('page') ?? '0');
      const size = Number(url.searchParams.get('size') ?? '20');
      return jsonResponse(makeOverduePage(page, size));
    }
    throw new Error(`unexpected request: ${url.pathname}`);
  });
}

describe('AC-UI-01 OverdueReportPage 四种状态', () => {
  it('加载态：首次加载显示加载提示', async () => {
    mockOverdueApi(() => pendingForever());
    renderPage(<OverdueReportPage />);
    expect(await screen.findByText('报表加载中…')).toBeInTheDocument();
  });

  it('错误态：503 显示“系统繁忙”，点击“重试”后 refetch 成功渲染', async () => {
    let calls = 0;
    const spy = mockOverdueApi(() => {
      calls += 1;
      return calls === 1 ? errorResponse(503, 'DB_BUSY') : jsonResponse(makeOverduePage(0, 20));
    });
    renderPage(<OverdueReportPage />);
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('张三')).toBeInTheDocument();
    expect(callsTo(spy, '/api/reports/overdue')).toHaveLength(2);
  });

  it('错误态：403 显示“无权限执行该操作”', async () => {
    mockOverdueApi(() => errorResponse(403, 'FORBIDDEN'));
    renderPage(<OverdueReportPage />);
    expect(await screen.findByText('无权限执行该操作')).toBeInTheDocument();
  });

  it('空态：没有逾期记录时显示引导文案', async () => {
    mockOverdueApi(() => jsonResponse(makeEmptyOverduePage(0, 20)));
    renderPage(<OverdueReportPage />);
    expect(
      await screen.findByText('当前没有逾期未还的借阅，所有借阅都在应还日期内'),
    ).toBeInTheDocument();
  });

  it('正常态：渲染读者、卡号、电话、图书、ISBN、条码、借出日、应还日、逾期天数', async () => {
    mockOverdueApi();
    renderPage(<OverdueReportPage />);
    expect(await screen.findByText('张三')).toBeInTheDocument();
    const item = overdueItemFixture;
    expect(screen.getByText(item.reader.cardNo)).toBeInTheDocument();
    expect(screen.getByText(item.reader.phone as string)).toBeInTheDocument();
    expect(screen.getByText(item.book.title)).toBeInTheDocument();
    expect(screen.getByText(item.book.isbn)).toBeInTheDocument();
    expect(screen.getByText(item.copyBarcode)).toBeInTheDocument();
    expect(screen.getByText(item.borrowDate)).toBeInTheDocument();
    expect(screen.getByText(item.dueDate)).toBeInTheDocument();
    expect(screen.getByText(`${item.overdueDays} 天`)).toBeInTheDocument();
    // 电话为 null 的读者显示占位符
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByText('共 25 条逾期')).toBeInTheDocument();
  });
});

describe('AC-STAT-04 / AC-UI-06 OverdueReportPage 请求参数、分页与排序', () => {
  it('默认请求 page=0、size=20、sort=overdueDays,desc', async () => {
    const spy = mockOverdueApi();
    renderPage(<OverdueReportPage />);
    await screen.findByText('张三');
    const params = callsTo(spy, '/api/reports/overdue')[0].searchParams;
    expect(params.get('page')).toBe('0');
    expect(params.get('size')).toBe('20');
    expect(params.get('sort')).toBe('overdueDays,desc');
  });

  it('点击第 2 页后请求 page=1；请求期间保留旧数据，返回后渲染第 2 页', async () => {
    const spy = mockOverdueApi((url) => {
      const page = Number(url.searchParams.get('page') ?? '0');
      if (page === 1) {
        return new Promise<Response>((resolve) =>
          setTimeout(() => resolve(jsonResponse(makeOverduePage(1, 20))), 80),
        );
      }
      return jsonResponse(makeOverduePage(page, 20));
    });
    renderPage(<OverdueReportPage />);
    await screen.findByText('张三');
    fireEvent.click(screen.getByRole('button', { name: '页 2' }));
    // 10.2 节：翻页时保留旧数据
    expect(screen.getByText('张三')).toBeInTheDocument();
    await screen.findByText('王五');
    const calls = callsTo(spy, '/api/reports/overdue');
    expect(calls[calls.length - 1].searchParams.get('page')).toBe('1');
    expect(screen.queryByText('张三')).not.toBeInTheDocument();
  });

  it('点击“应还日”排序后请求 sort=dueDate,desc，并回到第一页', async () => {
    const spy = mockOverdueApi();
    renderPage(<OverdueReportPage />);
    await screen.findByText('张三');
    fireEvent.click(screen.getByRole('button', { name: '页 2' }));
    await screen.findByText('王五');
    fireEvent.click(screen.getByRole('columnheader', { name: /应还日/ }));
    await waitFor(() => {
      const calls = callsTo(spy, '/api/reports/overdue');
      const params = calls[calls.length - 1].searchParams;
      expect(params.get('sort')).toBe('dueDate,desc');
      expect(params.get('page')).toBe('0');
    });
  });

  it('修改每页条数为 10 后请求 size=10', async () => {
    const spy = mockOverdueApi();
    renderPage(<OverdueReportPage />);
    await screen.findByText('张三');
    const sizeItem = screen.getByTitle('20 条/页');
    fireEvent.mouseDown(sizeItem.closest('.ant-select-selector') ?? sizeItem);
    fireEvent.click(await screen.findByTitle('10 条/页'));
    await waitFor(() => {
      const calls = callsTo(spy, '/api/reports/overdue');
      expect(calls[calls.length - 1].searchParams.get('size')).toBe('10');
    });
  });

  it('逾期天数列默认显示降序排序状态', async () => {
    mockOverdueApi();
    renderPage(<OverdueReportPage />);
    await screen.findByText('张三');
    const header = screen.getByRole('columnheader', { name: /逾期天数/ });
    expect(header).toHaveAttribute('aria-sort', 'descending');
  });
});

describe('AC-UI-06 OverdueReportPage 数据行结构', () => {
  it('每行包含逾期天数红色标签', async () => {
    mockOverdueApi();
    renderPage(<OverdueReportPage />);
    await screen.findByText('张三');
    const row = screen.getByText('张三').closest('tr') as HTMLElement;
    expect(within(row).getByText('10 天')).toBeInTheDocument();
    expect(within(row).getByText('C-00021-01')).toBeInTheDocument();
  });
});