# 图书馆管理系统（本地开发版）

单机本地运行的图书馆管理系统首版：分类 / 图书 / 副本管理、读者管理、借书 / 还书 / 续借、检索、借阅统计与逾期报表，以及持久化站内借阅提醒与管理员催还记录。两个角色：管理员（ADMIN）与读者（READER）。

- 后端：Spring Boot 4.1（Java 21）+ Spring Data JPA + Flyway + SQLite（文件数据库）
- 前端：Vite + React 19 + TypeScript + Ant Design + TanStack Query
- 契约文档：`docs/design/solution-design.md`（design-v0.1-R1）、`docs/design/ac-dod.md`（AC/DoD v1）
- 基础核验矩阵：`docs/verification/ac-dod-matrix.md`（历史证据）
- 借阅提醒增量契约：`docs/design/reminder-increment.md`（AC/DoD v1.1、reminder-plan-v1.5）；本次矩阵：`docs/verification/reminder-ac-dod-matrix.md`，未提交候选快照：`docs/verification/reminder-candidate-snapshot.txt`。开发自测不代表独立评审、验收或上线通过。

> **非目标（首版不做）**：预约排队、罚金、部署 / 发布 / 运维、SSO 或第三方登录、邮件和短信、多馆 / 多租户、原生 App、读者自助注册、多管理员管理、CSV / Excel 导出、登录限流和验证码、HTTPS。系统只在本机运行。

---

## 1. 前置条件（本机已核实的环境约束）

| 依赖 | 要求 | 本机位置 / 说明 |
|---|---|---|
| JDK | **21 及以上**（固定使用 Homebrew JDK 21 LTS） | `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` |
| Maven | ≥ 3.9 | `/opt/homebrew/bin/mvn`（3.9.16） |
| Node / npm | Node ≥ 20.19 | `/opt/homebrew/bin/node`（v26.7.0）、`/opt/homebrew/bin/npm`（11.19.0） |

**必须注意的两件事：**

1. **默认 `java` 是 1.8，不能用。** 本项目所有脚本都会显式设置 `JAVA_HOME` 指向上面的 JDK 21，并把 `/opt/homebrew/bin` 放到 `PATH` 最前。后端 pom 配置了 maven-enforcer（要求 Java ≥ 21）：如果你绕过脚本、直接用 Java 8 或 17 跑 `mvn`，构会在 enforcer 处**明确报错退出**，这是预期行为。手工执行 Maven 时请先 `source scripts/env.sh`（在 **bash** 中），或显式加 `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`。
2. **交互式 zsh 里的 nvm 懒加载已损坏。** 不要依赖 `nvm`、也不要直接敲 `npm`（可能命中损坏的 shim）。请使用本项目脚本，或直接用绝对路径 `/opt/homebrew/bin/npm`。脚本不加载 zsh/nvm，不修改任何全局配置。

**所有脚本必须用 bash 运行**（脚本按 bash 编写，兼容 macOS 自带 bash 3.2）：

```bash
bash scripts/dev.sh        # ✔ 正确
bash scripts/test-all.sh   # ✔ 正确
```

不要在 zsh 里 `source scripts/dev.sh` 或 `zsh scripts/dev.sh`（会失败）。唯一按设计需要 `source` 的是 `scripts/env.sh`，且应在 **bash** 中执行：`bash -c 'source scripts/env.sh && mvn -f backend/pom.xml verify'`。

## 2. 安装

```bash
# 前端依赖（锁文件安装；后端依赖由 Maven 在首次构建时自动下载）
/opt/homebrew/bin/npm --prefix frontend ci
```

E2E 需要 Playwright chromium；本机已有缓存（`~/Library/Caches/ms-playwright/`）。如缺失，执行 `/opt/homebrew/bin/npm --prefix frontend exec -- playwright install chromium`（需联网）。

## 3. 启动（一键开发）

```bash
bash scripts/dev.sh
```

行为：先检查端口占用 → 启动后端（dev profile，`mvn spring-boot:run`）→ 轮询 `GET /api/auth/csrf`（最多等 120 秒）→ 就绪后启动前端开发服务器。**数据库文件不存在时会自动创建父目录、建库并完成 Flyway 迁移**，dev profile 同时写入演示数据（幂等，重启不重复）。

- 前端地址：<http://localhost:15195>（浏览器打开即可登录）
- 后端地址：<http://localhost:18095>（前端通过 Vite 代理同源访问 `/api`）
- 停止：在运行 `dev.sh` 的终端按 **Ctrl-C**，脚本会同时清理后端与前端子进程。
- 端口被占用时脚本会给出明确提示（列出占用进程），可用环境变量换端口后重试。

### 冒烟流程（验收可按此操作）

1. 管理员 `admin / Admin@12345` 登录 → 分类管理新建分类 → 图书管理新建图书（2 个副本）→ 读者管理新建读者（记下角色卡号与初始口令）。
2. 流通柜台：输入读者卡号 + 副本条码回车借书 → 显示应还日（当天 + 30 天）。
3. 退出登录，用新读者（或演示读者 `reader1 / Reader@123`）登录 → 图书检索找到该书（可借数量已减 1）→ “我的借阅”看到这条记录 → 访问 `/admin/books` 显示 403 页。
4. 退出，管理员登录 → 流通柜台对该借阅续借（应还日顺延 30 天，仅限 1 次）→ 还书 Tab 输入条码还书。
5. 逾期报表：包含演示读者 `reader2` 的逾期借阅（逾期 ≥ 10 天）。
6. 仪表盘与借阅统计页正常加载（日 / 月粒度可切换）。
7. 登出后直接访问受保护页面 → 跳登录页；重新登录后回到原页面。

## 4. 测试与检查命令

```bash
# 全量验证（后端 verify → 前端 lint/typecheck/test/build → E2E，任一步失败即停止）
bash scripts/test-all.sh

# 分步执行（先 source scripts/env.sh，或用绝对路径）
mvn -f backend/pom.xml verify                 # 后端全部单元/集成/并发测试 + JaCoCo 报告
/opt/homebrew/bin/npm --prefix frontend run lint
/opt/homebrew/bin/npm --prefix frontend run typecheck
/opt/homebrew/bin/npm --prefix frontend run test     # vitest run
/opt/homebrew/bin/npm --prefix frontend run build
/opt/homebrew/bin/npm --prefix frontend run e2e      # Playwright chromium headless
```

E2E 使用**独立端口 18096 / 15196 和系统临时目录数据库**（Playwright webServer 自动启动），不影响开发数据库；**不要与 `dev.sh` 同时运行**。测试一律使用临时数据库，不会写 `backend/data/library.db`。标准命令包含原冒烟与借阅提醒回归；Playwright **仅在后端 E2E 子进程**设置 `LIB_REMINDER_SCANINTERVAL=PT1S`，无需手工扫描参数，生产默认仍为 `PT1H`，test profile 禁用自动扫描。临时 E2E 库可能保留用于复现，不要把它当成开发库。

生产依赖审计（不含开发依赖，不替代代码安全评审）：

```bash
/opt/homebrew/bin/npm --prefix frontend audit --omit=dev --audit-level=high
```

## 5. 端口与环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `SERVER_PORT` | `18095` | 后端端口（8080 已被其他进程占用，勿用） |
| `FRONTEND_PORT` | `15195` | 前端开发服务器端口（strictPort；5173 已被占用） |
| `VITE_API_PROXY_TARGET` | `http://localhost:18095` | 前端 `/api` 代理目标 |
| `LIB_JAVA_HOME` | Homebrew JDK 21 路径 | 覆盖脚本使用的 JDK |
| `LIB_DEV_READY_TIMEOUT` | `120` | dev.sh 等待后端就绪的秒数 |
| `LIB_DB_PATH` | `./data/library.db`（相对后端工作目录 `backend/`） | SQLite 数据库文件路径；绝对路径按原样使用 |
| `LIB_TIMEZONE` | `Asia/Shanghai` | 业务时区（逾期判定、统计口径） |
| `LIB_ADMIN_USERNAME` | `admin` | 首次启动创建的管理员用户名 |
| `LIB_ADMIN_PASSWORD` | 空 | 初始管理员口令；为空且 dev profile 时用开发默认口令并输出 WARN；为空且非 dev profile 时启动失败 |
| `E2E_BACKEND_PORT` / `E2E_FRONTEND_PORT` | `18096` / `15196` | E2E 专用端口 |

## 6. 业务规则配置（用户已确认的 D1 / D2）

| 规则 | 默认值 | 配置键（环境变量） |
|---|---|---|
| 每位读者同时在借上限 | 5 本 | `lib.loan.max-active-loans`（`LIB_MAX_ACTIVE_LOANS`） |
| 借期 | 30 天（应还日当天仍算按期） | `lib.loan.loan-days`（`LIB_LOAN_DAYS`） |
| 续借次数上限 | 1 次 | `lib.loan.max-renewals`（`LIB_MAX_RENEWALS`） |
| 续借顺延 | 从当前应还日顺延 30 天 | `lib.loan.renew-days`（`LIB_RENEW_DAYS`） |
| 读者自助续借 | **不允许**（只能管理员操作） | `lib.loan.reader-self-renew`（`LIB_READER_SELF_RENEW`，默认 `false`） |
| 同一书目多副本同时在借 | 禁止（`DUPLICATE_BOOK_LOAN`） | 固定规则（D4②） |
| ISBN 校验位 | 严格校验（D4③） | 固定规则 |

修改环境变量后重启后端即生效（阈值必须 ≥ 1，`max-renewals` 可以为 0，非法配置会启动失败）。

### 6.1 借阅提醒配置与规则

| 配置键 | 默认值 | 环境变量（Spring relaxed binding） |
|---|---|---|
| `lib.reminder.enabled` | `true` | `LIB_REMINDER_ENABLED` |
| `lib.reminder.due-soon-days` | `3` | `LIB_REMINDER_DUESOONDAYS` |
| `lib.reminder.overdue-repeat-days` | `7` | `LIB_REMINDER_OVERDUEREPEATDAYS` |
| `lib.reminder.scan-interval` | `PT1H`（一小时） | `LIB_REMINDER_SCANINTERVAL` |
| `lib.reminder.scan-on-startup` | `true` | `LIB_REMINDER_SCANONSTARTUP` |

天数必须 ≥ 1；扫描间隔为正数 ISO-8601 Duration，至少可表示为 1 毫秒，非法配置启动失败（即使禁用扫描也校验）。启动补扫在演示数据提交之后进行；test profile 关闭调度及启动补扫。

- Asia/Shanghai 应还日 D 当天仍按期。默认 D-3 生成一次到期提醒；错过扫描时 D-2 到 D 首次补发一次，D+1 不补到期前提醒。
- 逾期第 1 / 8 / 15 天进入新周期，每期最多一条；停机恢复只补当前周期，不补历史周期。重复扫描 / 重启使用数据库唯一键去重；数据库繁忙记录 `reminder.scan.busy`，后续扫描再尝试。
- 续借按新应还日重算，旧通知及已读状态保留；通知显示生成时应还日、当前应还日与当前借阅状态。归还后停止新增自动提醒，已有通知保留。停用读者仍生成通知，重新启用并登录后可查看。
- 读者“我的提醒”支持分页、仅看未读、单条 / 全部标已读；角标在打开 / 切换页面、标记已读时刷新，**没有后台角标轮询**。
- 管理员从逾期报表“登记催还”记录电话 / 当面 / 站内 / 其他方式，备注可空、最多 500 字，仅管理员可见。登记在同一事务中生成固定站内通知，不包含备注。催还仅限逾期未还借阅；“催还记录”页查看历史与未结案 / 已结案案件。按借阅号或条码归还均自动结案，结案时间等于归还时间，历史保留。
- **站内提醒仅能触达登录系统的读者**；已读仅表示读者在系统里标记过，不证明离线送达。系统不发送短信、邮件或电话；“电话”只是管理员记录已采用的人工方式，不代表系统已拨打电话。登记失败（包括 503）需用户显式重试，系统不自动重试催还提交。

增量冒烟：使用下节临时库启动 → 管理员查看 reader2 的逾期报表并登记一次催还（可留空备注）→ “催还记录”查看未结案历史 → 退出后 reader2 登录“我的提醒”，确认逾期 / 催还通知与未读角标，标已读 → 管理员按该条码归还 → “催还记录”筛选已结案，读者旧通知显示已归还。此操作会改变所选库，**只在一次性临时库执行，不在现有开发库操作演示借阅**。

## 7. 默认账号（仅用于本地开发，勿用于任何真实环境）

| 账号 | 口令 | 角色 / 状态 |
|---|---|---|
| `admin` | `Admin@12345`（可被 `LIB_ADMIN_PASSWORD` 覆盖） | 管理员 |
| `reader1` | `Reader@123` | 读者，ACTIVE，1 条在借 |
| `reader2` | `Reader@123` | 读者，ACTIVE，1 条逾期（借于 40 天前，应还 10 天前） |
| `reader3` | `Reader@123` | 读者，**DISABLED**（用于演示停用后无法登录） |

登录后可在“修改口令”页修改本人口令（管理员与读者都可）。管理员可在读者详情中重置读者口令。

## 8. 数据库位置与重置

- 位置：`backend/data/library.db`（SQLite，WAL 模式；伴随 `-wal` / `-shm` 文件）。已在 `.gitignore` 中。
- 重置（**先停止后端**，脚本检测到端口仍在监听会拒绝执行）：

```bash
bash scripts/reset-db.sh          # 交互确认后删除
bash scripts/reset-db.sh --yes    # 跳过确认
```

下次启动会自动重建数据库、完成迁移并重新写入演示数据。也可以用下面的隔离启动方式在不动开发库的情况下开一个全新实例；先确认 18097 / 15197 空闲（若占用则选其它端口）。

```bash
# 系统临时目录，无联网安装；需先按安装节准备好既有依赖
REMINDER_TMP=$(mktemp -d "${TMPDIR:-/tmp}/library-reminder.XXXXXX")
LIB_DB_PATH="$REMINDER_TMP/library.db" SERVER_PORT=18097 FRONTEND_PORT=15197 bash scripts/dev.sh
# 浏览器 http://localhost:15197；Ctrl-C 停止自己的服务，保留临时库用于复验
```

本功能仅追加 `V2__reminders_dunning.sql`（通知与催还记录），**V1 不修改**。空库自动依次迁移 V1 / V2；已有 V1 数据库启动时会自动应用 V2。升级任何需要保留的数据前先停服务并备份，优先在副本验证迁移；本轮开发验证只使用临时库，**不迁移、重置或删除现有开发库**。迁移不可用重跑旧 V1 或删除数据来代替。

## 9. 已知限制

- **没有登录限流 / 验证码**：可能被暴力破解；首版只在本机运行，作为已接受的限制。
- **没有 HTTPS**：仅本机 http 访问，不做部署。
- **前端主 chunk 约 928KB**（本次构建 927.80 kB）：仍有大于 500 kB 的体积告警（不是错误），本次不做无关代码分割优化（I-06）。
- **读者被停用后，其已登录会话在过期前仍可访问部分接口**：重新登录会被拒绝、借书 / 续借会被拒绝（满足方案最低要求）；是否加强为即时踢出由评审裁量（I-07）。
- **`formErrors.ts` 在 catalog 与 readers 两个 feature 中各有一份**：受任务写入范围限制形成的重复，属技术债务，待后续合并到共享层（I-04）。
- **单连接数据库池**：SQLite 同一时间只允许一个写者，Hikari 池大小为 1，多人同时使用会排队；本地单机场景可接受。**不要**在不改用其他并发方案的情况下调大连接池。
- 并发写冲突或数据库忙时返回 503“系统繁忙”，需用户手动重试（服务端不自动重试写操作）。

## 10. 目录速览

```
backend/    Spring Boot 后端（Maven；Flyway V1 基础表 / V2 站内提醒与催还表）
frontend/   React 前端（Vite；e2e/ 为 Playwright 冒烟用例）
scripts/    env.sh / dev.sh / test-all.sh / reset-db.sh（一律用 bash 运行）
docs/design/        已批准的设计契约与 AC/DoD（只读）
docs/verification/  核验矩阵、候选快照与本轮验证证据
```