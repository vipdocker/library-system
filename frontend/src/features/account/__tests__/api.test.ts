// AC-AUTH-10（契约接线）：account/api.ts 的方法、路径、请求体与第 8.4 节 PUT /api/auth/password 对应。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiPut } from '../../../api/client';
import { changePassword } from '../api';

vi.mock('../../../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../api/client')>();
  return { ...actual, apiPut: vi.fn() };
});

const mockPut = vi.mocked(apiPut);

beforeEach(() => {
  mockPut.mockResolvedValue(undefined);
});

describe('AC-AUTH-10 PUT /api/auth/password', () => {
  it('请求体为 {oldPassword, newPassword}，路径与方法与契约一致', async () => {
    await changePassword({ oldPassword: 'OldPass123', newPassword: 'NewPass123' });
    expect(mockPut).toHaveBeenCalledWith('/api/auth/password', {
      oldPassword: 'OldPass123',
      newPassword: 'NewPass123',
    });
  });

  it('204 响应解析为 undefined（成功无返回体）', async () => {
    await expect(
      changePassword({ oldPassword: 'OldPass123', newPassword: 'NewPass123' }),
    ).resolves.toBeUndefined();
  });
});