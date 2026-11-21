// REM-FE-INBOX / v1.1。独立前端浏览器自测，所有 /api 请求拦截模拟；不启动后端、不写数据库。
// 运行：FRONTEND_PORT=15197 VITE_API_PROXY_TARGET=http://127.0.0.1:9 npm --prefix frontend run dev -- --host 127.0.0.1
// /opt/homebrew/bin/node frontend/src/features/notifications/__tests__/browser-selftest.mjs
import { chromium, expect } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..');
const output = path.join(frontend, 'test-results/rem-fe-inbox');
await fs.mkdir(output, { recursive: true });
const baseURL = 'http://127.0.0.1:15197';
const browser = await chromium.launch({ headless: true });
const results = [];
const traffic = [];
const contexts = [];
const errors = [];
const reader = { id: 2, username: 'reader1', displayName: '演示读者一超长姓名', role: 'READER', readerId: 1, cardNo: 'R000001' };
const admin = { id: 1, username: 'admin', displayName: '管理员', role: 'ADMIN', readerId: null, cardNo: null };
function notice(id, overrides = {}) {
  return { id, type: 'DUE_SOON', title: '到期前提醒', content: '请在应还日前归还图书。本消息为站内提醒。', loanId: 100 + id, book: { id, title: `图书 ${id}` }, copyBarcode: `C${String(id).padStart(8, '0')}`, dueDate: '2026-11-22', overdueDays: null, seq: null, createdAt: '2026-11-19T08:00:00Z', readAt: null, loanState: { status: 'ACTIVE', currentDueDate: '2026-11-22' }, ...overrides };
}
function fixtures() {
  return Array.from({ length: 23 }, (_, i) => notice(23 - i, i === 0 ? { type: 'DUNNING', title: '历史催还通知', book: { id: 23, title: '已归还的图书' }, content: '请尽快归还图书。', loanState: { status: 'RETURNED', currentDueDate: '2026-11-22' } } : i === 1 ? { book: { id: 22, title: '已续借的图书' }, loanState: { status: 'ACTIVE', currentDueDate: '2026-12-22' } } : {}));
}
async function setup(name, user = reader, records = fixtures(), viewport = { width: 1440, height: 1000 }) {
  const context = await browser.newContext({ viewport });
  contexts.push(context);
  await context.addCookies([{ name: 'XSRF-TOKEN', value: 'mock-csrf', url: baseURL }]);
  const state = { user, records, listError: null, countError: null, writeError: null, holdList: null, holdWrite: null };
  const page = await context.newPage();
  page.on('pageerror', (error) => errors.push({ case: name, message: error.message }));
  await page.route('**/api/**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const method = request.method();
    const pathname = url.pathname;
    // Vite 模块路径 /src/api/* 不是服务端 API，必须放行源码加载。
    if (!pathname.startsWith('/api/')) return route.continue();
    traffic.push({ case: name, method, path: pathname, query: url.search, csrf: method === 'POST' ? request.headers()['x-xsrf-token'] === 'mock-csrf' : null });
    const fulfill = (data, status = 200) => route.fulfill({ status, contentType: 'application/json', body: status === 204 ? '' : JSON.stringify(data) });
    const error = (status, code) => fulfill({ status, code, message: 'mock error', fieldErrors: [], details: {}, path: pathname, timestamp: '2026-11-19T08:00:00Z' }, status);
    if (pathname === '/api/auth/csrf') return fulfill(null, 204);
    if (pathname === '/api/auth/me') return state.user ? fulfill(state.user) : error(401, 'AUTH_REQUIRED');
    if (pathname === '/api/auth/login') { state.user = reader; return fulfill(reader); }
    if (pathname === '/api/auth/logout') { state.user = null; return fulfill(null, 204); }
    if (pathname.startsWith('/api/notifications')) {
      if (!state.user) return error(401, 'AUTH_REQUIRED');
      if (state.user.role !== 'READER') return error(403, 'FORBIDDEN');
      if (method === 'POST' && request.headers()['x-xsrf-token'] !== 'mock-csrf') return error(403, 'CSRF_INVALID');
      if (pathname === '/api/notifications/me/unread-count') return state.countError ? error(...state.countError) : fulfill({ unreadCount: state.records.filter((r) => r.readAt === null).length });
      if (pathname === '/api/notifications/me') {
        if (state.holdList) await state.holdList;
        if (state.listError) return error(...state.listError);
        const pageIndex = Number(url.searchParams.get('page'));
        const size = Number(url.searchParams.get('size'));
        const all = state.records.filter((r) => url.searchParams.get('unreadOnly') !== 'true' || r.readAt === null).sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id - a.id);
        return fulfill({ items: all.slice(pageIndex * size, (pageIndex + 1) * size), page: pageIndex, size, totalItems: all.length, totalPages: Math.ceil(all.length / size) });
      }
      if (state.holdWrite) await state.holdWrite;
      if (state.writeError) return error(...state.writeError);
      if (pathname === '/api/notifications/me/read-all') {
        const updated = state.records.filter((r) => r.readAt === null).length;
        state.records.forEach((r) => { r.readAt ??= '2026-11-19T09:00:00Z'; });
        return fulfill({ updated });
      }
      const match = /\/me\/(\d+)\/read$/.exec(pathname);
      if (match) {
        const record = state.records.find((r) => r.id === Number(match[1]));
        if (!record) return error(404, 'NOTIFICATION_NOT_FOUND');
        record.readAt ??= '2026-11-19T09:00:00Z';
        return fulfill(null, 204);
      }
    }
    if (pathname === '/api/categories') return fulfill([]);
    if (pathname === '/api/books' || pathname === '/api/loans/me') return fulfill({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });
    errors.push({ case: name, message: `unexpected API ${method} ${pathname}` });
    return error(500, 'INTERNAL_ERROR');
  });
  return { page, state, context };
}
async function capture(page, name) { await page.screenshot({ path: path.join(output, `${name}.png`), fullPage: false }); }
async function run(name, action) {
  try { await action(); results.push({ name, result: 'PASS' }); }
  catch (error) { results.push({ name, result: 'FAIL', error: error.message }); throw error; }
}
try {
  const golden = await setup('golden', null);
  const { page, state } = golden;
  await run('未登录守卫→真实登录页面操作→本人收件箱', async () => {
    await page.goto(`${baseURL}/my/notifications`);
    await expect(page.getByText('欢迎回来')).toBeVisible();
    expect(traffic.filter((t) => t.case === 'golden' && t.path.startsWith('/api/notifications'))).toHaveLength(0);
    await page.getByLabel('用户名').fill('reader1');
    await page.getByLabel('密码', { exact: true }).fill('MockReader123');
    await page.getByRole('button', { name: /登\s*录/ }).click();
    await expect(page.getByRole('heading', { name: '我的提醒' })).toBeVisible();
    await expect(page.getByLabel('未读提醒 23 条')).toBeVisible();
    await page.waitForLoadState('networkidle');
    await capture(page, '01-reader-inbox-desktop');
  });
  await run('新到旧/分页/续借和归还历史不自动已读', async () => {
    const first = page.getByRole('article').first();
    await expect(first.getByText('已归还的图书')).toBeVisible();
    await expect(first.getByText(/该借阅已归还，无需再还书/)).toBeVisible();
    await expect(first.getByRole('button', { name: '标为已读', exact: true })).toBeEnabled();
    const renewed = page.getByRole('article').nth(1);
    await expect(renewed.getByText(/应还日已调整/)).toBeVisible();
    await expect(renewed.getByText('当前应还日：2026-12-22')).toBeVisible();
    await renewed.scrollIntoViewIfNeeded();
    await capture(page, '02-renewed-history');
    expect(traffic.filter((t) => t.case === 'golden' && t.method === 'POST' && t.path.startsWith('/api/notifications'))).toHaveLength(0);
    await page.locator('.ant-pagination-item-2 a').click();
    await expect(page.getByRole('heading', { name: '图书 3', exact: true })).toBeVisible();
    await expect(page.getByRole('article')).toHaveCount(3);
    await capture(page, '03-pagination');
    await page.getByRole('checkbox', { name: '仅看未读' }).check();
    await expect(page.getByRole('heading', { name: '已归还的图书' })).toBeVisible();
    expect(traffic.some((t) => t.case === 'golden' && t.query.includes('unreadOnly=true') && t.query.includes('page=0'))).toBe(true);
  });
  await run('单条已读/CSRF/角标刷新/未读过滤/防重复', async () => {
    let release;
    state.holdWrite = new Promise((resolve) => { release = resolve; });
    const first = page.getByRole('article').first().getByRole('button', { name: '标为已读', exact: true });
    await first.click();
    await expect(first).toBeDisabled();
    await expect(page.getByRole('button', { name: '全部标为已读' })).toBeDisabled();
    await capture(page, '04-single-read-pending');
    release(); state.holdWrite = null;
    await expect(page.getByLabel('未读提醒 22 条')).toBeVisible();
    await expect(page.getByRole('heading', { name: '已归还的图书' })).toHaveCount(0);
    expect(traffic.filter((t) => t.case === 'golden' && t.path === '/api/notifications/me/23/read')).toHaveLength(1);
    expect(traffic.filter((t) => t.case === 'golden' && t.method === 'POST' && t.path.startsWith('/api/notifications')).every((t) => t.csrf)).toBe(true);
    await capture(page, '05-unread-after-single');
  });
  await run('全部已读/空未读/旧记录持久保留/导航刷新/无后台轮询', async () => {
    await page.getByRole('button', { name: '全部标为已读' }).click();
    await expect(page.getByText('暂无未读提醒')).toBeVisible();
    await expect(page.getByLabel('未读提醒 0 条')).toBeVisible();
    await capture(page, '06-all-read-empty');
    await page.getByRole('checkbox', { name: '仅看未读' }).uncheck();
    await expect(page.getByRole('heading', { name: '已归还的图书' })).toBeVisible();
    await expect(page.getByRole('article').first().getByText('已读', { exact: true })).toBeVisible();
    state.records.push(notice(24));
    await page.getByText('我的借阅', { exact: true }).click();
    await expect(page.getByLabel('未读提醒 1 条')).toBeVisible();
    await page.getByText('我的提醒', { exact: true }).click();
    await expect(page.getByRole('heading', { name: '图书 24', exact: true })).toBeVisible();
    const before = traffic.filter((t) => t.case === 'golden' && t.path.startsWith('/api/notifications')).length;
    // 用浏览器虚拟时钟推进10分钟，而非真实 sleep；应无轮询请求。
    await page.clock.install();
    await page.clock.runFor(600000);
    const after = traffic.filter((t) => t.case === 'golden' && t.path.startsWith('/api/notifications')).length;
    expect(after).toBe(before);
    await capture(page, '07-navigation-new-notice');
  });

  const loading = await setup('loading', reader, [notice(1)]);
  await run('加载态/503系统繁忙/显式重试恢复', async () => {
    let release;
    loading.state.holdList = new Promise((resolve) => { release = resolve; });
    await loading.page.goto(`${baseURL}/my/notifications`, { waitUntil: 'domcontentloaded' });
    await expect(loading.page.getByText('正在加载提醒…')).toBeVisible();
    await expect(loading.page.getByRole('button', { name: '全部标为已读' })).toBeDisabled();
    await capture(loading.page, '08-loading');
    loading.state.listError = [503, 'DB_BUSY'];
    release(); loading.state.holdList = null;
    await expect(loading.page.getByText('系统繁忙，请稍后再试')).toBeVisible();
    await capture(loading.page, '09-list-503');
    loading.state.listError = null;
    await loading.page.getByRole('button', { name: /重\s*试/ }).click();
    await expect(loading.page.getByRole('heading', { name: '图书 1' })).toBeVisible();
    await capture(loading.page, '10-retry-recovered');
  });
  await run('404/CSRF/503写入失败不误标已读，原按钮可重试', async () => {
    for (const [status, code, text] of [[404, 'NOTIFICATION_NOT_FOUND', '通知不存在或无权限查看'], [403, 'CSRF_INVALID', '页面安全令牌已失效，请重试'], [503, 'DB_BUSY', '系统繁忙，请稍后再试']]) {
      loading.state.writeError = [status, code];
      await loading.page.getByRole('button', { name: '标为已读', exact: true }).click();
      await expect(loading.page.getByText(text)).toBeVisible();
      await expect(loading.page.getByRole('button', { name: '标为已读', exact: true })).toBeEnabled();
      await expect(loading.page.getByLabel('未读提醒 1 条')).toBeVisible();
      await capture(loading.page, `11-write-${status}-${code}`);
    }
    loading.state.writeError = null;
    await loading.page.getByRole('button', { name: '标为已读', exact: true }).click();
    await expect(loading.page.getByRole('article').getByText('已读', { exact: true })).toBeVisible();
    await expect(loading.page.getByLabel('未读提醒 0 条')).toBeVisible();
  });

  const empty = await setup('empty', reader, []);
  await run('普通空态与未读计数错误/重试', async () => {
    empty.state.countError = [503, 'DB_BUSY'];
    await empty.page.goto(`${baseURL}/my/notifications`);
    await expect(empty.page.getByText('暂无提醒')).toBeVisible();
    const retry = empty.page.getByRole('button', { name: '未读数加载失败，重试' });
    await expect(retry).toBeVisible();
    await capture(empty.page, '12-empty-count-error');
    empty.state.countError = null;
    await retry.click();
    await expect(empty.page.getByLabel('未读提醒 0 条')).toBeVisible();
    await capture(empty.page, '13-empty');
  });

  const mobile = await setup('mobile', reader, fixtures().slice(0, 3), { width: 390, height: 844 });
  await run('390px顶栏单行、不横向溢出、折叠侧栏角标可见', async () => {
    await mobile.page.goto(`${baseURL}/my/notifications`);
    await expect(mobile.page.getByRole('heading', { name: '已归还的图书' })).toBeVisible();
    await mobile.page.waitForLoadState('networkidle');
    const metrics = await mobile.page.evaluate(() => {
      const header = document.querySelector('header');
      const tag = header.querySelector('.ant-tag').getBoundingClientRect();
      const avatar = header.querySelector('.ant-avatar').getBoundingClientRect();
      const button = header.querySelector('button').getBoundingClientRect();
      const item = document.querySelector('.ant-menu-item-selected').getBoundingClientRect();
      const badge = document.querySelector('.ant-menu-item-selected .ant-badge-count').getBoundingClientRect();
      return { width: window.innerWidth, scrollWidth: document.documentElement.scrollWidth, centers: [tag, avatar, button].map((r) => r.y + r.height / 2), buttonRight: button.right, badgeTop: badge.top, badgeBottom: badge.bottom, menuTop: item.top, menuBottom: item.bottom };
    });
    expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.width);
    expect(Math.max(...metrics.centers) - Math.min(...metrics.centers)).toBeLessThan(3);
    expect(metrics.buttonRight).toBeLessThanOrEqual(390);
    expect(metrics.badgeTop).toBeGreaterThanOrEqual(metrics.menuTop);
    expect(metrics.badgeBottom).toBeLessThanOrEqual(metrics.menuBottom);
    await expect(mobile.page.getByLabel('未读提醒 3 条')).toBeVisible();
    results.push({ name: '390px metrics', result: 'PASS', metrics });
    await capture(mobile.page, '14-mobile-390');
  });

  const administrator = await setup('admin', admin);
  await run('ADMIN催还菜单仅占位，读者收件箱被守卫且无读者请求', async () => {
    await administrator.page.goto(`${baseURL}/admin/dunning`);
    await expect(administrator.page.getByText('催还记录功能待接入')).toBeVisible();
    await expect(administrator.page.getByText('我的提醒', { exact: true })).toHaveCount(0);
    await capture(administrator.page, '15-admin-placeholder');
    await administrator.page.goto(`${baseURL}/my/notifications`);
    await expect(administrator.page.getByText('无权限访问该页面')).toBeVisible();
    expect(traffic.filter((t) => t.case === 'admin' && t.path.startsWith('/api/notifications'))).toHaveLength(0);
    await capture(administrator.page, '16-admin-forbidden');
  });
  const malicious = await setup('text', reader, [notice(1, { title: '<script>evil</script>', content: '<img src=x onerror=alert(1)>', type: 'OVERDUE', overdueDays: 8, seq: 2, createdAt: '2026-11-18T16:00:00Z', loanState: { status: 'OVERDUE', currentDueDate: '2026-11-11' } })]);
  await run('外部输入仅文本/逾期历史值/上海UTC午夜边界', async () => {
    await malicious.page.goto(`${baseURL}/my/notifications`);
    await expect(malicious.page.getByText('<img src=x onerror=alert(1)>')).toBeVisible();
    await expect(malicious.page.locator('article img')).toHaveCount(0);
    await expect(malicious.page.getByText('生成时逾期：8 天')).toBeVisible();
    await expect(malicious.page.getByText('生成时间：2026-11-19 00:00')).toBeVisible();
    await capture(malicious.page, '17-overdue-escaped');
  });
  const expired = await setup('expired', reader, [notice(1)]);
  await run('通知401沿用会话过期流程→登录页', async () => {
    await expired.page.goto(`${baseURL}/my/notifications`);
    await expect(expired.page.getByRole('heading', { name: '图书 1' })).toBeVisible();
    expired.state.user = null;
    await expired.page.getByText('我的借阅', { exact: true }).click();
    await expect(expired.page.getByText('欢迎回来')).toBeVisible();
    await expect(expired.page.getByText('登录已过期，请重新登录')).toBeVisible();
    await capture(expired.page, '18-session-expired');
  });
  expect(errors).toEqual([]);
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  const reportPath = path.join(output, `browser-results-${Date.now()}.json`);
  const report = JSON.stringify({ taskId: 'lib-borrow-reminder-p1', subtaskId: 'REM-FE-INBOX', planVersion: 'reminder-plan-v1.1', mockOnly: true, browser: await browser.version(), baseURL, results, errors, traffic }, null, 2);
  await fs.writeFile(reportPath, report);
  await fs.writeFile(path.join(output, 'browser-results.json'), report);
  console.log(JSON.stringify({ results, errors, output }, null, 2));
  for (const context of contexts) await context.close();
  await browser.close();
}
