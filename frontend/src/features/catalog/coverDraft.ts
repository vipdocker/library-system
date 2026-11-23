import type { BookSource, BookSourceProvider, CoverChange, CoverMediaType, OpenLibraryLookup } from '../../api/types';

export type CoverDraft = { action: 'KEEP' } | { action: 'REMOVE' } | {
  action: 'REPLACE'; blob: Blob; mediaType: CoverMediaType; base64: string; sourceIsbn: string | null; source?: BookSource;
};
export const MAX_COVER_BYTES = 2 * 1024 * 1024;
export function coverPayload(draft: CoverDraft): CoverChange | undefined {
  if (draft.action === 'KEEP') return undefined;
  if (draft.action === 'REMOVE') return { action: 'REMOVE' };
  // 新通用 source 与旧 sourceIsbn 互斥：ShowAPI 只发 source，OL/本地保留旧字段形状。
  if (draft.source) return { action: 'REPLACE', mediaType: draft.mediaType, base64: draft.base64, source: draft.source };
  return { action: 'REPLACE', mediaType: draft.mediaType, base64: draft.base64, sourceIsbn: draft.sourceIsbn };
}
export function readBlobBytes(blob: Blob): Promise<Uint8Array<ArrayBuffer>> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('图片读取失败'));
    reader.onload = () => resolve(new Uint8Array(reader.result as ArrayBuffer));
    reader.readAsArrayBuffer(blob);
  });
}
function dimensionsOk(width: number, height: number) {
  if (width < 1 || height < 1 || width > 4096 || height > 4096 || width * height > 10000000) {
    throw new Error('封面尺寸须不超过 4096 像素且总像素不超过一千万');
  }
}
function checkHeader(bytes: Uint8Array, type: CoverMediaType) {
  if (type === 'image/png') {
    if (![137, 80, 78, 71, 13, 10, 26, 10].every((v, i) => bytes[i] === v) || bytes.length < 33) {
      throw new Error('封面内容与类型不一致或图片不完整');
    }
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    dimensionsOk(view.getUint32(16), view.getUint32(20));
  } else {
    if (bytes.length < 4 || bytes[0] !== 255 || bytes[1] !== 216 || bytes[2] !== 255 || bytes.at(-2) !== 255 || bytes.at(-1) !== 217) {
      throw new Error('封面内容与类型不一致或图片不完整');
    }
    // Bound allocation before the browser decoder reads a JPEG frame.
    let offset = 2;
    while (offset + 4 <= bytes.length) {
      if (bytes[offset++] !== 255) break;
      while (bytes[offset] === 255) offset++;
      const marker = bytes[offset++];
      if (marker === 0xda || marker === 0xd9) break;
      if (marker === 0x01 || marker >= 0xd0 && marker <= 0xd7) continue;
      const length = bytes[offset] * 256 + bytes[offset + 1];
      if (length < 2 || offset + length > bytes.length) break;
      if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker) && length >= 8) {
        dimensionsOk(bytes[offset + 5] * 256 + bytes[offset + 6], bytes[offset + 3] * 256 + bytes[offset + 4]);
        return;
      }
      offset += length;
    }
    throw new Error('封面图片不完整');
  }
}
async function decode(blob: Blob): Promise<{ width: number; height: number }> {
  const url = URL.createObjectURL(blob);
  try {
    return await new Promise((resolve, reject) => {
      const image = new Image();
      const finish = (error?: Error) => {
        clearTimeout(timer);
        image.onload = null;
        image.onerror = null;
        if (error) reject(error);
        else resolve({ width: image.naturalWidth, height: image.naturalHeight });
      };
      const timer = setTimeout(() => finish(new Error('图片解码超时，请重新选择')), 5000);
      image.onload = () => {
        try { dimensionsOk(image.naturalWidth, image.naturalHeight); finish(); }
        catch (error) { finish(error as Error); }
      };
      image.onerror = () => finish(new Error('图片无法完整解码'));
      image.src = url;
    });
  } finally { URL.revokeObjectURL(url); }
}
async function replacement(blob: Blob, sourceIsbn: string | null): Promise<Extract<CoverDraft, { action: 'REPLACE' }>> {
  if (blob.size > MAX_COVER_BYTES) throw new Error('封面不能超过 2 MiB');
  if (blob.type !== 'image/jpeg' && blob.type !== 'image/png') throw new Error('请选择 JPEG 或 PNG 图片');
  const bytes = await readBlobBytes(blob);
  checkHeader(bytes, blob.type);
  await decode(blob);
  // Avoid spread/apply argument limits for a 2 MiB image.
  let binary = '';
  for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return { action: 'REPLACE', blob, mediaType: blob.type, base64: btoa(binary), sourceIsbn };
}
export function readCoverFile(file: File): Promise<CoverDraft> { return replacement(file, null); }
export async function replacementFromBase64(cover: NonNullable<OpenLibraryLookup['cover']>, isbn: string, provider: BookSourceProvider = 'OPEN_LIBRARY'): Promise<CoverDraft> {
  if (cover.base64.length > 2796204) throw new Error('封面不能超过 2 MiB');
  let binary: string;
  try { binary = atob(cover.base64); } catch { throw new Error('封面 Base64 不合法'); }
  if (btoa(binary) !== cover.base64 || binary.length !== cover.byteLength) throw new Error('封面 Base64 不合法');
  const draft = await replacement(new Blob([Uint8Array.from(binary, (c) => c.charCodeAt(0))], { type: cover.mediaType }), provider === 'OPEN_LIBRARY' ? isbn : null);
  return provider === 'OPEN_LIBRARY' ? draft : { ...draft, source: { provider, isbn } };
}
