// acc-ui-check.mjs — 独立验收补充 UI 检查（第 2 版：修正路由与断言）
import { chromium } from '/tmp/acc-clone.yYiWjW/frontend/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';

const OUT = '/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/acceptance/screenshots';
fs.mkdirSync(OUT, { recursive: true });
const results = [];
const log = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${name} ${detail}`);
};
const shot = async (page, file) => page.screenshot({ path: `${OUT}/${file}`, fullPage: false });

const browser = await chromium.launch({
  headless: true,
  executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1254/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
});
const ctx = await browser.newContext({ viewport: { width: 1366, height: 900 } });
const page = await ctx.newPage();

const doLogin = async (user, pass) => {
  await page.goto('http://localhost:15195/login');
  await page.getByLabel('用户名').fill(user);
  await page.getByLabel('密码').fill(pass);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await page.waitForTimeout(1200);
};
const doLogout = async () => {
  await page.getByRole('button', { name: /退出登录/ }).first().click();
  await page.waitForURL(/\/login/, { timeout: 8000 });
  await page.waitForTimeout(400);
};

// ---------- 1. 未登录直接打开受保护页 → /login?redirect= → 登录后回原页 ----------
await page.goto('http://localhost:15195/admin/readers');
await page.waitForURL(/\/login\?redirect=/, { timeout: 8000 });
log('I-09a 未登录访问受保护页带 redirect', true, page.url());
await shot(page, 'ui-01-unauth-redirect.png');
await page.getByLabel('用户名').fill('admin');
await page.getByLabel('密码').fill('Admin@12345');
await page.getByRole('button', { name: /登\s*录/ }).click();
await page.waitForURL(/\/admin\/readers$/, { timeout: 8000 });
log('I-09b 登录后回到原页面（redirect 生效）', true, page.url());
await page.waitForSelector('table', { timeout: 8000 });
await shot(page, 'ui-02-admin-readers.png');

// ---------- 2. 管理员在管理页登出 → /login 无 redirect ----------
await doLogout();
log('I-09c 管理页登出后 /login 无 redirect 残留', !/redirect=/.test(page.url()), page.url());
await shot(page, 'ui-03-after-logout.png');

// ---------- 3. 读者登录 → 落在读者首页（/），无 403，无 redirect ----------
await doLogin('reader1', 'Reader@123');
await page.waitForTimeout(600);
const url3 = page.url();
const has403 = await page.getByText(/403|无权限/).count();
log('I-09d 读者登录后落在读者首页且无 403', /^http:\/\/localhost:15195\/$/.test(url3) && has403 === 0, url3);
await shot(page, 'ui-04-reader-home.png');

// ---------- 4. 读者访问 /admin/books → 403 页 ----------
await page.goto('http://localhost:15195/admin/books');
await page.waitForTimeout(800);
log('AC-AUTH-09 读者访问 /admin/books 显示 403 页', page.url().endsWith('/403'), page.url());
await shot(page, 'ui-05-reader-403.png');

// ---------- 5. 读者菜单只含检索/我的借阅/我的资料/修改口令 ----------
const menuText = await page.locator('.ant-menu').first().innerText().catch(() => '');
const expectItems = ['图书检索', '我的借阅', '我的资料', '修改口令'];
const allPresent = expectItems.every(t => menuText.includes(t));
const noAdmin = !/分类管理|图书管理|读者管理|流通柜台|统计|报表/.test(menuText);
log('AC-UI-05 读者菜单为四项且无管理员项', allPresent && noAdmin, menuText.replace(/\n/g, '/'));

// ---------- 6. reader2：我的借阅含逾期记录（AC-LOAN-07） ----------
await doLogout();
await doLogin('reader2', 'Reader@123');
await page.goto('http://localhost:15195/my/loans');
await page.waitForSelector('table, .ant-empty', { timeout: 8000 });
await page.waitForTimeout(600);
const overdueCells = await page.locator('.ant-tag-red, [class*="red"], .ant-table-tbody').filter({ hasText: /逾期/ }).count();
const bodyLoans = await page.locator('body').innerText();
log('AC-LOAN-07 我的借阅显示逾期', /逾期/.test(bodyLoans), bodyLoans.slice(0, 150).replace(/\n/g, '|'));
await shot(page, 'ui-06-reader2-loans-overdue.png');

// ---------- 7. reader2：我的资料（AC-RDR-06） ----------
await page.goto('http://localhost:15195/my/profile');
await page.waitForTimeout(800);
const body = await page.locator('body').innerText();
const profileOk = /R000002/.test(body) && /演示读者二/.test(body);
log('AC-RDR-06 我的资料显示卡号姓名', profileOk, body.slice(0, 150).replace(/\n/g, '|'));
await shot(page, 'ui-07-reader2-profile.png');

// ---------- 8. 管理员流通柜台：回车借书（AC-UI-04） ----------
await doLogout();
await doLogin('admin', 'Admin@12345');
await page.goto('http://localhost:15195/admin/circulation');
await page.waitForTimeout(1000);
const cardInput = page.getByPlaceholder(/卡号/, { exact: false }).first();
const inputs = page.locator('input');
const n = await inputs.count();
let cardFilled = false;
for (let i = 0; i < n; i++) {
  const ph = await inputs.nth(i).getAttribute('placeholder') || '';
  if (/卡号/.test(ph)) { await inputs.nth(i).fill('R000004'); cardFilled = true; break; }
}
let barcodeFilled = false;
for (let i = 0; i < n; i++) {
  const ph = await inputs.nth(i).getAttribute('placeholder') || '';
  if (/条码/.test(ph)) { await inputs.nth(i).fill('C00000011'); await inputs.nth(i).press('Enter'); barcodeFilled = true; break; }
}
await page.waitForTimeout(1800);
const circ = await page.locator('body').innerText();
const borrowOk = /C00000011/.test(circ) && (/借阅成功|成功/.test(circ));
log('AC-UI-04 柜台回车借书', borrowOk && cardFilled && barcodeFilled, `card=${cardFilled} bc=${barcodeFilled} ${circ.slice(0, 200).replace(/\n/g, '|')}`);
await shot(page, 'ui-08-circulation-borrow.png');
log('AC-LOAN-05/D2 柜台提供续借操作', /续借/.test(circ));

// ---------- 9. 统计页与逾期报表、仪表盘（AC-UI-06 / AC-STAT-04） ----------
await page.goto('http://localhost:15195/admin/stats');
await page.waitForTimeout(1000);
const statsText = await page.locator('body').innerText();
log('AC-UI-06 统计页渲染（日/月切换）', /借阅统计/.test(statsText) && /日/.test(statsText) && /月/.test(statsText), '');
await shot(page, 'ui-09-stats.png');
await page.goto('http://localhost:15195/admin/reports/overdue');
await page.waitForTimeout(1000);
const reportText = await page.locator('body').innerText();
log('AC-STAT-04 逾期报表含 reader2 逾期借阅', /R000002|Python编程从入门到实践/.test(reportText), '');
await shot(page, 'ui-10-overdue-report.png');
await page.goto('http://localhost:15195/admin/dashboard');
await page.waitForTimeout(1000);
await shot(page, 'ui-11-dashboard.png');
const dashText = await page.locator('body').innerText();
log('AC-UI-06 仪表盘渲染', /仪表盘|总藏书|在借/.test(dashText), dashText.slice(0, 120).replace(/\n/g, '|'));

await browser.close();
const fails = results.filter(r => !r.ok).length;
console.log(`\nSUMMARY: ${results.length} checks, ${fails} failures`);
fs.writeFileSync('/tmp/acc-ui-check-result.json', JSON.stringify(results, null, 2));
process.exit(fails ? 1 : 0);
