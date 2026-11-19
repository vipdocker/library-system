// 确定性生成的示意书封：由书名哈希取色（图书馆主题色板），无外部图片依赖。
//  purely decorative —— 对读屏器隐藏，书目信息以文本呈现。
const PALETTE = [
  { bg: '#1e6b52', deep: '#152e26', accent: '#e8c98a' },
  { bg: '#2f6f8f', deep: '#1d4257', accent: '#f0d9a8' },
  { bg: '#b23a32', deep: '#7c2822', accent: '#f5e3c8' },
  { bg: '#c9861d', deep: '#8a5c12', accent: '#fffdf7' },
  { bg: '#3d8b51', deep: '#28593a', accent: '#f5f2ea' },
  { bg: '#152e26', deep: '#0d1d18', accent: '#e8c98a' },
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
        borderRadius: size === 'lg' ? 6 : 3,
        background: `linear-gradient(150deg, ${colors.bg} 0%, ${colors.deep} 100%)`,
        boxShadow:
          size === 'lg'
            ? '6px 8px 18px rgba(21, 46, 38, 0.28)'
            : '2px 2px 5px rgba(21, 46, 38, 0.22)',
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
          background: 'rgba(0,0,0,0.28)',
        }}
      />
      <span
        style={{
          color: colors.accent,
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
          borderTop: `1px solid ${colors.accent}55`,
          paddingTop: size === 'lg' ? 6 : 2,
          color: `${colors.accent}cc`,
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