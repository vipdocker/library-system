// T12 E2E 共享辅助：
// - 通过 Vite 代理走同源 /api（page.request 与浏览器共享 Cookie），按契约携带 X-XSRF-TOKEN；
// - 用 API 准备各用例的前置数据（书目/副本/读者/在借记录），保证用例彼此独立、不依赖执行顺序；
// - 唯一后缀测试数据 + 合法 ISBN-13 生成 + Asia/Shanghai 业务日期工具。
// 契约来源：docs/design/solution-design.md §5/§7/§8/§9/§11。
import { expect, type Page } from '@playwright/test';
import type { BookDetail, Copy, Loan, Reader, UserInfo } from '../src/api/types';

export const ADMIN_USERNAME = 'admin';
export const ADMIN_PASSWORD = process.env.LIB_ADMIN_PASSWORD ?? 'Admin@12345';
// 满足口令策略：8–64 位，含字母和数字（设计 §7）。
export const READER_PASSWORD = 'Reader@1234';

let seq = 0;

/** 每次调用返回唯一后缀（时间戳 base36 + 自增序号），用于分类名/书名/读者名/用户名。 */
export function uniqueSuffix(): string {
  seq += 1;
  return `${Date.now().toString(36)}${seq}`;
}

/** 生成校验位合法的 ISBN-13（978 前缀 + 时间戳尾 8 位 + 随机 1 位 + 校验位），避免 ISBN_DUPLICATE。 */
export function uniqueIsbn13(): string {
  const base = `978${String(Date.now()).slice(-8)}${Math.floor(Math.random() * 10)}`;
  let sum = 0;
  for (let i = 0; i < 12; i += 1) {
    sum += Number(base[i]) * (i % 2 === 0 ? 1 : 3);
  }
  return `${base}${(10 - (sum % 10)) % 10}`;
}

/** Asia/Shanghai（UTC+8，无夏令时）的业务日期 YYYY-MM-DD。 */
export function shanghaiToday(): string {
  return new Date(Date.now() + 8 * 3_600_000).toISOString().slice(0, 10);
}

/** ISO 日期加 n 天（纯日历运算，不受本机时区影响）。 */
export function addDays(isoDate: string, days: number): string {
  const d = new Date(`${isoDate}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

async function freshCsrfToken(page: Page): Promise<string> {
  // GET /api/auth/csrf 让服务端下发 Token；登录/登出后 Token 会轮换，所以每次非 GET 前重新读取。
  const res = await page.request.get('/api/auth/csrf');
  expect(res.status(), 'GET /api/auth/csrf 应返回 204').toBe(204);
  const cookies = await page.context().cookies();
  const token = cookies.find((c) => c.name === 'XSRF-TOKEN')?.value;
  if (!token) throw new Error('未找到 XSRF-TOKEN Cookie');
  return token;
}

/** 带 CSRF 头的同源 POST；断言 2xx，204 时返回 undefined，否则解析 JSON。 */
export async function apiPost<T>(page: Page, path: string, data?: unknown): Promise<T> {
  const token = await freshCsrfToken(page);
  const res = await page.request.post(path, {
    headers: { 'X-XSRF-TOKEN': token },
    data,
  });
  expect(
    res.ok(),
    `POST ${path} 应成功，实际 ${res.status()}：${await res.text()}`,
  ).toBeTruthy();
  if (res.status() === 204) return undefined as T;
  return (await res.json()) as T;
}

export async function apiGet<T>(page: Page, path: string): Promise<T> {
  const res = await page.request.get(path);
  expect(res.ok(), `GET ${path} 应成功，实际 ${res.status()}`).toBeTruthy();
  return (await res.json()) as T;
}

export function apiLogin(page: Page, username: string, password: string): Promise<UserInfo> {
  return apiPost<UserInfo>(page, '/api/auth/login', { username, password });
}

export function apiLogout(page: Page): Promise<void> {
  return apiPost<void>(page, '/api/auth/logout');
}

export interface SeedBook {
  book: BookDetail;
  copies: Copy[];
  title: string;
}

/** API 建书（默认 2 个副本），返回书目与副本（条码 C%08d）。 */
export async function seedBook(page: Page, initialCopies = 2): Promise<SeedBook> {
  const suffix = uniqueSuffix();
  const title = `E2E冒烟图书-${suffix}`;
  const book = await apiPost<BookDetail>(page, '/api/books', {
    isbn: uniqueIsbn13(),
    title,
    author: `E2E作者-${suffix}`,
    initialCopies,
  });
  const copies = await apiGet<Copy[]>(page, `/api/books/${book.id}/copies`);
  expect(copies.length, '初始副本数量应正确').toBe(initialCopies);
  return { book, copies, title };
}

export interface SeedReader {
  reader: Reader;
  username: string;
}

/** API 建读者（同时创建 READER 账号，卡号 R%06d 由系统生成）。 */
export async function seedReader(page: Page): Promise<SeedReader> {
  const suffix = uniqueSuffix();
  const username = `e2e_${suffix}`;
  const reader = await apiPost<Reader>(page, '/api/readers', {
    name: `E2E读者-${suffix}`,
    username,
    initialPassword: READER_PASSWORD,
  });
  expect(reader.cardNo).toMatch(/^R\d{6}$/);
  return { reader, username };
}

/** API 借书，返回 201 的 Loan。 */
export function seedLoan(page: Page, cardNo: string, copyBarcode: string): Promise<Loan> {
  return apiPost<Loan>(page, '/api/loans', { readerCardNo: cardNo, copyBarcode });
}

/** 在当前已打开的 /login 页面上填写凭据并提交（不重置 URL，保留 redirect 参数）。 */
export async function submitLogin(page: Page, username: string, password: string): Promise<void> {
  await page.getByLabel('用户名').fill(username);
  await page.getByLabel('密码').fill(password);
  // antd Button 会在两个汉字之间插入空格（“登 录”）。
  await page.getByRole('button', { name: /登\s*录/ }).click();
}

/** 打开登录页并用 UI 登录。 */
export async function loginViaUi(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login');
  await submitLogin(page, username, password);
}