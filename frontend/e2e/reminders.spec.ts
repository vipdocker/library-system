// REM-E2E, reminder-plan-v1.5 / AC-DoD v1.1. Real HTTP/browser, no API mocks.
// Playwright sets PT1S only for its backend webServer (production remains PT1H).
// Standard entry, no manual scan parameter: /opt/homebrew/bin/npm --prefix frontend run e2e
// Only newly API-created loans in the live E2E temporary DB receive fixture dates.
// Never modify/return reader2's overdue seed, or insert notices/records directly.
import { expect, test, type Page, type TestInfo, type APIResponse, type Response } from '@playwright/test';
import type {
  DunningCase, DunningCaseSummary, DunningMethod, DunningRecord, Loan,
  Notification, Page as ApiPage,
} from '../src/api/types';
import {
  ADMIN_PASSWORD, ADMIN_USERNAME, READER_PASSWORD, addDays,
  apiGet, apiLogin, apiLogout, apiPost, loginViaUi, reminderFixtureReturnedAt,
  reminderPostResponse, seedBook, seedLoan, seedReader, seedReminderLoan, seedReminderLoanGroup,
  setReminderFixtureDueDate, shanghaiToday, type ReminderLoanFixture,
} from './helpers';

let pageErrors: string[] = [];
function watchPage(page: Page) { page.on('pageerror', failure => pageErrors.push(failure.message)); }
test.beforeEach(async ({ page }) => { pageErrors = []; watchPage(page); });
test.afterEach(async ({ context }, info) => {
  await info.attach('pageerrors', { body: JSON.stringify({ errors: pageErrors, openPages: context.pages().length }), contentType: 'application/json' });
  expect(pageErrors, 'No unhandled browser errors in any test page').toEqual([]);
});

const methods: { value: DunningMethod; label: string }[] = [
  { value: 'PHONE', label: '电话' }, { value: 'IN_PERSON', label: '当面' },
  { value: 'IN_APP', label: '站内' }, { value: 'OTHER', label: '其他' },
];
const notices = (page: Page) => apiGet<ApiPage<Notification>>(page, '/api/notifications/me?size=100');
const history = (page: Page, id: number) => apiGet<DunningCase>(page, `/api/loans/${id}/dunning-records`);
const unread = async (page: Page) => (await apiGet<{ unreadCount: number }>(page, '/api/notifications/me/unread-count')).unreadCount;
const badge = (page: Page, count: number) => page.getByLabel(`未读提醒 ${count} 条`, { exact: true });

async function waitForNotice(page: Page, id: number, type: Notification['type']): Promise<Notification> {
  await expect.poll(async () => (await notices(page)).items.filter(item => item.loanId === id && item.type === type).length,
    { timeout: 15_000, message: `Real scheduler must generate exactly one ${type} for loan ${id}` }).toBe(1);
  return (await notices(page)).items.find(item => item.loanId === id && item.type === type)!;
}

/** A bounded observation window spanning several PT1S scans (not a mock clock).
 * Check every sampled result, including early violations; never hide a transient
 * duplicate behind an eventual-success assertion. Also used for no-UI-polling.
 */
async function observeScans(assertState: () => Promise<void>): Promise<void> {
  const until = Date.now() + 2_200;
  while (Date.now() < until) {
    await assertState();
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  await assertState();
}

async function attachFixture(info: TestInfo, fixture: ReminderLoanFixture) {
  await info.attach(`fixture-${fixture.loan.id}`, { body: JSON.stringify({
    dbPath: fixture.dbPath, loanId: fixture.loan.id, readerId: fixture.reader.id,
    barcode: fixture.copies[0].barcode, borrowDate: fixture.loan.borrowDate,
    dueDate: fixture.loan.dueDate, dateSource: 'Asia/Shanghai / isolated loan only',
  }, null, 2), contentType: 'application/json' });
}

async function screenshot(page: Page, info: TestInfo, name: string) {
  const file = info.outputPath(`${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  await info.attach(name, { path: file, contentType: 'image/png' });
}

async function error(response: APIResponse, status: number, code: string) {
  expect(response.status()).toBe(status);
  const body = await response.json();
  expect(body.code).toBe(code);
  expect(body.status).toBe(status);
}

function descending<T>(items: T[], timestamp: (item: T) => string, id: (item: T) => number) {
  expect(items.map(id)).toEqual([...items].sort((a, b) =>
    Date.parse(timestamp(b)) - Date.parse(timestamp(a)) || id(b) - id(a)).map(id));
}

test('AC-REM-01 / AC-REM-04 / AC-REM-05 D-4不发、D-3及停机D-2补发；续借保留未读历史、归还停止扫描', async ({ page }, info) => {
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  const fixture = await seedReminderLoan(page, 4);
  await attachFixture(info, fixture);
  await apiLogout(page);
  await apiLogin(page, fixture.username, READER_PASSWORD);
  await observeScans(async () => expect((await notices(page)).totalItems).toBe(0));
  setReminderFixtureDueDate(fixture, addDays(shanghaiToday(), 3));
  const oldDue = fixture.loan.dueDate;
  const notice = await waitForNotice(page, fixture.loan.id, 'DUE_SOON');
  expect(notice.content).toContain(fixture.title);
  expect(notice.content).toContain(fixture.copies[0].barcode);
  expect(notice.content).toContain(oldDue);
  expect(notice.overdueDays).toBeNull();
  expect(notice.seq).toBeNull();
  await observeScans(async () => expect((await notices(page)).totalItems).toBe(1));
  await page.goto('/my/notifications');
  const article = page.getByRole('article').filter({ hasText: fixture.title });
  await expect(article).toContainText('到期提醒');
  await screenshot(page, info, 'due-soon');

  await apiLogout(page);
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  const renewed = await apiPost<Loan>(page, `/api/loans/${fixture.loan.id}/renew`);
  expect(renewed.dueDate).toBe(addDays(oldDue, 30));
  expect(renewed.renewCount).toBe(1);
  await apiLogout(page);
  await apiLogin(page, fixture.username, READER_PASSWORD);
  await page.reload();
  await expect(article).toContainText(`当前应还日：${renewed.dueDate}`);
  await expect(article).toContainText('应还日已调整');
  await expect(article).toContainText(`生成时应还日：${oldDue}`);
  expect((await notices(page)).items[0]).toMatchObject({ id: notice.id, readAt: null, loanState: { currentDueDate: renewed.dueDate } });
  await screenshot(page, info, 'renewed-historical-notice');

  await apiLogout(page);
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  await apiPost<Loan>(page, `/api/loans/${fixture.loan.id}/return`);
  const missed = await seedReminderLoan(page, 2);
  await attachFixture(info, missed);
  await apiLogout(page);
  await apiLogin(page, fixture.username, READER_PASSWORD);
  await observeScans(async () => expect((await notices(page)).items).toMatchObject([{ id: notice.id, readAt: null, loanState: { status: 'RETURNED' } }]));
  await page.reload();
  await expect(article).toContainText('该借阅已归还，无需再还书');
  await screenshot(page, info, 'returned-historical-notice');
  await apiLogout(page);
  await apiLogin(page, missed.username, READER_PASSWORD);
  await waitForNotice(page, missed.loan.id, 'DUE_SOON');
  await observeScans(async () => expect((await notices(page)).totalItems).toBe(1));
});

test('AC-REM-01 / AC-REM-02 / AC-REM-03 D当天不逾期，1/8/15天与恢复第10天只生成当前周期且重复扫描不重复', async ({ page }, info) => {
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  const offsets = [0, -1, -8, -15, -10];
  const fixtures: ReminderLoanFixture[] = [];
  for (const days of offsets) {
    const fixture = await seedReminderLoan(page, days);
    fixtures.push(fixture);
    await attachFixture(info, fixture);
  }
  await apiLogout(page);
  for (let index = 0; index < fixtures.length; index += 1) {
    const fixture = fixtures[index];
    await apiLogin(page, fixture.username, READER_PASSWORD);
    const type = index === 0 ? 'DUE_SOON' : 'OVERDUE';
    const notice = await waitForNotice(page, fixture.loan.id, type);
    expect(notice.seq).toBe(index === 0 ? null : [0, 1, 2, 3, 2][index]);
    expect(notice.overdueDays).toBe(index === 0 ? null : -offsets[index]);
    await observeScans(async () => {
      const result = await notices(page);
      expect(result.totalItems).toBe(1);
      expect(result.items[0].id).toBe(notice.id);
      // D+1 and later must never backfill DUE_SOON or previous overdue periods.
      expect(result.items[0].type).toBe(type);
    });
    await page.goto('/my/notifications');
    await expect(page.getByRole('article')).toContainText(index === 0 ? '到期提醒' : '逾期提醒');
    if (index === 4) await screenshot(page, info, 'overdue-current-period-only');
    await apiLogout(page);
  }
});

test('AC-DUN-01 / AC-DUN-04 / AC-NTF-01 / AC-NTF-03 四种催还经办记录、私密备注不外泄、收件箱分页与已读角标', async ({ page, browser }, info) => {
  test.setTimeout(120_000);
  const admin = await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  const fixture = await seedReminderLoan(page, -10);
  await attachFixture(info, fixture);
  const privateNote = '仅管理员可见-隐私回归-'.padEnd(500, '密'); // exactly 500 Chinese characters
  expect(privateNote.length).toBe(500);
  const records: DunningRecord[] = [];
  for (let index = 0; index < methods.length; index += 1) {
    await page.goto('/admin/reports/overdue');
    await page.getByRole('row').filter({ hasText: fixture.copies[0].barcode }).getByRole('button', { name: '登记催还' }).click();
    const dialog = page.getByRole('dialog', { name: '登记催还', exact: true });
    await expect(dialog).toContainText('系统不会发送电话、短信或邮件');
    await dialog.getByRole('radio', { name: methods[index].label, exact: true }).check();
    await dialog.getByLabel('管理员备注').fill(index === 0 ? privateNote : index === 1 ? '  ' : `记录-${index}`);
    const received = page.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith(`/api/loans/${fixture.loan.id}/dunning-records`));
    await dialog.getByRole('button', { name: '确认登记' }).click();
    const response = await received;
    expect(response.status()).toBe(201);
    const record: DunningRecord = await response.json();
    expect(record).toMatchObject({ loanId: fixture.loan.id, method: methods[index].value, operator: { id: admin.id, username: ADMIN_USERNAME } });
    expect(record.note).toBe(index === 0 ? privateNote : index === 1 ? null : `记录-${index}`);
    records.push(record);
    await expect(dialog).toBeHidden();
    await expect(page.getByText('已登记催还并生成站内提醒，不代表已送达')).toBeVisible();
  }
  // Real registrations prepare enough messages to cross the UI's fixed 20-row page.
  for (let index = 0; index < 17; index += 1) {
    records.push(await apiPost<DunningRecord>(page, `/api/loans/${fixture.loan.id}/dunning-records`, { method: 'IN_APP' }));
  }
  const result = await history(page, fixture.loan.id);
  expect(result.status).toBe('OPEN');
  expect(result.records.map(record => record.id)).toEqual(records.map(record => record.id));
  expect(result.records).toEqual([...result.records].sort((a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt) || a.id - b.id));
  await page.goto('/admin/dunning');
  await page.getByRole('article').filter({ hasText: fixture.copies[0].barcode }).getByRole('button', { name: '查看历史' }).click();
  const historyDialog = page.getByRole('dialog', { name: `催还历史 · 借阅 ${fixture.loan.id}` });
  await expect(historyDialog.getByRole('listitem')).toHaveCount(21);
  await expect(historyDialog).toContainText(privateNote);
  await expect(historyDialog).toContainText('经办人：admin');
  await screenshot(page, info, 'admin-dunning-private-history');

  const owner = await browser.newPage();
  const stranger = await browser.newPage();
  watchPage(owner); watchPage(stranger);
  try {
    await loginViaUi(owner, fixture.username, READER_PASSWORD);
    await expect(owner).toHaveURL(/\/books$/);
    await waitForNotice(owner, fixture.loan.id, 'OVERDUE');
    const all = await notices(owner);
    expect(all.totalItems).toBe(22);
    descending(all.items, item => item.createdAt, item => item.id);
    const manual = all.items.filter(item => item.type === 'DUNNING');
    expect(manual).toHaveLength(21);
    expect(manual.map(item => item.id).sort((a, b) => a - b)).toEqual(records.map(record => record.notificationId).sort((a, b) => a - b));
    const expectedContent = `您借阅的《${fixture.title}》（条码：${fixture.copies[0].barcode}），应还日为 ${fixture.loan.dueDate}，图书馆提醒您及时归还。`;
    for (const item of manual) {
      expect(item.title).toBe('图书催还提醒');
      expect(item.content).toBe(expectedContent);
      expect(item).not.toHaveProperty('note');
      expect(item.content).not.toContain(privateNote);
    }
    expect(await unread(owner)).toBe(22);
    await expect(badge(owner, 22)).toBeVisible();
    await owner.getByRole('menuitem', { name: /我的提醒/ }).click();
    await expect(owner.getByRole('article')).toHaveCount(20);
    await expect(owner.getByText('站内提醒仅在登录系统后可查看')).toBeVisible();
    await expect(owner.getByText('已读仅表示您在系统中标记过，不表示短信、邮件或电话已送达。')).toBeVisible();
    await expect(owner.locator('body')).not.toContainText(privateNote);
    const first = await apiGet<ApiPage<Notification>>(owner, '/api/notifications/me?page=0&size=20');
    const second = await apiGet<ApiPage<Notification>>(owner, '/api/notifications/me?page=1&size=20');
    expect([...first.items, ...second.items].map(item => item.id)).toEqual(all.items.map(item => item.id));
    await owner.getByTitle('2', { exact: true }).click();
    await expect(owner.getByRole('article')).toHaveCount(2);
    expect(await owner.getByRole('article').locator('time').evaluateAll(elements => elements.map(element => element.getAttribute('datetime')))).toEqual(second.items.map(item => item.createdAt));
    await screenshot(owner, info, 'reader-inbox-page-two');
    await owner.getByRole('checkbox', { name: '仅看未读' }).check();
    await expect(owner.getByRole('article')).toHaveCount(20);
    const firstId = all.items[0].id;
    const marked = owner.waitForResponse(response => response.request().method() === 'POST' && response.url().endsWith(`/api/notifications/me/${firstId}/read`));
    await owner.getByRole('article').first().getByRole('button', { name: '标为已读', exact: true }).click();
    expect((await marked).status()).toBe(204);
    await expect(badge(owner, 21)).toBeVisible();
    expect((await apiGet<ApiPage<Notification>>(owner, '/api/notifications/me?unreadOnly=true&size=100')).items.map(item => item.id)).not.toContain(firstId);
    const readAt = (await notices(owner)).items.find(item => item.id === firstId)!.readAt;
    expect(readAt).not.toBeNull();
    await apiPost<void>(owner, `/api/notifications/me/${firstId}/read`);
    expect((await notices(owner)).items.find(item => item.id === firstId)!.readAt).toBe(readAt);
    expect(await unread(owner)).toBe(21);
    await owner.getByRole('button', { name: '全部标为已读' }).click();
    await expect(owner.getByText('暂无未读提醒')).toBeVisible();
    await expect(badge(owner, 0)).toBeVisible();
    expect(await unread(owner)).toBe(0);
    expect((await notices(owner)).items.every(item => item.readAt !== null)).toBe(true);
    expect(await apiPost<{ updated: number }>(owner, '/api/notifications/me/read-all')).toEqual({ updated: 0 });
    await owner.getByRole('checkbox', { name: '仅看未读' }).uncheck();
    await expect(owner.getByRole('article')).toHaveCount(20);
    await screenshot(owner, info, 'reader-all-read-retained');
    let uiReads = 0;
    const onRequest = (request: { url(): string }) => { if (request.url().includes('/api/notifications/')) uiReads += 1; };
    owner.on('request', onRequest);
    await owner.waitForLoadState('networkidle');
    const initialReads = uiReads;
    // No test API requests in this window: exclusively observe browser traffic.
    await observeScans(async () => expect(uiReads).toBe(initialReads));
    owner.off('request', onRequest);
    await owner.getByRole('menuitem', { name: '我的资料' }).click();
    await expect(owner.getByRole('heading', { name: '我的资料' })).toBeVisible();
    await expect(badge(owner, 0)).toBeVisible();

    await loginViaUi(stranger, 'reader1', 'Reader@123');
    await expect(stranger).toHaveURL(/\/books$/);
    const unrelated = await notices(stranger);
    expect(unrelated.items.every(item => item.loanId !== fixture.loan.id)).toBe(true);
    await stranger.goto('/my/notifications');
    await expect(stranger.locator('body')).not.toContainText(fixture.title);
    await expect(stranger.getByRole('menuitem', { name: '催还记录' })).toHaveCount(0);
    await stranger.goto('/admin/dunning');
    await expect(stranger).toHaveURL(/\/403/);
    await expect(stranger.getByText('无权限访问该页面')).toBeVisible();
  } finally {
    await owner.close();
    await stranger.close();
  }
});

test('AC-NTF-02 / AC-DUN-02 身份隔离、CSRF、未逾期与非法登记均无副作用', async ({ page, browser }, info) => {
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  const fixture = await seedReminderLoan(page, -1);
  const currentReader = await seedReader(page);
  const currentBook = await seedBook(page, 1);
  const current = await seedLoan(page, currentReader.reader.cardNo, currentBook.copies[0].barcode);
  await attachFixture(info, fixture);
  const record = await apiPost<DunningRecord>(page, `/api/loans/${fixture.loan.id}/dunning-records`, { method: 'PHONE', note: '管理员隔离备注' });
  const owner = await browser.newPage();
  const other = await browser.newPage();
  const anonymous = await browser.newPage();
  watchPage(owner); watchPage(other); watchPage(anonymous);
  try {
    await apiLogin(owner, fixture.username, READER_PASSWORD);
    await waitForNotice(owner, fixture.loan.id, 'OVERDUE');
    const before = await notices(owner);
    const beforeHistory = await history(page, fixture.loan.id);
    await apiLogin(other, currentReader.username, READER_PASSWORD);
    await error(await other.request.get('/api/dunning-cases'), 403, 'FORBIDDEN');
    await error(await other.request.get(`/api/loans/${fixture.loan.id}/dunning-records`), 403, 'FORBIDDEN');
    await error(await reminderPostResponse(other, `/api/loans/${fixture.loan.id}/dunning-records`, { method: 'PHONE' }), 403, 'FORBIDDEN');
    await error(await reminderPostResponse(other, `/api/notifications/me/${record.notificationId}/read`), 404, 'NOTIFICATION_NOT_FOUND');
    const injected = await apiGet<ApiPage<Notification>>(other, `/api/notifications/me?readerId=${fixture.reader.id}`);
    expect(injected.totalItems).toBe(0);
    for (const csrf of ['missing', 'wrong'] as const) {
      await error(await reminderPostResponse(owner, `/api/notifications/me/${record.notificationId}/read`, undefined, csrf), 403, 'CSRF_INVALID');
      await error(await reminderPostResponse(owner, '/api/notifications/me/read-all', undefined, csrf), 403, 'CSRF_INVALID');
      await error(await reminderPostResponse(page, `/api/loans/${fixture.loan.id}/dunning-records`, { method: 'OTHER' }, csrf), 403, 'CSRF_INVALID');
    }
    await error(await page.request.get('/api/notifications/me'), 403, 'FORBIDDEN');
    await error(await page.request.get('/api/notifications/me/unread-count'), 403, 'FORBIDDEN');
    await error(await reminderPostResponse(page, '/api/notifications/me/read-all'), 403, 'FORBIDDEN');
    for (const url of ['/api/notifications/me', '/api/notifications/me/unread-count', '/api/dunning-cases']) {
      await error(await anonymous.request.get(url), 401, 'AUTH_REQUIRED');
    }
    const badInputs = [{ method: 'SMS' }, { method: 'OTHER', note: '密'.repeat(501) }, {}];
    for (const input of badInputs) await error(await reminderPostResponse(page, `/api/loans/${fixture.loan.id}/dunning-records`, input), 400, 'VALIDATION_FAILED');
    await error(await reminderPostResponse(page, '/api/loans/2147483647/dunning-records', { method: 'PHONE' }), 404, 'LOAN_NOT_FOUND');
    await error(await reminderPostResponse(page, `/api/loans/${current.id}/dunning-records`, { method: 'PHONE' }), 409, 'LOAN_NOT_OVERDUE');
    expect((await history(page, current.id)).records).toEqual([]);
    expect(await history(page, fixture.loan.id)).toEqual(beforeHistory);
    expect(await notices(owner)).toEqual(before);
    expect(await unread(owner)).toBe(before.totalItems);
    expect((await notices(other)).totalItems).toBe(0);

    // ADMIN layout itself must not subscribe to READER notification APIs.
    const browserCalls: string[] = [];
    page.on('request', request => { if (request.url().includes('/api/notifications/')) browserCalls.push(request.url()); });
    await page.goto('/admin/dunning');
    await expect(page.getByRole('heading', { name: '催还记录' })).toBeVisible();
    await page.waitForLoadState('networkidle');
    expect(browserCalls).toEqual([]);
    await page.goto('/my/notifications');
    await expect(page).toHaveURL(/\/403/);
    expect(browserCalls).toEqual([]);
    await screenshot(page, info, 'admin-inbox-forbidden');
  } finally {
    await owner.close(); await other.close(); await anonymous.close();
  }
});

test('AC-DUN-03 / AC-DUN-04 / AC-REM-05 借阅号和条码归还自动结案，历史保留、OPEN/CLOSED分页稳定', async ({ page }, info) => {
  test.setTimeout(120_000);
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  // Eleven cases cross the ten-item UI page, but only FOUR new readers are
  // needed; the two return fixtures still have independent reader inboxes.
  // Preserve the original smoke's first-page reader creation assertion.
  const fixtures: ReminderLoanFixture[] = [];
  for (const count of [1, 1, 5, 4]) fixtures.push(...await seedReminderLoanGroup(page, count, -1));
  for (const [index, fixture] of fixtures.entries()) {
    await attachFixture(info, fixture);
    await apiPost<DunningRecord>(page, `/api/loans/${fixture.loan.id}/dunning-records`, { method: 'IN_APP', note: `结案回归-${index}` });
  }
  for (let index = 0; index < 2; index += 1) {
    const fixture = fixtures[index];
    const before = await history(page, fixture.loan.id);
    let response: Response;
    if (index === 0) {
      await page.goto('/admin/loans');
      await page.getByLabel('副本条码').fill(fixture.copies[0].barcode);
      await page.getByLabel('副本条码').press('Enter');
      await page.getByRole('row').filter({ hasText: fixture.title }).getByRole('button', { name: /还\s*书/ }).click();
      const returned = page.waitForResponse(res => res.request().method() === 'POST' && res.url().endsWith(`/api/loans/${fixture.loan.id}/return`));
      await page.getByRole('tooltip').getByRole('button', { name: /还\s*书/ }).click();
      response = await returned;
    } else {
      await page.goto('/admin/circulation');
      await page.getByRole('tab', { name: '还书' }).click();
      const returned = page.waitForResponse(res => res.request().method() === 'POST' && res.url().endsWith('/api/loans/return-by-barcode'));
      await page.getByLabel('副本条码').fill(fixture.copies[0].barcode);
      await page.getByLabel('副本条码').press('Enter');
      response = await returned;
    }
    expect(response.status()).toBe(200);
    expect(await response.json()).toMatchObject({ id: fixture.loan.id, status: 'RETURNED', returnDate: shanghaiToday(), overdueDays: 1 });
    const closed = await history(page, fixture.loan.id);
    expect(closed).toMatchObject({ status: 'CLOSED', closedAt: reminderFixtureReturnedAt(fixture), closeReason: 'RETURNED' });
    expect(closed.records).toEqual(before.records);
    await error(await reminderPostResponse(page, `/api/loans/${fixture.loan.id}/dunning-records`, { method: 'PHONE' }), 409, 'LOAN_ALREADY_RETURNED');
    expect(await history(page, fixture.loan.id)).toEqual(closed);
  }
  const all = await apiGet<ApiPage<DunningCaseSummary>>(page, '/api/dunning-cases?size=100');
  descending(all.items, item => item.lastRecordedAt, item => item.loanId);
  for (const status of ['OPEN', 'CLOSED']) {
    const filtered = await apiGet<ApiPage<DunningCaseSummary>>(page, `/api/dunning-cases?status=${status}&size=100`);
    expect(filtered.items.map(item => item.loanId)).toEqual(all.items.filter(item => item.status === status).map(item => item.loanId));
    for (let index = 0; index < Math.min(2, filtered.totalItems); index += 1) {
      const one = await apiGet<ApiPage<DunningCaseSummary>>(page, `/api/dunning-cases?status=${status}&page=${index}&size=1`);
      expect(one).toMatchObject({ page: index, size: 1, totalItems: filtered.totalItems, totalPages: filtered.totalItems });
      expect(one.items).toEqual([filtered.items[index]]);
    }
  }
  await page.goto('/admin/dunning');
  await expect(page.getByRole('article')).toHaveCount(Math.min(20, all.totalItems));
  expect(await page.getByRole('article').locator('h4').allTextContents()).toEqual(all.items.slice(0, 20).map(item => item.book.title));
  await page.getByRole('combobox').click();
  await page.getByTitle('10 条/页', { exact: true }).click();
  await expect(page.getByRole('article')).toHaveCount(10);
  await page.getByTitle('2', { exact: true }).click();
  await expect(page.getByRole('article')).toHaveCount(Math.min(10, all.totalItems - 10));
  expect(await page.getByRole('article').locator('h4').allTextContents()).toEqual(all.items.slice(10, 20).map(item => item.book.title));
  await screenshot(page, info, 'case-page-two');
  await page.getByRole('radio', { name: '仅已结案' }).check();
  await expect(page.getByRole('article')).toHaveCount(2);
  const closedArticle = page.getByRole('article').filter({ hasText: fixtures[0].copies[0].barcode });
  await expect(closedArticle).toContainText('结案原因：已归还');
  await closedArticle.getByRole('button', { name: '查看历史' }).click();
  const dialog = page.getByRole('dialog', { name: `催还历史 · 借阅 ${fixtures[0].loan.id}` });
  await expect(dialog).toContainText('已结案');
  await expect(dialog).toContainText('结案原因：已归还');
  await expect(dialog.getByRole('listitem')).toHaveCount(1);
  await screenshot(page, info, 'closed-history-by-loan-id');
  await dialog.getByRole('button', { name: '关闭历史' }).click();
  await page.getByRole('radio', { name: '仅未结案' }).check();
  await expect(page.getByRole('article').filter({ hasText: fixtures[0].title })).toHaveCount(0);
  await expect(page.getByRole('article').filter({ hasText: fixtures[1].title })).toHaveCount(0);
  await apiLogout(page);
  for (const fixture of fixtures.slice(0, 2)) {
    await apiLogin(page, fixture.username, READER_PASSWORD);
    const before = await notices(page);
    await observeScans(async () => expect(await notices(page)).toEqual(before));
    expect(before.items.every(item => item.loanState.status === 'RETURNED')).toBe(true);
    await page.goto('/my/notifications');
    await expect(page.getByRole('article').first()).toContainText('该借阅已归还，无需再还书');
    await screenshot(page, info, `closed-reader-${fixture.loan.id}`);
    await apiLogout(page);
  }
});
