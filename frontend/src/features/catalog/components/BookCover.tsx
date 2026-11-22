import { useEffect, useRef, useState } from 'react';
import type { BookCoverMetadata } from '../../../api/types';
import { useBookCover } from '../useBookCover';

// 中性暖纸层级：同一 Claude token 源的三种奶油表面，无彩色装饰。
const PALETTE = [
  'var(--claude-surface-soft)', 'var(--claude-surface-card)', 'var(--claude-surface-cream-strong)',
  'var(--claude-surface-card)', 'var(--claude-surface-cream-strong)', 'var(--claude-surface-soft)',
] as const;

function hashTitle(title: string): number {
  let hash = 0;
  for (let i = 0; i < title.length; i += 1) {
    hash = (hash * 31 + title.charCodeAt(i)) >>> 0;
  }
  return hash;
}

export interface BookCoverProps {
  title: string;
  bookId?: number;
  cover?: BookCoverMetadata | null;
  author?: string;
  /** sm 用于列表行，lg 用于详情页 */
  size?: 'sm' | 'lg';
}

export function BookCover(props: BookCoverProps) {
  return props.cover && props.bookId
    ? <RemoteCover {...props} bookId={props.bookId} cover={props.cover} />
    : <PaperCover {...props} />;
}

function RemoteCover(props: BookCoverProps & { bookId: number; cover: BookCoverMetadata }) {
  const ref = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(typeof IntersectionObserver === 'undefined');
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined' || !ref.current) return;
    const observer = new IntersectionObserver(([entry]) => setVisible(entry.isIntersecting));
    observer.observe(ref.current);
    return () => observer.disconnect();
  }, []);
  const query = useBookCover(props.bookId, props.cover, visible);
  return <div ref={ref}><CoverPreview blob={query.data} {...props} /></div>;
}

/** Revokes only its own URL, on replacement/unmount, never onLoad. */
export function CoverPreview({ blob, ...props }: BookCoverProps & { blob?: Blob }) {
  const [image, setImage] = useState<{ blob: Blob; url: string }>();
  const [failed, setFailed] = useState<Blob>();
  useEffect(() => {
    if (!blob) return;
    const url = URL.createObjectURL(blob);
    setImage({ blob, url });
    return () => URL.revokeObjectURL(url);
  }, [blob]);
  if (!blob || image?.blob !== blob || failed === blob) return <PaperCover {...props} />;
  return <img src={image.url} alt="" aria-hidden onError={() => setFailed(blob)} style={{
    width: props.size === 'lg' ? 128 : 40, height: props.size === 'lg' ? 176 : 56,
    objectFit: 'contain', flexShrink: 0, background: 'var(--claude-surface-soft)',
    border: '1px solid var(--claude-control-border)', borderRadius: 'var(--claude-radius-sm)',
  }} />;
}

function PaperCover({ title, author, size = 'sm' }: BookCoverProps) {
  const colors = PALETTE[hashTitle(title) % PALETTE.length];
  const width = size === 'lg' ? 128 : 40;
  const height = size === 'lg' ? 176 : 56;
  return (
    <div
      aria-hidden
      style={{
        width,
        height,
        flex: `0 0 ${width}px`,
        borderRadius: size === 'lg' ? 'var(--claude-radius-sm)' : 'var(--claude-radius-xs)',
        background: colors,
        border: '1px solid var(--claude-control-border)',
        position: 'relative',
        overflow: 'hidden',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'space-between',
        padding: size === 'lg' ? '14px 12px 12px 20px' : '5px 4px 4px 8px',
        boxSizing: 'border-box',
      }}
    >
      <span
        style={{
          position: 'absolute',
          left: size === 'lg' ? 8 : 3,
          top: 0,
          bottom: 0,
          width: size === 'lg' ? 3 : 1.5,
          background: 'var(--claude-control-border)',
        }}
      />
      <span
        style={{
          color: 'var(--claude-ink)',
          fontSize: size === 'lg' ? 15 : 7,
          lineHeight: 1.35,
          fontWeight: 600,
          display: '-webkit-box',
          WebkitLineClamp: size === 'lg' ? 4 : 3,
          WebkitBoxOrient: 'vertical',
          overflow: 'hidden',
          wordBreak: 'break-all',
        }}
      >
        {title}
      </span>
      <span
        style={{
          borderTop: '1px solid var(--claude-control-border)',
          paddingTop: size === 'lg' ? 6 : 2,
          color: 'var(--claude-text-muted)',
          fontSize: size === 'lg' ? 11 : 5,
          whiteSpace: 'nowrap',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {author ?? ''}
      </span>
    </div>
  );
}