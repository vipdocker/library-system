import { describe, expect, it, vi } from 'vitest';
import { apiGet, apiPut } from '../../../api/client';
import { getProxySettings, saveProxySettings } from '../api';
vi.mock('../../../api/client', () => ({ apiGet: vi.fn(), apiPut: vi.fn() }));
describe('AC-FE-04 proxy API contract', () => {
  it('same-origin GET consumes signal; PUT sends only enabled/host/port', async () => {
    const signal = new AbortController().signal;
    await getProxySettings(signal);
    expect(apiGet).toHaveBeenCalledWith('/api/admin/settings/proxy', undefined, { signal });
    await saveProxySettings({ enabled: false, host: 'localhost', port: 9999 });
    expect(apiPut).toHaveBeenCalledWith('/api/admin/settings/proxy', { enabled: false, host: 'localhost', port: 9999 });
  });
});
