# IdGenerator 发号原语实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `game-data` 落地号段模式发号原语 `IdGenerator`（Mongo counters 账本 + 进程内存发号）与 id 位型协议。

**Architecture:** 无爹实体（player/account/guild/全服邮件）的唯一 id 来源。位型 `id = (serverId << 32) | seq`（16 位服号 + 32 位流水，协议常量）；租段靠 Mongo `findOneAndUpdate($inc)` 单文档原子性，多进程必然拿到不重叠段；counters 是基础设施元数据，不进 Redis、不走 dirty 流水线。服号自检在**首次发号时**执行（懒，对齐 primitives §3.4 懒连接哲学——自检放启动装配期会炸掉不连 live Mongo 的 ApplicationContextRunner 装配测试，spec §5.2 措辞随 Task 7 修订）。

**Tech Stack:** Java 25、MongoDB Java Driver（原生 `MongoClient`，同 `MongoStore`）、JUnit 5 + AssertJ、Spring Boot `@ConfigurationProperties`、ArchUnit（既有 `DependencyRuleTest`）。

**Spec:** `.superpowers/specs/2026-09-24-id-generator-design.md`——本计划逐条实现其 §3（位型）、§4（号段机制）、§5（API/配置/自检）、§8（测试口径）、§9（修订清单）；其 §6/§7 属业务侧接入与容器建模，不在本计划范围。

## Global Constraints

- 位型：`id = (serverId << 32) | seq`；serverId ∈ [1, 65535]（0 非法）；seq ∈ [1, 0xFFFFFFFF]；**改位型 = 全量数据重写**（spec §3，与 `DataKeys.BUCKETS` 同性质）。
- counter 文档 `_id` = `idgen:{serverId}:{name}`，Mongo 集合名固定 `counters`；`name` 非空、不含 `:`、**不得为保留字 `boot`**（身份文档 `idgen:{serverId}:boot` 与实体 counter 同居 counters 集合，防撞名——spec §5 校验的补充，Task 7 修 spec）。
- 配置：`game.data.idgen.server-id` 默认 1，`@Min(1) @Max(65535)`；`game.data.idgen.segment-size` 默认 100，`@Min(1) @Max(1_000_000)`；`lease` 的 count ∈ [1, 1_000_000]（spec §5.1）。
- 服号自检：首次发号时执行一次；本库存在其他服号的 boot 标记 → 抛 `IllegalStateException`（fail-fast）；同服号幂等（spec §5.2 + 本计划懒自检修订）。
- **game-data 不认识业务实体**（primitives §6）：`idgen` 包零业务 import；`name` 只是调用方传的字符串。
- javadoc：中文、引 spec 章节、论证密度对齐 `DataKeys`/`MongoStore` 既有风格。
- 测试约定：`*Test` 纯 Java，`*IT` 继承 `LocalRedisMongo`（连本地预起 Mongo `localhost:27018`，读 `it-config.yaml`，每用例 drop 测试库）；不引 Testcontainers、不引新依赖。
- 模块命令：`./mvnw -pl game-data -am test -Dtest=<类> -Dsurefire.failIfNoSpecifiedTests=false`（单类）；`./mvnw test`（全量）。

---

### Task 1: IdScheme——位型与 counter 命名（纯 Java，无依赖）

**Files:**
- Create: `game-data/src/main/java/io/github/brick/data/idgen/IdScheme.java`
- Test: `game-data/src/test/java/io/github/brick/data/idgen/IdSchemeTest.java`

**Interfaces:**
- Consumes: 无（纯函数）。
- Produces: `IdScheme.compose(int serverId, long seq) → long`；`serverIdOf(long) → int`；`seqOf(long) → long`；`counterName(int serverId, String name) → String`；`bootName(int serverId) → String`；常量 `SERVER_ID_MAX = 65535`、`SEQ_MAX = 0xFFFFFFFFL`。后续所有任务的拼号与命名走这里。

- [ ] **Step 1: 写失败测试**

```java
package io.github.brick.data.idgen;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdSchemeTest {

    @Test
    void composeRoundTripsAndMatchesSpecExamples() {
        // 发号 spec §3 的两个例子数字直接进测试
        assertThat(IdScheme.compose(1, 1)).isEqualTo(4294967297L);
        assertThat(IdScheme.compose(3, 587)).isEqualTo(12884902475L);
        long id = IdScheme.compose(12345, 987654321L);
        assertThat(IdScheme.serverIdOf(id)).isEqualTo(12345);
        assertThat(IdScheme.seqOf(id)).isEqualTo(987654321L);
    }

    @Test
    void top16BitsStayZeroSoIdsStayShort() {
        // 15 位恒 0 是刻意的（spec §3）：最大合法 id 是 48 位正数，不会把数字撑长
        long id = IdScheme.compose(65535, 0xFFFFFFFFL);
        assertThat(id).isEqualTo(281474976710655L);   // 0xFFFFFFFFFFFF
        assertThat(id).isPositive();
    }

    @Test
    void rejectsServerIdOutOfRange() {
        assertThatThrownBy(() -> IdScheme.compose(0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdScheme.compose(65536, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSeqOutOfRange() {
        assertThatThrownBy(() -> IdScheme.compose(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdScheme.compose(1, 0x100000000L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void counterNameFollowsSpecFormat() {
        assertThat(IdScheme.counterName(1, "player")).isEqualTo("idgen:1:player");
        assertThat(IdScheme.bootName(1)).isEqualTo("idgen:1:boot");
    }

    @Test
    void rejectsBadCounterNames() {
        assertThatThrownBy(() -> IdScheme.counterName(1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdScheme.counterName(1, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdScheme.counterName(1, "a:b"))
                .isInstanceOf(IllegalArgumentException.class);
        // 保留字：业务实体名叫 boot 会撞服号身份文档（与 bootName 同串）
        assertThatThrownBy(() -> IdScheme.counterName(1, "boot"))
                .isInstanceOf(IllegalArgumentException.class)
                .withMessageContaining("boot");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-data -am test -Dtest=IdSchemeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`IdScheme` 不存在。

- [ ] **Step 3: 实现 IdScheme**

```java
package io.github.brick.data.idgen;

/**
 * id 位型与 counter 命名（协议常量，发号 spec §3）。位型一次性钉死：
 * {@code [符号位][15 位恒 0][16 位服号][32 位流水]}，id = {@code (serverId << 32) | seq}。
 * 改位型 = 全量数据重写，与 {@code DataKeys.BUCKETS} 同性质。
 *
 * <p>15 位恒 0 是刻意的：数字短——1 亿角色 × 每人 5 个数据 key，id 段每多 1 字节就是
 * GB 级 Redis 内存。占满的对比：流水扩到 47 位，1 服首号 = 2⁴⁷+1 = 140737488355329
 * （15 位数字），换来的容量永远用不到（发号 spec §3）。
 */
public final class IdScheme {

    /** 服号上限：16 位 = 65536 个服（发号 spec §3）。 */
    public static final int SERVER_ID_MAX = 65535;

    /** 流水上限：32 位 = 42.9 亿/服。达到即抛——实际到不了，防御性校验（发号 spec §3）。 */
    public static final long SEQ_MAX = 0xFFFFFFFFL;

    /** counter 保留字：服号身份文档（boot，spec §5.2）不走实体 counter 通道。 */
    private static final String RESERVED = "boot";

    private IdScheme() {
    }

    /** 拼装：id = (serverId << 32) | seq。serverId ∈ [1,65535]、seq ∈ [1,SEQ_MAX]。 */
    public static long compose(int serverId, long seq) {
        checkServerId(serverId);
        if (seq < 1 || seq > SEQ_MAX) {
            throw new IllegalArgumentException("seq 越界 [1," + SEQ_MAX + "]: " + seq);
        }
        return ((long) serverId << 32) | seq;
    }

    /** 拆出服号（高 16 位，符号位外）。 */
    public static int serverIdOf(long id) {
        return (int) (id >>> 32);
    }

    /** 拆出流水（低 32 位）。 */
    public static long seqOf(long id) {
        return id & 0xFFFFFFFFL;
    }

    /**
     * counter 文档 _id：{@code idgen:{serverId}:{name}}。name 非空、不含 {@code :}
     * （防拼名歧义）、不为保留字 {@code boot}（身份文档与实体 counter 同居 counters
     * 集合，保留字防撞名——发号 spec §4.1/§5）。
     */
    public static String counterName(int serverId, String name) {
        checkServerId(serverId);
        if (name == null || name.isEmpty() || name.indexOf(':') >= 0 || RESERVED.equals(name)) {
            throw new IllegalArgumentException(
                    "非法 name（非空、不含 ':'、非保留字 '" + RESERVED + "'）: " + name);
        }
        return "idgen:" + serverId + ":" + name;
    }

    /** 服号身份文档 _id：{@code idgen:{serverId}:boot}（发号 spec §5.2）。 */
    public static String bootName(int serverId) {
        checkServerId(serverId);
        return "idgen:" + serverId + ":boot";
    }

    private static void checkServerId(int serverId) {
        if (serverId < 1 || serverId > SERVER_ID_MAX) {
            throw new IllegalArgumentException("serverId 越界 [1," + SERVER_ID_MAX + "]: " + serverId);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -pl game-data -am test -Dtest=IdSchemeTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS，7 个用例全绿。

- [ ] **Step 5: Commit**

```bash
git add game-data/src/main/java/io/github/brick/data/idgen/IdScheme.java game-data/src/test/java/io/github/brick/data/idgen/IdSchemeTest.java
git commit -m "feat(data): id 位型与 counter 命名 IdScheme（发号 spec §3，协议常量）"
```

---

### Task 2: IdGenerator 接口 + SegmentIdGenerator 内存发号（纯 Java，stub 账本）

**Files:**
- Create: `game-data/src/main/java/io/github/brick/data/idgen/IdGenerator.java`
- Create: `game-data/src/main/java/io/github/brick/data/idgen/SegmentLeaser.java`
- Create: `game-data/src/main/java/io/github/brick/data/idgen/ServerIdGuard.java`
- Create: `game-data/src/main/java/io/github/brick/data/idgen/SegmentIdGenerator.java`
- Test: `game-data/src/test/java/io/github/brick/data/idgen/SegmentIdGeneratorTest.java`

**Interfaces:**
- Consumes: `IdScheme.compose/counterName`（Task 1）。
- Produces:
  - `interface IdGenerator { long next(String name); long lease(String name, int count); }`
  - `interface SegmentLeaser { long lease(String counterName, int count); }`——counter 不存在时 upsert、返回新 seq（段末位）、首段 = [1, count]
  - `interface ServerIdGuard { void checkAndMark(int serverId); }`——幂等，发现他服标记抛异常
  - `class SegmentIdGenerator implements IdGenerator`，构造 `(int serverId, int segmentSize, SegmentLeaser leaser, ServerIdGuard guard)`，常量 `MAX_LEASE = 1_000_000`
  - Task 3/4/6 按这些签名实现与装配。

- [ ] **Step 1: 写失败测试**

```java
package io.github.brick.data.idgen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SegmentIdGeneratorTest {

    /** 按调用顺序返回预设段末位的 stub 账本。 */
    private static SegmentLeaser leaser(long... ends) {
        AtomicInteger i = new AtomicInteger();
        return (counter, count) -> ends[i.getAndIncrement()];
    }

    @Test
    void nextIssuesSeqWithinLeasedSegment() {
        SegmentIdGenerator g = new SegmentIdGenerator(1, 100, leaser(100L), s -> {});
        assertThat(g.next("player")).isEqualTo(IdScheme.compose(1, 1));   // 4294967297
        assertThat(g.next("player")).isEqualTo(IdScheme.compose(1, 2));
    }

    @Test
    void nextReleasesNewSegmentOnExhaustion() {
        // 段长 2：首租返回 2（段 [1,2]），第二租返回 6（段 [5,6]）
        SegmentIdGenerator g = new SegmentIdGenerator(1, 2, leaser(2L, 6L), s -> {});
        assertThat(g.next("g")).isEqualTo(IdScheme.compose(1, 1));
        assertThat(g.next("g")).isEqualTo(IdScheme.compose(1, 2));
        assertThat(g.next("g")).isEqualTo(IdScheme.compose(1, 5));
    }

    @Test
    void namesHaveIndependentSegments() {
        // counter 每 entity 独立、流水都从 1 起（发号 spec §3 表格）
        SegmentIdGenerator g = new SegmentIdGenerator(1, 100, leaser(100L, 50L), s -> {});
        g.next("player");
        assertThat(g.next("guild")).isEqualTo(IdScheme.compose(1, 1));
    }

    @Test
    void leaseReturnsSegmentStart() {
        SegmentIdGenerator g = new SegmentIdGenerator(1, 100, leaser(100000L), s -> {});
        assertThat(g.lease("mail", 100000)).isEqualTo(IdScheme.compose(1, 1));
    }

    @Test
    void firstCallTriggersServerIdCheckExactlyOnce() {
        List<Integer> marks = new CopyOnWriteArrayList<>();
        SegmentIdGenerator g = new SegmentIdGenerator(1, 100, leaser(100L), marks::add);
        g.next("player");
        g.next("player");
        assertThat(marks).containsExactly(1);
    }

    @Test
    void concurrentNextWithinSegmentNeverDuplicates() throws Exception {
        // 96 个号 < 段长 100：全程一段内发完，stub 账本只备了一个返回值——
        // 若 CAS/锁有误触发二次租段，stub 越界直接炸，测试自然失败
        SegmentIdGenerator g = new SegmentIdGenerator(1, 100, leaser(100L), s -> {});
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    for (int n = 0; n < 12; n++) ids.add(g.next("player"));
                    return null;
                }));
            }
            for (var f : futures) f.get(10, TimeUnit.SECONDS);
        }
        assertThat(ids).hasSize(96);
    }

    @Test
    void rejectsBadLeaseCount() {
        SegmentIdGenerator g = new SegmentIdGenerator(1, 100, leaser(100L), s -> {});
        assertThatThrownBy(() -> g.lease("mail", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> g.lease("mail", SegmentIdGenerator.MAX_LEASE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBadSegmentSize() {
        assertThatThrownBy(() -> new SegmentIdGenerator(1, 0, leaser(1L), s -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SegmentIdGenerator(1, SegmentIdGenerator.MAX_LEASE + 1, leaser(1L), s -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-data -am test -Dtest=SegmentIdGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，接口与实现类不存在。

- [ ] **Step 3: 写三个接口 + SegmentIdGenerator**

`IdGenerator.java`：

```java
package io.github.brick.data.idgen;

/**
 * 发号原语（发号 spec §5）：无爹实体（player/account/guild/全服邮件…）的 id 来源。
 * {@code name} 由业务侧传——{@code player}/{@code guild} 属业务词汇，game-data 不认识
 * 业务实体（primitives §6，同 {@code EntityPriorities} 的边界思路）。
 *
 * <p>发号不依赖 Redis 实体锁：号段原子性由 Mongo 账本保证，{@code next} 锁内锁外皆可
 * 调用（创角口径：锁外拿号 → lockAll → 锁内写，发号 spec §6）。
 */
public interface IdGenerator {

    /** 发一个 id。同 name 进程内严格递增、进程间趋势递增（发号 spec §4.2）。 */
    long next(String name);

    /**
     * 批量租号段 [start, start+count)，返回 start。绕过本地段直租账本，全服邮件用
     * （发号 spec §4.2）；本地段未发完的剩余作废——空洞无害，跳号不是重号。
     */
    long lease(String name, int count);
}
```

`SegmentLeaser.java`：

```java
package io.github.brick.data.idgen;

/**
 * 账本租段接口（发号 spec §4）。实现负责原子性：并发 {@code lease} 必然返回不重叠的段。
 */
public interface SegmentLeaser {

    /**
     * counter 记账 {@code seq += count}，返回新 seq（段末位）。counter 不存在时 upsert，
     * 新 seq = count，即首段 = [1, count]——流水从 1 起（发号 spec §3）。
     */
    long lease(String counterName, int count);
}
```

`ServerIdGuard.java`：

```java
package io.github.brick.data.idgen;

/**
 * 服号自检（发号 spec §5.2）：本库已存在其他服号的 boot 标记 = 配置漂移，fail-fast。
 * 幂等：同服号重复调用无副作用（多进程同服并发首号时各自 upsert 同一文档）。
 */
public interface ServerIdGuard {

    void checkAndMark(int serverId);
}
```

`SegmentIdGenerator.java`：

```java
package io.github.brick.data.idgen;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 号段模式发号（发号 spec §4）：进程向账本整段租号，内存顺次发。租段靠单文档原子性，
 * 多进程必然不重叠；进程崩溃丢段 = 空洞，无害。
 *
 * <p>并发形态：段内发号走 CAS（无锁）；段耗尽进 per-counter 互斥块串行租段，
 * double-check 后再租——排队期间已被别人租好就直接出去继续 CAS，不锁其他实体。
 *
 * <p>服号自检在首次发号时执行一次（懒，对齐 primitives §3.4 懒连接哲学：装配测试与
 * 不发号的模块不被迫触达 Mongo；真实部署中首次发号必然在最早期，fail-fast 语义不变，
 * 见 {@code MongoServerIdGuard}）。
 */
public final class SegmentIdGenerator implements IdGenerator {

    /** lease 单次上限，与配置校验的段长上限一致（发号 spec §5.1）。 */
    public static final int MAX_LEASE = 1_000_000;

    private final int serverId;
    private final int segmentSize;
    private final SegmentLeaser leaser;
    private final ServerIdGuard guard;
    private final ConcurrentHashMap<String, Segment> segments = new ConcurrentHashMap<>();
    private final AtomicBoolean checked = new AtomicBoolean();

    public SegmentIdGenerator(int serverId, int segmentSize,
                              SegmentLeaser leaser, ServerIdGuard guard) {
        if (segmentSize < 1 || segmentSize > MAX_LEASE) {
            throw new IllegalArgumentException("segmentSize 越界 [1," + MAX_LEASE + "]: " + segmentSize);
        }
        this.serverId = serverId;
        this.segmentSize = segmentSize;
        this.leaser = leaser;
        this.guard = guard;
    }

    @Override
    public long next(String name) {
        String counter = IdScheme.counterName(serverId, name);
        ensureChecked();
        Segment seg = segments.computeIfAbsent(counter, k -> new Segment());
        while (true) {
            long seq = seg.next.get();
            if (seq <= seg.end) {
                if (seg.next.compareAndSet(seq, seq + 1)) {
                    return IdScheme.compose(serverId, seq);
                }
                continue;   // 被并发抢走，重读
            }
            synchronized (seg) {   // 段耗尽：per-counter 串行租段
                if (seg.next.get() > seg.end) {   // double-check：排队期间可能已被租好
                    long end = leaser.lease(counter, segmentSize);
                    seg.end = end;
                    seg.next.set(end - segmentSize + 1);
                }
            }
        }
    }

    @Override
    public long lease(String name, int count) {
        if (count < 1 || count > MAX_LEASE) {
            throw new IllegalArgumentException("count 越界 [1," + MAX_LEASE + "]: " + count);
        }
        String counter = IdScheme.counterName(serverId, name);
        ensureChecked();
        long end = leaser.lease(counter, count);
        return IdScheme.compose(serverId, end - count + 1);
    }

    private void ensureChecked() {
        if (checked.get()) {
            return;
        }
        synchronized (this) {
            if (!checked.get()) {
                guard.checkAndMark(serverId);
                checked.set(true);
            }
        }
    }

    /**
     * 本地段：{@code next} 是下一个可发流水、{@code end} 是段末位（含）。
     * 初值 next=1、end=0 即空段（1 > 0），首次发号由它触发租段。
     */
    private static final class Segment {
        final AtomicLong next = new AtomicLong(1);
        volatile long end;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -pl game-data -am test -Dtest=SegmentIdGeneratorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS，9 个用例全绿（含 96 号并发无重复）。

- [ ] **Step 5: Commit**

```bash
git add game-data/src/main/java/io/github/brick/data/idgen/ game-data/src/test/java/io/github/brick/data/idgen/
git commit -m "feat(data): IdGenerator 号段发号原语——接口三件套 + SegmentIdGenerator 内存发号（发号 spec §4/§5）"
```

---

### Task 3: MongoSegmentLeaser——Mongo counters 账本（IT）

**Files:**
- Create: `game-data/src/main/java/io/github/brick/data/idgen/MongoSegmentLeaser.java`
- Test: `game-data/src/test/java/io/github/brick/data/idgen/MongoSegmentLeaserIT.java`

**Interfaces:**
- Consumes: `SegmentLeaser.lease(String counterName, int count)`（Task 2）；`IdScheme.counterName`（Task 1）。
- Produces: `class MongoSegmentLeaser implements SegmentLeaser`，构造 `(MongoClient client, String dbName)`，常量 `COLLECTION = "counters"`。Task 4/5/6 按此使用。

- [ ] **Step 1: 写失败 IT**

```java
package io.github.brick.data.idgen;

import io.github.brick.data.LocalRedisMongo;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class MongoSegmentLeaserIT extends LocalRedisMongo {

    private MongoSegmentLeaser leaser() {
        return new MongoSegmentLeaser(mongo, MONGO_DB);
    }

    @Test
    void firstLeaseStartsSegmentAtOne() {
        // 首租 upsert：新 seq = count，段 = [1, count]——流水从 1 起（发号 spec §3）
        assertThat(leaser().lease(IdScheme.counterName(1, "player"), 100)).isEqualTo(100L);
    }

    @Test
    void leasesAccumulateOnSameCounter() {
        MongoSegmentLeaser l = leaser();
        l.lease(IdScheme.counterName(1, "player"), 100);
        assertThat(l.lease(IdScheme.counterName(1, "player"), 7)).isEqualTo(107L);
    }

    @Test
    void countersAreIndependent() {
        MongoSegmentLeaser l = leaser();
        l.lease(IdScheme.counterName(1, "player"), 100);
        assertThat(l.lease(IdScheme.counterName(1, "guild"), 50)).isEqualTo(50L);
    }

    @Test
    void counterDocShapeFollowsSpec() {
        leaser().lease(IdScheme.counterName(1, "player"), 100);
        Document doc = db().getCollection("counters")
                .find(new Document("_id", "idgen:1:player")).first();
        assertThat(doc).isNotNull();
        assertThat(doc.getLong("seq")).isEqualTo(100L);
    }

    @Test
    void concurrentLeasesNeverOverlap() throws Exception {
        // 两个实例各 100 次并发租段（count=10）：原子 $inc 下 200 个段末位
        // 必然恰好是 10,20,…,2000——排序后公差恒 10 即证明互不重叠也无跳变
        MongoSegmentLeaser a = leaser();
        MongoSegmentLeaser b = leaser();
        List<Long> ends = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (MongoSegmentLeaser l : List.of(a, b)) {
                fs.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 100; i++) {
                        synchronized (ends) { ends.add(l.lease("idgen:1:player", 10)); }
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(ends).hasSize(200);
        ends.sort(Long::compare);
        for (int i = 0; i < ends.size(); i++) {
            assertThat(ends.get(i)).isEqualTo(10L * (i + 1));
        }
    }
}
```

- [ ] **Step 2: 跑 IT 确认失败**

Run: `./mvnw -pl game-data -am test -Dtest=MongoSegmentLeaserIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`MongoSegmentLeaser` 不存在。

- [ ] **Step 3: 实现 MongoSegmentLeaser**

```java
package io.github.brick.data.idgen;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;

/**
 * Mongo counters 账本（发号 spec §4.1）。
 *
 * <p>counters 是基础设施元数据，**不是实体数据**：不进 Redis、不走 CommitLua/dirty
 * 流水线、不受 flushdb 影响，{@code game-dbserver} 对它无感知。账本只增不减，因此
 * 发号器不存在恢复链——Redis 丢什么都与发号无关。这同时是否决 Redis INCR 方案的理由
 * （发号 spec §2：Redis 丢账本从 Mongo max 回种有 1~3s 落盘滞后窗口，重号即覆盖）。
 *
 * <p>独立成类而非塞进 {@code MongoStore}：MongoStore 只管 {@code {entity}:{field}}
 * 集合上的 {@code {_id, v}} 实体文档（collection 由 DataKeys 派生）；counters 的
 * 集合名固定、文档形态 {@code {_id, seq}}，职责不同。
 */
public final class MongoSegmentLeaser implements SegmentLeaser {

    /** 集合名固定（发号 spec §4.1）。 */
    public static final String COLLECTION = "counters";

    private final MongoCollection<Document> counters;

    public MongoSegmentLeaser(MongoClient client, String dbName) {
        this.counters = client.getDatabase(dbName).getCollection(COLLECTION, Document.class);
    }

    /**
     * 原子租段：{@code findOneAndUpdate($inc: {seq: count})} 返回新值（段末位）。
     * Mongo 单文档修改排队执行，并发租段物理上拿不到重叠段（发号 spec §2）；
     * counter 不存在时 upsert，新值 = count，段 = [1, count]。
     */
    @Override
    public long lease(String counterName, int count) {
        Document after = counters.findOneAndUpdate(
                new Document("_id", counterName),
                new Document("$inc", new Document("seq", (long) count)),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return after.getLong("seq");
    }
}
```

- [ ] **Step 4: 跑 IT 确认通过**

Run: `./mvnw -pl game-data -am test -Dtest=MongoSegmentLeaserIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS，5 个用例全绿（含 200 段并发不重叠）。

- [ ] **Step 5: Commit**

```bash
git add game-data/src/main/java/io/github/brick/data/idgen/MongoSegmentLeaser.java game-data/src/test/java/io/github/brick/data/idgen/MongoSegmentLeaserIT.java
git commit -m "feat(data): Mongo counters 账本 MongoSegmentLeaser——原子租段（发号 spec §4.1）"
```

---

### Task 4: MongoServerIdGuard——服号自检（IT）

**Files:**
- Create: `game-data/src/main/java/io/github/brick/data/idgen/MongoServerIdGuard.java`
- Test: `game-data/src/test/java/io/github/brick/data/idgen/MongoServerIdGuardIT.java`

**Interfaces:**
- Consumes: `ServerIdGuard.checkAndMark(int serverId)`（Task 2）；`IdScheme.bootName`（Task 1）。
- Produces: `class MongoServerIdGuard implements ServerIdGuard`，构造 `(MongoClient client, String dbName)`。Task 5/6 按此使用。

- [ ] **Step 1: 写失败 IT**

```java
package io.github.brick.data.idgen;

import io.github.brick.data.LocalRedisMongo;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MongoServerIdGuardIT extends LocalRedisMongo {

    private MongoServerIdGuard guard() {
        return new MongoServerIdGuard(mongo, MONGO_DB);
    }

    @Test
    void emptyLibraryPassesAndMarks() {
        guard().checkAndMark(1);
        Document boot = db().getCollection("counters")
                .find(new Document("_id", "idgen:1:boot")).first();
        assertThat(boot).isNotNull();
        assertThat(boot.getString("bootAt")).isNotBlank();
    }

    @Test
    void rejectsForeignServerId() {
        guard().checkAndMark(1);
        // 同一库出现第二个服号 = 配置漂移（发号 spec §5.2），fail-fast
        assertThatThrownBy(() -> guard().checkAndMark(2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idgen:1:boot")
                .hasMessageContaining("server-id=2");
    }

    @Test
    void sameServerIdIsIdempotent() {
        MongoServerIdGuard g = guard();
        g.checkAndMark(1);
        g.checkAndMark(1);   // 不抛——同服多进程并发首号各自 upsert 同一文档
        assertThat(db().getCollection("counters")
                .countDocuments(new Document("_id", java.util.regex.Pattern.compile("^idgen:\\d+:boot$"))))
                .isEqualTo(1);
    }

    @Test
    void concurrentSameServerMarkIsSafe() throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                fs.add(pool.submit(() -> { go.await(); guard().checkAndMark(1); return null; }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        // 8 个并发标记后恰好 1 个 boot 文档、无异常
        assertThat(db().getCollection("counters")
                .countDocuments(new Document("_id", java.util.regex.Pattern.compile("^idgen:\\d+:boot$"))))
                .isEqualTo(1);
    }
}
```

- [ ] **Step 2: 跑 IT 确认失败**

Run: `./mvnw -pl game-data -am test -Dtest=MongoServerIdGuardIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败，`MongoServerIdGuard` 不存在。

- [ ] **Step 3: 实现 MongoServerIdGuard**

```java
package io.github.brick.data.idgen;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * 服号自检（发号 spec §5.2）：本库 counters 已有**其他**服号的 boot 标记 = 同一库被
 * 两个服号交替使用，uid 位型前提被破坏，fail-fast（启动语义：首次发号必然在部署最早期）。
 *
 * <p>自检时机在**首次发号时**而非启动装配期——对齐懒连接哲学（primitives §3.4，
 * Redisson {@code setLazyInitialization(true)} 的同款取舍）：装配测试
 * （ApplicationContextRunner，不连 live Mongo）与不发号的模块不被迫触达 Mongo。
 *
 * <p>两个**独立**的服（各自独立的 Mongo）被运维配了同一个号，这里检测不到——
 * 不同库互不可见，这层靠运维纪律，代码防不住（发号 spec §5.2 诚实记录）。
 */
public final class MongoServerIdGuard implements ServerIdGuard {

    private static final Pattern BOOT = Pattern.compile("^idgen:\\d+:boot$");

    private final MongoCollection<Document> counters;

    public MongoServerIdGuard(MongoClient client, String dbName) {
        this.counters = client.getDatabase(dbName).getCollection("counters", Document.class);
    }

    /** 幂等：同服号重复调用只 upsert 同一文档（bootAt 用 $setOnInsert，首次时间不被覆盖）。 */
    @Override
    public void checkAndMark(int serverId) {
        String mine = IdScheme.bootName(serverId);
        for (Document d : counters.find(new Document("_id", BOOT))) {
            if (!mine.equals(d.getString("_id"))) {
                throw new IllegalStateException("""
                        本 Mongo 库已被其他服号标记: %s，本进程 server-id=%d。
                        同一库被两个服号交替使用会破坏 uid 位型（发号 spec §3/§5.2），
                        请核对 game.data.idgen.server-id 配置。"""
                        .formatted(d.getString("_id"), serverId));
            }
        }
        counters.updateOne(
                new Document("_id", mine),
                new Document("$setOnInsert", new Document("bootAt", Instant.now().toString())),
                new UpdateOptions().upsert(true));
    }
}
```

- [ ] **Step 4: 跑 IT 确认通过**

Run: `./mvnw -pl game-data -am test -Dtest=MongoServerIdGuardIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS，4 个用例全绿。

- [ ] **Step 5: Commit**

```bash
git add game-data/src/main/java/io/github/brick/data/idgen/MongoServerIdGuard.java game-data/src/test/java/io/github/brick/data/idgen/MongoServerIdGuardIT.java
git commit -m "feat(data): 服号自检 MongoServerIdGuard——boot 标记 fail-fast（发号 spec §5.2）"
```

---

### Task 5: SegmentIdGenerator 整体 IT——并发不重号 / 重建续发 / 批量租段

**Files:**
- Test: `game-data/src/test/java/io/github/brick/data/idgen/IdGeneratorIT.java`

**Interfaces:**
- Consumes: `SegmentIdGenerator(serverId, segmentSize, leaser, guard)`、`MongoSegmentLeaser(MongoClient, String)`、`MongoServerIdGuard(MongoClient, String)`（Task 2/3/4）。
- Produces: 无新接口——这是发号 spec §8 用例 4/5/6（并发租段不重叠、lease、重建续发）的端到端验证。

- [ ] **Step 1: 写失败 IT**

```java
package io.github.brick.data.idgen;

import io.github.brick.data.LocalRedisMongo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class IdGeneratorIT extends LocalRedisMongo {

    private SegmentIdGenerator gen(int serverId, int segmentSize) {
        return new SegmentIdGenerator(serverId, segmentSize,
                new MongoSegmentLeaser(mongo, MONGO_DB),
                new MongoServerIdGuard(mongo, MONGO_DB));
    }

    @Test
    void twoGeneratorsConcurrentlyNeverDuplicate() throws Exception {
        // 发号 spec §8.4：两实例（同库同 counter）并发各发 1000 个，段长 100 → 各租 10 段
        SegmentIdGenerator a = gen(1, 100);
        SegmentIdGenerator b = gen(1, 100);
        Set<Long> all = ConcurrentHashMap.newKeySet();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> fs = new ArrayList<>();
            for (SegmentIdGenerator g : List.of(a, b)) {
                fs.add(pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 1000; i++) all.add(g.next("player"));
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        }
        assertThat(all).hasSize(2000);   // 零重复 = 账本租段原子性的端到端证明
    }

    @Test
    void rebuiltInstanceContinuesWithoutReissue() {
        // 发号 spec §8.6：丢段只产生空洞（跳号），绝不重号
        SegmentIdGenerator a = gen(1, 100);
        a.next("guild");
        a.next("guild");                 // 本地段 [1,100] 只发了 2 个就"崩溃"丢弃
        SegmentIdGenerator b = gen(1, 100);   // 重建实例：租新段
        assertThat(b.next("guild")).isEqualTo(IdScheme.compose(1, 101));
    }

    @Test
    void leaseBypassesLocalSegment() {
        // 发号 spec §8.5：lease 直租账本，与本地段无关
        SegmentIdGenerator g = gen(1, 100);
        g.next("mail");                   // 本地段 [1,100]
        long start = g.lease("mail", 100000);
        assertThat(start).isEqualTo(IdScheme.compose(1, 101));   // 新段 [101,100100]
        Document seqDoc = db().getCollection("counters")
                .find(new Document("_id", "idgen:1:mail")).first();
        assertThat(seqDoc.getLong("seq")).isEqualTo(100100L);
    }

    @Test
    void foreignServerIdFailsFastOnFirstIssue() {
        // 自检挂进首次发号路径（懒）：配错服号 → 首个 next 即抛，静默发号不存在
        SegmentIdGenerator one = gen(1, 100);
        one.next("player");
        SegmentIdGenerator two = gen(2, 100);   // 同库不同服号
        assertThatThrownBy(() -> two.next("player"))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

（`assertThatThrownBy` 已由文件头 `import static org.assertj.core.api.Assertions.assertThat` 的同族 import 覆盖——若编译器报缺失，补 `import static org.assertj.core.api.Assertions.assertThatThrownBy;`。）

- [ ] **Step 2: 跑 IT 确认通过**

本任务不适用"先红"：被测实现已由 Task 2~4 落地，此 IT 验证的是它们的**组合行为**。

Run: `./mvnw -pl game-data -am test -Dtest=IdGeneratorIT -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS，4 个用例全绿。任何失败都意味着 Task 2~4 的实现有误——**修实现，不是改测试**。

- [ ] **Step 3: Commit**

```bash
git add game-data/src/test/java/io/github/brick/data/idgen/IdGeneratorIT.java
git commit -m "test(data): IdGenerator 端到端 IT——两实例并发 2000 号零重复、重建续发、lease 直租（发号 spec §8）"
```

---

### Task 6: 配置与自动装配（含 @Valid 级联的坑）

**Files:**
- Modify: `game-data/src/main/java/io/github/brick/data/config/DataProperties.java`（新增 `Idgen` 嵌套类 + `@Valid` 级联，**不加 @Valid 嵌套校验静默失效**）
- Modify: `game-data/src/main/java/io/github/brick/data/config/DataAutoConfiguration.java`（新增 `idGenerator` @Bean）
- Modify: `game-data/src/test/java/io/github/brick/data/config/DataAutoConfigurationTest.java`（补断言 + 4 个新用例）

**Interfaces:**
- Consumes: `SegmentIdGenerator/MongoSegmentLeaser/MongoServerIdGuard` 构造（Task 2/3/4）。
- Produces: Spring bean `IdGenerator`（`@ConditionalOnMissingBean`，业务可覆盖）；配置 `game.data.idgen.server-id`（默认 1）、`game.data.idgen.segment-size`（默认 100）。

- [ ] **Step 1: 在 DataAutoConfigurationTest 写失败用例**

在现有测试类中**修改** `wiresAllPrimitives`（补一行断言）并**新增**以下用例：

```java
	// wiresAllPrimitives 补一行（在 hasSingleBean(JsonCodec.class) 之前）：
	//   .hasSingleBean(IdGenerator.class)

	@Test
	void idgenDefaultsBound() {
		runner.run(ctx -> {
			DataProperties.Idgen idgen = ctx.getBean(DataProperties.class).getIdgen();
			assertThat(idgen.getServerId()).isEqualTo(1);        // 大区制默认 1 号服
			assertThat(idgen.getSegmentSize()).isEqualTo(100);
		});
	}

	@Test
	void idgenValuesBound() {
		runner.withPropertyValues(
						"game.data.idgen.serverId=7",
						"game.data.idgen.segmentSize=10000")
				.run(ctx -> {
					DataProperties.Idgen idgen = ctx.getBean(DataProperties.class).getIdgen();
					assertThat(idgen.getServerId()).isEqualTo(7);
					assertThat(idgen.getSegmentSize()).isEqualTo(10000);
				});
	}

	// 服号越界必须启动失败——协议常量的显式设限不能只活在注释里
	@Test
	void rejectsServerIdBelowFloor() {
		runner.withPropertyValues("game.data.idgen.serverId=0")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void rejectsServerIdAboveCeiling() {
		runner.withPropertyValues("game.data.idgen.serverId=65536")
				.run(ctx -> assertThat(ctx).hasFailed());
	}

	@Test
	void rejectsSegmentSizeBelowFloor() {
		runner.withPropertyValues("game.data.idgen.segmentSize=0")
				.run(ctx -> assertThat(ctx).hasFailed());
	}
```

同时在文件头补 import：`io.github.brick.data.idgen.IdGenerator`（`DataProperties.Idgen` 经 `DataProperties` 访问，无需单独 import）。

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-data -am test -Dtest=DataAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译失败（`getIdgen()` 不存在）。

- [ ] **Step 3: DataProperties 加 Idgen 嵌套配置**

在 `DataProperties` 的字段区（`lockLeaseSeconds` 之后）加：

```java
    /** 发号器配置（发号 spec §5.1）。嵌套类校验必须 @Valid 级联，否则 Min/Max 静默失效。 */
    @Valid
    private final Idgen idgen = new Idgen();

    public Idgen getIdgen() { return idgen; }

    /**
     * id 位型与租段参数。server-id 是协议常量级决定——进 id 高 16 位，改它 = 全量数据
     * 重写（发号 spec §3）；segment-size 应对突发（开服买量窗口临时调大，发号 spec §4.2）。
     */
    public static class Idgen {

        /** 服号。大区制不改默认值（1 号服）；滚服制开新服时改，且两服不得同库同号（自检兜底）。 */
        @Min(1) @Max(65535)
        private int serverId = 1;

        /** 租段步长。低频顶层实体 100 足够（发号 spec §4.2 的突发口径）。 */
        @Min(1) @Max(1_000_000)
        private int segmentSize = 100;

        public int getServerId() { return serverId; }
        public void setServerId(int v) { serverId = v; }
        public int getSegmentSize() { return segmentSize; }
        public void setSegmentSize(int v) { segmentSize = v; }
    }
```

同时在 import 区补 `jakarta.validation.Valid`。

- [ ] **Step 4: DataAutoConfiguration 加 @Bean**

在 `lockScope` 方法之后加（import 区补 `io.github.brick.data.idgen.IdGenerator` 等 4 个 idgen 类）：

```java
    /**
     * 发号器（发号 spec §4/§5）：号段模式，Mongo counters 账本。发号不依赖锁，
     * 锁内锁外皆可调用。bean 创建只做 new（懒），不触达 Mongo——服号自检挂在首次
     * 发号路径（对齐 primitives §3.4 懒连接，保证装配测试无需 live Mongo）。
     */
    @Bean
    @ConditionalOnMissingBean
    IdGenerator idGenerator(MongoClient c, DataProperties p) {
        DataProperties.Idgen cfg = p.getIdgen();
        return new SegmentIdGenerator(cfg.getServerId(), cfg.getSegmentSize(),
                new MongoSegmentLeaser(c, p.getMongoDb()),
                new MongoServerIdGuard(c, p.getMongoDb()));
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -pl game-data -am test -Dtest=DataAutoConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS。重点确认：三个 rejects 用例真失败（证明 `@Valid` 级联生效——漏加 `@Valid` 时这三个用例会反过来红）；`wiresAllPrimitives` 依然绿（bean 创建无 Mongo 副作用）。

- [ ] **Step 6: 跑 game-data 全量测试防回归**

Run: `./mvnw -pl game-data -am test`
Expected: 全绿（现有 LockCtxIT/LockScopeIT/MongoStoreIT 等 IT 需本地预起 Redis/Mongo，见 DEVELOPMENT.md）。

- [ ] **Step 7: Commit**

```bash
git add game-data/src/main/java/io/github/brick/data/config/DataProperties.java game-data/src/main/java/io/github/brick/data/config/DataAutoConfiguration.java game-data/src/test/java/io/github/brick/data/config/DataAutoConfigurationTest.java
git commit -m "feat(data): 发号器装配——game.data.idgen.* 配置（@Valid 级联）+ IdGenerator bean（发号 spec §5.1）"
```

---

### Task 7: javadoc / spec 修订 / 全量验证（发号 spec §9 清单）

**Files:**
- Modify: `game-data/src/main/java/io/github/brick/data/store/DataKeys.java`（类 javadoc 补指向）
- Modify: `.superpowers/specs/00-architecture-overview.md`（§4.1 补一句）
- Modify: `.superpowers/specs/2026-08-11-game-data-primitives-design.md`（§3 包结构补 idgen/）
- Modify: `.superpowers/specs/2026-09-24-id-generator-design.md`（三处口径修订，见 Step 2）

**Interfaces:**
- Consumes: 无代码依赖——纯文档收尾。
- Produces: spec 与实现一致；`DependencyRuleTest` 的既有规则（`io.github.brick.data..` 不得依赖 web/contract/dbserver）天然覆盖 `idgen` 包，无需新增用例（发号 spec §8.8 的措辞同步修订）。

- [ ] **Step 1: DataKeys javadoc 补指向**

在 `DataKeys` 类 javadoc 的「桶前缀」段落之后新起一段：

```java
 * <p><b>id 的位型与生成</b>另有唯一出处：发号 spec（{@code 2026-09-24-id-generator-design.md}）
 * §3——{@code (serverId << 32) | seq}，与 {@code BUCKETS} 同属协议常量，改即全量数据重写。
```

- [ ] **Step 2: 三份 spec 修订**

1. `00-architecture-overview.md` §4.1 的 key 约定段落后补一句（按该节文风融入）：
   > `{id}` 的位型与生成方式的唯一出处见发号 spec（2026-09-24）§3——16 位服号 + 32 位流水，协议常量级决定。

2. `2026-08-11-game-data-primitives-design.md` §3 包结构清单（`codec/` 行之后）补：
   ```
   idgen/    IdGenerator        号段发号（next/lease）：无爹实体的 id 来源（发号 spec 2026-09-24）
             SegmentIdGenerator 内存段发号 + MongoSegmentLeaser/MongoServerIdGuard（counters 账本）
   ```

3. `2026-09-24-id-generator-design.md` 三处修订（实现中钉死的口径）：
   - §5 参数校验句补：**保留字 `boot` 禁用**（身份文档与实体 counter 同居 counters 集合，防撞名）；
   - §5.2 自检时机改为：**首次发号时执行一次（懒）**，并记录理由——装配测试（ApplicationContextRunner）不连 live Mongo 的既有约束 + primitives §3.4 懒连接哲学；fail-fast 语义不变（首次发号必然在部署最早期）；
   - §8 测试口径微调：第 7 条"server-id 自检"的断言改为"首次发号失败"；第 8 条（ArchUnit）改为"既有 `DependencyRuleTest` 的 `io.github.brick.data..` 规则已覆盖 idgen 包，无新增"。

- [ ] **Step 3: 全量验证**

Run: `./mvnw test`
Expected: 四个模块全绿。

- [ ] **Step 4: Commit**

```bash
git add game-data/src/main/java/io/github/brick/data/store/DataKeys.java .superpowers/specs/
git commit -m "docs(spec): 发号落地后的 spec 修订——DataKeys 指向、primitives 包结构、boot 保留字与懒自检口径"
```
