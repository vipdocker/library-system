// 前端校验规则，与契约一致（solution-design 第 6 / 8.4 节，D4③ ISBN 严格校验位检查）。
// 服务端仍会完整校验；这里只为即时反馈，不替代服务端。

/** 规范化 ISBN：去掉空格与连字符，X 转大写（第 8.4 节检索/保存语义）。 */
export function normalizeIsbn(raw: string): string {
  return raw.replace(/[\s-]/g, '').toUpperCase();
}

function isbn10ChecksumOk(digits: string): boolean {
  if (!/^\d{9}[\dX]$/.test(digits)) return false;
  let sum = 0;
  for (let i = 0; i < 10; i += 1) {
    const ch = digits[i];
    const value = ch === 'X' ? 10 : Number(ch);
    sum += (10 - i) * value;
  }
  return sum % 11 === 0;
}

function isbn13ChecksumOk(digits: string): boolean {
  if (!/^\d{13}$/.test(digits)) return false;
  let sum = 0;
  for (let i = 0; i < 13; i += 1) {
    sum += Number(digits[i]) * (i % 2 === 0 ? 1 : 3);
  }
  return sum % 10 === 0;
}

/** 规范化后必须是合法 ISBN-10（末位可为 X）或 ISBN-13，并通过校验位检查。 */
export function isValidIsbn(raw: string): boolean {
  const value = normalizeIsbn(raw);
  return isbn10ChecksumOk(value) || isbn13ChecksumOk(value);
}

/** 可选字符串入参：去首尾空白；空串按 null（第 8.1 节）。 */
export function trimToNull(value: string | null | undefined): string | null {
  if (value === null || value === undefined) return null;
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}