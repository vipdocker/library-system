// AC-BOOK-01（D4③）：ISBN 规范化与严格校验位；第 8.1 节可选字符串 trim→null。
import { describe, expect, it } from 'vitest';
import { isValidIsbn, normalizeIsbn, trimToNull } from '../validation';

describe('AC-BOOK-01 ISBN 规范化与校验', () => {
  it('去掉空格与连字符，X 转大写', () => {
    expect(normalizeIsbn('978-0-306-40615-7')).toBe('9780306406157');
    expect(normalizeIsbn(' 0-8044-2957-x ')).toBe('080442957X');
  });

  it('合法 ISBN-10 / ISBN-13（含 X 末位与连字符形式）通过', () => {
    expect(isValidIsbn('0306406152')).toBe(true);
    expect(isValidIsbn('0-8044-2957-X')).toBe(true);
    expect(isValidIsbn('080442957x')).toBe(true);
    expect(isValidIsbn('9780306406157')).toBe(true);
    expect(isValidIsbn('978-0-306-40615-7')).toBe(true);
    expect(isValidIsbn('978 0 306 40615 7')).toBe(true);
  });

  it('校验位错误、长度非法、含非法字符均拒绝', () => {
    expect(isValidIsbn('9780306406158')).toBe(false); // ISBN-13 校验位错
    expect(isValidIsbn('0306406153')).toBe(false); // ISBN-10 校验位错
    expect(isValidIsbn('1234567890')).toBe(false);
    expect(isValidIsbn('978030640615')).toBe(false); // 12 位
    expect(isValidIsbn('97803064061577')).toBe(false); // 14 位
    expect(isValidIsbn('978030640615A')).toBe(false);
    expect(isValidIsbn('')).toBe(false);
  });
});

describe('第 8.1 节可选字符串约定', () => {
  it('trimToNull：空白→null，非空去首尾空白', () => {
    expect(trimToNull('   ')).toBeNull();
    expect(trimToNull('')).toBeNull();
    expect(trimToNull(undefined)).toBeNull();
    expect(trimToNull(null)).toBeNull();
    expect(trimToNull(' 出版社 ')).toBe('出版社');
  });
});