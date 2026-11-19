// acc-renew-check.mjs — 柜台续借 UI 专项（AC-LOAN-05 前端）
import { chromium } from '/tmp/acc-clone.yYiWjW/frontend/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';

const OUT = '/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/acceptance/screenshots';
const results = [];
const log = (n, ok, d = '') => { results.push({ n, ok, d }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${n} ${d}`); };

const browser = await chromium.launch({
  headless: true,
  executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1254/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
});
const page = await (await browser.newContext({ viewport: { width: 1366, height: 900 } })).newPage();

await page.goto('http://localhost:15195/login');
await page.getByLabel('用户名').fill('admin');
await page.getByLabel('密码').fill('Admin@12345');
await page.getByRole('button', { name: /登\s*录/ }).click();
await page.waitForTimeout(1500);
await page.goto('http://localhost:15195/admin/circulation');
await page.waitForTimeout(800);

await page.locator('#circ-card-no').fill('R000004');
await page.locator('#circ-borrow-barcode').press('Enter'); // 只输卡号 → 查在借
await page.waitForTimeout(1200);

const before = await page.locator('[aria-label="读者在借列表"]').innerText();
const hasRenewBtn = /续\s*借/.test(before);
log('柜台在借列表提供续借按钮（D2 管理员操作）', hasRenewBtn, before.slice(0, 120).replace(/\n/g, '|'));

// 点击第一行的续借按钮
const row = page.locator('[aria-label="读者在借列表"] tr:has-text("算法导论")');
await row.getByRole('button', { name: /续\s*借/ }).click();
await page.waitForTimeout(1500);
const after = await page.locator('[aria-label="读者在借列表"]').innerText();
const renewed = /1\/1/.test(after) && /2027-01-17/.test(after);
log('柜台续借后应还日顺延 30 天且续借次数 1/1', renewed, after.slice(0, 200).replace(/\n/g, '|'));
await page.screenshot({ path: `${OUT}/ui-08b-circulation-renew.png` });

// 再次续借应失败（RENEW_LIMIT_REACHED 中文提示）
await row.getByRole('button', { name: /续\s*借/ }).click().catch(() => {});
await page.waitForTimeout(1500);
const body = await page.locator('body').innerText();
const secondRefused = /续借次数已达上限|不能续借|已达上限/.test(body);
log('柜台重复续借被拒并显示中文原因', secondRefused, body.slice(-150).replace(/\n/g, '|'));
await page.screenshot({ path: `${OUT}/ui-08e-renew-limit.png` });

await browser.close();
const fails = results.filter(r => !r.ok).length;
console.log(`\nSUMMARY: ${results.length} checks, ${fails} failures`);
fs.writeFileSync('/tmp/acc-renew-check-result.json', JSON.stringify(results, null, 2));
process.exit(fails ? 1 : 0);
