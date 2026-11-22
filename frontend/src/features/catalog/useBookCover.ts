import { useQuery } from '@tanstack/react-query';
import { useAuth } from '../../auth/AuthContext';
import type { BookCoverMetadata } from '../../api/types';
import { getBookCover } from './api';
import { MAX_COVER_BYTES, readBlobBytes } from './coverDraft';

/** Only Blob data is shared. Object URLs belong to each rendering instance. */
export function useBookCover(bookId: number, cover: BookCoverMetadata, visible: boolean) {
  const { user, status } = useAuth();
  return useQuery({
    queryKey: ['catalog', 'cover', user?.id, user?.role, bookId, cover.sha256],
    enabled: visible && status === 'authenticated' && !!user,
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
    queryFn: async ({ signal }) => {
      const blob = await getBookCover(bookId, cover.sha256, signal);
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
      if (blob.type !== cover.mediaType || blob.size !== cover.byteLength || blob.size > MAX_COVER_BYTES) throw new Error('封面内容不匹配');
      const bytes = await readBlobBytes(blob);
      const digest = await crypto.subtle.digest('SHA-256', bytes);
      const sha = Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, '0')).join('');
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
      if (sha !== cover.sha256) throw new Error('封面校验失败');
      return blob;
    },
  });
}
