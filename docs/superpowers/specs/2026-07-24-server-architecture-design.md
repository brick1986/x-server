# x-server 架构设计（架构层）

> 范围：仅架构层决策。模块细化（清单、依赖规则、facade 接口）后续单独讨论，不在本文件内展开。

## 1. 定位与规模目标

**休闲游戏服务器**，定位为**数据仓库 + 轻交互玩法引擎**。

- **非交互玩法场景**：比如:装备、背包等常规数据操作。
- **轻交互玩法场景**：比如:2~20 人实时可见、竞技协作/仲裁。
- **同步模型**：**状态同步为主**。服务器持有权威状态，客户端上报意图，服务器校验/推进后广播变更。不做帧同步、不引入确定性逻辑/追帧机制。

规模目标：

| 阶段 | 在线规模 | 部署形态 |
| --- | --- | --- |
| 菜鸟期 | 3000+ | 业务进程 + 落盘进程，快速迭代 |
| 成熟期 | 10W+ | 横向扩展，缓存/数据库动态提升，特殊模块独立进程 |

架构演进原则：**按菜鸟期最简起步，架构留好成熟期升级路径**。留路方式统一为「代码留路、运行时从简」——通过抽象接口和 Protobuf 契约预留扩展点，菜鸟期不预埋额外运行时复杂度。

---

## 2. 架构形态

**Modular Monolith**：代码一体、部署可拆。

- **代码层**：Maven 多模块，按业务域切分（模块清单后续单独定）。
- **部署层**：菜鸟期 **业务进程 + 落盘进程** 2 进程（落盘进程专责异步落 Mongo，见第 4 节）；成熟期按模块继续抽独立进程，故障隔离与流量隔离。

**拆进程触发标准（容量驱动 + 有状态优先）**：菜鸟期业务进程到达 3000 瓶颈时，先拆有状态场景模块（房间/匹配，进程内状态最重、最该隔离），无状态模块靠 Redis/Mongo 共享继续多实例横向扩。具体拆哪个、怎么拆，到当时按瓶颈再定。**拆进程是部署形态决策，不是架构决策**——只要模块边界和 gRPC 契约留好，到时候抽模块即可。

---

## 3. 技术栈与网络层架构

### 3.1 技术栈清单

| 层面 | 技术选型 |
| --- | --- |
| 运行环境 | **JDK 25 LTS** (Eclipse Temurin / Amazon Corretto，开启虚拟线程；已含 JEP 491，`synchronized` 不再 pin 虚拟线程) |
| Web 框架 | **Spring Boot 4.1.0** (Spring MVC, 基于 Spring Framework 7.0.8) |
| 网络通讯 | **混合模式**：HTTP 短连接 (Spring MVC) + WS 长连接 (Netty **4.1.136.Final**) |
| 运行时/线程模型 | **虚拟线程池**：HTTP 请求由 Tomcat 内置虚拟线程处理；Netty 消息交付虚拟线程池 |
| 数据库 | **MongoDB Driver 5.9.0** (Sync) + **Redisson 4.6.1** (Sync API + Spring Boot Starter，4.x 配 Spring Boot 4) |
| HTTP 协议 | HTTP POST + **Protobuf 4.34.1** Body |
| WebSocket 协议 | WebSocket + **Protobuf 4.34.1** 二进制帧（Unity 原生兼容） |
| 跨进程通信 | **Spring gRPC 1.0.3** + gRPC-Java 1.82.1 + Protobuf 4.34.1（仅契约定义，菜鸟期不部署） |
| 构建与客户端 | Maven 多模块 + Unity (C# Protobuf 生成器版本另行记录) |

**开发纪律**：

- **禁用 Reactive / 响应式链式表达（如 Mono/Flux）**。
- 统一采用平铺直叙的**同步命令式编程**，IO 阻塞时由 JDK 25 虚拟线程挂起，兼顾开发体验与高吞吐。
- **数据库连接池必须显式设限**（Redis: 100~200, Mongo: 50~100），禁止无上限分配。
- **虚拟线程 Pinning 防护（JDK 25 + JEP 491 下的新形态）**：JDK 25 已含 [JEP 491](https://openjdk.org/jeps/491)，`synchronized` 不再 pin 虚拟线程，无需再把 `synchronized` 改写成 `ReentrantLock`。**注意 `-Djdk.tracePinnedThreads=full` 已在 JDK 24 起移除，设置无效，不再使用**。残留 Pinning 仅发生于 native 代码（FFM/JNI）回调 Java 并阻塞的场景——MongoDB Driver 5.x / Redisson 4.x 均为纯 Java，预期不触达。启动自检改为通过 **JFR `jdk.VirtualThreadPinned` 事件**监测上述残留场景，若出现则定位 native 调用点并评估替代方案。
- **载体线程池与连接池比例约束**（防连接池耗尽，与 Pinning 无关）：载体线程池大小（`jdk.virtualThreadScheduler.maxPoolSize`）应 ≥ 各数据库连接池上限之和。例如 Redis 连接池 128 + Mongo 连接池 100 = 总 228，则载体线程池至少 ≥ 228（通过 JVM 启动参数 `-Djdk.virtualThreadScheduler.maxPoolSize=256` 设置），避免连接池耗尽时虚拟线程批量阻塞。
- **Netty 版本统一 BOM 钉版**：Netty 会被 Redisson、gRPC-Java、Spring Boot 间接传递引入，多模块下极易版本漂移。统一通过 **Netty 4.1.x BOM** 在根 POM 钉定 `4.1.136.Final`，所有传递依赖归一，禁止子模块各自声明 Netty 版本。

---

### 3.2 网络通信分流设计（长短连接分离）

采用 **“HTTP 负责常规数据，Netty 负责实时交互”** 的按需连撤机制：

```
[客户端 Unity]
       │
       ├─ (1) 登录/背包/装备/商城 (常规操作) ──────────────► [HTTP POST / Spring Boot MVC]
       │                                                       (短连接，处理完即断开)
       │
       ├─ (2) 点击“进入 2~20 人房间” ────────────────────────► [HTTP POST] 申请房间，获取 IP + RoomToken
       │
       ├─ (3) 建立 WebSocket 长连接 (携带 RoomToken) ───────► [嵌入式 Netty WS Server]
       │                                                       (原生 Netty Channel Pipeline)
       │
       ├─ (4) 场景内实时移动/战斗仲裁 ◄══════════════════════► [Netty WS Server]
       │                                                       (二进制 Protobuf 双向持续通信)
       │
       └─ (5) 结算/退出场景 ─────────────────────────────────► [Netty WS Server] 断开 WS，释放 Channel 资源

```

#### 通道职责划分：

| 通道 | 承载业务 | 技术形态与线程模型 |
| --- | --- | --- |
| **HTTP 短连接** | 登录、数据读写、背包、商城等非实时操作 | POST + Protobuf；Spring MVC + Tomcat 自动挂载虚拟线程 |
| **WS 长连接** | 实时竞技、位置同步、玩法仲裁（仅进入玩法时建立） | 原生 Netty 监听专属端口 + Protobuf 解码；业务丢入虚拟线程池 |
| **gRPC** | 跨进程同步调用 | Protobuf 契约预留，菜鸟期只留在代码不部署 |

**明确不做（YAGNI）**：

* 菜鸟期不引入 Redis Pub/Sub / Stream 做跨进程广播与通知。
* 不预埋独立网关进程，成熟期拆进程时，客户端直接直连独立的战斗进程 Netty 端口。

---

## 4. 数据层架构（Write-Behind）

Redis 权威 + Redis 标脏 + **独立进程异步落 Mongo** + **业务侧磁盘日志兜底**。规避 MongoDB 多文档事务。

### 4.1 数据组织

- **玩家主档单文档聚合根**：货币、等级、基础信息、常用小计数放一个 MongoDB 文档，`updateOne` 带条件天然原子。
- **背包/装备分文档**：每个物品一个文档，单文档条件更新。
- **Redis 数据结构**：玩家数据按玩家级聚合存储（Hash/String），货币/库存等计数以 Redis 为权威。
- **懒加载源**：登录仅载常用字段/货币到 Redis；背包等冷数据按需从 Redis 拉，Redis 未命中则从 Mongo 加载并回填 Redis（Redis 权威，Mongo 冷源）。

### 4.2 写入流程与并发控制

1. 写操作先到 Redis，**单 Key Lua 脚本原子执行**（改数据 + 标脏）。
2. **跨玩家逻辑**：避免跨 Slot 的多 Key Lua（预防成熟期 Redis Cluster 报错）。采用单 Key 依次扣减/追加 + 业务补偿；中断恢复依赖业务日志对账补偿。
3. 关键操作（扣费/合成/交易/充值/赠送）追加**业务侧详细日志**——磁盘 append-only 文件，**独立于 Redis 与 MongoDB**，先于返回客户端成功写盘。

### 4.3 异步落盘（独立进程）

- **落盘进程独立**：由**独立进程**（非业务进程）扫描 Redis dirty 集合，异步落地到 MongoDB。落盘进程是 MongoDB 的**唯一写者**，串行化天然防覆盖，故不引入版本号乐观锁。留路：若未来落盘多实例并行，需重新引入版本号或按玩家分片归并。
- **标脏粒度按玩家级**：一个玩家一个 dirty 标记，落盘时主档 + 货币 + 背包一组 `bulkWrite`（主档 1 + 背包 N 文档，单文档各自条件更新，**非事务**）。
- **落盘触发**：独立进程定时（1~3s）扫 dirty；玩家下线/被踢时业务进程通知落盘进程强刷一次，**强刷确认成功后才清 Redis**，失败则重试/告警并保留 Redis 数据，避免"Mongo 旧 + Redis 空 → 冷启动脏读"。
- **上线冷启动**：Redis 无数据则从 Mongo 加载到 Redis（Redis 权威，Mongo 是冷源）。
- **批量落盘**：跨玩家 dirty 合并为 `bulkWrite`，MongoDB Driver 5.9.0 原生支持，避免逐玩家 `updateOne` 的往返开销。

### 4.4 兜底与撤回项

* **Redis 可靠性**：AOF `everysec` + 主从，接受秒级丢失窗口。
* **业务日志**：磁盘 append-only，保留窗口（24~48h）+ 对账，用于 Redis 异常重放兜底与跨玩家操作中断补偿。**日志载体为磁盘文件，不落 Redis、不落 MongoDB**（避免与权威存储同命运）。
* **撤回项**：❌ 不使用 Mongo 多文档事务；❌ Redis 不承担消息/会话职责；❌ 业务日志不写入 Redis/Mongo。

---

## 5. 会话与认证

### 5.1 会话与断连（菜鸟期进程内）

- Netty Channel 进程内维护映射表（`Channel ↔ 玩家ID`）。
- **不做长重线恢复，保留短时挂起（Grace Period）**：
- WS 物理断开时， Session 不立即销毁，标记为 `SUSPENDED` 并保留 15 秒。
- 15 秒内重新握手成功直接恢复会话；超过 15 秒则彻底清除，下次重连重新拉全量状态，规避移动端网络闪断引发的全量数据拉取风暴。


* **留路方式**：抽象 `SessionManager` 接口，菜鸟期进程内实现，成熟期换 Redis/网关实现。

### 5.2 认证与安全

- **登录换 token**：校验通过签发不透明 token 存 Redis（带 TTL）。
- **HTTP 鉴权**：请求头带 token，Spring Filter 校验，通过 ThreadLocal/MDC 传递上下文。
- **WS 鉴权**：建立 WS 时带 `RoomToken` 握手校验，校验通过才允许加入 Channel。
- **防重进**：同账号单点在线，登录成功顶掉旧 token 并踢下线。

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
| 2 | **网络架构** | **HTTP (MVC) + WS (原生 Netty) 混合分流**；玩法连 WS，平时走 HTTP |
| 3 | **数据一致性** | Write-Behind + **独立进程串行落 Mongo（唯一写者，免版本号）** + 业务侧磁盘日志兜底 |
| 4 | **会话机制** | 菜鸟期进程内管理，**15s Session 挂起防护闪断风暴**，`SessionManager` 接口留路 |
| 5 | **跨进程与演进** | 菜鸟期无跨进程，gRPC 契约预留；容量达 3000 时优先拆有状态场景进程 |
| 6 | **非功能配套** | Actuator 监控 + Standard MDC 日志链路 + JFR `jdk.VirtualThreadPinned` 事件监测残留 Pinning |

---

## 8. 后续待定（不在本架构层范围）

- 模块清单与依赖规则（Modular Monolith 模块边界、facade 接口契约）。
- 场景/房间具体模型与路由。
- 各玩法的具体状态机。
- **惊群效应防护**：业务进程崩溃后 3000+ 并发重连 + Redis 冷启动拉全量状态的渐进恢复流程设计。
- **优雅停机流程**：正常停服时 in-flight 请求等待完成、dirty 数据全量刷入 MongoDB、WS 连接有序断开的具体流程。