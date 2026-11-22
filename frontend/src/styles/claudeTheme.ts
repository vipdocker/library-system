// Claude 亮色主题单源（claude-light-design-v1 §3）：颜色、字体、间距、圆角与状态集中在此，
// antd 走公开 theme.defaultAlgorithm（六 seed 精确保留），CSS 通过 --claude-* 变量消费同一来源。
import { theme, type ThemeConfig } from 'antd';

const baseColors = {
  canvas: '#faf9f5', 'surface-soft': '#f5f0e8', 'surface-card': '#efe9de', 'surface-cream-strong': '#e8e0d2',
  ink: '#141413', body: '#3d3d3a', 'text-muted': '#625f58', 'text-disabled': '#8e8b82',
  hairline: '#e6dfd8', 'control-border': '#817d73',
  'brand-primary': '#cc785c', 'on-primary': '#ffffff',
  primary: '#a9583e', 'primary-hover': '#934b34', 'primary-active': '#7d3d2a',
} as const;

const colors = {
  ...baseColors,
  // 链接/焦点复用同一陶土深色，不重复定义色值。
  link: baseColors['primary-hover'], 'link-active': baseColors['primary-active'],
} as const;

export const claudeTokens = {
  colors,
  fonts: {
    display: "Georgia, 'Songti SC', STSong, SimSun, serif",
    sans: "-apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Segoe UI', sans-serif",
    mono: "ui-monospace, 'SF Mono', SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace",
  },
  typography: {
    'display-md': { fontSize: 36, fontWeight: 400, lineHeight: 1.15, letterSpacing: -0.5 },
    headline: { fontSize: 28, fontWeight: 400, lineHeight: 1.2, letterSpacing: -0.3 },
    'headline-sm': { fontSize: 24, fontWeight: 400, lineHeight: 1.2, letterSpacing: -0.3 },
    'card-title': { fontSize: 22, fontWeight: 500, lineHeight: 1.3, letterSpacing: 0 },
    subhead: { fontSize: 20, fontWeight: 500, lineHeight: 1.4, letterSpacing: 0 },
    'body-lg': { fontSize: 18, fontWeight: 400, lineHeight: 1.55, letterSpacing: 0 },
    body: { fontSize: 16, fontWeight: 400, lineHeight: 1.55, letterSpacing: 0 },
    'body-sm': { fontSize: 14, fontWeight: 400, lineHeight: 1.55, letterSpacing: 0 },
    caption: { fontSize: 12, fontWeight: 400, lineHeight: 1.4, letterSpacing: 0 },
    button: { fontSize: 14, fontWeight: 500, lineHeight: 1.2, letterSpacing: 0 },
    eyebrow: { fontSize: 13, fontWeight: 500, lineHeight: 1.3, letterSpacing: 0 },
    mono: { fontSize: 13, fontWeight: 400, lineHeight: 1.5, letterSpacing: 0 },
  },
  rounded: { xs: 4, sm: 6, md: 8, lg: 12, xl: 16, xxl: 24, pill: 9999, full: 9999 },
  spacing: { xxs: 4, xs: 8, sm: 12, md: 16, lg: 24, xl: 32, xxl: 48, section: 96 },
  // 深色前景为批准的后台可读性适配（§3 状态表）；背景/边框由 Claude 原语义色与 canvas 混合而来。
  status: {
    success: { foreground: '#2f6b40', background: '#eaf3e8', border: '#d3e9d4' },
    warning: { foreground: '#805c14', background: '#f6f0df', border: '#f1e3be' },
    error: { foreground: '#a53636', background: '#f5e7e3', border: '#edccc9' },
    info: { foreground: '#28695e', background: '#eaf3ed', border: '#d3e9e1' },
  },
  overlayOpacity: 0.45,
} as const;

const c = claudeTokens.colors;
const t = claudeTokens.typography;
const r = claudeTokens.rounded;
const s = claudeTokens.spacing;
const semanticAliases = Object.fromEntries(Object.entries(claudeTokens.status).flatMap(([name, value]) => {
  const prefix = `color${name[0].toUpperCase()}${name.slice(1)}`;
  return [
    ...['', 'Hover', 'Active', 'Text', 'TextHover', 'TextActive'].map((suffix) => [prefix + suffix, value.foreground]),
    ...['Bg', 'BgHover'].map((suffix) => [prefix + suffix, value.background]),
    ...['Border', 'BorderHover'].map((suffix) => [prefix + suffix, value.border]),
  ];
}));
const inputTokens = {
  colorBgContainer: c.canvas, colorText: c.body,
  paddingBlock: s.xs, paddingInline: s.sm,
  hoverBg: c.canvas, activeBg: c.canvas,
  hoverBorderColor: c['control-border'], activeBorderColor: c.link,
  activeShadow: 'none', errorActiveShadow: 'none', warningActiveShadow: 'none',
};
const popupTokens = { colorBgElevated: c.canvas, colorText: c.body, colorTextSecondary: c['text-muted'] };

export const claudeTheme: ThemeConfig = {
  algorithm: theme.defaultAlgorithm,
  token: {
    ...semanticAliases,
    colorPrimary: c.primary, colorPrimaryHover: c['primary-hover'], colorPrimaryActive: c['primary-active'],
    colorPrimaryBorder: c.link, colorPrimaryBorderHover: c.link,
    colorPrimaryBg: c['surface-card'], colorPrimaryBgHover: c['surface-cream-strong'],
    colorPrimaryText: c.link, colorPrimaryTextHover: c['link-active'], colorPrimaryTextActive: c['link-active'],
    colorLink: c.link, colorLinkHover: c['link-active'], colorLinkActive: c['link-active'],
    colorTextBase: c.ink, colorText: c.body, colorTextHeading: c.ink,
    colorTextSecondary: c['text-muted'], colorTextLabel: c['text-muted'],
    colorTextTertiary: c['text-muted'], colorTextPlaceholder: c['text-muted'], colorTextDescription: c['text-muted'],
    colorTextQuaternary: c['text-disabled'], colorTextDisabled: c['text-disabled'], colorTextLightSolid: c['on-primary'],
    colorBgBase: c.canvas, colorBgLayout: c.canvas, colorBgContainer: c['surface-soft'], colorBgElevated: c.canvas,
    colorBgContainerDisabled: c['surface-card'], colorBgSpotlight: c.canvas,
    colorBgMask: `rgba(20, 20, 19, ${claudeTokens.overlayOpacity})`,
    colorFill: c['surface-cream-strong'], colorFillSecondary: c['surface-card'], colorFillTertiary: c['surface-soft'], colorFillQuaternary: c.canvas,
    colorFillAlter: c['surface-soft'], colorFillContent: c['surface-card'], colorFillContentHover: c['surface-cream-strong'],
    colorBgTextHover: c['surface-soft'], colorBgTextActive: c['surface-card'],
    colorBorder: c['control-border'], colorBorderSecondary: c.hairline, colorSplit: c.hairline,
    colorIcon: c['text-muted'], colorIconHover: c.ink, colorHighlight: claudeTokens.status.error.foreground,
    controlOutline: c.link, controlOutlineWidth: 2, lineWidthFocus: 2, lineWidth: 1,
    controlItemBgHover: c['surface-card'], controlItemBgActive: c['surface-cream-strong'], controlItemBgActiveHover: c['surface-cream-strong'],
    colorErrorOutline: claudeTokens.status.error.foreground, colorWarningOutline: claudeTokens.status.warning.foreground,
    boxShadow: 'none', boxShadowSecondary: '0 1px 3px rgba(20, 20, 19, 0.08)', boxShadowTertiary: 'none',
    fontFamily: claudeTokens.fonts.sans, fontFamilyCode: claudeTokens.fonts.mono,
    fontSize: t.body.fontSize, fontSizeSM: t['body-sm'].fontSize, lineHeight: t.body.lineHeight, fontWeightStrong: 600,
    fontSizeHeading1: t['display-md'].fontSize, lineHeightHeading1: t['display-md'].lineHeight,
    fontSizeHeading2: t.headline.fontSize, lineHeightHeading2: t.headline.lineHeight,
    fontSizeHeading3: t.headline.fontSize, lineHeightHeading3: t.headline.lineHeight,
    fontSizeHeading4: t['card-title'].fontSize, lineHeightHeading4: t['card-title'].lineHeight,
    fontSizeHeading5: t.body.fontSize, lineHeightHeading5: t.body.lineHeight,
    borderRadius: r.md, borderRadiusXS: r.xs, borderRadiusSM: r.sm, borderRadiusLG: r.lg,
    controlHeight: 40, controlHeightLG: 44,
    marginXXS: s.xxs, marginXS: s.xs, marginSM: s.sm, margin: s.md, marginLG: s.lg, marginXL: s.xl, marginXXL: s.xxl,
    paddingXXS: s.xxs, paddingXS: s.xs, paddingSM: s.sm, padding: s.md, paddingLG: s.lg, paddingXL: s.xl,
  },
  components: {
    Layout: { bodyBg: c.canvas, siderBg: c['surface-soft'], headerBg: c.canvas, headerColor: c.ink, triggerBg: c['surface-card'], triggerColor: c['text-muted'], headerHeight: 64 },
    Menu: {
      itemBg: c['surface-soft'], subMenuItemBg: c['surface-soft'], popupBg: c.canvas,
      itemColor: c['text-muted'], itemHoverColor: c.ink, itemSelectedColor: c.ink,
      itemHoverBg: c['surface-card'], itemSelectedBg: c['surface-cream-strong'], itemDisabledColor: c['text-disabled'], itemBorderRadius: r.md,
    },
    Button: {
      primaryColor: c['on-primary'], dangerColor: c['on-primary'],
      defaultBg: c.canvas, defaultColor: c.ink, defaultBorderColor: c['control-border'],
      defaultHoverBg: c.canvas, defaultHoverColor: c.link, defaultHoverBorderColor: c.link,
      defaultActiveBg: c['surface-soft'], defaultActiveColor: c['link-active'], defaultActiveBorderColor: c['link-active'],
      textTextColor: c.body, textTextHoverColor: c.ink, textTextActiveColor: c.ink, textHoverBg: c['surface-soft'],
      fontWeight: t.button.fontWeight, paddingBlock: 8, paddingInline: 14,
      contentFontSize: t.button.fontSize, contentLineHeight: t.button.lineHeight,
      defaultShadow: 'none', primaryShadow: 'none', dangerShadow: 'none',
    },
    Input: inputTokens, InputNumber: inputTokens,
    Select: {
      ...popupTokens, selectorBg: c.canvas, clearBg: c.canvas,
      optionSelectedBg: c['surface-cream-strong'], optionSelectedColor: c.ink, optionSelectedFontWeight: 600,
      optionActiveBg: c['surface-card'], hoverBorderColor: c['control-border'], activeBorderColor: c.link, activeOutlineColor: c.link,
    },
    Pagination: { itemActiveColor: c.link, itemActiveColorHover: c['link-active'] },
    DatePicker: {
      ...inputTokens, ...popupTokens, colorTextQuaternary: c['text-muted'], colorTextDisabled: c['text-disabled'],
      cellHoverBg: c['surface-card'], cellActiveWithRangeBg: c['surface-card'],
      cellHoverWithRangeBg: c['surface-cream-strong'], cellRangeBorderColor: c.link,
    },
    Card: { headerBg: c['surface-soft'], actionsBg: c['surface-soft'], bodyPadding: s.lg, headerPadding: s.lg, extraColor: c['text-muted'], colorBgContainer: c['surface-soft'], borderRadiusLG: r.lg },
    Table: {
      headerBg: c['surface-card'], headerSortActiveBg: c['surface-card'], headerSortHoverBg: c['surface-card'], headerColor: c['text-muted'],
      bodySortBg: c['surface-soft'], rowHoverBg: c['surface-card'], rowSelectedBg: c['surface-card'], rowExpandedBg: c['surface-soft'], rowSelectedHoverBg: c['surface-cream-strong'],
      borderColor: c.hairline, headerSplitColor: c.hairline, footerBg: c['surface-soft'], footerColor: c['text-muted'],
      filterDropdownBg: c.canvas, filterDropdownMenuBg: c.canvas, expandIconBg: c.canvas,
      headerBorderRadius: r.md, cellFontSize: t['body-sm'].fontSize, cellPaddingBlock: s.sm, cellPaddingInline: s.md,
    },
    Modal: { contentBg: c.canvas, headerBg: c.canvas, footerBg: c.canvas, titleColor: c.ink, titleFontSize: t['card-title'].fontSize, titleLineHeight: t['card-title'].lineHeight, borderRadiusLG: r.lg, colorText: c.body },
    Tag: { ...semanticAliases, defaultBg: c['surface-card'], defaultColor: c['text-muted'], borderRadiusSM: r.pill, fontSize: t.caption.fontSize, colorBorder: c.hairline },
    Alert: { ...semanticAliases, colorText: c.body, colorTextHeading: c.ink, containerBorderRadius: r.md },
    Badge: { colorError: c['surface-cream-strong'], colorTextLightSolid: c.ink },
    Tabs: { itemColor: c['text-muted'], itemSelectedColor: c.ink, itemHoverColor: c.ink, itemActiveColor: c.ink, inkBarColor: c['brand-primary'], cardBg: c['surface-soft'] },
    Descriptions: { labelBg: c['surface-card'], labelColor: c['text-muted'], contentColor: c.body, titleColor: c.ink, extraColor: c['text-muted'] },
    Message: popupTokens, Notification: popupTokens, Popover: popupTokens, Dropdown: popupTokens, Popconfirm: popupTokens,
    // Tooltip 消费 Spotlight/LightSolid：组件级亮底深字；全局 LightSolid 白字留给实色按钮/日期。
    Tooltip: { colorBgSpotlight: c.canvas, colorTextLightSolid: c.body },
    Spin: { colorText: c['text-muted'] },
  },
};

export const claudeCssVariables: Record<string, string> = {
  ...Object.fromEntries(Object.entries(c).map(([name, value]) => [`--claude-${name}`, value])),
  ...Object.fromEntries(Object.entries(s).map(([name, value]) => [`--claude-space-${name}`, `${value}px`])),
  ...Object.fromEntries(Object.entries(r).map(([name, value]) => [`--claude-radius-${name}`, `${value}px`])),
  ...Object.fromEntries(Object.entries(t).flatMap(([name, value]) => [
    [`--claude-${name}-size`, `${value.fontSize}px`], [`--claude-${name}-weight`, String(value.fontWeight)],
    [`--claude-${name}-line-height`, String(value.lineHeight)], [`--claude-${name}-tracking`, `${value.letterSpacing}px`],
  ])),
  ...Object.fromEntries(Object.entries(claudeTokens.status).flatMap(([name, value]) => [
    [`--claude-${name}`, value.foreground], [`--claude-${name}-bg`, value.background], [`--claude-${name}-border`, value.border],
  ])),
  '--claude-font-display': claudeTokens.fonts.display,
  '--claude-font-sans': claudeTokens.fonts.sans,
  '--claude-font-mono': claudeTokens.fonts.mono,
  '--claude-overlay': c.ink,
};

export function applyClaudeCssVariables(root: HTMLElement): void {
  for (const [name, value] of Object.entries(claudeCssVariables)) root.style.setProperty(name, value);
}

// favicon：保留原图书 SVG 静态几何，仅从同一 token 源取色；main.tsx 同步设置已有 link。
export const claudeFaviconUri = `data:image/svg+xml,${encodeURIComponent(
  `<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'><rect width='32' height='32' rx='7' fill='${c['brand-primary']}'/><path d='M9 8h6a3 3 0 0 1 3 3v13a2.5 2.5 0 0 0-2.5-2.5H9z' fill='${c.canvas}'/><path d='M23 8h-6a3 3 0 0 0-3 3v13a2.5 2.5 0 0 1 2.5-2.5H23z' fill='${c['surface-cream-strong']}'/></svg>`,
)}`;