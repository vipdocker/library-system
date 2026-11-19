// AC-UI-03：errors.ts 必须覆盖设计 8.2 节的全部错误码（逐项比对），外加 NETWORK_ERROR。
import { describe, expect, it } from 'vitest';
import { ERROR_CODES, ERROR_MESSAGES, errorMessage } from '../errors';

// 与 docs/design/solution-design.md 第 8.2 节 ErrorCode 表逐项对应（35 项，design-v0.1-R1）+ NETWORK_ERROR。
// R1（approval-record §1.1，I-02）：新增 METHOD_NOT_ALLOWED（405），本清单与快照同步修订（T13 披露）。
const DESIGN_ERROR_CODES = [
  'VALIDATION_FAILED',
  'AUTH_REQUIRED',
  'AUTH_INVALID_CREDENTIALS',
  'AUTH_ACCOUNT_DISABLED',
  'AUTH_PASSWORD_MISMATCH',
  'FORBIDDEN',
  'CSRF_INVALID',
  'CATEGORY_NOT_FOUND',
  'BOOK_NOT_FOUND',
  'COPY_NOT_FOUND',
  'READER_NOT_FOUND',
  'LOAN_NOT_FOUND',
  'ACTIVE_LOAN_NOT_FOUND',
  'READER_PROFILE_NOT_FOUND',
  'CATEGORY_NAME_DUPLICATE',
  'ISBN_DUPLICATE',
  'USERNAME_DUPLICATE',
  'CATEGORY_IN_USE',
  'BOOK_HAS_ACTIVE_LOANS',
  'BOOK_HAS_LOAN_HISTORY',
  'COPY_ON_LOAN',
  'COPY_HAS_LOAN_HISTORY',
  'READER_HAS_ACTIVE_LOANS',
  'READER_HAS_LOAN_HISTORY',
  'READER_DISABLED',
  'READER_HAS_OVERDUE',
  'LOAN_LIMIT_EXCEEDED',
  'DUPLICATE_BOOK_LOAN',
  'COPY_NOT_AVAILABLE',
  'LOAN_ALREADY_RETURNED',
  'LOAN_OVERDUE',
  'RENEW_LIMIT_REACHED',
  'DB_BUSY',
  'METHOD_NOT_ALLOWED',
  'INTERNAL_ERROR',
] as const;

describe('AC-UI-03 errors.ts 错误码完整性', () => {
  it('ERROR_CODES 与设计 8.2 清单 + NETWORK_ERROR 完全一致（不多不少）', () => {
    expect([...ERROR_CODES].sort()).toEqual([...DESIGN_ERROR_CODES, 'NETWORK_ERROR'].sort());
  });

  it('ERROR_MESSAGES 的键与 ERROR_CODES 一致', () => {
    expect(Object.keys(ERROR_MESSAGES).sort()).toEqual([...ERROR_CODES].sort());
  });

  it('每个错误码都有非空中文文案', () => {
    for (const code of ERROR_CODES) {
      const message = ERROR_MESSAGES[code];
      expect(message, code).toBeTruthy();
      expect(message, code).toMatch(/[\u4e00-\u9fff]/);
    }
  });

  it('503 DB_BUSY 文案为"系统繁忙"提示', () => {
    expect(ERROR_MESSAGES.DB_BUSY).toContain('系统繁忙');
  });

  it('errorMessage 对未知码回退通用提示', () => {
    expect(errorMessage('SOMETHING_NEW')).toBe('操作失败，请稍后再试');
    expect(errorMessage(undefined)).toBe('操作失败，请稍后再试');
    expect(errorMessage('FORBIDDEN')).toBe(ERROR_MESSAGES.FORBIDDEN);
  });

  it('文案快照（修订需契约依据并披露）', () => {
    expect(ERROR_MESSAGES).toMatchSnapshot();
  });
});