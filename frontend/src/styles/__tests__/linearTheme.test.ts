import { describe, expect, it, vi } from 'vitest';
import { theme } from 'antd';
import { applyLinearCssVariables, linearCssVariables, linearTheme, linearTokens } from '../linearTheme';

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

const surfaces = ['canvas', 'surface-1', 'surface-2', 'surface-3', 'surface-4'] as const;

describe('linear-restyle-ac-dod-v1 AC-2 single-source theme', () => {
  it('uses only the dark algorithm and approved palette', () => {
    const algorithm = linearTheme.algorithm;
    expect(typeof algorithm).toBe('function');
    if (typeof algorithm !== 'function') throw new Error('Expected one dark mapping algorithm');
    const restored = {
      colorPrimary: linearTokens.colors.primary, colorLink: linearTokens.colors['primary-hover'],
      colorSuccess: linearTokens.status.success.foreground, colorError: linearTokens.status.error.foreground,
      colorWarning: linearTokens.status.warning.foreground, colorInfo: linearTokens.status.info.foreground,
    };
    for (const overrides of [{}, { fontSize: 18, borderRadius: 12 }]) {
      const seed = Object.freeze({ ...theme.defaultSeed, ...linearTheme.token, ...overrides });
      const before = JSON.stringify(seed);
      const map = Object.freeze(theme.darkAlgorithm(seed));
      const mapBefore = JSON.stringify(map);
      const expected = theme.darkAlgorithm(seed, map);
      const spy = vi.spyOn(theme, 'darkAlgorithm');
      try {
        const actual = algorithm(seed, map);
        expect(spy).toHaveBeenCalledExactlyOnceWith(seed, map);
        expect(actual).toEqual({ ...expected, ...restored });
        expect(actual).not.toBe(spy.mock.results[0].value);
        expect(spy.mock.results[0].value).toEqual(expected);
        expect(algorithm(seed, map)).toEqual(actual);
        expect(JSON.stringify(seed)).toBe(before);
        expect(JSON.stringify(map)).toBe(mapBefore);
      } finally {
        spy.mockRestore();
      }
    }
    expect(linearTokens.colors).toMatchObject({
      canvas: '#010102', 'surface-1': '#0f1011', 'surface-2': '#141516',
      'surface-3': '#18191a', 'surface-4': '#191a1b', primary: '#5e6ad2',
      'primary-hover': '#828fff', 'primary-focus': '#5e69d1', ink: '#f7f8f8',
    });
  });

  it('maps every color, spacing, radius and typography token to root variables', () => {
    for (const [name, value] of Object.entries(linearTokens.colors)) {
      expect(linearCssVariables[`--linear-${name}`]).toBe(value);
    }
    for (const [name, value] of Object.entries(linearTokens.spacing)) {
      expect(linearCssVariables[`--linear-space-${name}`]).toBe(`${value}px`);
    }
    for (const [name, value] of Object.entries(linearTokens.rounded)) {
      expect(linearCssVariables[`--linear-radius-${name}`]).toBe(`${value}px`);
    }
    for (const [name, value] of Object.entries(linearTokens.typography)) {
      expect(linearCssVariables[`--linear-${name}-size`]).toBe(`${value.fontSize}px`);
      expect(linearCssVariables[`--linear-${name}-weight`]).toBe(String(value.fontWeight));
      expect(linearCssVariables[`--linear-${name}-line-height`]).toBe(String(value.lineHeight));
      expect(linearCssVariables[`--linear-${name}-tracking`]).toBe(`${value.letterSpacing}px`);
    }
  });

  it('F-01 uses readable pagination text without changing solid primary colors', () => {
    expect(linearTheme.components?.Pagination).toMatchObject({
      itemActiveColor: linearTokens.colors['primary-hover'],
      itemActiveColorHover: linearTokens.colors['primary-hover'],
    });
    expect(theme.getDesignToken(linearTheme).colorPrimary).toBe(linearTokens.colors.primary);
  });

  it('F-03 keeps enabled outside-month dates readable and disabled dates distinct', () => {
    expect(linearTheme.components?.DatePicker).toMatchObject({
      colorTextQuaternary: linearTokens.colors['ink-subtle'],
      colorTextDisabled: linearTokens.colors['ink-tertiary'],
    });
    expect(theme.getDesignToken(linearTheme).colorTextQuaternary).toBe(linearTokens.colors['ink-tertiary']);
  });

  it('initializes synchronously and idempotently without adding DOM nodes', () => {
    const root = document.createElement('div');
    root.style.setProperty('--unrelated', 'keep');
    applyLinearCssVariables(root);
    for (const [name, value] of Object.entries(linearCssVariables)) {
      expect(root.style.getPropertyValue(name)).toBe(value);
    }
    const first = root.outerHTML;
    applyLinearCssVariables(root);
    expect(root.outerHTML).toBe(first);
    expect(root.children).toHaveLength(0);
    expect(root.style.getPropertyValue('--unrelated')).toBe('keep');
  });

  it('preserves explicit aliases after antd algorithm resolution', () => {
    const resolved = theme.getDesignToken(linearTheme);
    const c = linearTokens.colors;
    expect(resolved).toMatchObject({
      colorBgBase: c.canvas, colorBgLayout: c.canvas, colorBgContainer: c['surface-1'],
      colorBgElevated: c['surface-3'], colorBorder: c['hairline-strong'],
      colorText: c.ink, colorTextSecondary: c['ink-muted'], colorTextTertiary: c['ink-subtle'],
      colorTextPlaceholder: c['ink-subtle'], colorTextDisabled: c['ink-tertiary'],
      colorPrimary: c.primary, colorPrimaryHover: c['primary-hover'], colorPrimaryActive: c['primary-focus'],
      colorLink: c['primary-hover'], colorLinkActive: c['primary-hover'],
      borderRadius: 8, borderRadiusLG: 12, controlHeight: 40, controlHeightLG: 44,
      controlOutline: c['primary-focus'], controlOutlineWidth: 2, lineWidthFocus: 2,
      boxShadow: 'none', boxShadowSecondary: 'none', boxShadowTertiary: 'none',
      fontFamily: linearTokens.fonts.sans,
    });
    expect(linearTheme.components?.Button).toMatchObject({ primaryColor: c['on-primary'], dangerColor: c.canvas });
    expect(linearTheme.components?.Menu).toMatchObject({ darkItemSelectedBg: c['surface-2'], darkItemSelectedColor: c.ink });
    expect(linearTheme.components?.Modal).toMatchObject({ contentBg: c['surface-3'] });
    expect(linearTheme.components?.Table).toMatchObject({ rowHoverBg: c['surface-2'] });
  });
});

describe('AC-3/4 approved text contrast (not browser evidence)', () => {
  it.each(['ink', 'ink-muted', 'ink-subtle', 'primary-hover'] as const)('%s meets 4.5 on all surfaces', (foreground) => {
    for (const surface of surfaces) expect(contrast(linearTokens.colors[foreground], linearTokens.colors[surface])).toBeGreaterThanOrEqual(4.5);
  });

  it('keeps default/pressed white text and approved dark hover text readable', () => {
    const c = linearTokens.colors;
    expect(contrast(c['on-primary'], c.primary)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c['on-primary'], c['primary-focus'])).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c.canvas, c['primary-hover'])).toBeGreaterThanOrEqual(4.5);
    expect(contrast(c['on-primary'], c['primary-hover'])).toBeLessThan(4.5);
    expect(contrast(c.canvas, linearTokens.status.error.foreground)).toBeGreaterThanOrEqual(4.5);
  });

  it('maps exact semantic foreground/background/border and readable aliases', () => {
    expect(linearTokens.status).toEqual({
      success: { foreground: '#27a644', background: '#16261c', border: '#1a4325' },
      error: { foreground: '#c18889', background: '#292324', border: '#4b3a3b' },
      warning: { foreground: '#cdae7f', background: '#2a2723', border: '#4f4638' },
      info: { foreground: '#7a7fad', background: '#141516', border: '#34343a' },
    });
    const resolved = theme.getDesignToken(linearTheme);
    for (const [name, value] of Object.entries(linearTokens.status)) {
      const prefix = `color${name[0].toUpperCase()}${name.slice(1)}`;
      const aliases = resolved as unknown as Record<string, unknown>;
      for (const suffix of ['', 'Text', 'TextHover', 'TextActive', 'Hover', 'Active']) expect(aliases[prefix + suffix]).toBe(value.foreground);
      expect(aliases[prefix + 'Bg']).toBe(value.background);
      expect(aliases[prefix + 'Border']).toBe(value.border);
      expect(linearCssVariables[`--linear-${name}`]).toBe(value.foreground);
      expect(contrast(value.foreground, value.background)).toBeGreaterThanOrEqual(4.5);
      for (const surface of surfaces) expect(contrast(value.foreground, linearTokens.colors[surface])).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('excludes ink-tertiary only for disabled/decorative content, not hints', () => {
    const c = linearTokens.colors;
    expect(contrast(c['ink-tertiary'], c['surface-1'])).toBeLessThan(4.5);
    expect(linearTheme.token?.colorTextPlaceholder).toBe(c['ink-subtle']);
    expect(linearTheme.token?.colorTextDescription).toBe(c['ink-subtle']);
  });
});
