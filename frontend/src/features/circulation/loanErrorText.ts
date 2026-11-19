// 借 / 还 / 续借失败时的展示文案：统一走 errors.ts 的错误码 → 中文映射（AC-UI-03，设计 10.2 节）。
// LOAN_LIMIT_EXCEEDED 附带服务端 details（limit / current），帮助柜台操作员理解上限（D1：5 本）。
import { ApiError } from '../../api/client';
import { errorMessage } from '../../api/errors';

export function loanErrorText(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return errorMessage('NETWORK_ERROR');
  }
  const base = errorMessage(error.code);
  if (error.code === 'LOAN_LIMIT_EXCEEDED') {
    const limit = error.details.limit;
    const current = error.details.current;
    if (typeof limit === 'number' && typeof current === 'number') {
      return `${base}（上限 ${limit} 本，当前在借 ${current} 本）`;
    }
  }
  return base;
}