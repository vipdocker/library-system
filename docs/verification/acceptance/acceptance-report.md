# 独立验收报告（第 2 轮）— PASS

- taskId：lib-mgmt-20261117
- 环节：独立验收（acceptance-verifier），第 2 轮
- 会话标题：独立验收：图书馆系统候选 53beed1d
- 验收日期：2026-11-18
- 交付终点：本机可运行的首版图书馆管理系统（Spring Boot + React + SQLite）；部署/发布不在范围内（交付运维环节未启用）
- 契约：design-v0.1-R1（solution-design.md，sha256 前缀 f8439ccfd8449952，本轮核验 OK）
- 标准：**AC/DoD v1**（ac-dod.md，sha256 前缀 41454ea7dfffe92b，本轮核验 OK）
- 授权：approval-record.md §1 D1–D7、§1.1 追认（R1、I-08/I-09）、§3 待办、I-04/I-06/I-07 已由评审同意按已知限制放行
- 独立评审：PASS（0 阻断，7 非阻断），会话 63bcd8ec-d7fd-53d9-a712-cd4eefe52915，同一候选

---

## 0. 第 1 轮记录（BLOCKED，已解决）

第 1 轮开始前快照校验发现 `docs/design/approval-record.md` 哈希与快照不符（主会话在快照生成后追加了 §4 环节结论），按交接包规则返回 BLOCKED，未执行实测。主会话已删除追加段使文件恢复快照原字节（e9aa8a7a…，与快照一致），登记内容移至快照排除目录下的 `docs/verification/stage-results.md`。评审在追加前完成 279/279 校验，因此评审与验收绑定同一候选，无需豁免。

## 1. 候选确认

| 校验 | 开始前 | 结束时 |
|---|---|---|
| 工作区 279 文件哈希 | 279/279 OK | 279/279 OK |
| 清单文本 sha256 = 候选 ID | `53beed1d…c2ef1c7` ✓ | ✓ |
| 干净克隆 279 文件哈希 | 279/279 OK | 279/279 OK |
| 开发库 backend/data/library.db | Nov 18 11:12:41 2026 / 118784 | Nov 18 11:12:41 2026 / 118784（未被触碰）|

克隆目录：`/tmp/acc-clone.yYiWjW`（mktemp 生成，只复制快照列出的 279 个文件，不含 node_modules/target/dist/data；验收结束后保留作证据）。

## 2. 验收方式

按交接包推荐方式：干净克隆 + **只按克隆内 README.md 操作**（安装、启动、测试、重置全部照做）。API 用 curl（先 GET /api/auth/csrf 取 Token 再带 Cookie/X-XSRF-TOKEN）；UI 用真实浏览器（Playwright chromium-1254 headless，与 E2E 同一引擎）实测；并发用 Node fetch 黑盒并发请求。

## 3. AC 核验矩阵（43 条）

| 编号 | 定义版本 | 前置/数据 | 步骤与证据 | 预期 | 实际 | 结果 |
|---|---|---|---|---|---|---|
| AC-AUTH-01 | v1 | 克隆 dev 实例，admin/reader1 | curl 登录 admin→200 UserInfo(role=ADMIN)；reader1→200 含 readerId=1,cardNo=R000001；JSESSIONID `#HttpOnly_`（HttpOnly）；/api/auth/me 返回同一用户 | 200+正确角色 | 全部一致 | **PASS** |
| AC-AUTH-02 | v1 | 同上 | 错误用户名与错误口令均 401 `AUTH_INVALID_CREDENTIALS`（message 相同）；reader3（DISABLED）正确口令 401 `AUTH_ACCOUNT_DISABLED` | 401 相应码 | 一致 | **PASS** |
| AC-AUTH-03 | v1 | 匿名 | 匿名 GET /api/books→401 JSON `AUTH_REQUIRED`，无 302（redirect 为空）；匿名 POST 无 CSRF→403 CSRF_INVALID（按评审口径 R-03 属设计内，不算失败）；带有效 Token 的匿名 POST→401 AUTH_REQUIRED | 401 不重定向 | 一致 | **PASS** |
| AC-AUTH-04 | v1 | reader1 会话 | 9 个 GET 管理端点 + 6 个写操作（POST categories/readers/loans、PUT books、PATCH copies、DELETE books）全部 403 `FORBIDDEN`；admin 访问 /api/readers/me、/api/loans/me 403 | 403 | 一致 | **PASS** |
| AC-AUTH-05 | v1 | reader1/reader2 会话 | reader2 /api/readers/me 返回 id=2、overdueLoanCount=1；loans/me 读者 id 全为 2，与 reader1（id=1）无交集；传 readerId 参数被忽略；读者续借他人借阅→403（D2 生效，且 renew 走 ADMIN-only 端点） | 隔离 | 一致 | **PASS** |
| AC-AUTH-06 | v1 | 登录态 | 错误 X-XSRF-TOKEN POST→403 `CSRF_INVALID`；缺 Token 同样 403；带正确 Token（每次重新获取）成功 | 403/成功 | 一致 | **PASS** |
| AC-AUTH-07 | v1 | admin 会话 | POST /api/auth/logout→204；同 Cookie /api/auth/me→401 | 204→401 | 一致 | **PASS** |
| AC-AUTH-08 | v1 | 克隆库 | SQLite 直查：app_user.password_hash 均为 `{bcrypt}$2a$` 前缀；登录/用户/读者响应 JSON 无 password 字段 | BCrypt+无泄露 | 一致 | **PASS** |
| AC-AUTH-09 | v1 | 浏览器 | 未登录访问 /admin/readers→`/login?redirect=%2Fadmin%2Freaders`，登录后回到原页面（UI 实测+截图 ui-01/02）；管理页登出→/login 无 redirect 残留（ui-03）；读者访问 /admin/books→/403 页（ui-05）；读者登录落在读者首页无 403（ui-04） | 跳转/回跳/403 | 一致 | **PASS** |
| AC-AUTH-10 | v1 (D4①) | 新建读者 accreaderac11x9 | 旧口令错误→400 `AUTH_PASSWORD_MISMATCH`；新口令不合规→400 VALIDATION_FAILED(fieldErrors)；正确修改→204，之后新口令可登录、旧口令 401 | 204/400 | 一致 | **PASS** |
| AC-AUTH-11 | v1 | 克隆全新库 | dev.sh 首启无库→自动建库+迁移+admin 创建，admin/Admin@12345 可登录；重启后口令不变（幂等验证时未重置 admin 口令，且 WARN 输出在 dev 日志中） | 引导可用 | 一致 | **PASS** |
| AC-CAT-01 | v1 | admin | 新建分类 201；同名精确与大小写不同（AccCase→ACCCASE）均 409 `CATEGORY_NAME_DUPLICATE`；PUT 更新成功；删除被引用分类（文学，bookCount=3）409 `CATEGORY_IN_USE`；删除未用分类成功；读者可列分类且 bookCount 正确 | CRUD+错误码 | 一致 | **PASS** |
| AC-BOOK-01 | v1 | admin | 新建图书 201，ISBN 带连字符规范化存储；重复 409 `ISBN_DUPLICATE`；校验位错 400（D4③）；缺 title/author、publishYear=500、initialCopies=51 均 400+fieldErrors；副本数=2 创建 | 201/409/400 | 一致 | **PASS** |
| AC-BOOK-02 | v1 | admin | PUT 修改成功；删除有借阅历史的书 409 `BOOK_HAS_LOAN_HISTORY`；删除有在借的书 409 `BOOK_HAS_ACTIVE_LOANS`；删无历史新书 204 且 GET 变 404 | 204/409 | 一致 | **PASS** |
| AC-BOOK-03 | v1 | admin | book13 初始 availableCopies=2；借出后=1；归还后恢复；借出/归还后 totalCopies 不变 | 计数联动 | 一致 | **PASS** |
| AC-COPY-01 | v1 | admin | POST /api/books/13/copies count=3→3 个副本条码 C00000027..29（C+8 位数字）；count=0→400 | 生成唯一条码 | 一致 | **PASS** |
| AC-COPY-02 | v1 | admin | AVAILABLE↔UNAVAILABLE 切换成功；UNAVAILABLE 不可借（409 COPY_NOT_AVAILABLE）；在借副本切换 409 `COPY_ON_LOAN`；请求 BORROWED 400；删未借副本 204；删有历史副本 409 `COPY_HAS_LOAN_HISTORY`；删在借副本 409 COPY_ON_LOAN | 全错误码 | 一致 | **PASS** |
| AC-RDR-01 | v1 | admin | 新建读者 201，卡号 R000004（R+6 位数字），响应无口令；初始口令可登录；用户名大小写不同重复 409 `USERNAME_DUPLICATE`；口令无数字 400（8–64 位含字母数字）；邮箱格式错 400 fieldErrors[email] | 201/409/400 | 一致 | **PASS** |
| AC-RDR-02 | v1 | admin+读者 | PUT 全字段停用→状态 DISABLED；停用后登录 401 AUTH_ACCOUNT_DISABLED、借书 409 READER_DISABLED、续借 409 READER_DISABLED；重新启用后全部恢复 | 停用联动 | 一致 | **PASS** |
| AC-RDR-03 | v1 | admin | 删除有在借的读者 409 `READER_HAS_ACTIVE_LOANS`；新建无借阅历史读者删除 204 | 204/409 | 一致 | **PASS** |
| AC-RDR-04 | v1 | admin | q=姓名子串、q=卡号精确、q=用户名（大小写不敏感）精确命中；status=DISABLED 过滤正确；分页 page/size/totalItems/totalPages 正确；activeLoanCount/overdueLoanCount 与实际一致（读者4 借 5 本时 activeLoanCount=5） | 检索正确 | 一致 | **PASS** |
| AC-RDR-05 | v1 | admin | PUT /api/readers/4/password 204；旧口令登录 401；新口令登录 200 | 重置生效 | 一致 | **PASS** |
| AC-RDR-06 | v1 | reader2 浏览器 | /my/profile 显示卡号 R000002、姓名、状态、在借 1、逾期 1（截图 ui-07）；API /api/readers/me 数据一致 | 展示正确 | 一致 | **PASS** |
| AC-LOAN-01 | v1 | admin+R000004+C00000027 | POST /api/loans 201：borrowDate=2026-11-18，dueDate=2026-12-18（+30 天），status=ACTIVE，renewCount=0；副本变 BORROWED（availableCopies 2→1）；DB 查 loan.borrow_operator_id=1 | 201+日期规则 | 一致 | **PASS** |
| AC-LOAN-02 | v1 | 各失败路径 | 读者不存在 404 READER_NOT_FOUND；副本不存在 404 COPY_NOT_FOUND；停用 409 READER_DISABLED；逾期读者 409 READER_HAS_OVERDUE；在借副本 409 COPY_NOT_AVAILABLE；同书目另一副本 409 DUPLICATE_BOOK_LOAN（D4②）；第 5 本成功、第 6 本 409 LOAN_LIMIT_EXCEEDED；拒绝后数据不变 | 全错误码 | 一致 | **PASS** |
| AC-LOAN-03 | v1 | 黑盒并发 | 10 并发借同一副本：201×1 + COPY_NOT_AVAILABLE×9，在借记录恰好 1；同一读者并发借 8 本不同书：201×5 + LOAN_LIMIT_EXCEEDED×3，最终在借 5；并发还同一借阅 2 次：200×1 + 409 LOAN_ALREADY_RETURNED×1（证据 logs/acc-concurrency-blackbox.txt）；另有 mvn verify 中 LoanConcurrencyTest 通过（评审确认真实并发） | 并发安全 | 一致 | **PASS** |
| AC-LOAN-04 | v1 | admin | 按 id 还书 200：RETURNED、returnDate=当天、副本恢复 AVAILABLE；重复还 409 LOAN_ALREADY_RETURNED；条码还 200；无在借条码 404 ACTIVE_LOAN_NOT_FOUND；逾期借阅归还成功（E2E 步骤④） | 归还规则 | 一致 | **PASS** |
| AC-LOAN-05 | v1 | admin | 续借：dueDate 2026-12-18→2027-01-17（+30 天从原应还日起算），renewCount 1；再续 409 RENEW_LIMIT_REACHED；逾期借阅续借 409 LOAN_OVERDUE；停用读者续借 409 READER_DISABLED；读者续借 403（D2）；UI 柜台续借后按钮禁用（1/1）（截图 ui-08b/08e） | 续借规则 | 一致 | **PASS** |
| AC-LOAN-06 | v1 | reader2 演示逾期借阅 | dueDate=2026-11-08（已过 10 天）：status=OVERDUE，overdueDays=10；按期借阅（dueDate 未来）status=ACTIVE overdueDays=0；重置库后重建同结果；边界（应还日当天/UTC 16:00）由 MutableClock 集成测试覆盖（mvn verify 通过） | 逾期判定 | 一致 | **PASS** |
| AC-LOAN-07 | v1 | reader2 浏览器 | /my/loans 分“当前/历史”Tab，只含本人记录，显示书名/条码/借阅日/应还日/状态；页头警示“您有 1 册图书已逾期”；逾期记录显示逾期数（截图 ui-06） | 当前/历史+标红 | 一致 | **PASS** |
| AC-LOAN-08 | v1 | admin | 过滤 readerId=4、status=RETURNED（10 条）、status=CURRENT（5 条）、copyBarcode=C00000027、q=书名子串、sort=borrowDate,desc 均生效并分页 | 过滤排序 | 一致 | **PASS** |
| AC-SRCH-01 | v1 | admin | q 子串匹配（python/PYTHON 同命中）；作者子串；ISBN 带连字符与不带均精确命中；field=all 命中 ISBN；categoryId+availableOnly 过滤；空结果 200 空 items；`%`/`_` 字面匹配（0 命中无报错）；q>100 字符 400；分页 totalItems 正确 | 检索语义 | 一致 | **PASS** |
| AC-SRCH-02 | v1 | reader1 | 读者可检索并看详情（availableCopies 可见）；读者访问 /api/books/{id}/copies 403；未登录 401；检索页条件写 URL（E2E 与 UI 实测刷新保留） | 读者检索 | 一致 | **PASS** |
| AC-STAT-01 | v1 | admin | /api/stats/overview 全字段（totalBooks=13,totalCopies=28,availableCopies=23,borrowedCopies=5,totalReaders=4,activeReaders=3,activeLoans=4,overdueLoans=1,borrowsToday=5,returnsToday=2）与本轮操作历史完全自洽；UI 仪表盘渲染（ui-11） | 计数一致 | 一致 | **PASS** |
| AC-STAT-02 | v1 | admin | /api/stats/loans DAY 粒度逐日含 0 填充；from>to 400；>366 天 400（集成测试覆盖） | 序列正确 | 一致 | **PASS** |
| AC-STAT-03 | v1 | admin | top-books 降序、limit=3 生效（REVIEW 阶段测试通过 + 本轮实测 top3 返回） | 排序正确 | 一致 | **PASS** |
| AC-STAT-04 | v1 | admin | /api/reports/overdue 只含未还过期借阅（reader2 的 C00000001，overdueDays=10），含读者/书/条码/应还日/逾期天数，totalItems=1；读者访问 403；UI 报表页渲染（ui-10） | 逾期报表 | 一致 | **PASS** |
| AC-UI-01 | v1 | 前端 | 列表页加载/空态/错误/有数据四态：组件测试全量通过（vitest 323）；错误态实测——后端停掉后登录提交显示“网络异常，请检查连接后重试”（截图 ui-12）；503“系统繁忙”由 errors.ts 映射与单测覆盖 | 四状态 | 一致 | **PASS** |
| AC-UI-02 | v1 | 前端 | 前端校验与契约一致（E2E 新建图书流程通过）；fieldErrors 显示（本轮 API 400 响应含结构化 fieldErrors，前端组件测试验证映射）；删除需二次确认（组件测试；reset-db 类比）；提交按钮禁用（柜台续借达上限按钮 disabled 实测） | 表单行为 | 一致 | **PASS** |
| AC-UI-03 | v1 | 柜台 | 柜台借不存在的条码显示“副本不存在或已被删除”；重复续借路径按钮禁用+“已达上限”文案；所有后端错误码均有中文文案（errors.ts 完整性单测通过，评审 DOD-04 核验） | 中文错误 | 一致 | **PASS** |
| AC-UI-04 | v1 | 浏览器柜台 | 卡号+条码回车借书成功显示应还日（借出成功卡片：借阅日/应还日/读者/条码）；条码框清空且焦点保留（value="" focus=circ-borrow-barcode）；在借列表同步显示；还书 Tab 条码还书显示归还成功与书信息（截图 ui-08/08b/08c/08d） | 柜台交互 | 一致 | **PASS** |
| AC-UI-05 | v1 | reader1/2 浏览器 | 读者菜单恰为“图书检索/我的借阅/我的资料/修改口令”四项，无管理员菜单；无续借按钮（D2 生效） | 菜单隔离 | 一致 | **PASS** |
| AC-UI-06 | v1 | admin 浏览器 | 仪表盘、统计页（借阅统计、日/月切换）、逾期报表均渲染真实数据（截图 ui-09/10/11） | 页面渲染 | 一致 | **PASS** |

**AC 计数：43/43 PASS，0 FAIL，0 NOT_RUN，0 BLOCKED。**

## 4. DoD 核验矩阵（12 条）

| 编号 | 定义版本 | 阶段 | 预期 | 实际 | 结果 | 方法/证据 |
|---|---|---|---|---|---|---|
| DOD-01 | v1 | ACC | 空克隆 `mvn verify` 成功；Java 8 enforcer 报错；npm ci+build 成功 | 三项全部实测通过 | **PASS** | 克隆中 `source scripts/env.sh && mvn -f backend/pom.xml verify`→**BUILD SUCCESS，Tests run: 515, Failures: 0, Errors: 0, Skipped: 0**（log acc-dod01-mvn-verify.log）；Java 8（/usr/libexec/java_home -v 1.8）下 enforcer 明确报错“本项目要求 JDK 21 及以上”退出码 1（acc-dod01-java8.log）；`npm ci` exit 0、`npm run build` exit 0（898KB chunk 告警=已知限制 I-06，非错误） |
| DOD-02 | v1 | ACC | 120s 内启动；建库迁移；浏览器可登录；Ctrl-C 双进程退出；端口占用提示 | 全部实测通过 | **PASS** | `bash scripts/dev.sh`：后端就绪 6s（全新库含迁移+种子）；前端 200；三段式验证：kill 后端→dev.sh 清理前端退出；kill 前端占用进程→dev.sh 退 1 并列出占用进程与替代方案（acc-dod02-port-occupied.log）；SERVER_PORT=18097/FRONTEND_PORT=15197 换端口启动成功 |
| DOD-03 | v1 | REV | 后端测试全通过、JaCoCo | 评审核验 PASS；本轮克隆复跑一致 | **PASS** | 评审结论（approval-record §4）+ 本轮 mvn verify 515/515、JaCoCo index.html 生成、无 @Disabled |
| DOD-04 | v1 | REV | lint/typecheck/test/build 0 error | 评审核验 PASS；本轮 build 复跑成功 | **PASS** | 评审结论 + 本轮 npm build exit 0（vitest 323 通过为 T13 test-all 记录，评审已核） |
| DOD-05 | v1 | ACC | E2E 7 步冒烟通过 | 8/8 通过（7 步冒烟 + 1 登录冒烟） | **PASS** | 克隆中 `npm run e2e` exit 0，8 passed (42.8s)，独立端口 18096/15196 与临时库，结束后端口干净、开发库未变（acc-dod05-e2e.log） |
| DOD-06 | v1 | ACC | 演示账号可登录；含 1 逾期；重启不重复 | 全部实测通过 | **PASS** | admin/reader1/reader2/reader3（停用）行为符合 README §7；reader2 逾期借阅 overdueDays=10；重启前后 loan=16/reader=4/book=13 不变（幂等）；reset-db.sh --yes 后重启重建完整演示数据 |
| DOD-07 | v1 | ACC | README 要点齐全可照做 | 验收全程只按 README 操作成功 | **PASS** | 覆盖：前置条件（JDK21 路径/Java8 规避/nvm 说明）、安装（npm ci）、启动（dev.sh）、测试（test-all/分步）、端口与环境变量、默认账号、数据库位置与重置、业务规则配置（D1/D2）、已知限制、非目标——全部实测可照做。已登记的 R-04 文档小误差（矩阵计数、I-04“两份”实为三份）不构成本条失败 |
| DOD-08 | v1 | REV | 安全项全部通过 | 评审核验 PASS；本轮实测 BCrypt/CSRF/授权矩阵吻合 | **PASS** | 评审结论（0 阻断）；本轮补充：哈希 `{bcrypt}$2a$`、响应无敏感字段、授权矩阵 403 全命中 |
| DOD-09 | v1 | REV | 矩阵填写完整 | 评审核验 PASS | **PASS** | 评审结论（ac-dod-matrix.md 已填、无空项、无 PASS 代替证据） |
| DOD-10 | v1 | REV | 范围与依赖合规 | 评审核验 PASS | **PASS** | 评审结论（无非目标实现、无未批准依赖、无 REQUIRES_NEW、迁移未改动；本轮工作区快照 279/279 一致佐证候选未被变更） |
| DOD-11 | v1 | REV | 独立评审 PASS、明确候选 | 评审 PASS（0 阻断）基于同一候选 53beed1d… | **PASS** | approval-record §4；本轮开始/结束双校验确认同一候选字节 |
| DOD-12 | v1 | ACC | 验收按 AC v1 实测并 PASS；验收快照=评审快照 | 本报告：43 AC 全 PASS，双快照一致 | **PASS** | 本报告；工作区+克隆 279/279、候选 ID 相同 |

**DoD 计数：12/12 PASS，0 FAIL，0 NOT_RUN，0 BLOCKED。**

## 5. 缺陷清单

**无新增缺陷（0 个 A-xx）。**

过程中出现过 5 次“疑似失败”，经复核全部为测试脚本自身问题或产品规则正确生效，均不构成缺陷：
1. 柜台借 C00000011 被拒——实际是 D4② DUPLICATE_BOOK_LOAN 正确生效（该读者已在借同书目 C00000010）；
2. 并发场景 2 用同一书目 8 副本被拒 7 次——同上，D4② 正确；
3. 测试 ISBN 校验位算错（9787301060930/9781449316622/9781933988161）被 400 拒——D4③ 严格校验正确；
4. UI 脚本初版 selector/路由路径错误（/loans vs /my/loans、“续 借”带空格）——脚本修正后全过；
5. disable 读者请求缺 name 字段被 400——请求体不符合 ReaderUpdateRequest 契约，补全后正常。

已知限制 I-04（formErrors.ts 三份重复）、I-06（chunk 898KB）、I-07（停用后旧会话未即时踢出）均经评审同意放行且 README §9 已披露，本轮实测行为与披露一致，不计缺陷。R-04 文档小误差（矩阵测试计数、README I-04 份数）已登记待文档修正，不影响验收。

## 6. 实际命令与操作汇总（关键项）

| 命令/操作 | 结果 |
|---|---|
| 快照校验（开始/结束，工作区+克隆） | 4 次 279/279 OK，候选 ID 一致 |
| `bash /tmp/acc-clone.sh`（克隆 279 文件） | 279 文件，哈希全 OK |
| `source scripts/env.sh && mvn -f backend/pom.xml verify`（克隆） | BUILD SUCCESS，515/515 |
| Java 8 `mvn validate`（克隆） | enforcer 报错退出 1 ✓ |
| `npm --prefix frontend ci` / `run build`（克隆） | 均 exit 0 |
| `bash scripts/dev.sh`（克隆，4 次启停） | 就绪 6s/2s/2s/4s，全部<120s |
| 端口占用测试 / 换端口测试 | 提示明确；18097/15197 启动成功 |
| `npm --prefix frontend run e2e`（克隆） | 8 passed (42.8s) |
| `bash scripts/reset-db.sh --yes` + 重启 | 重建完整演示数据 |
| curl API 实测 | 覆盖全部控制器：auth（login/logout/me/csrf/password）、categories、books、copies、readers、loans（borrow/return/renew/return-by-barcode/list/me）、stats（overview/loans/top-books）、reports/overdue；40+ 断言全过 |
| 浏览器 UI 实测（Playwright chromium headless） | 22 项检查全过（登录/登出/redirect/403/菜单/我的借阅/我的资料/柜台借还续/统计/报表/错误态），16 张截图 |
| 并发黑盒（Node fetch） | 3 场景全 PASS |

## 7. 新增文件与清理状态

**新增（均在授权写入范围）**：
- `docs/verification/acceptance/acceptance-report.md`（本报告）
- `docs/verification/acceptance/screenshots/`（16 张 PNG，均来自克隆实例）
- `docs/verification/acceptance/logs/`（20 个日志/结果/脚本副本 + 并发黑盒记录）
- `/tmp/acc-clone.yYiWjW`（克隆目录，保留作证据）
- `/tmp/acc-*.{log,mjs,sh,json}`（过程文件，保留在 /tmp，不碍环境）

以上均不在候选快照 279 文件范围内，不影响候选 ID。
**清理状态**：dev/E2E/临时进程全部停止；6 个端口（18095/15195/18096/15196/18097/15197）逐一确认空闲；工作区开发库 `backend/data/library.db` mtime/size 前后一致未被写入；克隆内产生的数据库与测试数据仅存在于克隆目录；E2E 临时库已由 Playwright 自动清理。测试数据全部为带唯一后缀的合成数据。

## 8. 风险与待复测范围

- 本轮全部在干净克隆与本机 dev 实例完成，未涉及任何部署路径（交付终点即本机运行，运维环节未启用）。
- I-07（停用读者旧会话残留）在会话过期窗口内仍存在，属已批准的已知限制；若未来要求即时踢出，需重测登录/会话相关 AC。
- R-04 文档小误差待文档修正后，无需代码重测。
- 候选如有任何变更（代码/配置/依赖），受影响证据失效，需重新快照并重测。

## 9. 结论

**第 2 轮验收状态：PASS。**

- 候选确认：开始与结束均为 279/279 + 候选 ID `53beed1d…c2ef1c7`，与独立评审绑定同一候选。
- AC：43/43 PASS；DoD：12/12 PASS；无阻断、无未测项、无缺陷。
- 本环节结论仅覆盖约定交付终点（本机可运行首版），不构成生产发布授权（部署/发布不在范围）。

**建议下一步**：主会话可向用户汇报首版验收通过；后续如需迭代（文档 R-04 修正、I-04 合并 formErrors、I-06 代码分割），走正常变更流程并在变更后重新快照+重验。
