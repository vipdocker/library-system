const PALETTE = [
  'var(--linear-surface-1)', 'var(--linear-surface-2)', 'var(--linear-surface-3)',
  'var(--linear-surface-4)', 'var(--linear-surface-2)', 'var(--linear-surface-3)',
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
  author?: string;
  /** sm 用于列表行，lg 用于详情页 */
  size?: 'sm' | 'lg';
}

export function BookCover({ title, author, size = 'sm' }: BookCoverProps) {
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
        borderRadius: size === 'lg' ? 'var(--linear-radius-sm)' : 'var(--linear-radius-xs)',
        background: colors,
        border: '1px solid var(--linear-hairline-strong)',
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
          background: 'var(--linear-hairline-strong)',
        }}
      />
      <span
        style={{
          color: 'var(--linear-ink)',
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
          borderTop: '1px solid var(--linear-hairline-strong)',
          paddingTop: size === 'lg' ? 6 : 2,
          color: 'var(--linear-ink-muted)',
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