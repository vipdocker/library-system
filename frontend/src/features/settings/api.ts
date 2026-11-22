import { apiGet, apiPut } from '../../api/client';
import type { ProxySettings, ProxySettingsInput } from '../../api/types';
export const proxySettingsKey = ['settings', 'proxy'] as const;
export function getProxySettings(signal?: AbortSignal): Promise<ProxySettings> {
  return apiGet('/api/admin/settings/proxy', undefined, { signal });
}
export function saveProxySettings(input: ProxySettingsInput): Promise<ProxySettings> {
  return apiPut('/api/admin/settings/proxy', { enabled: input.enabled, host: input.host, port: input.port });
}
