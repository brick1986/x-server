# 提交协议桶化设计（Cluster CROSSSLOT 了结）

> 范围：`game-data` 提交路径（`CommitLua`）与 `game-dbserver` 落盘路径（`DirtyLedger` / `FlushOrchestrator`）的协议改造——把脏标记从全局 `{dirty}` 集合改为与数据 key 同 slot 的**分桶集合**，使提交侧 Lua 在 Redis Cluster 下合法。本文了结 [落盘 spec §2.5.1](./2026-09-04-dbserver-flush-design.md) 备案搁置的「提交侧 CROSSSLOT」。
>
> **本次只改协议，不改运行时**：`DataAutoConfiguration` 仍 `useSingleServer()`，不含 Cluster 连接配置与真实集群环境验证。协议本身 Cluster-ready；运行时支持（`ClusterServersConfig`、地址列表、真实集群 IT）是将来独立的工作，届时协议无需再动。
>
> 方案否决过程（无 Lua 的排序纪律推演）与 K 值推导已随讨论定稿，本文记录结论与依据，供实现与将来维护引用。

## 1. 问题

`CommitLua` 的 KEYS 是 `(数据 key, {dirty})`：`player:123:profile` 与 `{dirty}` 在 Cluster 下必然不同 slot，EVAL 直接报 `CROSSSLOT`。落盘 spec §2.5.1 已论证：不能给所有数据 key 加同一个 tag（全服挤进一个 slot，Cluster 失去意义）；解法方向是「按 slot 分片的多个 dirty 集合」。

本文把这个方向细化为具体协议，并回答三个随之而来的问题：分片粒度多粗（§3）、落盘怎么发现分片（§5.2）、开销多大（§3.3、§5.5）。

## 2. 方案否决记录（为什么只剩「同 slot」）

这些结论是后面全部设计的前提，先钉死再往下谈。

### 2.1 无 Lua 的排序纪律：单机成立、Cluster 不成立

把提交拆成两条命令（SET + SADD）时，顺序与送达保证决定安全性：

| 组合 | 崩溃窗口 | drain 竞态 |
| --- | --- | --- |
| SET 先 | SET 后 SADD 前崩溃/断连 → **值在、无标记、永不下沉**（正是 `CommitLua` 要防的灾难） | 安全 |
| SADD 先 + 单连接 pipeline（单机） | 无——标记先于值落地，任何崩溃点最坏是「有标记、值仍旧」（多落一次，幂等无害） | 无——Redis 对单连接 FIFO，flusher 的 MGET 至少晚 drain 一个往返，必然读到新值 |
| SADD 先 + Cluster pipeline | **顺序契约不存在**：跨槽两条命令按节点分组并发发送，SET 可先落地，退化为「SET 先」的毫秒级崩溃窗口 | 同左 |
| SADD 先 + 逐条 await（Cluster） | 顺序有了 | **关不死**：drain 消费标记 → MGET 读到旧值 → SET 落地 → ack，这种先后顺序真的会发生 |
| 三连 SADD→SET→SADD | 「S1 已被 drain 消费、SET 落地、崩溃于 S2 前」仍会出现无标记的新值 | 只能缩小窗口，关不死 |

结论：**单机下「SADD 先 + pipeline」是与 Lua 等价的正确协议；Cluster 下没有不用原子就能保住这条保证的做法。** 且无 Lua 方案在 Cluster 下出问题是**不报错的**（值留在 Redis 里没人管、没有标记也没有日志，只在 Redis 重建时才暴露），比现状直接抛 `CROSSSLOT` 更危险——报错的失败在上线前就会逼人修，不报错的失败要等线上丢了数据才被发现。

### 2.2 脏标记进 value：落盘进程就得扫全库

标记与值同 key（value 包一层 dirty 标志）则提交天然原子（单 key SET），但落盘进程找脏 key 只能全键空间扫描取值——每轮读全部数据而非脏数据。不可行。**标记必须是可枚举的独立集合，而「独立集合 + 原子」⇒ 同 slot**，回到 §2.1 的结论。

### 2.3 分片粒度：固定桶，不是按实例

按实体实例 tag（`{player:123}:dirty`）功能上成立，但 10 万脏玩家 = 10 万个集合：落盘每轮 ~30 万条元数据命令、且需要 SCAN 全键空间发现分片。改为**固定 K 个桶**（`{b0421}::dirty`，桶号 = hash(entity:id) % K）：

| | 全局 `{dirty}`（现状） | 按实例 tag | **固定桶（本文）** |
| --- | --- | --- | --- |
| 脏集合个数 | 1 | = 脏实例数 | **K（恒定）** |
| 标记成员总数 | N | N | N |
| 每轮元数据命令 | ~500 | ~30 万 | ~K + 实工作量 |
| 分片发现 | 一次 drain | SCAN 全键空间 | **桶名固定（b0000..b4095），落盘进程自己算得出来，不用查** |
| flushChunk 的 MGET | 跨 slot（Cluster 下也是雷） | 同桶同 slot | 同桶同 slot |

顺带修掉现状的第二个 Cluster 雷：`RedisStore.mget` 一次 500 个不同 slot 的 key，Cluster 下同样非法；桶化后「同桶成员」天然同 slot，单条 MGET 合法。

## 3. key 文法与桶

### 3.1 文法

```
数据 key     {bNNNN}:{entity}:{id}:{field}       如 {b0421}:player:123:profile
锁 key       lock:{entity}:{id}                  不变——锁是单 key 操作，无需 tag
脏集合       {bNNNN}::dirty                        桶号与数据 key 同号
in-flight    {bNNNN}::dirty:inflight              同上
```

- `NNNN` 为 4 位定宽十进制桶号，取值 `[0, K)`，K = 4096（§3.3）。
- `{...}` 即 Redis Cluster hash tag：slot 由首个 `{` 与其后首个 `}` 之间的子串（`bNNNN`）决定，**同号键必然同 slot**——这是提交原子性在 Cluster 下合法的全部依据。
- **`::` 保留给 overlay 基础设施键**：数据 key 的 field 不含 `:`（现有校验），构造上永不与 `{bNNNN}::dirty` 冲突；锁 key 无花括号，同样不可能混淆。

### 3.2 桶计算

```java
// DataKeys（key 文法的唯一来源，primitives §6 边界不变）
public static final int BUCKETS = 4096;

static int bucket(String entity, long id) {
    int h = (entity + ":" + id).hashCode();           // 散列对象是逻辑身份整串
    h ^= h >>> 16;                                    // 高 16 位异或进低位，防块状/snowflake id 低位聚集（§8.3 分布测试三模式靠它）
    return Math.floorMod(h, BUCKETS);
}

public static String key(String entity, long id, String field) {
    return String.format("{b%04d}:%s:%d:%s", bucket(entity, id), entity, id, field);
}
```

- **散列对象必须是 `entity:id` 整串**：朴素 `id % K` 遇上结构化 id（按服务器分块分配、snowflake 机器位）会把数据系统性偏到一部分桶里；整串散列对顺序 / 块状 / snowflake 三种模式都均匀（§8.3 有统计单测钉死；哈希函数若被测试证伪则换函数，文法不动）。
- 实例的全部 field 落同一桶（桶由 entity:id 决定），将来若需要同实例多 field 的原子 Lua 天然合法；代价是该实例的负载集中在一个节点（现状无 tag 时分散在各 slot）——量级是 field 数（个位数），记入取舍清单（§11）。

### 3.3 K = 4096 的选取

三个事实夹出来的：

**事实一（下界）：桶太少，Cluster 里分不均。** 桶是分配的最小颗粒（同桶必同节点）。节点负载失衡 ≈ √(节点数 ÷ 桶数)（数据量足够大时与数据量无关）。节点上限按写入吞吐估算：成熟期 10 万在线 × 每秒 1 次写、读放大 5–10 倍 → 总 ops 超单节点容量一个量级 → **16 节点封顶**。容忍度：±10% 以内的不均衡在双倍容量冗余下感觉不出来（目标 ±7%），且 resharding 可以再平衡。ε = 7% → K ≥ 16/0.07² ≈ 3265。

**事实二（上界）：K 越大，落盘每轮白跑越多。** 每轮无条件排空全部 K 个桶（§5.2），单机模式全压在一台 Redis 上：2048 / 4096 / 8192 → 每秒约 1000 / 2000 / 4000 条命令（单节点 10 万 ops/s 的 1% / 2% / 4%）。这笔开销改完当天就有；而「分得均匀」这个好处要等真上了 Cluster 才用得到。

**事实三（锁定）：K 定了就改不了。** 桶号写在每个 key 的名字里，改 K = 全量数据重写。按协议生命周期的节点上限定价，不按当前部署。

随机 tag 有生日碰撞：K 个 tag 落进 ≈ 16384·(1−e^(−K/16384)) 个不同 slot（K=4096 → ~3620，打 12% 折扣；碰撞无害，只是碰撞的两个桶会一直待在同一个节点）。K 超过 ~16384 后再加桶就没有更多好处了。

| K | 单机背景命令 | ±失衡 @4 节点 | @8 节点 | @16 节点 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 2048 | ~1000 条/s | 4.6% | 6.5% | 9.1% | 贴容忍线 |
| **4096** | **~2000 条/s** | **3.3%** | **4.7%** | **6.6%** | **选定** |
| 8192 | ~4000 条/s | 2.5% | 3.5% | 5.0% | 开销翻倍，偏差只从 6.6% 缩到 5.0% |

选错了也有得救：K 偏小，不均衡在双倍冗余下感觉不出来，真恶化了还能用 resharding 再平衡；K 偏大，只是多花一些背景命令，不影响正确性。K 钉进 `DataKeys.BUCKETS` 常量，javadoc 记录本节逻辑，分布单测（§8.3/§8.4）守卫。

### 3.4 解析与校验

`parts()` 重写：剥 `{b<digits>}` 前缀（校验格式、数字、范围 < K），剩余部分按现行 `entity:id:field` 三段校验，规则一字不改。**旧格式 key（无桶前缀）直接 `IllegalArgumentException`**——迁移依赖此行为（§7）。新增 `bucketOf(key)`：解析出桶号，供 `CommitLua` / `DirtyLedger` 从 key 推导集合名。

## 4. 提交路径

```java
// CommitLua——SCRIPT 一字不改，KEYS[2] 从常量改为按 key 推导
public void commit(String key, String json) {
    client.getScript(StringCodec.INSTANCE).eval(
            RScript.Mode.READ_WRITE, SCRIPT, RScript.ReturnType.LONG,
            List.of(key, DirtyLedger.dirtySetOf(key)),     // {bNNNN}::dirty，与 key 同 slot
            json);
}
```

- 不变量不变：SET 成功 ⇒ 同轮 SADD 成功（原子）；标记与值同桶同 slot，Cluster 合法。
- **对外签名不变**：`RedissonLockCtx.put` 的门控 + 提交流程、`DataAutoConfiguration` 装配、`RedissonLockScope` 全部零改动。
- `DirtyLedger.dirtySetOf(key)` = `bucketOf(key)` → `{b%04d}::dirty`。集合名唯一来源仍是 `DirtyLedger`（沿用现状：`CommitLua` 引用其常量）。

## 5. 落盘协议

### 5.1 集合与脚本

`DRAIN_SCRIPT` 文本零改动，KEYS 从 `(DIRTY_SET, INFLIGHT_SET)` 改为 `(dirtySetOf(b), inflightSetOf(b))`——同 tag 同 slot，`RENAME` / `SUNIONSTORE` 在 Cluster 合法。落盘 spec §2.1–§2.4 的全部正确性论证**对每个桶同样成立**：commit 和 drain 不会互相吞标记、排空后的新标记进新一轮、in-flight 残留下轮合回、恢复路径唯一——语义不变，作用域从全局变单桶。

### 5.2 无条件全量排空（分桶发现方式）

落盘每轮对 b0000..b4095 **逐桶 drain，不做任何预探测**。曾考虑「先流水线 EXISTS 找非空桶、只 drain 非空桶」——**已否决**：中断轮次的桶只剩 inflight 残留（dirty 已被 RENAME 走），EXISTS 探测 dirty 键会漏掉它，残留永远等不到合回。无条件 drain 自带探测：空桶是一次微秒级 no-op（两个 EXISTS 都不命中，直接返回空表）；inflight 残留桶由 drain 头三行合回。**恢复路径仍然唯一，无需任何专门分支。**

### 5.3 轮次结构

```java
// FlushOrchestrator.drainAndFlush 骨架（两段流水线，替代原「一次 drain + 全局分片」）
Map<Integer, Set<String>> drained;                     // 桶号 → 本轮成员快照
for (int b = 0; b < DataKeys.BUCKETS; b++) {           // 第一段：K 次 drain，批量流水线发
    Set<String> members = dirty.drainToInflight(b);    // 空桶返回空集
    if (!members.isEmpty()) drained.put(b, members);
}

List<Map.Entry<String, String>> buffer;                // 第二段：逐桶 MGET，跨桶聚 Mongo 批
for (var e : drained.entrySet())
    for (List<String> mchunk : chunks(e.getValue(), chunkSize)) {   // 同桶同 slot，MGET 单条合法
        buffer.addAll(redis.mget(mchunk).entrySet());               // 不存在的 key 不进 Map（nil 计数照旧）
        while (buffer.size() >= chunkSize) flushBatch(take(buffer, chunkSize));
    }
flushBatch(buffer 余量);

// flushBatch（顺序约束落盘 spec §2.4 不变，粒度从「片」改为「批」）：
//   1. failed = mongo.bulkUpsert(批)        // unordered + index 反查，不变
//   2. dirty.markAll(failed)                // 失败 key 按各自桶分组 SADD 回 {bN}::dirty
//   3. dirty.ackInflight(本批全部成员)      // 含 nil key（同现状：不回 dirty 但 ack）；按桶分组 SREM
//   4. stillHoldsLock(lock)                 // 失锁即中断，剩余留各桶 inflight（§2.3 恢复）
```

> **勘误（随实现校正）**：上方第 4 步的失锁检查只在循环内每个**整批**落盘后执行；末尾余量批**刻意不查**——轮次到余量批即结束、其后再无任何写，补上检查唯一的效果是把已完成的轮误报成 INTERRUPTED。

- Mongo 批跨桶聚合（保持 500/批的批量收益）；`markAll` / `ackInflight` 从 key 推导桶、分组下发；批内先 mark 后 ack 的顺序保持——崩溃于两步之间 = 整批下轮重放，upsert 幂等。
- 中断 / 崩溃路径与现状完全同构：残留桶的成员留在该桶 inflight，下轮无条件 drain 合回。
- `flushOnce` 三态返回值契约（0 / n / INTERRUPTED）不变；`FlushScheduler`、`GracefulShutdown` 结构零改动。

### 5.4 聚合方法

`DirtyLedger` 的聚合从单集合 SCARD / SMEMBERS 改为对 K 桶的**流水线聚合**（对缺失键 SCARD 返回 0，不报错）：

- `backlogSize()`：流水线 SCARD 全部 K 桶求和。每次 gauge 抓取一批量往返，4096 条命令在抓取间隔（15s+）下开销可忽略。语义不变（成员数、持续增长 = 落盘跟不上写入）。
- `inflightMembers()`：仅停机日志用（`GracefulShutdown`），流水线 SMEMBERS 聚合，停机路径付得起这个往返。

### 5.5 成本

- 每轮 K 条 EVAL，流水线为 1–3 次批量往返；单机 ~2000 条/s ≈ 2% 处理能力；Cluster 后按节点分摊（每节点 K/N 条）。
- 10 万脏场景：drain 回包从「1 个 10 万成员的大包」变为「4096 个 ~24 成员的小包」，单包更小更均匀。
- 提交路径成本不变（仍 1 次 EVAL，1 次往返）。

## 6. 指标

| 指标 | 变化 |
| --- | --- |
| `dbserver.flush.round / keys / failed / missing / skipped` | 不变 |
| `dbserver.dirty.backlog` | 实现改为 §5.4 流水线聚合，名称与告警语义不变 |
| `dbserver.dirty.buckets`（新增 gauge） | 非空桶数，独立的 K-SCARD 扫描（gauge 抓取一次批量往返）——最先该看的信号，比成员数粗 |

## 7. 迁移

- **Mongo 零迁移**：collection = `entity:field`、`_id` = id，由解析结果决定，剥桶前缀后与现状逐字段一致。
- **Redis flushdb 一次**（开发 / 测试环境；沿用落盘 spec §2.5 迁移先例）。旧 `{dirty}` / `{dirty}:inflight` 随之消失，无孤儿键。
- 数据回灌走**现成冷启动路径**：`LockCtx.get` miss → 锁内从 Mongo 加载 → 回填 Redis（primitives §2.2、落盘 spec §11 的既有机制）。从未下沉的脏数据随 flushdb 丢失——与「不存在生产数据迁移」的既有裁决一致。
- `game-dbserver` 从未上过生产。

## 8. 测试

**纯 Java（不连 Redis）：**

1. CRC16-XMODEM 实现正确性（已知测试向量）；
2. **同槽断言**：任取桶号 b，`slot("{b%04d}::dirty") == slot("{b%04d}:player:123:profile") == slot("{b%04d}::dirty:inflight")`（遍历 4096 桶）——单机 IT 测不了 CROSSSLOT，Cluster 正确性就靠这条测试兜底；
3. `bucket()` 分布：顺序 id / 块状分配（低 12 位恒定，恒 1024）/ snowflake 型（高位变化）三种合成模式各 10 万身份，断言 χ² < 2K 且 max ≤ 3×mean——100k 样本下均匀散列的期望 ≈ 1.7×mean、尾部抽样可到 2×，1.2 的口径统计上立不住（计划阶段修正）；
4. tag→slot 散布：4096 个 tag 去重后的 slot 数 ≥ 3600；按 16 路等分槽位区间模拟节点分配，失衡 ≤ 12%（期望 ~6.6%，留余量；期望基数取去重 slot 数，比按 4096 计更严）；
5. `DataKeys`：新文法 round-trip、旧格式拒绝、越界桶号拒绝、field 含冒号拒绝。

**IT（沿用本地预起 Redis/Mongo 约定，见 DEVELOPMENT.md）：**

6. `CommitLuaIT`：提交后成员落在正确的桶集合；
7. `DirtyLedgerIT`：逐桶 drain、桶级 inflight 残留合回、backlog 聚合；
8. `FlushOrchestratorIT`：多桶 + 跨桶 Mongo 批 + 中断残留恢复（下轮无条件 drain 合回）；
9. `LockCtxIT` / `LockScopeIT`：提交路径对外行为不变，应原样通过——回归即验证「调用方零改动」的声明；
10. `FlushMetricsIT`：新 gauge 语义。

## 9. 实现期验证点与风险

1. **Redisson 批量执行 EVAL 的机制**（RBatch 是否支持 script eval）——第一验证点，**实现计划排的第一件事就是把它验证掉**。若不支持：退回分批顺序 EVAL 并重新核算 K 与轮次预算（4096 × 单往返延迟须 ≤ 轮次间隔的一小半），或走 Redisson 底层连接的 pipeline。此项可能反过来调整 K 常量定值。**已验证并落地（Task 4）**：未走 RBatch——`DirtyLedger.drainAll()` 以 `evalAsync` 异步扇出 K 条 EVAL、聚合等待，空库 4096 桶排空实测约 0.11s，K 定值未受影响。
2. CRC16-XMODEM 的 Java 实现须与 Redis 服务端一致（向量测试；tag 提取规则一并在 Java 里复刻，才能在单测里算 slot）。
3. `String.hashCode` 在真实 key 形态下的分布（§8.3 守卫；弱则换散列函数，文法不动）。
4. K 条 EVAL 流水线的实测往返与耗时（≤ 轮次预算）。

## 10. 既有 spec 修订清单（随实现落地）

- **架构 spec（2026-07-24）**：
  - §4.1 key 约定改为 `{bNNNN}:{entity}:{id}:{field}`，指向本文 §3；
  - §4.2「不做跨 Key Lua」修订为「**Lua 仅限同一 hash tag（同一桶）内的 key**；跨实体实例的原子操作仍然不做」——落盘 spec §2.5.1 指出的「该条在 `CommitLua` 上未兑现」自此了结。
- **落盘 spec（2026-09-04）**：
  - §2 消费协议按桶重述（以本文 §5 为准）；§2.5 的 hash tag 论证从「两个集合名」扩展为「数据 key + 集合键的全套文法」；§2.5.1 标注「已由本文了结」；
  - §3 批量策略改为两段流水线（本文 §5.3）；§7.1 指标表补 `dbserver.dirty.buckets`；§8 的 `game-data` 改动清单按本文 §3–§5 更新。
- **代码 javadoc**：`DataKeys` / `CommitLua` / `DirtyLedger` / `RedisStore` 按新文法与桶协议重写，保留论证链并指向本文。

## 11. 不在本次范围

- **Cluster 运行时支持**：`DataProperties` / Redisson 的 `ClusterServersConfig`、地址列表、真实集群环境验证——协议已就绪，运行时是独立工作；
- **工程化 tag**（构造 CRC16 精确命中目标槽的字符串，消掉 §3.3 的生日碰撞折扣）——K 的好处用到头时再评估；
- **fencing token**：跨实例写重叠微窗口（固定租约 + isHeld 门控）不变，仍按并发修订 §4.3 的成熟期升级路径备案；
- **热点实例**：单个巨实例占一个 slot，与现状（无 tag 时同样单 slot）无差异，不额外处理。
