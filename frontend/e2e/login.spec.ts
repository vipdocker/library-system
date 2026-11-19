import { test, expect } from '@playwright/test';

// T02 登录冒烟骨架；设计第 13 节的 7 步完整流程由 T12 实现。
test('AC-AUTH-01 / AC-AUTH-09 管理员登录冒烟：未登录跳转登录页，登录后进入仪表盘', async ({
  page,
}) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/login\?redirect=/);

  await page.getByLabel('用户名').fill('admin');
  await page.getByLabel('密码').fill(process.env.LIB_ADMIN_PASSWORD ?? 'Admin@12345');
  await page.getByRole('button', { name: /登\s*录/ }).click();

  await expect(page).toHaveURL(/\/admin\/dashboard/);
  await expect(page.getByRole('heading', { name: '仪表盘' })).toBeVisible();
});