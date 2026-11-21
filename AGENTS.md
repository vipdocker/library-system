# 项目代理指南

适用于整个仓库。当前只设置代理指南与架构约束；不自动添加 hooks、发布流程或项目级 Skill。

## 项目与契约

- 本地单机图书馆管理系统，角色为 ADMIN / READER。后端为 Java 21 + Spring Boot + JPA / Flyway / SQLite；前端为 React + TypeScript + Vite + Ant Design + TanStack Query。
- 运行说明见 [README.md](README.md)。基础设计与验收标准见 [solution-design.md](docs/design/solution-design.md)、[ac-dod.md](docs/design/ac-dod.md)；站内提醒与催还增量见 [reminder-increment.md](docs/design/reminder-increment.md)。
- 修改前读取相关契约和对应测试。设计文档默认只读；发现设计与实现不一致时报告差异，不通过改设计文档让错误实现变成正确。
- 核验矩阵在 `docs/verification/`。只记录实际执行的命令、结果与证据；开发自测不能标为独立评审、验收或上线通过。
- 不擅自增加预约、罚金、邮件 / 短信、SSO、多馆 / 多租户、读者注册、导出、部署等非目标。

## 环境与命令

所有命令从仓库根目录执行。脚本必须用 `bash`；不能用 zsh 执行或 source 项目脚本。不要依赖默认 Java 或 nvm。

- 默认 JDK：`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`，可用 `LIB_JAVA_HOME` 覆盖。Maven 命令先在 bash 内加载 `scripts/env.sh`，失败时停止。
- Node >= 20.19、Maven >= 3.9；前端使用 `/opt/homebrew/bin/npm`，或在加载 `scripts/env.sh` 后使用 npm。不要修改全局 shell、Java 或 Git 配置来解决项目环境问题。

```bash
# 本地启动，Ctrl-C 清理自己的前后端子进程
bash scripts/dev.sh

# 后端全量测试、构建与覆盖率报告（包含架构测试）
bash -c 'source scripts/env.sh && mvn -f backend/pom.xml verify'

# 后端快速架构检查，无需启动 Spring 或数据库
bash -c 'source scripts/env.sh && mvn -f backend/pom.xml test -Dtest=ArchitectureTest'

# 前端检查（lint 包含模块边界检查）
/opt/homebrew/bin/npm --prefix frontend run lint
/opt/homebrew/bin/npm --prefix frontend run typecheck
/opt/homebrew/bin/npm --prefix frontend run test
/opt/homebrew/bin/npm --prefix frontend run build
/opt/homebrew/bin/npm --prefix frontend run e2e

# 全量验证入口：后端 verify → 前端 lint / typecheck / test / build → E2E
bash scripts/test-all.sh
```

- 开发端口为后端 18095 / 前端 15195；E2E 为 18096 / 15196，使用系统临时目录数据库。不要并发跑多组 E2E，也不要与 `dev.sh` 同时跑；端口占用时确认归属，不终止不属于本次任务的进程。
- 依赖安装按锁文件使用 `/opt/homebrew/bin/npm --prefix frontend ci`；新增依赖须与任务直接相关，不顺带升级版本。

## 架构边界

### 后端

规则位于 `backend/src/test/java/com/example/library/architecture/ArchitectureTest.java`，自动纳入 Maven test / verify。

- 顶层包固定为 `domain`、`common`、`security`、`auth`、`catalog`、`reader`、`loan`、`stats`、`reminder`、`dunning`、`seed`。新增顶层包须明确依赖方向并同步规则。
- `domain` 不依赖其他项目模块或 Web / Security / Servlet API；`common` 不依赖其他项目模块；`security` 只依赖 `common` 和 `domain`。
- 业务模块可使用 `common`、`domain`、`security`；跨业务模块依赖仅允许 `reminder → loan` 和 `dunning → loan / reminder`。不要新增逆向依赖或循环。
- 唯一存量例外：`common.config.WebConfig → security.CurrentUserArgumentResolver`，用于注册参数解析器。该精确依赖已在规则中豁免，不代表整个 common 可以依赖 security；不要扩展豁免。
- Controller 做 HTTP 适配，不依赖项目 Repository、JdbcClient、JdbcTemplate 或 EntityManager；非 Controller 不依赖 Controller。
- DTO 不依赖项目 Service 或 domain Repository。现有 DTO 从实体转换、domain 使用 JPA 的方式保留，不借本任务重构成另一套架构。

### 前端

规则位于 `frontend/eslint.config.js`，自动纳入 `npm run lint`。

- `app` 负责路由与布局，可组合共享层和业务页面。
- `api`、`components`、`auth` 为共享层，不依赖 `app` 或 `features`。
- `features/*` 不依赖 `app`，不引用其他 feature 的 `pages`；跨 feature 复用优先选择 api 或 components，保留现有 circulation / catalog、stats / dunning 的协作。
- 生产代码不引用 `__tests__`；测试可复用测试夹具，但仍遵守共享层方向和跨 feature 页面边界。
- HTTP 请求统一走 `src/api/client.ts`，其他生产文件不直接调用 fetch，不引入 axios；测试可模拟 fetch。

不要删除规则、扩大 ignore、添加 eslint-disable 或跳过测试来消除失败；先排查真实依赖。需要改变边界时先说明原因并获得用户确认。

## 数据与交付安全

- 不手工修改、删除或迁移 `backend/data/` 下的开发数据库及其 WAL / SHM 文件；验证使用临时库。保留数据的迁移先停服务、备份，并在副本验证；未经明确授权不运行数据库重置脚本。
- 不改已有 Flyway 迁移；数据库变更通过新的版本化迁移实现，不用删库重建替代升级。
- `bak/` 是本地备份，不能作为源码修改或清理。`target/`、`dist/`、`node_modules/`、测试报告和缓存不能手工修改；正常构建和测试生成产物不受此限制。
- 不提交数据库、备份、凭据、环境变量文件或生成产物；未经要求不创建提交、不推送、不部署。
- 做最小范围变更，补充受影响的回归测试。完成前运行对应检查；涉及跨模块或公共行为时运行全量入口。UI 改动还须实际浏览器验证。
- 汇报实际修改、验证结果和未验证事项。失败、未运行或仅部分通过必须明确说明，不能宣称全部通过。
