// circulation 接口函数契约测试（方案 8.3 / 8.4 节）：路径、方法、参数与返回类型。
// mock 共享 client（只读，T02 产出），不发起真实请求。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiGet, apiPost } from '../../../api/client';
import type { Loan, Reader } from '../../../api/types';
import {
  DEFAULT_LOAN_QUERY,
  borrowLoan,
  findReaderByCardNo,
  listLoans,
  listMyLoans,
  renewLoan,
  returnLoan,
  returnLoanByBarcode,
  searchReaders,
} from '../api';
import { makeLoan, makePage, readerFixture } from './testUtils';

vi.mock('../../../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/client')>();
  return { ...actual, apiGet: vi.fn(), apiPost: vi.fn() };
});

const mockGet = vi.mocked(apiGet);
const mockPost = vi.mocked(apiPost);

beforeEach(() => {
  vi.clearAllMocks();
});

describe('AC-UI-04 借书接口', () => {
  it('borrowLoan 发送 POST /api/loans，请求体为 {readerCardNo, copyBarcode}', async () => {
    const loan = makeLoan();
    mockPost.mockResolvedValueOnce(loan);
    const result = await borrowLoan({ readerCardNo: 'R000001', copyBarcode: 'C00000011' });
    expect(mockPost).toHaveBeenCalledWith('/api/loans', {
      readerCardNo: 'R000001',
      copyBarcode: 'C00000011',
    });
    expect(result).toBe(loan);
  });
});

describe('AC-LOAN-04 还书接口', () => {
  it('returnLoan 发送 POST /api/loans/{id}/return', async () => {
    const returned = makeLoan({ status: 'RETURNED', returnDate: '2026-11-18' });
    mockPost.mockResolvedValueOnce(returned);
    const result = await returnLoan(101);
    expect(mockPost).toHaveBeenCalledWith('/api/loans/101/return');
    expect(result.status).toBe('RETURNED');
  });

  it('returnLoanByBarcode 发送 POST /api/loans/return-by-barcode，请求体为 {copyBarcode}', async () => {
    mockPost.mockResolvedValueOnce(makeLoan({ status: 'RETURNED' }));
    await returnLoanByBarcode({ copyBarcode: 'C00000011' });
    expect(mockPost).toHaveBeenCalledWith('/api/loans/return-by-barcode', {
      copyBarcode: 'C00000011',
    });
  });
});

describe('AC-LOAN-05 续借接口', () => {
  it('renewLoan 发送 POST /api/loans/{id}/renew', async () => {
    const renewed = makeLoan({ dueDate: '2026-12-31', renewCount: 1 });
    mockPost.mockResolvedValueOnce(renewed);
    const result = await renewLoan(101);
    expect(mockPost).toHaveBeenCalledWith('/api/loans/101/renew');
    expect(result.renewCount).toBe(1);
  });
});

describe('AC-LOAN-08 管理员借阅列表接口', () => {
  it('listLoans 发送 GET /api/loans，携带过滤、分页与排序参数，空白过滤项不发送', async () => {
    const page = makePage<Loan>([makeLoan()]);
    mockGet.mockResolvedValueOnce(page);
    await listLoans({
      readerId: 1,
      status: 'OVERDUE',
      copyBarcode: '  C00000011  ',
      q: '  活着  ',
      page: 2,
      size: 50,
      sort: 'dueDate,asc',
    });
    expect(mockGet).toHaveBeenCalledWith('/api/loans', {
      readerId: 1,
      status: 'OVERDUE',
      copyBarcode: 'C00000011',
      q: '活着',
      page: 2,
      size: 50,
      sort: 'dueDate,asc',
    });
  });

  it('listLoans 默认查询不带过滤项（undefined 由 client 忽略）', async () => {
    mockGet.mockResolvedValueOnce(makePage<Loan>([]));
    await listLoans(DEFAULT_LOAN_QUERY);
    expect(mockGet).toHaveBeenCalledWith('/api/loans', {
      readerId: undefined,
      status: undefined,
      copyBarcode: undefined,
      q: undefined,
      page: 0,
      size: 20,
      sort: 'borrowDate,desc',
    });
  });
});

describe('AC-LOAN-07 我的借阅接口', () => {
  it('listMyLoans 发送 GET /api/loans/me，只携带 status/page/size', async () => {
    mockGet.mockResolvedValueOnce(makePage<Loan>([makeLoan()]));
    await listMyLoans({ status: 'CURRENT', page: 0, size: 20 });
    expect(mockGet).toHaveBeenCalledWith('/api/loans/me', {
      status: 'CURRENT',
      page: 0,
      size: 20,
    });
  });
});

describe('AC-UI-04 柜台按卡号定位读者', () => {
  it('searchReaders 发送 GET /api/readers?q=...（page=0, size=20）', async () => {
    mockGet.mockResolvedValueOnce(makePage<Reader>([readerFixture]));
    await searchReaders(' 读者一 ');
    expect(mockGet).toHaveBeenCalledWith('/api/readers', { q: '读者一', page: 0, size: 20 });
  });

  it('findReaderByCardNo 在混合结果中按卡号精确匹配', async () => {
    const other: Reader = { ...readerFixture, id: 2, cardNo: 'R000002', name: 'R000001读者' };
    mockGet.mockResolvedValueOnce(makePage<Reader>([other, readerFixture]));
    const found = await findReaderByCardNo(' R000001 ');
    expect(mockGet).toHaveBeenCalledWith('/api/readers', { q: 'R000001', page: 0, size: 20 });
    expect(found.id).toBe(1);
  });

  it('findReaderByCardNo 无精确匹配时抛出 404 READER_NOT_FOUND', async () => {
    const other: Reader = { ...readerFixture, id: 2, cardNo: 'R000002' };
    mockGet.mockResolvedValueOnce(makePage<Reader>([other]));
    await expect(findReaderByCardNo('R000001')).rejects.toMatchObject({
      status: 404,
      code: 'READER_NOT_FOUND',
    });
  });

  it('findReaderByCardNo 抛出的错误是 ApiError 实例', async () => {
    mockGet.mockResolvedValueOnce(makePage<Reader>([]));
    await expect(findReaderByCardNo('R999999')).rejects.toBeInstanceOf(ApiError);
  });
});