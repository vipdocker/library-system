// AC-AUTH-06（client 部分）/ AC-AUTH-09（401 处理）：CSRF 头、错误解析、401 事件、CSRF_INVALID 重试策略。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  UNAUTHORIZED_EVENT,
  apiDelete,
  apiGet,
  apiGetBlob,
  apiPost,
  apiPut,
  bootstrapCsrf,
} from '../client';
import type { ErrorResponse } from '../types';

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function errorBody(code: string, status: number): ErrorResponse {
  return {
    code,
    message: `msg:${code}`,
    status,
    path: '/api/test',
    timestamp: '2026-11-18T00:00:00Z',
    fieldErrors: [],
    details: {},
  };
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  document.cookie = 'XSRF-TOKEN=tok123';
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
});

describe('AC-AUTH-06 client CSRF 头', () => {
  it('非 GET 请求从 Cookie 读取 XSRF-TOKEN 并设置 X-XSRF-TOKEN 头', async () => {
    fetchMock.mockResolvedValue(jsonResponse(201, { id: 1 }));
    await apiPost('/api/loans', { readerCardNo: 'R000001', copyBarcode: 'C00000001' });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/loans');
    expect(init.method).toBe('POST');
    expect(init.credentials).toBe('same-origin');
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('tok123');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(init.body).toBe(JSON.stringify({ readerCardNo: 'R000001', copyBarcode: 'C00000001' }));
  });

  it('PUT / DELETE 同样携带 CSRF 头；GET 不携带', async () => {
    // 每次调用返回新的 Response（body 只能读取一次）
    fetchMock.mockImplementation(async () => jsonResponse(200, {}));
    await apiPut('/api/readers/1', { name: 'x' });
    await apiDelete('/api/copies/1');
    await apiGet('/api/books');

    const headersOf = (i: number) =>
      (fetchMock.mock.calls[i][1] as RequestInit).headers as Record<string, string>;
    expect(headersOf(0)['X-XSRF-TOKEN']).toBe('tok123');
    expect(headersOf(1)['X-XSRF-TOKEN']).toBe('tok123');
    expect(headersOf(2)['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('Cookie 值做 URL 解码', async () => {
    document.cookie = `XSRF-TOKEN=${encodeURIComponent('a+b/c=')}`;
    fetchMock.mockResolvedValue(jsonResponse(200, {}));
    await apiPost('/api/auth/logout');
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('a+b/c=');
  });
});

describe('AC-FE-03 blob uses the same protected client', () => {
  it('returns exact bytes with cookies, query and cancellation, without JSON or CSRF headers', async () => {
    fetchMock.mockResolvedValue(new Response(new Uint8Array([137, 80, 78, 71]), { headers: { 'Content-Type': 'image/png' } }));
    const signal = new AbortController().signal;
    const blob = await apiGetBlob('/api/books/5/cover', { v: 'sha' }, { signal });
    expect(blob.type).toBe('image/png');
    expect(Array.from(new Uint8Array(await blob.arrayBuffer()))).toEqual([137, 80, 78, 71]);
    expect(fetchMock).toHaveBeenCalledWith('/api/books/5/cover?v=sha', expect.objectContaining({ credentials: 'same-origin', signal }));
    const headers = fetchMock.mock.calls[0][1].headers;
    expect(headers.Accept).toBe('image/jpeg, image/png');
    expect(headers['Content-Type']).toBeUndefined();
    expect(headers['X-XSRF-TOKEN']).toBeUndefined();
  });
  it.each([401, 403, 404, 429, 502])('retains JSON errors at %s and 401 session handling', async (status) => {
    const listener = vi.fn();
    window.addEventListener(UNAUTHORIZED_EVENT, listener);
    fetchMock.mockResolvedValue(jsonResponse(status, { ...errorBody('COVER_NOT_FOUND', status), details: { retryAfterSeconds: 30 } }));
    await expect(apiGetBlob('/api/books/5/cover')).rejects.toMatchObject({ status, code: 'COVER_NOT_FOUND', details: { retryAfterSeconds: 30 } });
    expect(listener).toHaveBeenCalledTimes(status === 401 ? 1 : 0);
    window.removeEventListener(UNAUTHORIZED_EVENT, listener);
  });
  it('does not convert cancellation or retry a disconnected request', async () => {
    const controller = new AbortController();
    controller.abort();
    const error = new DOMException('Aborted', 'AbortError');
    fetchMock.mockRejectedValue(error);
    await expect(apiGetBlob('/api/books/5/cover', undefined, { signal: controller.signal })).rejects.toBe(error);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

describe('client 响应与错误解析', () => {
  it('2xx JSON 正常解析；查询参数拼接并跳过空值', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { items: [] }));
    const data = await apiGet<{ items: unknown[] }>('/api/books', {
      q: 'java',
      page: 0,
      availableOnly: true,
      skipUndef: undefined,
      skipNull: null,
      skipEmpty: '',
    });
    expect(data).toEqual({ items: [] });
    expect(fetchMock.mock.calls[0][0]).toBe('/api/books?q=java&page=0&availableOnly=true');
  });

  it('204 返回 undefined', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await expect(bootstrapCsrf()).resolves.toBeUndefined();
    expect(fetchMock.mock.calls[0][0]).toBe('/api/auth/csrf');
  });

  it('非 2xx 解析为 ApiError（status/code/message/fieldErrors/details）', async () => {
    const body: ErrorResponse = {
      ...errorBody('VALIDATION_FAILED', 400),
      fieldErrors: [{ field: 'isbn', message: 'ISBN 校验位错误' }],
      details: { limit: 5 },
    };
    fetchMock.mockResolvedValue(jsonResponse(400, body));
    const err = await apiPost('/api/books', {}).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    const apiErr = err as ApiError;
    expect(apiErr.status).toBe(400);
    expect(apiErr.code).toBe('VALIDATION_FAILED');
    expect(apiErr.fieldErrors).toEqual([{ field: 'isbn', message: 'ISBN 校验位错误' }]);
    expect(apiErr.details).toEqual({ limit: 5 });
  });

  it('错误响应不是 JSON 时 code 为 NETWORK_ERROR', async () => {
    fetchMock.mockResolvedValue(new Response('<html>502</html>', { status: 502 }));
    const err = (await apiGet('/api/books').catch((e: unknown) => e)) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('NETWORK_ERROR');
    expect(err.status).toBe(502);
  });

  it('网络失败时 code 为 NETWORK_ERROR、status 为 0', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    const err = (await apiGet('/api/books').catch((e: unknown) => e)) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('NETWORK_ERROR');
    expect(err.status).toBe(0);
  });
});

describe('AC-AUTH-09 client 401 全局事件', () => {
  it('401 触发 UNAUTHORIZED_EVENT；suppressAuthEvent 时不触发', async () => {
    const listener = vi.fn();
    window.addEventListener(UNAUTHORIZED_EVENT, listener);
    fetchMock.mockResolvedValue(jsonResponse(401, errorBody('AUTH_REQUIRED', 401)));

    await expect(apiGet('/api/books')).rejects.toBeInstanceOf(ApiError);
    expect(listener).toHaveBeenCalledTimes(1);

    await expect(
      apiGet('/api/auth/me', undefined, { suppressAuthEvent: true }),
    ).rejects.toBeInstanceOf(ApiError);
    expect(listener).toHaveBeenCalledTimes(1);

    window.removeEventListener(UNAUTHORIZED_EVENT, listener);
  });
});

describe('CSRF_INVALID 重试策略（设计 10.4）', () => {
  it('幂等请求（PUT）：刷新 Token 后自动重试 1 次并携带新 Token', async () => {
    let call = 0;
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      call += 1;
      const url = String(input);
      if (call === 1) return jsonResponse(403, errorBody('CSRF_INVALID', 403));
      if (url === '/api/auth/csrf') {
        document.cookie = 'XSRF-TOKEN=tokNew';
        return new Response(null, { status: 204 });
      }
      return jsonResponse(200, { ok: true });
    });

    await expect(apiPut('/api/readers/1', { name: 'x' })).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const retryInit = fetchMock.mock.calls[2][1] as RequestInit;
    expect(retryInit.method).toBe('PUT');
    expect((retryInit.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('tokNew');
  });

  it('幂等请求（GET）：刷新 Token 后自动重试 1 次（GET 本身不带 CSRF 头）', async () => {
    let call = 0;
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      call += 1;
      if (call === 1) return jsonResponse(403, errorBody('CSRF_INVALID', 403));
      if (String(input) === '/api/auth/csrf') return new Response(null, { status: 204 });
      return jsonResponse(200, { ok: true });
    });

    await expect(apiGet('/api/books')).resolves.toEqual({ ok: true });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const retryInit = fetchMock.mock.calls[2][1] as RequestInit;
    expect(retryInit.method).toBe('GET');
    expect((retryInit.headers as Record<string, string>)['X-XSRF-TOKEN']).toBeUndefined();
  });

  it('非幂等请求（POST）：刷新 Token 但不自动重试，直接抛出 CSRF_INVALID', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/auth/csrf') return new Response(null, { status: 204 });
      return jsonResponse(403, errorBody('CSRF_INVALID', 403));
    });

    const err = (await apiPost('/api/loans', {}).catch((e: unknown) => e)) as ApiError;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe('CSRF_INVALID');
    // 原始请求 + csrf 刷新，共 2 次，没有第 3 次重试
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('重试后仍 CSRF_INVALID 时不再继续重试', async () => {
    fetchMock.mockImplementation(async (input: RequestInfo | URL) => {
      if (String(input) === '/api/auth/csrf') return new Response(null, { status: 204 });
      return jsonResponse(403, errorBody('CSRF_INVALID', 403));
    });
    const err = (await apiGet('/api/books').catch((e: unknown) => e)) as ApiError;
    expect(err.code).toBe('CSRF_INVALID');
    // 原始 + csrf + 重试 1 次 = 3 次
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('普通 403 FORBIDDEN 不触发 CSRF 刷新', async () => {
    fetchMock.mockResolvedValue(jsonResponse(403, errorBody('FORBIDDEN', 403)));
    const err = (await apiGet('/api/stats/overview').catch((e: unknown) => e)) as ApiError;
    expect(err.code).toBe('FORBIDDEN');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});