# 开发进度

更新日期：2026-10-16。范围：当前本地工作树；不代表生产环境部署状态。

## 当前阶段

数据基础设施已实现，首个业务闭环设计已于 2026-10-16 获用户确认；实现计划已编写，待审阅及选择执行方式。
已确认采用账号名＋密码进行本地注册和登录，账号同步存 Mongo、玩家档案继续异步落盘。
目标到「注册 → 登录/首次创角 → 玩家档案读取与修改 → 自动落 Mongo → 缓存 miss 回填」为止。
背包、装备、商城、实时玩法以及多角色管理不在本轮范围。

## 已实现

| 里程碑 | 代码范围 | 依据 |
| --- | --- | --- |
| 模块骨架 | game-contract / game-data / game-web / game-dbserver | 根 POM、四模块构建与入口 |
| 数据原语 | LockScope/LockCtx、RedisStore/MongoStore、CommitLua/DirtyLedger、JsonCodec | 源码及对应单元/集成测试 |
| 异步落盘 | FlushOrchestrator/FlushScheduler/GracefulShutdown/FlushMetrics | 分批落盘、恢复、互斥、停机与指标代码 |
| 提交桶化 | DataKeys、CommitLua、DirtyLedger、落盘编排 | 2026-09-22 至 09-23 提交；同槽提交协议 |
| ID 发号器 | IdScheme、SegmentIdGenerator、MongoSegmentLeaser、MongoServerIdGuard | 2026-10-08 提交，含装配和测试；已进入 main |

基线提交：`d97cbdf`（2026-10-08）。核对时本地 main 与本地 origin/main 引用相同；未联网刷新远端。

## 当前工作与完成条件

- [x] 核对源码、Git 记录和已有测试报告，修正发号器待合并等过期状态。
- [x] 修复开发指南中的 spec 路径、固定测试数量及过于笼统的缓存清理说明。
- [x] 编写[首个业务闭环设计](./.superpowers/specs/2026-10-16-first-business-loop-design.md)，用户已确认。
- [x] 编写并自审[实现计划](./.superpowers/plans/2026-10-16-first-business-loop.md)，共八项任务。
- [ ] 用户审阅实现计划并选择执行方式。
- [ ] 实现账号注册/登录、token 鉴权、业务日志和玩家档案接口。
- [ ] 通过并发、异常和 HTTP 到 Mongo 冷回填的闭环测试。
- [ ] 更新接口说明、运行/验收步骤和验证记录，再将里程碑标为完成。

## 验证记录

2026-10-16 实现计划检查：8 项任务、47 个待执行步骤，已核对设计覆盖与任务间接口；
15 份修改/新增 Markdown 文档中的 66 个本地文件链接可解析，`git diff --check` 通过。
本次仅编写计划和同步状态，未运行 Java 测试、未开始实现。

2026-10-16 文档维护检查：14 份修改/新增 Markdown 文档、代码示例之外的 62 个本地文件链接均可解析；
`git diff --check` 通过。本轮尚未修改业务代码，未重新运行 Java 测试。

2026-10-16 读取本地 2026-10-08 的既有 XML 测试报告，未重新执行：

| 模块 | 单元测试 | 集成测试 | 失败 / 错误 / 跳过 |
| --- | ---: | ---: | --- |
| game-contract | 2 | 0 | 0 / 0 / 0 |
| game-data | 48 | 55 | 0 / 0 / 0 |
| game-dbserver | 20 | 24 | 0 / 0 / 0 |
| game-web | 1 | 0 | 0 / 0 / 0 |
| 合计 | 71 | 79 | 0 / 0 / 0 |

证据位置：各模块 `target/surefire-reports/TEST-*.xml` 和 `target/failsafe-reports/TEST-*.xml`。
这些是历史快照，不能证明后续改动已通过测试，也不能证明当前外部服务可用。

## 文档维护规则

1. 本文件记录开发状态和验证证据；[架构总纲](./.superpowers/specs/00-architecture-overview.md) 汇总设计入口及里程碑。
2. 完成一项功能时同步相关 spec 状态及计划勾选；完成验证后记录日期、命令、范围和结果。
3. 旧计划是执行前的步骤设计，不将未勾选的历史步骤直接当作待办，也不追补无法证明的历史执行记录。
4. 架构变更写入新的修订设计并登记总纲；引用旧文档时指出被新设计取代的部分。
5. 本轮完成首个闭环后停止扩展功能；后续业务保留为未开始。
