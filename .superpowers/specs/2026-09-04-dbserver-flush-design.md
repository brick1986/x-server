# game-dbserver 落盘编排设计（Plan C）

> 范围：`game-dbserver` 落盘子系统的对外行为与内部规格——dirty 消费协议、批量策略、失败语义、单实例保证、优雅停机，以及为此需要在 `game-data` 补的原语。本文是 [架构层设计](./2026-07-24-server-architecture-design.md) §4.3「异步落盘（独立进程）」与 [并发正确性修订](./2026-08-04-data-concurrency-fixes-design.md) §2.4/§3.2 的落地规格，落实 [原语设计 §8](./2026-08-11-game-data-primitives-design.md) 列为「不在范围」的落盘编排部分。
>
> 模块边界与依赖规则见 [模块清单与依赖规则设计 §3.5](./2026-08-05-module-breakdown-design.md)——本文沿用其钉定的 `FlushOrchestrator` / `FlushScheduler` / `GracefulShutdown` 三类划分，并给出各自的完整规格。
>
> 不在本次范围：`game-web` 横切（`AuthFilter` / `BizLogger`）——同属 Plan C，另行设计。

## 1. 设计取向

落盘是**纯字节搬运**：`dirty` 集合给出「哪些 key 变了」，Redis 给出「变成了什么」，Mongo 收下这份 JSON。全程不反序列化成 Java 对象、不感知业务实体（架构 §4.1「Redis 与 MongoDB 同构，落盘零转换」；模块 §2.2）。

因此本设计的全部难点不在数据处理，而在**边界正确性**：

- 消费 `dirty` 时不能吞掉并发写入的新标记（§2）
- 积压时不能被单轮数据量拖垮（§3）
- 单个 key 落不进去时不能连坐全服（§4）
- 多实例误起时不能静默丢标记（§5）
- 停机时刷到什么程度算完（§6）

原架构 §4.3 的表述（`SMEMBERS` → `GET` → upsert → `SREM`）在第一条上有真实缺陷，本文 §2 修订之。

## 2. dirty 消费协议：排空到 in-flight 集合

> **2026-09-21 修订**：全局 `{dirty}` / `{dirty}:inflight` 集合改为**按桶分片**的
> `{bNNNN}::dirty` / `{bNNNN}::dirty:inflight`——桶号取自数据 key 的 hash tag 前缀，集合与
> 数据 key 同 slot。§2.1–§2.4 的论证对每个桶原样成立（作用域从全局变单桶）；排空入口从
> 「一次 drain」变为「每轮无条件排空全部 4096 个桶」。协议细节以
> [提交桶化设计](./2026-09-21-commit-clusterslot-design.md) §5 为准。

### 2.1 被修订的问题：`SREM` 的丢标记窗口

架构 §4.3 原文是「落盘成功后 `SREM`」。该协议存在会**永久丢数据**的窗口：

```
落盘进程                          game-web
SMEMBERS dirty → {player:1:bag}
GET player:1:bag → v1
                                  持锁 Lua: SET v2; SADD dirty player:1:bag
bulkUpsert(v1)
SREM player:1:bag                 ← 把 v2 的脏标记一并删掉
```

结果：Redis 是 v2、Mongo 是 v1、`dirty` 为空。**v2 永不下沉**，直到该 key 下次被业务写入；期间 Redis 崩溃（超出 AOF `everysec` 秒级窗口）则 v2 永久丢失。

这与架构 §4.4 接受的「AOF 秒级丢失窗口」性质不同：后者丢的是最后 1s 内的写，前者丢的是**已提交、已标脏、却被落盘进程亲手取消标记**的值，且无任何机制能发现。1~3s 一轮 × 热点 key，撞上是常态而非极端情况。

### 2.2 新协议

`dirty` 的消费改为**原子排空到私有 in-flight 集合**：

```
Lua（一次往返，KEYS[1]={dirty}, KEYS[2]={dirty}:inflight）：
  if EXISTS inflight:                          -- 上轮未跑完（中断/崩溃）
      SUNIONSTORE dirty dirty inflight         -- 残留合回 dirty
      DEL inflight
  if not EXISTS dirty: return {}               -- RENAME 空 key 会报错，须先判
  RENAME dirty inflight                        -- 原子：dirty 立刻变空集
  return SMEMBERS inflight
```

两个 key 名**自带 hash tag**（`{dirty}` / `{dirty}:inflight`），理由见 §2.5。

排空后 `dirty` 立即是空集，落盘期间 `game-web` 的 `SADD dirty` 进的是**新一轮**的 `dirty`，与本轮 in-flight 快照物理隔离——§2.1 的窗口彻底消除。

**`GET` 到的值可能比排空瞬间更新**（排空后该 key 又被写了一次）：无害。`upsert` 是幂等的整值覆盖，落进去的是更新的版本；而该 key 已在新 `dirty` 里，下一轮会再落一次。落盘只保证「最终收敛到 Redis 当前值」，不保证「落的是排空瞬间的值」——这与架构 §4.2.4「Redis 里该 key 的任意瞬间态都是某个完整 v2」一致。

### 2.3 in-flight 的语义：尚未落盘的 key

每处理完一片（§3），立即把该片从 `inflight` 移除。因此 `inflight` 的含义始终是「**本轮已排空但尚未落盘的 key**」。这使中断与崩溃路径**无需任何专门处理**：

| 情形 | 处理 |
| --- | --- |
| 正常跑完 | `inflight` 自然为空（Redis 空 set 自动消失），无需显式 `DEL` |
| 失锁中断 / 异常中断 | 剩余 key 留在 `inflight`，直接返回；下一轮 §2.2 的恢复步骤合回 `dirty` |
| 进程硬崩 | 同上，由下次启动的第一轮 `drain` 合回 |

**恢复路径唯一**（就是 §2.2 Lua 的头三行），且每轮自愈——不需要 `ApplicationRunner` 式的启动恢复分支，也不会出现「只有重启才修」的死角。

**不提供「清空 inflight」的 API**：若 `inflight` 非空却被 `DEL`，那正是 §2.1 的丢标记。让它留着、由下轮合回，是唯一安全的处置。

### 2.4 顺序约束（强制）

一片处理完时，两个动作的顺序是**正确性要求**，不是风格：

```
1. markAll(失败的 key)      -- SADD 回 dirty
2. ackInflight(整片 key)    -- SREM 出 inflight
```

反序则「`SREM` 后、`SADD` 前」崩溃会让失败 key 既不在 `inflight` 也不在 `dirty`，静默丢标记。按上述顺序，两步之间崩溃只会导致整片被下轮重放（`upsert` 幂等，无害）。

### 2.5 key 名自带 hash tag

`dirty` 与 `dirty:inflight` 在同一段 Lua 里操作，Redis Cluster 要求同一 Lua 的所有 KEYS 落在同一 slot，否则报 `CROSSSLOT`。故**现在就**把两个集合命名为：

```
DIRTY_SET    = "{dirty}"
INFLIGHT_SET = "{dirty}:inflight"
```

Cluster 的 hash tag 规则是「取第一个 `{` 与随后 `}` 之间的子串算 slot」，故二者 tag 同为 `dirty`、必然同 slot。单机模式下 `{dirty}` 只是一个含花括号的普通 key 名，行为与 `dirty` 无差别。

**为什么不留到将来改**：改动成本恒定（`DirtyLedger` 两个常量，`CommitLua` 引用同一常量自动跟随），但**遗漏成本随时间上升**——真上 Cluster 时这是一个只在运行时暴露、且只在落盘路径暴露的 `CROSSSLOT` 错误。现在改是一行，将来改是一次线上事故加一行。这不违反架构 §1「代码留路、运行时从简」：它没有引入任何运行时复杂度，只是把名字取对。

**迁移**：本地开发/测试环境里已存在的旧 `dirty` 集合改名后会成为孤儿（不再被消费）。集成测试本就 `flushdb`，不受影响；手工环境需 `DEL dirty` 清理一次。`game-dbserver` 从未运行过，**不存在生产数据迁移问题**。

#### 2.5.1 提交侧的 CROSSSLOT（已由提交桶化设计了结）

hash tag 只消除了**落盘侧**（`{dirty}` ↔ `{dirty}:inflight`）的 slot 障碍。**提交侧仍有一个 hash tag 解决不了的**：`CommitLua` 的 KEYS 是 `(数据 key, dirty 集合)`，即 `player:123:profile` 与 `{dirty}` —— 二者在 Cluster 下必然不同 slot。

它不能用 hash tag 修：给所有数据 key 加同一个 tag 会把全部数据挤进一个 slot，Cluster 就失去意义了。真正的解法是**按 slot 分片的多个 dirty 集合**（如 `{player:123}:dirty`，让脏标记与它标记的数据同 slot；落盘侧改为遍历分片），那会同时改动提交路径与落盘路径的协议。

注意这与架构 §4.2「不做跨 Key Lua（预防成熟期 Redis Cluster 报错）」并不矛盾：那条针对的是**跨业务实体**的写（如转账同时改 A、B），而 `CommitLua` 的第二个 key 是基础设施用的脏标记集合，不是第二个业务实体。但从 CROSSSLOT 的角度，两者确实一样会报错——**架构 §4.2 的那句「预防 Cluster 报错」在 `CommitLua` 上并未实现兑现**。

菜鸟期单机，不构成任何现实问题；此处仅记录该障碍的存在与形状，供成熟期上 Cluster 时评估。**本文不设计它**：它属于提交路径（`game-data` Plan B 已实现的部分），改动面远超落盘编排的范围。

2026-09-21 起已由 [提交桶化设计](./2026-09-21-commit-clusterslot-design.md) 落地解决。解法形状以提交桶化设计 §2.3/§3 为准——固定 4096 桶，非按实例 tag。

## 3. 批量策略：全量快照 + 分片流水线

一次 `drain` 拿的是**全量**快照。3000 在线、2s 一轮的常态下脏 key 在数百~数千量级，但冷启动后首轮、或某轮被 Mongo 慢写卡住导致积压时，可能上万。两处会退化：

- 逐个 `RedisStore.get()` 是同步往返，1 万 key = 1 万次 round-trip
- 1 万条 `Document` 同时在堆里等一次 `bulkWrite`

故按固定分片（默认 500 key/片）**流水线**处理，内存与单次往返量只与分片大小相关、与总量无关：

```java
// 2026-09-21 桶化后为两段流水线（骨架；结构与语义以提交桶化设计 §5.3 为准，粒度从「片」改为「批」）：
Map<Integer, Set<String>> drained = dirtyLedger.drainAll();  // 第一段：4096 桶无条件排空（异步扇出，空桶 no-op）
if (drained 成员总数 == 0) return 0;
Batch batch;                                                 // 第二段：逐桶 MGET（同桶同 slot），
for (var e : drained.entrySet())                             //   值跨桶聚成 500/批的 Mongo 批
    for (List<String> mchunk : chunks(e.getValue(), chunkSize)) {
        batch.add(mchunk, redisStore.mget(mchunk));          // 一次往返；返回即还 Redis 连接
        while (batch.isFull()) {
            flushBatch(batch);                                // bulkUpsert（unordered，§4）→ markAll(失败)
                                                              // → ackInflight(整批)：§2.4 顺序、按桶分组
            if (!stillHoldsLock(lock)) return INTERRUPTED;   // §5——每个整批之后检查
        }
    }
flushBatch(batch 余量);                                      // 末批刻意不查锁：轮到此为止、其后无写
```

### 3.1 `flushOnce()` 的返回值契约

停机循环（§6）要靠返回值区分「真刷干净了」与「这轮没干成事」，故三种结局必须可分辨——三者都返回 0 会让停机循环把「抢不到锁」误判为「已刷空」而提前退出：

| 结局 | 返回 | 停机循环（§6）的反应 |
| --- | --- | --- |
| 排空后无 key | `0` | `break`——确实刷干净了 |
| 落盘 n 个 key（含部分失败） | `n > 0` | 继续下一轮 |
| 抢不到 Redis 锁 / 失锁中断 | `-1` | 继续重试直到超时；不当作刷完 |

「抢不到锁」在停机路径下继续重试到超时是对的：另一实例正在刷同一份 `dirty`，超时后本进程放行退出，残留由对方或下次启动落完（§2.3）。

**禁嵌套跨池天然成立**（并发修订 §3.2）：`mget` 返回时 Redis 连接已归还，之后才触达 Mongo；一个虚拟线程任一时刻只持有一个池的连接。

**`GET` 回 nil 的 key**（key 已被删/过期而标记尚存）：跳过 upsert，且**不回 `dirty`**——回了就是永久重试的死循环。记 WARN + 计数即可。Redisson 的批量取值对不存在的 key 不放入返回 Map，故 `chunk.size() - vals.size()` 即本片的 nil 数量。

## 4. 失败语义：unordered + 失败 key 精确回 dirty

`MongoStore.bulkUpsert` 现为默认 **ordered** 语义：首条出错即停止，后续 model 一条不执行，异常也不指明哪些成功。分片流水线下这会变成「第 5 片挂了 → 前 4 片已落、第 5 片状态不明、后续片没跑」。

落盘的每个 key 相互独立，**改 `ordered(false)` 是纯收益**：全部尝试、错误列表完整、`MongoBulkWriteException.getWriteErrors()` 的 `index` 可精确映射回具体 key。

两类异常分别处理：

| 异常 | 含义 | 处置 |
| --- | --- | --- |
| `MongoBulkWriteException` | 部分 model 写失败（如文档超 16MB） | 从 `getWriteErrors()` 的 index 反查失败 key，仅这些 `markAll` 回 `dirty`；WARN 记 key 名。**其余 key 视为已落** |
| 其它 `MongoException` | 连接级故障（Mongo 不可用） | 整片视为失败，不 `ack`；直接抛出中断本轮。剩余 key 留在 `inflight`，下轮恢复（§2.3） |

**index 的语义须注意**：`BulkWriteError.getIndex()` 是「该次 `bulkWrite` 调用内 models 列表的下标」。`bulkUpsert` 按 collection 分组、每组各跑一次 `bulkWrite`（现有实现如此），故每组必须保留一份**与 models 同序**的 key 列表才能反查。

**毒丸 key 不做隔离**。文档超 16MB（架构 §4.1 自己留了这个伏笔）会每轮失败、每轮 WARN，但**不阻塞其他 key 下沉**。不引入 dead-letter 集合与失败计数状态：那要维护「key → 连续失败次数」（放 Redis 是新数据结构，放进程内存则重启即丢），是菜鸟期可能永远用不上的状态机。按架构 §4.4「不自动恢复、人工对账」原则，告警 + `dirty` 积压指标（§7）足够让人介入。

## 5. 单实例保证：每轮竞争分布式锁

§2.2 的 `RENAME` 是覆盖语义，这使**多实例并发是新协议下的丢数据操作**（原 `SMEMBERS`+`SREM` 协议下只是重复劳动）：

```
实例A: RENAME dirty→inflight     inflight={k1..k100}，开始落盘
实例B:                           RENAME dirty→inflight   ← 覆盖，A 的 100 个标记蒸发
实例A: 落完 → SREM 掉自己那批    ← 连 B 的 10 个也被清掉
```

架构 §4.3 只在文字上声明「落盘进程是 Mongo 唯一写者」，无任何机制保证。故在代码层强制：

- 每轮 `flushOnce` 开始前 `tryLock("lock:dbserver:flush", 0, 60s)`，**拿不到即跳过本轮**（DEBUG 日志，不报错——这是热备实例的正常状态，不是故障）
- 固定租约 60s、**不用看门狗**（与并发修订 §4.1 全项目纪律一致）
- **每处理完一个整批检查 `isHeldByCurrentThread()`**，失锁立即中断本轮（剩余 key 留在 `inflight`，下轮恢复）。这与 `LockCtx.put` 的 `isHeld` 提交门控完全同构（并发修订 §4.2）

**顺带白赚一个能力**：第二个实例可作**热备**空转待命，主实例进程硬崩后 60s 内自动接管落盘。这是分布式锁方案相对「启动期独占标记」的额外好处——后者会让第二实例直接启动失败而非待命。

锁名 `lock:dbserver:flush` 不走 `DataKeys.lockKey()`——那是 `lock:{entity}:{id}` 的实体锁命名，本锁是进程级互斥锁，不是实体锁，刻意不复用以免混淆两种语义。

> 备案：固定租约 + 每片 `isHeld` 门控留有一个固有的（无 fencing token 的）跨实例写重叠微窗口——
> 单轮超过 60s 租约且热备接管后，被夺锁实例的在途 Mongo 写若晚于接管者的写落地，会让 Mongo
> 短暂回退到旧值（Redis 始终是权威；真要暴露还须叠加上线冷启动与 AOF 丢失窗口）。这与
> `LockCtx.put` 的 `isHeld` 门控同构，沿用并发修订 §4.1 的全项目固定租约纪律；成熟期按
> 并发修订 §4.3 的 token 门控升级路径评估。

## 6. 优雅停机：循环刷到空 + 硬超时

架构 §4.3 只有一句「正常停服时 dirty 数据全量刷入 MongoDB」。**「全量」在 dbserver 单独停机时不是可达状态**：`game-web` 仍在运行并持续 `SADD dirty`，刷空一次，下一毫秒又有新的。故真实语义只能是「尽力刷 + 有界超时」：

```java
stop():
    running = false                     // 调度器不再起新轮
    acquire 进程内 flushLock             // 等当前轮跑完（不是跳过它）
    deadline = now + shutdownTimeout    // 默认 20s
    while (now < deadline):
        if (flushOnce() == 0) break     // 排空后无 key，刷干净了
    if (dirty 非空):
        log.error("停机残留 {} 个 dirty key，将在下次启动后落盘", n)
    // 放行退出
```

- **残留不丢**：超时未刷完的 key 仍在 `dirty`（或 `inflight`，下轮合回），下次启动自然接着落。停机超时的后果是「数据暂时只在 Redis」，不是丢失。
- **超时上界 20s**：留在 Spring 默认 30s 关闭窗口内，给容器留出余量。
- **用 `SmartLifecycle` 而非 `@PreDestroy`**：Spring 关闭时先按 phase 降序执行 `Lifecycle.stop()`，之后才销毁 singleton bean。`@PreDestroy` 相对其他 bean 的销毁顺序不够可靠——`RedissonClient`（`destroyMethod = "shutdown"`）或 `MongoClient`（`destroyMethod = "close"`）可能已被关掉，刷盘就无连接可用。取 `phase = Integer.MAX_VALUE` 确保最先 `stop()`。

### 6.1 进程内串行

`@Scheduled` 触发的轮次与 `GracefulShutdown` 的循环刷必须串行，且停机时**不能靠「拿不到 Redis 锁就跳过」**（那就刷不成了）。故两者共用一把**进程内** `ReentrantLock`：调度轮 `tryLock` 拿不到即跳过；`stop()` 用阻塞 `lock()` 真等当前轮结束。

于是形成两层清晰的互斥：**进程内** `ReentrantLock`（本进程的轮次不重叠）+ **跨进程** Redisson 锁（多实例不重叠）。

Spring 的 `@Scheduled` 默认调度池 size=1 且 `fixedDelay` 语义已不重叠，`ReentrantLock` 是为停机路径而设，不依赖调度池配置。

## 7. 组件划分与配置

```
io.github.brick.dbserver
  flush/   FlushOrchestrator    一轮落盘的完整编排：Redis 锁 → drain → 分片流水线 → 失败回写。无状态
           FlushScheduler       @Scheduled(fixedDelay) 触发 + running 开关 + 进程内 ReentrantLock
           GracefulShutdown     SmartLifecycle：停调度 → 等当前轮 → 循环刷到空 → 硬超时
  config/  DbServerProperties   game.dbserver.* 配置
           DbServerConfiguration
```

三个类各持一个职责：**编排**、**触发**、**收尾**。`GracefulShutdown` 只是以另一种节奏反复调 `FlushOrchestrator.flushOnce()`，不复制任何落盘逻辑。

配置放 `game-dbserver` 自己的 `DbServerProperties`（`game.dbserver.*`），**不塞进 `game-data` 的 `DataProperties`**——落盘节奏是进程策略，`game-data` 是被两个进程共用的库模块。

```yaml
game.dbserver:
  flush-interval-millis:      ${FLUSH_INTERVAL_MILLIS:2000}    # 架构 §4.3 的 1~3s
  chunk-size:                 ${FLUSH_CHUNK_SIZE:500}
  lock-lease-seconds:         ${FLUSH_LOCK_LEASE:60}
  shutdown-timeout-seconds:   ${FLUSH_SHUTDOWN_TIMEOUT:20}
```

与 `DataProperties` 同规格：`@Validated` + `@Min`/`@Max` 在启动期强制范围，越界值使上下文启动失败而非留到线上暴露。

### 7.1 可观测

Actuator + Micrometer 指标（架构 §6 已钉定 Actuator，`game-dbserver` 的 pom 尚未引入，需补）：

| 指标 | 类型 | 用途 |
| --- | --- | --- |
| 单轮耗时 | timer | 是否逼近 flush 周期 |
| 落盘 key 数 | counter | 吞吐 |
| 失败 key 数 | counter | 毒丸告警（§4） |
| nil key 数 | counter | key 被删而标记残留（§3） |
| `dirty` 积压量 | **gauge** | **最该看的告警项**——持续增长说明落盘跟不上写入 |
| 非空桶数（`dbserver.dirty.buckets`） | **gauge** | 桶化后最先看的健康信号，比积压量粗一级；对全部 4096 桶的独立 SCARD 扫描（2026-09-21 随桶化落地） |
| 跳过轮次数 | counter | 抢不到 Redis 锁（热备正常，主实例异常） |

沿用架构 §6 的 Logback + MDC 链路日志（模块 §3.5：dbserver 自带、不依赖 `game-web`）。

> 状态注记：上表前六项指标已随本计划落地，非空桶数一行随 2026-09-21 提交桶化落地；Logback + MDC
> 链路日志**未随本计划交付**——其 MDC 填充源（userId 等）在 `game-web` 横切（Plan C 的余下部分），
> 将随那部分工作一并落地。

## 8. `game-data` 的改动

> **2026-09-21 修订**：随提交桶化（[提交桶化设计](./2026-09-21-commit-clusterslot-design.md) §3–§5），
> `DataKeys` / `CommitLua` / `DirtyLedger` 按桶协议改写；`RedisStore` / `MongoStore` 本次未再改动。

五个类，不越 `game-data` 边界（仍不认识任何业务实体）：`DataKeys` / `CommitLua` 为提交与落盘共用（提交桶化设计 §3–§4），`DirtyLedger` / `RedisStore` / `MongoStore` 在原语设计 §8 明说的「落盘进程消费」职责内。

**`DataKeys`** — key 文法桶化：`key()` 产出 `{bNNNN}:{entity}:{id}:{field}`，桶号 = `hash(entity:id)` 取模 `BUCKETS = 4096`（协议常量，改 K = 全量数据重写）；新增 `bucketOf(key)` 供 `CommitLua` / `DirtyLedger` 从 key 推导桶；`parts()` 剥前缀后按原规则校验，**旧格式 key（无桶前缀）直接拒绝**——迁移依赖此行为。

**`CommitLua`** — SCRIPT 一字不改，KEYS[2] 从 `DIRTY_SET` 常量改为 `DirtyLedger.dirtySetOf(key)` 按数据 key 推导同桶脏集合（与数据 key 同 slot，Cluster 合法）；对外签名与全部调用方零改动。

**`DirtyLedger`** — 桶化重写（原「单集合 + 新增 3 个方法」的 Plan C 形态就此终结）：

| 方法 | 实现 | 语义 |
| --- | --- | --- |
| `drainAll()` → `Map<Integer, Set<String>>` | 4096 桶 `evalAsync` 异步扇出 DRAIN_SCRIPT、聚合等待 | 每轮排空入口：合回残留 + 原子排空 + 返回桶号→快照（只含非空桶） |
| `drainToInflight(int bucket)` | 单桶同步 EVAL | 测试/诊断用；生产轮次走 `drainAll()` |
| `dirtySetOf(int)` / `dirtySetOf(String)` / `inflightSetOf(int)`（静态） | 格式化 `{bNNNN}::dirty` / `{bNNNN}::dirty:inflight`；String 重载经 `bucketOf(key)` 推导 | 集合名唯一来源，`CommitLua` 引用自动跟随 |
| `markAll(Collection<String>)` | 按 key 推导桶、分组 `SADD` 回各自 dirty | 失败 key 回写（§2.4 第 1 步） |
| `ackInflight(Collection<String>)` | 按桶分组 `SREM` 出各自 inflight | 一批落盘完成（§2.4 第 2 步） |
| `backlogSize()` / `dirtyBucketCount()` | 4096 桶 SCARD 扇出聚合 | 积压量 / 非空桶数——§7.1 两个 gauge，各自独立扫描 |
| `inflightMembers()` | 4096 桶 SMEMBERS 聚合 | 仅停机日志用 |

**`RedisStore`** — `mget(List<String>)` → `Map<String,String>`：用 `client.getBuckets(StringCodec.INSTANCE)` 一次往返取多 key。**必须与现有 `get`/`set` 保持同一个 `StringCodec`**，否则读到的是带引号的 JSON（现有类注释已记录该坑）。桶化后由编排保证一次 `mget` 的成员同桶同 slot（§3 第二段），Cluster 下单条命令合法。

**`MongoStore`** — `bulkUpsert` 签名 `void` → `Set<String>`（失败 key 集合）：加 `ordered(false)`；捕 `MongoBulkWriteException`，按 §4 的 index 语义反查 key；其它 `MongoException` 原样抛出。桶化不触及它——跨桶聚合的批按 collection 分组照旧。

> `bulkUpsert` 返回值变更曾波及现有调用方与测试——落地时已按 blast radius 同步更新。

## 9. 测试

**单元测试**（无外部依赖）：分片切分边界（空集 / 恰好整片 / 余数片）、失败 index→key 映射（含多 collection 分组）、nil 计数。

**集成测试**（沿用 `game-data` 现有的本地预起 Redis/Mongo 约定，见 `DEVELOPMENT.md`；不引 Testcontainers）：

1. **排空原子性**——排空期间并发 `SADD`，验证新标记进新 `dirty` 且不被本轮吞掉（§2.1 的回归测试）
2. **残留恢复**——手工造 `inflight` 残留 → 下一轮 `drain` 合回并落盘
3. **失败 key 精确回写**——造一个超 16MB 文档，验证只有它回 `dirty`、同片其余全部落成功（§4）
4. **双实例互斥**——并发跑两个 `flushOnce()`，验证一个跳过、且**零标记丢失**（§5 的回归测试）
5. **失锁中断**——落盘中途强制 `unlock`，验证本轮中断、剩余 key 留在 `inflight`、下轮落完
6. **nil key**——`dirty` 里塞一个 Redis 不存在的 key，验证跳过、不写 Mongo、**不回 `dirty`**（回了即死循环）
7. **停机收敛**——持续写入下调 `stop()`，验证 20s 内收敛；超时场景验证残留仍在 `dirty` 而非丢失
8. **零转换**——落盘后 Mongo 文档的 `v` 字段与 Redis 里的 JSON 字符串**逐字节相同**（架构 §4.1）

## 10. 运维约定（需同步进 `DEVELOPMENT.md`）

- **停服顺序：先停 `game-web`，再停 `game-dbserver`。** 否则停机刷盘刷的是一个持续注水的池子，§6 的收敛条件依赖此顺序才有意义。
- **`game-dbserver` 允许起多个实例**（多出的作热备空转），正确性由 §5 的分布式锁保证，**不依赖部署纪律**——这符合架构 §2 放弃 sticky 路由时立下的原则：写正确性不押在运维正确配置上。

## 11. 不在本次范围

- `game-web` 横切（`AuthFilter` / `BizLogger`）——同属 Plan C，另行设计。
- **上线冷启动**（架构 §4.3「Redis 无数据则从 Mongo 加载」）：该路径由 `LockCtx.get` 的 miss 锁内加载承担（原语设计 §2.2），不是落盘进程的职责，本文不涉及。
- **惊群防护**（架构 §8 待定项）：崩溃后 3000+ 并发重连 + Redis 冷启动拉全量的渐进恢复，独立议题。
- **毒丸 key 的自动隔离**（dead-letter）：§4 已论证菜鸟期不做。若日后 16MB 溢出成为常态，届时按架构 §4.1 的留路「背包拆为每物品一文档」从源头解决，而非在落盘侧堆状态机。
