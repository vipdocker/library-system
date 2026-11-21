import { useState } from 'react';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import DunningCaseListPage from '../pages/DunningCaseListPage';
import { callsTo, errorResponse, jsonResponse, mockFetch, pendingForever, renderPage } from '../../stats/__tests__/testUtils';
import { casesPage, emptyCases, history, summary } from './fixtures';

function mockCases(list: () => Response | Promise<Response> = () => jsonResponse(casesPage), details: () => Response | Promise<Response> = () => jsonResponse(history)) {
  return mockFetch((url) => url.pathname === '/api/dunning-cases' ? list() : details());
}

describe('AC-DUN-04 / AC-UI-07 案件与历史', () => {
  it('首次加载显示加载提示，不能看见旧状态', async () => {
    mockCases(() => pendingForever());
    renderPage(<DunningCaseListPage />);
    expect(await screen.findByText('正在加载催还案件…')).toBeInTheDocument();
  });
  it('空态并明确人工方式仅记录，不宣称送达', async () => {
    mockCases(() => jsonResponse(emptyCases));
    renderPage(<DunningCaseListPage />);
    expect(await screen.findByText('暂无催还案件')).toBeInTheDocument();
    expect(screen.getByText(/电话、当面、站内、其他仅为登记方式/)).toBeInTheDocument();
  });
  it('503没有自动重试，显式重试恢复', async () => {
    let n = 0;
    const spy = mockCases(() => ++n === 1 ? errorResponse(503, 'DB_BUSY') : jsonResponse(casesPage));
    renderPage(<DunningCaseListPage />);
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    expect(n).toBe(1);
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText(summary.book.title)).toBeInTheDocument();
    expect(callsTo(spy, '/api/dunning-cases')).toHaveLength(2);
  });
  it('数据显示真实记录数，默认固定排序不发sort', async () => {
    const spy = mockCases(); renderPage(<DunningCaseListPage />);
    await screen.findByText(summary.book.title);
    expect(screen.getByText('2 次')).toBeInTheDocument();
    expect(screen.getByText('未结案')).toBeInTheDocument();
    const query = callsTo(spy, '/api/dunning-cases')[0].searchParams;
    expect([...query.entries()]).toEqual([['page', '0'], ['size', '20']]);
  });
  it('分页后切换状态回第一页，只发OPEN或CLOSED', async () => {
    const spy = mockFetch((url) => jsonResponse({ ...casesPage, page: Number(url.searchParams.get('page') ?? 0) }));
    renderPage(<DunningCaseListPage />); await screen.findByText(summary.book.title);
    fireEvent.click(screen.getByRole('button', { name: '页 2' }));
    await waitFor(() => expect(callsTo(spy, '/api/dunning-cases').at(-1)?.searchParams.get('page')).toBe('1'));
    await waitFor(() => expect(screen.getByRole('radio', { name: '仅未结案' })).toBeEnabled());
    fireEvent.click(screen.getByRole('radio', { name: '仅未结案' }));
    await waitFor(() => {
      const params = callsTo(spy, '/api/dunning-cases').at(-1)!.searchParams;
      expect(params.get('status')).toBe('OPEN'); expect(params.get('page')).toBe('0');
    });
    await waitFor(() => expect(screen.getByRole('radio', { name: '仅已结案' })).toBeEnabled());
    fireEvent.click(screen.getByRole('radio', { name: '仅已结案' }));
    await waitFor(() => expect(callsTo(spy, '/api/dunning-cases').at(-1)?.searchParams.get('status')).toBe('CLOSED'));
    await waitFor(() => expect(screen.getByRole('radio', { name: '全部案件' })).toBeEnabled());
    fireEvent.click(screen.getByRole('radio', { name: '全部案件' }));
    await waitFor(() => expect(callsTo(spy, '/api/dunning-cases').at(-1)?.searchParams.has('status')).toBe(false));
  });
  it('历史按server顺序显示经办人、方式、纯文本备注和时间', async () => {
    mockCases(); renderPage(<DunningCaseListPage />);
    fireEvent.click(await screen.findByRole('button', { name: '查看历史' }));
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText('<script>管理员备注</script>');
    expect(dialog.querySelector('script')).toBeNull();
    expect(within(dialog).getAllByText('admin-real')).toHaveLength(2);
    expect(within(dialog).getByText('电话')).toBeInTheDocument();
    expect(within(dialog).getByText('当面')).toBeInTheDocument();
    expect(within(dialog).getByText('无备注')).toBeInTheDocument();
    const times = [...dialog.querySelectorAll('time')].map(t => t.getAttribute('datetime'));
    expect(times).toEqual(history.records.map(r => r.createdAt));
  });
  it('无记录历史显示空态，404显示中文并可重试', async () => {
    let fail = true;
    mockCases(undefined, () => fail ? errorResponse(404, 'LOAN_NOT_FOUND') : jsonResponse({ ...history, records: [] }));
    renderPage(<DunningCaseListPage />);
    fireEvent.click(await screen.findByRole('button', { name: '查看历史' }));
    const dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('借阅记录不存在')).toBeInTheDocument();
    fail = false;
    fireEvent.click(within(dialog).getByRole('button', { name: /重\s*试/ }));
    expect(await within(dialog).findByText('该借阅暂无催还记录')).toBeInTheDocument();
  });
  it('再次打开历史强制获取server结案状态，不展示缓存OPEN', async () => {
    let closed = false;
    const spy = mockCases(undefined, () => jsonResponse(closed ? { ...history, status: 'CLOSED', closedAt: '2026-11-20T01:02:03Z', closeReason: 'RETURNED' } : history));
    renderPage(<DunningCaseListPage />);
    fireEvent.click(await screen.findByRole('button', { name: '查看历史' }));
    let dialog = await screen.findByRole('dialog');
    await within(dialog).findByText('<script>管理员备注</script>');
    fireEvent.click(within(dialog).getByRole('button', { name: '关闭历史' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    closed = true;
    fireEvent.click(screen.getByRole('button', { name: '查看历史' }));
    dialog = await screen.findByRole('dialog');
    expect(await within(dialog).findByText('结案原因：已归还')).toBeInTheDocument();
    expect(within(dialog).getByText('已结案')).toBeInTheDocument();
    expect(dialog.querySelector('time[datetime="2026-11-20T01:02:03Z"]')).not.toBeNull();
    expect(callsTo(spy, '/api/loans/21/dunning-records')).toHaveLength(2);
  });
  it('历史刷新同时重新取案件列表，503不继续展示过期状态', async () => {
    let fail = false;
    const spy = mockCases(undefined, () => fail ? errorResponse(503, 'DB_BUSY') : jsonResponse(history));
    renderPage(<DunningCaseListPage />);
    fireEvent.click(await screen.findByRole('button', { name: '查看历史' }));
    const dialog = await screen.findByRole('dialog');
    await within(dialog).findByText('<script>管理员备注</script>');
    fail = true; fireEvent.click(within(dialog).getByRole('button', { name: '刷新历史' }));
    expect(await within(dialog).findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    expect(within(dialog).queryByText('<script>管理员备注</script>')).not.toBeInTheDocument();
    await waitFor(() => expect(callsTo(spy, '/api/dunning-cases')).toHaveLength(2));
  });
  it('案件再挂载即重新加载，不轮询/自动聚焦请求', async () => {
    const spy = mockCases();
    function Harness() {
      const [visible, setVisible] = useState(true);
      return <><button onClick={() => setVisible(value => !value)}>切换页面</button>{visible && <DunningCaseListPage />}</>;
    }
    renderPage(<Harness />); await screen.findByText(summary.book.title);
    fireEvent.click(screen.getByRole('button', { name: '切换页面' }));
    fireEvent.click(screen.getByRole('button', { name: '切换页面' }));
    await waitFor(() => expect(callsTo(spy, '/api/dunning-cases')).toHaveLength(2));
    window.dispatchEvent(new Event('focus'));
    expect(callsTo(spy, '/api/dunning-cases')).toHaveLength(2);
  });
});
