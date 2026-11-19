// AC-AUTH-10（UI）、AC-UI-02：修改口令页的前端校验、确认不一致拦截、400 文案映射、成功提示、提交中禁用。
// 契约来源：docs/design/solution-design.md 第 7 / 8.4 / 10.2 节（design-v0.1，D4①）。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App as AntdApp } from 'antd';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '../../../api/client';
import { changePassword } from '../api';
import ChangePasswordPage from '../pages/ChangePasswordPage';

vi.mock('../api', () => ({
  changePassword: vi.fn(),
}));

const mockChange = vi.mocked(changePassword);

beforeEach(() => {
  mockChange.mockReset();
  mockChange.mockResolvedValue(undefined);
});

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AntdApp>
        <ChangePasswordPage />
      </AntdApp>
    </QueryClientProvider>,
  );
}

async function fillAndSubmit(oldPassword: string, newPassword: string, confirmPassword: string) {
  const user = userEvent.setup();
  renderPage();
  if (oldPassword) await user.type(screen.getByLabelText('旧口令'), oldPassword);
  if (newPassword) await user.type(screen.getByLabelText('新口令'), newPassword);
  if (confirmPassword) await user.type(screen.getByLabelText('确认新口令'), confirmPassword);
  await user.click(screen.getByRole('button', { name: '修改口令' }));
  return user;
}

describe('AC-AUTH-10 修改口令页', () => {
  it('合法输入提交 PUT /api/auth/password，成功提示并清空表单', async () => {
    await fillAndSubmit('OldPass123', 'NewPass123', 'NewPass123');
    await waitFor(() =>
      expect(mockChange).toHaveBeenCalledWith({
        oldPassword: 'OldPass123',
        newPassword: 'NewPass123',
      }),
    );
    expect(
      await screen.findByText('口令已修改，当前登录保持有效，下次登录请使用新口令'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('旧口令')).toHaveValue('');
    expect(screen.getByLabelText('新口令')).toHaveValue('');
    expect(screen.getByLabelText('确认新口令')).toHaveValue('');
  });

  it('AC-UI-02 两次输入的新口令不一致时拦截，不发请求', async () => {
    await fillAndSubmit('OldPass123', 'NewPass123', 'OtherPass123');
    expect(await screen.findByText('两次输入的口令不一致')).toBeInTheDocument();
    expect(mockChange).not.toHaveBeenCalled();
  });

  it('AC-UI-02 新口令缺数字时按口令策略拦截，不发请求', async () => {
    await fillAndSubmit('OldPass123', 'abcdefgh', 'abcdefgh');
    expect(await screen.findByText('口令必须同时包含字母和数字')).toBeInTheDocument();
    expect(mockChange).not.toHaveBeenCalled();
  });

  it('AC-UI-02 新口令过短时按口令策略拦截，不发请求', async () => {
    await fillAndSubmit('OldPass123', 'Ab1', 'Ab1');
    expect(await screen.findByText('口令长度必须为 8–64 位')).toBeInTheDocument();
    expect(mockChange).not.toHaveBeenCalled();
  });

  it('必填项为空时提示必填，不发请求', async () => {
    await fillAndSubmit('', '', '');
    expect(await screen.findByText('请输入旧口令')).toBeInTheDocument();
    expect(screen.getByText('请输入新口令')).toBeInTheDocument();
    expect(screen.getByText('请再次输入新口令')).toBeInTheDocument();
    expect(mockChange).not.toHaveBeenCalled();
  });

  it('400 AUTH_PASSWORD_MISMATCH 显示"旧密码不正确"，表单不清空', async () => {
    mockChange.mockRejectedValue(new ApiError(400, 'AUTH_PASSWORD_MISMATCH', '旧口令错误'));
    await fillAndSubmit('WrongOld123', 'NewPass123', 'NewPass123');
    expect(await screen.findByText('旧密码不正确')).toBeInTheDocument();
    expect(screen.getByLabelText('旧口令')).toHaveValue('WrongOld123');
  });

  it('AC-UI-02 400 VALIDATION_FAILED 的 fieldErrors 映射到对应表单项', async () => {
    mockChange.mockRejectedValue(
      new ApiError(400, 'VALIDATION_FAILED', '请求参数校验失败', [
        { field: 'newPassword', message: '口令必须为 8–64 位，且至少包含一个字母和一个数字' },
      ]),
    );
    await fillAndSubmit('OldPass123', 'NewPass123', 'NewPass123');
    expect(
      await screen.findByText('口令必须为 8–64 位，且至少包含一个字母和一个数字'),
    ).toBeInTheDocument();
    // 全部映射成功时不显示表单级 Alert
    expect(screen.queryByText('请求参数不合法，请检查后重试')).not.toBeInTheDocument();
  });

  it('网络异常显示通用中文文案', async () => {
    mockChange.mockRejectedValue(new Error('socket hang up'));
    await fillAndSubmit('OldPass123', 'NewPass123', 'NewPass123');
    expect(await screen.findByText('网络异常，请检查连接后重试')).toBeInTheDocument();
  });

  it('提交中按钮 loading 并禁用，重复点击不重复提交', async () => {
    let resolveMutation: (() => void) | undefined;
    mockChange.mockReturnValue(
      new Promise<void>((resolve) => {
        resolveMutation = () => resolve();
      }),
    );
    const user = await fillAndSubmit('OldPass123', 'NewPass123', 'NewPass123');
    // antd loading 态的可访问名会带上 loading 图标前缀，用正则匹配
    const button = screen.getByRole('button', { name: /修改口令/ });
    await waitFor(() => expect(button).toBeDisabled());
    expect(screen.getByLabelText('旧口令')).toBeDisabled();
    await user.click(button);
    expect(mockChange).toHaveBeenCalledTimes(1);
    resolveMutation?.();
    expect(
      await screen.findByText('口令已修改，当前登录保持有效，下次登录请使用新口令'),
    ).toBeInTheDocument();
    await waitFor(() => expect(button).toBeEnabled());
  });
});