import { theme, type ThemeConfig } from 'antd';

const colors = {
  primary: '#5e6ad2', 'on-primary': '#ffffff', 'primary-hover': '#828fff', 'primary-focus': '#5e69d1',
  ink: '#f7f8f8', 'ink-muted': '#d0d6e0', 'ink-subtle': '#8a8f98', 'ink-tertiary': '#62666d',
  canvas: '#010102', 'surface-1': '#0f1011', 'surface-2': '#141516', 'surface-3': '#18191a', 'surface-4': '#191a1b',
  hairline: '#23252a', 'hairline-strong': '#34343a', 'hairline-tertiary': '#3e3e44',
  'inverse-canvas': '#ffffff', 'inverse-surface-1': '#f5f6f6', 'inverse-surface-2': '#f6f7f7', 'inverse-ink': '#000000',
  'brand-secure': '#7a7fad', 'semantic-success': '#27a644', 'semantic-overlay': '#000000',
} as const;

export const linearTokens = {
  colors,
  fonts: {
    sans: "'SF Pro Display', -apple-system, BlinkMacSystemFont, system-ui, 'Segoe UI', Roboto, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
    mono: "ui-monospace, 'SF Mono', SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace",
  },
  typography: {
    'display-xl': { fontSize: 80, fontWeight: 600, lineHeight: 1.05, letterSpacing: -3 },
    'display-lg': { fontSize: 56, fontWeight: 600, lineHeight: 1.10, letterSpacing: -1.8 },
    'display-md': { fontSize: 40, fontWeight: 600, lineHeight: 1.15, letterSpacing: -1 },
    headline: { fontSize: 28, fontWeight: 600, lineHeight: 1.20, letterSpacing: -0.6 },
    'card-title': { fontSize: 22, fontWeight: 500, lineHeight: 1.25, letterSpacing: -0.4 },
    subhead: { fontSize: 20, fontWeight: 400, lineHeight: 1.40, letterSpacing: -0.2 },
    'body-lg': { fontSize: 18, fontWeight: 400, lineHeight: 1.50, letterSpacing: -0.1 },
    body: { fontSize: 16, fontWeight: 400, lineHeight: 1.50, letterSpacing: -0.05 },
    'body-sm': { fontSize: 14, fontWeight: 400, lineHeight: 1.50, letterSpacing: 0 },
    caption: { fontSize: 12, fontWeight: 400, lineHeight: 1.40, letterSpacing: 0 },
    button: { fontSize: 14, fontWeight: 500, lineHeight: 1.20, letterSpacing: 0 },
    eyebrow: { fontSize: 13, fontWeight: 500, lineHeight: 1.30, letterSpacing: 0.4 },
    mono: { fontSize: 13, fontWeight: 400, lineHeight: 1.50, letterSpacing: 0 },
  },
  rounded: { xs: 4, sm: 6, md: 8, lg: 12, xl: 16, xxl: 24, pill: 9999, full: 9999 },
  spacing: { xxs: 4, xs: 8, sm: 12, md: 16, lg: 24, xl: 32, xxl: 48, section: 96 },
  status: {
    success: { foreground: colors['semantic-success'], background: '#16261c', border: '#1a4325' },
    // Error/warning mix the existing semantic seed with ink-muted, per approved design §4.3.
    error: { foreground: '#c18889', background: '#292324', border: '#4b3a3b' },
    warning: { foreground: '#cdae7f', background: '#2a2723', border: '#4f4638' },
    info: { foreground: colors['brand-secure'], background: colors['surface-2'], border: colors['hairline-strong'] },
  },
  overlayOpacity: 0.72,
} as const;

const c = linearTokens.colors;
const t = linearTokens.typography;
const r = linearTokens.rounded;
const s = linearTokens.spacing;
const semanticAliases = Object.fromEntries(Object.entries(linearTokens.status).flatMap(([name, value]) => {
  const prefix = `color${name[0].toUpperCase()}${name.slice(1)}`;
  return [
    ...['', 'Hover', 'Active', 'Text', 'TextHover', 'TextActive'].map((suffix) => [prefix + suffix, value.foreground]),
    ...['Bg', 'BgHover'].map((suffix) => [prefix + suffix, value.background]),
    ...['Border', 'BorderHover'].map((suffix) => [prefix + suffix, value.border]),
  ];
}));
const inputTokens = {
  colorBgContainer: c['surface-1'], colorText: c.ink,
  paddingBlock: s.xs, paddingInline: s.sm,
  hoverBg: c['surface-1'], activeBg: c['surface-1'],
  hoverBorderColor: c['hairline-tertiary'], activeBorderColor: c['primary-focus'],
  activeShadow: 'none', errorActiveShadow: 'none', warningActiveShadow: 'none',
};
const popupTokens = { colorBgElevated: c['surface-3'], colorText: c.ink, colorTextSecondary: c['ink-muted'] };

const linearSeedColors = {
  colorPrimary: c.primary,
  colorLink: c['primary-hover'],
  colorSuccess: linearTokens.status.success.foreground,
  colorError: linearTokens.status.error.foreground,
  colorWarning: linearTokens.status.warning.foreground,
  colorInfo: linearTokens.status.info.foreground,
} as const;

export const linearDarkAlgorithm: typeof theme.darkAlgorithm = (seed, mapToken) => {
  const derived = theme.darkAlgorithm(seed, mapToken);
  return { ...derived, ...linearSeedColors };
};

export const linearTheme: ThemeConfig = {
  algorithm: linearDarkAlgorithm,
  token: {
    ...semanticAliases,
    colorPrimary: c.primary, colorPrimaryHover: c['primary-hover'], colorPrimaryActive: c['primary-focus'],
    colorPrimaryBorder: c['primary-focus'], colorPrimaryBorderHover: c['primary-hover'],
    colorPrimaryBg: c['surface-2'], colorPrimaryBgHover: c['surface-3'],
    colorPrimaryText: c['primary-hover'], colorPrimaryTextHover: c['primary-hover'], colorPrimaryTextActive: c['primary-hover'],
    colorLink: c['primary-hover'], colorLinkHover: c['primary-hover'], colorLinkActive: c['primary-hover'],
    colorTextBase: c.ink, colorText: c.ink, colorTextHeading: c.ink,
    colorTextSecondary: c['ink-muted'], colorTextLabel: c['ink-muted'],
    colorTextTertiary: c['ink-subtle'], colorTextPlaceholder: c['ink-subtle'], colorTextDescription: c['ink-subtle'],
    colorTextQuaternary: c['ink-tertiary'], colorTextDisabled: c['ink-tertiary'], colorTextLightSolid: c['on-primary'],
    colorBgBase: c.canvas, colorBgLayout: c.canvas, colorBgContainer: c['surface-1'], colorBgElevated: c['surface-3'],
    colorBgContainerDisabled: c['surface-2'], colorBgSpotlight: c['surface-4'],
    colorBgMask: `rgba(0, 0, 0, ${linearTokens.overlayOpacity})`,
    colorFill: c['surface-4'], colorFillSecondary: c['surface-3'], colorFillTertiary: c['surface-2'], colorFillQuaternary: c['surface-4'],
    colorFillAlter: c['surface-2'], colorFillContent: c['surface-2'], colorFillContentHover: c['surface-3'],
    colorBgTextHover: c['surface-2'], colorBgTextActive: c['surface-3'],
    colorBorder: c['hairline-strong'], colorBorderSecondary: c.hairline, colorSplit: c.hairline,
    colorIcon: c['ink-subtle'], colorIconHover: c['ink-muted'], colorHighlight: linearTokens.status.error.foreground,
    controlOutline: c['primary-focus'], controlOutlineWidth: 2, lineWidthFocus: 2, lineWidth: 1,
    controlItemBgHover: c['surface-2'], controlItemBgActive: c['surface-2'], controlItemBgActiveHover: c['surface-3'],
    colorErrorOutline: linearTokens.status.error.foreground, colorWarningOutline: linearTokens.status.warning.foreground,
    boxShadow: 'none', boxShadowSecondary: 'none', boxShadowTertiary: 'none',
    fontFamily: linearTokens.fonts.sans, fontFamilyCode: linearTokens.fonts.mono,
    fontSize: t.body.fontSize, fontSizeSM: t['body-sm'].fontSize, lineHeight: t.body.lineHeight, fontWeightStrong: 600,
    fontSizeHeading1: t['display-md'].fontSize, lineHeightHeading1: t['display-md'].lineHeight,
    fontSizeHeading2: t.headline.fontSize, lineHeightHeading2: t.headline.lineHeight,
    fontSizeHeading3: t['card-title'].fontSize, lineHeightHeading3: t['card-title'].lineHeight,
    fontSizeHeading4: t.subhead.fontSize, lineHeightHeading4: t.subhead.lineHeight,
    fontSizeHeading5: t['body-lg'].fontSize, lineHeightHeading5: t['body-lg'].lineHeight,
    borderRadius: r.md, borderRadiusXS: r.xs, borderRadiusSM: r.sm, borderRadiusLG: r.lg,
    controlHeight: 40, controlHeightLG: 44,
    marginXXS: s.xxs, marginXS: s.xs, marginSM: s.sm, margin: s.md, marginLG: s.lg, marginXL: s.xl, marginXXL: s.xxl,
    paddingXXS: s.xxs, paddingXS: s.xs, paddingSM: s.sm, padding: s.md, paddingLG: s.lg, paddingXL: s.xl,
  },
  components: {
    Layout: { bodyBg: c.canvas, siderBg: c.canvas, headerBg: c.canvas, headerColor: c.ink, triggerBg: c['surface-2'], triggerColor: c['ink-muted'], headerHeight: 64 },
    Menu: {
      darkItemBg: c.canvas, darkSubMenuItemBg: c['surface-3'], darkPopupBg: c['surface-3'],
      darkItemColor: c['ink-subtle'], darkItemHoverColor: c.ink, darkItemSelectedColor: c.ink,
      darkItemHoverBg: c['surface-2'], darkItemSelectedBg: c['surface-2'], darkItemDisabledColor: c['ink-tertiary'], itemBorderRadius: r.md,
    },
    Button: {
      primaryColor: c['on-primary'], dangerColor: c.canvas,
      defaultBg: c['surface-1'], defaultColor: c.ink, defaultBorderColor: c.hairline,
      defaultHoverBg: c['surface-2'], defaultHoverColor: c.ink, defaultHoverBorderColor: c['hairline-strong'],
      defaultActiveBg: c['surface-3'], defaultActiveColor: c.ink, defaultActiveBorderColor: c['hairline-tertiary'],
      textTextColor: c.ink, textTextHoverColor: c.ink, textTextActiveColor: c.ink, textHoverBg: c['surface-2'],
      fontWeight: t.button.fontWeight, paddingBlock: 8, paddingInline: 14,
      contentFontSize: t.button.fontSize, contentLineHeight: t.button.lineHeight,
      defaultShadow: 'none', primaryShadow: 'none', dangerShadow: 'none',
    },
    Input: inputTokens, InputNumber: inputTokens,
    Select: {
      ...popupTokens, selectorBg: c['surface-1'], clearBg: c['surface-1'],
      optionSelectedBg: c['surface-2'], optionSelectedColor: c.ink, optionSelectedFontWeight: 600,
      optionActiveBg: c['surface-4'], hoverBorderColor: c['hairline-tertiary'], activeBorderColor: c['primary-focus'], activeOutlineColor: c['primary-focus'],
    },
    Pagination: { itemActiveColor: c['primary-hover'], itemActiveColorHover: c['primary-hover'] },
    DatePicker: {
      ...inputTokens, ...popupTokens, colorTextQuaternary: c['ink-subtle'], colorTextDisabled: c['ink-tertiary'],
      cellHoverBg: c['surface-2'], cellActiveWithRangeBg: c['surface-2'],
      cellHoverWithRangeBg: c['surface-3'], cellRangeBorderColor: c['primary-focus'],
    },
    Card: { headerBg: c['surface-1'], actionsBg: c['surface-1'], bodyPadding: s.lg, headerPadding: s.lg, extraColor: c['ink-subtle'], colorBgContainer: c['surface-1'], borderRadiusLG: r.lg },
    Table: {
      headerBg: c['surface-2'], headerSortActiveBg: c['surface-2'], headerSortHoverBg: c['surface-2'], headerColor: c['ink-muted'],
      bodySortBg: c['surface-1'], rowHoverBg: c['surface-2'], rowSelectedBg: c['surface-2'], rowExpandedBg: c['surface-2'], rowSelectedHoverBg: c['surface-3'],
      borderColor: c.hairline, headerSplitColor: c.hairline, footerBg: c['surface-1'], footerColor: c['ink-muted'],
      filterDropdownBg: c['surface-3'], filterDropdownMenuBg: c['surface-3'], expandIconBg: c['surface-2'],
      headerBorderRadius: r.md, cellFontSize: t['body-sm'].fontSize, cellPaddingBlock: s.sm, cellPaddingInline: s.md,
    },
    Modal: { contentBg: c['surface-3'], headerBg: c['surface-3'], footerBg: c['surface-3'], titleColor: c.ink, titleFontSize: t['card-title'].fontSize, titleLineHeight: t['card-title'].lineHeight, borderRadiusLG: r.lg, colorText: c.ink },
    Tag: { ...semanticAliases, defaultBg: c['surface-2'], defaultColor: c['ink-muted'], borderRadiusSM: r.pill, fontSize: t.caption.fontSize, colorBorder: c.hairline },
    Alert: { ...semanticAliases, colorText: c.ink, colorTextHeading: c.ink, containerBorderRadius: r.md },
    Badge: { colorError: c['surface-4'], colorTextLightSolid: c.ink },
    Tabs: { itemColor: c['ink-subtle'], itemSelectedColor: c.ink, itemHoverColor: c.ink, itemActiveColor: c.ink, inkBarColor: c['ink-muted'], cardBg: c['surface-2'] },
    Descriptions: { labelBg: c['surface-2'], labelColor: c['ink-subtle'], contentColor: c.ink, titleColor: c.ink, extraColor: c['ink-muted'] },
    Message: popupTokens, Notification: popupTokens, Tooltip: popupTokens, Popover: popupTokens, Dropdown: popupTokens, Popconfirm: popupTokens,
    Spin: { colorText: c['ink-subtle'] },
  },
};

export const linearCssVariables: Record<string, string> = {
  ...Object.fromEntries(Object.entries(c).map(([name, value]) => [`--linear-${name}`, value])),
  ...Object.fromEntries(Object.entries(s).map(([name, value]) => [`--linear-space-${name}`, `${value}px`])),
  ...Object.fromEntries(Object.entries(r).map(([name, value]) => [`--linear-radius-${name}`, `${value}px`])),
  ...Object.fromEntries(Object.entries(t).flatMap(([name, value]) => [
    [`--linear-${name}-size`, `${value.fontSize}px`], [`--linear-${name}-weight`, String(value.fontWeight)],
    [`--linear-${name}-line-height`, String(value.lineHeight)], [`--linear-${name}-tracking`, `${value.letterSpacing}px`],
  ])),
  ...Object.fromEntries(Object.entries(linearTokens.status).flatMap(([name, value]) => [
    [`--linear-${name}`, value.foreground], [`--linear-${name}-bg`, value.background], [`--linear-${name}-border`, value.border],
  ])),
  '--linear-font-sans': linearTokens.fonts.sans,
  '--linear-font-mono': linearTokens.fonts.mono,
  '--linear-overlay': c['semantic-overlay'],
};

export function applyLinearCssVariables(root: HTMLElement): void {
  for (const [name, value] of Object.entries(linearCssVariables)) root.style.setProperty(name, value);
}
