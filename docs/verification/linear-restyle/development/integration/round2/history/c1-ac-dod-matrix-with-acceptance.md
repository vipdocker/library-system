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
