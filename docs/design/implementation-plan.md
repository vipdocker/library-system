# 图书馆管理系统首版：实施拆解

| 项 | 值 |
|---|---|
| planVersion | **plan-v1**（草案，与 design-v0.1、AC/DoD v0.1 配套） |
| 根目录 | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10`（下文路径均相对于此根目录；派发时请展开为绝对路径） |
| 派发方 | 主会话。本文件只是计划，**不构成开发授权**。 |
| 派发前提 | ① 用户确认 design-v0.1 与 AC/DoD v1；② D3（Boot 版本）、D6（依赖安装授权）已决定；③ D1、D2、D4、D5 已决定，或明确接受默认值。 |

---

## 1. 通用约束（所有任务）

1. **契约来源**：`docs/design/solution-design.md` 中的第 5 节（命令与配置）、第 6 节（schema）、第 7 节（授权矩阵）、第 8 节（API/DTO/错误码）、第 9 节（业务规则）。实现结果与契约不一致时，不得自行修改契约，应返回主会话，并附上偏差说明和建议。
2. **写入边界**：只能写本任务 `writeScope` 内的路径。需要改动范围外的文件时（包括 pom.xml、package.json、锁文件、迁移文件、SecurityConfig、ErrorCode、router.tsx、types.ts），返回 `NEEDS_INPUT`，由主会话协调，或推迟到 T13 处理。
3. **依赖**：只能使用设计第 4 节列出的依赖。依赖只能由 T01 写入 pom.xml、T02 写入 package.json，之后只有 T13 能改动这两个文件，而且新增依赖需要用户批准。
4. **环境**：所有命令通过 `source scripts/env.sh` 执行，或显式指定 `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` 和 `/opt/homebrew/bin/npm`。不要调用 nvm，也不要修改全局配置。T03 完成之前，T01 和 T02 直接使用上述绝对路径。
5. **代码约定**：禁止 `REQUIRES_NEW`；禁止修改已有 Flyway 迁移文件；SQL 必须使用参数绑定；禁止在日志中输出口令或 Token；测试名中带上 AC 编号。
6. **完成报告**：每个任务都返回 `DONE / DONE_WITH_CONCERNS / NEEDS_INPUT / BLOCKED` 之一，附实际执行的命令及退出码、测试通过数、改动文件清单（绝对路径），以及 AC 证据条目（写入草稿段落，T13 汇总进 `docs/verification/ac-dod-matrix.md`）。

---

## 2. 共享可变资源与所有权

| 资源 | 首次写入 | 之后的写权限 | 其余任务 |
|---|---|---|---|
| `backend/pom.xml` | T01 | T13（新增依赖需用户批准） | 只读 |
| `backend/src/main/resources/db/migration/**` | T01（V1） | 无。确需变更时新增 V2，由主会话安排，并标记为影响重验 | 只读 |
| `backend/src/main/resources/application*.yml`、`src/test/resources/**` | T01 | T13 | 只读 |
| `common/error/ErrorCode.java`（包含第 8.2 节的全部错误码） | T01 | T13 | 只读 |
| `security/SecurityConfig.java`（包含第 7 节的全部 URL 规则） | T01 | T13，以及 T14（仅限 `/api/auth/password` 一行，且仅当 D4① 纳入时） | 只读 |
| `domain/entity/**`、`domain/converter/**` | T01 | T13 | 只读 |
| `domain/repository/CategoryRepository`、`BookRepository`、`BookCopyRepository` | T01 | T04 可**新增**方法 | 只读；禁止修改已有方法签名 |
| `domain/repository/ReaderRepository`、`AppUserRepository` | T01 | T05 可新增方法 | 同上 |
| `domain/repository/LoanRepository` | T01 | T06 可新增方法 | 同上 |
| `backend/src/test/java/com/example/library/support/**`（测试基类、TestDataFactory、MutableClock、登录辅助） | T01 | T13 | 只读；各任务的私有夹具写在各自测试包里 |
| `frontend/package.json`、`package-lock.json`、`vite.config.ts`、`tsconfig*.json`、`eslint.config.js`、`vitest.setup.ts` | T02 | T13 | 只读 |
| `frontend/src/app/**`（router.tsx 全部路由、menu.ts、布局） | T02 | T13 | 只读 |
| `frontend/src/api/**`（client.ts、types.ts 全部 DTO、errors.ts 全部错误码文案） | T02 | T13 | 只读；各 feature 的接口函数写在 `features/<x>/api.ts` |
| `frontend/src/features/<x>/**` 的占位页面 | T02 创建占位文件 | 对应的 feature 任务（T08/T09/T10/T11/T14） | — |
| `frontend/playwright.config.ts`、`frontend/e2e/**` | T02（骨架 + 登录冒烟） | T12 | 只读 |
| `scripts/**`、`.gitignore` | T03 | T13 | 只读 |
| `README.md`、`docs/verification/**` | T13 | — | 只读 |
| SQLite 数据库文件 `backend/data/library.db` | 运行时生成 | — | 测试一律使用临时数据库，**禁止**让测试写入这个文件 |
| 端口 18095/15195（开发）、18096/15196（E2E） | 运行时占用 | — | 并行执行的任务不能同时启动 dev 服务器。只有 T12 和 T13 会起服务器，而且它们是串行的 |

> 后端集成测试使用临时文件数据库，前端测试使用 mock。所以同一批次的任务**运行测试时不会抢同一个运行时资源**。但它们共用 `~/.m2` 和 npm 缓存：首次下载依赖只在 T01 和 T02 进行，后续任务不会再触发大规模下载。

---

## 3. 任务清单

### T01 `be-foundation` 后端基础与认证（implement，必需）
- **目标**：
  - 创建 `backend/pom.xml`，内容包括：Boot 4.1.x（若 D3 选 B 则用 3.5.x）、第 4 节列出的依赖、enforcer（Java ≥21）、`maven.compiler.release=21`、JaCoCo report。
  - 建立应用骨架：启动时自动创建数据库父目录；`LibraryProperties`（@Validated）；Clock 配置。
  - Flyway `V1__init_schema.sql`，**完整覆盖第 6 节**。
  - 全部实体、枚举、Converter，以及 Repository，并预置以下跨模块方法：
    - BookCopyRepository：`markBorrowedIfAvailable`、`markAvailableIfBorrowed`、`findByBarcode`、`countByBookId`、`countByBookIdAndStatus`
    - LoanRepository：`existsByCopyId`、`existsByCopyBookId`、`existsActiveByCopyBookId`、`existsByReaderId`、`countActiveByReaderId`、`existsOverdueByReaderId(readerId, today)`、`existsActiveByReaderIdAndBookId`、`markReturnedIfActive`、`findActiveByCopyId`
    - ReaderRepository：`findByCardNo`、`findByUserId`
    - AppUserRepository：`findByUsernameIgnoreCase`、`existsByUsernameIgnoreCase`、`existsByRole`
    - CategoryRepository：`existsByNameIgnoreCase`
    - BookRepository：`existsByIsbn`、`existsByCategoryId`
  - 公共层：ErrorCode（全部错误码）、ApiException、GlobalExceptionHandler（含校验错误转 fieldErrors、DataIntegrityViolation 兜底、DB_BUSY、500 时不暴露内部信息）、PageResponse 和分页/排序白名单工具、IsbnUtils、LikeEscaper。
  - 安全层：SecurityConfig（第 7 节全部 URL 规则，含 D2 开关）、JSON 形式的 401/403/CSRF 处理、UserDetailsService（禁用账号不可登录）、AdminBootstrap。
  - auth 接口：csrf、login、logout、me。
  - seed/DemoDataLoader（第 11 节）。
  - 测试支撑：IntegrationTestBase（临时数据库、每个测试前清表）、TestDataFactory、MutableClock、`loginAs(...)` 和带 CSRF 的请求辅助；`security/AuthorizationMatrixTest` 参数化框架（含 auth 相关用例）。
- **非目标**：catalog/reader/loan/stats 的控制器和服务（各模块只建好空包结构也可以）；任何前端内容。
- **dependsOn**：无
- **readScope**：`docs/design/**`
- **writeScope**：`backend/pom.xml`、`backend/src/main/java/com/example/library/{LibraryApplication.java,common/**,security/**,auth/**,domain/**,seed/**}`、`backend/src/main/resources/**`、`backend/src/test/java/com/example/library/{support/**,security/**,auth/**,domain/**,common/**,seed/**}`、`backend/src/test/resources/**`
- **共享资源**：创建上表中所有后端共享资源；需要联网下载 Maven 依赖（D6）。
- **对应 AC/DoD**：AC-AUTH-01/02/03/06/07/08/11，AC-AUTH-04（框架部分及 auth 部分），DOD-01（后端）、DOD-03（部分）、DOD-06、DOD-08（部分）
- **预期产物**：可编译的后端；在空数据库上完成迁移且 `ddl-auto=validate` 通过；auth 接口可用。
- **验证**：`mvn -f backend/pom.xml verify` 退出码为 0；用 Java 8 运行时 enforcer 报错（`JAVA_HOME=$(/usr/libexec/java_home -v 1.8) mvn -f backend/pom.xml validate`，预期以 enforcer 规则失败退出）；Repository 冒烟测试，至少覆盖每个预置的跨模块方法；`spring-boot:run` 启动后 `GET /api/auth/csrf` 返回 204（在 18095 端口手工启动后立即关闭）。
- **风险提示**：Boot 4 的 starter 名称、Jackson 3 和测试注解所在的包都要以实际 BOM 为准。两轮尝试后仍无法让 SQLite 方言工作的，返回 BLOCKED，并附上证据。

### T02 `fe-foundation` 前端基础（implement，必需）
- **目标**：
  - 用 Vite + React 19 + TS 工程，安装第 4 节列出的依赖，生成锁文件。
  - 配置 `vite.config.ts`（端口 15195、strictPort、`/api` 代理目标取自环境变量）。
  - 配置 ESLint、`typecheck` 脚本和 Vitest。
  - npm scripts 名称固定为：`dev`、`build`、`preview`、`lint`、`typecheck`、`test`、`e2e`。
  - `api/client.ts`、`api/types.ts`（第 8.3 节全部 DTO）、`api/errors.ts`（第 8.2 节全部错误码及中文文案，外加 NETWORK_ERROR）、QueryClient 配置。
  - AuthContext（启动时依次请求 csrf 和 me）、RequireAuth、RequireRole、LoginPage。
  - AppLayout（按角色显示菜单）、RootRedirect；`router.tsx` 注册设计 10.1 的**全部路由**（页面懒加载）。
  - 公共组件：PageLoading、PageError（带重试）、EmptyState、ForbiddenPage、NotFoundPage。
  - 为每个 feature 页面在指定路径创建**占位组件**，只显示页面标题和“建设中”。
  - Playwright 配置骨架：两个 webServer，E2E 端口 18096/15196，后端用临时数据库，命令通过 `bash ../scripts/env.sh` 的同等逻辑设置 JAVA_HOME；附一个登录冒烟用例。
  - D4① 纳入时，额外注册 `/account/password` 路由和占位页。
- **非目标**：各 feature 页面的真实实现。
- **dependsOn**：无
- **readScope**：`docs/design/**`
- **writeScope**：`frontend/**`
- **共享资源**：创建上表中所有前端共享资源；需要联网执行 npm install（D6）。Playwright 浏览器优先使用缓存版本。
- **对应 AC/DoD**：AC-AUTH-06（client 部分）、AC-AUTH-09、AC-UI-01（公共组件）、AC-UI-03、DOD-01（前端）、DOD-04（部分）
- **预期产物**：可以构建的前端；登录跳转和权限守卫可用；全部路由可以访问到占位页。
- **验证**：`npm --prefix frontend ci` 后，`lint`、`typecheck`、`test`、`build` 全部通过；client、守卫、errors 完整性单测通过（与设计 8.2 的错误码清单逐项比对）。E2E 骨架在 T12 中运行，T02 内只要求 `npx playwright test --list` 能列出用例。

### T03 `root-scripts` 根目录脚本（implement，必需）
- **目标**：按设计第 5 节命令约定编写：
  - `scripts/env.sh`：校验 Java 主版本 ≥21、Node ≥20.19，失败时给出指引；支持 `LIB_JAVA_HOME` 覆盖。
  - `scripts/dev.sh`：先检查端口是否被占用，再启动后端并轮询 `/api/auth/csrf`，然后启动前端，用 trap 清理子进程。
  - `scripts/test-all.sh`：依次执行，任一步失败即停止并返回非 0；`node_modules` 不存在时先执行 `npm ci`。
  - `scripts/reset-db.sh`：检测到后端仍在运行时拒绝执行；需要交互确认，也可传 `--yes` 跳过。
  - `.gitignore`。
- **非目标**：README；应用代码。
- **dependsOn**：无（命令契约已在设计中固定）
- **readScope**：`docs/design/**`
- **writeScope**：`scripts/**`、`.gitignore`
- **对应 AC/DoD**：DOD-01、DOD-02（脚本部分）
- **验证**：`bash -n scripts/*.sh` 通过；`LIB_JAVA_HOME=$(/usr/libexec/java_home -v 1.8) bash -c 'source scripts/env.sh'` 返回非 0 并给出明确提示；默认参数下 `source scripts/env.sh && java -version` 显示 21；在临时端口上模拟端口被占用时，dev.sh 给出明确提示。完整的启动联调在 T13 进行。

### T04 `be-catalog` 分类、图书、副本与检索 API（implement，必需）
- **目标**：实现第 8.4 节中分类、图书、副本的全部接口和检索语义；删除前检查借阅历史；生成条码；计算可借数量；按白名单排序。
- **非目标**：借还逻辑；统计。
- **dependsOn**：T01
- **readScope**：`docs/design/**`、`backend/**`
- **writeScope**：`backend/src/main/java/com/example/library/catalog/**`、`backend/src/test/java/com/example/library/catalog/**`，并可在 `domain/repository/{CategoryRepository,BookRepository,BookCopyRepository}.java` 中**新增**方法
- **对应 AC**：AC-CAT-01、AC-BOOK-01/02/03、AC-COPY-01/02、AC-SRCH-01/02（后端部分）、AC-AUTH-04（本模块的授权用例）
- **验证**：`mvn -f backend/pom.xml verify` 通过；新增的测试覆盖上述 AC 的全部成功和失败分支。

### T05 `be-reader` 读者 API（implement，必需）
- **目标**：读者增删改查及检索；创建读者时同时创建账号；状态与账号 enabled 同步；管理员重置口令；`/api/readers/me`；删除前检查借阅历史；生成卡号。
- **dependsOn**：T01
- **readScope**：`docs/design/**`、`backend/**`
- **writeScope**：`backend/src/main/java/com/example/library/reader/**`、`backend/src/test/java/com/example/library/reader/**`，并可在 `domain/repository/{ReaderRepository,AppUserRepository}.java` 中新增方法
- **对应 AC**：AC-RDR-01..06、AC-AUTH-02（停用联动）、AC-AUTH-05（/me 部分）、AC-AUTH-04（本模块用例）
- **验证**：`mvn verify` 通过；包含停用后无法登录的端到端集成用例。

### T06 `be-loan` 借阅、归还、续借 API（implement，必需）
- **目标**：LoanPolicy；LoanService 的 borrow/return/returnByBarcode/renew，严格按第 9 节的顺序、原子更新和错误码实现；借阅列表和 `/me`；`renewable` 计算；INFO 事件日志。
- **dependsOn**：T01
- **readScope**：`docs/design/**`、`backend/**`
- **writeScope**：`backend/src/main/java/com/example/library/loan/**`、`backend/src/test/java/com/example/library/loan/**`，并可在 `domain/repository/LoanRepository.java` 中新增方法
- **对应 AC**：AC-LOAN-01..08、AC-AUTH-05（续借归属部分及 /me 部分）、AC-AUTH-04（本模块用例）、AC-RDR-02（借阅侧）
- **验证**：`mvn verify` 通过；LoanPolicy 单测覆盖逾期边界和时区；并发测试（AC-LOAN-03）在本地连续运行 3 次都通过（`mvn -Dtest=LoanConcurrencyTest -Dsurefire.rerunFailingTestsCount=0 test`，执行 3 次）。

### T07 `be-stats` 统计与逾期报表 API（implement，必需）
- **目标**：overview、loans 序列（补齐没有数据的周期为 0）、top-books、逾期报表；在 `StatsQueryRepository` 中用 JdbcClient 执行只读 SQL，基于 TEXT 日期写聚合；校验参数。
- **dependsOn**：T01
- **readScope**：`docs/design/**`、`backend/**`
- **writeScope**：`backend/src/main/java/com/example/library/stats/**`、`backend/src/test/java/com/example/library/stats/**`
- **对应 AC**：AC-STAT-01..04、AC-AUTH-04（本模块用例）
- **验证**：`mvn verify` 通过；用固定数据集精确断言各计数，包括跨月和边界日。

### T08 `fe-catalog` 检索与馆藏管理页面（implement，必需）
- **目标**：BookSearchPage（检索条件与 URL 同步）、BookDetailPage、AdminBookListPage、BookFormPage（新建、编辑，以及副本列表的追加、改状态、删除）、CategoryPage；`features/catalog/api.ts`；第 10.2 节规定的全部页面状态。
- **dependsOn**：T02
- **readScope**：`docs/design/**`、`frontend/**`
- **writeScope**：`frontend/src/features/catalog/**`
- **对应 AC**：AC-CAT-01、AC-BOOK-01/03、AC-COPY-01/02（UI 部分）、AC-SRCH-02、AC-UI-01/02
- **验证**：`lint`、`typecheck`、`test` 通过；组件测试覆盖检索页的四种状态和图书表单的 fieldErrors 显示。

### T09 `fe-readers` 读者管理与我的资料（implement，必需）
- **目标**：AdminReaderListPage（检索、新建、停用/启用、删除）、ReaderDetailPage（资料编辑、重置口令，以及该读者的借阅列表，数据来自 `GET /api/loans?readerId`）、MyProfilePage；`features/readers/api.ts`。
- **dependsOn**：T02
- **readScope**：`docs/design/**`、`frontend/**`
- **writeScope**：`frontend/src/features/readers/**`
- **对应 AC**：AC-RDR-01..06（UI 部分）、AC-UI-01/02
- **验证**：同 T08。

### T10 `fe-circulation` 流通柜台与借阅页面（implement，必需）
- **目标**：CirculationDeskPage（借书和还书两个 Tab、显示读者在借列表、续借）、AdminLoanListPage（过滤、分页）、MyLoansPage（当前/历史、逾期标红、D2 开关控制续借按钮）；`features/circulation/api.ts`。
- **dependsOn**：T02
- **readScope**：`docs/design/**`、`frontend/**`
- **writeScope**：`frontend/src/features/circulation/**`
- **对应 AC**：AC-LOAN-04/05/07/08（UI 部分）、AC-UI-01/03/04/05
- **验证**：同 T08；另需组件测试覆盖柜台的成功路径和 409 错误文案。

### T11 `fe-stats` 仪表盘、统计与逾期报表页面（implement，必需）
- **目标**：DashboardPage、StatsPage（日/月粒度切换、日期区间、热门图书；不引入图表库）、OverdueReportPage；`features/stats/api.ts`。
- **dependsOn**：T02
- **readScope**：`docs/design/**`、`frontend/**`
- **writeScope**：`frontend/src/features/stats/**`
- **对应 AC**：AC-STAT-01..04（UI 部分）、AC-UI-01/06
- **验证**：同 T08。

### T14 `account-password` 修改本人口令（implement，**条件必需：仅当 D4① 纳入时**；不纳入则删除此任务，AC-AUTH-10 标记为 NOT_APPLICABLE）
- **目标**：后端实现 `PUT /api/auth/password`，包括校验旧口令和新口令策略，保留当前会话；前端实现 ChangePasswordPage，并在菜单中增加入口（菜单入口已由 T02 在 D4① 纳入时预置）。
- **dependsOn**：T01、T02
- **readScope**：`docs/design/**`、`backend/**`、`frontend/**`
- **writeScope**：`backend/src/main/java/com/example/library/auth/**`、`backend/src/test/java/com/example/library/auth/**`（T01 已完成，所有权移交给 T14）；`security/SecurityConfig.java` 中只能确认已有的 `/api/auth/password` 规则（T01 已预置，原则上不需要修改）；`frontend/src/features/account/**`
- **对应 AC**：AC-AUTH-10
- **验证**：`mvn verify` 通过；前端 `test` 通过。

### T12 `e2e-smoke` 端到端冒烟（implement，必需）
- **目标**：按设计第 13 节的 7 步冒烟流程编写 Playwright 用例；完善 `playwright.config.ts`：E2E 端口 18096/15196、临时数据库、dev 演示数据、失败时保留 trace。
- **dependsOn**：T01、T02、T03、T04、T05、T06、T07、T08、T09、T10、T11，以及 T14（若纳入）
- **readScope**：全仓库
- **writeScope**：`frontend/e2e/**`、`frontend/playwright.config.ts`
- **对应 AC/DoD**：DOD-05；AC-AUTH-01/07/09、AC-LOAN-01/04/05/07、AC-SRCH-02、AC-STAT-04、AC-UI-04/06（E2E 证据）
- **验证**：`npm --prefix frontend run e2e` 全部通过；运行前后检查开发数据库 `backend/data/library.db`，确认没有被修改（若文件存在，比较修改时间）。
- **发现缺陷时**：不在本任务内修改 feature 代码。把缺陷清单交给 T13 处理；如果缺陷属于契约层面，返回主会话。

### T13 `final-integration` 最终集成（integrate，必需）
- **目标**：
  1. 在干净状态下执行全量验证：`bash scripts/test-all.sh`，以及按 README 从零执行 `bash scripts/dev.sh` 并手工冒烟。
  2. 修复**集成缺陷**（跨任务的接线、配置、类型不一致等），每处修复都要记录原因和影响到的 AC。如果修复会改变业务行为或契约，返回主会话。
  3. 编写根目录 `README.md`，满足 DOD-07。
  4. 执行 `npm audit --omit=dev --audit-level=high`。
  5. 汇总各任务证据，填写 `docs/verification/ac-dod-matrix.md`，满足 DOD-09，并记录候选快照（源码树的文件清单哈希加时间戳）。
- **dependsOn**：T01–T12，以及 T14（若纳入）
- **readScope**：全仓库
- **writeScope**：`README.md`、`docs/verification/**`；集成修复可以写入任何应用路径，但必须逐项列出。禁止修改已有的 Flyway 迁移文件，也禁止在未经批准的情况下新增依赖。
- **对应 DoD**：DOD-01..09 的 IMPL 阶段证据（DOD-10/11/12 由评审和验收环节完成）
- **验证**：test-all.sh 退出码为 0；dev.sh 能启动并完成登录冒烟；矩阵中没有空白项；所有 FAIL、NOT_RUN 的条目都在报告中单独列出。
- **交付**：候选版本加上矩阵，交回主会话，由主会话安排独立评审（REV），评审通过后再安排独立验收（ACC）。

---

## 4. 依赖图与批次

```
批次1（并行3）： T01 be-foundation │ T02 fe-foundation │ T03 root-scripts
                    │                      │
批次2（并行5）： T04 │ T05 │ T06 │ T07  (←T01)      T08 (←T02)
批次3（并行≤4）：T09 │ T10 │ T11 (←T02)   [+ T14 (←T01,T02)，仅 D4①]
批次4（串行）：  T12 e2e-smoke (←T01..T11[,T14])
批次5（串行）：  T13 final-integration (←全部必需任务)
```

**同批次无冲突的依据**
- 批次 1：T01 只写 `backend/**`，T02 只写 `frontend/**`，T03 只写 `scripts/**` 和 `.gitignore`。三个任务都只读设计文档，互不读取对方的产物。依赖下载用的是不同的缓存（`~/.m2` 和 npm 缓存）。
- 批次 2：T04–T07 各自写不同的 feature 包。可以新增方法的 Repository 文件按所有权严格分开（T04 的 3 个、T05 的 2 个、T06 的 1 个、T07 不改任何 Repository）。共享的 ErrorCode、SecurityConfig、迁移文件和测试支撑都是只读。集成测试各用各的临时数据库。T08 只写 `frontend/src/features/catalog/**`。注意：T04–T07 都会**读取** T01 产出的共享文件，并会同时执行 `mvn verify` 编译整个 backend。每个任务应在**独立的 worktree 或工作副本**中开发，由主会话在批次结束时合并，或者按序串行合并；合并后在主工作副本里再运行一次 `mvn verify`。如果无法隔离工作副本，批次 2 的后端任务应改为串行执行（T04→T05→T06→T07），T08 仍可与它们并行。
- 批次 3：T09、T10、T11 写不同的 feature 目录。T14 写 `auth/**`（T01 已完成）和 `features/account/**`，与其他任务不重叠。前端任务都会运行 `lint`、`typecheck`、`test`，这些命令会扫描整个 frontend；同样建议使用独立工作副本，否则同批次任务的半成品可能导致别人的检查失败（这属于结果误报，不是写冲突）。
- 并行度：每个批次最多 5 个任务。

**关键路径**：T01 → T06 → T12 → T13。T06 规则最多，又包含并发测试，建议分配最多的时间。

---

## 5. 失败与回退

| 情况 | 处理 |
|---|---|
| T01 两轮内仍无法解决 Boot 4 / SQLite 方言问题 | 返回 BLOCKED，附错误证据，由主会话转给用户决定是否改选 D3-B（3.5.x）。改选后，T01 的 pom 和测试注解需要重做，其余设计不变 |
| 某个任务发现契约缺陷（字段、错误码、规则不可行） | 返回 NEEDS_INPUT，附修订建议。由主会话决定是否修订 design 或 AC 的版本。修订会使依赖该契约的已完成任务的证据失效，需要按影响范围重新验证 |
| 需要新增依赖 | 返回 NEEDS_INPUT（说明理由和替代方案），未经用户批准不得安装 |
| 网络或依赖下载失败 | 最多重试 2 次；确实无法下载的，返回 BLOCKED，并说明是哪个具体坐标或包 |
| 并发测试不稳定 | 不允许用 @Disabled 或放宽断言来规避，应查明根因。如果根因是设计中的连接池或事务假设，返回主会话 |
