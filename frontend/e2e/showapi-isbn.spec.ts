// INT-01（showapi-isbn-20261122 / showapi-plan-v1）ShowAPI ISBN 来源持久回归 E2E。
// 契约：showapi-design-v1 / showapi-ac-dod-v1（AC-S1..S4、T1..T3）。
// 边界声明：会话/CSRF/设置 PUT/书目创建与更新/详情/封面二进制/读者视图全部走真实临时库后端
// （playwright.config.ts：18096/15196 + os.tmpdir 临时 SQLite）；ShowAPI 与 OL 的 HTTP lookup
// 响应用 page.route 受控模拟（模拟的是本地 lookup 端点的供应商结果，不是真实上游调用）。
// 未配置 409、设置校验/409/413、角色/CSRF 拒绝走真实后端且不发任何外部请求。
// 本文件不使用真实 AppKey；FAKE_KEY 为明显测试值。外部供应商域名一律被守卫路由拦截并断言零命中。
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Buffer } from 'node:buffer';
import { test, expect, type Page } from '@playwright/test';
import {
  ADMIN_PASSWORD,
  ADMIN_USERNAME,
  apiGet,
  apiLogin,
  loginViaUi,
  seedBook,
  uniqueIsbn13,
} from './helpers';
import type { BookDetail } from '../src/api/types';

const EV = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../docs/verification/showapi-isbn');
const SHOTS = path.join(EV, 'screenshots');
const BASE = `http://localhost:${process.env.E2E_FRONTEND_PORT ?? 15196}`;
const SETTINGS = '/api/admin/settings/showapi';
const FAKE_KEY = 'FAKE-KEY-INT01-E2E-TEST-ONLY';
// 1x1 PNG（69 字节），与 FE-01 浏览器自测一致的合法最小封面。
const PNG_BASE64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC';
const SHOWAPI_SOURCE_URL = 'https://www.showapi.com/apis/isbn-book-1626';

// 读者侧复用 dev profile 种子账号 reader1（DemoDataLoader.DEMO_READER_PASSWORD），
// 不新建读者：读者列表按用户名排序且 pageSize=20，额外读者会把 smoke 用例的新读者挤到第 2 页。
const SEEDED_READER_USERNAME = 'reader1';
const SEEDED_READER_PASSWORD = 'Reader@123';

interface SettingsView { enabled: boolean; appKeyConfigured: boolean; revision: number; updatedAt: string | null }

test.beforeAll(() => { mkdirSync(SHOTS, { recursive: true }); });

// ---------- 真实后端 API 辅助 ----------
async function csrfToken(page: Page): Promise<string> {
  const res = await page.request.get('/api/auth/csrf');
  expect(res.status(), 'GET /api/auth/csrf 应 204').toBe(204);
  const token = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')?.value;
  if (!token) throw new Error('未找到 XSRF-TOKEN Cookie');
  return token;
}
async function rawPutSettings(page: Page, data: unknown, csrf: 'valid' | 'missing' = 'valid') {
  const headers: Record<string, string> = csrf === 'valid' ? { 'X-XSRF-TOKEN': await csrfToken(page) } : {};
  return page.request.put(SETTINGS, { headers, data });
}
async function rawPostShowApi(page: Page, data: unknown, csrf: 'valid' | 'missing' = 'valid') {
  const headers: Record<string, string> = csrf === 'valid' ? { 'X-XSRF-TOKEN': await csrfToken(page) } : {};
  return page.request.post('/api/catalog/showapi', { headers, data });
}
async function settingsView(page: Page): Promise<SettingsView> {
  return apiGet<SettingsView>(page, SETTINGS);
}
/** 每个用例自置前置状态（设置是全局单例；workers=1 串行，无并发写入）。 */
async function ensureShowApi(page: Page, want: 'configured' | 'unconfigured') {
  const view = await settingsView(page);
  if (want === 'configured') {
    if (!view.enabled || !view.appKeyConfigured) {
      const res = await rawPutSettings(page, { enabled: true, appKeyAction: 'REPLACE', appKey: FAKE_KEY, expectedRevision: view.revision });
      expect(res.status(), '前置 REPLACE+启用 应 200').toBe(200);
    }
  } else if (view.enabled || view.appKeyConfigured) {
    const res = await rawPutSettings(page, { enabled: false, appKeyAction: 'CLEAR', expectedRevision: view.revision });
    expect(res.status(), '前置 CLEAR+停用 应 200').toBe(200);
  }
}

// ---------- 受控供应商边界（模拟 HTTP lookup 响应，非真实上游） ----------
function showApiPayload(isbn: string) {
  return {
    provider: 'SHOWAPI', isbn, found: true, sourceUrl: SHOWAPI_SOURCE_URL,
    fields: { title: 'ShowAPI候选标题', author: 'ShowAPI作者', publisher: 'ShowAPI出版社', description: 'ShowAPI简介<b>不是HTML</b>', publishYear: 2021 },
    additional: { publicationDate: '2021-05', edition: '第2版', pageCountText: '300页', format: '16开', paper: '胶版纸', binding: '平装', priceText: '59.00元' },
    capabilities: { tableOfContents: false },
    cover: { mediaType: 'image/png', base64: PNG_BASE64, byteLength: 69, width: 1, height: 1 },
    warnings: [], retryAfterSeconds: null,
  };
}
function olPayload(isbn: string) {
  return {
    isbn, found: true, sourceUrl: `https://openlibrary.org/isbn/${isbn}`,
    fields: { title: 'OL候选标题', author: 'OL作者', publisher: 'OL出版社', description: 'OL简介文本', publishYear: 2019 },
    cover: null,
    warnings: [{ code: 'COVER_UNAVAILABLE', field: 'cover', message: '封面获取失败，书目信息仍可应用', details: { reason: 'HTTP_ERROR', stage: 'COVER', provider: 'ARCHIVE', upstreamStatus: 503, lastRoute: 'DIRECT', proxyOutcome: 'NOT_ATTEMPTED' } }],
    retryAfterSeconds: null,
  };
}
async function mockShowApiGolden(page: Page): Promise<{ calls: string[] }> {
  const calls: string[] = [];
  await page.route('**/api/catalog/showapi', async (route) => {
    calls.push(route.request().postDataJSON()?.isbn ?? '');
    await route.fulfill({ headers: { 'Cache-Control': 'no-store' }, json: showApiPayload(calls[calls.length - 1]) });
  });
  return { calls };
}
async function mockOlGolden(page: Page): Promise<{ calls: string[] }> {
  const calls: string[] = [];
  await page.route('**/api/catalog/open-library?*', async (route) => {
    const isbn = new URL(route.request().url()).searchParams.get('isbn') ?? '';
    calls.push(isbn);
    await route.fulfill({ json: olPayload(isbn) });
  });
  return { calls };
}
interface Gate { release: () => void; done: Promise<void> }
/** 门控 ShowAPI 路由：请求挂起直到测试显式放行；done 在 fulfill 结束后 resolve，避免固定 sleep。 */
async function mockShowApiGated(page: Page) {
  const pending: Gate[] = [];
  const calls: string[] = [];
  await page.route('**/api/catalog/showapi', async (route) => {
    const isbn = route.request().postDataJSON()?.isbn ?? '';
    calls.push(isbn);
    let release!: () => void;
    let finished!: () => void;
    const opened = new Promise<void>((r) => { release = r; });
    const done = new Promise<void>((r) => { finished = r; });
    pending.push({ release, done });
    await opened;
    try { await route.fulfill({ json: showApiPayload(isbn) }); } catch { /* 请求可能已被取消/卸载 */ }
    finished();
  });
  return { pending, calls };
}
/** 确定性地等待两帧渲染（释放门控后让 React 状态落屏），不是固定时长 sleep。 */
async function flushRender(page: Page) {
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
}
function candidateSection(page: Page) { return page.locator('section[aria-label="ISBN 书目候选"]'); }
async function switchSource(page: Page, label: 'ShowAPI' | 'Open Library') {
  await page.locator('label.ant-radio-button-wrapper', { hasText: label }).click();
}
async function startLookup(page: Page) { await page.getByRole('button', { name: '获取书目与封面' }).click(); }

// ---------- 全局守卫：外部供应商域名零命中 + 无未捕获页面错误 ----------
let externalAttempts: string[] = [];
let pageErrors: string[] = [];
test.beforeEach(async ({ page }) => {
  externalAttempts = [];
  pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(String(error)));
  await page.route(/https?:\/\/([^/]*\.)?(showapi\.com|openlibrary\.org|archive\.org)\//, async (route) => {
    externalAttempts.push(route.request().url());
    await route.abort();
  });
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  await expect(page).toHaveURL(/\/admin\/dashboard/);
});
test.afterEach(async () => {
  expect(externalAttempts, '浏览器不得直接请求任何外部供应商域名').toEqual([]);
  expect(pageErrors, '不得出现未捕获页面错误').toEqual([]);
});

test('AC-S1/T1 未配置真实 409 SHOWAPI_NOT_CONFIGURED；切换来源不发起请求；不自动重试', async ({ page }) => {
  await ensureShowApi(page, 'unconfigured');
  const calls: string[] = [];
  // fallback 放行到真实后端：未配置时后端在连接供应商之前就返回 409。
  await page.route('**/api/catalog/showapi', async (route) => {
    calls.push(route.request().postDataJSON()?.isbn ?? '');
    await route.fallback();
  });
  await page.goto('/admin/books/new');
  await page.getByLabel('ISBN', { exact: true }).fill(uniqueIsbn13());
  await switchSource(page, 'ShowAPI');
  await flushRender(page);
  expect(calls, '切换来源本身不得发起 lookup').toHaveLength(0);
  const responsePromise = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/catalog/showapi'));
  await startLookup(page);
  const response = await responsePromise;
  expect(response.status()).toBe(409);
  expect(response.headers()['cache-control']).toContain('no-store');
  expect((await response.json()).code).toBe('SHOWAPI_NOT_CONFIGURED');
  await expect(page.getByText('ShowAPI 尚未启用或未配置 AppKey，请先在系统设置中配置')).toBeVisible();
  await page.screenshot({ path: path.join(SHOTS, 'int01-error-409-real-1440.png') });
  await flushRender(page);
  expect(calls, '失败后不得自动重试').toHaveLength(1);
  await switchSource(page, 'Open Library');
  await flushRender(page);
  expect(calls, '切回 OL 也不得自动查询').toHaveLength(1);
});

test('AC-S4 设置 UI：REPLACE/KEEP/CLEAR 真实 PUT、omit key、trim、成功清空、响应/存储无 key', async ({ page }) => {
  await ensureShowApi(page, 'unconfigured');
  await page.goto('/admin/settings/showapi');
  await expect(page.getByRole('heading', { name: 'ShowAPI 书目来源' })).toBeVisible();
  await expect(page.getByText('AppKey 状态：未配置')).toBeVisible();
  await expect(page.getByText(/明文保存在本机 SQLite/)).toBeVisible();
  const before = await settingsView(page);

  // REPLACE + 启用（真实 PUT）
  await page.locator('label.ant-radio-wrapper', { hasText: '替换' }).click();
  await page.getByLabel('AppKey', { exact: true }).fill(`  ${FAKE_KEY}  `);
  await page.getByRole('switch', { name: '启用 ShowAPI' }).check();
  let putPromise = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith(SETTINGS));
  await page.getByRole('button', { name: '保存配置', exact: true }).click();
  let put = await putPromise;
  const replaceBody = put.request().postDataJSON();
  expect(put.status()).toBe(200);
  expect(replaceBody.appKey, '前端应提交 trim 后的 key').toBe(FAKE_KEY);
  expect(replaceBody.appKeyAction).toBe('REPLACE');
  expect(replaceBody.expectedRevision).toBe(before.revision);
  const replaceText = await put.text();
  expect(replaceText, 'PUT 响应不得包含 AppKey').not.toContain(FAKE_KEY);
  expect(JSON.parse(replaceText).appKeyConfigured).toBe(true);
  await expect(page.getByText('AppKey 状态：已配置')).toBeVisible();
  await expect(page.getByLabel('AppKey', { exact: true })).toHaveValue('');
  await page.screenshot({ path: path.join(SHOTS, 'int01-settings-configured-1440.png') });

  // KEEP（真实 PUT，完全省略 appKey 字段）
  putPromise = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith(SETTINGS));
  await page.getByRole('button', { name: '保存配置', exact: true }).click();
  put = await putPromise;
  const keepBody = put.request().postDataJSON();
  expect(put.status()).toBe(200);
  expect(keepBody.appKeyAction).toBe('KEEP');
  expect('appKey' in keepBody, 'KEEP 必须完全省略 appKey').toBe(false);

  // CLEAR + 停用（真实 PUT，完全省略 appKey 字段）
  await page.getByRole('switch', { name: '启用 ShowAPI' }).uncheck();
  await page.locator('label.ant-radio-wrapper', { hasText: '清除' }).click();
  putPromise = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith(SETTINGS));
  await page.getByRole('button', { name: '保存配置', exact: true }).click();
  put = await putPromise;
  const clearBody = put.request().postDataJSON();
  expect(put.status()).toBe(200);
  expect('appKey' in clearBody, 'CLEAR 必须完全省略 appKey').toBe(false);
  const clearView = await put.json();
  expect(clearView.enabled).toBe(false);
  expect(clearView.appKeyConfigured).toBe(false);
  await expect(page.getByText('AppKey 状态：未配置')).toBeVisible();

  // key 卫生：GET 响应与浏览器存储均无 key
  const getText = await (await page.request.get(SETTINGS)).text();
  expect(getText).not.toContain(FAKE_KEY);
  const storage = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }));
  expect(storage, 'AppKey 不得进入 local/sessionStorage').not.toContain(FAKE_KEY);
});

test('AC-S4/T1 设置 409 冲突与网络未知：输入保留、不自动重发、人工重新读取后恢复（模拟 PUT 边界）', async ({ page }) => {
  await ensureShowApi(page, 'configured');
  await page.goto('/admin/settings/showapi');
  await expect(page.getByText('AppKey 状态：已配置')).toBeVisible();
  const serverView = await settingsView(page);

  // —— 模拟一次 409 SETTINGS_CONFLICT（真实后端校验已在 API 契约用例覆盖）——
  let putAttempts = 0;
  await page.route(SETTINGS, async (route) => {
    if (route.request().method() !== 'PUT') return route.fallback();
    putAttempts += 1;
    await route.fulfill({ status: 409, headers: { 'Cache-Control': 'no-store' }, json: { code: 'SETTINGS_CONFLICT', message: '配置已被其他请求修改，请重新读取后重试', details: { currentRevision: serverView.revision + 5 }, fieldErrors: [] } });
  });
  await page.locator('label.ant-radio-wrapper', { hasText: '替换' }).click();
  await page.getByLabel('AppKey', { exact: true }).fill(`${FAKE_KEY}-CONFLICT`);
  await page.getByRole('button', { name: '保存配置', exact: true }).click();
  await expect(page.getByText(`配置已被其他请求修改（服务器版本 ${serverView.revision + 5}）`)).toBeVisible();
  await expect(page.getByLabel('AppKey', { exact: true }), '冲突后输入保留待人工核对').toHaveValue(`${FAKE_KEY}-CONFLICT`);
  await flushRender(page);
  expect(putAttempts, '409 后不得自动重发').toBe(1);
  await page.unroute(SETTINGS);
  await page.getByRole('button', { name: '重新读取核对' }).click();
  await expect(page.getByRole('status')).toContainText(`版本 ${serverView.revision}`);
  // 人工决定后手动重发（真实 PUT）成功
  let putPromise = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith(SETTINGS));
  await page.getByRole('button', { name: '保存配置', exact: true }).click();
  let put = await putPromise;
  expect(put.status()).toBe(200);
  await expect(page.getByText('ShowAPI 配置已保存，对下一次查询生效').first()).toBeVisible();

  // —— 模拟网络失败（保存结果未知）——
  await page.route(SETTINGS, async (route) => {
    if (route.request().method() !== 'PUT') return route.fallback();
    putAttempts += 1;
    await route.abort('failed');
  });
  await page.locator('label.ant-radio-wrapper', { hasText: '替换' }).click();
  await page.getByLabel('AppKey', { exact: true }).fill(`${FAKE_KEY}-UNKNOWN`);
  await page.getByRole('button', { name: '保存配置', exact: true }).click();
  await expect(page.getByText('保存结果未知，请重新读取配置核对；输入已保留，不会自动重发。')).toBeVisible();
  await expect(page.getByLabel('AppKey', { exact: true })).toHaveValue(`${FAKE_KEY}-UNKNOWN`);
  await flushRender(page);
  expect(putAttempts, '网络未知后不得自动重发').toBe(2);
  await page.unroute(SETTINGS);
  await page.getByRole('button', { name: '重新读取核对' }).click();
  await expect(page.getByRole('status')).toContainText('AppKey 已配置');
  putPromise = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith(SETTINGS));
  await page.getByRole('button', { name: '保存配置', exact: true }).click();
  put = await putPromise;
  expect(put.status()).toBe(200);
  await expect(page.getByText('ShowAPI 配置已保存，对下一次查询生效').first()).toBeVisible();
});

test('AC-S4/T2/S5 设置与 lookup API 契约：校验/真实 409/413/角色/CSRF（真实后端，无供应商调用）', async ({ page, browser }) => {
  await ensureShowApi(page, 'unconfigured');
  const view = await settingsView(page);

  // KEEP/CLEAR 携带 appKey（含 null）一律拒绝
  const badCases: Array<Record<string, unknown>> = [
    { enabled: false, appKeyAction: 'KEEP', appKey: null, expectedRevision: view.revision },
    { enabled: false, appKeyAction: 'CLEAR', appKey: FAKE_KEY, expectedRevision: view.revision },
    { enabled: true, appKeyAction: 'CLEAR', expectedRevision: view.revision },
    { enabled: false, appKeyAction: 'REPLACE', appKey: 'x'.repeat(513), expectedRevision: view.revision },
    { enabled: false, appKeyAction: 'REPLACE', appKey: '   ', expectedRevision: view.revision },
    { enabled: true, appKeyAction: 'KEEP', expectedRevision: view.revision },
    { enabled: false, appKeyAction: 'KEEP', expectedRevision: view.revision, unknownField: 1 },
  ];
  for (const bad of badCases) {
    const res = await rawPutSettings(page, bad);
    expect(res.status(), `非法 PUT 应 400：${JSON.stringify({ ...bad, appKey: bad.appKey ? '<redacted>' : bad.appKey })}`).toBe(400);
    expect((await res.json()).code).toBe('VALIDATION_FAILED');
    expect(await res.text(), '错误响应不得回显拒绝的 key').not.toContain(FAKE_KEY);
  }
  // >4KiB 请求体 → 413（流式上限）
  const big = await rawPutSettings(page, { enabled: false, appKeyAction: 'KEEP', expectedRevision: view.revision, padding: 'x'.repeat(5000) });
  expect(big.status()).toBe(413);
  // 真实乐观锁：成功 REPLACE 后旧 expectedRevision → 409 + currentRevision
  const ok = await rawPutSettings(page, { enabled: false, appKeyAction: 'REPLACE', appKey: FAKE_KEY, expectedRevision: view.revision });
  expect(ok.status()).toBe(200);
  expect(await ok.text()).not.toContain(FAKE_KEY);
  const stale = await rawPutSettings(page, { enabled: false, appKeyAction: 'KEEP', expectedRevision: view.revision });
  expect(stale.status()).toBe(409);
  const staleBody = await stale.json();
  expect(staleBody.code).toBe('SETTINGS_CONFLICT');
  expect(staleBody.details.currentRevision).toBe(view.revision + 1);
  expect(stale.headers()['cache-control']).toContain('no-store');
  // 保存配置不调用供应商：REPLACE 成功后外部守卫仍零命中（afterEach 断言）。

  // lookup 输入契约（未配置状态，校验先于供应商）
  const badLookup = await rawPostShowApi(page, { isbn: uniqueIsbn13(), extra: true });
  expect(badLookup.status()).toBe(400);
  const bigLookup = await rawPostShowApi(page, { isbn: uniqueIsbn13(), padding: 'x'.repeat(5000) });
  expect(bigLookup.status()).toBe(413);
  const noCsrf = await rawPostShowApi(page, { isbn: uniqueIsbn13() }, 'missing');
  expect(noCsrf.status()).toBe(403);
  const putNoCsrf = await rawPutSettings(page, { enabled: false, appKeyAction: 'KEEP', expectedRevision: view.revision + 1 }, 'missing');
  expect(putNoCsrf.status()).toBe(403);

  // READER：API 403 + UI 守卫 /403（种子 reader1，不新增读者避免污染共享临时库分页）
  const readerContext = await browser.newContext({ baseURL: BASE });
  const readerPage = await readerContext.newPage();
  await apiLogin(readerPage, SEEDED_READER_USERNAME, SEEDED_READER_PASSWORD);
  const readerPut = await rawPutSettings(readerPage, { enabled: false, appKeyAction: 'KEEP', expectedRevision: view.revision + 1 });
  expect(readerPut.status()).toBe(403);
  const readerGet = await readerPage.request.get(SETTINGS);
  expect(readerGet.status()).toBe(403);
  const readerLookup = await rawPostShowApi(readerPage, { isbn: uniqueIsbn13() });
  expect(readerLookup.status()).toBe(403);
  await readerPage.goto('/admin/settings/showapi');
  await expect(readerPage).toHaveURL(/\/403/);
  await expect(readerPage.getByRole('heading', { name: 'ShowAPI 书目来源' })).toHaveCount(0);
  await readerContext.close();
});

test('AC-S2/S3/T3 ShowAPI 候选：纯文本/只读附加/无目录/封面默认不选/人工变化不覆盖；应用→创建→持久封面→读者可见', async ({ page, browser }) => {
  test.setTimeout(90_000);
  await ensureShowApi(page, 'configured');
  const { calls } = await mockShowApiGolden(page);
  const isbn = uniqueIsbn13();
  await page.goto('/admin/books/new');
  await page.getByLabel('ISBN', { exact: true }).fill(isbn);
  await page.getByLabel('书名', { exact: true }).fill('人工标题保留');
  await switchSource(page, 'ShowAPI');
  await flushRender(page);
  expect(calls, '切换来源不查询').toHaveLength(0);
  await startLookup(page);
  const section = candidateSection(page);
  await expect(section.getByText('ShowAPI候选标题')).toBeVisible();
  expect(calls).toHaveLength(1);
  // 纯文本简介：HTML 标签以字面文本展示，不注入元素
  await expect(section.getByText('ShowAPI简介<b>不是HTML</b>')).toBeVisible();
  expect(await section.locator('b').count(), '简介不得注入 HTML 元素').toBe(0);
  // 只读附加信息 + 无目录声明 + 固定来源链接
  await expect(section.getByText('以下为 ShowAPI 只读附加信息，不写入书目草稿：')).toBeVisible();
  await expect(section.getByText('59.00元')).toBeVisible();
  await expect(section.getByText('此来源未提供目录')).toBeVisible();
  await expect(section.getByRole('link', { name: 'ShowAPI 来源' })).toHaveAttribute('href', SHOWAPI_SOURCE_URL);
  // 空字段预选、已填字段不预选、封面默认不选
  await expect(page.getByRole('checkbox', { name: '应用书名' })).not.toBeChecked();
  await expect(page.getByRole('checkbox', { name: '应用作者' })).toBeChecked();
  await expect(page.getByRole('checkbox', { name: '应用候选封面' })).not.toBeChecked();
  await page.screenshot({ path: path.join(SHOTS, 'int01-candidate-1440.png'), fullPage: true });
  // 勾选后人工修改字段 → 应用被拒，不覆盖人工内容
  await expect(page.getByRole('checkbox', { name: '应用出版社' })).toBeChecked();
  await page.getByLabel('出版社', { exact: true }).fill('人工出版社');
  await page.getByRole('button', { name: '应用选中项' }).click();
  await expect(candidateSection(page).getByRole('status')).toContainText('选中字段或封面已变化，请重新勾选后应用');
  await expect(page.getByLabel('出版社', { exact: true })).toHaveValue('人工出版社');
  await expect(page.getByLabel('作者', { exact: true })).toHaveValue('');
  // 恢复后重新勾选并应用（含封面）→ 仅草稿
  await page.getByLabel('出版社', { exact: true }).fill('');
  await page.getByRole('checkbox', { name: '应用出版社' }).check();
  await page.getByRole('checkbox', { name: '应用候选封面' }).check();
  await page.getByRole('button', { name: '应用选中项' }).click();
  await expect(candidateSection(page).getByRole('status')).toContainText('已应用到本页草稿，保存后生效');
  await expect(page.getByLabel('书名', { exact: true })).toHaveValue('人工标题保留');
  await expect(page.getByLabel('作者', { exact: true })).toHaveValue('ShowAPI作者');
  await expect(page.getByText('待保存 · ShowAPI 候选')).toBeVisible();
  // 创建（真实 POST）：通用 metadataSource + cover.source，不带旧字段
  const createPromise = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/books'));
  await page.getByRole('button', { name: '创建图书' }).click();
  const create = await createPromise;
  expect(create.status()).toBe(201);
  const createBody = create.request().postDataJSON();
  expect(createBody.metadataSource).toEqual({ provider: 'SHOWAPI', isbn });
  expect(createBody.cover.source).toEqual({ provider: 'SHOWAPI', isbn });
  expect('openLibraryIsbn' in createBody).toBe(false);
  expect('sourceIsbn' in createBody.cover).toBe(false);
  const created: BookDetail = await create.json();
  expect(created.sourceProvider).toBe('SHOWAPI');
  expect(created.sourceIsbn).toBe(isbn);
  expect(created.cover?.sourceProvider).toBe('SHOWAPI');
  expect(created.cover?.sourceIsbn).toBe(isbn);
  // 刷新后详情（真实后端数据）：书目/封面来源各自独立标注
  await page.goto(`/books/${created.id}`);
  await expect(page.getByRole('link', { name: `ShowAPI · ${isbn}` })).toHaveAttribute('href', SHOWAPI_SOURCE_URL);
  await expect(page.getByRole('link', { name: 'ShowAPI', exact: true })).toHaveAttribute('href', SHOWAPI_SOURCE_URL);
  await page.screenshot({ path: path.join(SHOTS, 'int01-detail-showapi-1440.png'), fullPage: true });
  // 持久二进制封面：字节与候选完全一致
  const coverRes = await page.request.get(`/api/books/${created.id}/cover`);
  expect(coverRes.status()).toBe(200);
  expect(coverRes.headers()['content-type']).toContain('image/png');
  expect((await coverRes.body()).equals(Buffer.from(PNG_BASE64, 'base64')), '落库封面字节应与候选一致').toBe(true);
  // 读者（独立会话，真实后端，种子 reader1）可见来源与封面
  const readerContext = await browser.newContext({ baseURL: BASE });
  const readerPage = await readerContext.newPage();
  await apiLogin(readerPage, SEEDED_READER_USERNAME, SEEDED_READER_PASSWORD);
  await readerPage.goto(`/books/${created.id}`);
  await expect(readerPage.getByRole('link', { name: `ShowAPI · ${isbn}` })).toBeVisible();
  await expect(readerPage.getByRole('link', { name: 'ShowAPI', exact: true })).toBeVisible();
  const readerCover = await readerPage.request.get(`/api/books/${created.id}/cover`);
  expect(readerCover.status()).toBe(200);
  expect((await readerCover.body()).equals(Buffer.from(PNG_BASE64, 'base64'))).toBe(true);
  await readerPage.screenshot({ path: path.join(SHOTS, 'int01-reader-detail-1440.png'), fullPage: true });
  await readerContext.close();
});

test('AC-S2/S3 封面缺失/坏封面部分成功：书目仍可应用；无封面保存后来源正确', async ({ page }) => {
  await ensureShowApi(page, 'configured');
  const isbnNoCover = uniqueIsbn13();
  const isbnBadCover = uniqueIsbn13();
  await page.route('**/api/catalog/showapi', async (route) => {
    const isbn = route.request().postDataJSON()?.isbn ?? '';
    const payload = showApiPayload(isbn);
    if (isbn === isbnNoCover) {
      payload.cover = null as never;
      payload.warnings = [{ code: 'COVER_UNAVAILABLE', field: 'cover', message: '封面获取失败，书目信息仍可应用' }] as never;
    } else {
      payload.cover = { mediaType: 'image/png', base64: '!!!not-base64!!!', byteLength: 3, width: 1, height: 1 } as never;
    }
    await route.fulfill({ json: payload });
  });
  // 无封面 + 警告：书目可应用并保存
  await page.goto('/admin/books/new');
  await page.getByLabel('ISBN', { exact: true }).fill(isbnNoCover);
  await switchSource(page, 'ShowAPI');
  await startLookup(page);
  const section = candidateSection(page);
  await expect(section.getByText('封面获取失败，书目信息仍可应用')).toBeVisible();
  await expect(section.getByText('封面暂不可用，请查看下方提示')).toBeVisible();
  await expect(page.getByRole('checkbox', { name: '应用候选封面' })).toBeDisabled();
  await page.getByRole('button', { name: '应用选中项' }).click();
  await expect(candidateSection(page).getByRole('status')).toContainText('已应用到本页草稿，保存后生效');
  const createPromise = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/books'));
  await page.getByRole('button', { name: '创建图书' }).click();
  const create = await createPromise;
  expect(create.status()).toBe(201);
  expect(create.request().postDataJSON().metadataSource).toEqual({ provider: 'SHOWAPI', isbn: isbnNoCover });
  expect('cover' in create.request().postDataJSON()).toBe(false);
  const created: BookDetail = await create.json();
  expect(created.sourceProvider).toBe('SHOWAPI');
  expect(created.cover ?? null).toBeNull();
  await page.goto(`/books/${created.id}`);
  await expect(page.getByRole('link', { name: `ShowAPI · ${isbnNoCover}` })).toBeVisible();
  await expect(page.getByText('暂无封面')).toBeVisible();
  // 坏封面：候选封面无法验证，但书目字段仍可应用
  await page.goto('/admin/books/new');
  await page.getByLabel('ISBN', { exact: true }).fill(isbnBadCover);
  await switchSource(page, 'ShowAPI');
  await startLookup(page);
  await expect(section.getByText('ShowAPI候选标题')).toBeVisible();
  await expect(section.getByText('候选封面无法验证，可继续手动选择本地封面')).toBeVisible();
  await expect(page.getByRole('checkbox', { name: '应用候选封面' })).toBeDisabled();
  await page.getByRole('button', { name: '应用选中项' }).click();
  await expect(page.getByLabel('书名', { exact: true })).toHaveValue('ShowAPI候选标题');
  await expect(page.getByText('未修改封面')).toBeVisible();
});

test('AC-S1/T3 来源独立与 OL 回归：metadata-only/cover-only/REMOVE 互不影响；OL 旧字段与诊断保留；应用仅草稿', async ({ page }) => {
  test.setTimeout(120_000);
  await ensureShowApi(page, 'configured');
  await mockShowApiGolden(page);
  await mockOlGolden(page);
  const { book } = await seedBook(page, 1);
  const isbn = book.isbn;
  const editUrl = `/admin/books/${book.id}/edit`;

  // ① ShowAPI metadata-only（封面默认不选）→ 保存：仅 metadataSource，无 cover 字段
  await page.goto(editUrl);
  await page.getByLabel('书名', { exact: true }).waitFor();
  await switchSource(page, 'ShowAPI');
  await startLookup(page);
  await expect(candidateSection(page).getByText('ShowAPI候选标题')).toBeVisible();
  await page.getByRole('button', { name: '应用选中项' }).click();
  await expect(candidateSection(page).getByRole('status')).toContainText('已应用到本页草稿，保存后生效');
  let savePromise = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith(`/api/books/${book.id}`));
  await page.getByRole('button', { name: '保存修改' }).click();
  let save = await savePromise;
  expect(save.status()).toBe(200);
  let body = save.request().postDataJSON();
  expect(body.metadataSource).toEqual({ provider: 'SHOWAPI', isbn });
  expect('cover' in body, 'metadata-only 不得携带 cover').toBe(false);
  expect('openLibraryIsbn' in body).toBe(false);
  await page.goto(`/books/${book.id}`);
  await expect(page.getByRole('link', { name: `ShowAPI · ${isbn}` })).toBeVisible();
  await expect(page.getByText('暂无封面')).toBeVisible();

  // ② OL metadata-only 回归：旧 openLibraryIsbn 字段、诊断行保留；应用后未保存前服务器数据不变
  await page.goto(editUrl);
  await page.getByLabel('书名', { exact: true }).waitFor();
  await startLookup(page); // 默认来源即 OL
  await expect(candidateSection(page).getByText('OL候选标题')).toBeVisible();
  await expect(candidateSection(page).getByText('封面 · 封面归档服务 · 上游 HTTP 503 · 直连')).toBeVisible();
  await page.getByRole('checkbox', { name: '应用书名' }).check();
  await page.getByRole('button', { name: '应用选中项' }).click();
  await expect(page.getByLabel('书名', { exact: true })).toHaveValue('OL候选标题');
  const beforeSave = await apiGet<BookDetail>(page, `/api/books/${book.id}`);
  expect(beforeSave.title, '应用仅改草稿，保存前服务器不变').toBe(book.title);
  expect(beforeSave.sourceProvider).toBe('SHOWAPI');
  savePromise = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith(`/api/books/${book.id}`));
  await page.getByRole('button', { name: '保存修改' }).click();
  save = await savePromise;
  expect(save.status()).toBe(200);
  body = save.request().postDataJSON();
  expect(body.openLibraryIsbn).toBe(isbn);
  expect('metadataSource' in body, 'OL 沿用旧字段，不发通用 metadataSource').toBe(false);
  expect('cover' in body).toBe(false);
  await page.goto(`/books/${book.id}`);
  await expect(page.getByRole('link', { name: `Open Library · ${isbn}` })).toHaveAttribute('href', `https://openlibrary.org/isbn/${isbn}`);

  // ③ ShowAPI cover-only：字段均已填 → 无预选；只勾封面 → 仅 cover.source，metadata 来源不变
  await page.goto(editUrl);
  await page.getByLabel('书名', { exact: true }).waitFor();
  await switchSource(page, 'ShowAPI');
  await startLookup(page);
  await expect(candidateSection(page).getByText('ShowAPI候选标题')).toBeVisible();
  await expect(page.getByRole('checkbox', { name: '应用书名' })).not.toBeChecked();
  await expect(page.getByRole('checkbox', { name: '应用内容简介' })).not.toBeChecked();
  await page.getByRole('checkbox', { name: '应用候选封面' }).check();
  await page.getByRole('button', { name: '应用选中项' }).click();
  await expect(page.getByText('待保存 · ShowAPI 候选')).toBeVisible();
  savePromise = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith(`/api/books/${book.id}`));
  await page.getByRole('button', { name: '保存修改' }).click();
  save = await savePromise;
  expect(save.status()).toBe(200);
  body = save.request().postDataJSON();
  expect(body.cover.source).toEqual({ provider: 'SHOWAPI', isbn });
  expect('metadataSource' in body, 'cover-only 不得改 metadata 来源').toBe(false);
  expect('openLibraryIsbn' in body).toBe(false);
  await page.goto(`/books/${book.id}`);
  await expect(page.getByRole('link', { name: `Open Library · ${isbn}` })).toBeVisible();
  await expect(page.getByRole('link', { name: 'ShowAPI', exact: true })).toBeVisible();
  await page.screenshot({ path: path.join(SHOTS, 'int01-ol-metadata-showapi-cover-1440.png'), fullPage: true });

  // ④ REMOVE 封面：metadata 来源保持 OL 不变
  await page.goto(editUrl);
  await page.getByLabel('书名', { exact: true }).waitFor();
  await page.getByRole('button', { name: '移除封面' }).click();
  await expect(page.getByText('待移除，保存后生效')).toBeVisible();
  savePromise = page.waitForResponse((r) => r.request().method() === 'PUT' && r.url().endsWith(`/api/books/${book.id}`));
  await page.getByRole('button', { name: '保存修改' }).click();
  save = await savePromise;
  expect(save.status()).toBe(200);
  body = save.request().postDataJSON();
  expect(body.cover).toEqual({ action: 'REMOVE' });
  expect('metadataSource' in body).toBe(false);
  expect('openLibraryIsbn' in body).toBe(false);
  await page.goto(`/books/${book.id}`);
  await expect(page.getByRole('link', { name: `Open Library · ${isbn}` })).toBeVisible();
  await expect(page.getByText('暂无封面')).toBeVisible();
});

test('T1 迟到拒绝：ISBN 变化/同 ISBN 切换来源/显式取消/响应 ISBN 错配均不采用', async ({ page }) => {
  await ensureShowApi(page, 'configured');
  const { pending, calls } = await mockShowApiGated(page);
  const ol = await mockOlGolden(page); // 守卫：若切换来源错误触发 OL 查询，会出现候选并被断言捕获
  const isbnA = uniqueIsbn13();
  const isbnB = uniqueIsbn13();
  await page.goto('/admin/books/new');
  await page.getByLabel('ISBN', { exact: true }).fill(isbnA);
  await page.getByLabel('书名', { exact: true }).fill('人工录入保留');
  await switchSource(page, 'ShowAPI');
  const section = candidateSection(page);

  // ① 查询挂起时修改 ISBN → 放行后迟到候选被拒
  await startLookup(page);
  await expect(page.getByRole('button', { name: '取消查询' })).toBeVisible();
  await page.getByLabel('ISBN', { exact: true }).fill(isbnB);
  let gate = pending.shift()!;
  gate.release();
  await gate.done;
  await flushRender(page);
  await expect(section.getByText('ShowAPI候选标题')).toHaveCount(0);
  await expect(page.getByLabel('书名', { exact: true })).toHaveValue('人工录入保留');
  expect(calls).toEqual([isbnA]);

  // ② 同 ISBN 挂起时切换来源 → 迟到候选被拒，且切换不触发新查询
  await startLookup(page);
  await expect(page.getByRole('button', { name: '取消查询' })).toBeVisible();
  await switchSource(page, 'Open Library');
  gate = pending.shift()!;
  gate.release();
  await gate.done;
  await flushRender(page);
  await expect(section.getByText('ShowAPI候选标题')).toHaveCount(0);
  await expect(section.getByText('OL候选标题')).toHaveCount(0);
  expect(calls).toEqual([isbnA, isbnB]);
  expect(ol.calls, '切换来源不得自动查询 OL').toHaveLength(0);

  // ③ 显式取消 → 迟到候选被拒
  await switchSource(page, 'ShowAPI');
  await startLookup(page);
  await page.getByRole('button', { name: '取消查询' }).click();
  gate = pending.shift()!;
  gate.release();
  await gate.done;
  await flushRender(page);
  await expect(section.getByText('ShowAPI候选标题')).toHaveCount(0);
  expect(calls).toEqual([isbnA, isbnB, isbnB]);

  // ④ 响应 ISBN 与请求不一致 → 整体拒绝，不进入候选
  await page.unroute('**/api/catalog/showapi');
  const mismatchIsbn = uniqueIsbn13();
  await page.route('**/api/catalog/showapi', async (route) => {
    calls.push(route.request().postDataJSON()?.isbn ?? '');
    await route.fulfill({ json: showApiPayload(mismatchIsbn) });
  });
  const responsePromise = page.waitForResponse((r) => r.request().method() === 'POST' && r.url().endsWith('/api/catalog/showapi'));
  await startLookup(page);
  await responsePromise;
  await flushRender(page);
  await expect(section.getByText('候选：')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '取消查询' })).toHaveCount(0);
  await expect(page.getByLabel('书名', { exact: true })).toHaveValue('人工录入保留');
  expect(calls, '全程无自动重试').toEqual([isbnA, isbnB, isbnB, isbnB]);
});

test('T1 16 秒超时提示与卸载清理：超时后可手动继续；离开页面后迟到响应无副作用', async ({ page }) => {
  test.setTimeout(60_000);
  await ensureShowApi(page, 'configured');
  const isbn = uniqueIsbn13();
  await page.goto('/admin/books/new');
  await page.getByLabel('ISBN', { exact: true }).fill(isbn);
  await switchSource(page, 'ShowAPI');
  // 永不放行的挂起路由：前端 16s AbortController 应触发超时提示（真实等待，语义断言）。
  await page.route('**/api/catalog/showapi', async () => { await new Promise<void>(() => {}); });
  await startLookup(page);
  await expect(page.getByText('书目查询超时，可继续手动录入或手动重试')).toBeVisible({ timeout: 25_000 });
  await expect(page.getByRole('button', { name: '取消查询' })).toHaveCount(0);
  await page.getByLabel('书名', { exact: true }).fill('超时后人工录入');
  await expect(page.getByLabel('书名', { exact: true })).toHaveValue('超时后人工录入');
  // 卸载：挂起中离开页面，放行迟到响应，不得产生页面错误或状态泄漏
  await page.unroute('**/api/catalog/showapi');
  const { pending } = await mockShowApiGated(page);
  await startLookup(page);
  await expect(page.getByRole('button', { name: '取消查询' })).toBeVisible();
  await page.goto('/admin/books');
  await expect(page.getByRole('heading', { name: '图书管理' })).toBeVisible();
  const gate = pending.shift()!;
  gate.release();
  await gate.done;
  await flushRender(page);
  expect(pageErrors, '卸载后迟到响应不得引发页面错误').toEqual([]);
});

test('AC-S2/UI 390/1440 无横向溢出；来源单选与候选勾选键盘可操作', async ({ page }) => {
  await ensureShowApi(page, 'configured');
  await mockShowApiGolden(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/admin/books/new');
  await page.getByLabel('ISBN', { exact: true }).fill(uniqueIsbn13());
  // 键盘：焦点在 OL 单选上，ArrowRight 切到 ShowAPI
  await page.getByRole('radio', { name: 'Open Library' }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(page.getByRole('radio', { name: 'ShowAPI' })).toBeChecked();
  await page.screenshot({ path: path.join(SHOTS, 'int01-keyboard-source-1440.png') });
  await startLookup(page);
  const section = candidateSection(page);
  await expect(section.getByText('ShowAPI候选标题')).toBeVisible();
  // 键盘：Space 切换候选勾选（书名为空 → 按契约预选为勾选态；Space 取消，再 Space 恢复）
  const titleCheckbox = page.getByRole('checkbox', { name: '应用书名' });
  await expect(titleCheckbox).toBeChecked();
  await titleCheckbox.focus();
  await page.keyboard.press('Space');
  await expect(titleCheckbox).not.toBeChecked();
  await page.keyboard.press('Space');
  await expect(titleCheckbox).toBeChecked();
  const overflow1440 = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow1440, '1440 表单无横向溢出').toBeLessThanOrEqual(1);
  // 390：表单（含候选）与设置页均无横向溢出。
  // 视口切换后 antd Sider 断点折叠有过渡动画；用 expect.poll 语义等待布局稳态，而非固定 sleep。
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(section.getByText('ShowAPI候选标题')).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth), { timeout: 5000, message: '390 表单稳态无横向溢出' }).toBeLessThanOrEqual(1);
  await page.screenshot({ path: path.join(SHOTS, 'int01-form-390.png'), fullPage: true });
  await page.goto('/admin/settings/showapi');
  await expect(page.getByText(/AppKey 状态/)).toBeVisible();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth), { timeout: 5000, message: '390 设置页稳态无横向溢出' }).toBeLessThanOrEqual(1);
  await page.screenshot({ path: path.join(SHOTS, 'int01-settings-390.png'), fullPage: true });
});