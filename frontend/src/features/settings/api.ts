import { apiGet, apiPut } from '../../api/client';
import type { ProxySettings, ProxySettingsInput, ShowApiSettings, ShowApiSettingsInput } from '../../api/types';
export const proxySettingsKey = ['settings', 'proxy'] as const;
export function getProxySettings(signal?: AbortSignal): Promise<ProxySettings> {
  return apiGet('/api/admin/settings/proxy', undefined, { signal });
}
export function saveProxySettings(input: ProxySettingsInput): Promise<ProxySettings> {
  return apiPut('/api/admin/settings/proxy', { enabled: input.enabled, host: input.host, port: input.port });
}
export const showApiSettingsKey = ['settings', 'showapi'] as const;
/** GET 仅配置状态（enabled/appKeyConfigured/revision/updatedAt），绝不返回 AppKey。 */
export function getShowApiSettings(signal?: AbortSignal): Promise<ShowApiSettings> {
  return apiGet('/api/admin/settings/showapi', undefined, { signal });
}
/** KEEP/CLEAR 序列化时完全省略 appKey 字段（null 也不发）；仅 REPLACE 携带。 */
export function saveShowApiSettings(input: ShowApiSettingsInput): Promise<ShowApiSettings> {
  const body = input.appKeyAction === 'REPLACE'
    ? { enabled: input.enabled, appKeyAction: input.appKeyAction, appKey: input.appKey, expectedRevision: input.expectedRevision }
    : { enabled: input.enabled, appKeyAction: input.appKeyAction, expectedRevision: input.expectedRevision };
  return apiPut('/api/admin/settings/showapi', body);
}
