// fetch 封装。契约来源：docs/design/solution-design.md 第 10.4 节（design-v0.1）。
// - credentials: 'same-origin'；非 GET 请求从 Cookie 读取 XSRF-TOKEN 并设置 X-XSRF-TOKEN 头。
// - 非 2xx 统一解析为 ApiError；网络失败或非 JSON 响应时 code 为 NETWORK_ERROR。
// - 401 触发全局事件 UNAUTHORIZED_EVENT（SessionGuard 负责清缓存并跳转登录页）。
//   启动探测 /api/auth/me 与登录请求使用 suppressAuthEvent，避免把"未登录"误报为"会话过期"。
// - 403 CSRF_INVALID：重新获取一次 CSRF Token，仅对幂等请求（GET/HEAD/PUT/DELETE/OPTIONS）自动重试 1 次。
// 本文件由 T02 一次写全，之后对其他任务只读（T13 除外）；各 feature 的接口函数写在 features/<x>/api.ts。

import { errorMessage } from './errors';
import type { ErrorResponse, FieldError } from './types';

export const UNAUTHORIZED_EVENT = 'library:unauthorized';

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldError[];
  readonly details: Record<string, unknown>;

  constructor(
    status: number,
    code: string,
    message: string,
    fieldErrors: FieldError[] = [],
    details: Record<string, unknown> = {},
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
    this.details = details;
  }
}

export type QueryParams = Record<string, string | number | boolean | null | undefined>;

export interface RequestOptions {
  query?: QueryParams;
  body?: unknown;
  signal?: AbortSignal;
  /** 为 true 时，401 不触发全局会话过期事件（用于启动探测与登录请求） */
  suppressAuthEvent?: boolean;
}

const IDEMPOTENT_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'PUT', 'DELETE']);

function readXsrfToken(): string | null {
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return match ? decodeURIComponent(match[1]) : null;
}

function buildUrl(path: string, query?: QueryParams): string {
  if (!query) return path;
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') continue;
    search.append(key, String(value));
  }
  const qs = search.toString();
  return qs ? `${path}?${qs}` : path;
}

async function parseError(res: Response): Promise<ApiError> {
  try {
    const data = (await res.json()) as Partial<ErrorResponse>;
    if (data && typeof data.code === 'string' && data.code) {
      return new ApiError(
        res.status,
        data.code,
        data.message || errorMessage(data.code),
        data.fieldErrors ?? [],
        data.details ?? {},
      );
    }
  } catch {
    // 响应不是 JSON：按 NETWORK_ERROR 处理
  }
  return new ApiError(res.status, 'NETWORK_ERROR', errorMessage('NETWORK_ERROR'));
}

/** 启动引导 / CSRF 刷新：GET /api/auth/csrf，服务端下发 XSRF-TOKEN Cookie（204）。 */
export function bootstrapCsrf(): Promise<void> {
  return request<void>('GET', '/api/auth/csrf', { suppressAuthEvent: true });
}

export function request<T>(method: string, path: string, options: RequestOptions = {}): Promise<T> {
  return doRequest<T>(method.toUpperCase(), path, options, false);
}

async function doRequest<T>(
  method: string,
  path: string,
  options: RequestOptions,
  csrfRetried: boolean,
): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (method !== 'GET' && method !== 'HEAD') {
    const token = readXsrfToken();
    if (token) headers['X-XSRF-TOKEN'] = token;
  }

  let res: Response;
  try {
    res = await fetch(buildUrl(path, options.query), {
      method,
      credentials: 'same-origin',
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: options.signal,
    });
  } catch (err) {
    if (options.signal?.aborted) throw err;
    throw new ApiError(0, 'NETWORK_ERROR', errorMessage('NETWORK_ERROR'));
  }

  if (res.status === 401 && !options.suppressAuthEvent) {
    window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT, { detail: { path } }));
  }

  if (res.status === 403 && !csrfRetried) {
    const error = await parseError(res);
    if (error.code === 'CSRF_INVALID') {
      await bootstrapCsrf();
      if (IDEMPOTENT_METHODS.has(method)) {
        return doRequest<T>(method, path, options, true);
      }
    }
    throw error;
  }

  if (!res.ok) {
    throw await parseError(res);
  }

  if (res.status === 204) return undefined as T;
  const text = await res.text();
  if (!text) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError(res.status, 'NETWORK_ERROR', errorMessage('NETWORK_ERROR'));
  }
}

type CallOptions = Omit<RequestOptions, 'query' | 'body'>;

export function apiGet<T>(path: string, query?: QueryParams, options?: CallOptions): Promise<T> {
  return request<T>('GET', path, { ...options, query });
}

export function apiPost<T>(path: string, body?: unknown, options?: CallOptions): Promise<T> {
  return request<T>('POST', path, { ...options, body });
}

export function apiPut<T>(path: string, body?: unknown, options?: CallOptions): Promise<T> {
  return request<T>('PUT', path, { ...options, body });
}

export function apiPatch<T>(path: string, body?: unknown, options?: CallOptions): Promise<T> {
  return request<T>('PATCH', path, { ...options, body });
}

export function apiDelete<T>(path: string, options?: CallOptions): Promise<T> {
  return request<T>('DELETE', path, options);
}