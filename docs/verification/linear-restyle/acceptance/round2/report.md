# LDR-ACC 独立验收复验报告（round 2，候选 C2）

- taskId：`linear-design-restyle-20261121`；subtaskId：`LDR-ACC`；stage：acceptance-verifier；plan：`linear-restyle-plan-v1.1`；**dispatchRound：2**（原 ACC 会话复验，非新任务）。
- 冻结定义：`linear-restyle-ac-dod-v1`；design：`linear-restyle-design-v1-R1`（SHA `551d621d…2ed455` 不变）。
- 候选：**C2=`linear-restyle-20261121-c2-288c7b59c108`**；339 文件 digest 运行前/后均=`288c7b59c108feff7d910dd8239c5d289c9f6029c18e598f3dfc777f3df857e8`，0 漂移。snapshot SHA `57303c616957ad9b433f19d1bd671054ee8a020f1160fb16fe7fe1404528425b`。
- C1→C2 独立逐文件比对：**恰 3 文件变化**（ForbiddenPage.tsx、NotFoundPage.tsx、linear-theme.spec.ts），其余 336 逐字节一致；根 DESIGN/上游恢复/设计 hash 不变。
- C1 历史完整性：history 三副本 hash（`d18a5392…`/`b0175652…`/`6c9ad2fe…`）逐字节核验 intact；C1 原 ACC report/results 未改。

## 1. 可直接展示的摘要

**状态：PASS（仅本次视觉增量 C2 到期必需项）。** 前端四项与标准 E2E 独立全绿（lint 28 边界、typecheck、410/410、build、**37/37 E2E**）；独立探针 107 组 / 4793 文字样本 **0 对比度失败**（最低 4.6999、9 真实 disabled 例外、0 横溢），1440/1024/390 与真实 ADMIN/READER 全范围回归通过。**ACC-D01 已关闭**：真实 READER 下 403/404 在 1440 与 390 均实际渲染单色 `data-icon=lock/search` 图标，`aria-hidden=true`，图标与全部 SVG path 计算 fill=`rgb(138,143,152)`（ink-subtle），`.ant-result-image` 节点 0、SVG 1，title/subtitle 原样，"返回首页"真实导航到 `/books`；四张截图 ACC 实际读取确认无彩色人物/仙人掌插画。AC-1–6 与 DoD-1–6 全部 PASS，无 FAIL/NOT_RUN/BLOCKED/NOT_DUE。

**C1 DoD-4 误标纠正**：固定 DoD-4 语义为"独立验收复核并满足所有到期必需 AC/DoD 给出通过结论"。C1 当时 AC-3 FAIL（ACC-D01），整体验收未通过，**C1 的 DoD-4 正确值应为 FAIL**；round1 将其标 PASS 属误标，特此纠正。C1 历史字节保留不改写，不以 C2 PASS 追改历史。C2 全部到期必需项实际通过，本轮 DoD-4 方为 PASS。

本 PASS 不代表整个系统历史质量/安全/审计门禁或生产发布通过；独立评审未启用/跳过，不计 PASS。

## 2. ACC-D01 复验（图形事实 + 导航，独立于 DEV 证据）

| 组合 | data-icon | aria-hidden | icon/path fill | .ant-result-image | svg 数 | 返回导航 |
|---|---|---|---|---|---|---|
| 403 @1440 | lock | true | rgb(138,143,152) | 0 | 1 | /books ✓ |
| 403 @390 | lock | true | rgb(138,143,152) | 0 | 1 | /books ✓ |
| 404 @1440 | search | true | rgb(138,143,152) | 0 | 1 | /books ✓ |
| 404 @390 | search | true | rgb(138,143,152) | 0 | 1 | /books ✓ |

证据：`computed/graphics/acc-d01-*.json`（含 expected/actual）、`screenshots/acc-d01-*.png`（四张已读）、探针 ACC-12 四用例（真实 UI 登录 + `/api/auth/me` role=READER 校验）。修复机制核对：两组件移除 Result 异常展示 status，使 icon prop 进入可渲染分支；title/subTitle/按钮/角色守卫路由保留。DEV 新增 4 用例仅作辅助，本结论以 ACC 独立实测为准。

## 3. AC / DoD 双矩阵（C2，定义 v1）

| 编号 | 结果 | 关键证据 |
|---|---|---|
| AC-1 | PASS | 原文/provenance hash 不变且独立核验（scope-check.json） |
| AC-2 | PASS | 336 文件含主题单源与 C1 一致；13 主题单测独立绿；运行时三态/focus/portal/ghost 实测 |
| AC-3 | PASS | 107 组 4793 样本 0 失败、最低 4.699901、0 横溢；三视口几何附件；ACC-D01 图形+导航关闭 |
| AC-4 | PASS | 真实三态通知/OPEN/CLOSED/逾期 Tag/校验/danger 深字复验 |
| AC-5 | PASS | 0 渐变/0 旧色；403/404 现中性单色 |
| AC-6 | PASS | 保护路径 diff 空；23 视觉 tracked 文件；5 新增源不变；原 13 业务 E2E 绿 |
| DoD-1 | PASS | ACC 独立四项 exit0（AL2） |
| DoD-2 | PASS | ACC 独立 37/37 exit0（AE2） |
| DoD-3 | PASS | DEV L2/J2/G2/P2 hash 绑定 S2 + ACC 107 组独立实测 |
| DoD-4 | PASS | C2 全部到期必需项通过；C1 误标纠正见 §1 |
| DoD-5 | PASS | 本轮只记实跑；C1 历史保留；新矩阵 hash `132e77e4107a3319dcb79a92e023dcd4662e788d7c80d7011c71c827c502a5bc` |
| DoD-6 | PASS | INT2 实跑 617/410/37 exit0，hash `887debd4…`/`37c9e9fd…` 绑定 S2；交叉核对不扩审计 |

## 4. 实际命令与结果

| 命令 | exit | 统计 |
|---|---:|---|
| npm --prefix frontend run lint / typecheck / test / build | 0/0/0/0 | 28 边界；tsc；410/410×41 文件；build 成功（>500kB 告警保留） |
| npm --prefix frontend run e2e | 0 | 37/37，4.1m |
| round2 探针 run-1（22 用例） | 1 | 21 过 1 失败：ACC-04 于 Popconfirm 入场瞬态帧（祖先 opacity≈0.54）采样致假对比度失败；稳定态颜色断言先已通过；**非产品缺陷** |
| 探针 run-2 `--grep ACC-04 --output runs/run-2` | 0 | 1/1；探针补"祖先 opacity settled=1"等待（稳定态测量语义，未降 4.5 阈值、未改产品） |

run-1 失败产物保留于 `runs/run-1/` 与 `logs/acc-probe-run1.log`/`acc-probe-report-run1.json`；run-2 独立输出目录，未清理首轮证据。round1 的定位修正（okText"确认"、同书多通知逐条）保留。

## 5. 相邻回归与几何永久证据

login 默认/hover/pressed/键盘/错误焦点、ADMIN 15 路由、READER 6 路由+逾期/未读已读、Modal/校验/danger/删除取消+确认(204)、Table hover、Select/DatePicker portal、范围 `::before` 合成与跨月可点、分页 active/hover/翻页、ghost、催还 OPEN→CLOSED→RETURNED、ACTIVE、六 feature 明标 mock loading/empty/503/重试 + auth/me 延迟透传、13 组明标组件夹具、1440/1024/390 统计与弹层/390 日历第二月内滚。几何附件永久唯一路径：`computed/geometry/acc-stats-geometry-{1440,1024,390}.json`、`acc-calendar-bounds-{1440,1024,390}.json`（含 expected/actual/assertions；修复 round1 重名覆盖遗漏）。ACC 实际读取 acc-d01 四张 + acc-stats-fit-390。

## 6. 未跑/限制/清理/写入

- 未独立重跑后端（DoD-6 DEV 门禁交叉核对）；无跨浏览器/真机/屏读；行选择 NOT_APPLICABLE；1024 仅统计补测（建议项）。
- 清理：round2 三个自有临时库（1795259289542/1795259565216/1795259892063 + WAL/SHM）已删；DEV 历史库未触碰；fixture 仅删自有行；四端口无监听；无源码修改/提交/暂存/push/deploy。
- 写入仅 `acceptance/round2/**` 与矩阵顶部 C2 ACCEPT 区域（C1 历史 suffix 逐字保留）；round1 原 probe/证据未覆盖。
- 已知告警：前端 >500kB chunk 既存告警保留。

## 7. 建议下一步

主会话向用户展示 C2 独立验收 PASS 与 ACC-D01 关闭证据。交付终点为本机视觉增量实现+独立验收；提交/发布/部署未授权亦未执行，如后续上线需另行授权与门禁。本会话停写，supervise 正常返回，不派发后续任务。