import type { DunningCase, DunningCaseSummary, DunningRecord, Page } from '../../../api/types';

export const record: DunningRecord = {
  id: 11, loanId: 21, method: 'PHONE', note: '<script>管理员备注</script>',
  operator: { id: 1, username: 'admin-real' }, createdAt: '2026-11-19T08:00:00Z', notificationId: 31,
};
export const summary: DunningCaseSummary = {
  loanId: 21, reader: { id: 2, cardNo: 'R000002', name: '张三' }, book: { id: 3, title: '借阅测试书' },
  copyBarcode: 'C00000003', dueDate: '2026-11-09', status: 'OPEN', closedAt: null, closeReason: null,
  recordCount: 2, lastRecordedAt: '2026-11-19T09:00:00Z',
};
export const history: DunningCase = { loanId: 21, status: 'OPEN', closedAt: null, closeReason: null,
  records: [record, { ...record, id: 12, method: 'IN_PERSON', note: null, createdAt: '2026-11-19T09:00:00Z', notificationId: 32 }],
};
export const casesPage: Page<DunningCaseSummary> = { items: [summary], page: 0, size: 20, totalItems: 21, totalPages: 2 };
export const emptyCases: Page<DunningCaseSummary> = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 };
