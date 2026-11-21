# 首期借阅提醒：批准的增量契约与实施拆解

## 1. 来源与版本

- 父任务：lib-borrow-reminder-p1。
- 增量 AC/DoD：v1.1；基础契约仍为 design-v0.1-R1、AC/DoD v1，批准来源见 approval-record.md。历史定义与历史测试证据保留，不重标为本次通过。
- 只读拆解来源：会话 327e9298-7302-57c2-ae94-4dac33f368dc，REM-PLAN / plan / 派发轮次1；原 Turn 3d0ebbeb-5f0a-50ea-ad85-dbdf28ae5fe3；精简补充 Turn agent-message:5390e1336eb861967af828e2a560b8f4f063c063ef915e472c8ab0f9d85c5be4。
- 当前计划：reminder-plan-v1.5。v1.5经主会话AskUserQuestion明确批准：REM-E2E可仅在frontend/playwright.config.ts后端webServer.env中设置LIB_REMINDER_SCANINTERVAL=PT1S，以标准E2E命令重验；生产默认PT1H、依赖、业务与等待断言不变，原E2E会话执行，不增加会话。v1.1增加后端催还接口依赖；v1.2经用户授权追加ContextLoadsTest精确V1/V2检查。v1.3用户明确选择继续开发、保留审计，将收件箱已核验源码和剩余审计分别跟踪；前端催还/E2E可消费源码，但审计仍属IMPL必需并阻断集成完成与交付，不后移、不豁免。v1.4仅在此前已批准的共享路由/测试范围内调整写入责任：router.test.tsx中新增的催还占位用例及其案件API mock交给REM-FE-DUNNING适配真实页面，保留原角色隔离断言；原写入者已停止，无并发冲突。该文件责任调整不是新增功能或自称新的用户授权。功能、AC/DoD v1.1、依赖、模型、6个开发会话数量及并发上限2不变，仍在原前端催还会话继续。
- 环节选择：ec0966d6-228e-4349-a00a-4da3b5ff92c3，主会话读取实际记录核验为 confirmed。方案设计跳过；开发实现 ultimate；独立评审、独立验收 qmodel_latest。跳过设计不表示设计环节通过。
- 授权来源：2026-11-19 主会话用户功能请求、已核验环节选择、三组 AskUserQuestion 的真实回答。第一组确认提前3天一次、持久化收件箱和角标、逾期首日及每7天、登记催还并发站内通知。第二组确认停机补发、固定逾期周期只补当前、仅逾期未还可登记、备注仅管理员可见。第三组允许迁移/共享文件/测试/需求与验收文档变更，并确认验收增量、其余最小实现及后续6个开发会话、最多2个并行。
- 基线：main / 6c7943278686e3e99a3d7dc3b497e1879b049587；实现前重新核实工作树，不覆盖既有未提交工作。

## 2. 批准的业务规则与边界

1. 时区沿用 Asia/Shanghai 和注入 Clock。应还日 D 当天按期，D+1 起逾期。借阅上限、借期、续借次数、管理员续借权限均不改变。
2. 到期前提醒在 D-3 生成一次。停机错过时，D-2 至 D 之间首次扫描补发一次。D+1 起不再补到期前提醒。
3. 逾期周期以应还日为基准：逾期第1、8、15天进入新周期。正常持续扫描每期只有一条；若停机恢复在周期内，首次扫描仅补当前周期一条，不补历史周期。每期重复扫描不重复生成，不误把“非周期首日”解释成禁止补当前周期。
4. 提醒持久化，读者有分页收件箱和未读角标，可单条或全部标已读。已读仅表示读者在系统里标记过，不表示离线送达。
5. 续借后按新应还日计算提醒。旧通知保留、不自动已读；页面显示当前借阅状态和当前应还日，避免把历史通知当成当前催还要求。
6. 已归还不再生成新提醒。停用读者仍生成，重新启用并登录后可查看。
7. 人工催还仅限逾期且未归还借阅。方式为电话/当面/站内/其他；备注可空，最长500字。登记同时生成一条固定模板的读者站内通知，不带管理员备注。
8. 未还且有催还记录的借阅为未结案案件，归还后自动结案；状态与结案时间由借阅归还字段派生，历史催还记录保留。结案后不能再登记。
9. 管理员从逾期报表行内登记，另有催还历史页，能按未结案/已结案筛选。读者增加“我的提醒”入口。
10. 未读角标在打开/切换页面、标记已读时刷新，不后台定时查询，避免延长空闲会话。
11. 不集成短信、邮件或电话外发服务。人工登记方式可包含电话，界面不能宣称系统已通过电话/短信/邮件触达。站内提醒只能触达登录系统的读者，页面和 README 必须明确说明。
12. 交付终点是本机实现、独立评审和独立验收，不包含提交、推送、上线或生产操作。

## 3. 拆解会话提出的接口与数据边界

复用既有分页、日期、时间戳、错误响应与授权模式。分页形状为 items/page/size/totalItems/totalPages，日期 YYYY-MM-DD，时间戳 ISO-8601 UTC，空值为 null。

### 读者通知

| 接口 | 行为 |
|---|---|
| GET /api/notifications/me?unreadOnly&page&size | 本人通知分页，createdAt/id 降序；不接受外部 readerId 作为身份 |
| GET /api/notifications/me/unread-count | 返回 {unreadCount} |
| POST /api/notifications/me/{id}/read | 单条已读，幂等；他人或不存在的通知404 NOTIFICATION_NOT_FOUND |
| POST /api/notifications/me/read-all | 本人全部已读，返回 {updated} |

通知字段：id、type（DUE_SOON/OVERDUE/DUNNING）、title、content、loanId、book{id,title}、copyBarcode、dueDate（生成时快照）、overdueDays（可空）、seq（可空）、createdAt、readAt（可空）、loanState{status,currentDueDate}。数值语义在后端交接中固定；非逾期类型的业务字段不假装为当前逾期值。

通知接口仅 READER，ADMIN 403、未登录401；所有写操作保持现有 CSRF 防护。

### 管理员催还

| 接口 | 行为 |
|---|---|
| POST /api/loans/{id}/dunning-records | 请求 {method,note?}；201；同一事务生成记录和站内通知；借阅不存在404 LOAN_NOT_FOUND，已还409 LOAN_ALREADY_RETURNED，未逾期409 LOAN_NOT_OVERDUE，非法输入400 |
| GET /api/loans/{id}/dunning-records | 案件 {loanId,status,closedAt,closeReason,records[]}，记录按时间排序 |
| GET /api/dunning-cases?status=OPEN\|CLOSED&page&size | 案件分页列表 |

以上仅ADMIN。REM-BE-DUNNING在会话251132de-6dfb-5c32-a524-38e40aa0160e、Turn 8ceb8c04-6a8a-5076-a5a7-f97f36f63b40输出并经主会话核对以下真实DTO。后端全量611测试通过，源码清单backend/target/rem-be-dunning-source-snapshot.txt的180项与当前文件匹配，清单SHA256为35af5902b9a4cf62aa94fcdce6749cdfbc4f4dd40e2f93b9ccc1259d5ec455a4。

- POST登记成功201返回记录：id、loanId、method、note（null或字符串）、operator{id,username}、createdAt、notificationId。每次登记独立产生记录和通知，不自动重试POST；备注去首尾空白、空串转null、最多500字符。
- GET借阅催还历史返回loanId、status、closedAt、closeReason、records[]；records使用同一记录DTO，createdAt ASC/id ASC排序。无记录的既有借阅返回空历史，但不进入案件列表；不存在借阅404。
- 案件列表Page.items字段为loanId、reader{id,cardNo,name}、book{id,title}、copyBarcode、dueDate（当前应还日）、status、closedAt、closeReason、recordCount、lastRecordedAt。每借阅一项，总数不因多记录增加；lastRecordedAt DESC/loanId DESC排序。
- status省略或空白为全部，否则仅OPEN/CLOSED；默认page=0、size=20，size1..100。固定排序，不发送非空sort参数。
- CLOSED时closedAt严格等于loan.returnedAt，closeReason=RETURNED；OPEN时两字段null。日期YYYY-MM-DD，时间ISO-8601 UTC，JSON数值用于ID/计数。
- 错误沿用统一响应：非法输入400 VALIDATION_FAILED；不存在404 LOAN_NOT_FOUND；已还409 LOAN_ALREADY_RETURNED；未逾期409 LOAN_NOT_OVERDUE；角色403 FORBIDDEN、未登录401 AUTH_REQUIRED；缺错CSRF的POST优先403 CSRF_INVALID；锁竞争503 DB_BUSY，需用户显式重试。

REM-FE-DUNNING按这些已冻结字段实现，不自行重命名或新增后端接口。

新增错误码：NOTIFICATION_NOT_FOUND(404)、LOAN_NOT_OVERDUE(409)，前后端与完整性测试同步。

### 数据与扫描

仅新增 V2__reminders_dunning.sql，不改 V1。新增 notification 和 dunning_record 表，引用现有读者、借阅、经办账号；自动通知去重键含 loanId/type/应还日快照/周期序号，人工催还不受自动通知唯一键限制。人工记录与通知写入应原子提交或回滚。案件状态不另设可与归还状态漂移的状态表。

新增独立 lib.reminder 配置，默认 enabled=true、due-soon-days=3、overdue-repeat-days=7、scan-interval=PT1H、scan-on-startup=true；非法值启动失败，test profile 关闭调度。启动补扫在演示数据写入后，扫描采用短事务与条件写入；不使用 REQUIRES_NEW。单连接池不代替幂等、并发和交易边界验证。

## 4. AC 增量定义 v1.1

所有条目必需。IMPL=开发自测，REV=独立评审，ACC=独立验收；定义不代表已通过，执行结果在核验矩阵单独记录。

| 编号 | 可观察条件 | 核验时点与责任 |
|---|---|---|
| AC-REM-01 | D-3 恰好生成一条含书名/条码/应还日的到期提醒，D-4及更早不生成；错过时D-2至D最多补一条，D+1不补 | IMPL/REV/ACC |
| AC-REM-02 | D当天无逾期通知；D+1、D+8、D+15进入新周期；停机后只补当前周期，每期最多一条；UTC D日16:00已是上海D+1；归还后不再生成 | IMPL/REV |
| AC-REM-03 | 重复/并发扫描和重启补扫均不重复；数据库繁忙可观察地记录并在后续扫描重试，不破坏正常接口 | IMPL/REV |
| AC-REM-04 | 续借后按新应还日重算，保留旧提醒且不自动已读，显示当前状态/应还日；原AC-LOAN-05不变 | IMPL/ACC |
| AC-REM-05 | 归还先提交不生成提醒，扫描先提交允许保留已有通知；归还提交后不再新增，不死锁；原AC-LOAN-04行为不变 | IMPL/REV |
| AC-REM-06 | 默认配置正确，非法值启动失败；测试不自动扫描，启动补扫在演示数据写入后 | IMPL/REV |
| AC-NTF-01 | 收件箱本人隔离、分页降序、未读数准确、单条幂等和全部已读；角标在页面/已读操作刷新且无后台轮询 | IMPL/ACC |
| AC-NTF-02 | 他人通知已读操作404且数据不变；不能通过readerId越权；管理员403、未登录401；错误/缺失CSRF的POST为403且无变化 | IMPL/REV |
| AC-NTF-03 | 页面与README说明登录触达限制；已读不宣称离线送达，不接外发服务；人工电话方式仅作记录 | IMPL/REV/ACC |
| AC-DUN-01 | 管理员对逾期未还登记四种合法方式和最多500字备注，201且记录经办人，同事务生成一条不带备注的站内通知；无外发调用，非法输入400 | IMPL/REV/ACC |
| AC-DUN-02 | 不存在404、已还409、未逾期409；失败无记录/通知；读者调用403 | IMPL/REV |
| AC-DUN-03 | 按借阅号或条码归还后案件结案，时间等于归还时刻、原因已归还；结案后登记409，并发无状态漂移 | IMPL/REV/ACC |
| AC-DUN-04 | 能查看单个案件与时间排序的催还历史，案件列表按OPEN/CLOSED过滤并分页 | IMPL/ACC |
| AC-UI-07 | 新页面/弹窗覆盖加载、空态、错误重试、有数据；503提示系统繁忙；提交禁重复、校验和中文错误码与契约一致 | IMPL/REV/ACC |
| AC-UI-05增量 | 原读者菜单保留并增加我的提醒；管理员增加催还记录，不能因新增入口开放其他角色菜单或自助续借 | IMPL/ACC |

## 5. DoD 增量与既有门禁

| 编号 | 可观察条件 | 核验时点与责任 |
|---|---|---|
| DOD-R1 | V1不改，V2在空库与有旧借阅的测试库上迁移成功，结构/数据校验通过；现有开发库不用于本轮测试 | IMPL/REV |
| DOD-R2 | 保留既有测试，全部通过；不删/跳过/放宽断言。授权的菜单/错误码测试与快照修订逐条披露。本次基线实际执行确定用例数，历史515/323/8不是本次实测证据 | IMPL/REV |
| DOD-R3 | 无外部发送服务、无新增依赖、无REQUIRES_NEW，借阅原业务行为不变 | REV |
| DOD-R4 | 真实浏览器在临时数据库上覆盖到期提醒、逾期通知、人工催还、归还结案，有截图/日志及边界/失败路径；自测不替代独立验收 | IMPL/ACC |
| DOD-R5 | README补配置与限制，本功能矩阵逐条记录定义版本、阶段、预期/实际、执行者、方法、证据、候选与环境 | IMPL/ACC |

DOD-R1..R5均必需。原DOD-01..12按既定门禁在本次候选重新核验，不降低构建、全量测试、E2E、启动、安全和追溯要求。评审与验收必须基于同一稳定候选。结果只用PASS/FAIL/NOT_RUN/BLOCKED/NOT_APPLICABLE/NOT_DUE，不以回调终态或局部PASS表示交付完成。

## 6. 子任务、写入边界与依赖

所有子任务可读取相关既有契约、README、脚本；后端任务读backend源码/配置，前端读frontend源码/测试/配置，不读无关云资源或node_modules内容。无其他会话调度、无worktree、无提交/推送/发布、无新增依赖、无pom/package/锁文件修改、无backend/data写入。测试生成的target/dist/报告为正常验证产物，必须纳入资源冲突控制。

| 子任务 | operation | dependsOn | writeScope | 负责AC与输出 |
|---|---|---|---|---|
| REM-BE-NOTIFY | implement | 无 | backend/src/main/resources/db/migration/V2__reminders_dunning.sql；新reminder包及测试；domain/entity的Notification/DunningRecord；domain/enums的NotificationType/DunningMethod；domain/repository的NotificationRepository/DunningRecordRepository；common/error/ErrorCode.java；security/SecurityConfig.java；support/TestDatabase.java；src/test/resources/application-test.yml；v1.2追加backend/src/test/java/com/example/library/support/ContextLoadsTest.java仅精确迁移与结构检查 | AC-REM-01..06、AC-NTF-01..03后端、DOD-R1/2；输出扫描、通知接口和测试证据；提供供催还调用的通知事务接口；不改LoanService/LoanRepository |
| REM-FE-INBOX | implement | 无，按本文件已冻结通知字段模拟开发 | frontend/src/api/types.ts与errors.ts；api/__tests__/errors.test.ts及快照；app/router.tsx、menu.ts、layouts/AppLayout.tsx；app/__tests__/menu.test.ts和router.test.tsx；新features/notifications及其测试；features/dunning/pages/DunningCaseListPage.tsx仅占位 | AC-NTF-01/03、AC-REM-04展示、AC-UI-05增量/07；真实浏览器自测，接口不可用的场景需说明模拟证据，不能冒充整体验收 |
| REM-BE-DUNNING | implement | REM-BE-NOTIFY | 新backend/src/main/java/com/example/library/dunning包及对应测试；DunningRecordRepository只追加所需方法 | AC-DUN-01..04；输出冻结催还DTO、事务/权限/并发测试；不重复修改迁移或公共错误码 |
| REM-FE-DUNNING | implement | REM-FE-INBOX已核验源码成果、REM-BE-DUNNING完整自测成果（v1.3用户批准；审计独立保留BLOCKED） | frontend/src/features/dunning及测试；features/stats/pages/OverdueReportPage.tsx及对应测试；src/api/types.ts仅追加已冻结催还类型；v1.4转交app/__tests__/router.test.tsx中催还占位用例与必要案件API mock，保留角色隔离断言，不改生产router | AC-DUN-01/04、AC-UI-07；替换占位、按后端结果对接，不删除既有断言 |
| REM-E2E | implement | REM-BE-NOTIFY、REM-FE-INBOX已核验源码成果、REM-BE-DUNNING、REM-FE-DUNNING | frontend/e2e/reminders.spec.ts；e2e/helpers.ts仅追加；v1.5用户追加授权frontend/playwright.config.ts仅后端webServer.env固定测试扫描间隔PT1S | 新通知/催还流程加原E2E回归；不归还reader2种子借阅，避免破坏原冒烟；缺失到期/结案浏览器证据由REM-INT补全，不能删标准 |
| REM-INT | integrate | REM-BE-NOTIFY、REM-FE-INBOX源码成果及必需审计、REM-BE-DUNNING、REM-FE-DUNNING、REM-E2E | 上述写入范围内必要集成修复；README.md；docs/verification/reminder-ac-dod-matrix.md、reminder-candidate-snapshot.txt；本功能测试日志/截图；docs/design/ac-dod.md仅本次UI-05增量和追溯；其他契约变更先回主会话 | 所有必需实现后唯一串行集成；全量构建/测试/回归及真实浏览器四类行为；输出稳定候选与完整矩阵 |

测试支撑准确路径：backend/src/test/java/com/example/library/support/TestDatabase.java。Java生产路径以backend/src/main/java/com/example/library为根。

### 批次与共享资源

- 批次1：REM-BE-NOTIFY + REM-FE-INBOX，最大并发2；后端不写前端，前端不读实现中的后端源码，以冻结通知契约和模拟数据为输入。
- 批次2：REM-BE-DUNNING，在REM-BE-NOTIFY完成后。只要无资源冲突，不要求前端收件箱一起完成。
- 批次3：REM-FE-DUNNING，等待两项直接依赖。
- 批次4：REM-E2E，等待全部四个实现任务。
- 批次5：REM-INT，等待上述全部五项及REM-FE-INBOX必需审计证据；停止候选并发写入。v1.3只允许下游开发消费已核验源码，审计仍BLOCKED时不能报告集成或交付通过。
- 集成后：独立评审，再独立验收同一候选。
- 后端target、单连接测试数据库与清表支撑不可共享并行写；前端共享路由/布局/types和dist只由当前对应任务写；E2E临时数据库、18096/15196端口串行使用。
- UI自测使用隔离临时库与不冲突端口。启动脚本必须显式设置LIB_DB_PATH，不运行会迁移现有backend/data/library.db的默认启动。原脚本使用bash、Homebrew JDK21和/opt/homebrew/bin/npm；不改全局环境。
- 用户批准后续6个开发会话、2个独立核验会话；开发最多同时2个。费用系数不是最终费用，不为每个子任务重新获得父任务全额预算。追加会话、扩大范围/模型/环境或额外预算需再次确认；返工保留任务ID和增加轮次，不冒充新任务绕过限制。
