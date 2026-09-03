# x-server 架构总纲（Specs 总入口）

> 本文档是 `.superpowers/specs/` 的**总架构文档与索引**：先给全局概览，再链接到各子模块文档。
> 权威细节一律见对应子文档，本文档只做导航与速览，不重复、不取代任何子文档内容。
>
> 阅读顺序建议：本文档 → [架构层设计](./2026-07-24-server-architecture-design.md) → 按需进入各子文档（见 [§9 阅读导航](#9-阅读导航)）。

---

## 1. 文档地图（Spec Index）

| 文档 | 日期 | 性质 | 状态 | 一句话职责 |
| --- | --- | --- | --- | --- |
| [00-architecture-overview.md](./00-architecture-overview.md) | — | 总纲 / 索引 | 本文档 | 全局概览 + 子文档导航 |
| [2026-07-24-server-architecture-design.md](./2026-07-24-server-architecture-design.md) | 2026-07-24 | 架构层设计（权威主文档） | 部分段落已被修订文档取代 | 定位 / 规模 / 形态 / 技术栈 / 数据层 / 会话 / 非功能，全项目架构决策源头 |
| [2026-07-24-realtime-gameplay-ws-design.md](./2026-07-24-realtime-gameplay-ws-design.md) | 2026-07-24 | 独立里程碑设计 | 预留，菜鸟期不落地 | 实时玩法（Netty WS 长连接）通道、会话、鉴权、与主文档数据层衔接 |
| [2026-08-04-data-concurrency-fixes-design.md](./2026-08-04-data-concurrency-fixes-design.md) | 2026-08-04 | 修订文档 | 已并入实现（game-data 落地） | 修正架构层设计三处正确性问题：锁模型泛化 / 读写路径 / 并发池配比 / 看门狗去除 |
| [2026-08-05-module-breakdown-design.md](./2026-08-05-module-breakdown-design.md) | 2026-08-05 | 模块设计 | 已落地（模块骨架） | 菜鸟期 5 模块划分、职责、依赖规则（架构 §8 待定项的基础设施部分） |
| [2026-08-11-game-data-primitives-design.md](./2026-08-11-game-data-primitives-design.md) | 2026-08-11 | 模块内设计（Plan B） | 已实现（代码 + 集成测试已合并） | `game-data` 数据原语：LockScope / LockCtx / CommitLua / DirtyLedger / JsonCodec 对外 API 与内部规格 |

> 执行计划（非 spec）位于 `.superpowers/plans/`：`2026-08-05-module-skeleton.md`、`2026-08-13-game-data-primitives.md`。specs 只放设计决策，plans 放落地步骤。

---

## 2. 项目定位与规模目标

- **定位**：休闲游戏服务器——数据仓库 + 轻交互玩法引擎（学习 CC 用）。
- **菜鸟期**（本次范围）：数据仓库 + 非交互数据操作（登录、背包、装备、商城），3000+ 在线。
- **成熟期**：10W+ 在线，横向扩展，特殊模块独立进程。
- **演进原则**：按菜鸟期最简起步，架构留好成熟期升级路径——代码留路、运行时从简。

详见 [架构层设计 §1](./2026-07-24-server-architecture-design.md)。

---

## 3. 架构形态

- **Modular Monolith**：代码一体（Maven 多模块）、部署可拆。
- **部署 = 2 进程**：无状态 HTTP 业务进程（`game-web`，可横向扩展）+ 落盘进程（`game-dbserver`，MongoDB 唯一写者）。
- **写正确性不依赖 sticky 路由**：同实体写串行由 Redisson 分布式锁在代码层强制，多实例横扩 / 滚动发布均安全。
- **实时玩法（WS）为独立里程碑、独立进程**，菜鸟期不落地。

详见 [架构层设计 §2](./2026-07-24-server-architecture-design.md)。

---

## 4. 技术栈速览

> 版本以 [架构层设计 §3.1](./2026-07-24-server-architecture-design.md) 为权威，本表只做速览。

| 层面 | 选型 |
| --- | --- |
| 运行环境 | JDK 25 LTS（虚拟线程；含 JEP 491，`synchronized` 不 pin 虚拟线程） |
| Web 框架 | Spring Boot 4.1.0（Spring MVC，Spring Framework 7.0.8） |
| 网络 | 菜鸟期 HTTP POST + Protobuf 4.34.1 Body；WS（Netty 4.1.136.Final）随实时玩法延后；Spring gRPC 契约预留、菜鸟期不部署 |
| 数据 | Redis（Redisson 4.6.1，Sync）+ MongoDB（Sync Driver 5.9.0）；连接池显式设限（Redis 100~200、Mongo 50~100） |
| 开发纪律 | 禁用 Reactive；同步命令式；禁嵌套跨池获取；Netty 版本统一 BOM 钉版于根 POM |

---

## 5. 数据层核心决策速览

> 速览仅作导航。三处正确性修订以 [数据并发正确性修订](./2026-08-04-data-concurrency-fixes-design.md) 为准（其 §5 汇总了全部改点）。

- **Write-Behind**：Redis 权威 + key 级标脏（`dirty` 集合）+ 独立进程异步落 Mongo；Redis 与 MongoDB 同构（JSON），落盘零转换、无版本号。
- **同实体写串行**：`lock:{entity}:{id}` 互斥锁，读写共用；跨实体按全局类型优先级（菜鸟期 `guild > player`）+ 同类型 ID 序加锁，all-or-nothing。
- **固定租约 10s、无看门狗 + isHeld 提交门控**：失锁 fail-fast abort，无锁毒。
- **读路径持锁回填**：miss 时锁内从 Mongo 加载 + 普通 `SET` 回填，消除丢失更新竞态。
- **业务日志**：关键操作先于提交写磁盘（增量 + before/after 绝对值），人工对账依据，不参与自动恢复。
- **崩溃策略**：不自动重放；锁 TTL 自愈；跨实体中途崩溃留待人工对账。

---

## 6. 模块与部署

| 模块 | artifactId | 职责 | 部署形态 |
| --- | --- | --- | --- |
| 根 POM | `game-parent` | BOM 钉版（Netty 等）、公共依赖管理 | 不产出制品 |
| 契约 | `game-contract` | proto wire 消息 + gRPC 契约（预留） | 不部署 |
| 数据原语 | `game-data` | Redis/Mongo 封装 + 连接池 + 锁 + 覆盖写 + JSON 编解码 | 被业务进程与落盘进程依赖 |
| 业务进程 | `game-web` | HTTP 业务进程：业务域 + proto↔domain 映射 + 鉴权 + 业务日志 | 部署进程 ① |
| 落盘进程 | `game-dbserver` | 落盘编排 + 定时调度 + 优雅停机刷盘 | 部署进程 ② |

**依赖方向**（严格单向、禁止环依赖）：

```
game-web      → game-data + game-contract
game-dbserver → game-data（唯一依赖）
game-data / game-contract → 不依赖任何业务模块
```

详见 [模块清单与依赖规则设计](./2026-08-05-module-breakdown-design.md)。

---

## 7. 里程碑与落地现状

| 里程碑 | 范围 | 状态 |
| --- | --- | --- |
| 模块骨架 | `game-parent` + 4 模块 POM、启动类、契约种子 | ✅ 已落地（plan: `2026-08-05-module-skeleton.md`） |
| game-data 数据原语（Plan B） | LockScope/LockCtx/CommitLua/DirtyLedger/JsonCodec + 自动装配 + 11 个正确性集成测试 | ✅ 已实现并合并（plan: `2026-08-13-game-data-primitives.md`） |
| Plan C（横切 + 落盘） | `game-web` 的 AuthFilter / BizLogger、`game-dbserver` 的 FlushOrchestrator / FlushScheduler / GracefulShutdown | ⏳ 待做（范围见 [原语设计 §8](./2026-08-11-game-data-primitives-design.md)） |
| 业务域 | player / bag / equipment / shop 等 | ⏳ 待做（模块内部划分待单独讨论） |
| 实时玩法 WS | Netty WS 长连接、场景/房间模型、会话挂起 | 🔒 独立里程碑，菜鸟期不落地（见 [实时玩法设计](./2026-07-24-realtime-gameplay-ws-design.md)） |

---

## 8. 文档关系与修订链

```
架构层设计 (2026-07-24) ──被修订──► 数据并发正确性修订 (2026-08-04)
        │                                     │
        ├─延伸─► 实时玩法 WS 设计 (2026-07-24)  └─落地于─► game-data 数据原语 (2026-08-11)
        └─延伸─► 模块清单与依赖规则 (2026-08-05)            （Plan B，已实现）
```

- **架构层设计**是唯一权威主文档；**并发正确性修订**对其三处问题（锁粒度、读写路径回填、看门狗/池配比）做了修订——阅读与落地时以修订文档为准。
- **模块清单与依赖规则**落地架构 §8「模块清单与依赖规则」待定项（基础设施部分）。
- **game-data 数据原语**是架构 §3.1/§4 与并发修订 §1~§4 在 `game-data` 层面的落地规格（Plan B）。
- **实时玩法 WS 设计**复用架构层的数据层与认证体系，是独立里程碑。

---

## 9. 阅读导航

| 目的 | 阅读路径 |
| --- | --- |
| 快速了解项目 | 本文档 → [架构层设计](./2026-07-24-server-architecture-design.md) |
| 理解模块边界与依赖规则 | [模块清单与依赖规则设计](./2026-08-05-module-breakdown-design.md) |
| 改数据层 / 锁 / 落盘相关代码 | [并发正确性修订](./2026-08-04-data-concurrency-fixes-design.md) + [game-data 原语设计](./2026-08-11-game-data-primitives-design.md)（+ 对应 plan） |
| 实现 Plan C（game-web 横切 / game-dbserver 落盘） | [模块设计 §3.4/§3.5](./2026-08-05-module-breakdown-design.md) + [原语设计 §8（范围）](./2026-08-11-game-data-primitives-design.md) |
| 了解实时玩法 | [实时玩法 WS 设计](./2026-07-24-realtime-gameplay-ws-design.md)（独立里程碑） |

---

## 附录 A：specs 目录维护约定

为保持总纲导航长期有效、避免再次杂乱：

1. **新 spec 一律登记到 §1 文档地图**，保持「总纲 → 子文档」单向导航成立。
2. **修订既有决策时新建修订文档**，声明被修订的原文位置与改点汇总（参照并发修订文档 §5 的格式），不直接改写权威主文档的历史表述。
3. **specs 只放设计决策**，落地步骤放 `plans/`；已实现的 spec 在文档地图「状态」列标注。
