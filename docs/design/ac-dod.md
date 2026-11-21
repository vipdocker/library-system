# 图书馆管理系统首版：AC / DoD 草案

| 项 | 值 |
|---|---|
| 定义版本 | **AC/DoD v0.1（草案，未批准）**。经主会话交用户确认后才成为 v1。 |
| 对应设计 | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/solution-design.md`（design-v0.1） |
| 对应计划 | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/implementation-plan.md`（plan-v1） |
| 阈值说明 | 文中数值为推荐默认值（D1：上限 5、借期 30 天、续借 1 次且顺延 30 天）。用户选择其他值时同步替换。标注〔D2〕〔D4①②③〕的条目，其适用性取决于对应决定。 |

**核验阶段**：IMPL（开发实现环节自检，提供自动化测试证据）、REVIEW（独立评审）、ACCEPT（独立验收，在本机实际运行并操作）。
**环节**：DEV = 开发实现（Qwen4-Max），REV = 独立评审（Qwen4-Max），ACC = 独立验收（GLM-5.3）。
**结果取值**：PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE / NOT_DUE。

---

## 1. 验收标准（AC）

每条的格式为：编号｜可观察条件｜适用范围｜必需｜核验阶段｜执行/核验环节｜验证方法｜证据要求。

### 1.1 认证与权限（AUTH）

| 编号 | 可观察条件 | 范围 | 必需 | 阶段 | 执行/核验 | 验证方法 | 证据 |
|---|---|---|---|---|---|---|---|
| AC-AUTH-01 | 管理员和读者用正确凭据调用 `POST /api/auth/login` 得到 200 和 `UserInfo`，其中 role 正确，读者带 readerId 和 cardNo。响应设置 `JSESSIONID`（HttpOnly、SameSite=Lax）。随后 `GET /api/auth/me` 返回同一用户。 | 后端 + 前端登录页 | 是 | IMPL / ACCEPT | DEV / ACC | MockMvc 集成测试；E2E 登录；验收时手工登录两个角色 | 测试名及结果；验收截图或操作记录 |
| AC-AUTH-02 | 用户名不存在或口令错误时返回 401 `AUTH_INVALID_CREDENTIALS`，两种情况的 message 相同。已停用账号用正确口令登录返回 401 `AUTH_ACCOUNT_DISABLED`。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | 集成测试 | 测试结果 |
| AC-AUTH-03 | 未登录访问除 `/api/auth/login`、`/api/auth/csrf` 以外的任何 `/api/**`，返回 401 JSON `AUTH_REQUIRED`，不发生 302 重定向。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | 参数化授权矩阵测试，覆盖每个控制器至少 1 个端点 | 测试结果 |
| AC-AUTH-04 | READER 访问管理员端点（分类/图书/副本写操作、`/api/books/{id}/copies`、`/api/copies/**`、`/api/readers/**`（`/me` 除外）、`/api/loans`（GET/POST）、还书、`/api/stats/**`、`/api/reports/**`）返回 403 `FORBIDDEN`；ADMIN 访问 `/api/readers/me`、`/api/loans/me` 返回 403。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | 参数化授权矩阵测试，与设计第 7 节矩阵逐行对应 | 测试结果；评审对照矩阵 |
| AC-AUTH-05 | 读者只能读取本人数据：`/api/readers/me`、`/api/loans/me` 只返回本人记录，且不接受 readerId 参数（传了也被忽略）。〔D2=允许时〕读者续借他人借阅返回 404 `LOAN_NOT_FOUND`，且数据没有变化。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | 两个读者的数据隔离测试 | 测试结果 |
| AC-AUTH-06 | 已登录状态下发出的非 GET 请求如果缺少或带了错误的 `X-XSRF-TOKEN`，返回 403 `CSRF_INVALID`，数据没有变化；带正确 Token 时成功。登录请求同样受此保护。 | 后端 + 前端 client | 是 | IMPL / REVIEW | DEV / REV | 集成测试（不用 `csrf()` 后处理器的负向用例）；前端 client 单测检查请求头 | 测试结果 |
| AC-AUTH-07 | `POST /api/auth/logout` 返回 204，之后用同一 Cookie 调用 `/api/auth/me` 返回 401。 | 后端 + 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试；E2E 登出步骤 | 测试结果 |
| AC-AUTH-08 | 口令以 BCrypt 存储（数据库中的值以 `{bcrypt}$2` 开头）。任何 API 响应 JSON 都不含 `password`、`passwordHash` 字段。登录失败日志中不出现口令。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | 集成测试直接查库断言；遍历主要响应检查没有敏感字段；评审代码 | 测试结果；评审意见 |
| AC-AUTH-09 | 前端：未登录访问受保护路由时跳转 `/login?redirect=...`，登录后回到原页面；ADMIN 和 READER 只看到各自的菜单；READER 访问 `/admin/*` 显示 403 页；任意接口返回 401 时跳转登录页并提示会话过期；redirect 参数只接受站内相对路径。 | 前端 | 是 | IMPL / ACCEPT | DEV / ACC | Vitest 测试 RequireAuth/RequireRole/client 的 401 处理；E2E 第③⑦步 | 测试结果；验收记录 |
| AC-AUTH-10 〔D4①〕 | 已登录用户通过 `PUT /api/auth/password` 提供正确旧口令和合规新口令，返回 204；之后只能用新口令登录。旧口令错误返回 400 `AUTH_PASSWORD_MISMATCH`；新口令不合规返回 400 `VALIDATION_FAILED`。 | 后端 + 前端 | D4①选择纳入时必需，否则 NOT_APPLICABLE | IMPL / ACCEPT | DEV / ACC | 集成测试；手工验收 | 测试结果 |
| AC-AUTH-11 | 首次启动时，如果没有管理员，就创建 `lib.admin.username` 管理员；口令取 `LIB_ADMIN_PASSWORD`。未设置该变量且为 dev profile 时，使用默认口令并输出 WARN；未设置且不是 dev profile 时，启动失败。重启不覆盖已有口令。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | AdminBootstrap 集成或单元测试 | 测试结果 |

### 1.2 图书与读者管理（CAT / BOOK / COPY / RDR）

| 编号 | 可观察条件 | 范围 | 必需 | 阶段 | 执行/核验 | 验证方法 | 证据 |
|---|---|---|---|---|---|---|---|
| AC-CAT-01 | 管理员可以新建、修改、删除分类，任何已登录用户都能列出分类。名称重复（不区分大小写）时返回 409 `CATEGORY_NAME_DUPLICATE`；删除仍被图书引用的分类返回 409 `CATEGORY_IN_USE`。列表中的 `bookCount` 正确。 | 后端 + 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试；前端组件测试；验收时手工操作 | 测试结果 |
| AC-BOOK-01 | 管理员用合法字段新建图书，返回 201；ISBN 带连字符或空格时按规范化结果保存；默认生成 1 个副本（`initialCopies` 可取 0..50）。字段缺失或超长返回 400，并附 `fieldErrors`；ISBN 重复返回 409 `ISBN_DUPLICATE`；〔D4③〕ISBN 校验位错误返回 400。 | 后端 + 前端表单 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试；IsbnUtils 单测；前端表单测试（显示 fieldErrors） | 测试结果 |
| AC-BOOK-02 | 管理员可以修改图书。删除没有任何借阅历史的图书返回 204，其副本一并删除；图书有在借副本时返回 409 `BOOK_HAS_ACTIVE_LOANS`；有历史借阅时返回 409 `BOOK_HAS_LOAN_HISTORY`；以上失败都不改变数据。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | 集成测试 | 测试结果 |
| AC-BOOK-03 | 图书详情和列表中，`totalCopies` 等于该书副本数，`availableCopies` 等于其中 AVAILABLE 的副本数；借出或归还后立即反映变化。 | 后端 + 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试（借还前后各断言一次）；E2E | 测试结果 |
| AC-COPY-01 | 管理员可以为图书追加 1..50 个副本，系统生成唯一条码，格式为 `C` 加 8 位数字；超出范围返回 400。 | 后端 + 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试 | 测试结果 |
| AC-COPY-02 | 副本状态可以在 AVAILABLE 和 UNAVAILABLE 之间切换；在借副本切换状态返回 409 `COPY_ON_LOAN`；请求设置 BORROWED 返回 400。删除从未借出的副本返回 204；删除有借阅历史的副本返回 409 `COPY_HAS_LOAN_HISTORY`，删除在借副本返回 409 `COPY_ON_LOAN`。UNAVAILABLE 的副本不能借出。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | 集成测试 | 测试结果 |
| AC-RDR-01 | 管理员新建读者（姓名、可选的电话和邮箱、用户名、初始口令），返回 201：同时创建 READER 账号，卡号由系统生成，格式为 `R` 加 6 位数字；响应不含口令。用户名重复（不区分大小写）返回 409 `USERNAME_DUPLICATE`；口令不合规（8–64 位，含字母和数字）、邮箱或电话格式错误返回 400。新读者可以用初始口令登录。 | 后端 + 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试；E2E 第①步 | 测试结果 |
| AC-RDR-02 | 管理员可以修改读者资料，并停用或启用读者。读者停用后不能登录（AC-AUTH-02），也不能借书或续借（返回 `READER_DISABLED`）；重新启用后恢复。 | 后端 + 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试 | 测试结果 |
| AC-RDR-03 | 删除没有借阅历史的读者返回 204，账号一并删除；读者有在借记录时返回 409 `READER_HAS_ACTIVE_LOANS`，有历史记录时返回 409 `READER_HAS_LOAN_HISTORY`。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | 集成测试 | 测试结果 |
| AC-RDR-04 | 管理员可以分页检索读者：姓名按子串匹配，卡号和用户名按精确匹配，可按状态过滤；列表中的 activeLoanCount 和 overdueLoanCount 正确。 | 后端 + 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试；前端组件测试 | 测试结果 |
| AC-RDR-05 | 管理员重置读者口令返回 204；之后读者只能用新口令登录。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | 集成测试 | 测试结果 |
| AC-RDR-06 | 读者在“我的资料”页看到本人的卡号、姓名、联系方式、在借数和逾期数（`GET /api/readers/me`）。 | 后端 + 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试；验收时手工查看 | 测试结果；截图 |

### 1.3 借阅与归还（LOAN）

| 编号 | 可观察条件 | 范围 | 必需 | 阶段 | 执行/核验 | 验证方法 | 证据 |
|---|---|---|---|---|---|---|---|
| AC-LOAN-01 | 读者为 ACTIVE、没有逾期、在借数小于 5、副本为 AVAILABLE 时，管理员用 `{readerCardNo, copyBarcode}` 借书，返回 201 `Loan`：borrowDate 为当天，dueDate 为当天加 30 天，status 为 ACTIVE，renewCount 为 0。副本变为 BORROWED，图书的 availableCopies 减 1，借阅记录中写入经办人。 | 后端 + 前端柜台 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试（MutableClock 固定日期）；E2E 第②步 | 测试结果 |
| AC-LOAN-02 | 以下拒绝情况返回对应错误码，且数据完全不变：读者不存在 404 `READER_NOT_FOUND`；副本不存在 404 `COPY_NOT_FOUND`；读者停用 409 `READER_DISABLED`；读者有逾期 409 `READER_HAS_OVERDUE`；在借 5 本时借第 6 本返回 409 `LOAN_LIMIT_EXCEEDED`（第 5 本成功）；副本已借出或不可用 409 `COPY_NOT_AVAILABLE`；〔D4②〕读者已在借同书目另一副本 409 `DUPLICATE_BOOK_LOAN`。同时满足多个条件时，按设计 9.2 的顺序返回第一个。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | 集成测试，每种情况一个用例，另加优先级用例 | 测试结果 |
| AC-LOAN-03 | 并发安全：10 个线程同时借同一副本时，恰好 1 个成功，其余返回 `COPY_NOT_AVAILABLE`，该副本在借记录恰好 1 条；同一读者（上限 5）并发借 8 个不同副本时，成功数不超过 5，最终在借数不超过 5；同一借阅被并发还两次时，恰好 1 次成功。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | 并发测试（ExecutorService + CountDownLatch），重复运行 3 次都通过 | 测试结果；评审确认测试确实是并发执行的 |
| AC-LOAN-04 | 管理员按借阅 id 或副本条码还书，返回 200：status 为 RETURNED，returnDate 为当天，副本变回 AVAILABLE。逾期归还时 overdueDays 大于 0 且归还成功。重复还书返回 409 `LOAN_ALREADY_RETURNED`；条码对应副本没有在借记录时返回 404 `ACTIVE_LOAN_NOT_FOUND`。 | 后端 + 前端柜台 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试；E2E 第④步 | 测试结果 |
| AC-LOAN-05 | 续借：借阅未逾期、renewCount 小于 1、读者为 ACTIVE 且没有其他逾期时，续借成功，dueDate 加 30 天（从原应还日起算），renewCount 加 1。再次续借返回 409 `RENEW_LIMIT_REACHED`；借阅已逾期返回 409 `LOAN_OVERDUE`；已归还返回 409 `LOAN_ALREADY_RETURNED`；读者有其他逾期返回 409 `READER_HAS_OVERDUE`；读者停用返回 409 `READER_DISABLED`。〔D2〕默认情况下读者调用续借返回 403；D2=允许时，读者可以续借本人借阅，结果同上。 | 后端 + 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试；E2E 第④步 | 测试结果 |
| AC-LOAN-06 | 逾期判定：一条借阅应还日为 D。在时区 Asia/Shanghai 下，D 当天 status 为 ACTIVE、overdueDays 为 0；D+1 当天 status 为 OVERDUE、overdueDays 为 1。UTC 时间 D 日 16:00（即上海时间 D+1 日 00:00）已判为逾期。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | LoanPolicy 单测，以及使用 MutableClock 的集成测试 | 测试结果 |
| AC-LOAN-07 | 读者“我的借阅”分“当前”和“历史”两部分，只含本人记录，显示书名、条码、借阅日、应还日、状态；逾期记录标红并显示逾期天数。 | 后端 + 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试；前端组件测试；E2E 第③步 | 测试结果；截图 |
| AC-LOAN-08 | 管理员借阅列表可以按读者、状态（ACTIVE / OVERDUE / RETURNED / CURRENT）、副本条码、书名过滤，并分页和排序。 | 后端 + 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试 | 测试结果 |

### 1.4 检索与统计（SRCH / STAT）

| 编号 | 可观察条件 | 范围 | 必需 | 阶段 | 执行/核验 | 验证方法 | 证据 |
|---|---|---|---|---|---|---|---|
| AC-SRCH-01 | `GET /api/books`：书名和作者按子串匹配，ASCII 字母不区分大小写；ISBN 带或不带连字符都能精确命中；`field=all` 同时匹配书名、作者和 ISBN；分类过滤和“仅可借”过滤生效；分页的 totalItems 正确；没有结果时返回 200 和空 items；`%`、`_` 按字面字符匹配；`q` 超过 100 字符返回 400。 | 后端 | 是 | IMPL / REVIEW | DEV / REV | 集成测试 | 测试结果 |
| AC-SRCH-02 | 管理员和读者都能检索，并查看图书详情及可借数量；读者看不到副本列表（`/api/books/{id}/copies` 返回 403）；未登录返回 401。检索页的条件写入 URL，刷新后保留。 | 后端 + 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试；E2E 第③步 | 测试结果 |
| AC-STAT-01 | `GET /api/stats/overview` 在固定测试数据下，各计数项与预期值完全一致。 | 后端 + 前端仪表盘 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试；验收时查看仪表盘 | 测试结果 |
| AC-STAT-02 | `GET /api/stats/loans` 按 DAY 或 MONTH 返回区间内每个周期的借出数和归还数，没有数据的周期填 0；from 晚于 to 或跨度超过 366 天返回 400。 | 后端 + 前端统计页 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试 | 测试结果 |
| AC-STAT-03 | `GET /api/stats/top-books` 按区间内借阅次数降序排列，次数相同时按书名升序，limit 生效。 | 后端 + 前端 | 是 | IMPL / REVIEW | DEV / REV | 集成测试 | 测试结果 |
| AC-STAT-04 | `GET /api/reports/overdue` 只列出未还且已过应还日的借阅，含读者、书、条码、应还日、逾期天数，默认按逾期天数降序；已还或未到期的记录不出现；只有 ADMIN 可访问。 | 后端 + 前端报表页 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试；E2E 第⑤步（演示数据中的 reader2） | 测试结果 |

### 1.5 前端交互（UI）

| 编号 | 可观察条件 | 范围 | 必需 | 阶段 | 执行/核验 | 验证方法 | 证据 |
|---|---|---|---|---|---|---|---|
| AC-UI-01 | 各数据页都有加载、空态、错误（带“重试”）、有数据四种状态；503 时提示“系统繁忙”。 | 前端 | 是 | IMPL / REVIEW / ACCEPT | DEV / REV / ACC | 每个 feature 至少 1 个列表页覆盖四种状态的组件测试；评审抽查；验收时可停掉后端观察错误态 | 测试结果；截图 |
| AC-UI-02 | 表单在前端校验，规则与 API 契约一致；服务端返回的 fieldErrors 显示到对应字段；提交过程中按钮禁用，不会重复提交；删除和停用需要二次确认。 | 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 组件测试（图书表单、读者表单） | 测试结果 |
| AC-UI-03 | 所有后端错误码在 `errors.ts` 中都有中文文案（与 ErrorCode 列表一致的测试）；借、还、续借失败时，柜台显示对应的中文原因。 | 前端 | 是 | IMPL / REVIEW | DEV / REV | 单测比对错误码清单；组件测试 | 测试结果 |
| AC-UI-04 | 流通柜台可以输入卡号和条码后回车借书，成功后显示应还日、清空条码并保持焦点，同时显示该读者的在借列表；输入条码可以还书并显示逾期天数。 | 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 组件测试；E2E 第②④步；验收时手工操作 | 测试结果；截图 |
| AC-UI-05 | 读者界面只有检索、图书详情、我的借阅、我的资料（〔D4①〕再加修改口令）；〔D2〕只有允许读者自助续借时才显示续借按钮。 | 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 组件测试（菜单）；E2E | 测试结果 |
| AC-UI-06 | 管理员仪表盘、统计页（日/月粒度切换、热门图书）、逾期报表可以正常渲染数据并分页。 | 前端 | 是 | IMPL / ACCEPT | DEV / ACC | 组件测试；E2E 第⑤⑥步 | 测试结果；截图 |

---

## 2. 完成定义（DoD）

| 编号 | 可观察条件 | 必需 | 阶段 | 执行/核验 | 验证方法 | 证据 |
|---|---|---|---|---|---|---|
| DOD-01 构建 | 在空的克隆目录中执行 `source scripts/env.sh && mvn -f backend/pom.xml verify` 成功（JDK 21）；用 Java 8 或 17 运行时 enforcer 明确报错；`npm --prefix frontend ci && npm --prefix frontend run build` 成功（/opt/homebrew/bin 下的 node 26）。 | 是 | IMPL / ACCEPT | DEV / ACC | 执行命令 | 命令输出摘要（退出码、BUILD SUCCESS） |
| DOD-02 启动 | `bash scripts/dev.sh` 在 120 秒内启动后端（:18095）和前端（:15195）；数据库不存在时自动创建并完成迁移；浏览器打开 http://localhost:15195 能登录；Ctrl-C 后两个进程都退出。端口被占用时给出明确提示。 | 是 | IMPL / ACCEPT | DEV / ACC | 实际运行 | 启动日志；验收记录 |
| DOD-03 后端测试 | `mvn verify` 中全部单元、集成和并发测试通过，0 个 failure，0 个 error，没有被 @Disabled 的 AC 测试；所有涉及后端的 AC 至少有 1 个测试，测试名带 AC 编号；生成 JaCoCo 报告（不设门槛）。 | 是 | IMPL / REVIEW | DEV / REV | 执行测试并检查 surefire 报告 | 测试总数、通过数；AC 到测试的映射 |
| DOD-04 前端检查 | `lint`、`typecheck`、`test`（vitest run）、`build` 全部通过，0 error。 | 是 | IMPL / REVIEW | DEV / REV | 执行命令 | 输出摘要 |
| DOD-05 E2E | `npm --prefix frontend run e2e` 通过设计 13 节的 7 步冒烟流程（chromium headless），使用独立端口和临时数据库，不影响开发数据库。 | 是 | IMPL / ACCEPT | DEV / ACC | 执行命令 | Playwright 报告摘要（必要时附 trace 路径） |
| DOD-06 种子与初始管理员 | 首次启动 dev 环境后，可以用管理员和演示读者登录；演示数据包含 1 条逾期借阅；重启后数据不重复（幂等）。测试 profile 不写入任何种子数据。 | 是 | IMPL / ACCEPT | DEV / ACC | 集成测试加实际启动 | 测试结果；验收记录 |
| DOD-07 README | 根目录 `README.md` 包含：前置条件（JDK 21 路径、Java 8 的规避方式、/opt/homebrew/bin 下的 node、nvm 损坏说明）、安装、启动、测试命令、端口与环境变量、默认账号（注明只用于开发）、数据库位置与重置方法、业务规则配置、已知限制（没有限流、没有 HTTPS 等）、非目标。验收人员**只按 README 操作**就能启动并完成冒烟流程。 | 是 | IMPL / ACCEPT | DEV / ACC | 验收人员按 README 从零执行 | 验收记录 |
| DOD-08 安全 | 没有明文口令；授权矩阵、CSRF、IDOR 测试全部通过；500 响应不含堆栈；所有 SQL 都用参数绑定，不拼接用户输入（评审检查）；`npm audit --omit=dev --audit-level=high` 没有 high/critical 漏洞，如有则逐条说明并经用户批准豁免；仓库中没有真实凭据或密钥；后端依赖处在受支持的版本线上。 | 是 | IMPL / REVIEW | DEV / REV | 测试、命令和代码评审 | 审计输出；评审意见 |
| DOD-09 追溯 | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/ac-dod-matrix.md` 为每条 AC/DoD 填写定义版本、阶段、预期/实际结果、验证方法、执行者、证据位置和候选快照（如源码树哈希或时间戳）；不允许留空，也不能用 PASS 代替缺失的证据。 | 是 | IMPL / REVIEW / ACCEPT | DEV 填写 / REV、ACC 复核 | 文档检查 | 矩阵文件 |
| DOD-10 范围 | 没有实现非目标功能；没有引入设计第 4 节清单以外的依赖，确需引入时必须经主会话转用户批准；后端代码中没有 `REQUIRES_NEW`；没有修改已有的 Flyway 迁移文件。 | 是 | REVIEW | REV | 评审 | 评审意见 |
| DOD-11 独立评审 | 独立评审环节结论为 PASS（没有未关闭的 Blocker 或 Critical），评审基于明确的候选快照。 | 是 | REVIEW | REV | 评审报告 | 评审报告位置 |
| DOD-12 独立验收 | 独立验收环节按 AC v1 在本机实际运行验收，结论为 PASS；验收快照必须与评审快照一致，若不一致需要说明差异并重新验证。 | 是 | ACCEPT | ACC | 验收报告 | 验收报告位置 |

> 交付运维环节没有启用：部署、发布和上线验证都不在 DoD 中。

---

## 3. 映射：AC/DoD 与设计、任务、测试

| 编号 | 设计章节（solution-design.md） | 实现任务（plan-v1） | 主要测试位置（建议） |
|---|---|---|---|
| AC-AUTH-01/02/03/06/07/08/11 | §7, §8.4 auth, §11 | T01（后端）、T02（前端登录页和 client） | `backend/src/test/java/com/example/library/auth/*`、`security/*`；`frontend/src/auth/__tests__`、`src/api/__tests__` |
| AC-AUTH-04 | §7 矩阵 | T01（规则），T04–T07 各自补充本模块的矩阵用例 | `security/AuthorizationMatrixTest`（T01 建立参数化框架，各模块追加用例文件 `*AuthorizationTest`） |
| AC-AUTH-05 | §7 IDOR, §8.4 /me | T05、T06 | `reader/*`、`loan/*` |
| AC-AUTH-09 | §10.1/10.2 | T02 | `frontend/src/auth/__tests__`，E2E T12 |
| AC-AUTH-10〔D4①〕 | §8.4 | T14 | `auth/ChangePasswordTest`、`features/account/__tests__` |
| AC-CAT-01, AC-BOOK-01..03, AC-COPY-01..02 | §6, §8.4 catalog | T04（后端）、T08（前端） | `catalog/*`；`features/catalog/__tests__` |
| AC-RDR-01..06 | §6, §8.4 reader | T05（后端）、T09（前端） | `reader/*`；`features/readers/__tests__` |
| AC-LOAN-01..08 | §9, §8.4 loan | T06（后端）、T10（前端） | `loan/LoanPolicyTest`、`loan/*IT`、`loan/LoanConcurrencyTest`；`features/circulation/__tests__` |
| AC-SRCH-01..02 | §8.4 检索语义 | T04、T08 | `catalog/BookSearchTest`；E2E |
| AC-STAT-01..04 | §8.4 stats | T07、T11 | `stats/*`；`features/stats/__tests__` |
| AC-UI-01..06 | §10 | T02（公共组件和错误映射）、T08–T11 | 各 feature 的 `__tests__`；E2E T12 |
| DOD-01/02 | §1, §5 | T01、T02、T03，T13 联调 | 命令输出 |
| DOD-03 | §13 | T01、T04–T07 | surefire 报告 |
| DOD-04 | §13 | T02、T08–T11 | vitest/eslint/tsc 输出 |
| DOD-05 | §13 E2E | T12 | Playwright 报告 |
| DOD-06 | §7, §11 | T01 | `seed/*Test`、实际启动 |
| DOD-07 | §5 | T13 | README.md |
| DOD-08 | §7, §12, §13 | 全部任务；T13 汇总 | 测试、审计、评审 |
| DOD-09 | — | T13 填写；REV、ACC 复核 | 矩阵文件 |
| DOD-10/11 | — | REV | 评审报告 |
| DOD-12 | — | ACC | 验收报告 |

---

## 4. 核验矩阵（当前状态）

方案阶段只完成“定义 + 映射”。所有 AC/DoD 的执行结果都要在后续阶段才能取得，所以当前统一为 **NOT_DUE**，这一状态以用户确认 v1 为前提。用户确认前，定义本身仍是草案。

| 编号范围 | 定义版本 | 阶段 | 预期 | 实际 | 结果 | 方法 | 执行/核验 | 证据位置 | 快照/环境 |
|---|---|---|---|---|---|---|---|---|---|
| AC-AUTH-01..11 | v0.1 草案 | IMPL/REVIEW/ACCEPT | 见 1.1 | — | NOT_DUE | 见各条 | DEV/REV/ACC | 待填：docs/verification/ac-dod-matrix.md | — |
| AC-CAT/BOOK/COPY/RDR | v0.1 草案 | 同上 | 见 1.2 | — | NOT_DUE | | | 同上 | — |
| AC-LOAN-01..08 | v0.1 草案 | 同上 | 见 1.3 | — | NOT_DUE | | | 同上 | — |
| AC-SRCH/STAT | v0.1 草案 | 同上 | 见 1.4 | — | NOT_DUE | | | 同上 | — |
| AC-UI-01..06 | v0.1 草案 | 同上 | 见 1.5 | — | NOT_DUE | | | 同上 | — |
| DOD-01..12 | v0.1 草案 | 见第 2 节 | 见第 2 节 | — | NOT_DUE | | | 同上 | — |
| AC-AUTH-10 | v0.1 草案 | — | — | — | 取决于 D4①：纳入时为 NOT_DUE，不纳入时为 NOT_APPLICABLE（需用户决定作为依据） | | | | |

**方案阶段自检（DESIGN，执行者：方案设计环节，非独立核验）**：每条 AC/DoD 都已映射到设计章节和实现任务，没有未映射项；阈值已标注为待确认。这只是自检结论，不代表任何环节的 PASS。

## 5. 首期借阅提醒增量 AC/DoD v1.1

2026-11-19 经主会话 AskUserQuestion 批准。完整增量定义与实施范围见 `docs/design/reminder-increment.md`，批准来源见 `docs/design/approval-record.md` 第4节。以上基础v1定义原文保留；本次仅以下菜单定义增量覆盖原AC-UI-05的排他性表述：

- 原读者菜单保留，并增加“我的提醒”。原读者自助续借权限不变。
- 管理员增加“催还记录”；逾期报表行内提供人工催还登记入口。
- 新增AC-REM-01..06、AC-NTF-01..03、AC-DUN-01..04、AC-UI-07，以及DOD-R1..R5，全部必需。
- 原DOD-01..12在本次候选重新核验，不降低既有门禁；历史结果不能作为当前候选证据。

本功能执行结果由 `docs/verification/reminder-ac-dod-matrix.md` 单独追溯；定义批准不表示实现或核验通过。
