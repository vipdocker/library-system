# 审批与调度记录（主会话维护）

任务：lib-mgmt-20261117　环节选择：selectionId=572b5d67-404b-4ac6-b12b-3413cdc4248b（confirmed）

## 1. 用户决定（来源：主会话 AskUserQuestion，2026-11-17）

| 项 | 决定 |
|---|---|
| 方案 | 批准 design-v0.1（docs/design/solution-design.md，sha256 前缀 3a2f6e94606d6466） |
| AC/DoD | v0.1 升为 **v1**（docs/design/ac-dod.md，sha256 前缀 41454ea7dfffe92b），内容不变 |
| D1 | 每位读者最多同时借 5 本；借期 30 天；可续借 1 次，从当前应还日顺延 30 天 |
| D2 | 只有管理员可以续借（`reader-self-renew=false`） |
| D3 | Spring Boot 4.1.x |
| D4 | ①修改本人口令（T14、AC-AUTH-10）纳入；②禁止同一读者同时借同一书目的多个副本纳入；③ISBN 严格校验纳入 |
| D5 | 后端 18095 / 前端 15195（E2E 用 18096 / 15196）；dev 默认管理员 admin / Admin@12345（可由 LIB_ADMIN_PASSWORD 覆盖）；时区 Asia/Shanghai |
| D6 | 允许开发环节联网下载、安装方案第 4 节列出的依赖（Maven、npm，以及必要时下载 Playwright chromium）；新增依赖仍需用户批准 |
| D7 | “rreact”即 React |
| 并行方式 | 共享工作区，不使用 git worktree；后端任务串行，前端页面任务可以并行 |

## 1.1 后续追认（来源：主会话 AskUserQuestion，2026-11-18）

| 项 | 决定 |
|---|---|
| T02 额外开发依赖 | 追认 `@types/react`、`@types/react-dom`、`@types/node`、`@testing-library/dom`（只用于开发和测试） |
| I-08 归属 | `router.test.tsx` 由 T13 修复 |
| 契约修订 R1（design-v0.1 → **design-v0.1-R1**） | solution-design §8.2 新增错误码 `METHOD_NOT_ALLOWED`，HTTP 405，场景：请求方法不被该端点支持（I-02）。后端 ErrorCode、GlobalExceptionHandler 与前端 `api/errors.ts`（含错误码完整性测试）同步，由 T13 实施。AC/DoD v1 不变；受影响的旧证据：errors.ts 完整性测试、GlobalExceptionHandler 相关测试，需在 T13 重验 |
| I-09（E2E E-01） | 由 T13 修复：登出后跳到不带 redirect 参数的 `/login`；未登录直接访问受保护页时仍保留 redirect（AC-AUTH-09 不变） |

## 2. 调度修订 plan-v1.1（只改排期，不改任务内容和契约）

plan-v1 第 4 节建议使用独立工作副本。用户选择了共享工作区，因此 dependsOn 按以下方式改写：

| 任务 | plan-v1 依赖 | plan-v1.1 依赖 |
|---|---|---|
| T01 / T02 / T03 | 无 | 无（批次 1，并行） |
| T04 | T01 | T01 |
| T05 | T01 | T04（为了串行编译后端） |
| T06 | T01 | T05（为了串行编译后端） |
| T07 | T01 | T06（为了串行编译后端） |
| T14 | T01、T02 | T07、T02（后端部分接在后端串行链末尾） |
| T08 / T09 / T10 / T11 | T02 | T02（并行，与后端串行链同时运行） |
| T12 | 全部实现任务 | 全部实现任务 |
| T13 | 全部必需任务 | T12 及全部必需任务 |

前端页面任务并行执行时，各自只对本 feature 目录运行 lint 和测试。typecheck 或 lint 的错误如果只出现在其他 feature 目录，记为“不属于本任务”，不算本任务失败。全量检查由 T13 负责。

## 3. 交给 T13（最终集成）的待办（主会话汇总，来源：各子任务报告）

| 编号 | 来源 | 内容 |
|---|---|---|
| I-01 | T03 | `scripts/*.sh` 只支持 bash，在 zsh 中 `source` 会失败；README 需写明使用 `bash scripts/dev.sh` 等命令 |
| I-02 | T05 | `GlobalExceptionHandler` 把 `HttpRequestMethodNotSupportedException` 当作 500 INTERNAL_ERROR 处理，应返回 405；该文件属于共享层（T01），由 T13 评估修复并补回归测试 |
| I-03 | T08、T10 | 共享布局 `AppLayout` 在 390px 宽度下顶栏用户名换行、显得拥挤 |
| I-04 | T08、T09 | `formErrors.ts` 在 catalog 和 readers 中各维护一份（受写入范围限制），可评估合并到共享层 |
| I-05 | T08 | 借书、还书后检索结果和可借数量的缓存刷新，需要在真实联调时确认 |
| I-06 | T02 | 前端主 chunk 约 898KB，构建时有体积告警（不是错误） |
| I-07 | T05 | 读者被停用后，已登录的会话仍能访问 `/api/auth/me` 以外的接口（满足方案的最低要求，即重新登录会被拒绝）；由评审裁量是否需要加强 |
| I-08 | T14 | `frontend/src/app/__tests__/router.test.tsx`（T02 共享文件）仍断言“建设中”占位页，且缺少 QueryClientProvider/antd App 包装，全量 vitest 19 失败（17 个早已存在，2 个因 `/account/password` 已实现）。用户 2026-11-18 决定由 T13 修复：改为断言真实页面或路由守卫行为，全量 `npm test` 须为 0 失败 |
| I-09 | T12（E-01） | 管理员在 `/admin/readers` 登出后，`RequireAuth` 与 `AppLayout.onLogout` 的 `navigate('/login')` 竞态，URL 留下 `?redirect=%2Fadmin%2Freaders`；换读者登录被带回该页并显示 403。用户决定由 T13 修复（见 §1.1）；修复后 E2E 步骤①中读者登录前的显式 `goto('/login')` 可保留 |

## 4. 首期借阅提醒增量（2026-11-19）

任务 lib-borrow-reminder-p1。selectionId=ec0966d6-228e-4349-a00a-4da3b5ff92c3，由主会话读取实际选择记录核验 confirmed；不是从回调或默认预选推定。

| 项 | 用户决定及来源 |
|---|---|
| 环节与模型 | 用户选择卡片：方案设计跳过；开发实现 ultimate；独立评审与独立验收 qmodel_latest |
| 首组业务问答 | 主会话 AskUserQuestion：提前3天一次；持久化收件箱与角标；逾期首日及之后每7天；管理员登记并发站内通知 |
| 补充业务问答 | 主会话 AskUserQuestion：D-2至D补到期前一次；逾期固定周期只补当前；催还仅逾期未还；备注仅管理员可见 |
| 最小实现与验收 | 主会话展示并经 AskUserQuestion 确认：保留旧通知并显示当前借阅状态；停用读者仍生成；独立催还历史页；角标不后台轮询；批准增量AC/DoD v1.1，定义见 reminder-increment.md |
| 文件授权 | 主会话 AskUserQuestion 明确允许新增V2、必要共享源码/测试修改、README与AC/批准记录更新、本功能契约和核验矩阵新增；V1不改，不新增依赖 |
| 数据与操作边界 | 只在临时库验证，不对现有开发库迁移；不提交、不推送、不发布，不改权限设置或全局环境 |
| 会话预算边界 | 允许后续6个开发会话，最多2个并行；评审与验收各1个会话。最终费用未知，额外扩项、模型改变或增会话先确认 |
| 计划版本v1.1 | 在拆解v1上补足前端催还对后端催还的接口依赖；仍为6个开发任务，不扩大功能 |
| 测试范围追加与计划v1.2 | 主会话AskUserQuestion明确允许原后端任务修订support/ContextLoadsTest.java：精确检查V1/V2成功，保留原六表及无种子检查、追加新表检查，再运行全量verify。REM-BE-NOTIFY在原会话返工轮次2；依赖、业务及AC/DoD定义不变，不新增会话；当前范围与图见reminder-increment.md |

### 4.1 审计阻塞下的排期修订v1.3

主会话AskUserQuestion对“npm审计网络仍不可用，后续开发如何安排？”的真实用户回答为“继续开发，保留审计（推荐）”。默认registry的TLS诊断失败证据保留，审计状态仍BLOCKED，不改源/代理/TLS、不豁免。

允许REM-FE-DUNNING消费已核验收件箱源码与已完成后端催还DTO，随后REM-E2E可消费全部业务源码。REM-FE-INBOX的剩余审计继续在原会话跟踪，保持IMPL必需门禁，未通过前阻断REM-INT整体验证完成及最终交付；不将剩余审计改为NOT_DUE或已通过。仅调整依赖排期，不扩功能、不改模型或AC/DoD、不增加6个开发会话或并发上限2。当前图及范围见reminder-increment.md。

### 4.2 既有授权范围内的测试责任调整v1.4

REM-FE-DUNNING首次交接Q-DUN-01指出router.test.tsx新增占位断言会与正式页面冲突。主会话核对第230–234行，依据第4节已获用户批准的共享路由/必要测试变更范围，转交此新增用例与必要案件API mock给原催还前端会话；保留管理员不得请求读者通知接口等隔离断言，不修改生产router，不删除旧用例。原共享文件写入会话已停止，无并发写入，不扩功能或模型、会话数、AC/DoD。此项为主会话范围内责任调整，不记录为新的用户问答授权。

### 4.3 标准E2E入口修订v1.5

主会话AskUserQuestion对“是否允许仅在Playwright的后端E2E启动环境中固定一秒扫描，使标准E2E命令无需手工参数？”的真实回答为“允许测试配置修改（推荐）”。仅授权REM-E2E在frontend/playwright.config.ts后端webServer.env添加LIB_REMINDER_SCANINTERVAL=PT1S；原会话轮次2修改并用标准命令全量重验。生产默认PT1H、业务、依赖、等待断言、6个开发会话与并发上限2均不变；审计仍BLOCKED，不豁免。记录为计划v1.5，AC/DoD v1.1不变。

原任务的批准与证据保持原版本；历史PASS不能沿用为本次候选通过。
