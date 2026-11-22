# Claude亮色视觉改造：批准方案与实施计划

## 1. 版本、批准与交付边界

- taskId：`claude-light-restyle-20261121`；stage：solution-designer；dispatchRound3；日期：2026-11-21。
- 固定版本：`claude-light-design-v1`、`claude-light-plan-v1`、`claude-light-ac-dod-v1`。由v0.1按用户决定定稿，不新增条件、文件、功能、依赖或预算。
- 状态：READY_FOR_APPROVAL（方案交接）。用户已批准以下定义与限定实施；这不是应用测试、集成或独立验收PASS，也不是提交、推送或发布授权。
- 来源主会话：sessionId=`d46a3369-9cac-4eba-9bf8-30721886321e`；sessionRef authorityEndpointId=`42677bcf-0da3-4a4d-b3f6-0f2b31e32e91`，sessionId同前。
- 方案输入：轮次1 Turn `7885dab4-253c-560f-a11f-0bc8b4c105d9`；轮次2回调 Turn `agent-message:165746a1374f3c5e4fbade79953dc40ab517bfb9f375badcded152bf69e76324`。本轮主会话已完整读取并展示后转交真实AskUserQuestion答案。
- Q-CLR-01「Claude亮色按钮采用哪种可读性适配？」真实回答：**深陶土白字 (Recommended)**。批准方案A及本文件颜色表，不降低启用文字≥4.5、焦点≥3要求。
- 「是否批准完整方案及本次AC1–5/DoD1–6，并允许限定文件改动和暂停自有程序进行验证？」真实回答：**批准并验证后恢复 (Recommended)**。
- 授权包含：根规范切换、精确旧根存档、保存本方案、必要主题/视觉测试rename与原列前端视觉改动；主会话暂停自有dev，DEV/INT/ACC在临时库串行验证后由主会话恢复dev。不得据旧任务授权提交/push/deploy。
- selectionId=`3b58bfb2-0c00-4cf6-8da8-09abe51d3795`，主会话已核验confirmed：方案ultimate，DEV/ACC qmodel_latest，独立评审关闭，不计PASS。1个DEV会话分轮实现/INT，1个ACC会话，不增加会话或改变模型。
- 本方案会话本轮仅获写入本文件的权限；不修改根规范、存档、应用、测试、旧文档或核验记录，不操作服务/数据库。本文件保存后只读。

## 2. 固定输入与规范追溯

- workspaceId：`f1c40b80-c306-474e-9051-af7c1b2a0de1`；根目录：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10`。
- 基线HEAD：`571c44c7863c50cf45740386a355520ded041586`。轮次1及本轮实测一致；跟踪树轮次1无修改，旧Linear未跟踪截图/trace/日志保留，不作本次证据。
- Claude源：`/Users/duff/.qoder/plugins/cache/local/sdlc-agent-orchestrator/0.6.2/design-styles/claude/DESIGN.md`。
- Claude源SHA256：`3d972f34f8d4f33b442fa28220cf3b7ca2f13c0a97c468e3988148f82521f9df`，已完整读取并实测；不下载新版本替换。
- 旧根规范：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/DESIGN.md`，整体SHA256：`70ca40c5a5dadee01eb368135ad71d1bce1ab102fa9f95183277f6e7d1b2d8f5`。
- 后续DEV先将旧根完整字节精确保存到`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/linear-upstream-design.snapshot.md`，包括原来源块，整体SHA必须等于旧根SHA；已有目标内容冲突时停止报告，不覆盖未知材料。
- 新根只在Claude源首个`---`行之后插入下列唯一固定YAML注释块，其他源字节不变。校验严格剥离该块、还原Buffer与源比较；来源SHA与新根整体SHA分开记录，不YAML重序列化。

```yaml
# CLAUDE-UPSTREAM-PROVENANCE-BEGIN
# Source: /Users/duff/.qoder/plugins/cache/local/sdlc-agent-orchestrator/0.6.2/design-styles/claude/DESIGN.md
# Slug: claude
# Plugin-Version: 0.6.2
# Imported-Date: 2026-11-21
# Task: claude-light-restyle-20261121
# Original-SHA256: 3d972f34f8d4f33b442fa28220cf3b7ca2f13c0a97c468e3988148f82521f9df
# CLAUDE-UPSTREAM-PROVENANCE-END
```

- 本文件记录后台适配，不改变上游营销原文；源中的dark mockup、Claude Logo、营销hero并非本任务实现要求。
- 已读且继续只读的契约：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/AGENTS.md`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/solution-design.md`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/ac-dod.md`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/reminder-increment.md`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/approval-record.md`。
- 基础批准版本design-v0.1-R1/AC v1、提醒AC v1.1仍约束既有行为。旧独立评审属于各自历史任务，不改写标准或PASS；本次项目强制全量入口仍必需。

## 3. 视觉规格与单源映射

目标是图书馆后台更温暖、可读、有书卷气；保留「图书馆管理系统」品牌及全部业务，不引入聊天/API/主题切换/在线字体/新依赖/后端改造。

| 单源token用途 | 批准值 | antd/CSS映射与组件用途 |
|---|---|---|
| canvas | #faf9f5 | colorBgBase/Layout/Elevated/Spotlight；页面、顶栏、输入及portal；color-scheme:light |
| surface-soft/card/cream-strong | #f5f0e8 / #efe9de / #e8e0d2 | colorBgContainer及侧栏/卡片；表头、筛选、hover/range；菜单选中/强调面板 |
| ink/body | #141413 / #3d3d3a | colorTextHeading / colorText；标题、正文；colorTextBase=ink |
| text-muted/disabled | #625f58 / #8e8b82 | Secondary/Tertiary/Label/Description/Placeholder=muted；Quaternary/Disabled=disabled，仅真实禁用 |
| hairline/control-border | #e6dfd8 / #817d73 | colorBorderSecondary/Split=hairline装饰；colorBorder及有效控件边界=control-border |
| brand-primary / on-primary | #cc785c / #ffffff | 原陶土仅有限品牌强调；白字用于批准深陶土实色，不作原陶土小字链接 |
| primary/hover/active | #a9583e / #934b34 / #7d3d2a | colorPrimary/Hover/Active；主按钮与选中日期白字 |
| link/focus | #934b34 | colorLink、PrimaryText、Pagination当前文字、ghost；link/Text hover及active=#7d3d2a；controlOutline=#934b34 |

- 新主题模块导出`claudeTokens`、`claudeTheme`、`claudeCssVariables`、`applyClaudeCssVariables`。颜色、字体、间距、圆角与状态集中一处；CSS按用途引用`--claude-*`，不保留linear别名或重复色值。
- 使用公开`theme.defaultAlgorithm`；本机antd6.7.1只读诊断证明推荐六seed均精确保留、getDesignToken显式token零差异，故不沿用dark包装，也不新增亮色包装。仍须在应用测试中验证最终解析结果，而非只查配置对象。
- 六seed：Primary=#a9583e、Link=#934b34、Success/Error/Warning/Info为下表foreground。各语义Hover/Active/Text/TextHover/TextActive显式等于foreground，Bg/BgHover和Border/BorderHover按表覆盖；colorHighlight=error。

| 状态 | foreground | background | border | 文字/背景实算 |
|---|---|---|---|---|
| success | #2f6b40 | #eaf3e8 | #d3e9d4 | 5.605 |
| warning | #805c14 | #f6f0df | #f1e3be | 5.334 |
| error | #a53636 | #f5e7e3 | #edccc9 | 5.477 |
| info | #28695e | #eaf3ed | #d3e9e1 | 5.666 |

- 状态延伸来源为Claude原success=#5db872、warning=#d4a017、error=#c64545、teal=#5db8a6；背景/边框分别按10%/25%原色与canvas作sRGB通道混合并round。深色前景为批准的后台可读性适配，不伪称上游原值；仅用于必要Tag/Alert/校验，不装饰普通卡片。
- Layout/Sider/Menu转light；菜单itemBg=soft、文字muted、hoverBg=card/文字ink、selectedBg=cream-strong/文字ink，删除dark组件映射。保留角色、折叠、232px侧栏、64px顶栏及窄屏用户名约束。
- Button primaryColor/dangerColor=white；默认按钮canvas/ink/控件边界；实色primary三态皆白字，删除旧Linear hover反色规则；ghost及非实色primary显式可读link。危险动作含义和确认不变。
- Input/InputNumber/Select/DatePicker输入canvas、正文body、提示muted；hover边界control-border、active边界focus。activeShadow/errorActiveShadow/warningActiveShadow=none，避免双轮廓。全局保留足够specificity的2px实色focus/offset2px；错误/警告保留各自foreground边框。
- DatePicker仅非disabled跨月日期选择器用muted，不覆盖真正disabled；range底card、选中日期primary/white。保留移动端两面板堆叠、纵滚、宽度与第二面板可达性。
- Pagination itemActiveColor=link、hover=#7d3d2a；不可依赖primary默认值。Tag保留success/warning/error/processing语义及文字，普通Tag为card/muted。Alert标题ink、正文body、状态图标foreground；Badge保持中性可读底字和计数语义。
- Card软面板，Table表头/hover/range为card、正文canvas或soft；Modal、Select dropdown、Popover/Popconfirm/Dropdown/Message/Notification等portal为canvas/深色字及细边界。Tooltip必须组件级colorBgSpotlight=canvas、colorTextLightSolid=body，不只覆盖colorText；全局LightSolid=white留给实色按钮/日期。
- 403/404维持Result不传异常status、实际Lock/Search图标及返回首页；中性图标muted，不重加异常插画。空态、加载、错误重试、503文案、提交禁重复及业务状态保持。
- 图表沿用现有CSS图形与数据，用body和深teal区分系列，保留图例/数值；书封仍为中性暖纸层级，不新增素材、图表库或营销装饰。
- 字体：display=`Georgia, 'Songti SC', STSong, SimSun, serif`，400；body=`-apple-system, BlinkMacSystemFont, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'Segoe UI', sans-serif`；mono沿用本地等宽回退。页面标题28px/窄屏24px、次级22px；正文14/16px，正文行高1.55；不下载字体。标题serif不强套表格/表单或所有小标签。
- 圆角输入/按钮8px、卡片12px；4px间距基数，面板16–24px内距。保留40px常规、44px大控件及窄屏触控增高；不套96px营销分段。阴影稀少，普通卡片无阴影，portal可用0 1px 3px rgba(20,20,19,0.08)。保留短颜色过渡/功能动效及reduced-motion，无新氛围渐变。
- favicon：保留原图书SVG静态几何；从同一主题token生成固定SVG URI，main同步设置已有link，去掉index旧色URI；不建立图标框架、新增资源/依赖或扩大图标范围。

### 对比度决定依据（静态sRGB诊断，不是浏览器PASS）

白字/原#cc785c为3.277（不满足4.5）；批准default/hover/active白字为5.057/6.370/8.162。body/canvas=10.344；muted、link在四背景最低4.860/4.861；原#6c6a64/card=4.476，原15%陶土焦点环/canvas约1.160，均不作为合格组合。control-border在四背景最低3.133；focus最低4.861。四类状态前景在四背景最低均≥4.5，危险实色白字亦达标。启用文字含小字和有效placeholder仍全部≥4.5；仅真正disabled例外单列，实际透明度/背景/伪层须浏览器核验。

## 4. 精确读写范围

readScope：上述规范/契约、相关现有前端源码/测试和运行脚本、本机antd类型/公开实现；不读无关旧截图/trace/日志。以下是后续已批准限定writeScope；本轮仅写本方案文件。

| 分组 | 允许操作的绝对路径 |
|---|---|
| 规范 | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/DESIGN.md`；新`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/linear-upstream-design.snapshot.md`；本`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/claude-light-restyle-design.md`（本轮定稿后只读） |
| rename主题 | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/styles/linearTheme.ts` → `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/styles/claudeTheme.ts` |
| rename主题测试 | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/styles/__tests__/linearTheme.test.ts` → `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/styles/__tests__/claudeTheme.test.ts` |
| rename视觉E2E | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/e2e/linear-theme.spec.ts` → `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/e2e/claude-theme.spec.ts` |
| 共享消费 | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/App.tsx`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/main.tsx`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/styles/global.css`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/app/layouts/AppLayout.tsx`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/components/PageLoading.tsx` |
| catalog | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/features/catalog/components/BookCover.tsx`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/features/catalog/pages/BookDetailPage.tsx`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/features/catalog/pages/BookSearchPage.tsx` |
| 四份CSS | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/features/stats/stats.css`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/features/circulation/circulation.css`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/features/notifications/notifications.css`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/features/dunning/dunning.css` |
| favicon | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/index.html`，仅旧配色移除/现link配合同源初始化 |
| 新任务证据 | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/claude-light-restyle/`，包含development、acceptance证据及矩阵/候选记录；不可覆盖旧任务 |

只读保留：原login/smoke/reminders业务E2E、helpers、Playwright配置、其它业务测试/源码、API/权限/路由逻辑、package/锁文件、后端及迁移、AGENTS/边界规则、README、旧Linear契约及`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/linear-restyle/`。不操作`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/backend/data/`及WAL/SHM、`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/bak/`。不改生成物/node_modules；正常构建产物除外。需要范围外修复先回主会话。

## 5. 实施计划与资源约束

`planVersion=claude-light-plan-v1`；稳定ID按本轮交接为CLR01/CLR02（对应草案CLR-01/CLR-02，不新增节点），CLR-INT不变。全部required=true；一个DEV会话内部串行，不按文件不同推定独立并行。

| subtaskId / operation | dependsOn | readScope / writeScope / 输出、验证与映射 |
|---|---|---|
| CLR01 / implement | [] | 读§2契约/源、主题/main/App及本机类型；写§4规范切换/旧根存档、主题/主题测试rename、App/main。输出来源SHA、公开解析精确token、CSS映射/同步初始化幂等与针对性测试证据；AC1–3。新主题为下游唯一输入，不新增架构。 |
| CLR02 / implement | [CLR01] | 读§4相关页面/CSS/原测试/配置；写§4其余视觉路径、视觉E2E rename/ref与批准颜色/名称断言。保留全部业务/graph/contrast/focus/role/响应式断言，完成前端四项、标准E2E、实际浏览器自测及新development证据；全部AC、DoD1–3/5。完成所有本职自测后返回主会话，失败/未测如实披露，不自行进入INT。 |
| CLR-INT / integrate | [CLR01, CLR02] | 主会话核对全部必需实现及DEV本职自测后单独派发；读全部候选/定义/自测证据；只写已批准源码/测试范围内必要集成修复及新矩阵/候选/证据，不修改本冻结设计或旧材料。运行根test-all、补齐证据、停止候选写入并冻结；全部AC/DoD。 |

- 同一DEV会话先CLR01→CLR02→返回主会话核对/等待，后独立integrate派发CLR-INT。冻结后主会话再派1个ACC；开发不得自行调度INT或ACC。评审未启用，不计PASS。
- 主会话在实施前按批准暂停自己的dev（18095/15195），重新核实进程归属，不用历史PID直接杀端口；方案/DEV/ACC不自行推定进程权限。
- DEV/INT/ACC只串行用既有E2E18096/15196、系统临时数据库；不得与dev或另一组E2E并行，不能用开发库借还。后端target、前端dist/测试报告/候选亦共享写资源。脚本一律bash；Java21与npm路径遵守AGENTS，不改全局配置或新增依赖。
- 全部验证完成后主会话负责恢复自有dev，按用户「批准并验证后恢复」决定执行；是否交付通过与服务恢复分别汇报，未解决失败不能宣称完成。
- ACC读取同冻结候选，writeScope仅新acceptance证据与本次矩阵ACCEPT行，发现缺陷回主会话/DEV，不自行修代码。返工保留ID增加轮次，重新验证/冻结，不盲目重试未知副作用或放宽断言。

## 6. 批准AC/DoD与核验状态

定义=`claude-light-ac-dod-v1`；以下全部必需。IMPL/INT由DEV核验，ACCEPT由ACC独立核验。批准是定义/限定实施授权，不是执行PASS；当前应用核验全部NOT_RUN，不以历史结果替代，也不将未执行项标NOT_DUE。

| ID | 可观察条件 | 到期阶段 / 责任 | 方法、证据与任务映射 | 当前 |
|---|---|---|---|---|
| AC1 | 新根指定原文+唯一来源块能字节还原；旧根精确存档，历史不改 | IMPL/INT DEV；ACCEPT ACC | Buffer比较/源与旧根SHA/新根SHA/来源记录；CLR01/INT | NOT_RUN |
| AC2 | 全站仅Claude亮色，antd/CSS单一token源，无Linear生产变量/旧主题/dark残留，无toggle | IMPL/INT DEV；ACCEPT ACC | getDesignToken精确alias/颜色、CSS映射、同步初始化幂等、类型/源码搜索、实际computed；CLR01/02/INT | NOT_RUN |
| AC3 | 系统serif标题/sans正文可读；所有启用文字/小字/按钮/link/placeholder≥4.5，2px实色焦点offset2px且≥3；状态/必要图形可辨；真实disabled例外不扩至有效提示/可点击跨月日期 | IMPL/INT DEV；ACCEPT ACC | 配色测试+真实DOM颜色/透明度/祖先背景/日期伪层；default/hover/active/focus/校验/portal截图与图形证据；CLR01/02/INT | NOT_RUN |
| AC4 | 角色/API/路由/缓存/业务不变，危险确认/提交禁重复/借还焦点/提醒催还语义及原业务测试保留 | IMPL/INT DEV；ACCEPT ACC | diff/不变文件SHA；原单测+标准E2E；借还/权限/已读/催还结案真实操作，不删跳放宽；CLR02/INT | NOT_RUN |
| AC5 | ADMIN/READER主要路由与加载/空/错误重试/有数据覆盖；1440/390及统计1024无页面横溢；日期堆叠/纵滚/第二面板可达，select完整值/长标题约束、403/404实际Lock/Search及返回首页正常 | IMPL/INT DEV；ACCEPT ACC | route/role/viewport/state清单、真实与模拟分列；截图/尺寸/text-fit/popup/滚动/导航及保留graph/focus/role断言；CLR02/INT | NOT_RUN |
| DoD1 | lint/typecheck/test/build均exit0，真实计数，不删跳测试消失败 | IMPL/INT DEV；ACCEPT ACC | 标准四命令日志/退出码/实际用例数；lint含边界检查；CLR02/INT | NOT_RUN |
| DoD2 | 标准E2E全部通过；既有隔离端口/系统临时库，不与dev/其它E2E并行、不影响开发库 | IMPL/INT DEV；ACCEPT ACC | 标准命令日志/Playwright报告/必要trace/实际计数/端口库来源；保留24视觉+13业务覆盖；CLR02/INT | NOT_RUN |
| DoD3 | DEV真实打开并操作两角色主要页面，完成状态/desktop/mobile/graph/focus自测 | IMPL/INT DEV | 逐页操作、截图/computed、route/role/viewport/state；模拟GET失败/空态单列不冒充真实业务；CLR02/INT | NOT_RUN |
| DoD4 | 共享主题跨模块，INT完整运行bash scripts/test-all.sh成功 | INT DEV | backend verify→frontend四项→标准E2E完整日志/退出码/环境；任一步失败后的未执行项不标PASS；CLR-INT | NOT_RUN |
| DoD5 | 新矩阵每项固定定义/阶段/预期实际/结果/方法/执行者/证据/候选/环境齐全，旧材料原版本保留 | IMPL/INT DEV填；ACCEPT ACC复核 | 新矩阵及HEAD/diff/SHA清单/环境命令；新development/acceptance目录；CLR02/INT/ACC | NOT_RUN |
| DoD6 | ACC同冻结候选独立验收给真实结论；不提交/push/deploy、不触碰开发库/WAL/SHM/备份、不改历史结果 | INT DEV供候选；ACCEPT ACC | 验收前后候选一致性、独立日志/截图/报告、范围及历史不变；变更返工重验再冻结；INT/ACC | NOT_RUN |

- 矩阵结果仅PASS/FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLE/NOT_DUE；缺证据和已证实失败分列。评审关闭不计PASS；旧历史门禁不删改。
- 冻结清单覆盖源码、配置、测试、规范和本次新增文件，记录HEAD/diff/SHA、Node/npm/JDK/Maven及适用环境，不纳入数据库、备份、缓存/生成产物。修复颜色/共享主题影响全站，旧相关证据失效，重跑主题/四项/E2E/浏览器及INT全量后再给ACC。

## 7. 实际证据、未测与风险

- 轮次1实际调查：完整源/契约/批准记录、HEAD及SHA；主题与13主题用例、main/App、五份CSS、布局、书封/检索/详情、favicon、403/404与视觉E2E/业务用例/Playwright配置及test-all脚本。静态枚举24视觉+13业务E2E；全前端410测试仅交接基线，非本轮执行计数。
- 源码定位：`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/styles/linearTheme.ts:75–183`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/main.tsx:7–10`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/app/layouts/AppLayout.tsx:60,81`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/styles/global.css:26–54`；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/src/features/stats/stats.css:71–83`。
- 只读Node诊断：antd6.7.1公开defaultAlgorithm精确保留推荐六seed、getDesignToken显式token零差异、重复调用确定且输入未变；TypeScript5.9.3内存ThemeConfig诊断0。alias内部删除seed普通覆盖，Tooltip实际消费Spotlight/LightSolid，故保留公开精确解析和组件渲染验证，不直接假定token等于最终色。
- 实际加载frontend-design-direction形成后台方向，brainstorming用于调查与最小取舍；未执行其中未授权写文档/提交/视觉陪伴/直接问用户/调度步骤。轮次2无工具/新Skill；本轮仅确认父目录/固定输入哈希并原生保存本文件。
- 本方案各轮未运行应用Maven/npm测试、浏览器、E2E、集成或独立验收，未启动/停止服务或操作数据库。常量/类型/算法diagnostic不是应用PASS。
- 已知风险：字体变化影响表格/select尺寸；portal和校验specificity造成漏色/双轮廓；日期伪层/透明度影响实际对比度；rename漏引用及favicon旧色。由批准路径内精确回归、真实computed/截图、标准E2E和冻结候选解决，不额外扩项。
- 无未决视觉/业务问题：主会话真实答案已解决Q-CLR-01与合并授权。后续仅主会话负责暂停dev、派发DEV/INT/ACC及完成后恢复；出现新范围/权限阻断回主会话，不自行放宽条件。
