// T12 端到端冒烟（DOD-05）：设计 §13 的 7 步冒烟流程。
// 每一步是一个独立用例：前置数据用带唯一后缀的名称通过 API 准备（helpers.ts），
// 不依赖执行顺序；被测行为本身全部走真实 UI（chromium headless）。
// 契约：solution-design.md §5/§7/§8/§9/§10/§11；AC/DoD v1（approval-record.md）。
import { test, expect } from '@playwright/test';
import {
  ADMIN_PASSWORD,
  ADMIN_USERNAME,
  READER_PASSWORD,
  addDays,
  apiGet,
  apiLogin,
  apiLogout,
  loginViaUi,
  seedBook,
  seedLoan,
  seedReader,
  shanghaiToday,
  submitLogin,
  uniqueIsbn13,
  uniqueSuffix,
} from './helpers';
import type { Copy } from '../src/api/types';

test('AC-AUTH-01 / DOD-05 步骤① 管理员登录→新建分类→新建图书(2副本)→新建读者，新读者可登录', async ({
  page,
}) => {
  test.setTimeout(90_000);
  const suffix = uniqueSuffix();
  const categoryName = `E2E分类-${suffix}`;
  const bookTitle = `E2E冒烟图书-${suffix}`;
  const readerName = `E2E读者-${suffix}`;
  const readerUsername = `e2e_${suffix}`;

  // 未登录访问受保护页 → 登录页（AC-AUTH-09 已由 login.spec.ts 覆盖跳转，这里走完整流程）。
  await page.goto('/');
  await expect(page).toHaveURL(/\/login\?redirect=/);
  await submitLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  await expect(page).toHaveURL(/\/admin\/dashboard/);
  await expect(page.getByRole('heading', { name: '仪表盘' })).toBeVisible();

  // 新建分类（AC-CAT-01 的 UI 冒烟）。
  await page.getByRole('menuitem', { name: '分类管理' }).click();
  await expect(page).toHaveURL(/\/admin\/categories/);
  await page.getByRole('button', { name: '新建分类' }).click();
  const categoryDialog = page.getByRole('dialog', { name: '新建分类' });
  await categoryDialog.getByLabel('分类名称').fill(categoryName);
  await categoryDialog.getByLabel('描述').fill('T12 冒烟流程创建的分类');
  await categoryDialog.getByRole('button', { name: /保\s*存/ }).click();
  await expect(page.getByText(`分类「${categoryName}」已创建`)).toBeVisible();
  await expect(page.getByRole('cell', { name: categoryName, exact: true })).toBeVisible();

  // 新建图书，初始 2 个副本（AC-BOOK-01 / AC-COPY-01 的 UI 冒烟）。
  await page.goto('/admin/books/new');
  await expect(page.getByRole('heading', { name: '新建图书' })).toBeVisible();
  await page.getByLabel('ISBN', { exact: true }).fill(uniqueIsbn13());
  await page.getByLabel('书名', { exact: true }).fill(bookTitle);
  await page.getByLabel('作者', { exact: true }).fill(`E2E作者-${suffix}`);
  await page.getByLabel('分类', { exact: true }).click();
  // antd v6 Select：可见下拉项是带 title 的 div（role=option 只存在于隐藏的 aria 列表），
  // 所以用 title 定位可见项，避免点击隐藏的无障碍节点导致超时。
  await page.getByTitle(categoryName, { exact: true }).click();
  await page.getByLabel('初始副本数量').fill('2');
  await page.getByRole('button', { name: '创建图书' }).click();
  await expect(page).toHaveURL(/\/admin\/books\/\d+\/edit/);
  // 编辑页副本管理面板应显示 2 个系统生成的条码 C%08d。
  const barcodes = page.locator('code').filter({ hasText: /^C\d{8}$/ });
  await expect(barcodes).toHaveCount(2);

  // 新建读者（AC-RDR-01 的 UI 冒烟）：同时创建账号，卡号系统生成。
  await page.goto('/admin/readers');
  await page.getByRole('button', { name: '新建读者' }).click();
  const readerDialog = page.getByRole('dialog', { name: '新建读者' });
  await readerDialog.getByLabel('姓名').fill(readerName);
  await readerDialog.getByLabel('用户名').fill(readerUsername);
  await readerDialog.getByLabel('初始口令').fill(READER_PASSWORD);
  await readerDialog.getByRole('button', { name: /创\s*建/ }).click();
  await expect(page.getByText(/读者已创建，卡号 R\d{6}/)).toBeVisible();
  await expect(page.getByRole('cell', { name: readerUsername, exact: true })).toBeVisible();

  // 新读者用初始口令登录（AC-AUTH-01 读者侧）：进入读者首页 /books，只看到读者菜单。
  // 注意（E-01，交 T13/评审裁量）：登出后 RequireAuth 会把上一页写入 ?redirect=，
  // 换角色登录会“回到原页面”后跳 403；这里显式打开干净的 /login，按读者正常入口登录。
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page).toHaveURL(/\/login/);
  await page.goto('/login');
  await submitLogin(page, readerUsername, READER_PASSWORD);
  await expect(page).toHaveURL(/\/books/);
  await expect(page.getByRole('menuitem', { name: '我的借阅' })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: '图书管理' })).toHaveCount(0);
});

test('AC-LOAN-01 / AC-UI-04 步骤② 流通柜台借书：显示应还日（Asia/Shanghai 借期30天），清空条码并保持焦点', async ({
  page,
}) => {
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  const { title, copies } = await seedBook(page, 2);
  const { reader } = await seedReader(page);
  const barcode = copies[0].barcode;

  await page.goto('/admin/circulation');
  await expect(page.getByRole('heading', { name: '流通柜台' })).toBeVisible();
  await page.getByLabel('读者卡号').fill(reader.cardNo);
  const barcodeInput = page.getByLabel('副本条码');
  await barcodeInput.fill(barcode);
  await barcodeInput.press('Enter'); // 回车即提交（设计 §10.3）

  // 成功结果区显示应还日 = 今天(Asia/Shanghai) + 30 天。
  const expectedDue = addDays(shanghaiToday(), 30);
  const result = page.locator('.ant-alert').filter({ hasText: '借出成功' });
  await expect(result).toBeVisible();
  await expect(result).toContainText(title);
  await expect(result).toContainText('应还日');
  await expect(result).toContainText(expectedDue);
  await expect(result).toContainText(shanghaiToday()); // 借阅日为当天

  // 条码输入框清空并保持焦点，方便连续扫码。
  await expect(barcodeInput).toHaveValue('');
  await expect(barcodeInput).toBeFocused();

  // 下方显示该读者当前在借列表（书名、条码、借阅日、应还日、状态）。
  const panel = page.getByRole('region', { name: '读者在借列表' });
  await expect(panel).toBeVisible();
  await expect(panel.getByText('在借 1 册')).toBeVisible();
  const row = panel.getByRole('row').filter({ hasText: title });
  await expect(row).toContainText(barcode);
  await expect(row).toContainText(expectedDue);
  await expect(row).toContainText('在借');
});

test('AC-SRCH-02 / AC-LOAN-07 / AC-AUTH-09 / AC-LOAN-05 步骤③ 读者登录→检索到该书→我的借阅可见→/admin/books 跳 403，且无续借按钮(D2)', async ({
  page,
}) => {
  // 前置：管理员建书（2 副本）、建读者并借出 1 册 → 可借 1 / 馆藏 2。
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  const { book, title, copies } = await seedBook(page, 2);
  const { reader, username } = await seedReader(page);
  const barcode = copies[0].barcode;
  const loan = await seedLoan(page, reader.cardNo, barcode);
  await apiLogout(page);

  // 读者登录 → READER 首页 /books，只有读者菜单（AC-AUTH-09 / AC-UI-05）。
  await loginViaUi(page, username, READER_PASSWORD);
  await expect(page).toHaveURL(/\/books/);
  await expect(page.getByRole('menuitem', { name: '我的借阅' })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: '流通柜台' })).toHaveCount(0);

  // 检索到该书；条件写入 URL，刷新后保留（AC-SRCH-02）。
  const keywordInput = page.getByLabel('检索关键词');
  await keywordInput.fill(title);
  await keywordInput.press('Enter');
  await expect(page).toHaveURL(/[?&]q=/);
  const resultRow = page.getByRole('row').filter({ hasText: title });
  await expect(resultRow).toBeVisible();
  await expect(resultRow).toContainText('1 / 2'); // 可借 1 / 馆藏 2（AC-BOOK-03）
  await page.reload();
  await expect(page.getByLabel('检索关键词')).toHaveValue(title);
  await expect(page.getByRole('row').filter({ hasText: title })).toBeVisible();

  // 图书详情：可借数量可见，读者看不到副本条码列表（AC-SRCH-02）。
  await page.getByRole('link', { name: title }).click();
  await expect(page).toHaveURL(new RegExp(`/books/${book.id}`));
  const detail = page.getByRole('region', { name: '图书详情' });
  await expect(detail).toContainText('现在可借');
  await expect(detail).toContainText('馆藏 2 册');
  await expect(page.getByText(barcode, { exact: true })).toHaveCount(0);

  // 我的借阅：当前借阅含该条记录，只含本人数据（AC-LOAN-07）。
  await page.goto('/my/loans');
  await expect(page.getByRole('heading', { name: '我的借阅' })).toBeVisible();
  const loanRow = page.getByRole('row').filter({ hasText: title });
  await expect(loanRow).toBeVisible();
  await expect(loanRow).toContainText(barcode);
  await expect(loanRow).toContainText(loan.borrowDate);
  await expect(loanRow).toContainText(loan.dueDate);
  await expect(loanRow).toContainText('在借');
  // D2=false：读者端不出现续借按钮（AC-LOAN-05 / AC-UI-05）。
  await expect(page.getByRole('button', { name: /续\s*借/ })).toHaveCount(0);
  // 历史 Tab 存在；该读者暂无历史借阅。
  await page.getByRole('tab', { name: '历史' }).click();
  await expect(page.getByText('暂无历史借阅记录')).toBeVisible();

  // 读者访问管理员路由 → 403 页（AC-AUTH-09）。
  await page.goto('/admin/books');
  await expect(page).toHaveURL(/\/403/);
  await expect(page.getByText('无权限访问该页面')).toBeVisible();
});

test('AC-LOAN-05 / AC-LOAN-04 / AC-UI-04 步骤④ 管理员柜台续借（应还日顺延30天、限1次）→还书（按期归还、副本恢复可借）', async ({
  page,
}) => {
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  const { book, title, copies } = await seedBook(page, 2);
  const { reader } = await seedReader(page);
  const barcode = copies[0].barcode;
  const loan = await seedLoan(page, reader.cardNo, barcode);

  await page.goto('/admin/circulation');
  // 只输入卡号回车 → 查看在借列表，不发起借书。
  const cardInput = page.getByLabel('读者卡号');
  await cardInput.fill(reader.cardNo);
  await cardInput.press('Enter');
  const panel = page.getByRole('region', { name: '读者在借列表' });
  const row = panel.getByRole('row').filter({ hasText: title });
  await expect(row).toContainText(loan.dueDate);
  await expect(row).toContainText('0/1'); // 续借次数 0/1

  // 管理员续借：应还日从原应还日顺延 30 天，renewCount 1/1（AC-LOAN-05）。
  const renewButton = row.getByRole('button', { name: /续\s*借/ });
  await expect(renewButton).toBeEnabled();
  await renewButton.click();
  const renewedDue = addDays(loan.dueDate, 30);
  const renewResult = page.locator('.ant-alert').filter({ hasText: '续借成功' });
  await expect(renewResult).toBeVisible();
  await expect(renewResult).toContainText(renewedDue);
  await expect(renewResult).toContainText('已续借 1/1 次');
  await expect(row).toContainText(renewedDue);
  // 达到续借上限后按钮不可用（RENEW_LIMIT_REACHED 的 UI 提示）。
  await expect(renewButton).toBeDisabled();

  // 还书 Tab：输入条码回车 → 归还成功、按期归还、归还日为当天（AC-LOAN-04 / AC-UI-04）。
  await page.getByRole('tab', { name: '还书' }).click();
  const returnInput = page.getByLabel('副本条码');
  await returnInput.fill(barcode);
  await returnInput.press('Enter');
  const returnResult = page.locator('.ant-alert').filter({ hasText: '归还成功' });
  await expect(returnResult).toBeVisible();
  await expect(returnResult).toContainText(title);
  await expect(returnResult).toContainText('按期归还');
  await expect(returnResult).toContainText(`归还日 ${shanghaiToday()}`);

  // 副本恢复 AVAILABLE（AC-LOAN-04 后端可观察结果）。
  const copiesAfter = await apiGet<Copy[]>(page, `/api/books/${book.id}/copies`);
  expect(copiesAfter.find((c) => c.barcode === barcode)?.status).toBe('AVAILABLE');
});

test('AC-STAT-04 / AC-UI-06 步骤⑤ 逾期报表包含演示数据中的 reader2（演示读者二，逾期≥10天）', async ({
  page,
}) => {
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  await page.goto('/admin/reports/overdue');
  await expect(page.getByRole('heading', { name: '逾期报表' })).toBeVisible();

  // 演示数据（设计 §11）：reader2 有 1 条逾期，借于 today-40、应还 today-10。
  const row = page.getByRole('row').filter({ hasText: '演示读者二' });
  await expect(row).toBeVisible();
  await expect(row).toContainText('R000002');
  const daysText = await row.getByText(/^\d+ 天$/).textContent();
  const days = Number((daysText ?? '').replace(/[^\d]/g, ''));
  expect(days, '演示逾期记录逾期天数应 ≥ 10').toBeGreaterThanOrEqual(10);
});

test('AC-UI-06 步骤⑥ 仪表盘与统计页正常加载：概览、借还趋势、热门图书、日/月粒度切换', async ({
  page,
}) => {
  await apiLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);

  // 仪表盘：概览数字卡片渲染。
  await page.goto('/admin/dashboard');
  await expect(page.getByRole('heading', { name: '仪表盘' })).toBeVisible();
  await expect(page.getByRole('list', { name: '馆藏概览' })).toBeVisible();
  await expect(page.getByRole('list', { name: '读者与流通概览' })).toBeVisible();

  // 统计页：默认最近 30 天，演示数据分布最近 60 天 → 趋势与热门图书非空。
  await page.goto('/admin/stats');
  await expect(page.getByRole('heading', { name: '借阅统计' })).toBeVisible();
  await expect(page.getByText('借出合计')).toBeVisible();
  await expect(page.getByText('归还合计')).toBeVisible();
  const topBooks = page.getByRole('list', { name: '热门图书榜' });
  await expect(topBooks).toBeVisible();
  expect(await topBooks.getByRole('listitem').count()).toBeGreaterThan(0);

  // 日/月粒度切换：条件写入 URL，页面仍正常渲染。
  await page.getByText('按月', { exact: true }).click();
  await expect(page).toHaveURL(/granularity=MONTH/);
  await expect(page.getByText('借出合计')).toBeVisible();
  await expect(page.getByRole('list', { name: '热门图书榜' })).toBeVisible();
});

test('AC-AUTH-07 / AC-AUTH-09 步骤⑦ 登出后会话失效，受保护页跳登录页，登录后回到原页面', async ({
  page,
}) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  await expect(page).toHaveURL(/\/admin\/dashboard/);

  // 登出 → 登录页；同一 Cookie 调 /api/auth/me 返回 401（AC-AUTH-07）。
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page).toHaveURL(/\/login/);
  const meRes = await page.request.get('/api/auth/me');
  expect(meRes.status()).toBe(401);
  expect((await meRes.json()).code).toBe('AUTH_REQUIRED');

  // 登出后访问受保护页 → /login?redirect=原路径（AC-AUTH-09）。
  await page.goto('/admin/books');
  await expect(page).toHaveURL(/\/login\?redirect=/);
  expect(page.url()).toContain(encodeURIComponent('/admin/books'));

  // 登录后回到原页面（redirect 只接受站内相对路径）。
  await submitLogin(page, ADMIN_USERNAME, ADMIN_PASSWORD);
  await expect(page).toHaveURL(/\/admin\/books/);
});