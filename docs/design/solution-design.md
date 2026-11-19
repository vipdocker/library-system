# 图书馆管理系统首版：技术方案

| 项 | 值 |
|---|---|
| 文档版本 | design-v0.1（**草案，待用户确认**） |
| 任务 | lib-mgmt-20261117/design-r1 |
| 日期 | 2026-11-17 |
| 配套文档 | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/ac-dod.md`（AC/DoD 草案 v0.1）、`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/implementation-plan.md`（实施拆解 plan-v1） |
| 状态说明 | 本文件中标注【待确认 Dx】的条目引用第 14 节的决定项，括号里是推荐默认值。用户确认前，这些值只是推荐，不算已经批准。 |

---

## 0. 范围

**首版范围（用户已确认）**
1. 图书与读者管理：分类、图书（书目）增删改查、副本/库存，以及读者档案。
2. 借阅与归还：借书、还书、续借、借阅上限、逾期判定。
3. 检索与统计：按书名、作者、ISBN 检索；借阅统计；逾期报表。
4. 两个角色：管理员（ADMIN）和读者（READER）。系统要求登录，并做权限控制。

**非目标**：预约排队；罚金；部署、发布和运维；SSO 或第三方登录；邮件和短信；多馆或多租户；原生 App；读者自助注册；多管理员管理；CSV 或 Excel 导出；登录限流和验证码；HTTPS（只在本机运行）。

**事实、假设与待确认事项**
- 已验证事实（2026-11-17 只读检查）：见第 1 节。
- 假设：“rreact”是笔误，指 React【待确认 D7，不阻塞】；系统只在单机本地运行，只有一个后端进程。
- 待确认事项：见第 14 节。

---

## 1. 环境约束（已核实）

| 检查项 | 结果 | 方案处理 |
|---|---|---|
| 默认 `java` | 1.8.0_421（x86_64） | 不使用。所有脚本都显式设置 `JAVA_HOME`。 |
| Homebrew JDK | `openjdk@21` 21.0.12，以及 23、24、26 | **固定使用 JDK 21 LTS**：`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`。实测 `JAVA_HOME=<该路径> mvn -v` 显示 Java 21.0.12。不用 JDK 26，因为 Mockito/ByteBuddy 这类字节码库对最新 JDK 的支持经常滞后。 |
| Maven | 3.9.16（/opt/homebrew/bin/mvn） | 使用这个系统 Maven。pom 中加入 `maven-enforcer-plugin`，要求 `requireJavaVersion [21,)`，Java 8 或 17 会直接构建失败。同时设置 `maven.compiler.release=21`。 |
| Node / npm | /opt/homebrew/bin/node v26.7.0，npm 11.19.0 | 脚本用 `#!/usr/bin/env bash`，不加载 zsh/nvm，并把 `/opt/homebrew/bin` 放到 PATH 最前面。README 里说明：交互式 zsh 中 nvm 懒加载已损坏，请用 `scripts/*.sh` 或 `/opt/homebrew/bin/npm`。 |
| sqlite3 CLI | `/opt/local/bin/sqlite3`（MacPorts）存在 | **方案不依赖它**。应用只通过 JDBC 访问数据库，这个 CLI 只可作为排错工具。 |
| 本地 Maven 缓存 | 已有 Spring Boot BOM 3.5.16 和 4.1.2、hibernate-core 7.4.13、sqlite-jdbc 3.53.4.0 等 | 开发时仍需联网补齐缺失的依赖，比如 `hibernate-community-dialects:7.4.13.Final`、`spring-boot-flyway`，需要用户授权【D6】。 |
| Playwright 浏览器缓存 | `~/Library/Caches/ms-playwright/chromium-1161/1200/1208/1254` | 安装 `@playwright/test` 时，尽量选一个对应已缓存 chromium 的版本，避免再下载浏览器。如果仍需下载，也要授权【D6】。 |
| 端口 | **8080、5173、18080 已被其他进程占用**；18095 和 15195 在检查时空闲 | 默认后端 `18095`，前端开发服务器 `15195`（strictPort）。E2E 使用 `18096` 和 `15196`。端口都可以用环境变量覆盖【D5】。 |
| 平台 | macOS 25.6.0 arm64 | — |

Spring Boot 版本线：截至 2026-10，Spring Boot 3.5.x 已结束 OSS 支持；4.0.x 将在 2026-12-31 结束支持；4.1.x 支持到 2027-07（来源：endoflife.date、spring.io support policy）。因此推荐 **Spring Boot 4.1.x（当前 4.1.2）**【待确认 D3】。

---

## 2. 总体架构

```
浏览器 (React SPA, Vite dev server :15195)
   │  同源请求 /api/**（Vite 代理到 :18095），Cookie: JSESSIONID + XSRF-TOKEN
   ▼
Spring Boot 4.1 (Java 21, :18095)
   ├─ security  : Spring Security 7，基于 Session，CSRF(SPA 模式)，URL+方法级授权
   ├─ auth      : 登录/登出/当前用户/CSRF 引导
   ├─ catalog   : 分类、图书、副本
   ├─ reader    : 读者档案与读者账号
   ├─ loan      : 借/还/续借、逾期判定（LoanPolicy）
   ├─ stats     : 概览、借阅趋势、热门图书、逾期报表（只读 SQL）
   ├─ domain    : JPA 实体 + Repository（共享数据层）
   └─ common    : 错误码/异常映射、分页、Clock、配置属性、ISBN 工具
   │  JDBC（HikariCP, maximumPoolSize=1）
   ▼
SQLite 文件 backend/data/library.db（WAL、foreign_keys=ON、busy_timeout=5000），Flyway 管理 schema
```

**关键决策**

| # | 决策 | 理由 | 放弃的方案 |
|---|---|---|---|
| K1 | 两个进程开发：Vite 开发服务器把 `/api` 代理到后端 | 浏览器看到的是同源请求，所以不需要 CORS，Cookie Session 和 CSRF 也比较简单；前后端可以各自测试。 | 把前端打进 jar 单进程运行。这要引入 frontend-maven-plugin，还会耦合构建。它不是首版必需项，可在后续版本做。 |
| K2 | Session + Cookie + CSRF，不用 JWT | 不需要额外 JWT 库；HttpOnly Cookie 可以防止 XSS 窃取凭据；登出时 Session 立即失效。 | JWT：需要处理吊销、续期和存储位置，首版没有分布式需求。 |
| K3 | Spring Data JPA + Hibernate 社区 SQLiteDialect；关键并发写操作使用显式条件 UPDATE | CRUD 开发效率高；用条件 UPDATE 和部分唯一索引保证并发正确性，不依赖 SQLite 不支持的 `SELECT ... FOR UPDATE`。 | 纯 JdbcClient：样板代码多。MyBatis：多一套映射。 |
| K4 | 时间显式转换：Instant 存为 INTEGER（epoch 毫秒），LocalDate 存为 TEXT `YYYY-MM-DD` | 不依赖方言默认的日期映射；ISO 日期按字典序比较就是按日期先后比较，统计 SQL 可以直接用 `substr`。 | 依赖 Hibernate 对 SQLite 的默认时间映射，格式不确定。 |
| K5 | Hikari 连接池大小 = 1，`open-in-view=false` | SQLite 同一时间只允许一个写者。单连接把应用内写操作串行化，从根本上避免 `SQLITE_BUSY` 和锁升级死锁。如果不关闭 open-in-view，连接会在整个请求期间被占住，单连接时会出问题。 | 多连接配合 `BEGIN IMMEDIATE`：更复杂，本地单用户场景用不上。 |
| K6 | Flyway 管理 schema，JPA 设置 `ddl-auto=validate` | schema 可评审、可重放；启动时校验实体和表结构是否一致。Flyway 自带 SQLite 支持，不需要额外的数据库模块。 | `ddl-auto=update`：结果不可控。 |
| K7 | 注入 `java.time.Clock`（时区可配置，默认 Asia/Shanghai） | 逾期判定和统计可以确定性测试。 | 直接调用 `LocalDate.now()`：无法测试。 |
| K8 | 前端用 Ant Design + TanStack Query | 现成的表格、表单、空态、加载组件，配合查询缓存和错误状态，能覆盖要求的全部页面状态。 | 手写 UI：工作量大，状态覆盖容易不一致。 |

---

## 3. 目录结构（由实现环节创建；当前工作区为空）

```
/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/
├─ README.md                         # T13
├─ .gitignore                        # T03（含 backend/data/、frontend/node_modules/、dist、target、test-results）
├─ scripts/                          # T03
│  ├─ env.sh                         # 设置并校验 JAVA_HOME(21)/PATH(/opt/homebrew/bin)/Node(>=20.19)
│  ├─ dev.sh                         # 一键启动后端(dev profile)+前端，Ctrl-C 同时退出
│  ├─ test-all.sh                    # 后端 verify + 前端 lint/typecheck/test/build + e2e
│  └─ reset-db.sh                    # 停机状态下删除 backend/data/library.db*（需确认提示）
├─ docs/design/                      # 本环节产物
├─ docs/verification/ac-dod-matrix.md # 实现/评审/验收环节回填证据
├─ backend/
│  ├─ pom.xml
│  └─ src/
│     ├─ main/java/com/example/library/
│     │  ├─ LibraryApplication.java
│     │  ├─ common/   (config/LibraryProperties, ClockConfig; error/ErrorCode, ApiException, ErrorResponse, GlobalExceptionHandler; web/PageResponse, PageRequestParams; util/IsbnUtils, LikeEscaper)
│     │  ├─ security/ (SecurityConfig, JsonAuthenticationEntryPoint, JsonAccessDeniedHandler, LibraryUserDetailsService, LibraryUserPrincipal, CurrentUser, AdminBootstrap)
│     │  ├─ auth/     (AuthController, dto/*)
│     │  ├─ domain/   (entity/*, enums/*, repository/*, converter/EpochMillisInstantConverter, converter/IsoLocalDateConverter)
│     │  ├─ catalog/  (CategoryController/Service, BookController/Service, CopyController/Service, dto/*)
│     │  ├─ reader/   (ReaderController/Service, dto/*)
│     │  ├─ loan/     (LoanController, LoanService, LoanPolicy, LoanViewMapper, dto/*)
│     │  ├─ stats/    (StatsController, StatsService, StatsQueryRepository[JdbcClient], dto/*)
│     │  └─ seed/     (DemoDataLoader)
│     ├─ main/resources/ (application.yml, application-dev.yml, db/migration/V1__init_schema.sql)
│     ├─ test/java/com/example/library/ (support/*, auth/*, security/*, catalog/*, reader/*, loan/*, stats/*, domain/*, common/*)
│     └─ test/resources/application-test.yml
└─ frontend/
   ├─ package.json, package-lock.json, index.html, vite.config.ts, tsconfig*.json, eslint.config.js, vitest.setup.ts, playwright.config.ts
   ├─ e2e/*.spec.ts
   └─ src/
      ├─ main.tsx, App.tsx
      ├─ app/ (router.tsx 全部路由, menu.ts, layouts/AppLayout.tsx, RootRedirect.tsx)
      ├─ auth/ (AuthContext.tsx, RequireAuth.tsx, RequireRole.tsx, LoginPage.tsx)
      ├─ api/ (client.ts, types.ts 全部 DTO, errors.ts 错误码→中文, queryClient.ts)
      ├─ components/ (PageLoading, PageError, EmptyState, ForbiddenPage, NotFoundPage, Pagination 辅助)
      └─ features/
         ├─ catalog/ (api.ts, pages/BookSearchPage, BookDetailPage, AdminBookListPage, BookFormPage, CategoryPage, __tests__)
         ├─ readers/ (api.ts, pages/AdminReaderListPage, ReaderDetailPage, MyProfilePage, __tests__)
         ├─ circulation/ (api.ts, pages/CirculationDeskPage, AdminLoanListPage, MyLoansPage, __tests__)
         ├─ stats/ (api.ts, pages/DashboardPage, StatsPage, OverdueReportPage, __tests__)
         └─ account/ (仅 D4 选择包含“修改本人密码”时：pages/ChangePasswordPage)
```

---

## 4. 技术栈与依赖（推荐；安装需用户授权【D6】）

**后端**（`spring-boot-starter-parent` 4.1.x，当前 4.1.2；版本由 BOM 管理）
| 依赖 | 用途 | 备注 |
|---|---|---|
| spring-boot-starter-webmvc | REST | Boot 4 模块化后的 starter 名称，本地缓存可以印证。 |
| spring-boot-starter-security | 认证授权 | Spring Security 7 |
| spring-boot-starter-validation | Bean Validation | |
| spring-boot-starter-data-jpa | JPA / Hibernate 7.4 | |
| spring-boot-starter-flyway | 迁移 | Boot 4 中 Flyway 自动配置在独立模块里，Flyway 本身自带 SQLite 支持。 |
| org.xerial:sqlite-jdbc | JDBC 驱动 | BOM 管理（4.1.2 → 3.53.4.0） |
| org.hibernate.orm:hibernate-community-dialects | `org.hibernate.community.dialect.SQLiteDialect` | 版本跟 `${hibernate.version}` 保持一致 |
| 测试：spring-boot-starter-test、spring-boot-starter-webmvc-test、spring-boot-starter-security-test | JUnit 5 / MockMvc / AssertJ | |
| 插件：maven-enforcer-plugin（Java ≥21、Maven ≥3.9）、jacoco-maven-plugin（只出报告） | | |

> Boot 4 需要注意的变化：Jackson 3（包名 `tools.jackson`）、测试注解所在包有变化、starter 改名。实现环节应以实际 BOM 为准逐项核实。如果 D3 选择 3.5.x，则改用 `spring-boot-starter-web`、Hibernate 6.6.53、Flyway 11.7.2，其他设计不变。

**前端**（安装时锁定为当时的最新稳定版，并写入 package-lock.json）
| 依赖 | 主版本 | 用途 |
|---|---|---|
| react / react-dom | 19.x | UI |
| react-router | 7.x | 路由（声明式模式） |
| antd + @ant-design/icons（+ dayjs） | 支持 React 19 的当前稳定主版本；如果是 antd 5.x，还要加 `@ant-design/v5-patch-for-react-19` | 组件库，zh_CN 语言包 |
| @tanstack/react-query | 5.x | 请求状态与缓存 |
| 开发：vite 7.x、@vitejs/plugin-react、typescript 5.x、vitest、@testing-library/react、@testing-library/user-event、@testing-library/jest-dom、jsdom、eslint 9 + typescript-eslint + eslint-plugin-react-hooks、@playwright/test | | 构建、测试、E2E |

`package.json` 中声明 `"engines": {"node": ">=20.19"}`。不使用 CSS 框架、状态管理库或 axios，自己封装 fetch。

---

## 5. 配置与运行

**后端 `application.yml`（关键项）**
```yaml
server:
  port: ${SERVER_PORT:18095}
  error: { include-stacktrace: never, include-message: never }
  servlet.session: { timeout: 30m, cookie: { http-only: true, same-site: lax } }
spring:
  profiles.default: dev
  datasource:
    url: jdbc:sqlite:${LIB_DB_PATH:./data/library.db}?foreign_keys=true&busy_timeout=5000&journal_mode=WAL
    driver-class-name: org.sqlite.JDBC
    hikari: { maximum-pool-size: 1, connection-timeout: 10000 }
  jpa:
    open-in-view: false
    hibernate.ddl-auto: validate
    database-platform: org.hibernate.community.dialect.SQLiteDialect
  flyway: { enabled: true, locations: classpath:db/migration }
lib:
  timezone: ${LIB_TIMEZONE:Asia/Shanghai}        # 【D1】
  loan:
    max-active-loans: ${LIB_MAX_ACTIVE_LOANS:5}   # 【D1】
    loan-days: ${LIB_LOAN_DAYS:30}                # 【D1】
    max-renewals: ${LIB_MAX_RENEWALS:1}           # 【D1】
    renew-days: ${LIB_RENEW_DAYS:30}              # 【D1】续借从当前应还日顺延
    reader-self-renew: ${LIB_READER_SELF_RENEW:false} # 【D2】
  admin:
    username: ${LIB_ADMIN_USERNAME:admin}
    initial-password: ${LIB_ADMIN_PASSWORD:}      # 为空时：dev profile 使用开发默认值并 WARN；非 dev 启动失败
  seed.demo: false                                 # application-dev.yml 中设为 true
```
- 后端工作目录是 `backend/`，所以数据库默认位于 `backend/data/library.db`。启动时自动创建父目录，SQLite 不会自己建目录。`backend/data/` 已在 `.gitignore` 中。
- `LibraryProperties` 使用 `@Validated`，阈值必须 ≥1（`max-renewals` 可以为 0），配置非法时启动失败。
- 测试 profile：`lib.seed.demo=false`，关闭 AdminBootstrap（`lib.admin.bootstrap=false`），每个测试 JVM 使用一个临时文件数据库（`@DynamicPropertySource`），每个测试前清表。

**前端 `vite.config.ts`**：`server.port=Number(process.env.FRONTEND_PORT ?? 15195)`，`strictPort: true`；`proxy['/api'] → process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:18095'`（`changeOrigin: false`）。

**命令约定（脚本和 README 必须一致；这些也是任务之间的契约）**
| 目的 | 命令 |
|---|---|
| 环境 | `source scripts/env.sh`（设置 `JAVA_HOME=${LIB_JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}` 和 `PATH=$JAVA_HOME/bin:/opt/homebrew/bin:$PATH`；Java 主版本 <21 或 Node <20.19 时报错，退出码非 0） |
| 一键开发启动 | `bash scripts/dev.sh`：后端 `mvn -f backend/pom.xml spring-boot:run`（dev profile）；轮询 `GET /api/auth/csrf`，最多等 120s；就绪后执行 `npm --prefix frontend run dev`；trap 负责清理子进程 |
| 后端构建与测试 | `mvn -f backend/pom.xml verify` |
| 前端安装 | `npm --prefix frontend ci` |
| 前端检查 | `npm --prefix frontend run lint`、`run typecheck`、`run test`（vitest run）、`run build` |
| E2E | `npm --prefix frontend run e2e`（Playwright webServer 自动启动 e2e 后端 :18096，使用临时 DB 和 dev 演示数据；前端 :15196） |
| 全量验证 | `bash scripts/test-all.sh` |

---

## 6. 数据模型（Flyway `V1__init_schema.sql`）

通用约定：主键使用 `INTEGER PRIMARY KEY AUTOINCREMENT`；`*_at` 为 INTEGER epoch 毫秒（UTC）；`*_date` 为 TEXT `YYYY-MM-DD`，按 `lib.timezone` 取业务日期；布尔值为 INTEGER 0/1，并加 CHECK；枚举为 TEXT，并加 CHECK；外键默认 `ON DELETE RESTRICT`。

```sql
CREATE TABLE app_user (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  username      TEXT    NOT NULL COLLATE NOCASE UNIQUE,          -- ^[A-Za-z0-9_.-]{3,32}$
  password_hash TEXT    NOT NULL,                                -- {bcrypt}$2a$...
  role          TEXT    NOT NULL CHECK (role IN ('ADMIN','READER')),
  enabled       INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1)),
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL
);

CREATE TABLE reader (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id    INTEGER NOT NULL UNIQUE REFERENCES app_user(id),
  card_no    TEXT    NOT NULL UNIQUE,                            -- 系统生成 R%06d(id)，不可修改
  name       TEXT    NOT NULL,                                   -- 1..50
  phone      TEXT,                                               -- ≤20, ^[0-9+\- ]*$
  email      TEXT,                                               -- ≤100, Email
  status     TEXT    NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX ix_reader_name ON reader(name);

CREATE TABLE category (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT NOT NULL COLLATE NOCASE UNIQUE,               -- 1..50
  description TEXT,                                              -- ≤200
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

CREATE TABLE book (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  isbn         TEXT NOT NULL UNIQUE,                             -- 规范化后的 10/13 位，无连字符，X 大写
  title        TEXT NOT NULL,                                    -- 1..200
  author       TEXT NOT NULL,                                    -- 1..100
  publisher    TEXT,                                             -- ≤100
  publish_year INTEGER CHECK (publish_year IS NULL OR publish_year BETWEEN 1000 AND 9999),
  category_id  INTEGER REFERENCES category(id),
  description  TEXT,                                             -- ≤2000
  created_at   INTEGER NOT NULL,
  updated_at   INTEGER NOT NULL
);
CREATE INDEX ix_book_title    ON book(title COLLATE NOCASE);
CREATE INDEX ix_book_author   ON book(author COLLATE NOCASE);
CREATE INDEX ix_book_category ON book(category_id);

CREATE TABLE book_copy (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  book_id    INTEGER NOT NULL REFERENCES book(id),
  barcode    TEXT    NOT NULL UNIQUE,                            -- 系统生成 C%08d(id)，不可修改
  status     TEXT    NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','BORROWED','UNAVAILABLE')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX ix_copy_book_status ON book_copy(book_id, status);

CREATE TABLE loan (
  id                 INTEGER PRIMARY KEY AUTOINCREMENT,
  copy_id            INTEGER NOT NULL REFERENCES book_copy(id),
  reader_id          INTEGER NOT NULL REFERENCES reader(id),
  borrowed_at        INTEGER NOT NULL,
  borrow_date        TEXT    NOT NULL,
  due_date           TEXT    NOT NULL,
  returned_at        INTEGER,
  return_date        TEXT,
  renew_count        INTEGER NOT NULL DEFAULT 0 CHECK (renew_count >= 0),
  borrow_operator_id INTEGER NOT NULL REFERENCES app_user(id),
  return_operator_id INTEGER REFERENCES app_user(id),
  last_renew_operator_id INTEGER REFERENCES app_user(id),
  CHECK ((returned_at IS NULL) = (return_date IS NULL)),
  CHECK (due_date >= borrow_date)
);
CREATE UNIQUE INDEX ux_loan_active_copy ON loan(copy_id) WHERE returned_at IS NULL;  -- 一个副本至多 1 条在借
CREATE INDEX ix_loan_reader_active ON loan(reader_id, returned_at);
CREATE INDEX ix_loan_due_active    ON loan(due_date) WHERE returned_at IS NULL;
CREATE INDEX ix_loan_borrow_date   ON loan(borrow_date);
CREATE INDEX ix_loan_return_date   ON loan(return_date);
```

**派生值（不落库）**
- 图书 `totalCopies` = 副本数；`availableCopies` = `status='AVAILABLE'` 的副本数（用聚合查询，不维护冗余计数）。
- 借阅 `status`：`RETURNED`（returned_at 非空）/ `OVERDUE`（未还且 `today > due_date`）/ `ACTIVE`。
- `overdueDays`：未还时为 `max(0, today - due_date)`；已还时为 `max(0, return_date - due_date)`。

**生成码**：`card_no` 和 `barcode` 先写入临时唯一值 `PENDING-<UUID>`，拿到自增 id 后，在同一事务内改为 `R%06d` / `C%08d`。两者都是不可修改的业务标识。

**读者与账号状态同步**：`reader.status` 是唯一的事实来源。读者服务在同一事务中同步 `app_user.enabled`（ACTIVE→1，DISABLED→0）。

**数据迁移**：首版只有 V1。之后的 schema 变更只能新增 `V{n}__*.sql`，禁止修改已发布的迁移文件。数据库可以通过 `scripts/reset-db.sh` 重建；首版没有需要兼容的历史数据。

---

## 7. 认证与权限

**机制**：Spring Security 7，基于 Session（`JSESSIONID`，HttpOnly，SameSite=Lax，空闲 30 分钟超时）。`DelegatingPasswordEncoder` 默认用 BCrypt，强度 10。不使用 formLogin 或 httpBasic，改由 JSON 登录接口完成认证：`AuthenticationManager.authenticate`，再通过 `HttpSessionSecurityContextRepository` 保存上下文。登录成功后执行 `changeSessionId()`，防止会话固定攻击。

**CSRF**：`CookieCsrfTokenRepository.withHttpOnlyFalse()`，Cookie 名 `XSRF-TOKEN`，请求头 `X-XSRF-TOKEN`，配合 SPA 请求处理器（Security 6.4+ 的 `csrf.spa()` 或等价配置）。前端启动时调用 `GET /api/auth/csrf`，让服务端下发 Token；所有非 GET 请求都带上这个头。登录也受 CSRF 保护。认证成功后 Token 会轮换，前端每次请求都从 Cookie 重新读取。

**错误响应**：未认证返回 401 `AUTH_REQUIRED`，不重定向；无权限返回 403 `FORBIDDEN`；CSRF 失败返回 403 `CSRF_INVALID`。三种情况都由自定义 EntryPoint/AccessDeniedHandler 输出统一的 JSON 结构。

**口令策略**：8–64 位，至少包含一个字母和一个数字。不得在任何响应或日志中输出口令或哈希。登录失败只记录用户名和原因类别，日志级别 WARN。

**授权矩阵**（URL 规则写在 SecurityConfig，按顺序匹配，未匹配的一律拒绝；服务层再对读者数据做归属校验）

| 路径 | 匿名 | READER | ADMIN |
|---|---|---|---|
| `GET /api/auth/csrf`、`POST /api/auth/login` | ✔ | ✔ | ✔ |
| `GET /api/auth/me`、`POST /api/auth/logout` | 401 | ✔ | ✔ |
| `PUT /api/auth/password`（仅当 D4 选择包含时） | 401 | ✔ | ✔ |
| `GET /api/categories`、`GET /api/books`、`GET /api/books/{id}` | 401 | ✔ | ✔ |
| `GET /api/readers/me`、`GET /api/loans/me` | 401 | ✔（只能看本人） | 403 |
| `POST /api/loans/{id}/renew` | 401 | D2=允许时只能续借本人的借阅，否则 403 | ✔ |
| 其余 `/api/**`（分类/图书/副本的写操作、`/api/books/{id}/copies`、`/api/copies/**`、`/api/readers/**`、`/api/loans/**`、`/api/stats/**`、`/api/reports/**`） | 401 | 403 | ✔ |
| 非 `/api/**` | 404 / 拒绝 | | |

**防越权（IDOR）**：读者接口不接收 readerId 参数，读者身份只从 `LibraryUserPrincipal` 中取得。读者自助续借时，服务层校验 `loan.reader.userId == principal.userId`，不匹配时返回 404 `LOAN_NOT_FOUND`，避免泄露该借阅是否存在。

**初始管理员（AdminBootstrap）**：启动时如果不存在 ADMIN 用户就创建一个，用户名取 `lib.admin.username`。口令取 `LIB_ADMIN_PASSWORD`；如果为空且当前是 dev profile，使用开发默认口令 `Admin@12345`【D5】，并输出 WARN“正在使用开发默认口令”；如果为空且不是 dev profile，启动失败。重复启动是幂等的，不会覆盖已有口令。

---

## 8. REST API 契约（/api，JSON UTF-8）

### 8.1 通用约定
- 分页参数 `page`（≥0，默认 0）、`size`（1–100，默认 20）；排序 `sort=field,asc|desc`，字段只能取各接口白名单中的值，越界或非法时返回 400 `VALIDATION_FAILED`。
- 分页响应：`{ "items": [...], "page": 0, "size": 20, "totalItems": 123, "totalPages": 7 }`。
- 日期：`YYYY-MM-DD`；时间戳：ISO-8601 UTC 字符串（如 `2026-11-17T08:00:00Z`）；空值输出为 `null`，不省略字段。
- 字符串入参先去掉首尾空白；去空白后为空的可选字段按 `null` 处理。
- 错误响应：
```json
{ "code": "LOAN_LIMIT_EXCEEDED", "message": "读者在借数量已达上限（5）", "status": 409,
  "path": "/api/loans", "timestamp": "2026-11-17T08:00:00Z",
  "fieldErrors": [ { "field": "isbn", "message": "ISBN 校验位错误" } ],
  "details": { "limit": 5, "current": 5 } }
```
`fieldErrors` 只在 400 校验错误时出现，否则为 `[]`；`details` 可以为空对象 `{}`。

### 8.2 错误码（`ErrorCode` 枚举，全部由 T01 定义）

| code | HTTP | 场景 |
|---|---|---|
| VALIDATION_FAILED | 400 | 参数、格式、长度、排序白名单、统计区间非法 |
| AUTH_REQUIRED | 401 | 未登录或会话过期 |
| AUTH_INVALID_CREDENTIALS | 401 | 用户名不存在或口令错误（提示统一，不区分哪一项错） |
| AUTH_ACCOUNT_DISABLED | 401 | 口令正确但账号已停用 |
| AUTH_PASSWORD_MISMATCH | 400 | 修改口令时旧口令错误（仅 D4 包含时） |
| FORBIDDEN | 403 | 角色不足 |
| CSRF_INVALID | 403 | CSRF Token 缺失或错误 |
| CATEGORY_NOT_FOUND / BOOK_NOT_FOUND / COPY_NOT_FOUND / READER_NOT_FOUND / LOAN_NOT_FOUND / ACTIVE_LOAN_NOT_FOUND | 404 | 资源不存在；读者访问他人借阅也返回 LOAN_NOT_FOUND；按条码还书时找不到在借记录返回 ACTIVE_LOAN_NOT_FOUND |
| READER_PROFILE_NOT_FOUND | 404 | READER 账号缺少读者档案（数据异常时的兜底） |
| CATEGORY_NAME_DUPLICATE / ISBN_DUPLICATE / USERNAME_DUPLICATE | 409 | 唯一冲突（分类名和用户名比较时忽略大小写） |
| CATEGORY_IN_USE | 409 | 删除仍被图书引用的分类 |
| BOOK_HAS_ACTIVE_LOANS / BOOK_HAS_LOAN_HISTORY | 409 | 删除有在借记录或有借阅历史的图书 |
| COPY_ON_LOAN / COPY_HAS_LOAN_HISTORY | 409 | 修改在借副本的状态，或删除有借阅历史的副本 |
| READER_HAS_ACTIVE_LOANS / READER_HAS_LOAN_HISTORY | 409 | 删除有在借记录或有借阅历史的读者（应改为停用） |
| READER_DISABLED | 409 | 停用的读者借书或续借 |
| READER_HAS_OVERDUE | 409 | 读者有逾期未还记录，不能借书或续借 |
| LOAN_LIMIT_EXCEEDED | 409 | 在借数量 ≥ 上限 |
| DUPLICATE_BOOK_LOAN | 409 | 读者已经在借同一书目的另一个副本（D4 选择包含该规则时） |
| COPY_NOT_AVAILABLE | 409 | 副本不可借（已借出或不可用），包括并发竞争失败 |
| LOAN_ALREADY_RETURNED | 409 | 重复还书，或续借已还的借阅 |
| LOAN_OVERDUE | 409 | 逾期的借阅不能续借 |
| RENEW_LIMIT_REACHED | 409 | 续借次数已达上限 |
| DB_BUSY | 503 | 获取数据库连接超时或 SQLITE_BUSY（可重试） |
| METHOD_NOT_ALLOWED | 405 | 请求方法不被该端点支持（修订 R1，2026-11-18 用户批准，见 approval-record §1.1） |
| INTERNAL_ERROR | 500 | 未预期错误；响应不含堆栈和内部信息，日志记录完整异常 |

### 8.3 DTO 定义（前端 `src/api/types.ts` 必须与此一致）
```
UserInfo        { id, username, role: "ADMIN"|"READER", displayName, readerId: number|null, cardNo: string|null }
Category        { id, name, description|null, bookCount }
BookSummary     { id, isbn, title, author, publisher|null, publishYear|null, category: {id,name}|null, totalCopies, availableCopies }
BookDetail      BookSummary + { description|null, createdAt, updatedAt }
Copy            { id, bookId, barcode, status: "AVAILABLE"|"BORROWED"|"UNAVAILABLE", createdAt }
Reader          { id, cardNo, name, phone|null, email|null, status: "ACTIVE"|"DISABLED", username,
                  activeLoanCount, overdueLoanCount, createdAt }
Loan            { id, reader: {id, cardNo, name}, copy: {id, barcode}, book: {id, title, author, isbn},
                  borrowDate, dueDate, returnDate|null, renewCount, maxRenewals,
                  status: "ACTIVE"|"OVERDUE"|"RETURNED", overdueDays, renewable }
StatsOverview   { totalBooks, totalCopies, availableCopies, borrowedCopies, unavailableCopies,
                  totalReaders, activeReaders, activeLoans, overdueLoans, borrowsToday, returnsToday }
LoanSeriesPoint { period: "YYYY-MM-DD"|"YYYY-MM", borrowCount, returnCount }
TopBook         { bookId, title, author, isbn, borrowCount }
OverdueItem     { loanId, reader: {id, cardNo, name, phone|null}, book: {id, title, isbn}, copyBarcode,
                  borrowDate, dueDate, overdueDays }
```
`renewable` 表示“当前调用者在当前时间点能否续借”，只是给 UI 的提示，服务端仍会完整校验。

### 8.4 接口清单

**认证（auth）**
| 方法 路径 | 请求 | 成功 | 主要错误 |
|---|---|---|---|
| GET /api/auth/csrf | — | 204，下发 XSRF-TOKEN Cookie（同时作为就绪探针） | — |
| POST /api/auth/login | `{username, password}` | 200 `UserInfo`，设置会话 | 400、401 AUTH_INVALID_CREDENTIALS / AUTH_ACCOUNT_DISABLED、403 CSRF_INVALID |
| POST /api/auth/logout | — | 204，会话失效 | 401 |
| GET /api/auth/me | — | 200 `UserInfo` | 401 |
| PUT /api/auth/password（仅当 D4 包含） | `{oldPassword, newPassword}` | 204，当前会话保留 | 400 VALIDATION_FAILED / AUTH_PASSWORD_MISMATCH |

**分类（catalog）**：排序白名单 `name`
| GET /api/categories | — | 200 `Category[]`（不分页，按名称排序） | |
|---|---|---|---|
| POST /api/categories | `{name, description?}` | 201 `Category` | 400、409 CATEGORY_NAME_DUPLICATE |
| PUT /api/categories/{id} | 同上 | 200 | 404、409 |
| DELETE /api/categories/{id} | — | 204 | 404、409 CATEGORY_IN_USE |

**图书与副本（catalog）**
| 方法 路径 | 请求 | 成功 | 主要错误 |
|---|---|---|---|
| GET /api/books | `q?`（≤100）、`field=all\|title\|author\|isbn`（默认 all）、`categoryId?`、`availableOnly?`（bool）、page/size、`sort`（白名单：title、author、publishYear、createdAt；默认 title,asc） | 200 `Page<BookSummary>` | 400 |
| GET /api/books/{id} | — | 200 `BookDetail` | 404 |
| POST /api/books | `{isbn, title, author, publisher?, publishYear?, categoryId?, description?, initialCopies? (0..50, 默认 1)}` | 201 `BookDetail` | 400、404 CATEGORY_NOT_FOUND、409 ISBN_DUPLICATE |
| PUT /api/books/{id} | 同上，不含 initialCopies | 200 `BookDetail` | 400、404、409 |
| DELETE /api/books/{id} | — | 204（同时删除其所有副本） | 404、409 BOOK_HAS_ACTIVE_LOANS / BOOK_HAS_LOAN_HISTORY |
| GET /api/books/{id}/copies | — | 200 `Copy[]`（ADMIN） | 404 |
| POST /api/books/{id}/copies | `{count: 1..50}` | 201 `Copy[]`（新建的副本） | 400、404 |
| PATCH /api/copies/{id} | `{status: "AVAILABLE"\|"UNAVAILABLE"}` | 200 `Copy` | 400（传入 BORROWED 时）、404、409 COPY_ON_LOAN |
| DELETE /api/copies/{id} | — | 204 | 404、409 COPY_ON_LOAN / COPY_HAS_LOAN_HISTORY |

**检索语义**：
- `title` / `author`：子串匹配，`lower(col) LIKE lower(?) ESCAPE '\'`；`%`、`_`、`\` 先经 `LikeEscaper` 转义，按字面匹配。
- `isbn`：去掉空格和连字符、X 转大写后精确匹配；规范化后不是 10 位或 13 位时返回空结果，不报错。
- `all`：title OR author 子串，或 isbn 精确匹配。
- `q` 为空时列出全部，可配合 `categoryId` 过滤。

**ISBN 校验**：去掉连字符和空格后，必须是合法的 ISBN-10（末位可为 X）或 ISBN-13，并通过校验位检查【D4 附带默认：严格校验】。ISBN-10 不转换成 13 位，按原样存储。

**读者（reader）**：排序白名单 `name`、`cardNo`、`createdAt`
| 方法 路径 | 请求 | 成功 | 主要错误 |
|---|---|---|---|
| GET /api/readers | `q?`（匹配姓名子串、卡号精确、用户名精确）、`status?`、page/size/sort | 200 `Page<Reader>` | 400 |
| GET /api/readers/{id} | — | 200 `Reader` | 404 |
| POST /api/readers | `{name, phone?, email?, username, initialPassword}` | 201 `Reader`（同时创建 READER 账号，卡号由系统生成） | 400、409 USERNAME_DUPLICATE |
| PUT /api/readers/{id} | `{name, phone?, email?, status}` | 200 `Reader`（停用时同时禁用账号） | 400、404 |
| PUT /api/readers/{id}/password | `{newPassword}` | 204（管理员重置口令） | 400、404 |
| DELETE /api/readers/{id} | — | 204（同时删除账号） | 404、409 READER_HAS_ACTIVE_LOANS / READER_HAS_LOAN_HISTORY |
| GET /api/readers/me | — | 200 `Reader`（READER 本人） | 404 READER_PROFILE_NOT_FOUND |

已停用读者的会话：下一次请求时由 `LibraryUserDetailsService`/会话校验发现账号已禁用，返回 401。最低要求是：重新登录会被拒绝，借书和续借会被拒绝。

**借阅（loan）**：排序白名单 `borrowDate`、`dueDate`（默认 borrowDate,desc）
| 方法 路径 | 请求 | 成功 | 主要错误 |
|---|---|---|---|
| POST /api/loans | `{readerCardNo, copyBarcode}` | 201 `Loan` | 400、404 READER_NOT_FOUND / COPY_NOT_FOUND、409（见 9.2） |
| POST /api/loans/{id}/return | — | 200 `Loan`（status=RETURNED，逾期时 overdueDays>0） | 404、409 LOAN_ALREADY_RETURNED |
| POST /api/loans/return-by-barcode | `{copyBarcode}` | 200 `Loan` | 404 COPY_NOT_FOUND / ACTIVE_LOAN_NOT_FOUND |
| POST /api/loans/{id}/renew | — | 200 `Loan` | 403（读者且 D2=否）、404、409 LOAN_ALREADY_RETURNED / LOAN_OVERDUE / RENEW_LIMIT_REACHED / READER_DISABLED / READER_HAS_OVERDUE |
| GET /api/loans | `readerId?`、`status?=ACTIVE\|OVERDUE\|RETURNED\|CURRENT`（CURRENT = ACTIVE+OVERDUE）、`copyBarcode?`、`q?`（书名子串）、page/size/sort | 200 `Page<Loan>` | 400 |
| GET /api/loans/me | `status?`（同上）、page/size | 200 `Page<Loan>`（只含本人） | — |

**统计与报表（stats）**：仅 ADMIN
| 方法 路径 | 请求 | 成功 | 主要错误 |
|---|---|---|---|
| GET /api/stats/overview | — | 200 `StatsOverview` | — |
| GET /api/stats/loans | `from`、`to`（含两端，YYYY-MM-DD，默认最近 30 天）、`granularity=DAY\|MONTH`（默认 DAY） | 200 `LoanSeriesPoint[]`（区间内每个周期都有一项，没有数据的周期填 0） | 400（from>to，或跨度 >366 天） |
| GET /api/stats/top-books | `from`、`to`、`limit`（1..50，默认 10） | 200 `TopBook[]`（按 borrowCount 降序，相同时按 title 升序） | 400 |
| GET /api/reports/overdue | page/size、`sort=overdueDays,desc`（白名单 overdueDays、dueDate） | 200 `Page<OverdueItem>` | 400 |

统计口径：`borrowCount` 是 borrow_date 落在该周期内的借阅条数（包括之后已归还的）；`returnCount` 是 return_date 落在该周期内的条数；续借不计入借阅次数。

---

## 9. 借阅业务规则

### 9.1 参数（推荐默认值【待确认 D1/D2/D4】）
| 规则 | 默认 | 配置键 |
|---|---|---|
| 每位读者同时在借上限 | 5 本 | lib.loan.max-active-loans |
| 借期 | 30 天，`due_date = borrow_date + 30`，应还日当天仍算按期 | lib.loan.loan-days |
| 续借次数上限 | 1 次 | lib.loan.max-renewals |
| 续借顺延 | 从**当前应还日**顺延 30 天 | lib.loan.renew-days |
| 读者自助续借 | 不允许，只能由管理员操作 | lib.loan.reader-self-renew |
| 同一书目多副本同时在借 | 禁止（DUPLICATE_BOOK_LOAN） | 固定规则（D4） |
| 逾期判定 | 未还且 `today(lib.timezone) > due_date` | lib.timezone=Asia/Shanghai |
| 停用读者 | 不能登录、借书、续借；可以由管理员还书 | — |

### 9.2 借书 `LoanService.borrow(readerCardNo, copyBarcode, operator)`（单事务）
按以下顺序校验，命中第一条即返回，这样错误码是确定的：
1. 按卡号找读者 → 404 READER_NOT_FOUND
2. 读者为 DISABLED → 409 READER_DISABLED
3. 读者存在逾期未还记录 → 409 READER_HAS_OVERDUE
4. 读者在借数 ≥ 上限 → 409 LOAN_LIMIT_EXCEEDED（details: limit、current）
5. 按条码找副本 → 404 COPY_NOT_FOUND
6. 副本状态不是 AVAILABLE → 409 COPY_NOT_AVAILABLE
7. 读者已在借同一书目的另一个副本 → 409 DUPLICATE_BOOK_LOAN
8. **原子占用**：执行 `UPDATE book_copy SET status='BORROWED', updated_at=? WHERE id=? AND status='AVAILABLE'`。如果受影响行数不是 1，返回 409 COPY_NOT_AVAILABLE。
9. 插入 loan（borrow_date=today，due_date=today+loanDays，renew_count=0，borrow_operator_id=operator）。如果违反唯一索引 `ux_loan_active_copy`，捕获 `DataIntegrityViolationException`，转成 409 COPY_NOT_AVAILABLE，事务回滚。
10. 输出 INFO 日志 `loan.borrow loanId= readerId= copyId= operatorId=`。

任何一步失败都整体回滚，不留下部分写入。

### 9.3 还书 `return(loanId | copyBarcode, operator)`
1. 找借阅 → 404；`returned_at` 非空 → 409 LOAN_ALREADY_RETURNED。
2. **原子关闭**：执行 `UPDATE loan SET returned_at=?, return_date=?, return_operator_id=? WHERE id=? AND returned_at IS NULL`。受影响行数为 0 时返回 409 LOAN_ALREADY_RETURNED，这对应并发重复还书的情况。
3. 执行 `UPDATE book_copy SET status='AVAILABLE' WHERE id=? AND status='BORROWED'`。行数为 0 说明数据不一致，记录 WARN 日志，但不阻断还书。
4. 逾期还书照常完成，响应中的 overdueDays>0（首版没有罚金）。

### 9.4 续借 `renew(loanId, actor)`
校验顺序：
1. 借阅存在（读者只能续借本人的借阅，否则返回 404 LOAN_NOT_FOUND）。
2. 未归还（LOAN_ALREADY_RETURNED）。
3. 读者是 ACTIVE（READER_DISABLED）。
4. 本条借阅没有逾期（LOAN_OVERDUE）。
5. 读者没有其他逾期借阅（READER_HAS_OVERDUE）。
6. `renew_count < max`（RENEW_LIMIT_REACHED）。

更新时执行 `UPDATE loan SET due_date=?, renew_count=renew_count+1, last_renew_operator_id=? WHERE id=? AND returned_at IS NULL AND renew_count=?`，用乐观条件防止并发重复续借；行数为 0 时重新读取，并返回对应错误码。

### 9.5 并发与一致性
- 第一层：Hikari 只有 1 个连接，应用内所有事务串行执行。“检查在借数 → 插入”这类读后写操作因此不会交错，借阅上限也就不会被并发突破。
- 第二层（与连接池大小无关）：条件 UPDATE + `ux_loan_active_copy` 部分唯一索引，保证同一副本最多只有一条在借记录。
- 等待连接超过 10s 或出现 SQLITE_BUSY 时，返回 503 DB_BUSY。服务端不自动重试写操作；前端提示“系统繁忙，请稍后再试”，由用户手动重试。
- 禁止 `REQUIRES_NEW` 嵌套事务：单连接下会自我死锁。这一点写入代码约定，评审时检查。
- 读者删除和图书删除都先检查借阅历史，并在同一事务中执行。外键 RESTRICT 是兜底，违反时转成对应的 409。

### 9.6 LoanPolicy（纯函数，便于单元测试）
`LoanPolicy(LoanProperties, Clock)` 提供：`today()`、`dueDateFor(borrowDate)`、`renewedDueDate(currentDue)`、`isOverdue(loan, today)`、`overdueDays(loan, today)`、`statusOf(loan, today)`、`renewable(loan, reader, hasOtherOverdue, today)`。

---

## 10. 前端设计

### 10.1 路由（T02 一次性注册全部路由；各页面组件由对应任务实现）
| 路由 | 角色 | 页面（文件） | 负责任务 |
|---|---|---|---|
| /login | 匿名 | auth/LoginPage | T02 |
| / | 已登录 | 按角色重定向：ADMIN → /admin/dashboard，READER → /books | T02 |
| /books | 两者 | features/catalog/pages/BookSearchPage | T08 |
| /books/:id | 两者 | features/catalog/pages/BookDetailPage | T08 |
| /my/loans | READER | features/circulation/pages/MyLoansPage | T10 |
| /my/profile | READER | features/readers/pages/MyProfilePage | T09 |
| /account/password | 两者（仅当 D4 包含） | features/account/pages/ChangePasswordPage | T14 |
| /admin/dashboard | ADMIN | features/stats/pages/DashboardPage | T11 |
| /admin/books、/admin/books/new、/admin/books/:id/edit | ADMIN | features/catalog/pages/AdminBookListPage、BookFormPage（编辑页内含副本管理） | T08 |
| /admin/categories | ADMIN | features/catalog/pages/CategoryPage | T08 |
| /admin/readers、/admin/readers/:id | ADMIN | features/readers/pages/AdminReaderListPage、ReaderDetailPage（含该读者的借阅） | T09 |
| /admin/circulation | ADMIN | features/circulation/pages/CirculationDeskPage | T10 |
| /admin/loans | ADMIN | features/circulation/pages/AdminLoanListPage | T10 |
| /admin/reports/overdue | ADMIN | features/stats/pages/OverdueReportPage | T11 |
| /admin/stats | ADMIN | features/stats/pages/StatsPage | T11 |
| /403、* | — | components/ForbiddenPage、NotFoundPage | T02 |

### 10.2 页面状态规范（所有数据页都要实现）
| 状态 | 表现 |
|---|---|
| 加载 | 首次加载显示 Skeleton/Spin；翻页或筛选时保留旧数据，表格显示 loading |
| 空态 | 用 `Empty` 加引导文案。检索无结果时提示“没有找到匹配的图书”；管理员列表为空时提供“新建”按钮 |
| 错误 | 用 `Alert` 显示错误码对应的中文文案，并提供“重试”按钮（调用 refetch）；503 显示“系统繁忙” |
| 无权限 | 路由层面：`RequireRole` 角色不符时跳转 /403；接口返回 403 时提示“无权限执行该操作”，不清除登录态 |
| 未登录 / 会话过期 | `RequireAuth` 跳转 `/login?redirect=<原路径>`；任何接口返回 401 时，`client.ts` 触发全局事件，清空 Query 缓存并跳转登录页，提示“登录已过期，请重新登录”；登录后回到原路径。redirect 只接受站内相对路径 |
| 提交中 | 按钮显示 loading 并禁用，防止重复提交；删除和停用需要 Popconfirm 二次确认 |
| 表单校验 | 前端校验规则与第 8 节长度、格式一致；服务端返回的 `fieldErrors` 映射到对应表单项 |
| 业务失败 | 借、还、续借的 409 用 `errors.ts` 映射成中文，显示在柜台结果区；成功时显示借阅摘要（应还日等） |

### 10.3 关键页面要点
- **流通柜台**：分“借书 / 还书”两个 Tab。借书 Tab 输入读者卡号和副本条码，回车即提交；成功后清空条码输入框并保持焦点，方便连续扫码；下方显示该读者当前的在借列表，可在列表中续借。还书 Tab 输入条码后提交，显示归还结果和逾期天数。
- **我的借阅（读者）**：两个 Tab，“当前借阅”（status=CURRENT）和“历史”。逾期行用红色 Tag 标出逾期天数。只有当 D2=允许且 `renewable=true` 时显示续借按钮。
- **检索页**：搜索框配合字段选择（全部/书名/作者/ISBN）、分类筛选和“仅显示可借”，结果分页；`q` 等参数写入 URL query，刷新后检索条件不丢失。
- **仪表盘**：概览数字卡片，以及最近 7 天借还趋势（antd 表格或简单的 CSS 条形；**不引入图表库**）和逾期数快捷入口。

### 10.4 API 客户端（client.ts）
- 封装 fetch，使用 `credentials: 'same-origin'`。非 GET 请求从 Cookie 读取 `XSRF-TOKEN`，设置到 `X-XSRF-TOKEN` 头。
- 非 2xx 响应统一解析成 `ApiError {status, code, message, fieldErrors, details}`。响应不是 JSON 或网络失败时，code 为 `NETWORK_ERROR`。
- 应用启动时先请求 `/api/auth/csrf`，再请求 `/api/auth/me`，以确定登录状态。
- 遇到 403 `CSRF_INVALID`：重新获取一次 CSRF Token，然后**只对幂等请求自动重试 1 次**；非幂等请求不自动重试，直接提示用户。

---

## 11. 种子数据

- **测试 profile**：没有任何种子数据，测试自己构造。
- **dev profile**（`lib.seed.demo=true`，只在 book 表为空时执行，所以幂等）：
  - 管理员：admin，口令见第 7 节。
  - 分类 5 个；图书约 12 本，ISBN 必须能通过校验位检查，并有单元测试断言这一点；每本 1–3 个副本。
  - 读者 3 个：`reader1/Reader@123`（ACTIVE，1 条在借）、`reader2/Reader@123`（ACTIVE，1 条逾期：借于 today-40，应还 today-10）、`reader3/Reader@123`（DISABLED）。
  - 另有若干已归还的历史借阅，分布在最近 60 天，用于统计页展示。
  - 演示数据直接通过 Repository 写入，同时把对应副本置为 BORROWED，保证数据一致。
  - 所有演示账号和口令都写在 README 中，并注明只用于本地开发。

---

## 12. 可观测性、日志与审计
- 日志：控制台输出，INFO 级别。借、还、续借事件记录 loanId、readerId、copyId、operatorId；登录失败记录 WARN（用户名、原因类别）；未预期异常记录 ERROR 和堆栈（只写日志，不进响应）。禁止记录口令、哈希、Session ID 和 CSRF Token。
- 审计字段：`loan.borrow_operator_id / return_operator_id / last_renew_operator_id`，所有表都有 `created_at/updated_at`。首版不做独立的审计表。
- 就绪探针：`GET /api/auth/csrf` 返回 204。不引入 actuator。

---

## 13. 测试策略

| 层次 | 工具 | 范围 | 负责 |
|---|---|---|---|
| 后端单元 | JUnit 5 + AssertJ | LoanPolicy（借期、续借顺延、逾期边界：应还日当天/次日、时区边界 23:59/00:00 Asia/Shanghai）、IsbnUtils（合法/非法/X/连字符）、LikeEscaper | T01、T06 |
| 后端集成 | @SpringBootTest + MockMvc + spring-security-test；临时文件 SQLite；共享上下文，每个测试前清表；`MutableClock` 作为 @Primary Clock | 每个接口的成功和主要失败路径；**授权矩阵测试**（参数化：匿名/READER/ADMIN × 关键端点 → 401/403/2xx）；CSRF 缺失时返回 403；IDOR（读者 A 续借读者 B 的借阅返回 404，`/me` 只返回本人数据）；口令以 `{bcrypt}` 存储，任何响应 JSON 都不含 password 字段；Flyway 能在空库上迁移、`ddl-auto=validate` 通过；种子数据幂等 | 各后端任务 |
| 并发 | ExecutorService + CountDownLatch，直接调用 Service（每个线程独立事务） | 10 个线程借同一副本：恰好 1 个成功，其余返回 COPY_NOT_AVAILABLE，数据库中该副本在借记录为 1；同一读者并发借 8 个不同副本（上限 5）：最终成功数 ≤5；并发重复还书：只有 1 个成功 | T06 |
| 前端单元/组件 | Vitest + RTL + jsdom；用 `vi.mock` 模拟各 feature 的 api.ts | client.ts（CSRF 头、错误解析、401 事件）；RequireAuth/RequireRole；LoginPage；errors.ts 映射完整（与 ErrorCode 列表一致的快照测试）；每个 feature 至少 1 个列表页覆盖加载、空态、错误重试、有数据四种状态，再加 1 个表单提交（含 fieldErrors） | T02、T08–T11 |
| E2E | Playwright chromium（headless），webServer 启动独立的后端（临时 DB + dev 演示数据，:18096）和前端（:15196） | 冒烟流程：①管理员登录→新建分类和图书（2 副本）→新建读者；②流通柜台借书→看到应还日；③读者登录→检索到该书→“我的借阅”能看到这条记录→访问 /admin/books 跳到 403；④管理员续借→还书；⑤逾期报表包含演示数据中的 reader2；⑥统计页加载；⑦登出后访问受保护页面跳到登录页 | T12 |
| 静态检查 | ESLint、`tsc --noEmit`；Maven 编译时不允许警告升级为错误（不强制） | | T02、T01 |
| 安全检查 | `npm audit --omit=dev --audit-level=high`；人工评审依赖版本是否在支持期内 | | T13 / 评审 |

测试命名约定：涉及 AC 的测试在 `@DisplayName` 或测试名中带 AC 编号（如 `AC-LOAN-03`），方便回填核验矩阵。

---

## 14. 需要用户决定的事项（均不阻塞方案定稿；但开发派发前需要确认）

| 编号 | 问题 | 选项 | 推荐与理由 | 影响 | 阻塞？ |
|---|---|---|---|---|---|
| D1 | 借阅规则阈值 | A. 上限 5 本 / 借期 30 天 / 续借 1 次，从应还日顺延 30 天（推荐）；B. 上限 10 / 借期 30 / 续借 2 次；C. 自定义 | A：常见的公共馆规则，测试用例数量适中；所有值都可以通过配置修改 | AC-LOAN-01/02/05/06 的具体数值 | 不阻塞设计；阻塞 T06 的最终断言（可以先用默认值实现，改值只需改配置和测试常量） |
| D2 | 读者能否自助续借 | A. 只能由管理员续借（推荐，与已确认需求“管理员管借还、读者查看本人借阅”最一致）；B. 允许读者自助续借本人借阅 | A：严格遵循已确认范围。选 B 只需打开配置开关，并增加 IDOR 测试 | AC-LOAN-05、AC-AUTH-05、AC-UI-05 | 不阻塞 |
| D3 | Spring Boot 版本线 | A. 4.1.x（推荐）；B. 3.5.x | A：3.5 已结束 OSS 支持，4.1 支持到 2027-07。代价是 Boot 4 / Jackson 3 的新写法，实现环节需要核实。B 的生态资料更多，但已过支持期 | 依赖坐标、测试注解 | 不阻塞设计；阻塞 T01 开工 |
| D4 | 是否纳入以下小项（多选） | ①“修改本人口令”（T14，推荐纳入：dev 默认口令和管理员设置的初始口令需要用户能自行修改）；②同一读者禁止同时借同一书目的多个副本（推荐纳入）；③ISBN 严格校验位检查（推荐纳入） | 三项成本都很低，能降低安全和数据质量风险；但都超出用户原话的字面范围，所以需要确认 | ① 增加 T14、AC-AUTH-10；② AC-LOAN-02 子项；③ AC-BOOK-01 子项 | 不阻塞 |
| D5 | 本机运行参数 | 端口：后端 18095 / 前端 15195（8080、5173、18080 已被占用）；dev 默认管理员 `admin / Admin@12345`（可以用 LIB_ADMIN_PASSWORD 覆盖）；时区 Asia/Shanghai | 推荐全部接受 | DoD-02/06、README | 不阻塞 |
| D6 | 开发环节的依赖安装授权 | 授权开发环节联网执行 `mvn`（下载 Maven 依赖）、`npm ci/install`，必要时执行 `npx playwright install chromium`；依赖范围限于第 4 节清单 | 没有这项授权就无法构建 | 全部实现任务 | **阻塞开发派发**（不阻塞方案审阅） |
| D7 | “rreact”是否就是 React | 是 / 否 | 推荐“是” | 技术栈 | 不阻塞 |

另外，以下取舍随方案一并请用户确认（默认按此执行）：不能删除有借阅历史的图书、副本或读者，应改为停用或设为不可用；卡号和条码由系统生成，不可修改；首版不支持导出、多管理员和读者自助注册。

---

## 15. 风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| Boot 4 / Spring Security 7 / Jackson 3 的 API 变化，实现模型可能按旧版本写代码 | 编译失败或行为偏差 | T01 先打通“登录 + 一个 CRUD + 迁移”的最小纵向链路，以实际 BOM 和编译结果为准；如果在 2 轮内无法解决，回退到 D3-B，需经主会话确认 |
| hibernate-community-dialects 的 SQLite 方言在 Hibernate 7.4 上的兼容性 | 启动或查询异常 | 时间类型用显式 Converter；关键写操作用显式 JPQL/原生 SQL；T01 的验收条件包括 `ddl-auto=validate` 通过和 Repository 冒烟测试 |
| 单连接池成为吞吐瓶颈 | 多人同时使用时变慢 | 本地单机场景可以接受；配置项保留；文档写明不要在不改用 `BEGIN IMMEDIATE` 方案的情况下调大连接池 |
| 端口在开发时被其他进程占用 | 启动失败 | strictPort 会让启动直接报错，日志清楚；端口可以用环境变量覆盖；dev.sh 启动前检测端口并提示 |
| 交互式 shell 中 nvm 已损坏 | 用户直接执行 npm 时报错 | 脚本固定使用 /opt/homebrew/bin；README 中写明 |
| Playwright 版本与已缓存的浏览器不匹配 | 需要联网下载浏览器 | 优先选一个与已缓存 chromium build 对应的版本；如需下载，要取得 D6 授权 |
| dev 默认口令 | 本机弱口令 | 只在 dev profile 生效并输出 WARN；推荐纳入 D4①；首版不做部署 |
| 登录没有限流 | 可能被暴力破解 | 首版只在本机运行，作为已知限制写入 README |
