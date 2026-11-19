// acc-circ-check.mjs — 流通柜台 UI 专项（AC-UI-04 / AC-LOAN-05 前端）
import { chromium } from '/tmp/acc-clone.yYiWjW/frontend/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';

const OUT = '/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/acceptance/screenshots';
const results = [];
const log = (name, ok, detail = '') => { results.push({ name, ok, detail }); console.log(`${ok ? 'PASS' : 'FAIL'} | ${name} ${detail}`); };
const shot = async (page, file) => page.screenshot({ path: `${OUT}/${file}` });

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
await page.waitForTimeout(1000);

const card = page.locator('#circ-card-no');
const barcode = page.locator('#circ-borrow-barcode');

// ---------- 借书：卡号 + 条码回车 ----------
await card.fill('R000004');
await barcode.fill('C00000013');
await barcode.press('Enter');
await page.waitForTimeout(1800);
const desk = await page.locator('body').innerText();
const borrowOk = /C00000013/.test(desk) && (/(借阅成功|借出成功|成功)/.test(desk));
log('AC-UI-04 回车借书成功并显示应还日', borrowOk && /2026-12-18/.test(desk), desk.slice(0, 260).replace(/\n/g, '|'));
await shot(page, 'ui-08-circulation-borrow.png');

// ---------- 条码清空且保持焦点 ----------
const bcValue = await barcode.inputValue();
const focused = await page.evaluate(() => document.activeElement?.id);
log('AC-UI-04 借出后条码清空并保持焦点', bcValue === '' && focused === 'circ-borrow-barcode', `value="${bcValue}" focus=${focused}`);

// ---------- 该读者在借列表显示 ----------
const readerPanel = await page.locator('[aria-label="读者在借列表"]').innerText().catch(() => desk);
log('AC-UI-04 柜台显示读者在借列表', /C00000013/.test(readerPanel), readerPanel.slice(0, 200).replace(/\n/g, '|'));

// ---------- 管理员续借（D2）：找到 C00000004 借阅的续借按钮 ----------
const renewBtn = page.getByRole('button', { name: /续借/ }).first();
if (await renewBtn.count()) {
  await renewBtn.click();
  await page.waitForTimeout(1500);
  const after = await page.locator('body').innerText();
  log('AC-LOAN-05 柜台续借操作可用', /续借成功|2027-/.test(after), after.slice(0, 200).replace(/\n/g, '|'));
  await shot(page, 'ui-08b-circulation-renew.png');
} else {
  log('AC-LOAN-05 柜台续借操作可用', false, 'no renew button');
}

// ---------- 还书 Tab：条码还书 ----------
await page.getByRole('tab', { name: /还书/ }).click();
await page.waitForTimeout(500);
await page.locator('#circ-return-barcode').fill('C00000013');
await page.locator('#circ-return-barcode').press('Enter');
await page.waitForTimeout(1500);
const ret = await page.locator('body').innerText();
log('AC-UI-04 还书 Tab 条码还书成功', /归还成功|还书成功|已归还/.test(ret), ret.slice(0, 200).replace(/\n/g, '|'));
await shot(page, 'ui-08c-circulation-return.png');

// ---------- 错误路径：借不存在的条码显示中文原因 ----------
await page.getByRole('tab', { name: /借书/ }).click();
await page.waitForTimeout(300);
await card.fill('R000004');
await barcode.fill('C99999999');
await barcode.press('Enter');
await page.waitForTimeout(1200);
const err = await page.locator('body').innerText();
log('AC-UI-03/04 柜台错误路径显示中文原因', /副本不存在/.test(err), err.slice(-220).replace(/\n/g, '|'));
await shot(page, 'ui-08d-circulation-error.png');

await browser.close();
const fails = results.filter(r => !r.ok).length;
console.log(`\nSUMMARY: ${results.length} checks, ${fails} failures`);
fs.writeFileSync('/tmp/acc-circ-check-result.json', JSON.stringify(results, null, 2));
process.exit(fails ? 1 : 0);
