/**
 * 登录成功后的回跳地址清洗（AC-AUTH-09）：只接受站内相对路径。
 * 拒绝：空值、不以 / 开头、协议相对（//host）、含反斜杠（/\host、浏览器会归一化为 //）。
 */
export function sanitizeRedirect(raw: string | null | undefined): string {
  if (!raw) return '/';
  if (!raw.startsWith('/') || raw.startsWith('//') || raw.includes('\\')) return '/';
  return raw;
}