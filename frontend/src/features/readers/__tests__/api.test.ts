// AC-RDR-01..06（契约接线）：readers/api.ts 的方法、路径、参数与第 8.4 节逐项对应，供 T13 联调核对。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiDelete, apiGet, apiPost, apiPut } from '../../../api/client';
import type { ReaderCreateInput, ReaderUpdateInput } from '../../../api/types';
import {
  createReader,
  deleteReader,
  getMyReaderProfile,
  getReader,
  listReaderLoans,
  listReaders,
  resetReaderPassword,
  updateReader,
} from '../api';

vi.mock('../../../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/client')>();
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn(), apiPut: vi.fn(), apiDelete: vi.fn() };
});

const mockGet = vi.mocked(apiGet);
const mockPost = vi.mocked(apiPost);
const mockPut = vi.mocked(apiPut);
const mockDelete = vi.mocked(apiDelete);

beforeEach(() => {
  mockGet.mockResolvedValue(undefined);
  mockPost.mockResolvedValue(undefined);
  mockPut.mockResolvedValue(undefined);
  mockDelete.mockResolvedValue(undefined);
});

describe('AC-RDR-04 GET /api/readers 检索参数', () => {
  it('传递 q/status/page/size/sort；空 q 不发送', async () => {
    await listReaders({ q: '', page: 0, size: 20 });
    expect(mockGet).toHaveBeenCalledWith('/api/readers', {
      q: undefined,
      status: undefined,
      page: 0,
      size: 20,
      sort: undefined,
    });

    await listReaders({ q: '张三', status: 'DISABLED', page: 2, size: 10, sort: 'name,asc' });
    expect(mockGet).toHaveBeenLastCalledWith('/api/readers', {
      q: '张三',
      status: 'DISABLED',
      page: 2,
      size: 10,
      sort: 'name,asc',
    });
  });
});

describe('AC-RDR-01/02/03/05/06 读者接口路径与方法', () => {
  it('AC-RDR-04 GET /api/readers/{id}', async () => {
    await getReader(5);
    expect(mockGet).toHaveBeenCalledWith('/api/readers/5');
  });

  it('AC-RDR-01 POST /api/readers 携带创建入参', async () => {
    const input: ReaderCreateInput = {
      name: '王五',
      phone: null,
      email: null,
      username: 'reader3',
      initialPassword: 'Reader@123',
    };
    await createReader(input);
    expect(mockPost).toHaveBeenCalledWith('/api/readers', input);
  });

  it('AC-RDR-02 PUT /api/readers/{id} 携带资料与状态', async () => {
    const input: ReaderUpdateInput = {
      name: '张三丰',
      phone: '13800000000',
      email: null,
      status: 'DISABLED',
    };
    await updateReader(5, input);
    expect(mockPut).toHaveBeenCalledWith('/api/readers/5', input);
  });

  it('AC-RDR-05 PUT /api/readers/{id}/password 只携带 newPassword', async () => {
    await resetReaderPassword(5, { newPassword: 'NewPass123' });
    expect(mockPut).toHaveBeenCalledWith('/api/readers/5/password', { newPassword: 'NewPass123' });
  });

  it('AC-RDR-03 DELETE /api/readers/{id}', async () => {
    await deleteReader(5);
    expect(mockDelete).toHaveBeenCalledWith('/api/readers/5');
  });

  it('AC-RDR-06 GET /api/readers/me', async () => {
    await getMyReaderProfile();
    expect(mockGet).toHaveBeenCalledWith('/api/readers/me');
  });
});

describe('读者详情页借阅列表（GET /api/loans?readerId）', () => {
  it('readerId 与 status/page/size/sort 一并传递', async () => {
    await listReaderLoans(5, { page: 0, size: 20 });
    expect(mockGet).toHaveBeenCalledWith('/api/loans', {
      readerId: 5,
      status: undefined,
      page: 0,
      size: 20,
      sort: undefined,
    });

    await listReaderLoans(5, { status: 'CURRENT', page: 1, size: 10, sort: 'dueDate,asc' });
    expect(mockGet).toHaveBeenLastCalledWith('/api/loans', {
      readerId: 5,
      status: 'CURRENT',
      page: 1,
      size: 10,
      sort: 'dueDate,asc',
    });
  });
});