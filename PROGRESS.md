# 开发进度

更新日期：2026-10-19。范围：当前本地开发分支；不代表已合并或部署。

## 当前阶段

首个完整服务端业务闭环已完成；独立审查的两项发现已修复，并通过最终全仓验证。
用户已确认设计、八项实现计划和 Native 连续执行方式。
交付链路为「注册 → 登录/首次创角 → 鉴权读档/改名 → 自动落 Mongo → 缓存 miss 回填」。
每账号一个默认角色；账号 ID 与角色 uid 分别发号。账号及密码散列同步存 Mongo，玩家档案继续异步落盘。
背包、装备、商城、货币、实时玩法、多角色和找回密码均未开始；本轮到首个闭环为止。

## 已实现

| 里程碑 | 代码范围 | 验证依据 |
| --- | --- | --- |
| 模块骨架 | game-contract / game-data / game-web / game-dbserver | 四模块构建与入口 |
| 数据原语 | LockScope/LockCtx、RedisStore/MongoStore、CommitLua/DirtyLedger、JsonCodec | 单元及真实 Redis/Mongo 集成测试 |
| 异步落盘 | FlushOrchestrator/FlushScheduler/GracefulShutdown/FlushMetrics | 分批、恢复、互斥、停机与指标测试 |
| 提交桶化 | DataKeys、CommitLua、DirtyLedger、落盘编排 | 同槽提交与多桶消费测试 |
| ID 发号器 | IdScheme、SegmentIdGenerator、MongoSegmentLeaser、MongoServerIdGuard | 发号、并发及装配测试；基线已在 main |
| 账号与会话 | MongoAccountRepository、PasswordHasher、AuthService、SessionStore、LoginRateLimiter | 唯一账号、PBKDF2、失效/过期/并发退出和限流测试 |
| 玩家业务与日志 | PlayerService、FileBizLogger | 幂等创角、归属校验、昵称校验、先日志后写、日志失败阻断 |
| HTTP 入口 | 五个 Protobuf POST 接口、AuthFilter、RequestBodyLimitFilter | 正常与错误协议、16KiB 限制、ThreadLocal/MDC 清理 |
| 首个业务闭环 | BusinessLoopIT、AccountConcurrencyIT | 真实调度落盘、清对应缓存后的冷回填、并发唯一性和会话失效 |

基线提交 `d97cbdf`；开发分支 `codex/first-business-loop`。
设计见[首个业务闭环设计](./.superpowers/specs/2026-10-16-first-business-loop-design.md)，执行清单见[实现计划](./.superpowers/plans/2026-10-16-first-business-loop.md)。
接口、环境变量、启动与验收步骤见[业务闭环指南](./docs/first-business-loop.md)。

## 收尾状态

- [x] 文档先行，核对既有源码和 Git 记录，修正基础设施/发号器过期状态。
- [x] 用户确认业务方案、书面设计和八项实现计划。
- [x] Task 1～6：契约、账号、会话、日志、玩家服务、HTTP 接口已提交。
- [x] Task 7：真实后端闭环及并发集成测试已提交。
- [x] Task 8：运行指南、架构状态与全仓回归。
- [x] 最终独立审查；两项有效发现已修复，新增回归测试先失败后通过，再跑全仓验证。

## 验证记录

2026-10-19 文档协作整理：公共规则集中到 [AGENTS.md](./AGENTS.md)，[CLAUDE.md](./CLAUDE.md)
通过 `@AGENTS.md` 导入；原工具使用说明整理至 [开发工具使用指南](./docs/development-tools.md)，
README 和 DEVELOPMENT 补充入口。保留既有 `.superpowers/specs/` 与 `.superpowers/plans/`，
明确其为两种工具共享的设计和计划，以及项目文档随代码提交、合并和冲突整合的要求。
本轮仅修改文档，不涉及 Java 格式化或重新运行 Maven 测试；下方 181 项测试结果为此前代码验证记录。
本次文档验证：PowerShell 检查上述 6 份文档的 34 个本地 Markdown 文件链接均可解析，
CodeGraph 锚点及 `CLAUDE.md` 首行导入有效；新共享指南未被 Git 忽略；`git diff --check HEAD` 通过。

2026-10-19 后续维护：首个闭环涉及的 47 个 Java 文件已通过 IDEA 格式化。
`WebConfiguration` 从 Spring 7 已弃用且标记移除的 `extendMessageConverters(List)`
迁移至 `configureMessageConverters(HttpMessageConverters.ServerBuilder)`，通过 `addCustomConverter`
将 Protobuf 转换器放在默认转换器之前。IDEA 复查该类无错误；变更后的全仓回归 181 项全部通过。
新增 [项目协作约定](./AGENTS.md)：每次修改后格式化新增/修改的 Java 类，Git 提交标题和说明使用中文。

2026-10-19 10:33:28（Asia/Shanghai）执行 `mvn verify` 成功，未携带跳过选项：

| 模块 | 单元测试 | 集成测试 | 失败 / 错误 / 跳过 |
| --- | ---: | ---: | --- |
| game-contract | 3 | 0 | 0 / 0 / 0 |
| game-data | 49 | 55 | 0 / 0 / 0 |
| game-dbserver | 20 | 24 | 0 / 0 / 0 |
| game-web | 21 | 9 | 0 / 0 / 0 |
| 合计 | 93 | 88 | 0 / 0 / 0 |

证据位置：各模块 `target/surefire-reports/TEST-*.xml` 和 `target/failsafe-reports/TEST-*.xml`。
测试总数 181；后续改动必须以新报告更新本表。

2026-10-16 定向命令 `mvn -pl game-web -am "-Dit.test=BusinessLoopIT,AccountConcurrencyIT" "-Dfailsafe.failIfNoSpecifiedTests=false" verify` 成功，执行 3 个闭环/并发 IT。
`mvn -pl game-web -am dependency:tree "-Dscope=runtime"` 确认 web 生产依赖中没有 dbserver。
文档的 Git Bash protoc 编码/解码示例实际往返成功；2026-10-19 检查的 86 个 Markdown 本地文件链接可解析，`git diff --check` 通过。

测试使用本地 Redis DB15 和 Mongo `game_test`，清理前检查显式 opt-in、loopback、非零 Redis 测试库和 `_test` Mongo 库名。
闭环 HTTP 使用真实过滤器/转换器/Controller 的 MockMvc；后端是真实 Redis/Mongo，落盘是独立客户端和独立 Spring 调度上下文里的生产代码，不手动调用 flushOnce 或 Mongo 写入冒充落盘。
日志故障用关闭真实 FileBizLogger 注入；具体锁/数据库错误映射另有单元测试。没有进行公网部署或负载验收。

## 独立审查与修复

2026-10-19 独立审查覆盖基线至首个业务闭环的整个分支、设计和测试，发现两项问题：

1. 原始 URI 跳过判断可让分号参数/百分号编码路径绕过 16KiB 与 IP 限流。现由容器映射决定过滤器范围，所有输入均受体积限制，非规范 API 路径统一拒绝；匿名和已登录请求均有回归覆盖。
2. 日志按创建时间清理可能删掉不足 48 小时的晚写记录。现从文件所属 UTC 日结束后计算 48 小时，到期才清理整段。

回归证据：`BusinessApiTest.normalizedApiAliasesCannotBypassRequestBoundaries`、
`RequestBodyLimitFilterTest.mappedApiAliasesStillEnforceTheStreamLimit`、
`FileBizLoggerTest.retentionKeepsLateRecordsForAtLeastFortyEightHours` 均先在旧代码失败，再在修复后通过；最终 `mvn verify` 181 项全部通过。
无遗留阻塞项，无暂缓的小问题。生产 web jar 已核对，不含 dbserver、test-jar 或私有测试配置。

执行调整：复用当前 checkout 的独立功能分支；用 PowerShell 维护执行记录；Maven 带点属性参数加引号以适配 PowerShell。

## 已知边界与后续

- Mongo 账号与 Redis 玩家档案不构成跨存储事务；初始化中途失败可以按预留 uid 重试。已经初始化的档案完全失踪时返回完整性错误，禁止悄悄重建。
- 保留 Redis 权威、秒级异步落盘和 AOF 原有风险边界。磁盘业务日志是操作意图，不等同于提交成功，不自动重放。
- 本地分支保留；尚未合并、推送或部署。后续业务等待新的任务范围。

## 文档维护规则

统一遵循 [AGENTS.md](./AGENTS.md) 的文档职责、交接和分支合并要求。
本文件记录进度与验证证据；[架构总纲](./.superpowers/specs/00-architecture-overview.md) 维护设计入口和里程碑。
功能状态、spec 状态与当前计划勾选同步更新；测试记录包含日期、命令、范围、数量和结果，不补写没有证据的历史验证。
