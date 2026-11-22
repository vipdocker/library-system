import { afterEach, describe, expect, it, vi } from 'vitest';
import { coverPayload, readCoverFile, replacementFromBase64 } from '../coverDraft';

const png = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC';
function imageFile(type = 'image/png') {
  return new File([Uint8Array.from(atob(png), (c) => c.charCodeAt(0))], 'cover.png', { type });
}
function decoder(width = 1, height = 1) {
  vi.stubGlobal('Image', class {
    naturalWidth = width; naturalHeight = height;
    onload?: () => void;
    set src(_value: string) { queueMicrotask(() => this.onload?.()); }
  });
  vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:decode');
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('AC-FE-01 bounded cover draft', () => {
  it('KEEP omits payload; REMOVE has only action', () => {
    expect(coverPayload({ action: 'KEEP' })).toBeUndefined();
    expect(coverPayload({ action: 'REMOVE' })).toEqual({ action: 'REMOVE' });
  });
  it('local file preserves canonical bytes and null source; decode URL is released', async () => {
    decoder();
    const draft = await readCoverFile(imageFile());
    expect(coverPayload(draft)).toEqual({ action: 'REPLACE', mediaType: 'image/png', base64: png, sourceIsbn: null });
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:decode');
  });
  it.each(['image/jpeg', 'image/gif', ''])('rejects incompatible declared type %s', async (type) => {
    await expect(readCoverFile(imageFile(type))).rejects.toThrow();
  });
  it('rejects oversized input before reading/decoding', async () => {
    await expect(readCoverFile(new File([new Uint8Array(2097153)], 'big.png', { type: 'image/png' }))).rejects.toThrow(/2 MiB/);
  });
  it('rejects excessive header dimensions before decoding', async () => {
    const bytes = Uint8Array.from(atob(png), (c) => c.charCodeAt(0));
    bytes.set([0, 0, 32, 0], 16);
    await expect(readCoverFile(new File([bytes], 'big.png', { type: 'image/png' }))).rejects.toThrow(/尺寸/);
  });
  it('rejects decoder failure and preserves no partial draft', async () => {
    decoder();
    vi.stubGlobal('Image', class { onerror?: () => void; set src(_value: string) { queueMicrotask(() => this.onerror?.()); } });
    await expect(readCoverFile(imageFile())).rejects.toThrow(/解码/);
    expect(URL.revokeObjectURL).toHaveBeenCalled();
  });
  it('remote draft validates standard base64 and records applied ISBN', async () => {
    decoder();
    const draft = await replacementFromBase64({ mediaType: 'image/png', base64: png, byteLength: 69, width: 1, height: 1 }, '9780140328721');
    expect(coverPayload(draft)).toMatchObject({ sourceIsbn: '9780140328721' });
    await expect(replacementFromBase64({ mediaType: 'image/png', base64: 'data:image/png;base64,' + png, byteLength: 69, width: 1, height: 1 }, '9780140328721')).rejects.toThrow();
  });
});
