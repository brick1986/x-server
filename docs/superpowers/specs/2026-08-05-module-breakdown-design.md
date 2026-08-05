# x-server 模块清单与依赖规则设计

> 范围：菜鸟期 **基础设施模块** 的划分、职责、依赖规则与划线理由。本文档为 [架构层设计](./2026-07-24-server-architecture-design.md) 的延伸，落实其 §8 待定项「模块清单与依赖规则」中的基础设施部分；业务域模块（player/bag/equipment/shop 等）的内部划分留待后续单独讨论，不在本文件内展开。

## 1. 划分总览

菜鸟期采用 **A 粗粒度** 划法：按「部署进程 + 一个基础设施桶」组织模块，模块少、依赖简单、迭代快。共 5 个 Maven 模块：

| 模块 | artifactId | 职责 | 部署形态 |
| --- | --- | --- | --- |
| 根 POM | `game-parent` | BOM 钉版（Netty 等）、公共依赖管理 | 不产出制品 |
| 契约 | `game-contract` | proto wire 消息 + gRPC 契约（预留，不部署） | 不部署 |
| 数据原语 | `game-data` | Redis/Mongo 封装 + 连接池 + 锁 + 覆盖写模板 + JSON 编解码 | 被业务进程与落盘进程依赖 |
| 业务进程 | `game-web` | HTTP 业务进程：业务域 + proto↔domain 映射 + 鉴权 + 业务日志 | 部署进程 ① |
| 落盘进程 | `game-dbserver` | 落盘编排 + 定时调度 + 优雅停机刷盘 | 部署进程 ② |

### 依赖关系图

```
game-parent          根 POM，BOM 钉版
game-contract        proto wire 消息 + gRPC 契约（预留）
game-data            Redis/Mongo 封装 + 连接池 + 锁 + 覆盖写模板 + JsonCodec
                     LockManager + CommitLua + DirtyLedger + OverlayWriter
game-web             → game-data + game-contract（内含鉴权 AuthFilter + 业务日志 BizLogger）
game-dbserver        → game-data（仅此一项）
```

依赖方向严格单向，禁止环依赖：

- `game-web` → `game-data` + `game-contract`
- `game-dbserver` → `game-data`（**唯一依赖**）
- `game-data`、`game-contract` 不依赖任何业务模块

---

## 2. 为什么这么划分

### 2.1 按「进程」而非「技术层」切顶层模块

架构 spec §2 钉定菜鸟期为 **2 进程**：无状态 HTTP 业务进程 + 落盘进程（DBServer）。两个进程各自独立打包部署，故把 `game-web` 与 `game-dbserver` 各立为顶层模块——进程边界即模块边界，部署单元与代码单元一致，便于单独打包、灰度、停机。

### 2.2 把「数据原语」独立成 `game-data`，让 dbServer 依赖最瘦

这是本次划分的关键判断。落盘进程 dbServer 的依赖关系直接决定了这条线：

- dbServer 落盘的完整动作是 `SMEMBERS dirty` → `GET Redis JSON 字符串` → **upsert Mongo 文档**。架构 spec §4.1 钉定「Redis 与 MongoDB 同构，落盘零转换」——从 Redis 拿到 JSON、原样 upsert 进 Mongo，**纯字节搬运，不反序列化成 Java 对象**。
- 因此 dbServer **既不碰 proto 消息，也不碰 domain POJO**，更不需要鉴权与业务日志。

把数据原语（Redis/Mongo 封装、锁、覆盖写模板）独立成 `game-data` 后，dbServer 的依赖缩到最瘦：**只依赖 `game-data` 一个模块**。

### 2.3 鉴权与业务日志放 `game-web`，不单独立 `game-infra`

鉴权 `AuthFilter` 与业务日志 `BizLogger` 都只有 web 进程消费（dbServer 不需要：不接外部请求故不鉴权；落盘是 Redis→Mongo 字节搬运，不写业务对账日志）。既然没有第二个消费者，硬拆一个独立的 `game-infra` 模块只会多一层间接而无收益。故两者直接并入 `game-web`，作为 web 进程内部横切包（`web/auth`、`web/log`）。`AuthFilter` 依赖 `game-data` 的 `RedisStore` 读 token，本就在 `game-web` 的依赖路径上，不增加新依赖。

> 留路：若未来 realtime WS 进程需要复用鉴权，届时再把 `AuthFilter` 抽到独立 `game-infra` 模块。菜鸟期不预埋。

### 2.4 dbServer 不依赖 `game-contract`

落盘是 JSON 字节搬运，不感知 wire 协议。`game-contract`（proto 消息 + gRPC 契约）只被业务进程消费（HTTP Body Protobuf 序列化）。dbServer 与 `game-contract` 解耦，意味着 wire 协议演进不影响落盘进程。

### 2.5 `game-data` 只放「POJO 无关」的数据原语 + 覆盖写模板

覆盖写编排（架构 spec §4.2.1：锁内读 → Java 改 → 提交 Lua + isHeld 门控 + miss 锁内加载）是所有业务域共用的「如何做一次正确覆盖写」设施，但其中的「改」一步是**业务逻辑**（扣道具、加货币），与具体实体 POJO 强耦合。

因此 `game-data` 里的 `OverlayWriter` 只能是**类型参数化的模板/泛型工具**：提供锁 + `GET` + `CommitLua` + `DirtyLedger` 原语，外加一个 `<T> T get(key, Class<T>)` → 业务回调 `mutate` → 序列化回写的模板方法。业务域把自家 POJO 喂进去。

`game-data` **不认识任何业务实体**（不持有 `PlayerProfile`/`Bag` 等类）。domain POJO 集中托管还是分散到业务域，是业务域阶段的工程取向选择，本次不锁死。

### 2.6 `game-contract` 仅放 wire 侧 proto，不放 domain POJO

存储形状（Redis JSON / Mongo BSON）与线协议（HTTP Body Protobuf）是两种关注点。`game-contract` 只放 proto 生成类 + gRPC 契约；domain POJO（存储用）由业务层定义，`game-web` 负责 proto↔domain 映射。这样 dbServer 落盘用的是存储 POJO（或直接 JSON 字符串），根本不碰 proto，不必依赖 `game-contract`。

---

## 3. 各模块内部结构

### 3.1 `game-parent`（根 POM）

- Netty BOM 钉版 `4.1.136.Final`（架构 spec §3.1：统一 BOM，禁止子模块各自声明 Netty 版本）。
- 公共依赖版本管理（Spring Boot 4.1.0 / Redisson 4.6.1 / MongoDB Driver 5.9.0 / Protobuf 4.34.1）。
- `<packaging>pom</packaging>`，不产出制品。

### 3.2 `game-contract`

- `.proto` 源文件 + protobuf-maven-plugin 生成的 Java 消息类。
- gRPC service 定义（菜鸟期仅契约，不部署）。
- **不放** domain POJO，不放业务逻辑。

### 3.3 `game-data`（数据原语 + 编排模板）

```
io.github.brick.data
  store/     RedisStore        GET/SET/DEL JSON 字符串（连接池 100~200）
             MongoStore        upsert/bulkWrite JSON 文档（连接池 50~100）
             DataKeys          key 命名常量 + entity→锁名映射
  lock/      LockManager       lock:{entity}:{id}, leaseTime=10s 无看门狗,
                                isHeld 门控, 跨实体顺序(guild>player / ID序)
  overlay/   OverlayWriter<T>  泛型覆盖写模板(锁内 get→mutate→commit)
             CommitLua         SET+SADD 原子脚本
             DirtyLedger       SADD/SMEMBERS/SREM
  codec/     JsonCodec         POJO↔JSON 序列化工具（不持有任何实体类）
```

- **连接池显式设限**：Redis 100~200、Mongo 50~100（架构 spec §3.1，禁止无上限）。
- **禁嵌套跨池获取**：一个虚拟线程绝不同时持有两个连接池的连接（架构 spec §3.1）。
- `OverlayWriter<T>` 是 POJO 无关的模板，业务域传入 `Class<T>` 与 mutate 回调。
- `CommitLua` 仅做 `SET key newjson; SADD dirty key`，不解析 JSON（架构 spec §4.2.1）。

### 3.4 `game-web`（业务进程）

```
io.github.brick.web
  auth/      AuthFilter        token Redis TTL 校验, ThreadLocal/MDC 注入 userId
  log/       BizLogger         磁盘 append-only 业务日志（增量 + before/after 绝对值）
  domain/    业务域子包（player/bag/equipment/shop/currency 等，本次不展开）
  codec/     proto↔domain 映射层
```

- HTTP Controller + Spring MVC + Tomcat 虚拟线程。
- `AuthFilter` 依赖 `game-data` 的 `RedisStore` 读 token。
- `BizLogger` 独立于 Redis/Mongo（纯磁盘，架构 spec §4.4），日志载体不落 Redis/Mongo。
- 依赖 `game-data` + `game-contract`。

### 3.5 `game-dbserver`（落盘进程）

```
io.github.brick.dbserver
  flush/     FlushOrchestrator  SMEMBERS dirty → GET JSON → bulkWrite upsert → SREM
             FlushScheduler     @Scheduled（1~3s 触发）
             GracefulShutdown   停机时 dirty 全量刷入 MongoDB
```

- **唯一依赖 `game-data`**：通过 `RedisStore` 拿 JSON、`MongoStore` upsert、`DirtyLedger` 扫 dirty 集合组装落盘流程。
- 落盘是 Mongo 的**唯一写者**，串行化天然防覆盖，不引入版本号（架构 spec §4.3）。
- JSON 原样 upsert，零转换，不反序列化成 Java 对象。
- 优雅停机流程：正常停服时 dirty 数据全量刷入 MongoDB（架构 spec §4.3）。
- 自带运行时链路日志（Logback + MDC），不依赖 `game-web`。

---

## 4. 依赖规则（强制）

1. **严格单向、禁止环依赖。** 依赖方向见 §1 图。
2. **dbServer 依赖最瘦**：仅 `game-data`，不依赖 `game-contract` / `game-web`。
3. **`game-data` 不认识业务实体**：只提供原语与 POJO 无关的覆盖写模板，不持有 domain POJO。
4. **鉴权与业务日志归 `game-web`**：不进 `game-data`，避免污染落盘进程依赖；二者仅 web 进程消费。
5. **Netty 版本统一 BOM 钉版于根 POM**，子模块禁止各自声明（架构 spec §3.1）。
6. **运行时日志每进程自带**：`game-web` 的 `BizLogger` 是业务对账日志；`game-dbserver` 自带 Logback + MDC 链路日志。两者互不依赖。

---

## 5. 留路说明（不在本次范围）

- **facade / `game-api` 模块**：架构 spec §8 提及「facade 接口契约」待定。facade 是**业务模块间解耦**的边界层，与 `game-data`（基础设施层、对下到存储）正交，非替代关系。菜鸟期业务模块间交叉调用尚不明确，**先不立独立 `game-api` 模块**，业务域内部用「同模块内分 api/impl 包」实现接口留路；待真正出现跨模块调用且边界模糊时，再把接口抽到独立 `game-api` 模块。本条留待业务域阶段最终决定。
- **`game-infra` 模块**：鉴权与业务日志目前只有 web 进程消费，已并入 `game-web`，菜鸟期不立独立 `game-infra`。若未来 realtime WS 进程需要复用鉴权/日志横切设施，届时再抽出 `game-infra` 作为共享横切模块。
- **业务域模块内部划分**（player/bag/equipment/shop 各自的子结构、domain POJO 集中 vs 分散）：留待后续单独讨论。
