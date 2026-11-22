import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { realpathSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  ADMIN_PASSWORD, ADMIN_USERNAME, READER_PASSWORD, apiGet, apiLogin, apiLogout,
  apiPost, loginViaUi, seedBook, seedLoan, seedReader, seedReminderLoan, uniqueSuffix,
} from './helpers';
import type { Notification, Page as ApiPage } from '../src/api/types';

const desktop = { width: 1440, height: 900 };
const mobile = { width: 390, height: 844 };
test.beforeEach(async ({ page }) => {
  await page.setViewportSize(desktop);
  await page.emulateMedia({ reducedMotion: 'reduce' });
});

async function ready(page: Page) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-spin-spinning')).toHaveCount(0);
}

async function capture(page: Page, info: TestInfo, name: string, role: string, mock = false, scope = 'body') {
  await expect(page.locator(scope).first()).toBeVisible();
  if (scope === '.ant-modal-container') await expect(page.locator('.ant-modal').first()).not.toHaveClass(/(?:enter|appear)(?:-active|-prepare)?(?:\s|$)/);
  await expect.poll(() => page.locator(scope).first().evaluate(element => {
    for (let node: Element | null = element; node; node = node.parentElement) {
      if (Number(getComputedStyle(node).opacity) !== 1) return false;
    }
    return element.getBoundingClientRect().width > 0;
  })).toBe(true);
  await page.evaluate(async () => {
    let stableFrames = 0;
    while (stableFrames < 2) {
      await new Promise<void>(resolve => requestAnimationFrame(() => resolve()));
      const finite = document.getAnimations().filter(animation => animation.effect?.getTiming().iterations !== Infinity && (animation.playState === 'running' || animation.pending));
      if (finite.length) {
        stableFrames = 0;
        await Promise.all(finite.map(animation => animation.finished.catch(() => undefined)));
      } else stableFrames++;
    }
  });
  const image = info.outputPath(`${name}.png`);
  const maskColor = await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--claude-surface-cream-strong').trim());
  await page.screenshot({ path: image, fullPage: true, maskColor, mask: [page.locator('input[type="password"]')] });
  await info.attach(name, { path: image, contentType: 'image/png' });
  const samples = await page.locator(scope).first().evaluate((root) => {
    type Color = [number, number, number, number];
    const parse = (value: string): Color => {
      const parts = value.match(/[\d.]+/g)?.map(Number);
      if (!parts || !value.startsWith('rgb')) throw new Error(`Unresolved computed color: ${value}`);
      return [parts[0], parts[1], parts[2], parts[3] ?? 1];
    };
    const over = (top: Color, bottom: Color): Color => {
      const a = top[3] + bottom[3] * (1 - top[3]);
      return [0, 1, 2].map(i => a ? (top[i] * top[3] + bottom[i] * bottom[3] * (1 - top[3])) / a : 0).concat(a) as Color;
    };
    const luminance = (color: Color) => color.slice(0, 3).map(v => v / 255).map(v => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4).reduce((sum, v, i) => sum + v * [0.2126, 0.7152, 0.0722][i], 0);
    const results: { text: string; selector: string; foreground: string; background: string; effectiveForeground: Color; effectiveBackground: Color; opacity: number[]; ratio: number; excluded: string | null; calendarLayers: { cellClass: string; background: string; height: string; transform: string; zIndex: string }[] }[] = [];
    for (const element of [root, ...root.querySelectorAll('*')]) {
      if (!(element instanceof HTMLElement)) continue;
      const rect = element.getBoundingClientRect();
      if (!rect.width || !rect.height || rect.right <= 0 || rect.left >= innerWidth) continue;
      const chain: HTMLElement[] = [];
      for (let node: HTMLElement | null = element; node; node = node.parentElement) chain.unshift(node);
      if (chain.some(node => { const s = getComputedStyle(node); return s.display === 'none' || s.visibility === 'hidden' || Number(s.opacity) === 0; })) continue;
      const field = element instanceof HTMLTextAreaElement || (element instanceof HTMLInputElement && !['checkbox', 'radio', 'hidden'].includes(element.type)) ? element : null;
      const value = field instanceof HTMLInputElement && field.type === 'password' ? '' : field?.value;
      const text = [...element.childNodes].filter(n => n.nodeType === Node.TEXT_NODE).map(n => n.textContent).join('').trim() || value || '';
      const placeholder = field && !field.value ? field.placeholder : '';
      if (!text && !placeholder) continue;
      const pseudo = placeholder ? '::placeholder' : undefined;
      const style = getComputedStyle(element, pseudo);
      const backgrounds: Color[] = [[255, 255, 255, 1]];
      const calendarLayers: { cellClass: string; background: string; height: string; transform: string; zIndex: string }[] = [];
      for (const node of chain) {
        let background = over(parse(getComputedStyle(node).backgroundColor), backgrounds[backgrounds.length - 1]);
        if (node.matches('.ant-picker-cell')) {
          // The range/disabled strip is a z-index:1 ::before behind the z-index:2 date text.
          const before = getComputedStyle(node, '::before');
          if (before.content !== 'none' && before.display !== 'none') {
            const layer = parse(before.backgroundColor);
            layer[3] *= Number(before.opacity);
            background = over(layer, background);
            calendarLayers.push({ cellClass: node.className, background: before.backgroundColor, height: before.height, transform: before.transform, zIndex: before.zIndex });
          }
        }
        backgrounds.push(background);
      }
      let background = backgrounds[backgrounds.length - 1];
      let foreground = over(parse(style.color), background);
      const opacities: number[] = [];
      for (let i = chain.length - 1; i >= 0; i--) {
        const opacity = Number(getComputedStyle(chain[i]).opacity) * (i === chain.length - 1 && pseudo ? Number(style.opacity) : 1);
        opacities.push(opacity);
        foreground = over([foreground[0], foreground[1], foreground[2], opacity], backgrounds[i]);
        background = over([background[0], background[1], background[2], opacity], backgrounds[i]);
      }
      const a = luminance(foreground), b = luminance(background);
      const disabled = element.closest(':disabled, [aria-disabled="true"], .ant-select-disabled, .ant-picker-cell-disabled, .ant-pagination-disabled, .ant-btn-disabled')
        || element.closest('label')?.querySelector('input:disabled');
      results.push({
        text: placeholder ? `placeholder: ${placeholder}` : text.slice(0, 100),
        selector: chain.slice(-3).map(el => el.tagName.toLowerCase() + (el.className ? '.' + el.className.trim().split(/\s+/).join('.') : '')).join(' > '),
        foreground: style.color, background: getComputedStyle(element).backgroundColor,
        effectiveForeground: foreground, effectiveBackground: background, opacity: opacities,
        ratio: (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05), excluded: disabled ? 'genuinely disabled control' : null, calendarLayers,
      });
    }
    return results;
  });
  const failures = samples.filter(s => !s.excluded && s.ratio < 4.5);
  const overflow = await page.evaluate(() => ({ body: document.body.scrollWidth, viewport: innerWidth }));
  const computedFile = info.outputPath(`${name}-computed.json`);
  writeFileSync(computedFile, JSON.stringify({ route: new URL(page.url()).pathname, role, viewport: page.viewportSize(), state: name, mock, kind: name.startsWith('fixture-') ? 'isolated real-component harness' : 'application', browser: 'Chromium', samples, failures, overflow }, null, 2));
  await info.attach(`${name}-computed`, { path: computedFile, contentType: 'application/json' });
  expect.soft(samples.length, `${name}: actual computed text samples`).toBeGreaterThan(0);
  expect.soft(failures, `${name}: all enabled text/placeholder contrast >=4.5`).toEqual([]);
  expect.soft(overflow.body, `${name}: no body horizontal overflow`).toBeLessThanOrEqual(overflow.viewport);
  await expect.soft(page.locator('body')).toHaveCSS('background-color', 'rgb(250, 249, 245)');
  return { samples, failures, overflow };
}

async function visit(page: Page, info: TestInfo, route: string, name: string, role: string) {
  await page.goto(route);
  await ready(page);
  await capture(page, info, name, role);
}

test('AC-2/3 login default hover pressed focus and validation at both widths', async ({ page }, info) => {
  await page.goto('/login');
  await ready(page);
  const button = page.getByRole('button', { name: /登\s*录/ });
  await page.getByLabel('用户名').blur();
  await expect(button).toHaveCSS('background-color', 'rgb(169, 88, 62)');
  await expect(button).toHaveCSS('color', 'rgb(255, 255, 255)');
  await capture(page, info, 'login-default', 'anonymous');
  await page.getByLabel('用户名').focus();
  await expect(page.locator('.ant-input-affix-wrapper').first()).toHaveCSS('outline-width', '2px');
  await expect(page.locator('.ant-input-affix-wrapper').first()).toHaveCSS('outline-color', 'rgb(147, 75, 52)');
  const focusCascade = await page.getByLabel('用户名').evaluate(element => {
    const matches: { selector: string; style: string }[] = [];
    for (const sheet of document.styleSheets) for (const rule of sheet.cssRules) {
      if (rule instanceof CSSStyleRule && rule.style.cssText.includes('outline') && element.matches(rule.selectorText)) matches.push({ selector: rule.selectorText, style: rule.style.cssText });
    }
    return { focusVisible: element.matches(':focus-visible'), outline: getComputedStyle(element).outline, ancestry: [element, element.parentElement, element.parentElement?.parentElement].map(node => node?.className), matches };
  });
  await info.attach('normal-input-focus-cascade', { body: JSON.stringify(focusCascade, null, 2), contentType: 'application/json' });
  await expect(page.getByLabel('用户名')).toHaveCSS('outline-style', 'none');
  await capture(page, info, 'login-normal-focus', 'anonymous');
  await page.getByLabel('用户名').press('Tab');
  await expect(page.getByLabel('密码')).toBeFocused();
  await page.getByLabel('密码').press('Tab');
  const visibilityToggle = page.locator('.ant-input-password-icon');
  await expect(visibilityToggle).toBeFocused();
  await expect(visibilityToggle).toHaveCSS('outline-width', '2px');
  await expect(visibilityToggle).toHaveCSS('outline-color', 'rgb(147, 75, 52)');
  await visibilityToggle.press('Tab');
  await expect(button).toBeFocused();
  await expect(button).toHaveCSS('outline-width', '2px');
  await expect(button).toHaveCSS('outline-color', 'rgb(147, 75, 52)');
  await capture(page, info, 'login-keyboard-button', 'anonymous');
  await button.hover();
  await expect(button).toHaveCSS('background-color', 'rgb(147, 75, 52)');
  await expect(button).toHaveCSS('color', 'rgb(255, 255, 255)');
  await capture(page, info, 'login-hover', 'anonymous');
  await page.mouse.down();
  await expect(button).toHaveCSS('background-color', 'rgb(125, 61, 42)');
  await expect(button).toHaveCSS('color', 'rgb(255, 255, 255)');
  await capture(page, info, 'login-pressed', 'anonymous');
  await page.mouse.up();
  await expect(page.getByText('请输入用户名', { exact: true })).toBeVisible();
  await capture(page, info, 'login-validation', 'anonymous');
  await page.getByLabel('用户名').focus();
  await expect.soft(page.locator('.ant-input-affix-wrapper').first()).toHaveCSS('outline-color', 'rgb(147, 75, 52)');
  await expect.soft(page.locator('.ant-input-affix-wrapper').first()).toHaveCSS('outline-width', '2px');
  await capture(page, info, 'login-focus', 'anonymous');
  await page.getByLabel('密码').focus();
  await capture(page, info, 'password-focus', 'anonymous');
  await page.setViewportSize(mobile);
  await capture(page, info, 'login-mobile', 'anonymous');
});

test('AC-3 ADMIN all primary routes use real authentication and data', async ({ page }, info) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  await expect(page).toHaveURL('/admin/dashboard');
  const { book } = await seedBook(page);
  const { reader } = await seedReader(page);
  const routes = [
    '/admin/dashboard', '/admin/books', '/admin/books/new', `/admin/books/${book.id}/edit`,
    '/admin/categories', '/admin/readers', `/admin/readers/${reader.id}`, '/admin/circulation',
    '/admin/loans', '/admin/stats', '/admin/reports/overdue', '/admin/dunning', '/books', `/books/${book.id}`, '/account/password',
  ];
  for (const [index, route] of routes.entries()) await visit(page, info, route, `admin-route-${index}`, 'ADMIN');
});

test('AC-3/4 READER routes overdue/history/unread/read and real 403/404', async ({ page }, info) => {
  await loginViaUi(page, 'reader2', 'Reader@123');
  await expect(page).toHaveURL('/books');
  const books = await apiGet<ApiPage<{ id: number }>>(page, '/api/books');
  for (const [index, route] of ['/books', `/books/${books.items[0].id}`, '/my/loans', '/my/profile', '/my/notifications', '/account/password'].entries()) {
    await visit(page, info, route, `reader-route-${index}`, 'READER');
  }
  await page.goto('/my/loans'); await ready(page);
  await expect(page.locator('.ant-tag-error').first()).toContainText('逾期');
  await page.getByRole('tab', { name: /历史/ }).click(); await ready(page);
  await capture(page, info, 'reader-history', 'READER');
  await page.goto('/my/notifications'); await ready(page);
  await expect(page.locator('.notification-unread').first()).toBeVisible();
  await capture(page, info, 'reader-unread', 'READER');
  await page.getByRole('button', { name: '标为已读', exact: true }).first().click();
  await expect(page.locator('.notification-unread')).toHaveCount(0);
  await expect(page.locator('.ant-badge-count')).toHaveCount(0);
  await ready(page);
  await capture(page, info, 'reader-read', 'READER');
  await page.goto('/admin/dashboard');
  await expect(page.getByText('无权限访问该页面')).toBeVisible();
  await capture(page, info, 'reader-real-403', 'READER');
  await page.goto('/claude-not-existing');
  await expect(page.getByText('页面不存在')).toBeVisible();
  await capture(page, info, 'reader-404', 'READER');
});

for (const viewport of [desktop, mobile]) {
  for (const state of [
    { code: '403', route: '/admin/dashboard', expectedRoute: '/403', subtitle: '无权限访问该页面', icon: 'lock' },
    { code: '404', route: '/claude-not-existing', expectedRoute: '/claude-not-existing', subtitle: '页面不存在', icon: 'search' },
  ]) {
    test(`ACC-D01 real READER ${state.code} monochrome graphic and return navigation at ${viewport.width}px`, async ({ page }, info) => {
      await page.setViewportSize(viewport);
      await loginViaUi(page, 'reader2', 'Reader@123');
      await expect(page).toHaveURL('/books');
      expect((await apiGet<{ role: string }>(page, '/api/auth/me')).role).toBe('READER');
      await page.goto(state.route); await ready(page);
      await expect(page).toHaveURL(state.expectedRoute);
      const result = page.locator('.ant-result');
      await expect(result.locator('.ant-result-title')).toHaveText(state.code);
      await expect(result.locator('.ant-result-subtitle')).toHaveText(state.subtitle);
      const graphic = await result.evaluate(element => {
        const icon = element.querySelector('.ant-result-icon > .anticon');
        return {
          iconName: icon?.querySelector('svg')?.getAttribute('data-icon') ?? null,
          iconAriaHidden: icon?.getAttribute('aria-hidden') ?? null,
          iconColor: icon ? getComputedStyle(icon).color : null,
          imageCount: element.querySelectorAll('.ant-result-image').length,
          graphicSvgCount: element.querySelectorAll('.ant-result-icon svg').length,
          graphicPathFills: [...element.querySelectorAll('.ant-result-icon svg path')].map(path => getComputedStyle(path).fill),
        };
      });
      const name = `acc-d01-${state.code}-${viewport.width}`;
      const file = info.outputPath(`${name}-graphic.json`);
      writeFileSync(file, JSON.stringify({ route: new URL(page.url()).pathname, role: 'READER', viewport, mock: false, expectedIcon: state.icon, ...graphic }, null, 2));
      await info.attach(`${name}-graphic`, { path: file, contentType: 'application/json' });
      await capture(page, info, name, 'READER');
      expect.soft(graphic.iconName, 'Approved icon must actually render, not just be passed as a prop').toBe(state.icon);
      expect.soft(graphic.iconAriaHidden).toBe('true');
      expect.soft(graphic.iconColor).toBe('rgb(98, 95, 88)');
      expect.soft(graphic.imageCount, 'Exception illustration must not be rendered').toBe(0);
      expect.soft(graphic.graphicSvgCount).toBe(1);
      expect.soft(graphic.graphicPathFills.length).toBeGreaterThan(0);
      for (const fill of graphic.graphicPathFills) expect.soft(fill).toBe('rgb(98, 95, 88)');
      await page.getByRole('button', { name: '返回首页', exact: true }).click();
      await expect(page).toHaveURL('/books');
      await ready(page);
      await expect(page.getByRole('heading', { name: '图书检索', exact: true })).toBeVisible();
    });
  }
}

test('AC-3 real category/reader modals danger confirmation Select DatePicker and table hover', async ({ page }, info) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  await expect(page).toHaveURL('/admin/dashboard');
  const { reader } = await seedReader(page);
  await page.goto('/admin/categories'); await ready(page);
  await page.getByRole('button', { name: '新建分类' }).first().click();
  await expect(page.locator('.ant-modal-container')).toHaveCSS('background-color', 'rgb(250, 249, 245)');
  await capture(page, info, 'category-modal', 'ADMIN', false, '.ant-modal-container');
  await page.getByRole('button', { name: /保\s*存/ }).click();
  await expect(page.getByText('请输入分类名称', { exact: true })).toBeVisible();
  await capture(page, info, 'category-validation', 'ADMIN', false, '.ant-modal-container');
  await page.getByLabel('分类名称').fill(`视觉分类-${uniqueSuffix()}`);
  await page.getByRole('button', { name: /保\s*存/ }).click();
  await expect(page.getByText(/分类「视觉分类-.*」已创建/)).toBeVisible();
  await capture(page, info, 'category-message', 'ADMIN');
  await page.goto('/admin/readers'); await ready(page);
  await page.getByRole('button', { name: '新建读者' }).first().click();
  await capture(page, info, 'reader-create-modal', 'ADMIN', false, '.ant-modal-container');
  await page.getByRole('button', { name: /取\s*消/ }).click();
  await page.goto(`/admin/readers/${reader.id}`); await ready(page);
  await page.getByRole('button', { name: '重置口令' }).click();
  await capture(page, info, 'reader-reset-modal', 'ADMIN', false, '.ant-modal-container');
  await page.getByRole('button', { name: /取\s*消/ }).click();
  await page.getByRole('button', { name: /^停\s*用$/ }).click();
  await expect(page.locator('.ant-popconfirm')).toBeVisible();
  await capture(page, info, 'reader-danger-popconfirm', 'ADMIN', false, '.ant-popconfirm');
  await page.keyboard.press('Escape');
  await page.goto('/admin/books'); await ready(page);
  await page.locator('.ant-table-tbody > tr[data-row-key]').first().hover();
  await expect(page.locator('.ant-table-tbody > tr[data-row-key] > td').first()).toHaveCSS('background-color', 'rgb(239, 233, 222)');
  await capture(page, info, 'table-hover', 'ADMIN');
  await page.goto('/admin/stats'); await ready(page);
  await page.getByRole('combobox', { name: '热门图书数量' }).click();
  await expect(page.locator('.ant-select-dropdown')).toHaveCSS('background-color', 'rgb(250, 249, 245)');
  await capture(page, info, 'select-portal', 'ADMIN', false, '.ant-select-dropdown');
  await page.keyboard.press('Escape');
  await page.getByPlaceholder('开始日期').click();
  await expect(page.locator('.ant-picker-dropdown')).toBeVisible();
  const calendar = await capture(page, info, 'datepicker-portal', 'ADMIN', false, '.ant-picker-dropdown');
  const inRange = calendar.samples.filter(sample => sample.calendarLayers.some(layer => layer.cellClass.includes('ant-picker-cell-in-view') && layer.cellClass.includes('ant-picker-cell-in-range')));
  expect(inRange.length).toBeGreaterThan(0);
  for (const sample of inRange) {
    expect(sample.calendarLayers[0]).toMatchObject({ background: 'rgb(239, 233, 222)', zIndex: '1' });
    expect(sample.effectiveBackground).toEqual([239, 233, 222, 1]);
    expect(sample.ratio).toBeGreaterThanOrEqual(4.5);
  }
  const selected = calendar.samples.filter(sample => sample.calendarLayers.some(layer => /ant-picker-cell-in-view/.test(layer.cellClass) && /ant-picker-cell-range-(start|end)/.test(layer.cellClass)));
  expect(selected.length).toBeGreaterThanOrEqual(2);
  for (const sample of selected) {
    expect(sample.foreground).toBe('rgb(255, 255, 255)');
    expect(sample.effectiveBackground).toEqual([169, 88, 62, 1]);
    expect(sample.ratio).toBeGreaterThanOrEqual(4.5);
  }
  await page.locator('.ant-picker-cell-in-view:not(.ant-picker-cell-in-range):not(.ant-picker-cell-range-start):not(.ant-picker-cell-range-end)').first().hover();
  await capture(page, info, 'datepicker-cell-hover', 'ADMIN', false, '.ant-picker-dropdown');
});

test('AC-4 real dunning OPEN/CLOSED history and returned notification', async ({ page }, info) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  await expect(page).toHaveURL('/admin/dashboard');
  const fixture = await seedReminderLoan(page, -10);
  try {
  await page.goto('/admin/reports/overdue'); await ready(page);
  const row = page.getByRole('row').filter({ hasText: fixture.title });
  await row.getByRole('button', { name: '登记催还' }).click();
  await capture(page, info, 'dunning-register', 'ADMIN', false, '.ant-modal-container');
  await page.setViewportSize(mobile);
  await capture(page, info, 'dunning-register-mobile', 'ADMIN', false, '.ant-modal-container');
  const mobileSubmit = page.getByRole('button', { name: '确认登记' });
  await mobileSubmit.scrollIntoViewIfNeeded();
  await expect(mobileSubmit).toBeInViewport();
  await capture(page, info, 'dunning-mobile-actions', 'ADMIN', false, '.ant-modal-container');
  await page.setViewportSize(desktop);
  await page.getByRole('radio', { name: '站内', exact: true }).check();
  await page.getByRole('button', { name: '确认登记' }).click();
  await expect(page.locator('.ant-modal-container')).toHaveCount(0);
  await page.goto('/admin/dunning'); await ready(page);
  const item = page.getByRole('article').filter({ hasText: fixture.title });
  await expect(item).toContainText('未结案');
  await capture(page, info, 'dunning-open', 'ADMIN');
  await item.getByRole('button', { name: '查看历史' }).click();
  await capture(page, info, 'dunning-history', 'ADMIN', false, '.ant-modal-container');
  await page.getByRole('button', { name: '关闭历史', exact: true }).click();
  await apiPost(page, `/api/loans/${fixture.loan.id}/return`);
  await page.reload(); await ready(page);
  await expect(item).toContainText('已结案');
  await capture(page, info, 'dunning-closed', 'ADMIN');
  await apiLogout(page);
  await loginViaUi(page, fixture.username, READER_PASSWORD);
  await expect(page).toHaveURL('/books');
  await page.goto('/my/notifications'); await ready(page);
  await expect(page.getByRole('article').first()).toContainText('已归还');
  await capture(page, info, 'notification-returned', 'READER');
  } finally {
    // The existing CLOSED-case regression counts all cases; remove only this test's owned records.
    const db = realpathSync(fixture.dbPath);
    expect(path.dirname(db)).toBe(realpathSync(os.tmpdir()));
    expect(path.basename(db)).toMatch(/^library-e2e-\d+\.db$/);
    expect(Number.isSafeInteger(fixture.loan.id) && Number.isSafeInteger(fixture.reader.id)).toBe(true);
    execFileSync('/opt/local/bin/sqlite3', ['-batch', '-bail', '-cmd', '.timeout 5000', db,
      `PRAGMA foreign_keys=ON; BEGIN IMMEDIATE;
       DELETE FROM dunning_record WHERE loan_id=${fixture.loan.id} AND loan_id IN (SELECT id FROM loan WHERE reader_id=${fixture.reader.id});
       DELETE FROM notification WHERE loan_id=${fixture.loan.id} AND reader_id=${fixture.reader.id};
       COMMIT;`]);
  }
});

for (const role of ['ADMIN', 'READER'] as const) {
  test(`AC-3 ${role} narrow layout, collapsed menu and primary flows`, async ({ page }, info) => {
    await page.setViewportSize(mobile);
    await loginViaUi(page, role === 'ADMIN' ? ADMIN_USERNAME : 'reader2', role === 'ADMIN' ? ADMIN_PASSWORD : 'Reader@123');
    await expect(page).toHaveURL(role === 'ADMIN' ? '/admin/dashboard' : '/books');
    const routes = role === 'ADMIN' ? ['/admin/dashboard', '/books', '/admin/circulation', '/admin/stats', '/admin/dunning'] : ['/books', '/my/loans', '/my/notifications'];
    for (const [index, route] of routes.entries()) await visit(page, info, route, `${role.toLowerCase()}-mobile-${index}`, role);
    await expect(page.locator('.ant-layout-sider')).toHaveClass(/ant-layout-sider-collapsed/);
    await page.locator('.ant-menu-item').first().hover();
    await expect(page.getByRole('tooltip')).toBeVisible();
    await capture(page, info, `${role.toLowerCase()}-collapsed-tooltip`, role, false, '.ant-tooltip');
  });
}

const emptyPage = { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 };
const stateCases = [
  { name: 'catalog', route: '/books', api: '/api/books', role: 'ADMIN', empty: emptyPage, loading: '图书加载中…' },
  { name: 'readers', route: '/admin/readers', api: '/api/readers', role: 'ADMIN', empty: emptyPage, loading: '加载中…' },
  { name: 'circulation', route: '/admin/loans', api: '/api/loans', role: 'ADMIN', empty: emptyPage, loading: '正在加载借阅记录…' },
  { name: 'stats', route: '/admin/stats', api: '/api/stats/loans', role: 'ADMIN', empty: [], loading: '统计加载中…' },
  { name: 'notification', route: '/my/notifications', api: '/api/notifications/me', role: 'READER', empty: emptyPage, loading: '正在加载提醒…' },
  { name: 'dunning', route: '/admin/dunning', api: '/api/dunning-cases', role: 'ADMIN', empty: emptyPage, loading: '正在加载催还案件…' },
];
for (const item of stateCases) {
  test(`AC-3 ${item.name} read-only simulated loading empty 503 and retry`, async ({ page }, info) => {
    await apiLogin(page, item.role === 'ADMIN' ? ADMIN_USERNAME : 'reader2', item.role === 'ADMIN' ? ADMIN_PASSWORD : 'Reader@123');
    let state: 'loading' | 'empty' | 'error' | 'real' = 'loading';
    let release: () => void = () => {};
    const held = new Promise<void>(resolve => { release = resolve; });
    await page.route(url => url.pathname === item.api, async route => {
      if (route.request().method() !== 'GET') return route.continue();
      if (state === 'loading') await held;
      if (state === 'real') return route.continue();
      if (state === 'error') return route.fulfill({ status: 503, json: { status: 503, code: 'DB_BUSY', message: '系统繁忙', fieldErrors: null, details: null } });
      return route.fulfill({ json: item.empty });
    });
    await page.goto(item.route);
    await expect(page.locator('.ant-layout-content').getByText(item.loading, { exact: true })).toBeVisible();
    await capture(page, info, `${item.name}-loading`, item.role, true);
    state = 'empty'; release(); await ready(page);
    await expect(page.locator('.ant-empty').first()).toBeVisible();
    await capture(page, info, `${item.name}-empty`, item.role, true);
    state = 'error'; await page.reload(); await ready(page);
    await expect(page.getByRole('button', { name: /重\s*试/ }).first()).toBeVisible();
    await expect(page.locator('.ant-alert').filter({ hasText: '系统繁忙' }).first()).toBeVisible();
    await capture(page, info, `${item.name}-503`, item.role, true);
    state = 'real'; await page.getByRole('button', { name: /重\s*试/ }).first().click(); await ready(page);
    await expect(page.locator('.ant-alert').filter({ hasText: '系统繁忙' })).toHaveCount(0);
    await capture(page, info, `${item.name}-retry-real`, item.role);
  });
}

test('AC-3 bootstrap loading retains warm cream canvas with real auth response', async ({ page }, info) => {
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  let release: () => void = () => {};
  const held = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/auth/me', async route => { await held; await route.continue(); });
  await page.goto('/admin/dashboard');
  await expect(page.getByText('正在确认登录状态…')).toBeVisible();
  await capture(page, info, 'bootstrap-loading', 'ADMIN', true);
  release(); await ready(page);
  await expect(page.getByRole('heading', { name: '仪表盘', exact: true })).toBeVisible();
});

test('AC-3 real delete confirmation and active pagination hover/navigation', async ({ page }, info) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  await expect(page).toHaveURL('/admin/dashboard');
  const name = `视觉删除-${uniqueSuffix()}`;
  await apiPost(page, '/api/categories', { name });
  await page.goto('/admin/categories'); await ready(page);
  const row = page.getByRole('row').filter({ hasText: name });
  await row.getByRole('button', { name: /删\s*除/ }).click();
  await expect(page.locator('.ant-popconfirm')).toBeVisible();
  await capture(page, info, 'delete-confirm-portal', 'ADMIN', false, '.ant-popconfirm');
  await page.getByRole('button', { name: /取\s*消/ }).click();
  await expect(row).toBeVisible();
  await row.getByRole('button', { name: /删\s*除/ }).click();
  const response = page.waitForResponse(res => res.request().method() === 'DELETE' && new URL(res.url()).pathname.startsWith('/api/categories/'));
  await page.getByRole('button', { name: '确认删除' }).click();
  expect((await response).status()).toBe(204);
  await expect(row).toHaveCount(0); await ready(page);
  await capture(page, info, 'delete-success', 'ADMIN');
  for (let i = 0; i < 9; i++) await seedBook(page, 0);
  await page.goto('/admin/books'); await ready(page);
  const active = page.locator('.ant-pagination-item-active a');
  await expect(active).toHaveCSS('color', 'rgb(147, 75, 52)');
  await active.hover();
  await expect(active).toHaveCSS('color', 'rgb(125, 61, 42)');
  await capture(page, info, 'pagination-active-hover', 'ADMIN');
  const firstId = await page.locator('.ant-table-tbody tr[data-row-key]').first().getAttribute('data-row-key');
  await page.locator('.ant-pagination-item-2').click(); await ready(page);
  await expect(page.locator('.ant-pagination-item-2')).toHaveClass(/ant-pagination-item-active/);
  expect(await page.locator('.ant-table-tbody tr[data-row-key]').first().getAttribute('data-row-key')).not.toBe(firstId);
  await capture(page, info, 'pagination-page-2', 'ADMIN');
});

test('AC-4 real ACTIVE due-soon notification retains readable current-state text', async ({ page }, info) => {
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  const fixture = await seedReminderLoan(page, 3);
  await apiLogout(page);
  await loginViaUi(page, fixture.username, READER_PASSWORD);
  await expect(page).toHaveURL('/books');
  await expect.poll(async () => (await apiGet<ApiPage<Notification>>(page, '/api/notifications/me')).items.some(n => n.loanId === fixture.loan.id && n.type === 'DUE_SOON' && n.loanState.status === 'ACTIVE')).toBe(true);
  await page.goto('/my/notifications'); await ready(page);
  const article = page.getByRole('article').filter({ hasText: fixture.title });
  await expect(article).toContainText('当前状态：借阅中');
  await expect(article).toContainText('到期提醒');
  await capture(page, info, 'notification-active', 'READER');
});

for (const viewport of [desktop, { width: 1024, height: 900 }, mobile]) {
  test(`F-05 full statistics labels and popup bounds at ${viewport.width}px`, async ({ page }, info) => {
    await page.setViewportSize(viewport);
    await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD);
    await expect(page).toHaveURL('/admin/dashboard');
    const longTitleBook = await seedBook(page, 1);
    const readers = await apiGet<ApiPage<{ username: string; cardNo: string }>>(page, '/api/readers?q=reader1');
    const reader = readers.items.find(item => item.username === 'reader1');
    expect(reader).toBeDefined();
    await seedLoan(page, reader!.cardNo, longTitleBook.copies[0].barcode);
    await page.goto('/admin/stats'); await ready(page);
    const rankingBounds = await page.locator('.top-book').evaluateAll(rows => rows.map(row => ({
      rowRight: row.getBoundingClientRect().right,
      sectionRight: row.closest('.stats-section')!.getBoundingClientRect().right,
      countRight: row.querySelector('.top-book-count')!.getBoundingClientRect().right,
    })));
    await info.attach(`ranking-bounds-${viewport.width}`, { body: JSON.stringify(rankingBounds), contentType: 'application/json' });
    for (const bounds of rankingBounds) {
      expect(bounds.rowRight).toBeLessThanOrEqual(bounds.sectionRight);
      expect(bounds.countRight).toBeLessThanOrEqual(bounds.sectionRight);
    }
    const metrics = await page.locator('.stats-control .ant-picker-input input, .stats-control .ant-select-content').evaluateAll(elements => elements.map(element => {
      const style = getComputedStyle(element);
      const value = element instanceof HTMLInputElement ? element.value : [...element.childNodes].filter(n => n.nodeType === Node.TEXT_NODE).map(n => n.textContent).join('').trim();
      const canvas = document.createElement('canvas');
      const context = canvas.getContext('2d')!;
      context.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
      const textWidth = context.measureText(value).width + Math.max(0, value.length - 1) * (parseFloat(style.letterSpacing) || 0);
      const available = element.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight);
      return { value, font: context.font, textWidth, available, clientWidth: element.clientWidth, scrollWidth: element.scrollWidth };
    }));
    await info.attach(`stats-label-fit-${viewport.width}`, { body: JSON.stringify(metrics, null, 2), contentType: 'application/json' });
    expect(metrics).toHaveLength(3);
    for (const metric of metrics) {
      expect(metric.value.length).toBeGreaterThan(0);
      expect(metric.textWidth).toBeLessThanOrEqual(metric.available);
      expect(metric.scrollWidth).toBeLessThanOrEqual(metric.clientWidth);
    }
    await capture(page, info, `stats-fit-${viewport.width}`, 'ADMIN');
    await page.getByRole('combobox', { name: '热门图书数量' }).click();
    const select = page.locator('.ant-select-dropdown');
    await expect(select).toBeVisible();
    const selectBox = await select.boundingBox();
    expect(selectBox!.x).toBeGreaterThanOrEqual(0);
    expect(selectBox!.x + selectBox!.width).toBeLessThanOrEqual(viewport.width);
    await capture(page, info, `stats-select-${viewport.width}`, 'ADMIN', false, '.ant-select-dropdown');
    await page.keyboard.press('Escape');
    await page.getByPlaceholder('开始日期').click();
    const panel = page.locator('.ant-picker-panel-container');
    await expect(panel).toBeVisible();
    await capture(page, info, `stats-calendar-${viewport.width}`, 'ADMIN', false, '.ant-picker-dropdown');
    const bounds = await panel.evaluate(element => ({ x: element.getBoundingClientRect().x, width: element.getBoundingClientRect().width, clientWidth: element.clientWidth, scrollWidth: element.scrollWidth }));
    const popupGeometry = await panel.evaluate(element => [element, element.parentElement, element.parentElement?.parentElement].map(node => node ? { className: node.className, x: node.getBoundingClientRect().x, width: node.getBoundingClientRect().width, left: getComputedStyle(node).left, right: getComputedStyle(node).right, transform: getComputedStyle(node).transform, position: getComputedStyle(node).position } : null));
    await info.attach(`calendar-bounds-${viewport.width}`, { body: JSON.stringify({ ...bounds, popupGeometry }), contentType: 'application/json' });
    expect(bounds.x).toBeGreaterThanOrEqual(0);
    expect(bounds.x + bounds.width).toBeLessThanOrEqual(viewport.width);
    expect(bounds.scrollWidth).toBeLessThanOrEqual(bounds.clientWidth);
    if (viewport.width === 390) {
      await page.locator('.ant-picker-panel').last().scrollIntoViewIfNeeded();
      await expect(page.locator('.ant-picker-panel').last()).toBeInViewport();
      await capture(page, info, 'stats-calendar-mobile-second-panel', 'ADMIN', false, '.ant-picker-dropdown');
    }
  });
}

test('F-03/04 real component harness validates disabled dates and normal/error/warning focus', async ({ page }, info) => {
  await page.goto('/login'); await ready(page);
  await page.evaluate(async () => {
    const paths = ['/node_modules/.vite/deps/react.js', '/node_modules/.vite/deps/react-dom_client.js', '/node_modules/.vite/deps/antd.js', '/node_modules/.vite/deps/dayjs.js', '/src/styles/claudeTheme.ts'];
    const [react, dom, antd, dayjsModule, visual] = await Promise.all(paths.map(url => import(url)));
    const h = react.default.createElement;
    const node = document.createElement('section');
    node.id = 'claude-component-fixture';
    node.style.cssText = 'position:fixed;inset:0;z-index:900;background:var(--claude-canvas);padding:24px;overflow:auto';
    document.body.append(node);
    const controls = ['Input', 'InputNumber', 'Select', 'DatePicker'];
    const rows = ['normal', 'error', 'warning'].flatMap(status => controls.map(control => {
      const label = `${status} ${control}`;
      const props = { id: `fixture-${status}-${control}`, 'aria-label': label, status: status === 'normal' ? undefined : status, style: { width: '100%' } };
      const extra = control === 'Input' ? { prefix: '#' } : control === 'Select' ? { options: [{ value: 'one', label: '选项一' }], defaultValue: 'one' } : control === 'DatePicker' ? { defaultValue: dayjsModule.default('2026-11-21'), disabledDate: (date: { date: () => number }) => date.date() === 15 } : {};
      return h('div', { key: label }, h('label', { htmlFor: props.id }, label), h(antd[control], { ...props, ...extra }));
    }));
    dom.default.createRoot(node).render(h(antd.ConfigProvider, { theme: visual.claudeTheme },
      h('h2', null, '组件状态核验（测试夹具，非业务页面）'),
      h('div', { style: { display: 'grid', gridTemplateColumns: 'repeat(4, minmax(0, 1fr))', gap: 24 } }, rows)));
  });
  await expect(page.getByText('组件状态核验（测试夹具，非业务页面）')).toBeVisible();
  for (const status of ['normal', 'error', 'warning']) {
    for (const control of ['Input', 'InputNumber', 'Select', 'DatePicker']) {
      const input = page.locator(`#fixture-${status}-${control}`);
      await input.focus();
      const root = input.locator('xpath=ancestor-or-self::*[contains(@class,"ant-input-affix-wrapper") or contains(@class,"ant-input-number ") or contains(@class,"ant-select ") or contains(@class,"ant-picker ")][1]');
      await expect(root).toHaveCSS('outline-width', '2px');
      await expect(root).toHaveCSS('outline-color', 'rgb(147, 75, 52)');
      if (control === 'Input') await expect(input).toHaveCSS('outline-style', 'none');
      if (status !== 'normal') await expect(root).toHaveCSS('border-top-color', status === 'error' ? 'rgb(165, 54, 54)' : 'rgb(128, 92, 20)');
      await capture(page, info, `fixture-${status}-${control}-focus`, 'component fixture', false, '#claude-component-fixture');
      await page.keyboard.press('Escape');
    }
  }
  await page.locator('#fixture-normal-DatePicker').click();
  await expect(page.locator('.ant-picker-dropdown')).toBeVisible();
  await expect(page.locator('.ant-picker-cell-disabled.ant-picker-cell-in-view .ant-picker-cell-inner')).toHaveText('15');
  const calendar = await capture(page, info, 'fixture-disabled-calendar', 'component fixture', false, '.ant-picker-dropdown');
  const disabled = calendar.samples.filter(sample => sample.selector.includes('ant-picker-cell-disabled'));
  expect(disabled.length).toBeGreaterThan(0);
  for (const sample of disabled) {
    expect(sample.excluded).toBe('genuinely disabled control');
    expect(sample.foreground).toBe('rgb(142, 139, 130)');
  }
});
