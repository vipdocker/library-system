// AC-AUTH-09：redirect 参数只接受站内相对路径。
import { describe, expect, it } from 'vitest';
import { sanitizeRedirect } from '../redirect';

describe('AC-AUTH-09 sanitizeRedirect', () => {
  it('接受站内相对路径（含 query）', () => {
    expect(sanitizeRedirect('/books')).toBe('/books');
    expect(sanitizeRedirect('/my/loans?tab=current')).toBe('/my/loans?tab=current');
    expect(sanitizeRedirect('/admin/readers/3')).toBe('/admin/readers/3');
  });

  it('拒绝外部与协议相对地址，回退到 /', () => {
    expect(sanitizeRedirect('https://evil.example/x')).toBe('/');
    expect(sanitizeRedirect('//evil.example')).toBe('/');
    expect(sanitizeRedirect('/\\evil.example')).toBe('/');
    expect(sanitizeRedirect('javascript:alert(1)')).toBe('/');
    expect(sanitizeRedirect('books')).toBe('/');
  });

  it('空值回退到 /', () => {
    expect(sanitizeRedirect(null)).toBe('/');
    expect(sanitizeRedirect(undefined)).toBe('/');
    expect(sanitizeRedirect('')).toBe('/');
  });
});