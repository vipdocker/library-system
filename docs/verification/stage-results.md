# 环节结论登记（主会话维护）

本文件位于快照排除目录 `docs/verification/` 下，登记各环节结论不会改变候选 ID。
`docs/design/**` 属于候选快照范围，候选冻结后主会话不再修改其中任何文件。

| 环节 | 会话 | 候选 | 结论 | 要点 |
|---|---|---|---|---|
| T13 最终集成 | a00a1003-9a42-5419-a985-8818fd28a84b | 53beed1d7decfa898413faa45b9b1793b2d529c82f4127409b5524727c2ef1c7 | READY_FOR_CHECKS | 主会话独立复验：test-all.sh 退出码 0（后端 515/515，前端 323/323，E2E 8/8），快照 279/279 一致、无漂移 |
| 独立评审 REV（Qwen4-Max） | 63bcd8ec-d7fd-53d9-a712-cd4eefe52915 | 同上 | **PASS**（0 阻断，7 非阻断） | DOD-03/04/08/09/10/11 PASS；I-04/I-06/I-07 同意按已知限制放行；非阻断：R-01 登出后重新登录时旧会话迟到 401 的残余竞态（技术债务）、R-03 匿名 POST 缺 CSRF 得 403 属设计内口径、R-04 矩阵计数与 README I-04 份数小误差（待文档修正）、R-07 circulation→catalog 查询键单向依赖可接受 |
| 独立验收 ACC 第 1 轮（GLM-5.3） | 968bd6be-6ab0-57e7-abc9-fc43fbea0888 | 同上 | BLOCKED（候选漂移，未实测） | 原因：主会话在快照生成后向 `docs/design/approval-record.md` 追加了上两行登记（主会话失误）。处理：2026-11-18 主会话删除该追加段，文件恢复为快照字节（sha256 e9aa8a7a…，复核一致），登记移至本文件；评审开始时校验的即为原字节，评审结论仍绑定同一候选 |
| 独立验收 ACC 第 2 轮（GLM-5.3） | 968bd6be-6ab0-57e7-abc9-fc43fbea0888 | 同上（开始/结束快照均 279/279，干净克隆 `/tmp/acc-clone.yYiWjW` 279/279） | **PASS**（AC 43/43，DoD 12/12，0 缺陷） | 克隆中只按 README：mvn verify 515/515、Java 8 enforcer 报错、npm ci+build、dev.sh 自动建库/双进程退出/端口占用提示、重启幂等、reset-db、E2E 8/8、黑盒并发 3 场景、浏览器 22 项检查。报告：`docs/verification/acceptance/acceptance-report.md`。主会话复核：快照无漂移、开发库未变、端口无残留；发现验收在工作区根目录遗留 curl cookie 文件 `acc-admin.jar`（仅含已停止测试实例的 XSRF-TOKEN，越出验收写入范围），已移至 `docs/verification/acceptance/logs/acc-admin.jar.stray-from-workspace-root`，源码树恢复与清单一致 |
