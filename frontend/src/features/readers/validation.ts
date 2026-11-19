// 读者表单前端校验规则，与契约一致（solution-design 第 6 节字段约束、第 7 节口令策略、第 8.1 节入参约定）。
// 服务端仍会完整校验；这里只为即时反馈，不替代服务端（AC-UI-02）。
import type { FormRule } from 'antd';

/** 可选字符串入参：去首尾空白；空串按 null（第 8.1 节）。 */
export function trimToNull(value: string | null | undefined): string | null {
  if (value === null || value === undefined) return null;
  const trimmed = value.trim();
  return trimmed === '' ? null : trimmed;
}

/** 姓名：必填，1..50（reader.name）。 */
export const readerNameRules: FormRule[] = [
  { required: true, whitespace: true, message: '请输入姓名' },
  { max: 50, message: '姓名不能超过 50 个字符' },
];

/** 电话：可选，≤20，^[0-9+\- ]*$（reader.phone）。 */
export const readerPhoneRules: FormRule[] = [
  { max: 20, message: '电话不能超过 20 个字符' },
  { pattern: /^[0-9+\- ]*$/, message: '电话只能包含数字、+、- 和空格' },
];

/** 邮箱：可选，≤100，Email 格式（reader.email）。 */
export const readerEmailRules: FormRule[] = [
  { type: 'email', message: '邮箱格式不正确' },
  { max: 100, message: '邮箱不能超过 100 个字符' },
];

/** 用户名：^[A-Za-z0-9_.-]{3,32}$（app_user.username）。 */
export const readerUsernameRules: FormRule[] = [
  { required: true, whitespace: true, message: '请输入用户名' },
  { pattern: /^[A-Za-z0-9_.-]{3,32}$/, message: '用户名为 3–32 位字母、数字、_、. 或 -' },
];

/** 口令策略：8–64 位，至少包含一个字母和一个数字（第 7 节）。 */
export function passwordRules(label: string): FormRule[] {
  return [
    { required: true, message: `请输入${label}` },
    { min: 8, max: 64, message: '口令长度必须为 8–64 位' },
    { pattern: /(?=.*[A-Za-z])(?=.*\d)/, message: '口令必须同时包含字母和数字' },
  ];
}