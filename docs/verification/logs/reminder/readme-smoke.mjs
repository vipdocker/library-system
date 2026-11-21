// REM-INT v1.5, repeatable development self-test; no production mocks or DB edits.
// Run from repo root: /opt/homebrew/bin/node docs/verification/logs/reminder/readme-smoke.mjs
import { createRequire } from 'node:module';
import { spawn, execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import assert from 'node:assert/strict';
const root = process.cwd();
const { chromium, expect } = createRequire(path.join(root, 'frontend/package.json'))('@playwright/test');
const ports = [18097, 15197];
const db = path.join(mkdtempSync(path.join(tmpdir(), 'library-reminder-int-')), 'library.db');
const url = 'http://localhost:15197';
const evidence = path.join(root, 'docs/verification/screenshots/reminder');
const errors = [];
const rows = [];
let server, browser, serverLog = '', startedAt;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
function freePorts() {
  for (const port of ports) {
    try { const text = execFileSync('lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN'], { encoding: 'utf8' }); assert.equal(text.trim(), '', `Port ${port} must be free`); }
    catch (err) { if (err.status !== 1) throw err; }
  }
}
async function start() {
  freePorts(); serverLog = ''; startedAt = Date.now();
  server = spawn('bash', ['scripts/dev.sh'], { cwd: root, env: { ...process.env, LIB_DB_PATH: db, SERVER_PORT: '18097', FRONTEND_PORT: '15197' }, stdio: ['ignore', 'pipe', 'pipe'] });
  for (const stream of [server.stdout, server.stderr]) stream.on('data', data => { serverLog += data.toString(); process.stdout.write(data); });
  while (Date.now() - startedAt < 120_000) {
    assert.equal(server.exitCode, null, 'dev.sh must stay running');
    try { if ((await fetch(url)).ok) { rows.push({ name: 'README dev.sh startup', milliseconds: Date.now() - startedAt, result: 'PASS' }); return; } } catch {}
    await sleep(250);
  }
  throw new Error('README startup exceeded 120s');
}
async function stop() {
  if (!server || server.exitCode !== null) return;
  // Own dev.sh PID only; its approved script owns child cleanup. TERM uses the same cleanup as Ctrl-C.
  const exited = new Promise(resolve => server.once('exit', (code, signal) => resolve({ code, signal })));
  server.kill('SIGTERM');
  const result = await Promise.race([exited, sleep(15_000).then(() => { throw new Error('Own dev.sh did not stop'); })]);
  freePorts(); rows.push({ name: 'README owned process cleanup (TERM, not keyboard Ctrl-C)', result: 'PASS', exit: result });
}
async function login(page, username) {
  await page.goto(`${url}/login`);
  await page.getByLabel('用户名').fill(username);
  await page.getByLabel('密码', { exact: true }).fill(username === 'admin' ? 'Admin@12345' : 'Reader@123');
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
  await page.waitForLoadState('networkidle');
}
async function logout(page) {
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page).toHaveURL(/\/login$/);
}
async function shot(page, name) { await page.screenshot({ path: path.join(evidence, `${name}.png`), fullPage: true }); }
async function get(page, endpoint) { const response = await page.request.get(url + endpoint); assert.equal(response.status(), 200); return response.json(); }
try {
  assert.equal(process.env.LIB_REMINDER_SCANINTERVAL, undefined, 'README smoke uses default PT1H, not E2E override');
  await start();
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  page.on('pageerror', error => errors.push(error.message));
  await login(page, 'admin');
  const initial = await get(page, '/api/stats/overview');
  assert.ok(serverLog.indexOf('seed.demo.loaded') >= 0);
  assert.ok(serverLog.indexOf('reminder.scan date=') > serverLog.indexOf('seed.demo.loaded'));
  // Occupied-port execution must refuse rather than stop another server.
  const occupied = spawn('bash', ['scripts/dev.sh'], { cwd: root, env: { ...process.env, LIB_DB_PATH: db, SERVER_PORT: '18097', FRONTEND_PORT: '15197' } });
  let occupiedLog = '';
  for (const stream of [occupied.stdout, occupied.stderr]) stream.on('data', data => occupiedLog += data.toString());
  const code = await new Promise(resolve => occupied.once('exit', resolve));
  assert.equal(code, 1); assert.match(occupiedLog, /已被占用/); console.log('OCCUPIED-PORT', occupiedLog);
  await browser.close(); browser = undefined;
  await stop(); await start();
  browser = await chromium.launch({ headless: true });
  const current = await browser.newPage(); current.on('pageerror', error => errors.push(error.message));
  await login(current, 'admin');
  assert.deepEqual(await get(current, '/api/stats/overview'), initial);
  rows.push({ name: 'Real dev restart seed/reminder idempotence and occupied-port rejection', result: 'PASS' });
  await current.goto(url + '/admin/dunning'); await expect(current.getByText('暂无催还案件')).toBeVisible();
  await shot(current, 'readme-empty-cases');
  await current.goto(url + '/admin/reports/overdue');
  const overdue = (await get(current, '/api/reports/overdue')).items[0];
  assert.ok(overdue);
  await current.getByRole('row').filter({ hasText: overdue.copyBarcode }).getByRole('button', { name: '登记催还' }).click();
  const dialog = current.getByRole('dialog', { name: '登记催还', exact: true });
  await dialog.getByRole('button', { name: '确认登记' }).click();
  await expect(dialog.getByText('请选择催还方式', { exact: true })).toBeVisible();
  await dialog.getByRole('radio', { name: '电话', exact: true }).check();
  await dialog.getByLabel('管理员备注').fill('密'.repeat(501));
  await dialog.getByRole('button', { name: '确认登记' }).click();
  await expect(dialog.getByText('备注最多500字符', { exact: true })).toBeVisible();
  await dialog.getByLabel('管理员备注').fill('临时库私密备注');
  const postPattern = `**/api/loans/${overdue.loanId}/dunning-records`;
  let posts = 0, release;
  await current.route(postPattern, async route => {
    if (route.request().method() !== 'POST') return route.continue();
    posts++; await new Promise(resolve => release = resolve);
    await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ status: 503, code: 'DB_BUSY', message: '系统繁忙', fieldErrors: [], details: {} }) });
  });
  await dialog.getByRole('button', { name: '确认登记' }).click();
  await expect(dialog.getByRole('button', { name: '确认登记' })).toBeDisabled();
  await expect(dialog.getByRole('button', { name: '取消登记' })).toBeDisabled();
  assert.equal(posts, 1); release();
  await expect(dialog.getByText('登记未成功')).toBeVisible();
  await expect(dialog.getByText(/系统繁忙/)).toBeVisible();
  await expect(dialog.getByLabel('管理员备注')).toHaveValue('临时库私密备注');
  await shot(current, 'mock-registration-503-retained-input');
  await current.unroute(postPattern);
  await dialog.getByRole('button', { name: '刷新逾期报表', exact: true }).click();
  // Refresh intentionally closes stale eligibility modal (OverdueReportPage.refresh).
  await expect(dialog).toBeHidden();
  await current.getByRole('row').filter({ hasText: overdue.copyBarcode }).getByRole('button', { name: '登记催还' }).click();
  await dialog.getByRole('radio', { name: '电话', exact: true }).check();
  await dialog.getByLabel('管理员备注').fill('临时库私密备注');
  await dialog.getByRole('button', { name: '确认登记' }).click();
  await expect(dialog).toBeHidden();
  rows.push({ name: 'Browser client validation / held-submit lock / mocked503 input preserved / explicit real201 recovery', result: 'PASS', simulatedFailure: true, heldPosts: posts });
  await current.goto(url + '/admin/dunning');
  await expect(current.getByRole('article')).toHaveCount(1);
  await current.setViewportSize({ width: 390, height: 844 });
  await current.waitForLoadState('networkidle');
  const metric = await current.evaluate(() => ({ width: innerWidth, scrollWidth: document.documentElement.scrollWidth }));
  assert.equal(metric.scrollWidth, metric.width); await shot(current, 'readme-cases-390px');
  await current.getByRole('button', { name: '查看历史' }).click();
  await expect(current.getByRole('dialog')).toContainText('临时库私密备注'); await shot(current, 'readme-history-390px');
  await current.getByRole('button', { name: '关闭历史' }).click();
  await current.setViewportSize({ width: 1280, height: 900 });
  await logout(current); await login(current, 'reader2');
  await current.goto(url + '/my/notifications');
  await expect(current.getByRole('article')).toHaveCount(2);
  await expect(current.locator('body')).not.toContainText('临时库私密备注');
  await expect(current.getByLabel('未读提醒 2 条', { exact: true })).toBeVisible();
  await current.setViewportSize({ width: 390, height: 844 }); await shot(current, 'readme-reader-390px');
  await current.getByRole('button', { name: '全部标为已读', exact: true }).click();
  await expect(current.getByLabel('未读提醒 0 条', { exact: true })).toBeVisible();
  await current.getByRole('checkbox', { name: '仅看未读' }).check();
  await expect(current.getByText('暂无未读提醒')).toBeVisible();
  await current.setViewportSize({ width: 1280, height: 900 });
  await logout(current); await login(current, 'admin');
  await current.goto(url + '/admin/circulation'); await current.getByRole('tab', { name: '还书' }).click();
  const returned = current.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith('/api/loans/return-by-barcode'));
  await current.getByLabel('副本条码').fill(overdue.copyBarcode); await current.getByLabel('副本条码').press('Enter');
  assert.equal((await returned).status(), 200);
  await current.goto(url + '/admin/dunning'); await current.getByRole('radio', { name: '仅已结案' }).check();
  await expect(current.getByRole('article')).toContainText('结案原因：已归还'); await shot(current, 'readme-closed-case');
  await logout(current); await login(current, 'reader2'); await current.goto(url + '/my/notifications');
  await expect(current.getByRole('article').first()).toContainText('该借阅已归还');
  await shot(current, 'readme-returned-inbox');
  rows.push({ name: 'README admin/reader smoke; real defaultPT1H startup notice, private note, badge, 390px, barcode closure and retained history', result: 'PASS', metric });
  assert.deepEqual(errors, []);
} catch (error) {
  console.error('FAIL', error.stack); process.exitCode = 1;
} finally {
  if (browser) await browser.close();
  await stop();
  console.log('REM-INT-README-RESULT', JSON.stringify({ db, ports, rows, pageErrors: errors, temporaryDbRetained: true, dependencyInstall: 'NOT_RUN; existing node_modules/cache', keyboardCtrlC: 'NOT_RUN; TERM cleanup verified' }, null, 2));
}
