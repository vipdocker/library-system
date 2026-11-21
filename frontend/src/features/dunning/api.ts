import { apiGet, apiPost } from '../../api/client';
import type { DunningCase, DunningCaseSummary, DunningInput, DunningQuery, DunningRecord, Page } from '../../api/types';

export const dunningKeys = {
  all: ['dunning'] as const,
  cases: ['dunning', 'cases'] as const,
  list: (params: DunningQuery) => ['dunning', 'cases', params] as const,
  history: (loanId: number) => ['dunning', 'history', loanId] as const,
};

export const dunningQueryOptions = {
  staleTime: 0,
  refetchOnMount: 'always' as const,
  refetchInterval: false as const,
  refetchOnWindowFocus: false,
  refetchOnReconnect: false,
  retry: false as const,
};

export function normalizeDunningNote(note?: string | null): string | null {
  // 与后端 Java String.trim 的边界一致，不移除不间断空格等业务文本。
  return note?.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '') || null;
}

export function registerDunning(loanId: number, input: DunningInput): Promise<DunningRecord> {
  return apiPost(`/api/loans/${loanId}/dunning-records`, { method: input.method, note: normalizeDunningNote(input.note) });
}

export function getDunningHistory(loanId: number, signal?: AbortSignal): Promise<DunningCase> {
  return apiGet(`/api/loans/${loanId}/dunning-records`, undefined, { signal });
}

export function listDunningCases(params: DunningQuery, signal?: AbortSignal): Promise<Page<DunningCaseSummary>> {
  return apiGet('/api/dunning-cases', { status: params.status, page: params.page, size: params.size }, { signal });
}
