# 当前候选 C2：ACC-D01 修复后的重新集成

- taskId：`linear-design-restyle-20261121`；subtaskId：`LDR-INT`；operation：`integrate`；dispatchRound：**2**；sessionRound：**6**。
- plan：`linear-restyle-plan-v1.1`；design：`linear-restyle-design-v1-R1`；冻结定义：`linear-restyle-ac-dod-v1`（下表v1），来源为`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/linear-restyle-design.md`，SHA `551d621d4b78fc6d056a35f89efbb4e91c72862c5b2f7f1a8380d1822b2ed455`。
- 主会话已核验R5三文件修复并**单独派发本次integrate**。本轮无源码修改，未改变定义、业务、依赖、权限或测试配置。
- **当前状态：READY_FOR_CHECKS，仅为C2开发集成候选；独立复验NOT_RUN，待主会话派发。** C1独立验收因ACC-D01为NEEDS_WORK/AC-3 FAIL；下面的C1旧DEV/ACC表仅为历史，不能当作C2结果，也不能以“出具结论”视为验收通过。

## C2 标识、历史与证据

**C2 candidateId**：`linear-restyle-20261121-c2-288c7b59c108`；339文件源码摘要：
`288c7b59c108feff7d910dd8239c5d289c9f6029c18e598f3dfc777f3df857e8`。

**S2**：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/candidate-snapshot.txt`。范围与C1同339文件；默认JS `Array.sort()`相对路径→字节SHA map，SHA256 UTF-8 `JSON.stringify`无空白。证据/矩阵/快照自身不进source digest。HEAD仍`c1e30e646bf7bd8c39e59b353a001f79192ca9f6`。

C1→C2恰好3文件：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/components/ForbiddenPage.tsx`、`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/components/NotFoundPage.tsx`、`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/e2e/linear-theme.spec.ts`。两组件只去Result异常展示status，保留title/subTitle/自定义icon/aria-hidden/按钮导航；E2E追加4个真实图形用例。其余336文件与C1一致；本次入口前后均复核无漂移。

更新前已精确保留三份C1历史副本（hash逐字节核验）：
- `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/integration/round2/history/c1-candidate-snapshot.txt`，SHA `d18a53921fb75b4c53b4ea8af0048adecf42ff08fa04c1f0c8b70570eee0fcd4`。
- `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/integration/round2/history/c1-ac-dod-matrix-with-acceptance.md`，SHA `b0175652a5dbb52126aa22e018098194d6b9891abdc00aa00ac1ebd147ba7e18`，包含原ACC-D01失败。
- `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/integration/round2/history/c1-integration-results.json`，SHA `6c9ad2feb946f301e5e0ab6f5824e31b9049ecbd7b8f71fcb41297642c7fbbd9`。

原`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/acceptance/`的235个普通文件及既有node_modules符号链接均未改；报告中的DoD-4误标由原ACC后续更正并保留历史，DEV不改其报告。

本次证据标识：
- **L2** `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/integration/round2/test-all.log`，完整实际入口日志。
- **J2** `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/integration/round2/computed/`，119份实际文本computed；**G2**同目录上级的`graphics/`，4份实际403/404图形属性。
- **P2** `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/integration/round2/screenshots/`，本轮11张关键截图，全部实际读取。
- **R2** `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/integration/round2/results.json`，本次精简结果及证据hash。

**E2环境**：macOS26.6.2 build25G83 arm64；Homebrew Node26.7.0/npm11.19.0、Maven3.9.16、JDK21.0.12；原`bash scripts/env.sh`；Playwright1.65.0 Chromium；18096/15196一组，仅系统临时SQLite。开发库/WAL/SHM/bak不触碰，无安装/全局配置变更。

## C2 实際全量命令

根目录`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10`执行：

```bash
bash -o pipefail -c 'bash scripts/test-all.sh 2>&1 | tee docs/verification/linear-restyle/development/integration/round2/test-all.log'
```

退出码**0**，无重试。原脚本`set -euo pipefail`各阶段顺序运行并输出最终成功，证明每个子命令exit0；不是用R5前端结果拼成全量。

| 子命令 | exit | 本轮实际结果 | L2定位 |
|---|---:|---|---|
| `source scripts/env.sh` | 0 | 固定JDK21与Homebrew npm | 原脚本第17行及环境探测 |
| `mvn -f backend/pom.xml verify` | 0 | **617 tests，0 failures/errors/skipped**，含6架构测试；BUILD SUCCESS | 2–1718；1701/1714 |
| `npm --prefix frontend run lint` | 0 | ESLint通过，28边界测试通过，无跳过 | 1720–1760 |
| `npm --prefix frontend run typecheck` | 0 | tsc通过 | 1762–1767 |
| `npm --prefix frontend run test` | 0 | **410 tests / 41 files通过** | 1768–1930；1922–1923 |
| `npm --prefix frontend run build` | 0 | 成功，既存>500kB告警保留 | 1932–1995 |
| `npm --prefix frontend run e2e` | 0 | **37/37通过**；24视觉+原13业务；4.0m | 1997–2504 |
| 根test-all | 0 | `[test-all.sh] 全部检查通过。` | 2506 |

## C2 开发集成矩阵（与C1历史分离）

以下结果只属于C2/E2，执行者为DEV，不是ACC/REV独立签字。所有条目定义均为冻结v1。

| 编号 | 定义 | 阶段/执行者 | 预期 | 本轮实际 | 结果 | 方法/证据 | 候选/环境 |
|---|---|---|---|---|---|---|---|
| AC-1 | v1 | INT2/DEV | 上游原文+唯一来源注释，可精确恢复 | 指定块逐行/唯一性验证；恢复Buffer与source逐字节相等；根与设计hash不变 | PASS | 本轮只读字节校验；S2/R2 | C2/E2 |
| AC-2 | v1 | INT2/DEV | 单源暗色token、准确映射和幂等初始化 | 主题13用例及全量实际颜色/焦点/portal断言通过；主题/初始化未变 | PASS | L2前端测试；J2；S2 | C2/E2 |
| AC-3 | v1 | INT2/DEV | 全站含403/404暗色，有效文字≥4.5 | 119组5059样本，9真实disabled例外，0失败，最低4.699901；**新增图形回归4/4：lock/search实际渲染、ink-subtle、image节点0、返回正常** | PASS | J2/G2/P2，L2四个ACC-D01用例；图形与文字分开核验 | C2/E2 |
| AC-4 | v1 | INT2/DEV | 语义色范围、状态/逾期/校验含义不变 | ACTIVE/OVERDUE/RETURNED及OPEN/CLOSED、逾期文字、危险确认等实际回归通过 | PASS | J2、L2原13业务及视觉状态用例 | C2/E2 |
| AC-5 | v1 | INT2/DEV | 无自有氛围渐变/聚光/大阴影或彩色装饰卡 | 旧色/变量/渐变扫描无残留；登录/仪表盘/统计实际图复核；403/404缺陷按AC-3修复 | PASS | 源码Grep与S2；P2 | C2/E2 |
| AC-6 | v1 | INT2/DEV | 不改业务/API/路由/权限/依赖/边界及旧断言 | C1仅3授权文件差异；336相同；本轮无源码改动；617/410/37通过 | PASS | S2差异清单及保护路径diff；L2 | C2/E2 |
| DoD-1 | v1 | INT2/DEV | 前端四项exit0 | lint/typecheck/test/build全部通过 | PASS | L2:1720–1995 | C2/E2 |
| DoD-2 | v1 | INT2/DEV | 标准隔离E2E全绿 | 37/37，原13业务未改；临时库与单组端口 | PASS | L2:1997–2506 | C2/E2 |
| DoD-3 | v1 | INT2/DEV | 同一候选真实页面与状态证据 | 本轮再次真实角色全站操作，119文本组及4图形组；实际读11张当前截图，含两角色与四张403/404 | PASS | J2/G2/P2；R2覆盖清单 | C2/E2 |
| DoD-4 | v1 | ACCEPT/ACC | 新候选独立复验并满足验收标准 | **未执行；待主会话向原ACC派发**，C1失败结论仍为历史失败 | **NOT_RUN** | 原ACC报告未改；下方C2待复验区 | C2/待ACC |
| DoD-5 | v1 | INT2/DEV | 实跑追溯、稳定候选、历史不改写 | S2全339文件hash；证据独立hash；精确保留C1快照/矩阵/INT结果与原ACC失败 | PASS | S2/R2及history三文件 | C2/E2 |
| DoD-6 | v1 | INT2/DEV | 根test-all完整成功 | 本轮实际backend verify→四项→37E2E退出0 | PASS | L2全过程及2506终行 | C2/E2 |

## C2 独立验收已核验区域（LDR-ACC round 2，2026-11-21）

定义保持v1；执行者：原独立验收会话（qmodel_latest，主会话派发 dispatchRound 2）。候选C2=`linear-restyle-20261121-c2-288c7b59c108`；运行前后339文件digest均=`288c7b59c108feff7d910dd8239c5d289c9f6029c18e598f3dfc777f3df857e8`，0漂移；C1→C2差异经独立逐文件比对恰为3文件（ForbiddenPage/NotFoundPage/linear-theme.spec.ts），其余336一致（round2/computed/source-digest-pre.json）。snapshot SHA `57303c61…8425b`、设计/根DESIGN/上游恢复hash不变；C1历史三副本hash逐字节核验 intact（round2/computed/scope-check.json）。

**ACC round2 总结论：PASS** —— 本次视觉增量全部到期必需 AC-1–6 / DoD-1–6 通过，无 FAIL/NOT_RUN/BLOCKED/NOT_DUE。ACC-D01 已在 C2 真实修复并经独立图形+导航复验关闭。

**C1 DoD-4 误标纠正（保留历史，不改写字节）**：固定 DoD-4 语义为『独立验收复核并满足所有到期必需 AC/DoD 给出通过结论』。C1 round1 因 AC-3 FAIL（ACC-D01），当时整体验收未通过，**C1 的 DoD-4 正确值应为 FAIL**；原 ACC round1 report/results 与 C1 历史矩阵中将其标 PASS 属语义误标，特此纠正。C1 原文件字节保留（history 副本 hash 核验），不以 C2 PASS 追改 C1 历史。本轮 C2 全部到期必需项实际通过，DoD-4 方为 PASS。

ACC round2 证据索引（前缀 `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/acceptance/round2/`）：
- **AL2** `logs/acc-r2-lint/typecheck/test/build.log`+`acc-r2-frontend-exit-codes.txt`（四项独立exit0；28边界；410/410、41文件；build保留>500kB告警）。
- **AE2** `logs/acc-r2-e2e.log`+`acc-r2-e2e-exit.txt`（标准E2E独立 **37/37** exit0，4.1m，临时库18096/15196）。
- **AP2** `logs/acc-probe-run1.log`+`acc-probe-report-run1.json`（22用例21过1失败：ACC-04在Popconfirm入场瞬态opacity≈0.54帧采样致假失败，非产品缺陷；失败产物保留于`runs/run-1/`）；`logs/acc-probe-run2.log`+`acc-probe-report.json`（探针补祖先opacity settled等待后针对性重跑ACC-04，1/1过，输出独立`runs/run-2/`）；round1定位修正保留。
- **AJ2** `computed/`107组采样JSON：**4793样本、0对比度失败、9真实disabled例外、最低4.699901、0横溢**；`computed/graphics/acc-d01-{403,404}-{1440,390}.json`四份图形事实；`computed/geometry/`六份永久唯一几何附件（stats三宽度+calendar三宽度，不与capture同名、不被run清理）；`summary.json`、`scope-check.json`、`source-digest-pre/post.json`。
- **AS2** `screenshots/`107张本轮真实截图；ACC实际读取 acc-d01 四张（1440/390×403/404）与 acc-stats-fit-390 等，确认单色lock/search、无`.ant-result-image`、无彩色插画、窄屏不截断。
- **AX2** `scripts/acc.playwright.config.ts`、`acc-visual-r2.spec.ts`、`summarize.cjs`、`package.json`（round2新证据脚本；未覆盖round1原probe/证据）。

| 编号 | 定义 | 阶段/责任 | 预期 | 当前实际 | 结果 | 证据 | 候选 |
|---|---|---|---|---|---|---|---|
| AC-1 | v1 | ACCEPT / ACC | 原文/provenance | 根DESIGN/设计/上游hash与C1一致且独立核验；C2未触碰原文 | PASS | AJ2 scope-check；S2 | C2 |
| AC-2 | v1 | ACCEPT / ACC | 单源主题/映射 | 336文件含linearTheme/global.css与C1逐字节一致；13主题单测独立绿；运行时三态/focus/portal/ghost实测一致 | PASS | AL2；AJ2；AS2 | C2/E2 |
| AC-3 | v1 | ACCEPT / ACC | 全站状态/对比度/响应式含403/404 | 107组4793样本0失败、最低4.699901、0横溢；三视口几何附件全通过；**ACC-D01关闭：403/404真实READER下data-icon=lock/search、aria-hidden=true、icon与全部SVG path fill=rgb(138,143,152)、.ant-result-image=0、svg=1、title/subtitle原样、返回首页真实到/books** | PASS | AJ2 graphics四份+summary；AS2四张已读；AP2 | C2/E2 |
| AC-4 | v1 | ACCEPT / ACC | 状态语义 | 真实OVERDUE/ACTIVE/RETURNED、OPEN/CLOSED、逾期Tag、校验、danger深字复验通过 | PASS | AJ2 acc-notification-*/acc-dunning-*/acc-danger-popconfirm | C2/E2 |
| AC-5 | v1 | ACCEPT / ACC | 装饰限制 | 0渐变/0旧色/0硬编码；403/404现为中性单色图标，C1插画缺陷不再存在 | PASS | AJ2 scope-check；AS2 | C2/E2 |
| AC-6 | v1 | ACCEPT / ACC | 范围与回归 | 保护路径diff空；tracked 23视觉文件；新增源5个不变；原13业务E2E独立绿；37/37 | PASS | AJ2 scope-check；AE2 | C2/E2 |
| DoD-1 | v1 | ACCEPT / ACC | 独立前端四项 | 全exit0；28边界、410/410 | PASS | AL2 | C2/E2 |
| DoD-2 | v1 | ACCEPT / ACC | 独立标准E2E | 37/37 exit0；原配置/超时/retry/helper未改 | PASS | AE2 | C2/E2 |
| DoD-3 | v1 | ACCEPT / ACC（交叉核对DEV） | DEV证据绑定C2 | INT2 results/test-all hash（37c9e9fd…/887debd4…）与S2一致；J2/G2/P2属C2；ACC另107组独立实测 | PASS | S2；DEV L2/J2/G2/P2；AJ2/AS2 | C2/E2 |
| DoD-4 | v1 | ACCEPT / ACC | 独立复验并满足全部到期必需项给出通过结论 | C2全部到期必需AC/DoD实际通过；前后digest一致；**C1误标纠正见上** | PASS | AJ2 source-digest-pre/post；本区域；round2/report.md | C2/E2 |
| DoD-5 | v1 | ACCEPT / ACC | 追溯与历史不改写 | 本区域+round2 report/results只记实跑；C1历史字节保留；新矩阵hash另记于round2/results.json | PASS | 本文件；round2/results.json | C2/E2 |
| DoD-6 | v1 | ACCEPT / ACC（交叉核对DEV门禁） | 根test-all全量 | INT2 L2实跑617后端/410前端/37E2E exit0终行"全部检查通过"；hash绑定S2；不扩为新后端审计 | PASS | DEV L2:1701/1922/2504/2506；S2 | C2/E2 |

ACC round2 未跑/限制：无独立后端重跑（DoD-6 DEV门禁交叉核对）；无跨浏览器/真机/屏读（非范围）；行选择NOT_APPLICABLE；1024仅统计补测（建议项）；run-1的ACC-04瞬态帧假失败已披露并以稳定态等待修正重跑，未降阈值/未改产品；既存>500kB chunk告警保留。

清理：round2三个自有临时库（1795259289542/1795259565216/1795259892063及WAL/SHM）已删除；DEV历史库未触碰；fixture仅删本轮自有行；四端口无监听；无源码修改/提交/部署。写入仅round2/与本C2 ACCEPT区域。

本次PASS仅覆盖本视觉增量C2到期必需项，不是整个系统历史质量/安全/审计门禁或生产发布通过；独立评审未启用/跳过，不计PASS。

## C2 UI复核、限制及下一步

本轮实际读取P2全部11图：四张403/404（1440×900、390×844），login-default、admin-route-0、reader-route-2、stats-fit-390、catalog-503/empty/loading。确认无彩色人物/仙人掌等旧插画，锁/放大镜中性可见；标题/中文说明/按钮保持，窄屏无截断。G2确认`data-icon=lock/search`、`aria-hidden=true`、颜色和每条SVG path填充均`rgb(138,143,152)`，`.ant-result-image=0`，真实READER返回首页仍到`/books`。这不是只看传入props或文字对比度。

J2为106应用组+13明确标注实际组件夹具；19个read-only模拟状态继续标识，身份/角色未mock；5059样本中的9个disabled有逐项理由，原placeholder/hint/日历伪元素核验保留。37测试全通过且标准回归覆盖两角色、全部feature、弹层/焦点/分页、1440/1024/390。行选择仍NOT_APPLICABLE（原表无该交互），不新增行为。

当前无已知阻断开发项；既存构建chunk>500kB告警保留。未执行C2独立验收、发布、跨浏览器/真机/屏幕阅读器认证。评审未启用、不计PASS；历史首版/提醒安全审计或门禁不被本任务关闭。只使用系统临时数据库，测试服务已退出；无安装、提交、暂存、push或部署。主会话核验S2后另派原ACC复验；本会话停写。

---

# C1 历史原文（以下逐字保留，不属于C2结果）

# Linear 暗色改造：开发集成核验矩阵

## 身份、定义与授权

- taskId：`linear-design-restyle-20261121`；subtaskId：`LDR-INT`；operation：`integrate`；dispatchRound：1；sessionRound：4。
- planVersion：`linear-restyle-plan-v1.1`；designVersion：`linear-restyle-design-v1-R1`；冻结定义：`linear-restyle-ac-dod-v1`（本表简写 **v1**）。
- 批准定义位置：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/linear-restyle-design.md` §8；SHA256 `551d621d4b78fc6d056a35f89efbb4e91c72862c5b2f7f1a8380d1822b2ed455`。本轮只读，未改变条目、阈值或必需性。
- 授权：主会话在核验LDR-01–04真实成果后单独派发本次integrate；用户原selectionId `3ad760f3-f0d6-4897-b592-559abededf2e`及“批准并继续”“允许最小修订”决定保持。没有自行启用验收或评审。
- **结论：READY_FOR_CHECKS，仅为可供独立验收的集成候选。** 本轮没有源码、测试、配置或依赖修改；独立验收未开始，不能称验收或上线通过。

## 候选与环境

**C1**：`linear-restyle-20261121-5c7c5b33b27d`；339个源码/配置/测试/规范文件的确定性内容摘要：
`5c7c5b33b27dcd304b72e85d1f286b766d485b9dddeec4cdd16a223326b3fe0c`。

快照：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/candidate-snapshot.txt`。逐文件SHA、覆盖规则、默认JS字典序、Git状态、实际diff摘要与独立证据hash见该文件。HEAD为`c1e30e646bf7bd8c39e59b353a001f79192ca9f6`，不是把HEAD单独当作候选。证据、矩阵和快照自身不进入source digest，避免自哈希循环。

27个已修改/新增应用与规范文件的R3输入摘要仍为`1475f607002ab3ba979d2ddd40c4baf9a003649ccc2d906b9fb395635adf20a2`。本轮开始和test-all后均逐文件核对，无漂移。

**E1**：macOS 26.6.2 / build25G83 / arm64；Homebrew Node26.7.0、npm11.19.0、Maven3.9.16、JDK21.0.12；Java经`bash`加载`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/scripts/env.sh`。antd6.7.1、Playwright1.65.0 Chromium；E2E18096/15196，仅系统临时SQLite库。开发库、WAL/SHM及备份未触碰。已有node_modules，无安装动作。

## 本轮证据索引

以下标识在表格每行引用，均是实际文件而不是预期输出：

- **L1** 完整串行入口日志：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/integration/test-all.log`。
- **J1** 本轮115份实际computed JSON：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/integration/computed/`。每份包含route、role、viewport、state、mock、kind、前景、有效背景、祖先opacity、calendarLayers、ratio及disabled排除理由。
- **P1** 本轮复制并实际打开复核的12张关键截图：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/integration/screenshots/`。
- **R1** 本轮精简结果：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/integration/results.json`。
- **U3** 同源码的完整R3截图与对比度证据：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/r3/current-browser/`；R3开发记录：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/development/r3/results.json`。
- **S1** C1快照及逐文件清单，绝对路径见上节。

没有复制多套新HTML报告。本轮全量E2E正常生成报告位于`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/playwright-report/index.html`，可能被后续测试覆盖；长期证据以L1/J1/P1/U3及S1独立hash为准。

## 实际执行命令与结果

工作目录始终为`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10`。实际入口：

```bash
bash -o pipefail -c 'bash scripts/test-all.sh 2>&1 | tee docs/verification/linear-restyle/development/integration/test-all.log'
```

外层退出码 **0**。未改脚本；脚本`set -euo pipefail`且每个阶段顺序执行。各子命令退出码0由后续阶段确实运行及最终成功共同确认，不是把旧局部PASS拼接成全量结果。

| 环节 | 脚本实际子命令 | 退出码 | 实际统计 | L1定位 |
|---|---|---:|---|---|
| 环境 | `source scripts/env.sh` | 0 | 明确使用JDK21与Homebrew npm | 脚本第17行；本轮环境探测输出 |
| 后端 | `mvn -f backend/pom.xml verify` | 0 | **617 tests；0 failures/errors/skipped**；含6个架构测试；BUILD SUCCESS；JaCoCo报告正常生成 | L1:2–1718，汇总1701、1714 |
| lint | `npm --prefix frontend run lint` | 0 | ESLint通过；28个模块边界测试；0失败/跳过 | L1:1720–1760 |
| 类型检查 | `npm --prefix frontend run typecheck` | 0 | tsc通过 | L1:1762–1767 |
| 前端测试 | `npm --prefix frontend run test` | 0 | **410 tests / 41 files，全部通过** | L1:1768–2013，汇总2005–2006 |
| 前端构建 | `npm --prefix frontend run build` | 0 | 构建成功；既存>500kB chunk告警保留 | L1:2015–2078 |
| E2E | `npm --prefix frontend run e2e` | 0 | **33/33通过**，其中原13个业务测试未修改；4.0分钟 | L1:2080–2579 |
| 全量入口 | `bash scripts/test-all.sh` | 0 | `[test-all.sh] 全部检查通过。` | L1:2581 |

前端子命令实际解析到`/opt/homebrew/bin/npm`。未跳过后端、未安装包、未改超时/retries、未修改原测试以消除失败。

## 开发/集成执行矩阵

每项为固定定义v1的本阶段结果；执行者均为DEV（本开发会话自测），不是独立ACC/REV签字。C1/E1定义如上。

| 编号 | 定义 | 阶段/执行者 | 预期 | 实际 | 结果 | 方法/证据 | 候选/环境 |
|---|---|---|---|---|---|---|---|
| AC-1 | v1 | INT / DEV | 完整上游原文及唯一指定来源注释，可精确恢复SHA | 固定注释块逐行及唯一性校验；移除后Buffer与`/tmp/linear-DESIGN.md`完全相等；恢复SHA `30bd30e7…fcb5f`，根SHA `70ca40c5…2d8f5` | PASS | 本轮只读严格Buffer检查；完整hash见S1/R1 | C1/E1 |
| AC-2 | v1 | INT / DEV | 单源暗色token、准确映射、幂等初始化 | 13个主题用例通过；公开darkAlgorithm包装仅恢复六seed；CSS与antd共源；浏览器默认/hover/pressed、焦点、组件实际值通过 | PASS | L1前端测试；J1；`linearTheme.ts`及新单测受S1绑定 | C1/E1 |
| AC-3 | v1 | INT / DEV | 全站及空/错/加载暗色；有效文字≥4.5；焦点/窄屏不回归 | 115组computed：5023文字样本，9个真实disabled例外，**0失败，最低4.699901**；1440/1024/390；输入/弹层/分页/日历伪元素与窄屏尺寸均通过 | PASS | J1全部；P1关键截图实际阅读；L1全量E2E；U3完整同源码视觉证据 | C1/E1 |
| AC-4 | v1 | INT / DEV | 语义色只用于状态/校验/业务告警；文本含义、逾期标识不变 | ACTIVE/OVERDUE/RETURNED通知、OPEN/CLOSED催还、逾期天数、校验、删除确认可辨识；标签及危险操作语义保留 | PASS | J1 notification-active/returned、reader-route-2、dunning-open/closed、category-validation、delete-confirm；原业务测试通过 | C1/E1 |
| AC-5 | v1 | INT / DEV | 无氛围渐变/聚光/大阴影；卡/书封/排名/图表中性 | 自有CSS旧色/旧变量/渐变扫描无残留；只有none阴影与批准modal mask；实际登录/仪表盘/统计/书封视觉符合 | PASS | 源码Grep及S1；P1 login-default/admin-route-0/stats-fit-390；U3 | C1/E1 |
| AC-6 | v1 | INT / DEV | 不改变业务/API/路由/权限/依赖/边界，不放宽旧测试 | 本轮无源码修改；保护路径git diff为0；全部617后端、410前端、33E2E通过；原业务测试保留 | PASS | 保护路径diff、实际tracked diff摘要与全部逐文件hash见S1；L1 | C1/E1 |
| DoD-1 | v1 | INT / DEV | lint/typecheck/test/build全部exit0 | 本轮入口四项全部成功，410测试及28边界测试 | PASS | L1:1720–2078 | C1/E1 |
| DoD-2 | v1 | INT / DEV | 标准隔离E2E全部通过 | 33/33，临时库18096/15196，原13业务用例仍通过 | PASS | L1:2080–2581；本轮J1 | C1/E1 |
| DoD-3 | v1 | INT / DEV | 真实ADMIN/READER主要页面及状态截图，模拟明确区分 | 本轮E2E再次真实打开全站；102应用截图状态+13明标组件夹具，19个mock状态；12张关键图人工读取；R3同源码完整图继续有效 | PASS | J1 role/route/state/mock/kind；P1；U3；本节下方覆盖说明 | C1/E1 |
| DoD-4 | v1 | ACCEPT / ACC | 独立验收在同一候选复核AC及DoD1/2 | 尚未由主会话派发；本会话没有独立验收权限 | NOT_DUE | 等待ACC真实报告，不以DEV结论替代 | C1/E1待ACC复核 |
| DoD-5 | v1 | INT / DEV | 本次矩阵记录真实执行、候选及证据；旧核验不改 | 本矩阵、339文件S1、独立证据hash和R1仅记录本轮实际结果；旧R1/R2/R3失败记录保留 | PASS | 本文件、S1、R1、L1/J1/P1；后续ACC追加ACCEPT行不重写历史DEV事实 | C1/E1 |
| DoD-6 | v1 | INT / DEV | 共享主题要求的根test-all完整通过 | **本轮实际从backend verify执行到标准E2E并退出0** | PASS | L1全过程及终行，不复用R3局部检查代替 | C1/E1 |

## 独立验收已核验区域（LDR-ACC round 1，2026-11-21）

定义保持v1（`linear-restyle-ac-dod-v1`，方案§8，SHA `551d621d…2ed455`）。执行者：独立验收会话（qmodel_latest，主会话派发 taskId=linear-design-restyle-20261121 / subtaskId=LDR-ACC / plan v1.1 / dispatchRound 1）。候选C1=`linear-restyle-20261121-5c7c5b33b27d`；运行前后均重算339文件digest=`5c7c5b33b27dcd304b72e85d1f286b766d485b9dddeec4cdd16a223326b3fe0c`，0漂移（证据：acceptance/computed/source-digest-pre.json、source-digest-post.json）。环境E1同DEV（Node26.7.0/npm11.19.0/JDK21.0.12/Maven3.9.16/Playwright1.65.0 Chromium）。

**ACC总结论：NEEDS_WORK** —— 存在1个已确认视觉缺陷ACC-D01（403/404渲染antd存量彩色插画，批准方案§4.5 F11/F12中性图标在antd 6.7.1运行时无效），AC-3 FAIL；其余到期必需项PASS。缺陷只报告不修产品；建议主会话交原开发返工后重新冻结候选重验。

ACC证据索引（绝对路径前缀 `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/acceptance/`）：
- **AL** `logs/acc-lint.log`、`acc-typecheck.log`、`acc-test.log`、`acc-build.log`、`acc-frontend-exit-codes.txt`（四项独立exit 0；lint含28边界测试；410/410单测41文件；build成功保留既存>500kB告警）。
- **AE** `logs/acc-e2e.log`、`acc-e2e-exit.txt`（标准E2E独立33/33，exit 0，3.9m，临时库18096/15196）。
- **AP** `logs/acc-probe-round1.log`+`acc-probe-report-round1.json`（独立探针18用例：16过2失败，两失败均为探针脚本定位错误，非产品缺陷）；`logs/acc-probe-r2.log`+`acc-probe-report.json`（针对性重跑ACC-04/06，2/2过）；首轮失败test-failed截图被Playwright outputDir清理机制清除，错误全文保留于round1日志。
- **AJ** `computed/`：103组采样JSON（4757文字样本、0对比度失败、9个真实disabled例外、最低有效比值4.699901、0横向溢出）+`summary.json`+`scope-check.json`+`design-provenance.json`+`source-digest-pre/post.json`+`defect-ACC-D01.json`。
- **AS** `screenshots/`：103张本轮真实截图（密码mask中性surface）；ACC实际读取login默认/hover、ADMIN仪表盘、READER逾期通知、390统计、390日历第二月、催还OPEN、danger Popconfirm、403、404、503、空态等关键图。
- **AX** `scripts/acc.playwright.config.ts`、`acc-visual.spec.ts`、`summarize.cjs`、`package.json`（独立验收证据脚本，不纳入source digest；复用现有helpers未修改）。

| 编号 | 定义 | 阶段/责任 | 预期 | 当前实际 | 结果 | 证据 | 候选 |
|---|---|---|---|---|---|---|---|
| AC-1 | v1 | ACCEPT / ACC | 独立复验原文/provenance | 唯一固定YAML注释块剥离后Buffer与/tmp/linear-DESIGN.md逐字节相等；恢复SHA `30bd30e7…fcb5f`、根SHA `70ca40c5…2d8f5`；注释URL/Ref main/日期/SHA逐行核对 | PASS | AJ design-provenance.json；S1 | C1 |
| AC-2 | v1 | ACCEPT / ACC | 独立复验主题/映射 | linearTheme.ts单源+R1纯包装仅恢复6 seed；global.css/feature CSS仅var(--linear-*)；13主题单测独立绿；运行时body canvas、按钮三态、focus 2px #5e69d1、Modal/Select surface-3、Table hover surface-2、Pagination primary-hover、ghost primary-hover实测一致 | PASS | AL acc-test.log；AJ 103组；AS login/portal截图 | C1/E1 |
| AC-3 | v1 | ACCEPT / ACC | 独立复验全站状态/对比度/响应式 | 103组4757样本0失败、最低4.699901、9真实disabled例外、0横溢；1440/1024/390统计文本/排行/弹层边界与390日历第二月内滚通过；跨月日期可点击且按有效文字采样；**但403/404渲染未主题化存量彩色插画，§4.5 F11/F12中性图标运行时无效（ACC-D01）** | FAIL | AJ summary.json、defect-ACC-D01.json；AS acc-reader-real-403.png、acc-reader-404.png | C1/E1 |
| AC-4 | v1 | ACCEPT / ACC | 独立复验状态语义 | 真实OVERDUE/ACTIVE/RETURNED通知、OPEN(warning)/CLOSED(success)催还、逾期Tag文本、校验error边框、danger实心深字7.1:1实测；语义色仅状态/校验/告警 | PASS | AJ acc-notification-*、acc-dunning-*、acc-danger-popconfirm；AS已读 | C1/E1 |
| AC-5 | v1 | ACCEPT / ACC | 独立复验装饰限制 | 生产CSS/TSX无渐变/聚光/旧绿暖纸/硬编码色（scope-check 0命中）；登录品牌中性重构、卡/书封/图表中性、阴影none；Skeleton功能动效除外。403/404插画问题按AC-3/§4.5归责ACC-D01，不在AC-5冻结枚举内重复计FAIL | PASS | AJ scope-check.json；AS login/dashboard/stats/empty | C1/E1 |
| AC-6 | v1 | ACCEPT / ACC | 独立复验范围与回归 | tracked diff仅23个视觉文件；保护路径（package/锁/pom/eslint/playwright配置/helpers/原3业务spec/api/backend/scripts/旧契约）diff为空；新增源文件恰为批准5个；原13业务E2E独立绿；617后端经DoD-6交叉核对 | PASS | AJ scope-check.json；AE；AL | C1/E1 |
| DoD-1 | v1 | ACCEPT / ACC | 独立执行前端四项 | lint/typecheck/test/build独立exit 0；28边界测试、410/410、41文件；无跳过/放宽 | PASS | AL全部 | C1/E1 |
| DoD-2 | v1 | ACCEPT / ACC | 独立执行标准E2E | 33/33 exit 0；既有webServer契约临时库；未改超时/retries/断言 | PASS | AE | C1/E1 |
| DoD-3 | v1 | ACCEPT / ACC（交叉核对DEV证据） | DEV真实角色/状态证据绑定候选 | DEV integration results.json/test-all.log hash与S1一致（ec3e5923…、6c9ad2fe…）；J1/P1/U3属C1；ACC另以103组独立实测覆盖同范围 | PASS | S1；DEV L1/J1/P1；AJ/AS | C1/E1 |
| DoD-4 | v1 | ACCEPT / ACC | 同一稳定候选的独立结论 | 已在C1完成独立复核并出具结论（NEEDS_WORK，因AC-3/ACC-D01）；运行前后digest一致，无源码漂移 | PASS | AJ source-digest-pre/post；本区域 | C1/E1 |
| DoD-5 | v1 | ACCEPT / ACC | 独立结果/候选/证据追溯 | 本ACCEPT区域与acceptance/report.md、results.json只记实跑；DEV/INT历史与定义未改；新矩阵hash另记于results.json（snapshot旧矩阵hash保持DEV/INT版本） | PASS | 本文件；acceptance/results.json | C1/E1 |
| DoD-6 | v1 | ACCEPT / ACC（交叉核对DEV门禁） | 根test-all全量入口 | DEV L1实跑backend verify 617/0/0/0→前端四项→33 E2E，exit 0，终行"全部检查通过"；hash绑定S1；按交接不新增独立后端重跑 | PASS | DEV L1:1701/2006/2579/2581；S1 | C1/E1 |

ACC未跑与限制：无独立后端verify重跑（DoD-6为DEV到期门禁，已交叉核对）；无跨浏览器/真机/屏幕阅读器（非本次范围）；Table行选择NOT_APPLICABLE（现有feature无rowSelection交互，批准范围限定）；1024仅统计页补测（§8.3为建议项）；ACC-10几何断言round1已执行并通过（见AP round1报告），其原始几何JSON因与同名capture JSON重名被覆盖，视觉_fit另由AS三宽度截图实际读取确认；模拟状态19组明标mock=true，auth/me仅延迟透传；组件夹具13组明标非业务页面。

清理：本轮3个自有临时库（library-e2e-1795255062110/1795255470752/1795255726295及WAL/SHM）已删除；DEV历史临时库未触碰；18095/15195/18096/15196无监听残留；fixture催还/通知行仅删本轮自有记录。

本次独立验收仅覆盖本视觉增量到期项，不是整个系统历史质量/安全/审计门禁或生产发布通过；独立评审未启用/跳过，不计PASS。

## UI复核与证据界限

本轮实际读取P1的全部12张图：登录默认与键盘焦点、管理员仪表盘、读者逾期借阅、真实ACTIVE通知、390px统计完整日期/数量/排行、390px日历弹层、分类校验Modal、图书503/空态/加载、未结案催还。观察到暗色表面层级一致、正文/次级字可辨、错误/警告有明确业务文字，主按钮hover深字符合批准适配；窄屏日期不截字，日历在视口内且第二月可内部滚动。没有新增需修复的视觉问题。

全量E2E继续覆盖15条ADMIN入口、6条READER入口、真实403/404、删除取消/确认、分页hover/翻页、Table hover、Select/DatePicker portal、催还登记/历史/结案、通知未读/已读及三种当前状态、六feature加载/空/503/重试。文字采样合成祖先透明背景/opacity，日历`::before`范围底独立合成并验证选中/范围颜色。最低比值是**实际稳定状态computed样本**，不是从截图估算或常量计算冒充。

- 115组中102组为真实应用页面，13组为标明“组件状态核验（测试夹具，非业务页面）”的实际antd组件：用于现有业务页面没有实例的disabledDate及各控件warning状态，不用于认证/角色业务通过证明。
- 19个模拟状态：六个只读feature接口各加载/空/503共18；一个auth/me仅延迟后透传真实响应。没有伪造认证/角色；恢复重试使用真实后端。
- 9个排除样本全部来自真正disabled控制/日期；有效placeholder、脚注、次级提示、可用跨月日期没有排除。逐项理由保留在J1。
- Table行选择：NOT_APPLICABLE，仅因为现有feature表没有rowSelection交互且批准范围明确“已有该交互时”；没有为了核验增加新交互。
- R3源码hash本轮未变化，故U3证据保持有效；本轮又执行全量入口并复核当前截图，不把R3局部PASS拼接成INT。

## 已知限制、未执行与历史边界

- 无本次已知阻断项。前端>500kB chunk告警仍存在，未通过修改阈值或无关分包重构隐藏。
- 独立验收未执行；评审按用户选择未启用/跳过，**不计PASS**。部署、发布、提交、推送均未授权且未执行。
- 本次没有重新宣称首版/提醒历史DOD-11、审计或其他旧BLOCKED条目关闭。历史定义/记录未改写。
- 没有重做新克隆安装、生产审计、真实设备/屏幕阅读器认证或跨浏览器认证；它们不是本次固定视觉增量的到期开发门禁，不据此声称整个系统历史质量门禁全部通过。
- 执行方法：沿用`verification-before-completion`、既有前端实现与`webapp-testing`证据纪律；无新Skill安装、会话/模型调度或环境配置变更。
