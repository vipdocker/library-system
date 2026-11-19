// 服务端错误 → 表单反馈（AC-UI-02 / 第 10.2 节"表单校验"）。
// fieldErrors 映射到对应表单项；无法映射的错误（如 409 唯一冲突）返回中文文案用于 Alert。
// 与 catalog 的同名 helper 保持同一模式，但按写入边界各自维护，不跨 feature 引用。
import type { FormInstance } from 'antd';
import { ApiError } from '../../api/client';
import { errorMessage } from '../../api/errors';

export interface FieldErrorMapping {
  /** 已映射到表单项的字段名 */
  mappedFields: string[];
  /** 无法映射时的表单级中文文案；全部映射成功时为 null */
  formLevelMessage: string | null;
}

export function applyServerFieldErrors(
  form: FormInstance,
  error: unknown,
  knownFields: readonly string[],
): FieldErrorMapping {
  if (!(error instanceof ApiError)) {
    return { mappedFields: [], formLevelMessage: errorMessage('NETWORK_ERROR') };
  }
  const mapped: string[] = [];
  const unmapped: string[] = [];
  for (const fe of error.fieldErrors) {
    if (knownFields.includes(fe.field)) {
      form.setFields([{ name: fe.field, errors: [fe.message] }]);
      mapped.push(fe.field);
    } else {
      unmapped.push(fe.message);
    }
  }
  if (mapped.length > 0 && unmapped.length === 0) {
    return { mappedFields: mapped, formLevelMessage: null };
  }
  const codeMessage = errorMessage(error.code);
  const message = unmapped.length > 0 ? `${codeMessage}（${unmapped.join('；')}）` : codeMessage;
  return { mappedFields: mapped, formLevelMessage: message };
}

/** mutation 失败的统一中文文案（AC-UI-03：全部走 errors.ts 映射）。 */
export function apiErrorMessage(error: unknown): string {
  return error instanceof ApiError ? errorMessage(error.code) : errorMessage('NETWORK_ERROR');
}