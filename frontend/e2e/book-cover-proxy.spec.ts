import { test, expect, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { apiGet, apiLogin, apiLogout, loginViaUi, ADMIN_USERNAME, ADMIN_PASSWORD, uniqueIsbn13, uniqueSuffix } from './helpers';
import type { BookDetail, ProxySettings } from '../src/api/types';
// OL-OPT-INT: an integration run may redirect evidence so earlier task evidence is never overwritten (default unchanged).
const evidence = path.resolve('..', process.env.OL_E2E_EVIDENCE_DIR ?? 'docs/verification/frontend-cover-proxy/development');
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC', 'base64');
const consoleErrors: string[] = [];
test.beforeAll(() => mkdirSync(evidence, { recursive: true }));
test.beforeEach(async ({ page }) => {
  page.on('pageerror', (error) => consoleErrors.push(error.message));
  page.on('console', (message) => { if (message.type() === 'error') consoleErrors.push(message.text()); });
});
test.afterAll(() => writeFileSync(path.join(evidence, 'browser-console.json'), JSON.stringify({ errors: consoleErrors }, null, 2)));
async function screenshot(page: Page, name: string) {
  await page.waitForLoadState('networkidle');
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: path.join(evidence, `${name}.png`), fullPage: true });
}
async function admin(page: Page) {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  await expect(page).toHaveURL(/\/admin\/dashboard$/);
}
async function waitForSaved(page: Page, buttonName: RegExp, method: string): Promise<BookDetail> {
  const saved = page.waitForResponse((response) => response.request().method() === method && /\/api\/books(?:\/\d+)?$/.test(new URL(response.url()).pathname));
  await page.getByRole('button', { name: buttonName }).click();
  const response = await saved;
  expect(response.ok()).toBe(true);
  return await response.json() as BookDetail;
}

test('AC-FE-01/03/06 real local create, replace, remove, reread and reader cover', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await admin(page);
  await page.goto('/admin/books/new');
  await page.getByLabel('ISBN', { exact: true }).fill(uniqueIsbn13());
  await page.getByLabel('书名', { exact: true }).fill(`封面真实链路-${uniqueSuffix()}`);
  await page.getByLabel('作者', { exact: true }).fill('本地作者');
  await page.getByLabel('初始副本数量').fill('2');
  await page.getByLabel('选择本地封面').setInputFiles({ name: 'cover.png', mimeType: 'image/png', buffer: png });
  await expect(page.getByText('待保存 · 本地上传', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '撤销封面修改' })).toBeEnabled();
  await screenshot(page, 'local-preview-1440');
  const created = await waitForSaved(page, /创建图书/, 'POST');
  expect(created.cover?.sourceUrl).toBeNull();
  expect(created.cover?.byteLength).toBe(png.length);
  expect(created.totalCopies).toBe(2);
  await expect(page).toHaveURL(new RegExp(`/admin/books/${created.id}/edit$`));
  const bytes = await page.request.get(`/api/books/${created.id}/cover?v=${created.cover?.sha256}`);
  expect(bytes.headers()['content-type']).toBe('image/png');
  expect(await bytes.body()).toEqual(png);
  await page.reload();
  await expect(page.locator('section[aria-label="图书封面"] img')).toBeVisible();
  await expect.poll(() => page.locator('section[aria-label="图书封面"] img').evaluate((img) => (img as HTMLImageElement).naturalWidth)).toBe(1);
  await page.getByLabel('选择本地封面').setInputFiles({ name: 'bad.png', mimeType: 'image/png', buffer: Buffer.from('not an image') });
  await expect(page.getByText(/类型不一致|图片不完整/)).toBeVisible();
  expect((await apiGet<BookDetail>(page, `/api/books/${created.id}`)).cover?.sha256).toBe(created.cover?.sha256);
  // Legal JPEG remains in memory; no blob or database fixture is written to the repository.
  const jpeg = Buffer.from(await page.evaluate(() => {
    const canvas = document.createElement('canvas'); canvas.width = 2; canvas.height = 3;
    const context = canvas.getContext('2d')!; context.fillStyle = '#a9583e'; context.fillRect(0, 0, 2, 3);
    return canvas.toDataURL('image/jpeg').split(',')[1];
  }), 'base64');
  await page.getByLabel('选择本地封面').setInputFiles({ name: 'cover.jpg', mimeType: 'image/jpeg', buffer: jpeg });
  await expect(page.getByText('待保存 · 本地上传', { exact: true })).toBeVisible();
  const replaced = await waitForSaved(page, /保存修改/, 'PUT');
  expect(replaced.cover?.mediaType).toBe('image/jpeg');
  expect(await (await page.request.get(`/api/books/${created.id}/cover`)).body()).toEqual(jpeg);
  await page.getByRole('button', { name: '移除封面', exact: true }).click();
  await expect(page.getByText('待移除，保存后生效')).toBeVisible();
  await page.getByRole('button', { name: '撤销封面修改' }).click();
  expect((await apiGet<BookDetail>(page, `/api/books/${created.id}`)).cover?.sha256).toBe(replaced.cover?.sha256);
  await page.goto(`/books/${created.id}`);
  await expect(page.locator('section[aria-label="图书详情"] img')).toBeVisible();
  await screenshot(page, 'real-detail-1440');
  await page.goto(`/books?q=${encodeURIComponent(created.isbn)}&field=isbn`);
  await expect(page.locator('tbody img')).toBeVisible();
  await apiLogout(page); await apiLogin(page, 'reader1', 'Reader@123');
  await page.goto(`/books/${created.id}`);
  await expect(page.locator('section[aria-label="图书详情"] img')).toBeVisible();
  await screenshot(page, 'reader-real-cover-1440');
  expect((await page.request.get(`/api/books/${created.id}/cover`)).status()).toBe(200);
  await apiLogout(page);
  expect((await page.request.get(`/api/books/${created.id}/cover`)).status()).toBe(401);
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD); await page.goto(`/admin/books/${created.id}/edit`);
  await page.getByRole('button', { name: '移除封面', exact: true }).click();
  const removed = await waitForSaved(page, /保存修改/, 'PUT');
  expect(removed.cover).toBeNull();
  const missing = await page.request.get(`/api/books/${created.id}/cover`);
  expect(missing.status()).toBe(404); expect((await missing.json()).code).toBe('COVER_NOT_FOUND');
  await page.goto(`/books/${created.id}`); await expect(page.locator('section[aria-label="图书详情"] img')).toHaveCount(0);
});

test('AC-FE-02/06 explicit metadata and remote-cover candidate, real save, narrow keyboard', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await admin(page);
  const isbn = uniqueIsbn13();
  await page.route('**/api/catalog/open-library?*', async (route) => route.fulfill({ json: {
    isbn, found: true, sourceUrl: `https://openlibrary.org/isbn/${isbn}`,
    fields: { title: '候选书名很长用于检验窄屏内容换行与明确应用', author: '候选作者', publisher: '候选出版社', publishYear: 2020, description: null },
    cover: { mediaType: 'image/png', base64: png.toString('base64'), byteLength: png.length, width: 1, height: 1 },
    warnings: [{ code: 'PARTIAL', field: 'description', message: '未提供简介，请手动补充' }], retryAfterSeconds: null,
  } }));
  await page.goto('/admin/books/new');
  await page.getByLabel('ISBN', { exact: true }).fill(isbn);
  await page.getByLabel('书名', { exact: true }).fill('人工标题');
  await page.getByRole('button', { name: '获取书目与封面' }).click();
  await expect(page.getByRole('checkbox', { name: '应用作者', exact: true })).toBeChecked();
  await expect(page.getByRole('checkbox', { name: '应用书名', exact: true })).not.toBeChecked();
  await expect(page.getByRole('checkbox', { name: '应用候选封面', exact: true })).not.toBeChecked();
  await expect(page.getByLabel('书名', { exact: true })).toHaveValue('人工标题');
  await screenshot(page, 'candidate-390');
  const checkbox = page.getByRole('checkbox', { name: '应用候选封面', exact: true });
  await checkbox.focus(); await page.keyboard.press('Space');
  await expect(checkbox).toBeChecked();
  const focused = await checkbox.evaluate((element) => {
    const target = element.closest('label') ?? element;
    return { outlineWidth: getComputedStyle(target).outlineWidth, outlineColor: getComputedStyle(target).outlineColor, canvas: getComputedStyle(document.body).backgroundColor };
  });
  writeFileSync(path.join(evidence, 'candidate-focus.json'), JSON.stringify(focused, null, 2));
  expect(focused.outlineWidth).toBe('2px');
  expect(focused.outlineColor).toBe('rgb(147, 75, 52)');
  await page.getByRole('button', { name: '应用选中项' }).click();
  await expect(page.getByLabel('作者', { exact: true })).toHaveValue('候选作者');
  await expect(page.getByLabel('书名', { exact: true })).toHaveValue('人工标题');
  const book = await waitForSaved(page, /创建图书/, 'POST');
  expect(book.openLibraryIsbn).toBe(isbn); expect(book.cover?.sourceUrl).toBe(`https://openlibrary.org/isbn/${isbn}`);
  expect(book.category).toBeNull();
  await page.goto(`/books/${book.id}`); await expect(page.locator('section[aria-label="图书详情"] img')).toBeVisible();
  await screenshot(page, 'candidate-saved-detail-390');
});

test('AC-FE-02/05 stale response and manual fallback remain non-writing', async ({ page }) => {
  await admin(page); await page.goto('/admin/books/new');
  const first = uniqueIsbn13(), second = uniqueIsbn13();
  let release!: () => void;
  const gate = new Promise<void>((done) => { release = done; });
  await page.route('**/api/catalog/open-library?*', async (route) => {
    await gate;
    await route.fulfill({ json: { isbn: first, found: true, sourceUrl: `https://openlibrary.org/isbn/${first}`, fields: { title: '迟到候选不得应用', author: null, publisher: null, description: null, publishYear: null }, cover: null, warnings: [], retryAfterSeconds: null } }).catch(() => {});
  });
  await page.getByLabel('ISBN', { exact: true }).fill(first);
  await page.getByLabel('书名', { exact: true }).fill('人工编辑保留');
  await page.getByRole('button', { name: '获取书目与封面' }).click();
  await page.getByLabel('ISBN', { exact: true }).fill(second); release();
  await expect(page.getByText('迟到候选不得应用')).toHaveCount(0);
  await page.unroute('**/api/catalog/open-library?*');
  for (const status of [429, 502]) {
    await page.route('**/api/catalog/open-library?*', (route) => route.fulfill({ status, json: { code: status === 429 ? 'OPEN_LIBRARY_RATE_LIMITED' : 'OPEN_LIBRARY_UNAVAILABLE', details: { retryAfterSeconds: 30 } } }));
    await page.getByRole('button', { name: '获取书目与封面' }).click();
    await expect(page.getByText(/30 秒/)).toBeVisible();
    await expect(page.getByLabel('书名', { exact: true })).toHaveValue('人工编辑保留');
    await page.unroute('**/api/catalog/open-library?*');
  }
  await screenshot(page, 'lookup-failure-1440');
});

test('AC-FE-04/06 real proxy save/disable and READER guard, desktop and mobile', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await admin(page); await page.getByRole('menuitem', { name: '系统设置' }).click();
  await expect(page.getByRole('heading', { name: '代理配置' })).toBeVisible();
  await page.getByLabel('代理主机', { exact: true }).fill('127.0.0.1');
  await page.getByLabel('代理端口', { exact: true }).fill('9999.5');
  await page.getByRole('button', { name: '保存配置', exact: true }).click();
  await expect(page.getByText('端口须为 1 至 65535 的整数')).toBeVisible();
  await page.getByLabel('代理端口', { exact: true }).fill('9999');
  await page.getByRole('switch', { name: '启用备用代理' }).check();
  let responsePromise = page.waitForResponse((response) => response.request().method() === 'PUT' && response.url().endsWith('/api/admin/settings/proxy'));
  await page.getByRole('button', { name: '保存配置', exact: true }).click();
  let response = await responsePromise; expect(response.ok()).toBe(true);
  let settings = await response.json() as ProxySettings;
  expect(settings.enabled).toBe(true); expect(settings.host).toBe('127.0.0.1'); expect(settings.port).toBe(9999);
  expect(response.request().postDataJSON()).toEqual({ enabled: true, host: '127.0.0.1', port: 9999 });
  await page.reload(); await expect(page.getByLabel('代理主机', { exact: true })).toHaveValue('127.0.0.1');
  await screenshot(page, 'proxy-enabled-1440');
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('switch', { name: '启用备用代理' }).uncheck();
  responsePromise = page.waitForResponse((res) => res.request().method() === 'PUT' && res.url().endsWith('/api/admin/settings/proxy'));
  await page.getByRole('button', { name: '保存配置', exact: true }).click(); response = await responsePromise;
  const disabled = await response.json() as ProxySettings;
  expect(disabled.enabled).toBe(false); expect(disabled.host).toBe('127.0.0.1'); expect(disabled.revision).toBeGreaterThan(settings.revision);
  await screenshot(page, 'proxy-disabled-390');
  await page.getByLabel('代理主机', { exact: true }).fill(''); await page.getByLabel('代理端口', { exact: true }).fill('');
  writeFileSync(path.join(evidence, 'proxy-empty-diagnostic.json'), JSON.stringify(await page.locator('button').evaluateAll((buttons) => buttons.map((button) => ({ text: button.textContent, aria: button.getAttribute('aria-label'), html: button.outerHTML }))), null, 2));
  responsePromise = page.waitForResponse((res) => res.request().method() === 'PUT' && res.url().endsWith('/api/admin/settings/proxy'));
  await page.getByRole('button', { name: '保存配置', exact: true }).click(); response = await responsePromise;
  settings = await response.json() as ProxySettings; expect(settings.host).toBeNull(); expect(settings.port).toBeNull();
  await apiLogout(page); await apiLogin(page, 'reader1', 'Reader@123');
  let settingsRequests = 0; page.on('request', (request) => { if (request.url().includes('/api/admin/settings/proxy')) settingsRequests++; });
  await page.goto('/admin/settings/proxy'); await expect(page.getByText('无权限访问该页面')).toBeVisible();
  expect(settingsRequests).toBe(0); await expect(page.getByRole('menuitem', { name: '系统设置' })).toHaveCount(0);
  await screenshot(page, 'proxy-reader-forbidden-390');
  expect((await page.request.get('/api/admin/settings/proxy')).status()).toBe(403);
});

// ---- OL-OPT-INT-01 browser diagnostics (controlled lookup GET responses shaped exactly like the backend's sanitized
// ErrorResponse/warning details; book writes, session and cover reads use the real temporary-DB backend). ----
test('OL-OPT diagnostics: upstream 401 is a local 502 Alert, session stays, manual entry saves (1440, keyboard)', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await admin(page); await page.goto('/admin/books/new');
  const isbn = uniqueIsbn13();
  let lookups = 0;
  await page.route('**/api/catalog/open-library?*', async (route) => { lookups++; await route.fulfill({ status: 502, headers: { 'Cache-Control': 'no-store' }, json: {
    code: 'OPEN_LIBRARY_UNAVAILABLE', message: 'Open Library 暂不可用，请继续手工录入', status: 502, path: '/api/catalog/open-library', timestamp: new Date().toISOString(), fieldErrors: [],
    details: { reason: 'HTTP_ERROR', stage: 'METADATA', provider: 'OPEN_LIBRARY', lastRoute: 'SOCKS5', proxyOutcome: 'RESPONDED', upstreamStatus: 401, directReason: 'CONNECT' },
  } }); });
  await page.getByLabel('ISBN', { exact: true }).fill(isbn);
  await page.getByLabel('书名', { exact: true }).fill(`诊断人工录入-${uniqueSuffix()}`);
  await page.getByLabel('作者', { exact: true }).fill('人工作者');
  await page.getByRole('button', { name: '获取书目与封面' }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByText('书目服务暂不可用，可继续手动录入')).toBeVisible();
  await expect(page.getByText('书目 · Open Library · 上游 HTTP 401 · 经代理（代理已响应）')).toBeVisible();
  await expect(page).toHaveURL(/\/admin\/books\/new$/);
  expect(lookups).toBe(1);
  expect((await page.request.get('/api/auth/me')).status()).toBe(200);
  await screenshot(page, 'ol-opt-diagnostic-401-as-502-1440');
  await page.getByLabel('选择本地封面').setInputFiles({ name: 'cover.png', mimeType: 'image/png', buffer: png });
  const created = await waitForSaved(page, /创建图书/, 'POST');
  expect(created.openLibraryIsbn).toBeNull(); expect(created.cover?.sourceUrl).toBeNull();
  expect(await (await page.request.get(`/api/books/${created.id}/cover`)).body()).toEqual(png);
  expect(lookups).toBe(1); // no automatic retry
});

test('OL-OPT diagnostics: 429 seconds and long wait stay manual; no-response failure states no guessed status (390)', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await admin(page); await page.goto('/admin/books/new');
  await page.getByLabel('ISBN', { exact: true }).fill(uniqueIsbn13());
  await page.getByLabel('书名', { exact: true }).fill('人工编辑保留');
  const cases = [
    { status: 429, code: 'OPEN_LIBRARY_RATE_LIMITED', details: { reason: 'UPSTREAM', retryAfterSeconds: 3600, stage: 'METADATA', provider: 'OPEN_LIBRARY', lastRoute: 'DIRECT', proxyOutcome: 'NOT_ATTEMPTED', upstreamStatus: 429 },
      expected: [/请等待 3600 秒后手动重试/, /书目 · Open Library · 上游 HTTP 429 · 直连/] },
    { status: 429, code: 'OPEN_LIBRARY_RATE_LIMITED', details: { reason: 'COOLDOWN', longWait: true }, expected: [/上游要求长时间等待，请稍后再手动重试/] },
    { status: 502, code: 'OPEN_LIBRARY_UNAVAILABLE', details: { reason: 'CONNECT', stage: 'METADATA', provider: 'OPEN_LIBRARY', lastRoute: 'SOCKS5', proxyOutcome: 'FAILED', upstreamStatus: null, directReason: 'CONNECT', proxyReason: 'CONNECT' },
      expected: [/书目 · Open Library · 未收到上游响应 · 经代理（代理连接失败）/] },
  ];
  for (const [index, item] of cases.entries()) {
    await page.route('**/api/catalog/open-library?*', (route) => route.fulfill({ status: item.status, json: { code: item.code, message: 'x', status: item.status, fieldErrors: [], details: item.details } }));
    await page.getByRole('button', { name: '获取书目与封面' }).click();
    for (const text of item.expected) { await expect(page.getByText(text)).toBeVisible(); }
    if (index === 1) { await expect(page.getByText(/请等待 \d+ 秒/)).toHaveCount(0); }
    await expect(page.getByLabel('书名', { exact: true })).toHaveValue('人工编辑保留');
    await expect(page).toHaveURL(/\/admin\/books\/new$/);
    await screenshot(page, `ol-opt-diagnostic-${index}-390`);
    await page.unroute('**/api/catalog/open-library?*');
  }
  expect((await page.request.get('/api/auth/me')).status()).toBe(200);
});

test('OL-OPT diagnostics: partial candidate with cover warning applies explicitly and saves real book (390/1440)', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await admin(page);
  const isbn = uniqueIsbn13();
  await page.route('**/api/catalog/open-library?*', (route) => route.fulfill({ json: {
    isbn, found: true, sourceUrl: `https://openlibrary.org/isbn/${isbn}`,
    fields: { title: '版本书名', author: null, publisher: '版本出版社', publishYear: 1999, description: '作品级简介' },
    cover: null, retryAfterSeconds: null,
    warnings: [
      { code: 'AUTHOR_UNVERIFIED', field: 'author', message: '无法核对作者与本版本一致，未采用作者，请手工录入', details: null },
      { code: 'DESCRIPTION_FROM_WORK', field: 'description', message: '简介来自作品级记录，可能与本版本不完全一致，请核对后再应用', details: null },
      { code: 'COVER_UNAVAILABLE', field: 'cover', message: '封面获取失败，书目信息仍可应用', details: { reason: 'HTTP_ERROR', stage: 'COVER', provider: 'ARCHIVE', lastRoute: 'SOCKS5', proxyOutcome: 'RESPONDED', upstreamStatus: 503 } },
    ],
  } }));
  await page.goto('/admin/books/new');
  await page.getByLabel('ISBN', { exact: true }).fill(isbn);
  await page.getByLabel('作者', { exact: true }).fill('人工作者');
  await page.getByRole('button', { name: '获取书目与封面' }).click();
  await expect(page.getByText('封面 · 封面归档服务 · 上游 HTTP 503 · 经代理（代理已响应）')).toBeVisible();
  await expect(page.getByText('封面暂不可用，请查看下方提示')).toBeVisible();
  await expect(page.getByRole('checkbox', { name: '应用候选封面', exact: true })).toBeDisabled();
  await expect(page.getByRole('checkbox', { name: '应用作者', exact: true })).toBeDisabled();
  await screenshot(page, 'ol-opt-partial-candidate-390');
  await page.getByRole('button', { name: '应用选中项' }).click();
  await expect(page.getByLabel('书名', { exact: true })).toHaveValue('版本书名');
  await expect(page.getByLabel('作者', { exact: true })).toHaveValue('人工作者');
  await page.setViewportSize({ width: 1440, height: 1000 });
  const book = await waitForSaved(page, /创建图书/, 'POST');
  expect(book.title).toBe('版本书名'); expect(book.author).toBe('人工作者'); expect(book.openLibraryIsbn).toBe(isbn); expect(book.cover).toBeNull();
  await page.goto(`/admin/books/${book.id}/edit`);
  await expect(page.getByLabel('书名', { exact: true })).toHaveValue('版本书名');
  await screenshot(page, 'ol-opt-partial-saved-1440');
});

// External connectivity was observed once during development (public-lookup.json).
// The repeatable regression suite uses controlled lookup GETs and real persistence;
// do not repeat a live Open Library probe on every test/INT run.
