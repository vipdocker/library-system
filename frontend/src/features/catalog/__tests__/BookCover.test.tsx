import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { BookCover } from '../components/BookCover';
import { getBookCover } from '../api';
import { useAuth } from '../../../auth/AuthContext';
import type { BookCoverMetadata } from '../../../api/types';
import { adminUser, readerUser } from './testUtils';
vi.mock('../api', async (original) => ({ ...await original<typeof import('../api')>(), getBookCover: vi.fn() }));
vi.mock('../../../auth/AuthContext', () => ({ useAuth: vi.fn() }));
const bytes = new Uint8Array([137, 80, 78, 71]);
const metadata: BookCoverMetadata = { url: '/api/books/5/cover?v=sha', sha256: 'sha', mediaType: 'image/png', byteLength: 4, width: 1, height: 1, sourceUrl: null };
function cover() { return <BookCover title="书" bookId={5} cover={metadata} />; }
let client: QueryClient;
beforeEach(() => {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  vi.mocked(useAuth).mockReturnValue({ user: adminUser, status: 'authenticated', login: vi.fn(), logout: vi.fn(), markLoggedOut: vi.fn() });
  vi.mocked(getBookCover).mockResolvedValue(new Blob([bytes], { type: 'image/png' }));
  let count = 0;
  vi.spyOn(URL, 'createObjectURL').mockImplementation(() => `blob:cover-${++count}`);
  vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {});
  vi.spyOn(crypto.subtle, 'digest').mockResolvedValue(new Uint8Array([0xab]).buffer);
});
afterEach(() => { client.clear(); vi.restoreAllMocks(); });
function renderCover(ui = cover()) { return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>); }

describe('AC-FE-03 real cover cache and instance lifetime', () => {
  it('without cover never requests bytes and retains paper placeholder', () => {
    const view = renderCover(<BookCover title="书" />);
    expect(view.container.querySelector('img')).toBeNull();
    expect(getBookCover).not.toHaveBeenCalled();
  });
  it('shared verified blob, independent URLs; removing one instance does not revoke another', async () => {
    const good = { ...metadata, sha256: 'ab' };
    const view = renderCover(<><BookCover title="书" bookId={5} cover={good} /><BookCover title="书" bookId={5} cover={good} /></>);
    await waitFor(() => expect(view.container.querySelectorAll('img')).toHaveLength(2));
    expect(getBookCover).toHaveBeenCalledTimes(1);
    const images = view.container.querySelectorAll('img');
    expect(images[0].src).not.toBe(images[1].src);
    fireEvent.load(images[0]);
    expect(URL.revokeObjectURL).not.toHaveBeenCalled();
    view.rerender(<QueryClientProvider client={client}><BookCover title="书" bookId={5} cover={good} /></QueryClientProvider>);
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(1);
    view.unmount();
    expect(URL.revokeObjectURL).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(client.getQueryCache().getAll()).toHaveLength(0));
  });
  it.each(['mime', 'size', 'sha', '404', 'network'])('mismatched/failing %s becomes placeholder without retry', async (kind) => {
    if (kind === 'mime') vi.mocked(getBookCover).mockResolvedValue(new Blob([bytes], { type: 'image/jpeg' }));
    if (kind === 'size') vi.mocked(getBookCover).mockResolvedValue(new Blob([], { type: 'image/png' }));
    if (kind === '404' || kind === 'network') vi.mocked(getBookCover).mockRejectedValue(new Error(kind));
    const view = renderCover();
    await waitFor(() => expect(getBookCover).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(client.getQueryCache().getAll()[0]?.state.status).toBe('error'));
    expect(view.container.querySelector('img')).toBeNull();
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });
  it('image decode error is local placeholder, not a list failure or network retry', async () => {
    const view = renderCover(<BookCover title="书" bookId={5} cover={{ ...metadata, sha256: 'ab' }} />);
    await waitFor(() => expect(view.container.querySelector('img')).not.toBeNull());
    fireEvent.error(view.container.querySelector('img')!);
    expect(view.container.querySelector('img')).toBeNull();
    expect(getBookCover).toHaveBeenCalledTimes(1);
  });
  it('last observer cancels late response and does not recreate a URL', async () => {
    let resolve!: (blob: Blob) => void;
    vi.mocked(getBookCover).mockReturnValue(new Promise((done) => { resolve = done; }));
    const view = renderCover();
    await waitFor(() => expect(getBookCover).toHaveBeenCalledTimes(1));
    const signal = vi.mocked(getBookCover).mock.calls[0][2];
    view.unmount();
    expect(signal?.aborted).toBe(true);
    resolve(new Blob([bytes], { type: 'image/png' }));
    await waitFor(() => expect(client.getQueryCache().getAll()).toHaveLength(0));
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });
  it('identity and SHA separate keys; query consumes cancellation signal', async () => {
    const good = { ...metadata, sha256: 'ab' };
    const view = renderCover(<BookCover title="书" bookId={5} cover={good} />);
    await waitFor(() => expect(view.container.querySelector('img')).not.toBeNull());
    const signal = vi.mocked(getBookCover).mock.calls[0][2];
    expect(signal).toBeInstanceOf(AbortSignal);
    vi.mocked(useAuth).mockReturnValue({ user: readerUser, status: 'authenticated', login: vi.fn(), logout: vi.fn(), markLoggedOut: vi.fn() });
    view.rerender(<QueryClientProvider client={client}><BookCover title="书" bookId={5} cover={good} /></QueryClientProvider>);
    await waitFor(() => expect(getBookCover).toHaveBeenCalledTimes(2));
    expect(URL.revokeObjectURL).toHaveBeenCalled();
  });
});
