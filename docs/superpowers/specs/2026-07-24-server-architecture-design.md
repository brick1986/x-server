# x-server 架构设计（架构层）

> 范围：仅架构层决策。模块细化（清单、依赖规则、facade 接口）后续单独讨论，不在本文件内展开。

## 1. 定位与规模目标

**休闲游戏服务器**，定位为**数据仓库 + 轻交互玩法引擎**。

- **菜鸟期范围（本次落地）**：**数据仓库 + 非交互数据操作**——登录、背包、装备、商城等常规数据读写。
- **非交互玩法场景**：比如:装备、背包等常规数据操作。
- **轻交互玩法（实时玩法）**：2~20 人实时可见、竞技协作/仲裁，**独立里程碑，菜鸟期不落地**，设计见 [实时玩法（WS 长连接）设计](./2026-07-24-realtime-gameplay-ws-design.md)。

规模目标：

| 阶段 | 在线规模 | 部署形态 |
| --- | --- | --- |
| 菜鸟期 | 3000+ | 无状态 HTTP 业务进程 + 落盘进程，快速迭代 |
| 成熟期 | 10W+ | 横向扩展，缓存/数据库动态提升，特殊模块独立进程 |

架构演进原则：**按菜鸟期最简起步，架构留好成熟期升级路径**。留路方式统一为「代码留路、运行时从简」——通过抽象接口和 Protobuf 契约预留扩展点，菜鸟期不预埋额外运行时复杂度。

---

## 2. 架构形态

**Modular Monolith**：代码一体、部署可拆。

- **代码层**：Maven 多模块，按业务域切分（模块清单后续单独定）。
- **部署层**：菜鸟期 **无状态 HTTP 业务进程（可横向扩展）+ 落盘进程（DBServer）** 2 进程。HTTP 业务进程不持进程内状态，按玩家分布式锁（见 4.2）保证写正确性，多实例横向扩、滚动/灰度发布均不依赖 sticky 路由。落盘进程专责异步落 Mongo（见第 4 节），是 Mongo 唯一写者。**实时玩法（Netty WS 长连接）服务器随实时玩法一并延后至独立里程碑**（设计见 [实时玩法（WS 长连接）设计](./2026-07-24-realtime-gameplay-ws-design.md)）——引入时即成熟期实时玩法落地，作为独立进程部署（WS 按房间路由，天然随房间模块抽离）。
- **无状态化的关键取舍**：放弃"进程内 per-player 串行 + sticky 路由"方案。sticky 在灰度/滚动发布（一致性 hash 环变动）下不是稳定不变量，且把写正确性押在运维正确配置上，一旦失误即静默数据损坏。改由 Redisson 分布式锁在代码层强制同玩家写串行，正确性不依赖运维。

**拆进程触发标准（容量驱动 + 有状态优先）**：菜鸟期 HTTP 业务进程到达 3000 瓶颈时，先拆有状态场景模块（房间/匹配，进程内状态最重、最该隔离），无状态模块靠 Redis/Mongo 共享继续多实例横向扩。具体拆哪个、怎么拆，到当时按瓶颈再定。**拆进程是部署形态决策，不是架构决策**——只要模块边界和 gRPC 契约留好，到时候抽模块即可。

---

## 3. 技术栈与网络层架构

### 3.1 技术栈清单

| 层面 | 技术选型 |
| --- | --- |
| 运行环境 | **JDK 25 LTS** (Eclipse Temurin / Amazon Corretto，开启虚拟线程；已含 JEP 491，`synchronized` 不再 pin 虚拟线程) |
| Web 框架 | **Spring Boot 4.1.0** (Spring MVC, 基于 Spring Framework 7.0.8) |
| 网络通讯 | 菜鸟期 **HTTP 短连接 (Spring MVC)**；WS 长连接 (Netty **4.1.136.Final**) 随实时玩法延后（见实时玩法文档） |
| 运行时/线程模型 | **虚拟线程池**：HTTP 请求由 Tomcat 内置虚拟线程处理；Netty 消息交付虚拟线程池 |
| 数据库 | **MongoDB Driver 5.9.0** (Sync) + **Redisson 4.6.1** (Sync API + Spring Boot Starter，4.x 配 Spring Boot 4) |
| HTTP 协议 | HTTP POST + **Protobuf 4.34.1** Body |
| WebSocket 协议 | WebSocket + **Protobuf 4.34.1** 二进制帧（Unity 原生兼容）——随实时玩法延后 |
| 跨进程通信 | **Spring gRPC 1.0.3** + gRPC-Java 1.82.1 + Protobuf 4.34.1（仅契约定义，菜鸟期不部署） |
| 构建与客户端 | Maven 多模块 + Unity (C# Protobuf 生成器版本另行记录) |

**开发纪律**：

- **禁用 Reactive / 响应式链式表达（如 Mono/Flux）**。
- 统一采用平铺直叙的**同步命令式编程**，IO 阻塞时由 JDK 25 虚拟线程挂起，兼顾开发体验与高吞吐。
- **数据库连接池必须显式设限**（Redis: 100~200, Mongo: 50~100），禁止无上限分配。各池大小按**该池自身的峰值并发需求**定，不与载体线程池绑定。
- **虚拟线程 Pinning 防护（JDK 25 + JEP 491 下的新形态）**：JDK 25 已含 [JEP 491](https://openjdk.org/jeps/491)，`synchronized` 不再 pin 虚拟线程，无需再把 `synchronized` 改写成 `ReentrantLock`。**注意 `-Djdk.tracePinnedThreads=full` 已在 JDK 24 起移除，设置无效，不再使用**。残留 Pinning 仅发生于 native 代码（FFM/JNI）回调 Java 并阻塞的场景——MongoDB Driver 5.x / Redisson 4.x 均为纯 Java，预期不触达。启动自检改为通过 **JFR `jdk.VirtualThreadPinned` 事件**监测上述残留场景，若出现则定位 native 调用点并评估替代方案。
- **连接池与载体线程池配比（禁嵌套获取）**：虚拟线程等连接时 park 释放载体，不占载体线程，"批量阻塞"为良性现象，**载体线程池无需大于各连接池上限之和**（该旧规则诊断错、方向反，已废止）。死锁的真实形态是"嵌套跨池获取"——一个虚拟线程持 A 池连接又去等 B 池连接，所有 B 连接都被同样持 A 的线程占着即死锁。防法为代码纪律：**一个虚拟线程绝不同时持有两个连接池的连接**，用完一个池的连接、归还后再取下一个池的。业务读写路径的 Redis `GET`/`SET` 与 Mongo `LOAD` 顺序执行、每步独立借还；落盘进程的 `GET` Redis 与 `bulkWrite` Mongo 两步分离，均不嵌套。载体线程池用 JDK 25 默认（基于 CPU 核数），不再通过 `maxPoolSize` 强行放大。
- **Netty 版本统一 BOM 钉版**：Netty 会被 Redisson、gRPC-Java、Spring Boot 间接传递引入，多模块下极易版本漂移。统一通过 **Netty 4.1.x BOM** 在根 POM 钉定 `4.1.136.Final`，所有传递依赖归一，禁止子模块各自声明 Netty 版本。
- **同玩家写串行化（Redisson 分布式锁，按实体分锁）**：同一实体的写操作必须跨进程串行，由 **Redisson 分布式锁 `lock:{entity}:{id}`** 保证（按实体粒度，覆盖该实体所有 key 的读-改-写，守护跨 key 业务不变量）。`lock:player:{id}` 管玩家私有数据（profile/bag/equipment，同玩家大多只操作自己数据，天然低竞争）；`lock:guild:{id}` 管工会等公共实体（竞争真正发生处）；后续新增实体按同规则 `lock:{entity}:{id}` 分配。这是数据层覆盖写正确性的前提（见 4.2），也是无状态 HTTP 横向扩展下不依赖 sticky 路由的关键。**锁租约固定 `leaseTime=10s`、禁用看门狗**：加锁一律传固定租约、不使用 Redisson 默认无限续约——正常操作（<100ms）主动 `unlock` 立即释放，操作超 10s 则锁 TTL 自然到期释放，进程卡死时锁最迟 10s 自愈、**无锁毒**（替代原"看门狗续约上界"方案，该方案停止续约会静默破串行不变量，已废止）。**提交门控**：覆盖写唯一提交点（`SET + SADD` Lua）执行前强制 `isHeldByCurrentThread()` 校验，失锁即抛异常 abort、绝不带着失效锁完成写（fail-fast）。`isHeld` 检查到 Lua 执行间的微 TOCTOU 窗口仅灾难点触发、菜鸟期接受；成熟期升级路径为 Lua token 门控（锁 token 传 Lua 原子校验后才提交），届时再评估。**跨实体加锁**：同时触玩家数据 + 公共数据（如玩家向工会捐献）时按**全局类型优先级**依次加锁防死锁（菜鸟期 `guild > player`）；同类型内（如两玩家转账）按 ID 序加锁（`lock:player:{min}` + `lock:player:{max}`）。

---

### 3.2 网络通信（菜鸟期：仅 HTTP 短连接）

菜鸟期仅落地 **HTTP 短连接通道**：登录、数据读写、背包、商城等非实时操作走 HTTP POST + Protobuf，Spring MVC + Tomcat 自动挂载虚拟线程，处理完即断开。

**WS 长连接通道**（实时竞技、位置同步、玩法仲裁，按需建立，含完整长短连接分流蓝图与通道职责划分）**随实时玩法延后至独立里程碑**，设计见 [实时玩法（WS 长连接）设计](./2026-07-24-realtime-gameplay-ws-design.md)。

**明确不做（YAGNI）**：

* 菜鸟期不引入 Redis Pub/Sub / Stream 做跨进程广播与通知。

---

## 4. 数据层架构（Write-Behind）

Redis 权威 + Redis 标脏 + **独立进程异步落 Mongo** + **业务侧磁盘日志（人工对账依据）**。规避 MongoDB 多文档事务。

### 4.1 数据组织

**形态原则：Redis 与 MongoDB 同构（JSON），落盘零转换。**

- **Redis 数据结构**：玩家数据按 key 拆分，每个 key 为一个 **String + JSON**（覆盖写粒度）：
  - `player:{id}:profile` —— 主档聚合根（货币、等级、基础信息、常用小计数），一个 JSON。
  - `player:{id}:bag` —— 背包，一个 JSON 数组。
  - `player:{id}:equipment` —— 装备，一个 JSON（视玩法可选拆分）。
  - 货币/库存等计数以 Redis 为权威。
- **MongoDB 数据结构**：与 Redis key 一一对应的文档——`players` / `bags` / `equipments` 集合，每个玩家每类一个文档（`_id = 玩家ID`），文档体即 Redis 那份 JSON。**与 Redis 同构，落盘 = GET Redis JSON → upsert Mongo 文档，无重组**。留路：若背包膨胀逼近 Mongo 16MB 文档上限，再拆为每物品一文档（届时落盘需做"以 Redis 为准的整体同步：upsert 现有 + 删除多余"）。
- **懒加载源**：登录仅载 `profile` 到 Redis；`bag`/`equipment` 按需从 Redis 拉，Redis 未命中则从 Mongo 加载并回填 Redis（Redis 权威，Mongo 冷源）。**读路径持锁**：读操作同样获取 `lock:{entity}:{id}`（读写共用同一把互斥 `RLock`），锁内完成 `GET` → miss 则从 Mongo 加载 → **普通 `SET` 回填**（持锁期间无并发写，无需 `SET NX`）→ 释放。回填进锁是消除丢失更新竞态的关键——否则不持锁回填会把持锁写的 v2 覆盖回从 Mongo 读到的 v1，污染 Redis 后再被落盘进程写回 Mongo，数据彻底丢失。

### 4.2 写入流程与并发控制

1. **覆盖写**：业务进程读 key 的 JSON → Java 内修改 → 构造完整新 JSON → 写回 Redis。写回用一个**极简 Lua**仅做 `SET key newjson; SADD dirty key`（**Lua 不解析 JSON**，只把"写数据 + 标脏"绑成一次原子往返）。标脏粒度**按 key 级**（见 4.3）。写路径读 key 时若 miss，须**在锁内从 Mongo 加载**再改（与读路径同源），不可假设 key 一定在 Redis。**提交门控**：执行提交 Lua 前强制 `isHeldByCurrentThread()` 校验，失锁即 abort、不写（见 §3.1）。
2. **同实体写串行化（Redisson 分布式锁）**：覆盖写是"读-改-写"发生在 Java 侧、非 Redis 原子，故**同一实体的写操作必须跨进程串行**，否则并发覆盖会丢更新（如两次扣费/充值互相覆盖）。由 **Redisson 分布式锁 `lock:{entity}:{id}`** 保证（按实体粒度，读写共用同一把互斥 `RLock`），这是无状态 HTTP 横扩、不依赖 sticky 路由的前提，也保护所有同实体业务不变量，不额外增加 Redis 侧 CAS。锁租约固定 `leaseTime=10s`、禁用看门狗，提交前 `isHeld` 门控（见 §3.1）。
3. **跨实体操作（转账/交易/工会捐献）**：不做跨 Key Lua（预防成熟期 Redis Cluster 报错）。**跨玩家**（同类型）采用**按 ID 序加双锁**（`lock:player:{min(A,B)}` + `lock:player:{max(A,B)}`，按固定顺序加锁防死锁）；**跨类型**（如玩家 + 工会）按**全局类型优先级**加锁（菜鸟期 `guild > player`，先加 guild 再加 player）。持锁期间完成**各实体的独立覆盖写**，各自由该实体锁保证单 key 正确，多锁使正常路径下跨 key 一致。写顺序：**先写业务日志（增量 + before/after 绝对值）→ 覆盖写 A → 覆盖写 B**。**中途崩溃不做自动恢复**——持锁崩溃后锁由 TTL 自然释放，若已写 A 未写 B 留下的不一致，靠业务日志事后人工对账补偿（见 4.2.4、4.4）。不引入自动重放，避免重放机制本身的复杂度与 bug 风险。
4. **业务日志（关键操作，人工对账依据）**：扣费/合成/交易/充值/赠送等关键操作追加**业务侧详细日志**——磁盘 append-only 文件，**独立于 Redis 与 MongoDB**，先于返回客户端成功写盘。**日志定位为人工介入时的对账与补偿依据，不参与任何自动崩溃恢复**。每条记录 **增量 + before 绝对值 + after 绝对值**（如"A 扣 30，before 100，after 70"）——三者冗余便于人工核对，且**纯磁盘、不依赖 Redis 等第三方**，可独立离线审阅。不记整份 JSON 快照。

### 4.3 异步落盘（独立进程）

- **落盘进程独立**：由**独立进程**（DBServer，非业务进程）扫描 Redis dirty 集合，异步落地到 MongoDB。落盘进程是 MongoDB 的**唯一写者**，串行化天然防覆盖，故不引入版本号乐观锁。留路：若未来落盘多实例并行，需重新引入版本号或按玩家分片归并。
- **标脏粒度按 key 级**：dirty 集合成员为数据 key（如 `player:123:profile`），落盘进程 `SMEMBERS dirty` → 对每个 key `GET` JSON → upsert 对应 Mongo 文档 → 落盘成功后 `SREM`。改哪类落哪类，同玩家多个 dirty key 在同次扫描里合并 `bulkWrite`。
- **落盘触发**：独立进程定时（1~3s）扫 dirty。**不做下线强刷**——玩家下线是业务进程本地事件，跨进程通知落盘进程强刷只省 1~3s 却引入跨进程调用，不值当；下线玩家数据等下一个落盘周期即可。停机场景的 dirty 全落由**DBServer优雅停机流程**保证。
- **上线冷启动**：Redis 无数据则从 Mongo 加载到 Redis（Redis 权威，Mongo 是冷源）。
- **批量落盘**：跨玩家 dirty key 合并为 `bulkWrite`，MongoDB Driver 5.9.0 原生支持，避免逐 key `updateOne` 的往返开销。
- **DBServer优雅停机流程**：正常停DBServer服时，dirty 数据全量刷入 MongoDB

### 4.4 兜底与撤回项

* **Redis 可靠性**：AOF `everysec` + 主从，接受秒级丢失窗口。
* **崩溃恢复策略（不自动重放）**：崩溃后以 Redis 当前状态为准（AOF 秒级窗口内的丢失视为可接受），**不依据业务日志做自动重放/补偿**。持锁崩溃由 TTL 释放锁，跨玩家操作中途（已写 A 未写 B）的不一致**留待事后人工**依据业务日志对账补偿。
* **业务日志**：磁盘 append-only，保留窗口（24~48h）+ 对账，定位为**人工对账与事后补偿依据**。**日志载体为磁盘文件，不落 Redis、不落 MongoDB**（避免与权威存储同命运）；记 增量 + before/after 绝对值，非快照。
* **锁可用性**：Redis 故障导致锁不可用时，写操作**快速失败**而非静默继续（静默继续会丢更新），由上层重试或返回错误。锁租约到期（操作超 10s）或提交门控 `isHeld` 校验失败时同样 fail-fast abort（见 §3.1）。
* **撤回项**：❌ 不使用 Mongo 多文档事务；❌ Redis 不承担消息/会话职责；❌ 业务日志不写入 Redis/Mongo；❌ 不做下线强刷；❌ 不依据业务日志做自动崩溃恢复/重放。

---

## 5. 会话与认证

### 5.1 会话与断连

WS 长连接的会话机制（进程内 Channel 映射、15s Grace Period 挂起防闪断风暴、`SessionManager` 接口留路）**随实时玩法延后至独立里程碑**，设计见 [实时玩法（WS 长连接）设计](./2026-07-24-realtime-gameplay-ws-design.md)。

### 5.2 认证与安全

- **登录换 token**：校验通过签发不透明 token 存 Redis（带 TTL）。
- **HTTP 鉴权**：请求头带 token，Spring Filter 校验，通过 ThreadLocal/MDC 传递上下文。
- **防重进**：同账号单点在线，登录成功顶掉旧 token（在 Redis 覆盖/删除旧 token）。无状态 HTTP 下旧实例无需跨进程通知——旧会话下次请求鉴权即因 token 失效被拒，自然踢下线。
- **WS 鉴权**：随实时玩法延后，见 [实时玩法（WS 长连接）设计](./2026-07-24-realtime-gameplay-ws-design.md)。

---

## 6. 非功能配套（菜鸟期最小集）

| 项 | 方案 |
| --- | --- |
| **配置** | `application.yml` + 环境变量，不引入配置中心 |
| **日志** | Logback + Standard `MDC`（链路跟踪：`MDC.put("userId", ...)`） |
| **监控** | Spring Boot Actuator 指标导出；通过 **JFR `jdk.VirtualThreadPinned` 事件**监测虚拟线程残留 Pinning（仅 native 回调场景） |
| **定时任务** | Spring `@Scheduled`（用于数据层扫 dirty 落盘调度） |
| **热更与 CI** | 不做热更，停服发版；Maven 打包业务进程 + 落盘进程，不引入容器编排 |

---

## 7. 架构层决策汇总

| # | 决策项 | 结论 |
| --- | --- | --- |
| 1 | **运行时/线程模型** | **Spring Boot 4.1.0 + JDK 25 虚拟线程**（含 JEP 491），摒弃 Reactive，全面采用同步命令式代码 |
| 2 | **网络架构** | 菜鸟期仅 **HTTP (MVC) 无状态横扩**；WS (原生 Netty) 实时玩法通道**随实时玩法延后**（见实时玩法文档） |
| 3 | **数据一致性** | Write-Behind + **独立进程串行落 Mongo（唯一写者，免版本号）** + **Redisson 分布式锁 `lock:{entity}:{id}` 保证同实体读写串行（读路径回填进锁、写路径锁内 miss 加载）** + **固定 leaseTime 10s 无看门狗 + isHeld 提交门控** + **禁嵌套跨池获取** + 业务侧磁盘日志（人工对账，不自动恢复） |
| 4 | **会话机制** | 菜鸟期仅 HTTP 鉴权（Redis token）；WS 会话/15s 挂起**随 WS 延后**（见实时玩法文档） |
| 5 | **跨进程与演进** | 菜鸟期无跨进程，gRPC 契约预留；容量达 3000 时优先拆有状态场景进程；WS/实时玩法为独立里程碑 |
| 6 | **非功能配套** | Actuator 监控 + Standard MDC 日志链路 + JFR `jdk.VirtualThreadPinned` 事件监测残留 Pinning |

---

## 8. 后续待定（不在本架构层范围）

- 模块清单与依赖规则（Modular Monolith 模块边界、facade 接口契约）。
- **WS/实时玩法服务器（独立里程碑）**：已拆分至 [实时玩法（WS 长连接）设计](./2026-07-24-realtime-gameplay-ws-design.md)，含 Netty WS 服务器、场景/房间模型与路由、玩法状态机、会话与 15s 挂起、WS 部署形态。
- **惊群效应防护**：业务进程崩溃后 3000+ 并发重连 + Redis 冷启动拉全量状态的渐进恢复流程设计。
- **优雅停机流程**：正常停服时 in-flight 请求等待完成、WS 连接有序断开的具体流程。