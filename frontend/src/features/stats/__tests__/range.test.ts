// AC-STAT-02（前端校验部分）：区间颠倒、跨度超限、格式非法；默认最近 30 天。
import { describe, expect, it } from 'vitest';
import dayjs from 'dayjs';
import { MAX_SPAN_DAYS, defaultRange, parseDate, validateRange } from '../range';

describe('AC-STAT-02 defaultRange', () => {
  it('默认最近 30 天，含今天（from = today-29）', () => {
    const { from, to } = defaultRange(30);
    expect(to).toBe(dayjs().format('YYYY-MM-DD'));
    expect(from).toBe(dayjs().subtract(29, 'day').format('YYYY-MM-DD'));
  });

  it('仪表盘用最近 7 天（from = today-6）', () => {
    const { from, to } = defaultRange(7);
    expect(dayjs(to).diff(dayjs(from), 'day')).toBe(6);
  });
});

describe('AC-STAT-02 validateRange', () => {
  it('合法区间返回 null', () => {
    expect(validateRange('2026-11-01', '2026-11-30')).toBeNull();
  });

  it('from 晚于 to：提示“开始日期不能晚于结束日期”', () => {
    expect(validateRange('2026-11-10', '2026-11-01')).toBe('开始日期不能晚于结束日期');
  });

  it('跨度恰为 366 天合法，367 天超限', () => {
    const from = '2025-11-01';
    const boundary = dayjs(from).add(MAX_SPAN_DAYS, 'day').format('YYYY-MM-DD');
    const over = dayjs(from).add(MAX_SPAN_DAYS + 1, 'day').format('YYYY-MM-DD');
    expect(validateRange(from, boundary)).toBeNull();
    expect(validateRange(from, over)).toBe('统计区间跨度不能超过 366 天');
  });

  it('同一天合法', () => {
    expect(validateRange('2026-11-17', '2026-11-17')).toBeNull();
  });

  it('格式非法（非 YYYY-MM-DD、不存在的日期）给出中文提示', () => {
    expect(validateRange('2026/11/01', '2026-11-30')).toBe('日期格式不正确，应为 YYYY-MM-DD');
    expect(validateRange('2026-02-30', '2026-11-30')).toBe('日期格式不正确，应为 YYYY-MM-DD');
    expect(validateRange('', '2026-11-30')).toBe('日期格式不正确，应为 YYYY-MM-DD');
  });
});

describe('AC-STAT-02 parseDate', () => {
  it('严格解析 YYYY-MM-DD；非法返回 null', () => {
    expect(parseDate('2026-11-17')?.format('YYYY-MM-DD')).toBe('2026-11-17');
    expect(parseDate('2026-13-01')).toBeNull();
    expect(parseDate('11-17-2026')).toBeNull();
    expect(parseDate(null)).toBeNull();
  });
});