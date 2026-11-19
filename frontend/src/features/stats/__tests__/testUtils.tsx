// stats feature 测试共享工具：Provider 组合 + fetch mock（后端统计接口尚未实现，测试一律 mock）。
import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { MemoryRouter } from 'react-router';
import { vi } from 'vitest';

export function renderPage(ui: ReactElement, { route = '/' }: { route?: string } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <ConfigProvider locale={zhCN}>
        <AntdApp>
          <MemoryRouter initialEntries={[route]}>{ui}</MemoryRouter>
        </AntdApp>
      </ConfigProvider>
    </QueryClientProvider>,
  );
  return { queryClient, ...utils };
}

export function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** 与 8.1 节错误响应结构一致；message 留空让 client 回退到 errors.ts 中文文案。 */
export function errorResponse(status: number, code: string): Response {
  return jsonResponse(
    {
      code,
      message: '',
      status,
      path: '/api/stats',
      timestamp: '2026-11-17T08:00:00Z',
      fieldErrors: [],
      details: {},
    },
    status,
  );
}

const BASE = 'http://localhost';

export type FetchHandler = (url: URL, init?: RequestInit) => Response | Promise<Response>;

/** mock 全局 fetch；vitest 配置 restoreMocks:true 会在每个用例后自动还原。 */
export function mockFetch(handler: FetchHandler) {
  return vi
    .spyOn(globalThis, 'fetch')
    .mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) =>
      handler(new URL(String(input), BASE), init),
    );
}

/** 取某路径的全部请求 URL（含 query），用于断言请求参数。 */
export function callsTo(spy: ReturnType<typeof mockFetch>, pathname: string): URL[] {
  return spy.mock.calls
    .map((call) => new URL(String(call[0]), BASE))
    .filter((url) => url.pathname === pathname);
}

/** 永不 resolve 的响应：让查询停留在加载态。 */
export function pendingForever(): Promise<Response> {
  return new Promise<Response>(() => {});
}