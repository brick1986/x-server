# game-data 数据原语设计（Plan B）

> 范围：`game-data` 模块的对外 API 与内部实现规格——锁、读、覆盖写、标脏、序列化、连接池。本文是对 [架构层设计](./2026-07-24-server-architecture-design.md) §3.1/§4 与 [数据并发正确性修订](./2026-08-04-data-concurrency-fixes-design.md) §1-§4 在 `game-data` 层面的落地；模块边界与依赖规则见 [模块清单与依赖规则设计](./2026-08-05-module-breakdown-design.md)（其 §2.5/§3.3 已同步为本文件所述形态）。
>
> 不在本次范围：`game-web` 横切（AuthFilter/BizLogger）、`game-dbserver` 落盘编排——见后续 Plan C。

## 1. 设计取向

`game-data` 对外暴露**一个锁作用域对象 `LockCtx`**，承载读与覆盖写；不暴露 `lock`/`unlock`/`find`/`save` 等裸原语，也不以"单次 `mutate` 回调"模板方法形态出现。

取此形态的理由：

- **业务逻辑保持命令式可读**。读-改-写是 `ctx.get` → `if/return` → `ctx.put` 的平铺语句，"够不够、不够直接返回错误"这类带客户端响应的分支以普通 `if/return` 表达，不必塞进只应返回新 POJO 的 `mutate` 回调。
- **安全不变量在 `game-data` 一处闭环**，不靠调用方自觉。锁的拿/放由 try-with-resources 绑定；`ctx.get`/`ctx.put` 内部 `isHeldByCurrentThread()` 断言/门控；`ctx.put` 是原子 Lua `SET+SADD`；多锁获取按全局类型优先级排序、all-or-nothing。
- **`game-data` 不认识业务实体**。`get`/`put` 全泛型 `<T>`，POJO 类由 `game-web` 传入，`game-data` 仅经 `JsonCodec` 反序列化/序列化，不 import 任何业务域 POJO——保 `game-dbserver` 依赖最瘦的前提。

## 2. 对外 API

### 2.1 加锁：`lockScope.lockAll(...)`

```java
public interface LockScope {
    /** 按全局类型优先级 + 同类型 ID 序排序后依次 acquire，all-or-nothing。 */
    LockCtx lockAll(List<LockReq> reqs);
}
```

```java
public record LockReq(String entity, long id) {
    public static LockReq of(String entity, long id) { return new LockReq(entity, id); }
}
```

- `game-web` 注入 `LockScope` 的实现 bean（菜鸟期为 Redisson 实现），调 `lockScope.lockAll(...)` 取 `LockCtx`。
- `LockCtx` 实现 `AutoCloseable`，**必须**用 try-with-resources 包住，`close()` 逆序释放全部锁（异常路径也释放）。
- 排序规则（并发修订 §1.3）：按实体类型全局优先级（菜鸟期 `guild > player`）排序；同类型内按 `id` 升序排序。调用方传入顺序无关。
- **all-or-nothing**：依次 acquire，任一失败立即逆序释放已获取的锁并抛 `LockAcquireException`——不把"部分获取"的中间态暴露给调用方。
- 单实体是 `lockAll(List.of(LockReq.of(...)))` 的 N=1 特例，不另设 `lock(entity,id)` API，避免多实体场景误用裸单锁导致乱序死锁。
- 锁租约固定 `leaseTime=10s`、**禁用看门狗**（并发修订 §4.1）：`lock.lock(10, TimeUnit.SECONDS)`，不续约。正常完成主动 `unlock`；超 10s 由 TTL 自然到期释放。

### 2.2 锁作用域：`LockCtx`

```java
public interface LockCtx extends AutoCloseable {
    /** 锁内读。miss 则锁内从 Mongo 加载并普通 SET 回填。 */
    <T> T get(String key, Class<T> type);

    /** 锁内覆盖写：序列化 + isHeld 门控 + 原子 Lua SET+SADD。单 key。 */
    <T> void put(String key, T pojo);

    @Override void close();   // 逆序释放全部锁
}
```

#### `ctx.get(key, Class<T>)` — 读路径

锁内流程（并发修订 §2.1）：

```
lock = resolveLock(key)                     # 由 key 解析到本实体的 RLock（见 §3.1）
if not lock.isHeldByCurrentThread():        # 持锁断言，失锁（含未拿锁即调用）抛 LockLostException
    throw LockLostException
v = GET key                                  # Redis
if v == nil:                                 # miss
    v = LOAD Mongo(key)                      # 锁内从 Mongo 加载
    SET key v                                # 普通回填（持锁，无并发写，无需 SET NX）
return JsonCodec.decode(v, type)
```

- **纯读也走 `LockCtx`**——读路径同样持锁，回填是持锁下的普通 `SET`。不另开无锁便捷读路径：无锁回填会把持锁写的 v2 覆盖回从 Mongo 读到的 v1，触发竞态 #2（静默数据损坏，并发修订 §2.1）。
- **失锁统一 `LockLostException`**：`get` 的持锁断言失锁与 `put` 的提交门控失锁都抛 `LockLostException`（不抛 `IllegalStateException`），使块内任意失锁点走同一条重试路径（见 §4）。"未拿锁即调 `get`"的编程错误同样抛 `LockLostException`——handler 整请求重试时若重新 `lockAll` 成功则自愈，若持续失败则重试到上限报错，绝不如静默回填损坏数据。
- `game-data` 不认识业务实体：`type` 由调用方传入，`get` 经 `JsonCodec.decode(v, type)` 反序列化，返回值用普通方法返回值传递（**不入 ThreadLocal**，见 §5）。

#### `ctx.put(key, pojo)` — 覆盖写提交

锁内流程（并发修订 §2.2、§4.2；架构 §4.2）：

```
json = JsonCodec.encode(pojo)
lock = resolveLock(key)                     # 由 key 解析到本实体的 RLock（见 §3.1）
if not lock.isHeldByCurrentThread():         # 提交门控
    throw LockLostException                  # 失锁绝不写，fail-fast
Lua: SET key json; SADD dirty key            # 唯一提交点，原子往返
```

- **单 key 提交**：`put` 一次写一个 key。跨实体（如玩家捐工会：改 guild + 改 player）由调用方在同一个 `LockCtx` 块内按业务顺序逐个 `ctx.put`——每个 `put` 各自原子，`isHeld` 门控各自校验。**不做跨 key Lua**（架构 §4.2，预防成熟期 Redis Cluster 报错）。
- **写顺序**（架构 §4.2.3，归业务层）：先写业务日志（`BizLogger`，落盘）→ `ctx.put` A → `ctx.put` B。`BizLogger` 在 `game-web`，`game-data` 的 `put` 不碰业务日志。
- **`put` 即唯一提交点**：原子 Lua `SET+SADD`，不暴露可分离的 `SET` 与 `SADD`——拆开则 `SET` 成功 `SADD` 失败时数据在 Redis 却不标脏，永不下沉 Mongo，Redis 崩溃即永久丢失（架构 §4.2）。

### 2.3 完整调用形态

```java
try (LockCtx ctx = lockScope.lockAll(List.of(
        LockReq.of("guild", gid),
        LockReq.of("player", pid)))) {                       // 内部按 guild>player 排序
    Guild  g = ctx.get(guildKey,   Guild.class);              // 锁内读
    Player p = ctx.get(playerKey,  Player.class);             // 锁内读
    if (p.coin() < 30) return Result.fail("余额不足");        // 命令式判断
    Guild  g2 = g.addContribution(30);
    Player p2 = p.withCoin(p.coin() - 30);
    bizLogger.log(donateLog(gid, pid, 30, p.coin(), p2.coin()));  // §4.2.3 业务日志先于 put
    ctx.put(guildKey,  g2);                                  // 原子 Lua SET+SADD
    ctx.put(playerKey, p2);                                  // 原子 Lua SET+SADD
    return Result.ok();
}                                                             // close() 逆序放锁
```

## 3. 内部实现

`game-data` 内部包结构（`io.github.brick.data`）：

```
store/   RedisStore         GET/SET/DEL JSON 字符串（连接池 100~200）
         MongoStore         upsert/bulkWrite JSON 文档（连接池 50~100）
         DataKeys           key 命名常量 + entity→锁名映射（已有种子）
lock/    LockScope          lockAll(List<LockReq>) → LockCtx 实现入口
         LockCtx            锁作用域：get/put/close；持有多把有序 RLock
         LockReq            (entity, id) 记录
         LockAcquireException   拿锁失败（all-or-nothing 回滚后抛）
         LockLostException   put 时 isHeld 门控失锁抛
overlay/ CommitLua         原子脚本 SET key newjson; SADD dirty key（不解析 JSON）
         DirtyLedger       SADD/SMEMBERS/SREM（落盘进程消费，见 Plan C）
codec/   JsonCodec         POJO↔JSON 序列化工具（不持有任何业务实体类）
```

### 3.1 `LockCtx` 内部职责

- 持有本次 `lockAll` 获取的全部 `RLock`，以 `Map<(entity, id), RLock>` 索引（已按全局类型优先级 + 同类型 ID 序排好序）。
- **`resolveLock(key)`**：`get`/`put` 的持锁断言/门控必须命中**本实体**那把锁，不能任取一把——否则同类型多 id（如同时持 `lock:player:1` 与 `lock:player:2`）时，本实体锁已过期而另一实体锁仍持有会漏过门控。解析依赖 key 命名约定：key 格式钉死为 `{entity}:{id}:{field}`（如 `player:123:profile`、`guild:7:fund`），`DataKeys` 提供 `entityOf(key)` / `idOf(key)` 解析，`LockCtx` 据此查 `Map` 取对应 `RLock`。`DataKeys` 是该命名约定的唯一来源，业务域不得自造违反约定的 key。
- `get`：`resolveLock(key)` → `isHeldByCurrentThread()` 断言（失锁抛 `LockLostException`）→ 读路径（GET → miss 则 LOAD Mongo + 普通 SET 回填）。
- `put`：`resolveLock(key)` → `isHeldByCurrentThread()` 门控（失锁抛 `LockLostException`）→ `CommitLua`。
- `close`：逆序 `unlock` 全部 `RLock`，异常路径也执行（`AutoCloseable` + try-with-resources 强制）。

### 3.2 `CommitLua`

- 仅做 `SET key newjson; SADD dirty key`，**不解析 JSON**（架构 §4.2、模块 §3.3）。
- 原子往返：一次 Lua 调用完成"写数据 + 标脏"，不可拆成独立 `SET` 与 `SADD`。

### 3.3 `LockScope` 加锁顺序实现

- 解析 `List<LockReq>` → 按 `entity` 查全局类型优先级表（菜鸟期 `guild=0 > player=1`，新增实体在此表登记其优先级位置）排序；同类型内按 `id` 升序排序。
- 排序后依次 `lock.lock(10, TimeUnit.SECONDS)`（固定租约、无看门狗）。
- 任一 acquire 失败：逆序 `unlock` 已获取者，抛 `LockAcquireException`。

### 3.4 连接池

- Redis 连接池 100~200、Mongo 连接池 50~100（架构 §3.1，显式设限，禁止无上限）。每池大小按**该池自身峰值并发需求**定，不与载体线程池绑定。
- 载体线程池用 JDK 25 默认（基于 CPU 核数）。
- **禁嵌套跨池获取**（并发修订 §3.2）：一个虚拟线程绝不同时持有两个连接池的连接。`ctx.get` 的 `GET` Redis（还）→ miss 则 `LOAD` Mongo（还）→ `SET` Redis 回填（还），每步独立借还，天然不嵌套。`ctx.put` 的 `SET+SADD` Lua 是一次 Redis 调用，不触达 Mongo。

## 4. 异常与重试

- **拿锁失败**：`lockAll` 抛 `LockAcquireException`，`game-web` handler 捕获后返回统一错误消息给客户端（不重试或由上层策略决定）。
- **锁过期**：`ctx.get` 的持锁断言或 `ctx.put` 的提交门控失锁时，均抛 `LockLostException`。块内任意失锁点走同一条重试路径。
  - `LockCtx` 块内若已 `bizLogger.log`（业务日志先于 `put` 写盘），该日志已落盘；`put` 抛出后，**禁止复用块内已 `get` 的旧 POJO 原地重试**——旧对象基于已被覆盖的 v2，用它再 `put` 会覆盖他者已写入的 v3，丢更新（并发修订 §2.2、§4.2）。
  - **重试契约**：`LockLostException` 由 `game-web` handler 捕获后**整请求重试**——从 `lockAll` + `ctx.get` 重新开始，重新拿最新值、重新跑业务逻辑。`game-data` 不内置自动重试（自动重试会重复写业务日志、且业务逻辑可能非幂等）。
- **业务逻辑异常**（如余额不足抛 `BizException`）：`put` 不执行，`LockCtx.close` 仍逆序放锁（try-with-resources 保证），锁不泄漏、不等待 TTL。

## 5. 对象传递

- `ctx.get` 返回的 POJO 一律用**方法返回值/局部变量**在块内流转，`ctx.put` 接收为参数。
- **禁止用 `ThreadLocal` 存业务 POJO**：虚拟线程下 `ThreadLocal` 跨挂起点语义微妙、难单测、清理易遗漏致内存泄漏。`ThreadLocal`/MDC 仅保留给 `AuthFilter` 的 `userId` 请求上下文（模块 §3.4），不用于数据 POJO。

## 6. POJO 约定

- 业务 POJO 由 `game-web` 定义，`game-data` 不持有任何业务实体类（`get`/`put` 全泛型 `<T>`，`JsonCodec` 不 import 业务域包）。
- POJO 形态（不可变 record 还是带 setter 的普通类）由业务域决定，`game-data` 不约束——`JsonCodec` 对 POJO 形态透明。
- `ctx.put` 是覆盖写：传入的 POJO 即"完整新值"，`game-data` 不做字段级合并、不读旧值做 diff（架构 §4.2 覆盖写语义）。

## 7. 集成测试

- 连**本地预起**的 Redis（`localhost:6379`）与 Mongo（`localhost:27017`），不引 Testcontainers、不引嵌入式实现。开发者按 `DEVELOPMENT.md` 预起服务；CI 预装 Redis/Mongo。
- 必覆盖的正确性用例（对齐并发修订 spec）：
  1. **回填竞态 #2**：实例 A 持锁写 v2、实例 B 无锁路径尝试回填——验证不存在无锁回填路径（`get` 必须持锁，未持锁直接抛 `LockLostException`，绝不回填 SET）。
  2. **写路径 miss 加载**：冷 key 首次 `get` → 验证从 Mongo 加载并回填 Redis，再 `get` 命中。
  3. **提交门控**：人为让锁 TTL 到期（或 mock `isHeld` 返回 false）→ `put` 抛 `LockLostException`，验证 Redis 未被写。
  4. **原子 Lua**：`put` 后 Redis 有新值且 `dirty` 集合含该 key；验证无独立 `SET`/`SADD` 可分别调用。
  5. **跨实体加锁顺序**：传入乱序 `[player, guild]`，验证实际按 `guild > player` 顺序 acquire。
  6. **all-or-nothing**：第二把锁 acquire 失败 → 验证第一把锁已释放、抛 `LockAcquireException`。
  7. **try-with-resources 放锁**：块内抛异常 → 验证锁已释放（不等 TTL）。
  8. **禁嵌套跨池**：`get` 的 miss 分支 `GET Redis → LOAD Mongo → SET Redis` 每步独立借还，验证不同时持有两池连接。
  9. **game-data 不认识业务实体**：ArchUnit/依赖检查禁止 `io.github.brick.data.*` 依赖业务域包。
  10. **resolveLock 命中本实体锁**：同类型多 id（持 `lock:player:1` + `lock:player:2`）时，人为让 `lock:player:1` 过期而 `lock:player:2` 仍持有，对 `player:1:*` key 调 `get`/`put` → 验证命中 `lock:player:1`（isHeld=false 抛 `LockLostException`），而非误取 `lock:player:2` 漏过门控。

## 8. 不在本次范围

- `game-web` 横切（`AuthFilter` 依赖 `RedisStore` 读 token、`BizLogger` 磁盘日志）——Plan C。
- `game-dbserver` 落盘编排（`FlushOrchestrator` 扫 dirty → `MongoStore` upsert → `SREM`、`FlushScheduler`、`GracefulShutdown`）——Plan C。`DirtyLedger`、`MongoStore` 在 `game-data` 提供，落盘编排由 Plan C 消费。
- POJO 集中托管 vs 分散到业务域——业务域阶段决定，本文件不锁死。
