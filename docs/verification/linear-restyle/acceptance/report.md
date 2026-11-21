# LDR-ACC 独立验收报告（round 1）

- taskId：`linear-design-restyle-20261121`；subtaskId：`LDR-ACC`；stage：acceptance-verifier；plan：`linear-restyle-plan-v1.1`；dispatchRound：1。
- 冻结定义：`linear-restyle-ac-dod-v1`（方案 §8）；design：`linear-restyle-design-v1-R1`（SHA `551d621d4b78fc6d056a35f89efbb4e91c72862c5b2f7f1a8380d1822b2ed455`）。
- 候选：`linear-restyle-20261121-5c7c5b33b27d`；339 文件 sourceDigest 运行前/后均为 `5c7c5b33b27dcd304b72e85d1f286b766d485b9dddeec4cdd16a223326b3fe0c`，0 漂移。HEAD 基线 `c1e30e646bf7bd8c39e59b353a001f79192ca9f6`。
- 环境：macOS arm64；Node v26.7.0 / npm 11.19.0（/opt/homebrew）；JDK 21.0.12（bash scripts/env.sh）；Maven 3.9.16；Playwright 1.65.0 Chromium；E2E/探针端口 18096/15196，仅系统临时 SQLite。
- 授权：主会话交接包启用本次验收；用户 selectionId `3ad760f3-f0d6-4897-b592-559abededf2e` confirmed 及后续 AskUserQuestion 批准（仅暗色、低饱和状态色、系统字体、根 DESIGN.md、hover 深字+实色 focus、AC/DoD v1、R1 最小机制）。写入仅 `acceptance/` 与矩阵 ACCEPT 区域。

## 1. 可直接展示的摘要

**状态：NEEDS_WORK。** 候选与授权核验通过；前端四项与标准 E2E 独立全绿（lint 28 边界测试、typecheck、410/410 单测、build、33/33 E2E）；独立浏览器探针 103 组 / 4757 文字样本 **0 对比度失败**（最低 4.6999，9 个真实 disabled 例外单列）、0 横向溢出，1440/1024/390 与真实 ADMIN/READER、403/404、空/错/加载、Modal/Select/DatePicker/分页/ghost/danger/Tag/三态通知/催还均实测。**但发现 1 个已确认视觉缺陷 ACC-D01：403/404 页仍渲染 antd 存量彩色插画（亮蓝圆底/绿仙人掌/紫锁/蓝问号），批准方案 §4.5 F11/F12 要求的中性 LockOutlined/SearchOutlined 在 antd 6.7.1 运行时无效**（Result 对 403/404/500 早退返回存量 SVG，忽略 icon prop）。AC-3 判 FAIL；其余到期必需 AC/DoD 全 PASS。缺陷只报告、不修产品。建议主会话交原开发返工、重新冻结候选后复验 403/404 与受影响回归。

本结论仅覆盖本次视觉增量到期项，**不是**整个系统历史质量/安全/审计门禁或生产发布通过；独立评审未启用/跳过，不计 PASS。

## 2. 缺陷 ACC-D01（medium，纯视觉）

- 关联：AC-3（403/404 暗色覆盖）；批准设计 §4.5 组件表 Empty/Result403/404 行、§5.2 F11/F12。
- 复现：READER（reader2）真实登录 → `/admin/dashboard` 得 403；任意不存在路径得 404；观察插画区。
- 预期：中性单色 Lock/Search 图标（aria-hidden、ink-subtle），无未主题化亮色存量插画。
- 实际：antd 存量彩色 SVG；`icon` prop 被忽略；渲染节点为 `.ant-result-image` 内嵌 SVG，global.css 的 `.ant-result .ant-result-icon > .anticon` 规则永不匹配。
- 根因：`frontend/node_modules/antd/es/result/index.js` Icon 组件 `if (ExceptionStatus.includes(status)) return <SVGComponent/>` 早退分支（40–46 行）。源码 `ForbiddenPage.tsx:9-11`、`NotFoundPage.tsx:9-11` 的改动存在但运行时无效。
- 证据：`screenshots/acc-reader-real-403.png`、`acc-reader-404.png`（ACC 实际读取）；`computed/defect-ACC-D01.json`；两页文字对比度本身全 ≥4.5（缺陷在图形层，文本采样不覆盖 SVG，故 DEV computed 证据未暴露）。
- 影响面：仅这两页（全仓 Result 用法仅此两处；Empty 的 image prop 正常，中性 Inbox 图标已截图确认）。业务/权限/路由/文案/按钮行为无影响。
- DEV 冲突：DEV/INT 矩阵 AC-3/AC-5 标 PASS；本缺陷证明 403/404 运行时未达批准条件。DEV 历史不改写，ACC 区域如实记 FAIL。AC-5 冻结枚举项（渐变/聚光/卡/书封/图表）无违反，故 AC-5 保持 PASS 并注明归责边界，不扩大定义。
- 修复责任：原开发环节；验收不修产品、不降标准。

## 3. AC 核验矩阵（ACCEPT，定义 v1，候选 C1/环境 E1）

| 编号 | 结果 | 关键实际证据 |
|---|---|---|
| AC-1 | PASS | 唯一固定 YAML 注释剥离后与 /tmp/linear-DESIGN.md 逐字节相等；恢复 SHA `30bd30e7…fcb5f`、根 SHA `70ca40c5…2d8f5`；注释 URL/Ref/日期/SHA 逐行核对（computed/design-provenance.json） |
| AC-2 | PASS | linearTheme.ts 单源 + R1 纯包装仅恢复 6 seed；CSS 仅 var(--linear-*)；13 主题单测独立绿；运行时 body `rgb(1,1,2)`、按钮默认/hover/pressed、2px `#5e69d1` focus、Modal/Select `rgb(24,25,26)`、Table hover `rgb(20,21,22)`、Pagination/ghost `rgb(130,143,255)` 实测 |
| AC-3 | **FAIL** | 103 组 4757 样本 0 失败、最低 4.699901、0 横溢、三视口统计/日历/跨月可点通过；**ACC-D01：403/404 存量彩色插画，中性图标运行时无效** |
| AC-4 | PASS | 真实 OVERDUE/ACTIVE/RETURNED 通知、OPEN(warning)/CLOSED(success) 催还、逾期 Tag 文本、error 校验边框、danger 实心深字（`rgb(193,136,137)` 底 + `rgb(1,1,2)` 字） |
| AC-5 | PASS | 生产 CSS/TSX 0 渐变/0 旧绿暖纸/0 硬编码色（scope-check.json）；登录品牌中性、卡/书封/图表中性、阴影 none；403/404 插画按 AC-3 归责 |
| AC-6 | PASS | tracked diff 仅 23 视觉文件；保护路径 diff 空；新增源文件恰为批准 5 个；原 13 业务 E2E 独立绿 |

## 4. DoD 核验矩阵

| 编号 | 结果 | 执行/核验 | 证据 |
|---|---|---|---|
| DoD-1 | PASS | ACC 独立 | logs/acc-lint/typecheck/test/build.log + acc-frontend-exit-codes.txt（全 exit 0；28 边界、410/410、41 文件） |
| DoD-2 | PASS | ACC 独立 | logs/acc-e2e.log + acc-e2e-exit.txt（33/33，3.9m，临时库） |
| DoD-3 | PASS | DEV 实跑 / ACC 交叉核对 | DEV L1/J1/P1/U3 hash 与 snapshot 一致；ACC 103 组独立实测同范围 |
| DoD-4 | PASS | ACC | 同一 C1 独立复核并出具结论；pre/post digest 一致 |
| DoD-5 | PASS | ACC | 矩阵 ACCEPT 区域 + 本 report + results.json 只记实跑；DEV/INT 历史未改；新矩阵 SHA `b0175652a5dbb52126aa22e018098194d6b9891abdc00aa00ac1ebd147ba7e18`（snapshot 旧矩阵 hash 保持 DEV/INT 版本） |
| DoD-6 | PASS | DEV 实跑 / ACC 交叉核对 | DEV test-all.log：617 后端 / 410 前端 / 33 E2E、exit 0、终行"全部检查通过"；hash `ec3e5923…` 绑定 snapshot；按交接不新增独立后端重跑 |

## 5. 实际命令与结果

| 命令 | exit | 统计 |
|---|---:|---|
| `/opt/homebrew/bin/npm --prefix frontend run lint` | 0 | ESLint + 28 边界测试 |
| `/opt/homebrew/bin/npm --prefix frontend run typecheck` | 0 | tsc 通过 |
| `/opt/homebrew/bin/npm --prefix frontend run test` | 0 | 410/410，41 文件 |
| `/opt/homebrew/bin/npm --prefix frontend run build` | 0 | 成功；既存 >500kB 告警保留 |
| `/opt/homebrew/bin/npm --prefix frontend run e2e` | 0 | 33/33 |
| 独立探针（acceptance/scripts/acc.playwright.config.ts）round 1 | 1 | 18 用例 16 过 2 失败（ACC-04/06 探针定位错误：okText 实为"确认"；同书双通知 strict mode）——非产品缺陷 |
| 探针针对性重跑 `--grep "ACC-04|ACC-06"` | 0 | 2/2 |

探针失败首轮日志保留于 `logs/acc-probe-round1.log`（其 test-failed 截图被 Playwright outputDir 清理机制清除，错误全文在日志中）；修正仅改验收脚本，未改产品/原测试/配置。

## 6. 浏览器实测覆盖与观感

103 组截图 + computed（`screenshots/`、`computed/`，汇总 `computed/summary.json`）。真实身份：anonymous 登录各态、ADMIN 15 路由、READER 6 路由 + 逾期/历史/未读已读、真实 403/404；弹层：分类/读者/重置 Modal、校验、danger Popconfirm、删除取消+确认（真实 DELETE 204）、Table hover、Select/DatePicker portal、范围 `::before` 合成（`rgb(20,21,22)`/z1）、range 端点白字 on `rgb(94,106,210)`、跨月日期可点击且按有效文字采样、分页 active/hover/翻页；催还登记→OPEN→历史→归还→CLOSED→读者 RETURNED 通知；ACTIVE 到期提醒；六 feature 只读模拟 loading/empty/503/重试（19 组明标 mock）+ auth/me 仅延迟透传；13 组明标组件夹具（warning/error 焦点、disabledDate）；1440/1024/390 统计文本/排行/弹层边界、390 日历第二月内滚可达。ACC 实际读取 12 张关键截图（含发现缺陷的 403/404）。密码输入 mask 中性 surface；催还备注留空；无 cookie/敏感备注入图。

## 7. 未跑、限制与清理

- 未独立重跑后端 verify（DoD-6 为 DEV 到期门禁，已交叉核对）；无跨浏览器/真机/屏幕阅读器（非本次范围）；Table 行选择 NOT_APPLICABLE；1024 仅统计页补测（建议项）。
- ACC-10 几何断言 round 1 已执行并通过（round 1 报告），原始几何 JSON 因与同名 capture JSON 重名被覆盖；视觉 fit 另由三宽度截图实际读取确认。
- 清理：本轮 3 个自有临时库及 WAL/SHM 已删除；DEV 历史临时库未触碰；fixture 仅删本轮自有催还/通知行；四端口无监听残留；无源码修改、无提交/暂存/push/deploy。
- 新增文件均在 `acceptance/`（脚本/日志/截图/computed/report/results），属证据，不纳入 source digest；矩阵仅 ACCEPT 区域更新。

## 8. 建议下一步

主会话将 ACC-D01 交原开发 smodel 会话返工：使 403/404 中性图标在 antd 6.7.1 实际渲染（不修改 node_modules；例如避开 Exception status 早退分支的呈现方式），保留本轮失败证据；修复后重新冻结候选、重跑受影响检查与全量入口，再派独立验收复验。验收会话本轮停写，不自行派发后续任务。