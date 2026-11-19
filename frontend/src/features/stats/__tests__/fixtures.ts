// 固定测试数据（DTO 与 src/api/types.ts / 设计 8.3 节一致）。
import dayjs from 'dayjs';
import type { LoanSeriesPoint, OverdueItem, Page, StatsOverview, TopBook } from '../../../api/types';

export const overviewFixture: StatsOverview = {
  totalBooks: 12,
  totalCopies: 28,
  availableCopies: 17,
  borrowedCopies: 9,
  unavailableCopies: 2,
  totalReaders: 36,
  activeReaders: 30,
  activeLoans: 9,
  overdueLoans: 3,
  borrowsToday: 4,
  returnsToday: 2,
};

export const zeroOverviewFixture: StatsOverview = {
  totalBooks: 0,
  totalCopies: 0,
  availableCopies: 0,
  borrowedCopies: 0,
  unavailableCopies: 0,
  totalReaders: 0,
  activeReaders: 0,
  activeLoans: 0,
  overdueLoans: 0,
  borrowsToday: 0,
  returnsToday: 0,
};

/** 最近 days 天的日序列（含今天），计数按固定模式生成，含一个全 0 周期。 */
export function makeSeries(days: number): LoanSeriesPoint[] {
  return Array.from({ length: days }, (_, i) => {
    const period = dayjs()
      .subtract(days - 1 - i, 'day')
      .format('YYYY-MM-DD');
    if (i === 1) return { period, borrowCount: 0, returnCount: 0 };
    return { period, borrowCount: (i % 5) + 1, returnCount: (i % 3) + 1 };
  });
}

export function makeZeroSeries(days: number): LoanSeriesPoint[] {
  return Array.from({ length: days }, (_, i) => ({
    period: dayjs()
      .subtract(days - 1 - i, 'day')
      .format('YYYY-MM-DD'),
    borrowCount: 0,
    returnCount: 0,
  }));
}

export function makeMonthSeries(months: number): LoanSeriesPoint[] {
  return Array.from({ length: months }, (_, i) => ({
    period: dayjs()
      .subtract(months - 1 - i, 'month')
      .format('YYYY-MM'),
    borrowCount: i + 2,
    returnCount: i + 1,
  }));
}

/** 服务端排序契约：borrowCount 降序，同次数按 title 升序（“百年孤独” < “算法导论”按拼音/Unicode 由服务端保证，这里按契约给出顺序）。 */
export const topBooksFixture: TopBook[] = [
  { bookId: 1, title: '深入理解计算机系统', author: 'Randal E. Bryant', isbn: '9787111544937', borrowCount: 9 },
  { bookId: 2, title: '百年孤独', author: '加西亚·马尔克斯', isbn: '9787544253994', borrowCount: 7 },
  { bookId: 3, title: '算法导论', author: 'Thomas H. Cormen', isbn: '9787111407010', borrowCount: 7 },
  { bookId: 4, title: '活着', author: '余华', isbn: '9787506365437', borrowCount: 2 },
];

export const overdueItemFixture: OverdueItem = {
  loanId: 101,
  reader: { id: 11, cardNo: 'R-00011', name: '张三', phone: '13800000001' },
  book: { id: 21, title: '百年孤独', isbn: '9787544253994' },
  copyBarcode: 'C-00021-01',
  borrowDate: '2026-10-08',
  dueDate: '2026-11-07',
  overdueDays: 10,
};

const overdueItem2: OverdueItem = {
  loanId: 102,
  reader: { id: 12, cardNo: 'R-00012', name: '李四', phone: null },
  book: { id: 22, title: '算法导论', isbn: '9787111407010' },
  copyBarcode: 'C-00022-01',
  borrowDate: '2026-10-14',
  dueDate: '2026-11-13',
  overdueDays: 4,
};

const overdueItemPage2: OverdueItem = {
  loanId: 121,
  reader: { id: 13, cardNo: 'R-00013', name: '王五', phone: '13800000003' },
  book: { id: 23, title: '活着', isbn: '9787506365437' },
  copyBarcode: 'C-00023-01',
  borrowDate: '2026-10-17',
  dueDate: '2026-11-16',
  overdueDays: 1,
};

/** 共 25 条、每页 20 条：第 0 页 2 条示例 + 第 1 页 1 条示例（totalItems 固定 25 以驱动分页控件）。 */
export function makeOverduePage(page: number, size: number): Page<OverdueItem> {
  const items = page === 0 ? [overdueItemFixture, overdueItem2] : [overdueItemPage2];
  return { items, page, size, totalItems: 25, totalPages: Math.ceil(25 / size) };
}

export function makeEmptyOverduePage(page: number, size: number): Page<OverdueItem> {
  return { items: [], page, size, totalItems: 0, totalPages: 0 };
}