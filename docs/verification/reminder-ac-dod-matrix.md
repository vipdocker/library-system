# 首期借阅提醒：唯一集成候选核验矩阵

## 1. 定义、授权与候选

- taskId：lib-borrow-reminder-p1；subtaskId：REM-INT；operation：integrate；planVersion：reminder-plan-v1.5；派发轮次：3（恢复原唯一集成会话；轮次1无最终交接、轮次2为NEEDS_WORK；既有有效检查保留，固定定义未修改）。
- 定义：增量 AC/DoD v1.1，基础 AC/DoD v1、design-v0.1-R1；基础草案正文和历史结果未重写。
- 定义路径（以下绝对路径根统一为 `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10`，表内证据代号按第2节展开）：
  - `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/reminder-increment.md`，SHA256 `c0c38f35f3ca6e78f89294e4838f67538ef181293567855b2de00d9409ae1ede`。
  - `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/approval-record.md` §4–4.3，SHA256 `4b70c32ac2309969e1be5bdcc15433ceb582241f59c3fc330b69b25debb583cc`。
  - `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/design/ac-dod.md` §2/5；只按第5节覆盖菜单排他性定义，未降低原门禁。
- 授权：主会话核验的 confirmed selectionId `ec0966d6-228e-4349-a00a-4da3b5ff92c3`及真实业务/文件/临时库授权；本轮仅串行集成，不调度、不提交、不推送、不发布。方案设计跳过，不计PASS。
- 当前 HEAD：`6c7943278686e3e99a3d7dc3b497e1879b049587`（main），候选未提交，不能只用HEAD识别。
- 候选标识：REM-INT-v1.5-r3，逐文件清单、依赖/配置与证据SHA256见 `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/reminder-candidate-snapshot.txt`。本矩阵由该清单散列绑定；不循环包含清单自身hash。后续任何修改使受影响证据失效。
- 初始323个源码/配置/契约文件聚合SHA256：`1b61c4ce153341b1ac79d7473c608e9cf68113aaeadf96f1f43b9ebd2b1ff2ee`；本轮只改README、增加本矩阵/快照及证据脚本/日志/截图，未改业务源码或原测试。
- 排除且保护：docs/hongkong-*、香港两个scripts及scripts/__pycache__、原验收stray jar、.vite缓存、现有开发数据库、其余历史报告。未读取无关文件内容、删除、暂存或提交。.vite/deps元数据optimized/chunks为空，作为Vite生成缓存保持原状，非候选。
- 环境E：本机arm64，实际执行Homebrew JDK21.0.12、Maven3.9.16、Node26.7.0/npm11.19.0、Playwright1.65.0 headless chromium；宿主工具Node24.22.1未用于npm验证。Maven实报macOS26.6.2；平台提示OS25.6.0，保留差异，不改环境。
- 每行执行者I：REM-INT开发/集成自测；不是独立评审或验收。表内PASS仅对IMPL列明部分有效。独立责任R=REV、A=ACC目前均NOT_DUE，没有授权本会话替代。

## 2. 证据索引与实际命令

绝对证据目录L=`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/logs/reminder`；S=`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/docs/verification/screenshots/reminder`。所有代号是确定的文件定位，不是未填占位。

| 代号 | 实际命令/方法 | 实际结果与证据位置 |
|---|---|---|
| B | bash下`source scripts/env.sh && mvn -f backend/pom.xml verify` | exit0；611 tests，0failure/error/skipped，BUILD SUCCESS，03:05；L/backend-verify.log；统计复核L/backend-report-summary.log；`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/backend/target/site/jacoco/index.html`生成 |
| F | `/opt/homebrew/bin/npm --prefix frontend run lint`、`typecheck`、`test`、`build` | 四项exit0；40文件397 tests通过，无失败/跳过；L/frontend-{lint,typecheck,test,build}.log；build主chunk927.80kB，有>500kB告警非错误 |
| P | 外层未设置扫描变量，标准`/opt/homebrew/bin/npm --prefix frontend run e2e` | exit0；原8+新5=13，0unexpected/flaky/skipped，1.8min；L/standard-e2e.log；L/e2e-report-summary-corrected.log复核实际HTML统计及5个pageerrors附件为空 |
| P图 | 当前标准E2E的真实浏览器截图 | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/test-results/e2e/*/*.png`共12张；到期/续借旧通知/逾期/读者分页/私密历史/ID归还/条码归还/结案旧通知/角色拒绝；完整报告`/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/playwright-report/index.html` |
| T | 定向重复当前候选高风险测试3次 | 每轮96 tests、0failure/error/skipped、BUILD SUCCESS；36.569/34.952/36.007秒；L/high-risk-run1.log、run2.log、run3.log。不是用旧611结果替代复验 |
| U | `/opt/homebrew/bin/node docs/verification/logs/reminder/readme-smoke.mjs`（外层扫描变量未设置） | 修订验证流程后run2 exit0；启动7103/6869ms；真实管理员/reader2登录、空案件、电话登记、备注隔离、角标、390px、条码结案、旧通知；模拟503/挂起提交单独标记；L/readme-smoke-run2.log、S/readme-*及mock-registration-503-retained-input.png；0pageerror；TERM结束自己的脚本，exit143 |
| C | `python3 docs/verification/logs/reminder/ctrlc-smoke.py`，真实PTY发送ETX | exit0；6.946秒就绪，PTY键盘Ctrl-C后dev.sh exit130，18098/15198均释放；L/ctrlc-smoke.log；不是TERM替代 |
| N | `/opt/homebrew/bin/npm --prefix frontend audit --omit=dev --audit-level=high` | 本轮exit0，found0 vulnerabilities；L/npm-audit.log。默认registry=https://registry.npmjs.org、strict-ssl=true；未改源/代理/TLS。生产依赖审计不含dev依赖，不替代静态评审 |
| X | JDK8下Maven validate | 实际Maven exit1，enforcer RequireJavaVersion明确失败，预期拒绝PASS；L/java8-negative-confirmed.log。JDK17未安装未另测，不伪称执行 |
| Q | 输入/静态范围自检 | L/input-static-check.log核对372个上游清单项无不匹配；L/static-check-corrected.log确认V1/pom/package/lock/原E2E未改、无可执行REQUIRES_NEW或外发HTTP/mail/SMS client；人工阅读新增SQL参数绑定、JPQL @Param、角色/CSRF边界。只是开发预检查 |
| ENV | 显式/bin/bash source env.sh打印版本 | exit0；L/environment-confirmed.log。首条环境日志的shell包装失败保留为L/environment.log，不作为PASS |
| K | 轮次2获授权的一次性空依赖/无产物隔离候选副本，真实npm ci → JDK21 verify → build | 三项exit0；初始node_modules/target/dist/.git/data均无；329输入逐文件hash与workspace前后相同；npm ci真实安装316包、审计317包found0；verify611/0/0/0，build927.80kB。L/clean-candidate-{npm-ci,backend-verify,frontend-build}.log及clean-candidate-summary.json；不是git clone，轮次2因此保留NEEDS_WORK，已由K2关闭字面方法缺口 |
| K2 | 轮次3真实本地`git clone --no-hardlinks`基线仓库，再覆盖精确329当前未提交候选allowlist；空依赖/无产物真实ci → verify → build | `.git`存在、初始git status干净、HEAD6c794327…；overlay只产生预期候选差异、329输入与workspace前后100%匹配。npm ci安装316包exit0/3.906s；JDK21 verify611/0/0/0、BUILD SUCCESS、exit0/173.350s，实际surefire统计已复核；前端build exit0/6.732s。L/clone-candidate-{npm-ci,backend-verify,frontend-build}.log、clone-candidate-summary.json及clone-candidate-execution.log；未声称未提交文件天然在clone内，固定DOD-01标准未改 |
| U-old | 上游前端浏览器边界，源码hash仍一致 | `/Users/duff/Documents/Lingma-Demo/qoder-pl-test10/frontend/test-results/rem-fe-dunning/browser-report.json`、candidate-snapshot.json、mock-*.png；当前核对sourceFiles/config/证据匹配（仅Playwright配置是获批v1.5 delta）。REAL6组与MOCK2组分开；旧局部结论不冒充本轮整体执行 |

### 数据库与服务隔离

- P：`/var/folders/4k/152xtrds5l5080jc5d_s58s00000gp/T/library-e2e-1795141673101.db`，18096/15196；仅Playwright后端子进程PT1S，生产ReminderProperties及Scheduled默认PT1H、test profile off。只读取该临时库核对reader2原逾期种子id2未还；其其它合法历史已归还种子不误判为违规。
- U最终：`/var/folders/4k/152xtrds5l5080jc5d_s58s00000gp/T/library-reminder-int-1SOl6B/library.db`，18097/15197，默认PT1H；U首次失败的独立临时库`.../library-reminder-int-z7pykJ/library.db`保留。两次均只停止自己的dev.sh。占用端口的第二个dev.sh明确拒绝，未杀第一实例。
- B/T：TestDatabase/TempDir创建的独立临时SQLite；V1升级空库/旧借阅库在测试TempDir执行，启动测试random port。未执行默认LIB_DB_PATH的dev启动，未读写/迁移/重置backend/data。
- C：`/var/folders/4k/152xtrds5l5080jc5d_s58s00000gp/T/library-reminder-ctrlc-hmqu3e8j/library.db`，18098/15198，PTY发送ETX真实Ctrl-C，脚本退出130、两端口均释放。
- K隔离候选：`/var/folders/4k/152xtrds5l5080jc5d_s58s00000gp/T/library-reminder-clean-candidate-s6gdo_j7`，329输入聚合SHA256 `64cf10ecb380cd313957180463b0b1d14434c05ecf7ec1821ec091a369b284a1`；逐输入manifest位于该目录verification-inputs.json（SHA256 `34bf94d64d3084805d369017b8b8a84634f5f988c62d59f960113b8d973dabb1`），验证前后workspace/副本业务输入均匹配；未复制.git/node_modules/target/data/dist/香港文件/cache。
- K2真实clone：`/var/folders/4k/152xtrds5l5080jc5d_s58s00000gp/T/library-reminder-real-clone-gycbm2sk/candidate`，HEAD=`6c7943278686e3e99a3d7dc3b497e1879b049587`，实际`.git`存在；父目录clone-overlay-inputs.json记录329条（SHA256 `34bf94d64d3084805d369017b8b8a84634f5f988c62d59f960113b8d973dabb1`），与K同一输入聚合`64cf10ec…`，当前workspace/clone前后100%hash匹配。最终矩阵/快照/验证日志不属于构建输入，未overlay入clone；README、批准契约及全部当前业务/测试/配置均在329白名单内。测试显式临时DB，不触碰现有开发库。
- 临时库、普通隔离候选与真实clone保留作复验，未破坏性删除；最终监听检查见生命周期证据。

## 3. 增量AC逐条矩阵（全部必需，定义v1.1）

所有行候选/环境均为REM-INT-v1.5-r3/E；执行者I。定义位置§4的预期不可按实现修改；表内后续阶段明确保留NOT_DUE。

| 编号 | 阶段与责任 | 预期 | 实际 | 结果(IMPL) | 方法与证据 | 后续独立阶段 |
|---|---|---|---|---|---|---|
| AC-REM-01 | IMPL/I | D-3含书/条码/应还日恰一条；D-4及以前不发；D-2..D补一次；D+1不补 | dueWindow参数3/2/1/0、dateBoundary及P实际扫描/页面通过；WHERE阈值静态阅读 | PASS | B/T ReminderApiTest.dueWindow/dateBoundary；P tests80/132与due-soon.png；Q ReminderWriter | REV/ACC NOT_DUE |
| AC-REM-02 | IMPL/I | D按期，D+1/8/15固定周期、停机只补当前；UTC16上海午夜；已还不发 | overdueCycle1/7/8/14/15/19/100、continuousCycles、15:59:59→16:00边界通过；P第10天只seq2；归还后通知无增加 | PASS | B/T ReminderApiTest.overdueCycle/continuousCycles/dateBoundary、DunningApiTest.midnight；P132/350 | REV NOT_DUE |
| AC-REM-03 | IMPL/I | 重复/并发/重启幂等；DB_BUSY可见并后续恢复，不破坏接口 | 8独立连接并发×3，freshDS重启、外部SQLITE_BUSY+WAL读+后续扫描成功；真实启动重启不重复 | PASS | B/T ReminderConcurrencyTest.independentConnections/busyRecovery/unexpectedFailure；ReminderSchedulerTest.busyThenRetry；ReminderStartupTest；U/P | REV NOT_DUE |
| AC-REM-04 | IMPL/I | 续借新应还日重算；旧通知保留未读，当前状态/日期展示；原续借权限不变 | 实际ADMIN续借+30日、旧id/readAt保留；后端Clock前进至新D-3产生第二条；读者不自助续借 | PASS | B/T ReminderApiTest.renewedAndReturned（新D-3再进入）；原RenewApiTest；P80续借UI与renewed-historical-notice.png；F菜单/借阅测试 | ACC NOT_DUE |
| AC-REM-05 | IMPL/I | 两种提交顺序符合契约，归还后不新增、不死锁；原归还行为不变 | returnWins零通知、scanWins保留已提交通知且后来零新增；P/UI按ID/条码归还成功 | PASS | B/T ReminderConcurrencyTest.returnWins/scanWins；ReminderApiTest.returnedFirst；原ReturnApiTest；P80/350、U | REV NOT_DUE |
| AC-REM-06 | IMPL/I | 配置默认正确、非法启动失败；test无调度；启动扫在种子后 | true/3/7/PT1H/true；8非法值即使disabled也失败；test bean不存在；真实启动seed先scan | PASS | B/T ReminderConfigurationTest.defaults/invalid、ReminderSchedulerTest、ReminderApiTest.testProfile、ReminderStartupTest；P日志；U默认PT1H | REV NOT_DUE |
| AC-NTF-01 | IMPL/I | 本人分页倒序、未读数/单条幂等/全部已读；事件刷新角标且无后台轮询 | P22条跨两页、过滤/排序/readAt不变/全部已读0、换页角标；浏览器观察2.2秒无后台请求；源码无refetchInterval，F角标测试通过 | PASS | B/T ReminderApiTest.inbox/invalidParams；P163；F notifications及layout tests；U真实2→0角标/空未读；a04d78…useUnreadNotifications | ACC NOT_DUE |
| AC-NTF-02 | IMPL/I | IDOR404不变，readerId不能越权；ADMIN403、匿名401、错缺CSRF403 | 后端全部GET/POST组合（含anonymous validCSRF、ADMIN single-read）通过；真实浏览器上下文隔离、数据前后不变 | PASS | B/T ReminderApiTest.deniedWrites/roleMatrix/cookieCsrfStillWorksAfterNotificationWrite；P289；原授权/CSRF测试 | REV NOT_DUE |
| AC-NTF-03 | IMPL/I | 页面和README说明登录触达、已读非离线送达，无外发，电话仅登记 | README§6.1写明；P/U页面明确，不读私密备注；新增服务无外发调用，无外发依赖 | PASS | README、P163、U；Q静态预检；独立安全评审仍未执行 | REV/ACC NOT_DUE |
| AC-DUN-01 | IMPL/I | ADMIN四方式/可空≤500备注、201/经办、原子固定通知无备注，无外发；非法400 | 四种真实UI201、500/空备注；插入/链接失败触发回滚、外层回滚；P通知id映射/固定模板无备注，非法输入400 | PASS | B/T DunningApiTest.validMethods/noteBoundary/emptyNotes/invalidBodies；DunningTransactionTest.notificationInsertFailure/notificationLinkFailure/outerRollback；P163/289；U | REV/ACC NOT_DUE |
| AC-DUN-02 | IMPL/I | 不存在404、已还/未逾期409、读者403，失败无残余 | 不存在/已还/未逾期/角色全部拒绝；外部锁503且零残余，释放后显式重试201 | PASS | B/T DunningApiTest.missingLoan/returnedFirst/notOverdue/securityMatrix；DunningTransactionTest.busyRecovery；P289/350 | REV NOT_DUE |
| AC-DUN-03 | IMPL/I | ID与条码归还派生CLOSED、closedAt=returnedAt、RETURNED；再登记409；并发无漂移 | 两实际归还路径、严格时间/历史保留；两锁顺序、独立连接并发重复×3无漂移，503显式重试 | PASS | B/T DunningApiTest.bothReturnPaths；DunningTransactionTest.returnWins/dunningWins/simultaneous；P350（临时SQL returnedAt与DTO严格对比）；U条码 | REV/ACC NOT_DUE |
| AC-DUN-04 | IMPL/I | 单案件时间ASC历史，列表OPEN/CLOSED分页 | records createdAt/id ASC，case latestAt/id DESC、一借阅一项；P11案件跨页/两过滤与size1分页，U空/数据/历史 | PASS | B/T DunningApiTest.sortingAndPagination/emptyHistory/invalidQuery；P163/350；F DunningCaseListPage；U | ACC NOT_DUE |
| AC-UI-07 | IMPL/I | 新页/弹窗加载/空/错重试/数据，503中文；禁重复、校验/错误码契约 | F覆盖全部状态/字段/不自动POST重试；U真实空/数据/输入校验+MOCK503保留输入和挂起禁按钮，显式刷新重开后真实201；旧模拟加载/404/500/CSRF证据源码匹配 | PASS | F notifications/dunning/OverdueDunning tests及errors完整性；U、U-old；P真实成功/空/拒绝路径。模拟失败不冒充真实数据库故障，真实DB_BUSY由B/T证明 | REV/ACC NOT_DUE |
| AC-UI-05增量 | IMPL/I | 原读者菜单保留+我的提醒；ADMIN+催还；不开放其他角色/续借 | 精确菜单与路由隔离通过；ADMIN布局不请求通知API；原8E2E保留 | PASS | F menu/router/layout tests；P289及原smoke；U | ACC NOT_DUE |

## 4. 高风险NOT_RUN补验的责任划分

P子任务未测的UTC午夜、并发/重启/DB_BUSY、续借后再进入新阈值、剩余授权组合、非法配置/默认值、V1旧库迁移，由本轮B完整测试及T定向重复在当前相同源码验证（具体方法见上表），不冒充浏览器覆盖。无Clock替换或生产测试端点。P的D-1范围由dueWindow参数1补验；更早日期由阈值SQL及D-4边界证明。U/P补真实界面，自测不能替代独立验收。

T定向命令：bash source env.sh后，`mvn -f backend/pom.xml -Dtest=LoanConcurrencyTest,ReminderApiTest,ReminderConcurrencyTest,ReminderConfigurationTest,ReminderMigrationTest,ReminderSchedulerTest,ReminderStartupTest,DunningApiTest,DunningTransactionTest test`；本轮已实际连续重复3次，各96/0failure/0error/0skipped且BUILD SUCCESS。结果与最终候选清单绑定的L/high-risk-run1..3.log一致；恢复轮次2未重复这些有效整轮检查。

## 5. 增量DoD矩阵（全部必需，定义v1.1）

| 编号 | 阶段/执行者 | 预期 | 实际 | 结果 | 方法/证据 | 候选/环境与后续 |
|---|---|---|---|---|---|---|
| DOD-R1 | IMPL/I | V1不改、V2空库与有旧借阅库迁移保数据，不用开发库 | 两迁移测试结构/FK/旧dueDate校验，真实临时空库启动V1/V2，V1 hash与HEAD相同 | PASS | B/T ReminderMigrationTest.emptyDatabase/oldDatabase、ContextLoadsTest；P/U；Q | REM-INT-v1.5-r3/E；REV NOT_DUE |
| DOD-R2 | IMPL/I | 保留全部旧测试，零失败/跳过/放宽，披露授权修订 | B611/F397/P13全部通过；原smoke/login未改、helpers原前缀保留；测试修订详见§8；验证脚本的错误也保留披露 | PASS | B/F/P/Q，git diff、§8 | REM-INT-v1.5-r3/E；REV NOT_DUE |
| DOD-R3 | REV/R | 无外发服务/新依赖/REQUIRES_NEW，原借阅行为不变 | 自检Q及B/F/P支持；尚无独立范围结论，不把自检当REV | NOT_DUE | Q、pom/package/lock/V1与HEAD比对；待独立评审报告 | REM-INT-v1.5-r3/E；REV尚未执行 |
| DOD-R4 | IMPL/I | 临时库真实浏览器到期/逾期/催还/结案与截图边界 | P四类真实行为、12截图/0pageerror；U追加默认扫描/登录冒烟/390px/模拟503恢复；B/T负责数据库并发/忙恢复 | PASS | P/P图、U/S；U-old标记REAL/MOCK；无生产mock | REM-INT-v1.5-r3/E；ACC NOT_DUE |
| DOD-R5 | IMPL/I | README配置限制+完整逐条矩阵，版本/实际/证据/候选明确 | README更新，本矩阵分开定义/执行结果、保留DOD-01历史缺口及K2实测关闭证据，候选清单绑定全部增量与证据 | PASS | README、本文件、reminder-candidate-snapshot.txt | REM-INT-v1.5-r3/E；ACC NOT_DUE |

## 6. 基础DoD重新核验（定义v1，未降低门禁）

| 编号 | 阶段/执行者 | 固定预期摘要 | 本轮实际 | 结果 | 方法/证据 | 候选/环境与后续 |
|---|---|---|---|---|---|---|
| DOD-01 | IMPL/I | 空克隆JDK21 verify、Java8或17拒绝、npm ci/build | K2从本地基线真实git clone --no-hardlinks（.git存在、初始干净），覆盖329当前未提交候选allowlist；初始无node_modules/target/dist/data，输入hash前后匹配；真实ci316包、verify611/0/0/0、build均exit0。X实际JDK8 enforcer拒绝保留 | PASS | K2/B/F/X/ENV；真实基线clone+当前候选overlay准确记录，不把普通K副本冒充clone；固定v1字面方法由K2实测关闭 | REM-INT-v1.5-r3/E；ACC NOT_DUE |
| DOD-02 | IMPL/I | dev.sh≤120s、新库迁移、浏览器登录、Ctrl-C双进程退出、端口占用提示 | U两次7103/6869ms、临时新库迁移、真实双角色登录、占用拒绝；C真实PTY发送ETX，6.946秒就绪，dev.sh exit130且两端口释放 | PASS | U；C/ctrlc-smoke.log | REM-INT-v1.5-r3/E；ACC NOT_DUE |
| DOD-03 | IMPL/I | 全量后端零failure/error/Disabled AC、AC映射及JaCoCo | B611/0/0/0；无@Disabled；当前高风险映射§3/4、基础回归§7；JaCoCo存在。原LoanConcurrency要求三次由T复验 | PASS | B、backend-report-summary.log、T、surefire XML、JaCoCo | REM-INT-v1.5-r3/E；REV NOT_DUE |
| DOD-04 | IMPL/I | lint/typecheck/test/build零error | F四项exit0，40文件397测试；仅非阻断chunk警告 | PASS | F | REM-INT-v1.5-r3/E；REV NOT_DUE |
| DOD-05 | IMPL/I | 标准E2E原7步chromium冒烟，独立端口/临时库 | 当前原8+新增5=13，0fail/flaky/skipped，12截图，seed reader2仍未还；默认命令无需外层扫描参数 | PASS | P/P图、e2e-report-summary-corrected.log | REM-INT-v1.5-r3/E；ACC NOT_DUE |
| DOD-06 | IMPL/I | dev管理员/演示读者/逾期种子、重启不重复、test不写种子 | U真实重启统计不变+reader2登录/逾期；B AdminBootstrapTest/DemoDataLoaderTest/ContextLoadsTest及ReminderStartupTest通过 | PASS | B/T/P/U | REM-INT-v1.5-r3/E；ACC NOT_DUE |
| DOD-07 | IMPL/I | README环境/安装/启动/测试/端口/账号/DB/配置/限制完整；验收人员只按README从零启动冒烟 | 完整旧要求保留并增加§6.1/临时安全/配置限制；U按README启动与新增冒烟成功，使用已有依赖，无安装。K/K2已真实空依赖安装与构建、字面空克隆由K2关闭；后续独立验收未执行 | PASS | README、U、ENV；此PASS是文档与已安装环境自测部分，不是从零独立验收PASS | REM-INT-v1.5-r3/E；ACC NOT_DUE |
| DOD-08 | IMPL/I | BCrypt/权限/CSRF/IDOR/500脱敏，参数化SQL/无真实密钥、支持后端版本、生产audit无high/critical | B全量授权/CSRF/口令/脱敏，新增强制SQL失败500脱敏/回滚；Q增量SQL参数化/固定站内内容/无外发、依赖未增；N本轮found0；配置/新增差异未见真实凭据。独立全仓库安全评审未执行且不读无关香港材料 | PASS | B/T GlobalExceptionHandlerTest/DunningTransactionTest；P隔离；N/Q；源码预检查只覆盖批准增量/相关边界 | REM-INT-v1.5-r3/E；REV NOT_DUE，生产audit不含dev |
| DOD-09 | IMPL/I | 每条定义/阶段/实际/方法/执行者/证据/候选追溯，不留空 | 根据ac-dod§5在本功能新矩阵追溯；基础旧矩阵保持历史，不重标PASS。本矩阵含每条增量AC/R及原DoD与回归映射、原到期缺口关闭证据/独立待核验 | PASS | 本文件、reminder-candidate-snapshot.txt | REM-INT-v1.5-r3/E；REV/ACC NOT_DUE |
| DOD-10 | REVIEW/R | 无非目标/新依赖/REQUIRES_NEW，旧迁移未改 | 开发Q预查支持，但本项原定义是独立评审，不由I批准 | NOT_DUE | Q、原测试回归及依赖比对，待REV报告 | REM-INT-v1.5-r3/E |
| DOD-11 | REVIEW/R | 同明确候选独立评审PASS，无未关闭Blocker/Critical | 独立评审未执行；不把本轮自测当评审 | NOT_DUE | 尚无独立报告；候选清单是后续检查输入 | REM-INT-v1.5-r3/E |
| DOD-12 | ACCEPT/A | 同评审快照本机独立验收PASS | 独立验收未执行 | NOT_DUE | 尚无独立报告；主会话负责派发 | REM-INT-v1.5-r3/E |

## 7. 基础AC回归映射（定义v1，IMPL/I）

以下仅记录当前候选全量自动化回归，不代表独立验收完成。具体统计/测试名见B的surefire XML及F/P当前报告；所有候选为REM-INT-v1.5-r3/E，实际均自动化测试PASS；定义原文保留于ac-dod.md§1，各条需要的REVIEW/ACCEPT仍NOT_DUE。

| 编号 | 预期/实际摘要 | 当前方法与证据 |
|---|---|---|
| AC-AUTH-01 | 双角色正确凭据/会话；测试PASS | B AuthControllerTest；P原login/smoke；U真实登录 |
| AC-AUTH-02 | 错凭据/停用账号拒绝；测试PASS | B AuthControllerTest相关用例；P原login |
| AC-AUTH-03 | 匿名API401非302；测试PASS | B AuthorizationMatrixTest/各模块授权；P289 |
| AC-AUTH-04 | 各角色授权矩阵；测试PASS | B AuthorizationMatrixTest、Loan/Catalog/Reader/StatsAuthorizationTest；P |
| AC-AUTH-05 | 本人隔离、readerId不越权；测试PASS | B ReaderMeTest/LoanListApiTest、授权测试；新增ReminderApiTest；P |
| AC-AUTH-06 | 真实cookie/header CSRF、数据不变；测试PASS | B安全CSRF测试/ReminderApiTest/DunningApiTest；F client；P289 |
| AC-AUTH-07 | 退出204、session失效；测试PASS | B认证测试；F logout；P原smoke；U |
| AC-AUTH-08 | BCrypt、响应/日志不带口令；测试PASS | B认证/读者/安全响应测试；Q源码预检；独立REV待核验 |
| AC-AUTH-09 | 路由角色/redirect/401守卫；测试PASS | F guards/redirect/client/router/logout；P原smoke及289 |
| AC-AUTH-10 | D4①已纳入，修改口令/旧口令拒绝；测试PASS | B ChangePasswordTest；F account tests；P原口令冒烟 |
| AC-AUTH-11 | 管理员bootstrap/profile/重启规则；测试PASS | B AdminBootstrapTest；U/P dev初始化 |
| AC-CAT-01 | 分类CRUD/冲突/引用与计数；测试PASS | B CategoryApiTest；F CategoryPage；P原smoke |
| AC-BOOK-01 | 图书校验/规范ISBN/副本/错误字段；测试PASS | B BookApiTest/IsbnUtilsTest；F BookForm；P原smoke |
| AC-BOOK-02 | 图书编辑/删除条件不变；测试PASS | B BookApiTest；F catalog |
| AC-BOOK-03 | 借还可借数量正确；测试PASS | B图书/借还测试；F BookDetail/BookSearch；P原smoke |
| AC-COPY-01 | 追加副本/条码/范围；测试PASS | B CopyApiTest；F catalog；P原smoke |
| AC-COPY-02 | 状态/删除条件、不可用拒借；测试PASS | B CopyApiTest/BorrowApiTest |
| AC-RDR-01 | 新读者/卡号/校验/登陆；测试PASS | B ReaderCreateTest；F reader表单；P原smoke |
| AC-RDR-02 | 停用/启用与借续拒绝；测试PASS | B ReaderStatusTest/BorrowApiTest/RenewApiTest；F reader状态 |
| AC-RDR-03 | 删除读者条件；测试PASS | B ReaderDeleteTest；F readers |
| AC-RDR-04 | 检索/分页/状态/计数；测试PASS | B ReaderSearchTest；F AdminReaderListPage |
| AC-RDR-05 | 重置读者口令；测试PASS | B ReaderPasswordResetTest；F readers |
| AC-RDR-06 | 我的资料本人信息；测试PASS | B ReaderMeTest；F MyProfilePage；P原smoke |
| AC-LOAN-01 | 借阅条件/30天/状态；测试PASS | B BorrowApiTest；F circulation；P原smoke |
| AC-LOAN-02 | 拒绝条件与优先级不变；测试PASS | B BorrowApiTest；F circulation |
| AC-LOAN-03 | 原三组真实并发，重复3次；当前T复验 | B/T LoanConcurrencyTest；新增Reminder/DunningConcurrency证据不可替代原借书三组 |
| AC-LOAN-04 | ID/条码归还、逾期/重复错误；测试PASS | B ReturnApiTest；P原smoke+350；U |
| AC-LOAN-05 | ADMIN+30天一次续借，原拒绝规则；测试PASS | B RenewApiTest；F circulation；P原smoke+80 |
| AC-LOAN-06 | Asia/Shanghai D/D+1/UTC16；测试PASS | B LoanPolicyTest/LoanPolicyTest；T ReminderApiTest.dateBoundary |
| AC-LOAN-07 | 本人当前/历史借阅；测试PASS | B LoanListApiTest；F MyLoansPage；P原smoke |
| AC-LOAN-08 | 管理员借阅过滤/分页/排序；测试PASS | B LoanListApiTest；F AdminLoanListPage；P350 |
| AC-SRCH-01 | 搜索精确/子串/字面%_与分页；测试PASS | B BookSearchTest；F searchQuery |
| AC-SRCH-02 | 双角色检索、URL/副本权限；测试PASS | B CatalogAuthorizationTest/BookSearchTest；F catalog；P原smoke |
| AC-STAT-01 | 统计总览；测试PASS | B StatsOverviewApiTest；F Dashboard；P原smoke；U重启统计不变 |
| AC-STAT-02 | 日/月/空周期/区间校验；测试PASS | B LoanSeriesApiTest；F StatsPage/range；P原smoke |
| AC-STAT-03 | 热门图书排序/limit；测试PASS | B TopBooksApiTest；F StatsPage |
| AC-STAT-04 | 逾期未还报表、ADMIN限定；测试PASS | B OverdueReportApiTest；F OverdueReportPage/OverdueDunning；P原smoke；U |
| AC-UI-01 | 列表四状态/503中文；测试PASS | F所有features；U模拟503与当前数据；U-old加载/错误截图标MOCK |
| AC-UI-02 | 表单校验/字段错误/提交锁/确认；测试PASS | F catalog/readers/forms；U新增501字/挂起锁 |
| AC-UI-03 | ErrorCode与中文完整一致；测试PASS | F errors完整性及snapshot；B ErrorCode；§8披露两新增码 |
| AC-UI-04 | 柜台回车/焦点/借还反馈；测试PASS | F CirculationDeskPage；P原smoke；U归还 |
| AC-UI-05 | 按批准§5菜单增量，原续借权限不变；测试PASS | F menu/router；P原smoke；增量§3 |
| AC-UI-06 | 仪表盘/统计/逾期报表；测试PASS | F stats；P原smoke；U逾期报表 |

## 8. 修订、失败历史、限制与交接结论

- 本轮无业务源码/迁移/依赖/原测试修复。README明确PT1H默认、E2E仅后端子进程PT1S、触达限制及DB安全。本矩阵不是对业务标准的事后修订。
- 上游获批测试修订保留：ContextLoadsTest由精确V1升级精确V1/V2并保留原六表/无种子、新两表检查；TestDatabase按FK顺序清新增表；application-test关闭调度；ErrorCode前端完整列表及snapshot仅追加NOTIFICATION_NOT_FOUND、LOAN_NOT_OVERDUE；菜单精确断言加入两个获批入口、保持原菜单/角色/读者不自助续借；router新增正式页/API mock替代新增占位断言，保留隔离断言；原E2E8用例未改、helpers只追加；Playwright唯一生产相关差异是获批测试子进程PT1S。没有删除/跳过/降断言。本轮没有重新解释旧失败为PASS。
- 本轮证据工具失败保留：input-static-check误写V1__init.sql（真实文件V1__init_schema.sql）后定向修正；e2e-report-summary误把reader2所有历史借阅当作应只有一条，改为批准的原逾期未还种子id2守卫，并保留原历史数据；U run1在“刷新”后误继续使用已关闭modal，源码refresh明确清selected，run2重新打开/重新确认，原503输入保留及无重复提交断言未降低。环境首命令shell包装错误，用显式/bin/bash调用验证，旧日志保留。上述不是应用回归失败；未删除日志。JDK8失败是预期负向PASS。
- U-old已核对源码/证据hash，只用其明确范围的MOCK边界/REAL390px资料；本轮P/U/B/F不拼接旧局部PASS冒充新执行。npm审计历史网络阻塞的r1/r2不重写；本轮默认源实际成功。
- 恢复轮次2授权与结果：主会话明确转交原D6依赖下载授权及用户“重试npm源”决定，允许一次隔离安装验证；仅K中实际安装，未更改registry/TLS/全局环境、package/lock或业务源码。K npm ci安装316包、verify611/0/0/0及build均exit0；C真实PTY ETX、退出130与两端口释放PASS。原B/F/P/N/T/U未无谓重跑，源码hash无漂移。
- 恢复轮次3授权：主会话明确指定本地`git clone --no-hardlinks`加当前稳定329输入allowlist overlay，在真实克隆目录执行空依赖安装/构建；沿用原D6及已恢复默认npm源授权，不改固定DOD-01文字或其它标准。K2已实际ci/verify/build全部exit0、611/0/0/0，329overlay输入前后与workspace匹配，关闭轮次2NEEDS_WORK；没有修改业务/测试/README/契约或重跑原B/F/P/N/T/U/C/K。
- 文档追溯纠正：初版矩阵基础回归表部分测试类名不准确，恢复轮次2逐项对照真实backend/src/test/java文件改为实际ApiTest/ReaderMe等定位；没有修改测试/业务代码、执行结果或批准定义。最终机器核对所有引用测试类存在；此证据定位修订交独立REV复核。
- 新警告：K安装报告dev eslint9.39.5 deprecated及esbuild/fsevents安装脚本未在allowScripts覆盖；未执行approve、未修改门禁/配置，实际构建成功。生产依赖N审计found0不代表开发依赖长期维护无风险。主chunk927.80kB告警保留。
- 未运行/限制：轮次2真正git clone未测的历史NOT_RUN已由K2真实基线clone+未提交候选overlay实测关闭，没有降标准或改定义版本。JDK17没有单独负向执行（JDK8预期拒绝已实际验证）；没有短信/邮件/电话外发；不测试离线送达；未运行独立评审/独立验收或上线验证。安全预查不等于全仓库凭据考古，独立REV保留。
- 当前交接状态：READY_FOR_CHECKS。全部开发/集成到期必需项有真实有效证据，无已知业务实现阻断；原轮次2NEEDS_WORK过程记录保留，由K2关闭DOD-01，C已关闭DOD-02。DOD-R3、DOD-10及DOD-11/12独立项保持NOT_DUE，绝不把自测当独立评审/验收或上线许可。最终候选仅更新矩阵/快照与K2验证证据，不提交/暂存/推送/发布、不删除用户文件，下一责任环节由主会话决定。
