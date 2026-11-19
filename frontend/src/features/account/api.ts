// account feature 接口函数。契约来源：docs/design/solution-design.md 第 8.4 节（design-v0.1，D4① / AC-AUTH-10）。
// 共享 client / types 只读（T02 产出）；本文件只负责本人口令修改的路径与请求体。
import { apiPut } from '../../api/client';
import type { ChangePasswordRequest } from '../../api/types';

/** PUT /api/auth/password：修改本人口令，204，当前会话保留（AC-AUTH-10）。 */
export function changePassword(input: ChangePasswordRequest): Promise<void> {
  return apiPut<void>('/api/auth/password', input);
}