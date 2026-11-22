import { describe, expect, it } from 'vitest';
import { theme } from 'antd';
import * as claudeModule from '../claudeTheme';
import { applyClaudeCssVariables, claudeCssVariables, claudeTheme, claudeTokens } from '../claudeTheme';

function contrast(foreground: string, background: string) {
  const luminance = (hex: string) => {
    const channels = hex.slice(1).match(/../g)!.map((pair) => {
      const channel = parseInt(pair, 16) / 255;
      return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
    });
    return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722;
  };
  const a = luminance(foreground), b = luminance(background);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

const surfaces = ['canvas', 'surface-soft', 'surface-card', 'surface-cream-strong'] as const;

describe('claude-light-ac-dod-v1 AC-2 single-source Claude light theme', () => {
  it('uses the public default algorithm and preserves the six approved seeds', () => {
    expect(claudeTheme.algorithm).toBe(theme.defaultAlgorithm);
    const configBefore = JSON.stringify(claudeTheme);
    const resolved = theme.getDesignToken(claudeTheme);
    expect(resolved.colorPrimary).toBe('#a9583e');
    expect(resolved.colorLink).toBe('#934b34');
    expect(resolved.colorSuccess).toBe(claudeTokens.status.success.foreground);
    expect(resolved.colorError).toBe(claudeTokens.status.error.foreground);
    expect(resolved.colorWarning).toBe(claudeTokens.status.warning.foreground);
    expect(resolved.colorInfo).toBe(claudeTokens.status.info.foreground);
    // Resolution must be deterministic and must not mutate the shared config.
    expect(JSON.stringify(theme.getDesignToken(claudeTheme))).toBe(JSON.stringify(resolved));
    expect(JSON.stringify(claudeTheme)).toBe(configBefore);
    expect(claudeTokens.colors).toMatchObject({
      canvas: '#faf9f5', 'surface-soft': '#f5f0e8', 'surface-card': '#efe9de', 'surface-cream-strong': '#e8e0d2',
      ink: '#141413', body: '#3d3d3a', 'text-muted': '#625f58', 'text-disabled': '#8e8b82',
      hairline: '#e6dfd8', 'control-border': '#817d73', 'brand-primary': '#cc785c', 'on-primary': '#ffffff',
      primary: '#a9583e', 'primary-hover': '#934b34', 'primary-active': '#7d3d2a',
    });
  });

  it('maps every color, spacing, radius and typography token to --claude root variables', () => {
    for (const [name, value] of Object.entries(claudeTokens.colors)) {
      expect(claudeCssVariables[`--claude-${name}`]).toBe(value);
    }
    for (const [name, value] of Object.entries(claudeTokens.spacing)) {
      expect(claudeCssVariables[`--claude-space-${name}`]).toBe(`${value}px`);
    }
    for (const [name, value] of Object.entries(claudeTokens.rounded)) {
      expect(claudeCssVariables[`--claude-radius-${name}`]).toBe(`${value}px`);
    }
    for (const [name, value] of Object.entries(claudeTokens.typography)) {
      expect(claudeCssVariables[`--claude-${name}-size`]).toBe(`${value.fontSize}px`);
      expect(claudeCssVariables[`--claude-${name}-weight`]).toBe(String(value.fontWeight));
      expect(claudeCssVariables[`--claude-${name}-line-height`]).toBe(String(value.lineHeight));
      expect(claudeCssVariables[`--claude-${name}-tracking`]).toBe(`${value.letterSpacing}px`);
    }
    expect(claudeCssVariables['--claude-font-display']).toBe(claudeTokens.fonts.display);
    expect(claudeCssVariables['--claude-font-sans']).toBe(claudeTokens.fonts.sans);
    expect(claudeCssVariables['--claude-font-mono']).toBe(claudeTokens.fonts.mono);
    expect(Object.keys(claudeCssVariables).some((name) => name.includes('linear'))).toBe(false);
  });

  it('F-01 pagination uses readable deep link text without changing solid primary colors', () => {
    expect(claudeTheme.components?.Pagination).toMatchObject({
      itemActiveColor: claudeTokens.colors.link,
      itemActiveColorHover: claudeTokens.colors['link-active'],
    });
    expect(theme.getDesignToken(claudeTheme).colorPrimary).toBe(claudeTokens.colors.primary);
  });

  it('F-03 keeps enabled outside-month dates readable and disabled dates distinct', () => {
    expect(claudeTheme.components?.DatePicker).toMatchObject({
      colorTextQuaternary: claudeTokens.colors['text-muted'],
      colorTextDisabled: claudeTokens.colors['text-disabled'],
    });
    expect(theme.getDesignToken(claudeTheme).colorTextQuaternary).toBe(claudeTokens.colors['text-disabled']);
  });

  it('initializes synchronously and idempotently without adding DOM nodes', () => {
    const root = document.createElement('div');
    root.style.setProperty('--unrelated', 'keep');
    applyClaudeCssVariables(root);
    for (const [name, value] of Object.entries(claudeCssVariables)) {
      expect(root.style.getPropertyValue(name)).toBe(value);
    }
    const first = root.outerHTML;
    applyClaudeCssVariables(root);
    expect(root.outerHTML).toBe(first);
    expect(root.children).toHaveLength(0);
    expect(root.style.getPropertyValue('--unrelated')).toBe('keep');
  });

  it('preserves explicit aliases after public defaultAlgorithm resolution', () => {
    const resolved = theme.getDesignToken(claudeTheme);
    const c = claudeTokens.colors;
    expect(resolved).toMatchObject({
      colorBgBase: c.canvas, colorBgLayout: c.canvas, colorBgContainer: c['surface-soft'],
      colorBgElevated: c.canvas, colorBgSpotlight: c.canvas, colorBorder: c['control-border'],
      colorBorderSecondary: c.hairline, colorSplit: c.hairline,
      colorText: c.body, colorTextHeading: c.ink, colorTextSecondary: c['text-muted'],
      colorTextTertiary: c['text-muted'], colorTextPlaceholder: c['text-muted'],
      colorTextDescription: c['text-muted'], colorTextDisabled: c['text-disabled'],
      colorTextLightSolid: c['on-primary'],
      colorPrimary: c.primary, colorPrimaryHover: c['primary-hover'], colorPrimaryActive: c['primary-active'],
      colorLink: c.link, colorLinkHover: c['link-active'], colorLinkActive: c['link-active'],
      colorPrimaryText: c.link, colorPrimaryTextHover: c['link-active'], colorPrimaryTextActive: c['link-active'],
      borderRadius: 8, borderRadiusLG: 12, controlHeight: 40, controlHeightLG: 44,
      controlOutline: c.link, controlOutlineWidth: 2, lineWidthFocus: 2,
      boxShadow: 'none', boxShadowTertiary: 'none',
      fontFamily: claudeTokens.fonts.sans,
    });
    expect(claudeTheme.components?.Button).toMatchObject({ primaryColor: c['on-primary'], dangerColor: c['on-primary'] });
    expect(claudeTheme.components?.Menu).toMatchObject({ itemBg: c['surface-soft'], itemSelectedBg: c['surface-cream-strong'], itemSelectedColor: c.ink });
    expect(Object.keys(claudeTheme.components?.Menu ?? {}).some((name) => name.startsWith('dark'))).toBe(false);
    expect(claudeTheme.components?.Layout).toMatchObject({ siderBg: c['surface-soft'], headerBg: c.canvas });
    expect(claudeTheme.components?.Modal).toMatchObject({ contentBg: c.canvas });
    expect(claudeTheme.components?.Table).toMatchObject({ headerBg: c['surface-card'], rowHoverBg: c['surface-card'] });
    // Tooltip must locally flip to light background + dark text; global LightSolid stays white for solid buttons/dates.
    expect(claudeTheme.components?.Tooltip).toMatchObject({ colorBgSpotlight: c.canvas, colorTextLightSolid: c.body });
  });

  it('favicon keeps the original book geometry with token-derived colors', () => {
    const uri = (claudeModule as { claudeFaviconUri?: string }).claudeFaviconUri;
    expect(typeof uri).toBe('string');
    expect(uri!.startsWith('data:image/svg+xml,')).toBe(true);
    const svg = decodeURIComponent(uri!.slice('data:image/svg+xml,'.length));
    const c = claudeTokens.colors;
    expect(svg).toContain(`fill='${c['brand-primary']}'`);
    expect(svg).toContain(`fill='${c.canvas}'`);
    expect(svg).toContain(`fill='${c['surface-cream-strong']}'`);
    // Static geometry of the existing book icon must be preserved byte-for-byte.
    expect(svg).toContain("d='M9 8h6a3 3 0 0 1 3 3v13a2.5 2.5 0 0 0-2.5-2.5H9z'");
    expect(svg).toContain("d='M23 8h-6a3 3 0 0 0-3 3v13a2.5 2.5 0 0 1 2.5-2.5H23z'");
    expect(svg).toContain("viewBox='0 0 32 32'");
  });
});

describe('AC-3/4 approved text contrast (static sRGB, not browser evidence)', () => {
  it.each(['ink', 'body', 'text-muted', 'link'] as const)('%s meets 4.5 on all four cream surfaces', (foreground) => {
    for (const surface of surfaces) expect(contrast(claudeTokens.colors[foreground], claudeTokens.colors[surface])).toBeGreaterThanOrEqual(4.5);
  });

  it('keeps white text readable on all three solid primary states; original coral stays brand-only', () => {
    const c = claudeTokens.colors;
    expect(contrast(c['on-primary'], c.primary)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c['on-primary'], c['primary-hover'])).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c['on-primary'], c['primary-active'])).toBeGreaterThanOrEqual(4.5);
    // Q-CLR-01 approved decision: white on the original coral fails AA, so coral is never a small-text surface.
    expect(contrast(c['on-primary'], c['brand-primary'])).toBeLessThan(4.5);
    expect(contrast(c['on-primary'], claudeTokens.status.error.foreground)).toBeGreaterThanOrEqual(4.5);
    for (const surface of surfaces) expect(contrast(c['link-active'], c[surface])).toBeGreaterThanOrEqual(4.5);
  });

  it('maps exact semantic foreground/background/border and readable aliases', () => {
    expect(claudeTokens.status).toEqual({
      success: { foreground: '#2f6b40', background: '#eaf3e8', border: '#d3e9d4' },
      warning: { foreground: '#805c14', background: '#f6f0df', border: '#f1e3be' },
      error: { foreground: '#a53636', background: '#f5e7e3', border: '#edccc9' },
      info: { foreground: '#28695e', background: '#eaf3ed', border: '#d3e9e1' },
    });
    const resolved = theme.getDesignToken(claudeTheme);
    for (const [name, value] of Object.entries(claudeTokens.status)) {
      const prefix = `color${name[0].toUpperCase()}${name.slice(1)}`;
      const aliases = resolved as unknown as Record<string, unknown>;
      for (const suffix of ['', 'Text', 'TextHover', 'TextActive', 'Hover', 'Active']) expect(aliases[prefix + suffix]).toBe(value.foreground);
      for (const suffix of ['Bg', 'BgHover']) expect(aliases[prefix + suffix]).toBe(value.background);
      for (const suffix of ['Border', 'BorderHover']) expect(aliases[prefix + suffix]).toBe(value.border);
      expect(claudeCssVariables[`--claude-${name}`]).toBe(value.foreground);
      expect(contrast(value.foreground, value.background)).toBeGreaterThanOrEqual(4.5);
      for (const surface of surfaces) expect(contrast(value.foreground, claudeTokens.colors[surface])).toBeGreaterThanOrEqual(4.5);
    }
    expect(resolved.colorHighlight).toBe(claudeTokens.status.error.foreground);
  });

  it('excludes text-disabled only for genuinely disabled content, not hints', () => {
    const c = claudeTokens.colors;
    expect(contrast(c['text-disabled'], c['surface-soft'])).toBeLessThan(4.5);
    expect(claudeTheme.token?.colorTextPlaceholder).toBe(c['text-muted']);
    expect(claudeTheme.token?.colorTextDescription).toBe(c['text-muted']);
  });

  it('focus ring and control borders stay visible (>=3) on every cream surface', () => {
    const c = claudeTokens.colors;
    for (const surface of surfaces) {
      expect(contrast(c.link, c[surface])).toBeGreaterThanOrEqual(3);
      expect(contrast(c['control-border'], c[surface])).toBeGreaterThanOrEqual(3);
    }
    expect(claudeTheme.token?.controlOutline).toBe(c.link);
    expect(claudeTheme.token?.controlOutlineWidth).toBe(2);
    expect(claudeTheme.token?.lineWidthFocus).toBe(2);
  });

  it('serif display and system sans body come from the same token source', () => {
    expect(claudeTokens.fonts.display).toBe("Georgia, 'Songti SC', STSong, SimSun, serif");
    expect(claudeTokens.fonts.sans).toBe("-apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Segoe UI', sans-serif");
    expect(theme.getDesignToken(claudeTheme).fontFamily).toBe(claudeTokens.fonts.sans);
    expect(claudeTokens.typography.headline).toMatchObject({ fontSize: 28, fontWeight: 400 });
    expect(claudeTokens.typography['headline-sm'].fontSize).toBe(24);
    expect(claudeTokens.typography.body.lineHeight).toBe(1.55);
    expect(claudeTokens.typography['body-sm'].fontSize).toBe(14);
  });
});