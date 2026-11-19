// acc-error-state-check.mjs — AC-UI-01 错误态：后端不可达时的前端表现
import { chromium } from '/tmp/acc-clone.yYiWjW/frontend/node_modules/playwright-core/index.mjs';
import fs from 'node:fs';

const OUT = '/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/acceptance/screenshots';
const browser = await chromium.launch({
  headless: true,
  executablePath: `${process.env.HOME}/Library/Caches/ms-playwright/chromium-1254/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing`,
});
const page = await (await browser.newContext({ viewport: { width: 1366, height: 900 } })).newPage();
// 后端未启动，仅前端（脚本单独启动 vite）
await page.goto('http://localhost:15195/login');
await page.getByLabel('用户名').fill('admin');
await page.getByLabel('密码').fill('Admin@12345');
await page.getByRole('button', { name: /登\s*录/ }).click();
await page.waitForTimeout(4000);
const body = await page.locator('body').innerText();
const hasRetry = /重试/.test(body);
const busyOrError = /系统繁忙|失败|错误|无法连接|Network|网络/.test(body);
console.log(`PASS-CHECK | AC-UI-01 后端不可达时有错误提示与重试 | retry=${hasRetry} err=${busyOrError}`);
console.log(body.slice(0, 300).replace(/\n/g, '|'));
await page.screenshot({ path: `${OUT}/ui-12-backend-down-error-state.png` });
await browser.close();
process.exit(hasRetry && busyOrError ? 0 : 1);
