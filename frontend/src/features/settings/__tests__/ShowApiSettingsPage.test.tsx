// AC-S4/T2（showapi-ac-dod-v1）：ShowAPI AppKey 系统设置。
// KEY 只短时存在于表单内存：成功清空、失败保留、绝不进入查询缓存/日志/回显。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderCatalog } from '../../catalog/__tests__/testUtils';
import { ApiError } from '../../../api/client';
import type { ShowApiSettings } from '../../../api/types';
import { getShowApiSettings, saveShowApiSettings, showApiSettingsKey } from '../api';
import ShowApiSettingsPage from '../pages/ShowApiSettingsPage';

vi.mock('../api', async (original) => ({ ...await original<typeof import('../api')>(), getShowApiSettings: vi.fn(), saveShowApiSettings: vi.fn() }));

const FAKE_KEY = 'FAKE-SHOWAPI-KEY-FOR-TEST-ONLY';
const unconfigured: ShowApiSettings = { enabled: false, appKeyConfigured: false, revision: 0, updatedAt: null };
const configured: ShowApiSettings = { enabled: false, appKeyConfigured: true, revision: 2, updatedAt: '2026-11-22T00:00:00Z' };
const saved: ShowApiSettings = { enabled: true, appKeyConfigured: true, revision: 3, updatedAt: '2026-11-23T00:00:00Z' };

beforeEach(() => {
  vi.mocked(getShowApiSettings).mockResolvedValue(unconfigured);
  vi.mocked(saveShowApiSettings).mockResolvedValue(saved);
});
function page() { return renderCatalog(<ShowApiSettingsPage />); }

describe('AC-S4 ShowAPI 设置加载与状态', () => {
  it('加载失败显示错误与手动重试；成功前无保存按钮', async () => {
    vi.mocked(getShowApiSettings).mockRejectedValueOnce(new ApiError(503, 'DB_BUSY', 'busy')).mockResolvedValue(unconfigured);
    page();
    expect(screen.queryByRole('button', { name: '保存配置' })).not.toBeInTheDocument();
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByLabelText('AppKey')).toHaveValue('');
    expect(screen.getByText('AppKey 状态：未配置')).toBeInTheDocument();
  });
  it('GET 仅状态：不回填 key，提示本机明文保存与备份风险，不声称加密', async () => {
    vi.mocked(getShowApiSettings).mockResolvedValue(configured);
    page();
    expect(await screen.findByText('AppKey 状态：已配置')).toBeInTheDocument();
    expect(screen.getByLabelText('AppKey')).toHaveValue('');
    expect(screen.getByText(/明文保存在本机 SQLite 数据库/)).toBeInTheDocument();
    expect(screen.getByText(/历史备份/)).toBeInTheDocument();
    expect(screen.getByText(/撤销/)).toBeInTheDocument();
    expect(screen.queryByText(/加密/)).not.toBeInTheDocument();
    expect(screen.getByText(/配置版本：2/)).toBeInTheDocument();
  });
});

describe('AC-S4/T2 KEEP / REPLACE / CLEAR 提交语义', () => {
  it('KEEP 完全不携带 appKey（null 也不发），成功后输入清空', async () => {
    vi.mocked(getShowApiSettings).mockResolvedValue(configured);
    page(); await screen.findByLabelText('AppKey');
    await userEvent.click(screen.getByLabelText('启用 ShowAPI'));
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    await waitFor(() => expect(saveShowApiSettings).toHaveBeenCalledTimes(1));
    const input = vi.mocked(saveShowApiSettings).mock.calls[0][0];
    expect(input).toEqual({ enabled: true, appKeyAction: 'KEEP', expectedRevision: 2 });
    expect('appKey' in input).toBe(false);
    expect(await screen.findByText(/配置版本：3/)).toBeInTheDocument();
    expect(screen.getByLabelText('AppKey')).toHaveValue('');
  });
  it('REPLACE 携带去空白的新 key；成功后清空输入与操作选择，key 不进入任何查询缓存', async () => {
    const view = page(); await screen.findByLabelText('AppKey');
    await userEvent.click(screen.getByRole('radio', { name: '替换' }));
    await userEvent.type(screen.getByLabelText('AppKey'), `  ${FAKE_KEY}  `);
    await userEvent.click(screen.getByLabelText('启用 ShowAPI'));
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    await waitFor(() => expect(saveShowApiSettings).toHaveBeenCalledWith({ enabled: true, appKeyAction: 'REPLACE', appKey: FAKE_KEY, expectedRevision: 0 }));
    await waitFor(() => expect(screen.getByLabelText('AppKey')).toHaveValue(''));
    expect(screen.getByRole('radio', { name: '保留现有' })).toBeChecked();
    const cached = JSON.stringify(view.client.getQueryCache().getAll().map((q) => q.state.data));
    expect(cached).not.toContain(FAKE_KEY);
    expect(view.client.getQueryData(showApiSettingsKey)).toEqual(saved);
  });
  it('CLEAR 仅在停用时可选；提交完全省略 appKey', async () => {
    vi.mocked(getShowApiSettings).mockResolvedValue({ ...configured, enabled: true });
    vi.mocked(saveShowApiSettings).mockResolvedValue({ enabled: false, appKeyConfigured: false, revision: 3, updatedAt: '2026-11-23T00:00:00Z' });
    page(); await screen.findByLabelText('AppKey');
    expect(screen.getByRole('radio', { name: '清除' })).toBeDisabled();
    await userEvent.click(screen.getByLabelText('启用 ShowAPI'));
    await userEvent.click(screen.getByRole('radio', { name: '清除' }));
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    await waitFor(() => expect(saveShowApiSettings).toHaveBeenCalledWith({ enabled: false, appKeyAction: 'CLEAR', expectedRevision: 2 }));
    const input = vi.mocked(saveShowApiSettings).mock.calls[0][0];
    expect('appKey' in input).toBe(false);
    expect(await screen.findByText('AppKey 状态：未配置')).toBeInTheDocument();
  });
  it('启用但既无已配置 key 也未选择替换时阻止提交', async () => {
    page(); await screen.findByLabelText('AppKey');
    await userEvent.click(screen.getByLabelText('启用 ShowAPI'));
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    expect(await screen.findByText(/启用 ShowAPI 需要已配置的 AppKey 或输入新 AppKey/)).toBeInTheDocument();
    expect(saveShowApiSettings).not.toHaveBeenCalled();
  });
  it('REPLACE 空白或超长 key 在前端被拦截', async () => {
    page(); await screen.findByLabelText('AppKey');
    await userEvent.click(screen.getByRole('radio', { name: '替换' }));
    await userEvent.type(screen.getByLabelText('AppKey'), '   ');
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    expect(await screen.findByText(/1 至 512 个字符/)).toBeInTheDocument();
    expect(saveShowApiSettings).not.toHaveBeenCalled();
    await userEvent.clear(screen.getByLabelText('AppKey'));
    fireEvent.change(screen.getByLabelText('AppKey'), { target: { value: 'x'.repeat(513) } });
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    expect(await screen.findByText(/1 至 512 个字符/)).toBeInTheDocument();
    expect(saveShowApiSettings).not.toHaveBeenCalled();
  });
});

describe('AC-S4 冲突与未知结果恢复', () => {
  it('409 SETTINGS_CONFLICT 显示服务器版本，保留输入，不自动重发；人工重新读取后可再保存', async () => {
    vi.mocked(getShowApiSettings).mockResolvedValue(configured);
    vi.mocked(saveShowApiSettings).mockRejectedValueOnce(new ApiError(409, 'SETTINGS_CONFLICT', 'conflict', [], { currentRevision: 7 }));
    page(); await screen.findByLabelText('AppKey');
    await userEvent.click(screen.getByRole('radio', { name: '替换' }));
    await userEvent.type(screen.getByLabelText('AppKey'), FAKE_KEY);
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    expect(await screen.findByText(/服务器版本 7/)).toBeInTheDocument();
    expect(screen.getByLabelText('AppKey')).toHaveValue(FAKE_KEY);
    expect(saveShowApiSettings).toHaveBeenCalledTimes(1);
    vi.mocked(getShowApiSettings).mockResolvedValue({ ...configured, revision: 7 });
    vi.mocked(saveShowApiSettings).mockResolvedValue({ ...saved, revision: 8 });
    await userEvent.click(screen.getByRole('button', { name: '重新读取核对' }));
    expect(await screen.findByText(/服务器当前：.*版本 7/)).toBeInTheDocument();
    expect(saveShowApiSettings).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    await waitFor(() => expect(saveShowApiSettings).toHaveBeenCalledTimes(2));
    expect(vi.mocked(saveShowApiSettings).mock.calls[1][0]).toMatchObject({ expectedRevision: 7, appKey: FAKE_KEY });
  });
  it('网络未知结果保留 key 输入，不自动重发；重新读取仅提示状态', async () => {
    vi.mocked(saveShowApiSettings).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', 'disconnected'));
    page(); await screen.findByLabelText('AppKey');
    await userEvent.click(screen.getByRole('radio', { name: '替换' }));
    await userEvent.type(screen.getByLabelText('AppKey'), FAKE_KEY);
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    expect(await screen.findByText(/保存结果未知/)).toBeInTheDocument();
    expect(screen.getByLabelText('AppKey')).toHaveValue(FAKE_KEY);
    expect(saveShowApiSettings).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: '重新读取核对' }));
    expect(await screen.findByText(/输入仍保留/)).toBeInTheDocument();
    expect(saveShowApiSettings).toHaveBeenCalledTimes(1);
  });
  it('保存期间表单禁用，同步锁阻止重复提交', async () => {
    vi.mocked(saveShowApiSettings).mockReturnValue(new Promise(() => {}));
    page(); await screen.findByLabelText('AppKey');
    await userEvent.click(screen.getByRole('radio', { name: '替换' }));
    await userEvent.type(screen.getByLabelText('AppKey'), FAKE_KEY);
    const button = screen.getByRole('button', { name: '保存配置' });
    fireEvent.click(button); fireEvent.click(button);
    await waitFor(() => expect(saveShowApiSettings).toHaveBeenCalledTimes(1));
    expect(button).toBeDisabled();
    expect(screen.getByLabelText('AppKey')).toBeDisabled();
  });
  it('服务端字段错误映射到表单项且不清空输入', async () => {
    vi.mocked(saveShowApiSettings).mockRejectedValue(new ApiError(400, 'VALIDATION_FAILED', 'invalid', [{ field: 'appKey', message: 'AppKey 去除首尾空白后须为 1 至 512 个字符' }]));
    page(); await screen.findByLabelText('AppKey');
    await userEvent.click(screen.getByRole('radio', { name: '替换' }));
    await userEvent.type(screen.getByLabelText('AppKey'), FAKE_KEY);
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    expect(await screen.findByText('AppKey 去除首尾空白后须为 1 至 512 个字符')).toBeInTheDocument();
    expect(screen.getByLabelText('AppKey')).toHaveValue(FAKE_KEY);
  });
});