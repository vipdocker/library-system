import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../../api/client';
import { getDunningHistory, listDunningCases, registerDunning, normalizeDunningNote, dunningQueryOptions } from '../api';
import { record, history, casesPage } from './fixtures';

afterEach(() => { vi.restoreAllMocks(); document.cookie = 'XSRF-TOKEN=;max-age=0'; });
function reply(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }); }

describe('AC-DUN-01/04 冻结接口', () => {
  it('登记只提供method/note，使用现有CSRF与201真实DTO', async () => {
    document.cookie = 'XSRF-TOKEN=token';
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(reply(record, 201));
    expect(await registerDunning(21, { method: 'PHONE', note: '  备注  ' })).toEqual(record);
    expect(spy).toHaveBeenCalledWith('/api/loans/21/dunning-records', expect.objectContaining({ method: 'POST', credentials: 'same-origin', body: JSON.stringify({ method: 'PHONE', note: '备注' }), headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'token' }) }));
  });
  it('空可选备注转null，不发送operatorId或readerId等额外字段', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(reply(record, 201));
    await registerDunning(21, { method: 'OTHER', note: ' \n ' });
    expect(JSON.parse(String(spy.mock.calls[0][1]?.body))).toEqual({ method: 'OTHER', note: null });
  });
  it('trim与Java String.trim一致，UTF16字符计数不隐性改变输入', () => {
    expect(normalizeDunningNote(' \t备注\n ')).toBe('备注');
    expect(normalizeDunningNote('')).toBeNull();
    expect(normalizeDunningNote(undefined)).toBeNull();
    expect(normalizeDunningNote('\u00a0备注\u00a0')).toBe('\u00a0备注\u00a0');
  });
  it.each(['OPEN', 'CLOSED', undefined])('查询status=%s及固定分页，无sort', async status => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(reply(casesPage));
    expect(await listDunningCases({ status: status as 'OPEN' | 'CLOSED' | undefined, page: 2, size: 10 })).toEqual(casesPage);
    const url = new URL(String(spy.mock.calls[0][0]), 'http://localhost');
    expect(url.pathname).toBe('/api/dunning-cases');
    expect(url.searchParams.get('page')).toBe('2'); expect(url.searchParams.get('size')).toBe('10');
    expect(url.searchParams.get('status')).toBe(status ?? null); expect(url.searchParams.has('sort')).toBe(false);
  });
  it('历史复用GET client，原样保留server状态与排序，传递AbortSignal', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(reply(history));
    const controller = new AbortController();
    expect(await getDunningHistory(21, controller.signal)).toEqual(history);
    expect(spy).toHaveBeenCalledWith('/api/loans/21/dunning-records', expect.objectContaining({ method: 'GET', signal: controller.signal }));
  });
  it.each([[409, 'LOAN_ALREADY_RETURNED'], [409, 'LOAN_NOT_OVERDUE'], [503, 'DB_BUSY']])('POST %s不自动重试', async (status, code) => {
    const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(reply({ code }, status));
    await expect(registerDunning(21, { method: 'IN_APP' })).rejects.toMatchObject({ status, code });
    expect(spy).toHaveBeenCalledTimes(1);
  });
  it('错误CSRF只引导token，不自动重发POST', async () => {
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async path => String(path).endsWith('/csrf') ? new Response(null, { status: 204 }) : reply({ code: 'CSRF_INVALID' }, 403));
    await expect(registerDunning(21, { method: 'PHONE' })).rejects.toBeInstanceOf(ApiError);
    expect(spy.mock.calls.map(c => c[0])).toEqual(['/api/loans/21/dunning-records', '/api/auth/csrf']);
  });
  it('局部查询始终重新打开取server，无轮询/重连/自动重试', () => {
    expect(dunningQueryOptions).toEqual({ staleTime: 0, refetchOnMount: 'always', refetchInterval: false, refetchOnWindowFocus: false, refetchOnReconnect: false, retry: false });
  });
});
