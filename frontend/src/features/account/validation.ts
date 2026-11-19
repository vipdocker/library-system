// 修改口令表单前端校验规则，与后端一致（solution-design 第 7 节口令策略、第 8.4 节请求体）。
// 服务端仍会完整校验；这里只为即时反馈，不替代服务端（AC-UI-02 / 第 10.2 节"表单校验"）。
// 与 readers/validation.ts 的 passwordRules 保持同一规则，但按写入边界各自维护，不跨 feature 引用。
import type { FormRule } from 'antd';

/** 口令策略：8–64 位，至少包含一个字母和一个数字（第 7 节，与后端 PasswordPolicy 一致）。 */
export function passwordRules(label: string): FormRule[] {
  return [
    { required: true, message: `请输入${label}` },
    { min: 8, max: 64, message: '口令长度必须为 8–64 位' },
    { pattern: /(?=.*[A-Za-z])(?=.*\d)/, message: '口令必须同时包含字母和数字' },
  ];
}

/** 旧口令：只校验必填（凭据不做策略校验，与后端 LoginRequest/ChangePasswordRequest 一致）。 */
export const oldPasswordRules: FormRule[] = [{ required: true, message: '请输入旧口令' }];

/** 确认新口令：必填 + 与新口令一致。 */
export function confirmPasswordRules(): FormRule[] {
  return [
    { required: true, message: '请再次输入新口令' },
    ({ getFieldValue }) => ({
      validator(_, value: string) {
        if (!value || getFieldValue('newPassword') === value) {
          return Promise.resolve();
        }
        return Promise.reject(new Error('两次输入的口令不一致'));
      },
    }),
  ];
}