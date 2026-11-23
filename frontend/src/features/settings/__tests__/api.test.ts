import { describe, expect, it, vi } from 'vitest';
import { apiGet, apiPut } from '../../../api/client';
import { getProxySettings, saveProxySettings, getShowApiSettings, saveShowApiSettings, showApiSettingsKey } from '../api';
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
describe('AC-S4/T2 ShowAPI settings API contract', () => {
  it('GET reads status only, with signal; key is a stable non-secret query key', async () => {
    const signal = new AbortController().signal;
    await getShowApiSettings(signal);
    expect(apiGet).toHaveBeenCalledWith('/api/admin/settings/showapi', undefined, { signal });
    expect(showApiSettingsKey).toEqual(['settings', 'showapi']);
  });
  it('KEEP omits appKey entirely (not even null)', async () => {
    await saveShowApiSettings({ enabled: true, appKeyAction: 'KEEP', expectedRevision: 3 });
    expect(apiPut).toHaveBeenCalledWith('/api/admin/settings/showapi', { enabled: true, appKeyAction: 'KEEP', expectedRevision: 3 });
    const body = vi.mocked(apiPut).mock.calls[0][1] as Record<string, unknown>;
    expect('appKey' in body).toBe(false);
  });
  it('CLEAR omits appKey entirely and keeps enabled=false', async () => {
    await saveShowApiSettings({ enabled: false, appKeyAction: 'CLEAR', expectedRevision: 4 });
    const body = vi.mocked(apiPut).mock.calls[0][1] as Record<string, unknown>;
    expect(body).toEqual({ enabled: false, appKeyAction: 'CLEAR', expectedRevision: 4 });
    expect('appKey' in body).toBe(false);
  });
  it('REPLACE carries the key and expectedRevision, no extra fields', async () => {
    await saveShowApiSettings({ enabled: true, appKeyAction: 'REPLACE', appKey: 'FAKE-KEY-FOR-TEST', expectedRevision: 0 });
    expect(apiPut).toHaveBeenCalledWith('/api/admin/settings/showapi', { enabled: true, appKeyAction: 'REPLACE', appKey: 'FAKE-KEY-FOR-TEST', expectedRevision: 0 });
  });
});