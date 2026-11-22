import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderCatalog } from '../../catalog/__tests__/testUtils';
import { ApiError } from '../../../api/client';
import { getProxySettings, saveProxySettings, proxySettingsKey } from '../api';
import ProxySettingsPage from '../pages/ProxySettingsPage';
vi.mock('../api', async (original) => ({ ...await original<typeof import('../api')>(), getProxySettings: vi.fn(), saveProxySettings: vi.fn() }));
const initial = { enabled: false, host: null, port: null, revision: 0, updatedAt: null };
beforeEach(() => {
  vi.mocked(getProxySettings).mockResolvedValue(initial);
  vi.mocked(saveProxySettings).mockResolvedValue({ ...initial, host: 'localhost', port: 9999, revision: 1, updatedAt: '2026-11-22T00:00:00Z' });
});
function page() { return renderCatalog(<ProxySettingsPage />); }
describe('AC-FE-04 proxy settings form', () => {
  it('cannot save before successful load; failure page has manual retry', async () => {
    vi.mocked(getProxySettings).mockRejectedValueOnce(new ApiError(503, 'DB_BUSY', 'busy')).mockResolvedValue(initial);
    page();
    expect(screen.queryByRole('button', { name: /保存配置/ })).not.toBeInTheDocument();
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByLabelText('代理主机')).toHaveValue('');
    expect(screen.getByLabelText('启用备用代理')).not.toBeChecked();
  });
  it('enable needs complete pair; invalid or fractional text ports are rejected rather than rounded', async () => {
    page(); await screen.findByLabelText('代理主机');
    await userEvent.click(screen.getByLabelText('启用备用代理'));
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    expect(await screen.findByText('请输入完整代理主机')).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('代理主机'), 'localhost');
    await userEvent.type(screen.getByLabelText('代理端口'), '9999.5');
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    expect(await screen.findByText('端口须为 1 至 65535 的整数')).toBeInTheDocument();
    expect(saveProxySettings).not.toHaveBeenCalled();
  });
  it.each(['0', '65536', '1e3', '-1', 'abc'])('port %s rejected', async (port) => {
    page(); await screen.findByLabelText('代理主机');
    await userEvent.type(screen.getByLabelText('代理主机'), 'localhost');
    fireEvent.change(screen.getByLabelText('代理端口'), { target: { value: port } });
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    expect(await screen.findByText('端口须为 1 至 65535 的整数')).toBeInTheDocument();
    expect(saveProxySettings).not.toHaveBeenCalled();
  });
  it('disabled address can still be edited and preserved; normalized response rebuilds baseline and cache', async () => {
    const view = page(); await screen.findByLabelText('代理主机');
    await userEvent.type(screen.getByLabelText('代理主机'), ' LOCALHOST ');
    await userEvent.type(screen.getByLabelText('代理端口'), '9999');
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    await waitFor(() => expect(saveProxySettings).toHaveBeenCalledWith({ enabled: false, host: 'LOCALHOST', port: 9999 }));
    await waitFor(() => expect(screen.getByLabelText('代理主机')).toHaveValue('localhost'));
    expect(screen.getByText(/配置版本：1/)).toBeInTheDocument();
    expect(view.client.getQueryData(proxySettingsKey)).toMatchObject({ revision: 1 });
  });
  it('disabled accepts both empty fields, never injects placeholder defaults', async () => {
    page(); await screen.findByLabelText('代理主机');
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    await waitFor(() => expect(saveProxySettings).toHaveBeenCalledWith({ enabled: false, host: null, port: null }));
  });
  it('failed save keeps draft and attaches server field errors', async () => {
    vi.mocked(saveProxySettings).mockRejectedValue(new ApiError(400, 'VALIDATION_FAILED', 'invalid', [{ field: 'host', message: '代理主机地址不合法' }]));
    page(); await screen.findByLabelText('代理主机');
    await userEvent.type(screen.getByLabelText('代理主机'), 'bad/host');
    await userEvent.type(screen.getByLabelText('代理端口'), '9999');
    await userEvent.click(screen.getByRole('button', { name: '保存配置' }));
    expect(await screen.findByText('代理主机地址不合法')).toBeInTheDocument();
    expect(screen.getByLabelText('代理主机')).toHaveValue('bad/host');
  });
  it('dirty refetch preserves inputs; synchronous pending lock blocks double save and editing', async () => {
    vi.mocked(saveProxySettings).mockReturnValue(new Promise(() => {}));
    const view = page(); await screen.findByLabelText('代理主机');
    await userEvent.type(screen.getByLabelText('代理主机'), 'localhost');
    await userEvent.type(screen.getByLabelText('代理端口'), '9999');
    vi.mocked(getProxySettings).mockResolvedValue({ ...initial, host: 'elsewhere', port: 1234, revision: 2 });
    await view.client.invalidateQueries({ queryKey: proxySettingsKey });
    expect(screen.getByLabelText('代理主机')).toHaveValue('localhost');
    const button = screen.getByRole('button', { name: '保存配置' });
    fireEvent.click(button); fireEvent.click(button);
    await waitFor(() => expect(saveProxySettings).toHaveBeenCalledTimes(1));
    expect(button).toBeDisabled();
    expect(screen.getByRole('button', { name: '保存配置' })).toBe(button);
    expect(screen.getByLabelText('代理主机')).toBeDisabled();
    expect(screen.getByLabelText('启用备用代理')).toBeDisabled();
  });
});
