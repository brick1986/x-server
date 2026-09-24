# 发号原语与 uid 位型设计（IdGenerator）

> 范围：`game-data` 新增发号原语 `IdGenerator`（号段模式，Mongo counters 做账本），并钉死无爹实体的 id 位型协议。key 文法 `{bNNNN}:{entity}:{id}:{field}`（[提交桶化设计](./2026-09-21-commit-clusterslot-design.md) §3）一字不动——本文回答的是 `{id}` 从哪来。
>
> 本文同时收口两类 id 来源：无爹实体（player/account/guild/全服邮件等）走发号器；有爹实体（道具/装备行）走容器内 seq，**不需要发号器**。方案否决过程与位型取舍已随讨论定稿，本文记录结论与依据，供实现与将来维护引用。
>
> 不在本次范围：`game-web` 业务接入（注册/创角流程属业务域）、对外展示号（防枚举）、装备升格全服实体（触发条件见 §7.3）。

## 1. id 的两类来源

`{id}` 是 long（`DataKeys.key(String, long, String)` 钉死，`idOf` 按 `Long.parseLong` 解析），同时就是 Mongo 文档 `_id`（`DataKeys.docIdOf`，Mongo 映射决策 A）。按"有没有爹"分两类，来源完全不同：

| 类别 | 例子 | id 来源 |
| --- | --- | --- |
| **无爹实体**（全局唯一，被跨玩家引用） | player（角色 uid）、account、guild、全服邮件、订单 | **发号器**（本文主体） |
| **有爹实体**（离不开父容器，读写都在父锁内） | 背包道具行、装备行 | **容器内 seq**（§7，零组件） |

讨论确认的口径：**uid = 角色的唯一 id**（一个账号可多角色，账号 id 独立发号）；背包、邮件收件箱、装备详情是**容器**——一个玩家一个整体文档（`{bNNNN}:bag:{uid}:items`、`mail:{uid}:inbox`、`equip:{uid}:detail`，覆盖写语义不变）。

唯一性范围按 entity 独立：Mongo 集合 = `{entity}:{field}`，guild:5 与 mail:5 互不冲突，counter 每 entity 一条。

## 2. 方案否决记录

这些结论是后面全部设计的前提，先钉死。

| 方案 | 例子 | 否决理由 |
| --- | --- | --- |
| UUID / ObjectId | `5f0a...`（36 字符）/ `65f2c8e8...`（24 位十六进制） | 协议出局：id 钉死 long，改协议 = 全量数据重写（同 `BUCKETS` 性质） |
| Redis INCR | `INCR {c}:guild` 返回 5 | 恢复链有坑：Redis 会 flushdb（DEVELOPMENT.md 迁移先例）、落盘滞后 1~3s（架构 spec §4.3），账本丢了只能从 Mongo `max(_id)` 回种+猜余量。事故链：INCR 发 100 → guild:100 已落盘 → Redis 丢 → 回种 max=99 → 再发 100 → flush upsert **静默覆盖前一个 guild:100** |
| Mongo 单发（每次 `$inc: 1`） | `findAndModify` 返回 6 → guild:6 | 稳但突发不扛：全服邮件一次要 10 万个号 = 10 万次 Mongo 往返，光发号等 1~2 分钟 |
| snowflake 族 | uid ≈ 2.1×10¹⁸（19 位） | 与号段同族（高位维度+低位序号），否决在于**唯一性押在时钟+机器号这两个新依赖上**：NTP 回拨=重号，处理代码是所有 snowflake 实现里最脏的部分；机器号分配照样要协调（中心依赖没消失，只是搬家）；uid 19 位数字，key 内存白多 8~10 字节/个，客服也读不懂。号段把唯一性押在**已经存在、本来就要连**的 Mongo 账本上，服号是滚服部署本来就有的编号 |
| 随机 long + 唯一性兜底 | `nextLong()` 出 19 位随机数 | 撞 `_id` 要等 2~3s 后 flush upsert 才炸，发现太晚；63 位随机发 1 亿实体碰撞概率 ~5×10⁻⁴。防枚举若真需要，用"内部 uid + 对外随机展示号"解决，不进主键 |

**号段的分布式安全不靠锁不靠协商**，靠一条命令的原子性：

```
findAndModify({_id: "idgen:1:player"}, {$inc: {seq: 100}}, upsert, new) → 返回新值 seq
```

Mongo 单文档修改排队执行，两个节点并发执行必然拿到**不重叠**的号段，物理上不可能重号。之后各节点在内存里发号，互不来往。

## 3. id 位型（协议常量，一次性钉死）

所有无爹实体共用同一位型：

```
 63   62                  48 47          32 31                    0
[符号] [15 位恒 0，预留] [16 位服号] [32 位流水，最多 42.9 亿/服]

id = (serverId << 32) | seq
```

| 例子 | 数值 |
| --- | --- |
| 1 号服（大区制默认）第 1 个角色 | 4294967297（10 位） |
| 3 号服第 587 个角色 | 12884902475（11 位） |
| 1 号服第 1 个工会 | 4294967297（counter 独立，流水同样从 1 起） |

- **16 位服号 = 65536 个服；32 位流水 = 42.9 亿/服**，均按"够到产品死"定价。
- **15 位预留恒 0，目的是数字短**。占满对比：流水扩到 47 位，1 服首号 = 2⁴⁷+1 = 140737488355329（15 位数字），亿级 key 下 id 段每多 1 字节都是 GB 级 Redis 内存，换来的是永远到不了的容量。留 0 同时给未来挪位留余量。
- **改位型 = 全量数据重写**，与 `DataKeys.BUCKETS` 同性质的协议常量。位型常量钉进实现类（`IdGenerator` 或其骨架类），javadoc 记录本节逻辑。
- **为什么高位必须有服号**：部署形态"还没定"，按滚服制设计——大区制当"1 号服"特例跑，反过来不行。滚服制下各服独立 Mongo 各自发号，将来合服时低 32 位永不相交（`idgen:1:player` 与 `idgen:3:player` 是 counters 表里两条文档，合库后各发各的），**合服零迁移**。不预留的代价：真要合服那天，每个 key 重写一遍——`BUCKETS` 量级的事故。
- 保险费，说实数：uid 从 4294967297 起步（10 位）而非纯大区制的 1。按 1 亿角色 × 每人 5 个数据 key，id 段平均多 9 字节 ≈ 4.5 GB Redis 内存。换来合服安全，值。
- 流水到 2³²-1 抛异常（防御性校验，实际到不了）。

## 4. 发号机制（号段模式）

### 4.1 账本

- Mongo `counters` 集合（集合名固定），文档 `{_id: "idgen:{serverId}:{name}", seq: <流水>}`。
- **seq 记的是服内流水**（1, 2, 3…），uid 由实现层拼上服号位；不存完整 uid 数值——账本数字小、合服后语义清晰。
- counter 名带服号的用意见 §3；counter 名带 `idgen:` 前缀，防 `counters` 集合未来被其他元数据用途复用时撞名。
- **counters 是基础设施元数据，不是实体数据**：不进 Redis、不走 `CommitLua`/dirty 流水线、不受 flushdb 影响、不参与标脏。`game-dbserver` 对它无感知。恢复链因此整体消失——账本在 Mongo 里只增不减，Redis 丢什么都与发号无关。

### 4.2 租段与发号

```
租段：findAndModify({_id: counterName}, {$inc: {seq: step}}, upsert, new)
      → 返回新 seq N → 本地段 = [N-step+1, N]
发号：进程内存 AtomicLong 从本地段顺次取；段耗尽 → 租下一段
```

- 进程崩溃丢失未发完的段 = 产生**空洞，空洞无害**（跳号，不是重号）。
- **段长一刀切**：配置项默认 100（当前发号实体全是低频顶层实体，每秒几十以内），开服/买量窗口临时调大即可，不做 per-name 配置（YAGNI）。
- 突发口径：开服 30 万注册 / 3 节点、段长 10000 → 每节点只碰 Mongo **10 次**，全程无感。
- 批量场景（全服邮件）：`lease(name, count)` 一次拿走 `[start, start+count)`，10 万个号 1 次往返。

### 4.3 失败模式

只有一个：租段那一刻 Mongo 不可用 → 该实体的发号阻塞，调用方同步感知。Mongo 挂了注册/创角本来也写不了库，属合理背压；**不做静默降级**（如借 Redis 顶替——那等于把否决记录里 Redis INCR 的坑请回来）。

## 5. 对外 API（game-data 原语）

```java
public interface IdGenerator {
    /** 发一个 id。name 由业务侧传（"player"/"account"/"guild"/"mail"...），game-data 不认识业务实体。 */
    long next(String name);

    /** 批量租号段 [start, start+count)，返回 start。count > 0。 */
    long lease(String name, int count);
}
```

- 位置：`io.github.brick.data.idgen`（接口 + 实现），`DataAutoConfiguration` 装配，沿用 `EntityPriorities` 的边界思路——业务词只出现在调用方参数里，底层零业务 import（primitives §6，`game-data 不认识业务实体`）。
- `next` 的典型调用点在业务侧：注册 `next("account")`、创角 `next("player")`；**发号不持 Redis 实体锁**——号段原子性由 Mongo 保证，与 `LockCtx` 无关，`next` 可以在锁外调用（创角流程里先拿号、再进锁写文档）。
- 参数校验：`name` 非空、不含 `:`（防拼进 counter 名后解析歧义）；`count` ∈ [1, 段长上限]。

### 5.1 配置

进 `DataProperties`（`game.data` 前缀，发号参数是 `game-data` 自己的，`game-dbserver` 不新增无意义配置项——对齐 `DbServerProperties` 的 javadoc 论证）：

| 配置项 | 默认 | 校验 | 说明 |
| --- | --- | --- | --- |
| `game.data.idgen.server-id` | 1 | @Min(1) @Max(65535) | 服号，一次性决定（§3） |
| `game.data.idgen.segment-size` | 100 | @Min(1) @Max(1_000_000) | 租段步长 |

### 5.2 服号自检

- 启动时往 `counters` 写一条身份文档 `idgen:{serverId}:boot`（内容：首次启动时间戳）。
- 启动校验：若本库 counters 中已存在**其他** serverId 的 `boot` 文档 → **启动失败**（同一个库被两个服号交替使用 = 配置漂移，位型前提被破坏，fail-fast 而非带病运行）。
- 诚实记录：两个**独立**的服（各自独立的 Mongo）被运维配了同一个号，自检测不到——这层靠运维纪律，代码防不住。

## 6. 多角色口径（账号与角色分开发号）

- 注册 → `next("account")` 发账号 id；创角 → `next("player")` 发角色 uid。两个 counter 互不相干，均走 §3 位型。
- 账号的**角色清单**就是账号文档的一个 field（容器模式）：`{bNNNN}:account:{accountId}:roles` = `[4294967297, 4294967298]`。角色也要全局唯一 id（被好友/排行榜跨玩家引用），不能塞账号高位。
- 创角流程（业务侧，记参考口径）：**锁外**先 `next("player")` 拿 uid（发号不依赖锁，§5）→ `lockAll(account + 新角色)`——写 player 的 key 必须持 player 本实体的锁，门控按 key 解析（primitives §3.1）→ 锁内更新账号角色清单 + 初始化角色文档（多次 `put`，多实体部分提交规则见 primitives §4）。

## 7. 有爹实体：容器内 seq（不发号）

### 7.1 模式

道具/装备行的读写全部发生在父玩家的锁内，唯一性范围就是玩家自己，**不需要任何发号组件**：

```
{bNNNN}:bag:4294967297:items    → {"seq": 58, "items": [...]}          背包（消耗品）
{bNNNN}:equip:4294967297:detail → {"seq": 59, "items": {"55": {...}}}  装备（与背包分家：文档大，混装互相拖累）
{bNNNN}:mail:4294967297:inbox   → 收件箱整体                           全服邮件实体另发全局 id
```

- 容器 JSON 自带 `seq` 字段，玩家锁内 +1，新增行的 iid 即取后值。装备与背包各一个 seq（各自 JSON 内）。
- 取全部道具/装备 = 1 次 `GET`；容器与背包/装备的覆盖写、标脏、落盘复用现有全套模型（primitives §6.1），零新增机制。

### 7.2 跨玩家引用：二元组，不造全局装备 id

交易行挂单、拍卖、举报存 `(ownerUid, iid)` 二元组：挂单文档（entity=`market`，id 走号段）里写 `{"seller": 4294967297, "equipIid": 55}`，展示时拿二元组 GET 装备容器。**不需要全局装备 id**。

### 7.3 为什么装备不能把玩家编进 id（纠正早期讨论）

早期讨论举过 `id = 123×2²⁴ + 45` 的例子——那是"玩家 id 是小数字"假设下的算法，uid 定成 48 位后**此路不通**：4294967297 << 32 ≈ 1.8×10¹⁹，超过 long 上限 9.2×10¹⁸，必爆。结论：有爹实体的全局引用一律走二元组（§7.2）。

**升格触发条件**（记录备案，触发再做）：单件装备文档大到几十 KB（整个玩家容器一起读写太亏）、或出现全服装备审计这类"单件成档"需求 → 装备升格为无爹实体，`next("equip")` 走号段，文档里存 `ownerUid`。机制零改动，加一条 counter。

## 8. 测试

**纯 Java（不连外部依赖）：**

1. 位型：`(serverId << 32) | seq` 的拼装与拆解 round-trip；serverId 越界（0、65536）拒绝；seq 达 2³²-1 抛异常；
2. counter 名：`idgen:{serverId}:{name}` 格式、`name` 含 `:` 拒绝；
3. 本地段发号：`next` 在段内严格 +1，段耗尽触发租段（mock 租段）。

**IT（本地预起 Mongo，沿用 DEVELOPMENT.md 约定）：**

4. **并发租段不重叠**：两个 `IdGenerator` 实例（同库同 counter）并发 `next` × 各 1000，断言 2000 个 id 无一重复；
5. `lease` 返回段正确、段内无重号、账本只增不减；
6. 实例重建（模拟重启）后继续发号不重号（丢段只产生空洞）；
7. server-id 自检：库内存在其他服号的 boot 文档 → 启动失败；
8. `game-data 不认识业务实体`：ArchUnit 禁 `io.github.brick.data.idgen` import 业务域包（沿用 primitives §7.9 手法）。

## 9. 既有 spec 修订清单（随实现落地）

- **架构 spec（2026-07-24）**：§4.1 key 约定补一句"id 来源的唯一出处即本文"（key 文法本身不动）；
- **primitives spec（2026-08-11）**：§3 包结构补 `idgen/`（`IdGenerator` 接口 + 实现）；
- **代码 javadoc**：`DataKeys` 类注释补指向本文（id 位型与本类文法同属协议常量），实现类记录 §3/§4 论证链。

## 10. 不在本次范围

- **双 buffer / 段预取**：当前量级（低频顶层实体）用不上，段长配置足以应对突发；
- **per-name 段长**：一刀切 + 全局配置项，出现真实差异化需求再加；
- **对外展示号**（防枚举）：内部 uid 顺序可猜，游戏场景基本无害；真要防，内部 uid 不变、对外加一层随机展示号；
- **装备升格全服实体**：触发条件见 §7.3；
- **服号自动分配**：`server-id` 是运维配置项（§5.2 自检兜底配置漂移），不做注册中心。
