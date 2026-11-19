// 统计区间的前端校验与默认值。规则来源：设计 8.4 节 GET /api/stats/loans
// （from > to 或跨度 > 366 天时服务端返回 400 VALIDATION_FAILED；默认最近 30 天）。
// 前端先校验，非法区间不发请求；跨度按 to - from 的自然日差计算（含两端共 367 个日期时差为 366，仍合法）。
import dayjs, { type Dayjs } from 'dayjs';
import customParseFormat from 'dayjs/plugin/customParseFormat';

dayjs.extend(customParseFormat);

export const DATE_FORMAT = 'YYYY-MM-DD';
export const MAX_SPAN_DAYS = 366;

export interface StatsRange {
  from: string;
  to: string;
}

/** 最近 days 天（含今天），如 days=30 → [today-29, today]。 */
export function defaultRange(days: number): StatsRange {
  const to = dayjs();
  return { from: to.subtract(days - 1, 'day').format(DATE_FORMAT), to: to.format(DATE_FORMAT) };
}

/** 严格按 YYYY-MM-DD 解析；非法（含溢出日期如 2026-02-30）返回 null。 */
export function parseDate(value: string | null | undefined): Dayjs | null {
  if (!value) return null;
  const d = dayjs(value, DATE_FORMAT, true);
  return d.isValid() && d.format(DATE_FORMAT) === value ? d : null;
}

/**
 * 校验区间，返回中文错误提示；合法返回 null。
 * 文案属于前端本地校验（设计允许“在前端先做校验”）；服务端 400 仍由 errors.ts 的
 * VALIDATION_FAILED 文案兜底显示。
 */
export function validateRange(from: string, to: string): string | null {
  const f = parseDate(from);
  const t = parseDate(to);
  if (!f || !t) return '日期格式不正确，应为 YYYY-MM-DD';
  if (f.isAfter(t, 'day')) return '开始日期不能晚于结束日期';
  if (t.diff(f, 'day') > MAX_SPAN_DAYS) return `统计区间跨度不能超过 ${MAX_SPAN_DAYS} 天`;
  return null;
}