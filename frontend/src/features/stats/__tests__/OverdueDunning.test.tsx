import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import OverdueReportPage from '../pages/OverdueReportPage';
import { makeOverduePage, overdueItemFixture } from './fixtures';
import { callsTo, errorResponse, jsonResponse, mockFetch, renderPage } from './testUtils';
import { record } from '../../dunning/__tests__/fixtures';

function setup(post: (body: unknown) => Response | Promise<Response> = () => jsonResponse(record, 201)) {
  return mockFetch((url, init) => url.pathname === '/api/reports/overdue' ? jsonResponse(makeOverduePage(0, 20)) : post(JSON.parse(String(init?.body))));
}
async function open() {
  renderPage(<OverdueReportPage />);
  const row = (await screen.findByText(overdueItemFixture.reader.name)).closest('tr')!;
  fireEvent.click(within(row).getByRole('button', { name: '登记催还' }));
  return within(await screen.findByRole('dialog'));
}
async function selectMethod(dialog: ReturnType<typeof within>, label: string) {
  fireEvent.click(dialog.getByRole('radio', { name: label }));
}

describe('AC-DUN-01 / AC-UI-07 逾期报表催还登记', () => {
  it('行入口显示借阅/读者/图书/应还日，明确管理员备注与登录触达限制', async () => {
    setup(); const dialog = await open();
    expect(dialog.getByText(`借阅号：${overdueItemFixture.loanId}`)).toBeInTheDocument();
    expect(dialog.getByText(overdueItemFixture.reader.name)).toBeInTheDocument();
    expect(dialog.getByText(overdueItemFixture.book.title)).toBeInTheDocument();
    expect(dialog.getByText(overdueItemFixture.dueDate)).toBeInTheDocument();
    expect(dialog.getByText(/备注仅管理员可见/)).toBeInTheDocument();
    expect(dialog.getByText(/登记将生成固定模板站内提醒/)).toBeInTheDocument();
  });
  it('方式必选，空提交不发POST', async () => {
    const spy = setup(); const dialog = await open();
    fireEvent.click(dialog.getByRole('button', { name: '确认登记' }));
    expect(await dialog.findByText('请选择催还方式')).toBeInTheDocument();
    expect(callsTo(spy, `/api/loans/${overdueItemFixture.loanId}/dunning-records`)).toHaveLength(0);
  });
  it.each([['电话', 'PHONE'], ['当面', 'IN_PERSON'], ['站内', 'IN_APP'], ['其他', 'OTHER']])('合法方式%s仅提交冻结字段，备注trim', async (label, method) => {
    let body: unknown;
    setup(b => { body = b; return jsonResponse(record, 201); }); const dialog = await open();
    await selectMethod(dialog, label);
    fireEvent.change(dialog.getByRole('textbox', { name: '管理员备注' }), { target: { value: '  私密备注  ' } });
    fireEvent.click(dialog.getByRole('button', { name: '确认登记' }));
    await waitFor(() => expect(body).toEqual({ method, note: '私密备注' }));
    expect(await screen.findByText('已登记催还并生成站内提醒，不代表已送达')).toBeInTheDocument();
  });
  it('空备注转null；成功失效案件、历史、报表缓存', async () => {
    let body: unknown;
    setup(b => { body = b; return jsonResponse(record, 201); });
    const view = renderPage(<OverdueReportPage />);
    view.queryClient.setQueryDefaults(['dunning'], { gcTime: Infinity });
    view.queryClient.setQueryData(['dunning', 'history', 21], { status: 'OPEN' });
    view.queryClient.setQueryData(['dunning', 'cases'], {});
    const row = (await screen.findByText(overdueItemFixture.reader.name)).closest('tr')!;
    fireEvent.click(within(row).getByRole('button', { name: '登记催还' }));
    const dialog = within(await screen.findByRole('dialog'));
    await selectMethod(dialog, '电话');
    fireEvent.change(dialog.getByRole('textbox', { name: '管理员备注' }), { target: { value: ' \t\n ' } });
    fireEvent.click(dialog.getByRole('button', { name: '确认登记' }));
    await waitFor(() => expect(body).toEqual({ method: 'PHONE', note: null }));
    await waitFor(() => expect(view.queryClient.getQueryState(['dunning', 'history', 21])?.isInvalidated).toBe(true));
    expect(view.queryClient.getQueryState(['dunning', 'cases'])?.isInvalidated).toBe(true);
  });
  it('501字符拒绝，500字符允许；超长前后空白不计入服务端长度', async () => {
    let n = 0;
    setup(() => { n++; return jsonResponse(record, 201); }); const dialog = await open();
    await selectMethod(dialog, '其他');
    const input = dialog.getByRole('textbox', { name: '管理员备注' });
    fireEvent.change(input, { target: { value: '字'.repeat(501) } });
    fireEvent.click(dialog.getByRole('button', { name: '确认登记' }));
    expect(await dialog.findByText('备注最多500字符')).toBeInTheDocument(); expect(n).toBe(0);
    fireEvent.change(input, { target: { value: '  ' + '字'.repeat(500) + '  ' } });
    fireEvent.click(dialog.getByRole('button', { name: '确认登记' }));
    await waitFor(() => expect(n).toBe(1));
  });
  it('提交期间禁重复和关闭，双击/重复submit只有一次POST', async () => {
    let resolve!: (r: Response) => void;
    const spy = setup(() => new Promise<Response>(r => { resolve = r; })); const dialog = await open();
    await selectMethod(dialog, '电话');
    const button = dialog.getByRole('button', { name: '确认登记' });
    fireEvent.click(button); fireEvent.click(button);
    await waitFor(() => expect(button).toBeDisabled());
    fireEvent.submit(button.closest('form')!);
    expect(dialog.getByRole('button', { name: '取消登记' })).toBeDisabled();
    expect(callsTo(spy, `/api/loans/${overdueItemFixture.loanId}/dunning-records`)).toHaveLength(1);
    resolve(jsonResponse(record, 201));
    await screen.findByText('已登记催还并生成站内提醒，不代表已送达');
  });
  it.each([[409, 'LOAN_ALREADY_RETURNED', '该借阅已归还，请勿重复操作'], [409, 'LOAN_NOT_OVERDUE', '该借阅尚未逾期，不能登记催还'], [503, 'DB_BUSY', '系统繁忙，请稍后再试'], [403, 'FORBIDDEN', '无权限执行该操作'], [500, 'INTERNAL_ERROR', '系统内部错误，请稍后再试']])('错误%s/%s明确提示且不自动重试', async (status, code, text) => {
    let n = 0;
    setup(() => { n++; return errorResponse(Number(status), String(code)); }); const dialog = await open();
    await selectMethod(dialog, '电话');
    fireEvent.click(dialog.getByRole('button', { name: '确认登记' }));
    expect(await dialog.findByText(String(text))).toBeInTheDocument(); expect(n).toBe(1);
    expect(dialog.getByRole('button', { name: '确认登记' })).toBeEnabled();
    expect(screen.queryByText('已登记催还并生成站内提醒，不代表已送达')).not.toBeInTheDocument();
  });
  it('服务端fieldErrors映射method/note，保留输入；显式重试后成功', async () => {
    let n = 0;
    setup(() => ++n === 1 ? jsonResponse({ code: 'VALIDATION_FAILED', message: '', status: 400, fieldErrors: [{ field: 'note', message: '服务端备注错误' }, { field: 'method', message: '服务端方式错误' }], details: {} }, 400) : jsonResponse(record, 201));
    const dialog = await open(); await selectMethod(dialog, '电话');
    fireEvent.change(dialog.getByRole('textbox', { name: '管理员备注' }), { target: { value: '保留备注' } });
    fireEvent.click(dialog.getByRole('button', { name: '确认登记' }));
    expect(await dialog.findByText('服务端备注错误')).toBeInTheDocument();
    expect(dialog.getByText('服务端方式错误')).toBeInTheDocument();
    expect(dialog.getByRole('textbox', { name: '管理员备注' })).toHaveValue('保留备注');
    fireEvent.click(dialog.getByRole('button', { name: '确认登记' }));
    await screen.findByText('已登记催还并生成站内提醒，不代表已送达'); expect(n).toBe(2);
  });
  it('409后显式刷新关闭旧表单并移除已归还行', async () => {
    let returned = false;
    const spy = mockFetch((url) => url.pathname === '/api/reports/overdue' ? jsonResponse(returned ? { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 } : makeOverduePage(0, 20)) : errorResponse(409, 'LOAN_ALREADY_RETURNED'));
    const dialog = await open(); await selectMethod(dialog, '电话');
    fireEvent.click(dialog.getByRole('button', { name: '确认登记' }));
    await dialog.findByText('该借阅已归还，请勿重复操作'); returned = true;
    fireEvent.click(dialog.getByRole('button', { name: '刷新逾期报表' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await screen.findByText('当前没有逾期未还的借阅，所有借阅都在应还日期内');
    expect(callsTo(spy, '/api/reports/overdue')).toHaveLength(2);
  });
});
