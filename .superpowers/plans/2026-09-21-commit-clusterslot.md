# 提交协议桶化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把脏标记从全局 `{dirty}` 集合改为与数据 key 同 slot 的分桶集合（K=4096），让提交侧 Lua 与落盘协议在 Redis Cluster 下合法；运行时仍单机。

**Architecture:** `DataKeys` 产出带 `{bNNNN}` hash tag 的数据 key（桶号 = hash(entity:id) mod 4096）；`CommitLua` 的 KEYS[2] 改为按 key 推导的同桶集合（SCRIPT 文本零改动）；`DirtyLedger` 重写为按桶协议（K 桶异步扇出）；`FlushOrchestrator` 改两段流水线（逐桶 drain → 逐桶 MGET → 跨桶聚 Mongo 批）。

**Tech Stack:** Java 25 / Spring Boot 4.1 / Redisson 4.6.1（`RScript.evalAsync`、`RSet` 异步接口）/ MongoDB Driver 5.9 / JUnit 5 + AssertJ / Maven 多模块。

**Spec:** `.superpowers/specs/2026-09-21-commit-clusterslot-design.md`（提交桶化设计，下文称「设计」）——本计划从它推导，执行者须两份都读。

## Global Constraints

- **运行时仍 `useSingleServer()`**：`DataAutoConfiguration`、`DataProperties`、`DbServerConfiguration` 零改动（设计 §前置一）。
- **K=4096 钉死**：`DataKeys.BUCKETS` 常量；**`::` 保留给 overlay 基础设施键**（设计 §3.1）。
- **`CommitLua.SCRIPT` 与 `DRAIN_SCRIPT` 文本一字不改**（设计 §4/§5.1）。
- **零改动清单**：`MongoStore`、`RedisStore`、`RedissonLockCtx`、`RedissonLockScope`、`FlushScheduler`、`GracefulShutdown`（`DirtyLedger` 聚合方法签名保持，实现换内部）。
- **提交侧对外 API 不变**：`CommitLua.commit(String key, String json)`。
- **测试依赖本地预起 Redis/Mongo**（`DEVELOPMENT.md`「跑测试」；`game-data/src/test/resources/it-config.yaml` 必须存在，本机已配好），不引 Testcontainers。
- **验证权威是 `./mvnw`**：game-data 范围用 `./mvnw -pl game-data -am test`；涉 dbserver 测试用 `./mvnw -pl game-data,game-dbserver -am test`；收尾全量 `./mvnw test`。
- **每个任务一个绿色提交**：任务排序已保证任一任务结束时全仓可编译、既有测试全绿（Task 4 的过渡桥见该任务说明）。
- **注释、javadoc、提交信息一律简体中文**，说人话、不写直译腔（禁词清单见用户反馈：买/兑现/代理验证/先验/倾斜/共置/搁浅 等）。
- 提交信息风格沿用现状：`feat(data): …` / `feat(dbserver): …` / `test(data): …` / `docs(spec): …`，中文一行。

## 文件结构总览

| 文件 | 动作 | 职责 |
| --- | --- | --- |
| `game-data/src/test/java/io/github/brick/data/ClusterSlot.java` | 新建 | Redis Cluster slot 算法的测试复刻（CRC16-XMODEM + tag 提取） |
| `game-data/src/main/java/io/github/brick/data/store/DataKeys.java` | 重写 | 桶文法：`{bNNNN}:{entity}:{id}:{field}` 的唯一来源 |
| `game-data/src/main/java/io/github/brick/data/overlay/DirtyLedger.java` | 重写 | 按桶账本：集合名推导、K 桶异步扇出、聚合 |
| `game-data/src/main/java/io/github/brick/data/overlay/CommitLua.java` | 微改 | KEYS[2] 按 key 推导 |
| `game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushOrchestrator.java` | 重写轮次 | 两段流水线 + `flushBatch` |
| `game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushMetrics.java` | 微改 | 新增 `dbserver.dirty.buckets` gauge |
| 各 IT/单测 | 改写 | 见各任务 |
| 三份 spec + DEVELOPMENT.md | 修订 | 见 Task 8/9 |

---

### Task 1: ClusterSlot 测试工具（CRC16-XMODEM + hash tag 提取）

**Files:**
- Create: `game-data/src/test/java/io/github/brick/data/ClusterSlot.java`
- Test: `game-data/src/test/java/io/github/brick/data/ClusterSlotTest.java`

**Interfaces:**
- Consumes: 无（纯 Java）。
- Produces（test 范围，game-data 与 game-dbserver 测试经 test-jar 均可用）:
  - `io.github.brick.data.ClusterSlot`：`static int slot(String key)`、`static String effectiveTag(String key)`、`static int crc16(String s)`

- [ ] **Step 1: 写失败测试**

```java
package io.github.brick.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClusterSlotTest {

    @Test
    void crc16MatchesXmodemCheckValue() {
        // CRC-16/XMODEM 的标准校验值：123456789 → 0x31C3
        assertEquals(0x31C3, ClusterSlot.crc16("123456789"));
    }

    @Test
    void slotOfKnownKeyMatchesRedisClusterSpecExample() {
        // Redis Cluster 文档示例：无花括号的 key 整串参与计算，123456789 的 slot 是 12739
        assertEquals(12739, ClusterSlot.slot("123456789"));
    }

    @Test
    void effectiveTagExtractsFirstBracePair() {
        assertEquals("user1000", ClusterSlot.effectiveTag("{user1000}.following"));
        assertEquals("user1000", ClusterSlot.effectiveTag("{user1000}.followers"));
        assertEquals("bar", ClusterSlot.effectiveTag("foo{bar}{zap}"));
        assertEquals("{bar", ClusterSlot.effectiveTag("foo{{bar}}zap"));
    }

    @Test
    void effectiveTagFallsBackToWholeKey() {
        // 无花括号、未闭合、或 tag 为空 → 整串
        assertEquals("player:1:profile", ClusterSlot.effectiveTag("player:1:profile"));
        assertEquals("foo{}{bar}", ClusterSlot.effectiveTag("foo{}{bar}"));
        assertEquals("foo{bar", ClusterSlot.effectiveTag("foo{bar"));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-data -am test -Dtest=ClusterSlotTest`
Expected: 编译失败，`ClusterSlot` 不存在。

- [ ] **Step 3: 实现 ClusterSlot**

```java
package io.github.brick.data;

import java.nio.charset.StandardCharsets;

/**
 * Redis Cluster slot 算法的 Java 复刻，纯测试用（设计 §8）。
 * 同槽断言（同桶的数据 key 与账本集合键同 slot）靠它在不连集群的情况下验证。
 */
public final class ClusterSlot {

    private static final int POLY = 0x1021;    // CRC-16/XMODEM

    private ClusterSlot() {
    }

    /** Redis 规则：CRC16(有效子串) mod 16384。 */
    public static int slot(String key) {
        return crc16(effectiveTag(key)) % 16384;
    }

    /**
     * hash tag 规则（Redis 官方）：取首个左花括号与其后首个右花括号之间的子串；
     * 无花括号、无闭合、或子串为空，整串参与计算。
     */
    public static String effectiveTag(String key) {
        int start = key.indexOf('{');
        if (start < 0) {
            return key;
        }
        int end = key.indexOf('}', start + 1);
        if (end < 0 || end == start + 1) {
            return key;
        }
        return key.substring(start + 1, end);
    }

    public static int crc16(String s) {
        int crc = 0;
        for (byte b : s.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ POLY : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -pl game-data -am test -Dtest=ClusterSlotTest`
Expected: PASS（5 个用例）。

- [ ] **Step 5: 提交**

```bash
git add game-data/src/test/java/io/github/brick/data/ClusterSlot.java game-data/src/test/java/io/github/brick/data/ClusterSlotTest.java
git commit -m "test(data): ClusterSlot——Redis slot 算法的测试复刻"
```

---

### Task 2: DataKeys 桶文法 + 全仓旧格式字面量迁移

新文法生效后，**旧格式 key（无桶前缀）一律 `IllegalArgumentException`**（设计 §3.4）。主代码零命中（已全仓扫描），但 7 个测试文件用旧格式字面量，须先迁到 `DataKeys.key(...)`，否则后续任务全线红。

**Files:**
- Modify: `game-data/src/main/java/io/github/brick/data/store/DataKeys.java`（重写）
- Test: `game-data/src/test/java/io/github/brick/data/store/DataKeysTest.java`（重写）
- Modify（字面量迁移）: `game-data/src/test/java/io/github/brick/data/store/MongoStoreIT.java`、`game-data/src/test/java/io/github/brick/data/overlay/CommitLuaIT.java`、`game-data/src/test/java/io/github/brick/data/overlay/DirtyLedgerIT.java`、`game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushOrchestratorIT.java`、`FlushLockIT.java`、`FlushMetricsIT.java`
- **不动**: `RedisStoreIT`（key 对 RedisStore 是不透明字符串，不解析，测试照常绿）

**Interfaces:**
- Produces:
  - `DataKeys.BUCKETS`（`public static final int = 4096`）
  - `static int bucket(String entity, long id)`（包内可见，测试用）
  - `public static int bucketOf(String key)`
  - `key/lockKey/entityOf/idOf/fieldOf/collectionOf/docIdOf` 签名不变
- Consumes: 无。

- [ ] **Step 1: 重写 DataKeysTest（先红）**

```java
package io.github.brick.data.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataKeysTest {

	@Test
	void lockKeyFollowsEntityIdFormat() {
		assertEquals("lock:player:7", DataKeys.lockKey("player", 7));
	}

	@Test
	void keyCarriesBucketPrefix() {
		// 桶号由散列决定，只断言形状；具体桶号由分布测试（Task 3）管
		String key = DataKeys.key("player", 123, "profile");
		assertTrue(key.matches("\\{b\\d{4}\\}:player:123:profile"), key);
	}

	@Test
	void bucketOfReadsThePrefixBack() {
		String key = DataKeys.key("guild", 7, "fund");
		assertEquals(String.format("{b%04d}", DataKeys.bucketOf(key)), key.substring(0, 7));
	}

	@Test
	void entityIdFieldRoundTrip() {
		String key = DataKeys.key("player", 123, "bag");
		assertEquals("player", DataKeys.entityOf(key));
		assertEquals(123L, DataKeys.idOf(key));
		assertEquals("bag", DataKeys.fieldOf(key));
	}

	@Test
	void collectionAndDocIdUnchanged() {
		String key = DataKeys.key("player", 123, "profile");
		assertEquals("player:profile", DataKeys.collectionOf(key));
		assertEquals(123L, DataKeys.docIdOf(key));
	}

	@Test
	void bucketIsStableAndSharedByFieldsOfOneInstance() {
		assertEquals(DataKeys.bucket("player", 5), DataKeys.bucket("player", 5));
		// 同实例的全部 field 落同一桶（设计 §3.2）
		assertEquals(DataKeys.bucketOf(DataKeys.key("player", 5, "bag")),
				DataKeys.bucketOf(DataKeys.key("player", 5, "profile")));
	}

	@Test
	void parseRejectsOldFormatKey() {
		// 迁移依赖此行为：旧格式 key 一律拒绝（设计 §3.4、§7）
		assertThrows(IllegalArgumentException.class, () -> DataKeys.entityOf("player:123:profile"));
		assertThrows(IllegalArgumentException.class, () -> DataKeys.bucketOf("player:123:profile"));
	}

	@Test
	void parseRejectsMalformedOrOutOfRangeBucket() {
		assertThrows(IllegalArgumentException.class, () -> DataKeys.entityOf("no-colons"));
		assertThrows(IllegalArgumentException.class,
				() -> DataKeys.idOf("{b0041}:player:notnum:profile"));
		assertThrows(IllegalArgumentException.class,
				() -> DataKeys.bucketOf("{b9999}:player:1:profile"));   // ≥ BUCKETS
		assertThrows(IllegalArgumentException.class,
				() -> DataKeys.bucketOf("{bx041}:player:1:profile"));   // 非数字
		assertThrows(IllegalArgumentException.class,
				() -> DataKeys.bucketOf("b0041:player:1:profile"));     // 缺花括号
	}
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-data -am test -Dtest=DataKeysTest`
Expected: FAIL（`keyComposesEntityIdField` 等按旧文法断言的用例红，或编译错）。

- [ ] **Step 3: 重写 DataKeys（保持 TAB 缩进）**

```java
package io.github.brick.data.store;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据 key 命名常量 + 解析。key 约定钉死为 {@code {bNNNN}:{entity}:{id}:{field}}（如
 * {@code {b0421}:player:123:profile}），锁名 {@code lock:{entity}:{id}}（架构 spec §4.1/§4.2、
 * 并发修订 §1.1，primitives §3.1）。本类是该约定的唯一来源，业务域不得自造违反约定的 key。
 *
 * <p><b>桶前缀 {@code {bNNNN}} 是 Redis Cluster 的 hash tag。</b>slot 只由 tag 决定，同号键
 * 必然同 slot——{@code CommitLua} 的「SET 数据 key + SADD 同桶脏集合」靠它保住原子性在
 * Cluster 下合法（提交桶化设计 §2/§3）。桶号由 {@link #bucket(String, long)} 从逻辑身份整串
 * 散列得出；{@link #BUCKETS} 是协议常量，**改它等于全量数据重写**（提交桶化设计 §3.3）。
 *
 * <p>只提供**与实体无关**的组装与解析。不含 {@code playerProfile()} 一类便捷方法——
 * {@code player}/{@code profile} 属业务词汇，放这里会让「game-data 不认识业务实体」
 * （primitives §6）失守。业务侧用 {@link #key(String, long, String)} 或自建常量类。
 */
public final class DataKeys {

	/** 桶数（协议常量，一次性钉死）。改 K = 全量数据重写，选取依据见提交桶化设计 §3.3。 */
	public static final int BUCKETS = 4096;

	/** 桶前缀文法：{b + 恰好 4 位数字 + }:（定宽，桶号必须小于 BUCKETS）。 */
	private static final Pattern TAG = Pattern.compile("^\\{b(\\d{4})\\}:");

	private DataKeys() {
	}

	/** 数据 key：{@code {bNNNN}:{entity}:{id}:{field}}。field 不得含 {@code :}。 */
	public static String key(String entity, long id, String field) {
		return tag(bucket(entity, id)) + entity + ":" + id + ":" + field;
	}

	/** 分布式锁名：{@code lock:{entity}:{id}}。锁是单 key 操作，无需 hash tag。 */
	public static String lockKey(String entity, long id) {
		return "lock:" + entity + ":" + id;
	}

	/**
	 * 桶号：对 {@code entity:id} 整串散列后取模。散列对象必须是**整串**——朴素
	 * {@code id % K} 遇上结构化 id（按服务器分块分配、snowflake 机器位）会把数据
	 * 系统性偏到少数桶里（提交桶化设计 §3.2）。高 16 位异或进低位，防低位聚集。
	 */
	static int bucket(String entity, long id) {
		int h = (entity + ":" + id).hashCode();
		h ^= h >>> 16;
		return Math.floorMod(h, BUCKETS);
	}

	/** 由数据 key 解析桶号。CommitLua/DirtyLedger 由此从 key 推导同桶集合名。 */
	public static int bucketOf(String key) {
		Matcher m = tagMatcher(key);
		return checkRange(key, m);
	}

	private static List<String> parts(String key) {
		Matcher m = tagMatcher(key);
		checkRange(key, m);
		String[] s = key.substring(m.end()).split(":", -1);
		if (s.length != 3 || s[0].isEmpty() || s[1].isEmpty() || s[2].isEmpty()) {
			throw new IllegalArgumentException("非法 key（应为 {bNNNN}:{entity}:{id}:{field}）: " + key);
		}
		return List.of(s[0], s[1], s[2]);
	}

	private static Matcher tagMatcher(String key) {
		Matcher m = TAG.matcher(key);
		if (!m.find()) {
			throw new IllegalArgumentException("非法 key（应为 {bNNNN}:{entity}:{id}:{field}）: " + key);
		}
		return m;
	}

	private static int checkRange(String key, Matcher m) {
		int bucket = Integer.parseInt(m.group(1));
		if (bucket >= BUCKETS) {
			throw new IllegalArgumentException("非法 key（桶号越界 ≥" + BUCKETS + "）: " + key);
		}
		return bucket;
	}

	private static String tag(int bucket) {
		return String.format("{b%04d}", bucket);
	}

	public static String entityOf(String key) {
		return parts(key).get(0);
	}

	public static long idOf(String key) {
		try {
			return Long.parseLong(parts(key).get(1));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("非法 key（id 非数字）: " + key, e);
		}
	}

	public static String fieldOf(String key) {
		return parts(key).get(2);
	}

	/** Mongo 集合名 = {@code {entity}:{field}}（Mongo 映射决策 A）。 */
	public static String collectionOf(String key) {
		return entityOf(key) + ":" + fieldOf(key);
	}

	/** Mongo 文档 _id = {id}（Mongo 映射决策 A）。 */
	public static long docIdOf(String key) {
		return idOf(key);
	}
}
```

- [ ] **Step 4: 跑 DataKeysTest 确认通过**

Run: `./mvnw -pl game-data -am test -Dtest=DataKeysTest`
Expected: PASS。

- [ ] **Step 5: 迁移 7 个测试文件的旧格式字面量**

替换规则：`"player:1:profile"` → `DataKeys.key("player", 1, "profile")`，其余同理。每个文件在顶部加 `import io.github.brick.data.store.DataKeys;`（game-dbserver 测试同理，test-jar 已有该依赖）。

逐文件要点（字面量出现处已全仓核实）：
- `MongoStoreIT`（19~105 行多处）：直接内联替换为 `DataKeys.key(...)`。
- `CommitLuaIT`（13~25 行）：类顶部加 `private static final String PROFILE = DataKeys.key("player", 1, "profile");`，字面量全换 `PROFILE`。
- `DirtyLedgerIT`（多处）：同法定义 `P1 = key("player",1,"profile")`、`P2 = key("player",2,"bag")`、`G7 = key("guild",7,"fund")` 常量替换（该文件 Task 4 还要重写，此处只做字面量替换保绿）。
- `FlushOrchestratorIT`：单值处同 `CommitLuaIT`；循环处 `"player:" + i + ":profile"` → `DataKeys.key("player", i, "profile")`（39~170 行多处）。
- `FlushLockIT`（38~183 行）：同上，含循环 `for (int i...)` 内的拼接。
- `FlushMetricsIT`（34~77 行）：同上。

`keyNamesCarryClusterHashTag`（DirtyLedgerIT:45）此步保留原样（断言 `DIRTY_SET="{dirty}"` 仍真），Task 4 重写时删除。

- [ ] **Step 6: 全量跑 game-data + game-dbserver 测试**

Run: `./mvnw -pl game-data,game-dbserver -am test`
Expected: PASS——新文法 key 流过旧全局 dirty 协议（旧 `mark/commit` 不解析 key，`MongoStore` 解析新文法 round-trip 成立），`LockCtxIT`/`LockScopeIT`/`DataAutoConfigurationTest` 本就不用改。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(data): DataKeys 桶文法——{bNNNN} 前缀、旧格式拒绝、测试字面量迁移"
```

---

### Task 3: 桶分布与 tag→slot 散布单测

设计 §8.3/§8.4 的统计验证。**注意统计口径与设计原文有一处修正**：100k 样本、4096 桶时，均匀散列的 max/mean 期望约 1.8，「≤1.2」在统计上站不住；改用 χ² 界 + 宽松 max 界（Task 8 会同步修订设计原文）。`String.hashCode` + 高低位混合若过不了 χ² 界，换散列函数（如乘法混排），文法不动。

**Files:**
- Test: `game-data/src/test/java/io/github/brick/data/store/DataKeysDistributionTest.java`

**Interfaces:**
- Consumes: `DataKeys.BUCKETS`、`DataKeys.bucket(String,long)`（Task 2）、`ClusterSlot`（Task 1）。

- [ ] **Step 1: 写测试（一次成型——纯统计断言，无实现步）**

```java
package io.github.brick.data.store;

import io.github.brick.data.ClusterSlot;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DataKeysDistributionTest {

    private static final int SAMPLES = 100_000;

    @Test
    void bucketSpreadSurvivesSequentialBlockAndSnowflakeIds() {
        // 三种 id 生成形态都不许把数据偏到少数桶（设计 §3.2/§8.3）
        assertSpread(i -> i);                            // 顺序 id
        assertSpread(i -> 10_000_000_000L + i * 4096L);  // 块状分配：低 12 位恒 0
        assertSpread(i -> i << 22);                      // snowflake 型：低位全 0、高位变化
    }

    private interface IdGen {
        long at(long i);
    }

    private void assertSpread(IdGen gen) {
        long[] counts = new long[DataKeys.BUCKETS];
        for (long i = 0; i < SAMPLES; i++) {
            counts[DataKeys.bucket("player", gen.at(i))]++;
        }
        double mean = SAMPLES / (double) DataKeys.BUCKETS;    // ≈ 24.4
        double chi2 = 0;
        long max = 0;
        for (long c : counts) {
            chi2 += (c - mean) * (c - mean) / mean;
            max = Math.max(max, c);
        }
        // 均匀散列的 χ² 期望 ≈ 桶数（自由度 K-1，标准差 ≈ 90）；坏散列大好几个数量级。
        // 2K 是宽到不会误伤好散列、又必然抓住坏散列的界。
        assertThat(chi2).isLessThan(2.0 * DataKeys.BUCKETS);
        // 好散列的 max ≈ 1.8×mean；3× 留余量
        assertThat(max).isLessThanOrEqualTo((long) (3.0 * mean));
    }

    @Test
    void bucketTagsMapToDistinctSlotsEvenlyEnough() {
        // tag→slot 视作随机放置：4096 个 tag 去重后 slot 数 ≥ 3600（生日碰撞打 ~12% 折扣）
        Set<Integer> slots = new HashSet<>();
        for (int b = 0; b < DataKeys.BUCKETS; b++) {
            slots.add(ClusterSlot.slot(String.format("{b%04d}", b)));
        }
        assertThat(slots.size()).isGreaterThanOrEqualTo(3600);

        // 16 节点、每节点连续 1024 个槽（redis-cli --cluster 的默认分法）的失衡 ≤ 12%（期望 ~6.6%）
        int[] perNode = new int[16];
        for (int b = 0; b < DataKeys.BUCKETS; b++) {
            perNode[ClusterSlot.slot(String.format("{b%04d}", b)) / 1024]++;
        }
        double expected = slots.size() / 16.0;
        int max = 0;
        for (int n : perNode) {
            max = Math.max(max, n);
        }
        assertThat(max).isLessThanOrEqualTo((int) Math.ceil(expected * 1.12));
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `./mvnw -pl game-data -am test -Dtest=DataKeysDistributionTest`
Expected: PASS（确定性断言——`hashCode` 固定，无随机波动）。若 FAIL：χ² 超界说明 `String.hashCode` 对该形态聚集，把 `bucket` 的混合改成乘法混排（如 `h *= 0x9E3779B9; h ^= h >>> 16;`）再跑，只动散列不动文法。

- [ ] **Step 3: 提交**

```bash
git add game-data/src/test/java/io/github/brick/data/store/DataKeysDistributionTest.java
git commit -m "test(data): 桶分布与 tag→slot 散布单测（χ² 口径）"
```

---

### Task 4: DirtyLedger 桶化重写

**Files:**
- Modify: `game-data/src/main/java/io/github/brick/data/overlay/DirtyLedger.java`（重写）
- Modify: `game-data/src/main/java/io/github/brick/data/overlay/CommitLua.java`（一行：KEYS[2] 改按 key 推导——`DIRTY_SET` 常量删除后此处必改；javadoc 定稿留给 Task 5）
- Test: `game-data/src/test/java/io/github/brick/data/overlay/DirtyLedgerIT.java`（重写）

**Interfaces:**
- Consumes: `DataKeys.BUCKETS`、`DataKeys.bucketOf(String)`（Task 2）。
- Produces（Task 5/6/7 依赖的完整契约）:
  - `public static String dirtySetOf(int bucket)` / `public static String inflightSetOf(int bucket)` / `public static String dirtySetOf(String key)`
  - `public Map<Integer, Set<String>> drainAll()`（K 桶全量排空，只含非空桶）
  - `public Set<String> drainToInflight(int bucket)`（单桶，测试/诊断）
  - `public void mark(String key)` / `public void markAll(Collection<String> keys)` / `public void ackInflight(Collection<String> keys)`（后两者按 key 推导桶分组）
  - `public Set<String> members()` / `public Set<String> inflightMembers()`（K 桶聚合，签名与旧版一致）
  - `public int backlogSize()` / `public int dirtyBucketCount()`（K 次 SCARD 扇出求和/计数）
  - 删除：`DIRTY_SET`、`INFLIGHT_SET` 常量，`remove`、`removeAll`，**过渡期暂留**无参 `drainToInflight()` 作桥（Task 6 删）。

**说明（本任务的关键取舍）：**
- 无参 `drainToInflight()` 桥保留是为了让 `FlushOrchestrator`（Task 6 才改）与本任务同提交不破编译——桥聚合 `drainAll()` 为扁平快照，行为对旧编排等价。
- K 个桶的批量操作全部走**异步扇出**（`evalAsync`/`sizeAsync`/`readAllAsync`/`addAllAsync`/`removeAllAsync` + `CompletableFuture.allOf` 聚合），避免逐桶串行往返（设计 §9.1 风险项的落地方式——不依赖 RBatch 是否支持 script eval）。
- **API 风险点**：Redisson 4.x 的 `RScript` 应有 `evalAsync`、`RSet` 应有上述异步方法（`RFuture extends CompletionStage`，`toCompletableFuture()` 可用）。若编译发现缺失，用 `client.getScript(...)` 的异步入口或 `RBatch` 等价替换，协议不变。

- [ ] **Step 1: 重写 DirtyLedgerIT（先红）**

```java
package io.github.brick.data.overlay;

import io.github.brick.data.ClusterSlot;
import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.store.DataKeys;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DirtyLedgerIT extends LocalRedisMongo {

    private static final String P1 = DataKeys.key("player", 1, "profile");
    private static final String P2 = DataKeys.key("player", 2, "bag");
    private static final String G7 = DataKeys.key("guild", 7, "fund");

    @Test
    void dataKeyAndLedgerSetsShareOneSlot() {
        // 设计 §8.2 的同槽断言——单机 IT 测不了 CROSSSLOT，Cluster 正确性靠它兜底
        int b = DataKeys.bucketOf(P1);
        assertThat(ClusterSlot.slot(P1))
                .isEqualTo(ClusterSlot.slot(DirtyLedger.dirtySetOf(b)))
                .isEqualTo(ClusterSlot.slot(DirtyLedger.inflightSetOf(b)));
    }

    @Test
    void dirtySetNamesFollowBucketGrammar() {
        assertThat(DirtyLedger.dirtySetOf(42)).isEqualTo("{b0042}::dirty");
        assertThat(DirtyLedger.inflightSetOf(42)).isEqualTo("{b0042}::dirty:inflight");
        assertThat(DirtyLedger.dirtySetOf(P1))
                .isEqualTo(DirtyLedger.dirtySetOf(DataKeys.bucketOf(P1)));
    }

    @Test
    void markRoutesToItsOwnBucket() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(P2);
        assertThat(d.members()).containsExactlyInAnyOrder(P1, P2);
        assertThat(d.drainToInflight(DataKeys.bucketOf(P1))).contains(P1);
    }

    @Test
    void membersStoresRawStringNotQuotedJson() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        assertThat(d.members()).first().isEqualTo(P1);
    }

    @Test
    void drainAllSweepsEveryBucketAndEmptiesDirty() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(G7);
        assertThat(d.drainAll().values().stream().flatMap(Set::stream))
                .containsExactlyInAnyOrder(P1, G7);
        assertThat(d.members()).isEmpty();
        assertThat(d.inflightMembers()).containsExactlyInAnyOrder(P1, G7);
    }

    @Test
    void drainAllOnEmptyRedisReturnsEmptyMap() {
        assertThat(new DirtyLedger(redis).drainAll()).isEmpty();
    }

    @Test
    void drainAllRecoversLeftoverInflightPerBucket() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.drainAll();                     // 第一轮排空后「崩溃」，不 ack
        d.mark(P2);                       // 期间业务侧又标脏一个

        // 下一轮必须把该桶残留合回，两者一起返回
        assertThat(d.drainAll().values().stream().flatMap(Set::stream))
                .containsExactlyInAnyOrder(P1, P2);
        assertThat(d.members()).isEmpty();
    }

    @Test
    void drainOfEmptyBucketIsANoop() {
        // RENAME 对不存在的 key 会报错，Lua 必须先 EXISTS 判空——空桶 drain 不抛
        assertThat(new DirtyLedger(redis).drainToInflight(0)).isEmpty();
    }

    @Test
    void concurrentMarkDuringDrainIsNotSwallowed() {
        // 落盘 spec §2.1 的回归：排空期间的新标脏必须留在新一轮
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        Set<String> snapshot = d.drainToInflight(DataKeys.bucketOf(P1));

        d.mark(P1);                       // 落盘期间业务侧写 v2，重新标脏
        d.ackInflight(snapshot);          // 本轮 ack 自己的快照

        assertThat(d.members()).containsExactly(P1);
        assertThat(d.inflightMembers()).isEmpty();
    }

    @Test
    void ackAndMarkRouteKeysToTheirOwnBuckets() {
        // markAll/ackInflight 按 key 推导桶分组——批内跨桶也能正确路由
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(P2);
        d.mark(G7);
        d.drainAll();
        d.ackInflight(List.of(P1, P2));
        assertThat(d.inflightMembers()).containsExactly(G7);

        d.markAll(List.of(P1, G7));       // 失败回写：跨桶的两个 key
        assertThat(d.members()).containsExactlyInAnyOrder(P1, G7);
    }

    @Test
    void ackInflightAndMarkAllTolerateEmptyInput() {
        DirtyLedger d = new DirtyLedger(redis);
        d.ackInflight(List.of());
        d.markAll(List.of());
        assertThat(d.members()).isEmpty();
    }

    @Test
    void backlogSizeSumsBucketsAndBucketCountCountsNonEmpty() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark(P1);
        d.mark(P2);
        assertThat(d.backlogSize()).isEqualTo(2);
        // P1/P2 散列进 1 或 2 个桶（确定性但依实现而定），两个界都成立
        assertThat(d.dirtyBucketCount()).isBetween(1, 2);
        d.drainAll();
        assertThat(d.backlogSize()).isZero();   // 已排空，积压在 inflight 不算积压
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-data -am test -Dtest=DirtyLedgerIT`
Expected: 编译失败——`dirtySetOf`/`drainAll` 等不存在。

- [ ] **Step 3: 重写 DirtyLedger**

```java
package io.github.brick.data.overlay;

import io.github.brick.data.store.DataKeys;
import org.redisson.api.RFuture;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 按桶分片的 dirty 账本，落盘进程消费（提交桶化设计 §5，修订落盘 spec §2）。
 * 脏集合 {@code {bNNNN}::dirty} 与 in-flight 集合 {@code {bNNNN}::dirty:inflight} 和它标记的
 * 数据 key 同桶同 slot。集合名唯一来源在本类，{@link CommitLua} 引用
 * {@link #dirtySetOf(String)}。集合操作用 {@link StringCodec}：与 {@link CommitLua}
 * Lua 写入的裸 key 字符串对齐。
 *
 * <p><b>桶是排空、回写、ack 的作用域。</b>落盘 spec §2.1–§2.4 的论证对每个桶原样成立：
 * 消费协议是「原子排空到桶内 in-flight」——排空后该桶 dirty 立刻变空集，落盘期间的新标记
 * 进新一轮，物理隔离；in-flight 残留由下一轮 {@link #drainAll()} 的恢复步骤合回，
 * 恢复路径唯一。**刻意不提供「清空 inflight」的方法**：inflight 非空却被 DEL，正是丢标记。
 *
 * <p><b>每轮无条件排空全部 {@link DataKeys#BUCKETS} 个桶**（提交桶化设计 §5.2）：不做
 * 「先探测非空再排空」——中断轮次的桶只剩 inflight 残留（dirty 已被 RENAME 走），探测
 * dirty 键会漏掉它们，残留永远等不到合回。空桶的 drain 是微秒级 no-op，自带探测。K 个桶的
 * 批量操作用异步接口扇出、聚合等待，不逐桶串行往返（测试连接池 8 也能几十毫秒扫完）。
 */
public final class DirtyLedger {

    /**
     * 一次往返完成三件事：合回上轮残留、原子排空、返回快照。KEYS 是**单桶**的
     * (dirty, inflight)——同桶同 slot，Cluster 下合法（提交桶化设计 §5.1）。
     * {@code RENAME} 对不存在的 key 会报错，故排空前必须先 {@code EXISTS} 判空。
     */
    private static final String DRAIN_SCRIPT =
            "if redis.call('EXISTS', KEYS[2]) == 1 then " +
            "  redis.call('SUNIONSTORE', KEYS[1], KEYS[1], KEYS[2]); " +
            "  redis.call('DEL', KEYS[2]); " +
            "end; " +
            "if redis.call('EXISTS', KEYS[1]) == 0 then return {} end; " +
            "redis.call('RENAME', KEYS[1], KEYS[2]); " +
            "return redis.call('SMEMBERS', KEYS[2])";

    private final RedissonClient client;

    public DirtyLedger(RedissonClient client) {
        this.client = client;
    }

    /** 桶 b 的脏集合名：{@code {bNNNN}::dirty}。集合名唯一来源。 */
    public static String dirtySetOf(int bucket) {
        return String.format("{b%04d}::dirty", bucket);
    }

    /** 桶 b 的 in-flight 集合名：{@code {bNNNN}::dirty:inflight}。 */
    public static String inflightSetOf(int bucket) {
        return String.format("{b%04d}::dirty:inflight", bucket);
    }

    /** 由数据 key 推导其脏集合名——{@link CommitLua} 的 KEYS[2]。 */
    public static String dirtySetOf(String key) {
        return dirtySetOf(DataKeys.bucketOf(key));
    }

    /** 标脏单个 key（诊断/测试用；生产提交走 {@link CommitLua} 的原子 Lua）。 */
    public void mark(String key) {
        client.getSet(dirtySetOf(key), StringCodec.INSTANCE).add(key);
    }

    /**
     * 每轮排空入口：对全部 {@link DataKeys#BUCKETS} 个桶并发执行 DRAIN_SCRIPT（含残留合回）。
     * 空桶返回空集，不出现在结果里。
     *
     * @return 桶号 → 本轮成员快照；只含非空桶
     */
    public Map<Integer, Set<String>> drainAll() {
        RScript script = client.getScript(StringCodec.INSTANCE);
        List<CompletableFuture<List<Object>>> futures = new ArrayList<>(DataKeys.BUCKETS);
        for (int b = 0; b < DataKeys.BUCKETS; b++) {
            futures.add(script.<List<Object>>evalAsync(
                    RScript.Mode.READ_WRITE, DRAIN_SCRIPT, RScript.ReturnType.LIST,
                    List.of(dirtySetOf(b), inflightSetOf(b))).toCompletableFuture());
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        Map<Integer, Set<String>> result = new LinkedHashMap<>();
        for (int b = 0; b < futures.size(); b++) {
            List<Object> members = futures.get(b).join();
            if (members == null || members.isEmpty()) {
                continue;
            }
            Set<String> snapshot = new LinkedHashSet<>(members.size());
            for (Object m : members) {
                snapshot.add(m.toString());
            }
            result.put(b, snapshot);
        }
        return result;
    }

    /**
     * 单桶排空（测试/诊断用）。生产轮次走 {@link #drainAll()}。
     *
     * @return 本桶本轮要落盘的 key 快照；空桶返回空 Set
     */
    public Set<String> drainToInflight(int bucket) {
        List<Object> members = client.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE, DRAIN_SCRIPT, RScript.ReturnType.LIST,
                List.of(dirtySetOf(bucket), inflightSetOf(bucket)));
        Set<String> snapshot = new LinkedHashSet<>();
        for (Object m : members) {
            snapshot.add(m.toString());
        }
        return snapshot;
    }

    /**
     * 过渡桥：聚合全部桶排空为扁平快照，供桶化编排落地前的旧版 FlushOrchestrator 使用。
     * **桶化编排落地后删除**——扁平化丢失桶信息，MGET 无法按桶分组。
     */
    @Deprecated
    public Set<String> drainToInflight() {
        Set<String> flat = new LinkedHashSet<>();
        drainAll().values().forEach(flat::addAll);
        return flat;
    }

    /**
     * 一批落盘完成，把成员移出各自桶的 in-flight。**必须在 {@link #markAll} 回写失败
     * key 之后调用**（落盘 spec §2.4）：反序则「ack 后、mark 前」崩溃会让失败 key 既不在
     * in-flight 也不在 dirty，静默丢标记。批内 key 可跨桶——按 key 推导桶分组下发。
     */
    public void ackInflight(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        Map<Integer, List<String>> byBucket = groupByBucket(keys);
        List<RFuture<Boolean>> futures = new ArrayList<>(byBucket.size());
        for (Map.Entry<Integer, List<String>> e : byBucket.entrySet()) {
            futures.add(inflightOf(e.getKey()).removeAllAsync(new ArrayList<>(e.getValue())));
        }
        awaitAll(futures);
    }

    /** 落盘失败的 key 回写各自桶的 dirty 等下轮重试。upsert 幂等，重做无害。 */
    public void markAll(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        Map<Integer, List<String>> byBucket = groupByBucket(keys);
        List<RFuture<Boolean>> futures = new ArrayList<>(byBucket.size());
        for (Map.Entry<Integer, List<String>> e : byBucket.entrySet()) {
            futures.add(setOf(e.getKey()).addAllAsync(new ArrayList<>(e.getValue())));
        }
        awaitAll(futures);
    }

    /** 全部桶的 dirty 成员聚合（诊断/测试用）。K 次 SMEMBERS 异步扇出。 */
    public Set<String> members() {
        List<RFuture<Set<String>>> futures = new ArrayList<>(DataKeys.BUCKETS);
        for (int b = 0; b < DataKeys.BUCKETS; b++) {
            futures.add(setOf(b).readAllAsync());
        }
        awaitAll(futures);
        Set<String> all = new LinkedHashSet<>();
        for (RFuture<Set<String>> f : futures) {
            Set<String> members = f.toCompletableFuture().join();
            if (members != null) {
                all.addAll(members);
            }
        }
        return all;
    }

    /** 当前 in-flight（已排空但尚未落盘）成员聚合。K 次 SMEMBERS 扇出，仅停机日志用。 */
    public Set<String> inflightMembers() {
        List<RFuture<Set<String>>> futures = new ArrayList<>(DataKeys.BUCKETS);
        for (int b = 0; b < DataKeys.BUCKETS; b++) {
            futures.add(inflightOf(b).readAllAsync());
        }
        awaitAll(futures);
        Set<String> all = new LinkedHashSet<>();
        for (RFuture<Set<String>> f : futures) {
            Set<String> members = f.toCompletableFuture().join();
            if (members != null) {
                all.addAll(members);
            }
        }
        return all;
    }

    /** dirty 积压量（全部桶求和，不含 in-flight）。K 次 SCARD 扇出——缺键返回 0，不报错。 */
    public int backlogSize() {
        return dirtySizes().members();
    }

    /** 非空脏桶数。gauge {@code dbserver.dirty.buckets} 用（提交桶化设计 §6）。 */
    public int dirtyBucketCount() {
        return dirtySizes().nonEmptyBuckets();
    }

    private record Sizes(int members, int nonEmptyBuckets) {}

    private Sizes dirtySizes() {
        List<RFuture<Integer>> futures = new ArrayList<>(DataKeys.BUCKETS);
        for (int b = 0; b < DataKeys.BUCKETS; b++) {
            futures.add(setOf(b).sizeAsync());
        }
        awaitAll(futures);
        int members = 0;
        int nonEmpty = 0;
        for (RFuture<Integer> f : futures) {
            int n = f.toCompletableFuture().join();
            members += n;
            if (n > 0) {
                nonEmpty++;
            }
        }
        return new Sizes(members, nonEmpty);
    }

    private static Map<Integer, List<String>> groupByBucket(Collection<String> keys) {
        Map<Integer, List<String>> byBucket = new LinkedHashMap<>();
        for (String key : keys) {
            byBucket.computeIfAbsent(DataKeys.bucketOf(key), k -> new ArrayList<>()).add(key);
        }
        return byBucket;
    }

    private static void awaitAll(List<? extends RFuture<?>> futures) {
        CompletableFuture.allOf(futures.stream()
                .map(RFuture::toCompletableFuture)
                .toArray(CompletableFuture[]::new)).join();
    }

    private RSet<String> setOf(int bucket) {
        return client.getSet(dirtySetOf(bucket), StringCodec.INSTANCE);
    }

    private RSet<String> inflightOf(int bucket) {
        return client.getSet(inflightSetOf(bucket), StringCodec.INSTANCE);
    }
}
```

- [ ] **Step 4: 同步改 CommitLua 的 KEYS[2]（编译倒逼，必做）**

`DirtyLedger.DIRTY_SET` 常量已删，`CommitLua.commit` 里的 `List.of(key, DirtyLedger.DIRTY_SET)` 不再编译。改为：

```java
                List.of(key, DirtyLedger.dirtySetOf(key)),
```

javadoc 与测试强化留给 Task 5，本步只动这一行。

- [ ] **Step 5: 跑 DirtyLedgerIT 确认通过**

Run: `./mvnw -pl game-data -am test -Dtest=DirtyLedgerIT`
Expected: PASS（12 个用例；`drainAll` 全量扫 4096 桶，测试池 8 连接下约一两百毫秒，属正常）。

- [ ] **Step 6: 全量回归（含 dbserver——旧编排走过渡桥）**

Run: `./mvnw -pl game-data,game-dbserver -am test`
Expected: PASS。`FlushOrchestrator` 走桥版 `drainToInflight()`；`FlushLockIT`/`FlushMetricsIT`/`FlushOrchestratorIT` 用聚合 `members()`/`backlogSize()`，签名未变；`LockCtxIT`/`LockScopeIT` 断言走聚合视图照常成立。

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(data): DirtyLedger 桶化重写——K 桶异步扇出与聚合"
```

---

### Task 5: CommitLua 落位 + CommitLuaIT 强化

Task 4 的大概率情形已把 `CommitLua` 的 KEYS[2] 改成 `dirtySetOf(key)`（编译倒逼）。本任务把它钉死并强化测试。

**Files:**
- Modify: `game-data/src/main/java/io/github/brick/data/overlay/CommitLua.java`
- Test: `game-data/src/test/java/io/github/brick/data/overlay/CommitLuaIT.java`（重写断言）

**Interfaces:**
- Consumes: `DirtyLedger.dirtySetOf(String)`（Task 4）、`DataKeys.bucketOf`（Task 2）。
- Produces: `CommitLua.commit(String key, String json)` 签名不变、语义不变（原子 SET+SADD，SADD 落同桶集合）。

- [ ] **Step 1: 重写 CommitLuaIT**

```java
package io.github.brick.data.overlay;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import org.redisson.client.codec.StringCodec;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CommitLuaIT extends LocalRedisMongo {

    private static final String PROFILE = DataKeys.key("player", 1, "profile");

    @Test
    void commitSetsValueAndMarksDirtyInTheRightBucket() {
        CommitLua lua = new CommitLua(redis);
        lua.commit(PROFILE, "{\"name\":\"alice\"}");

        assertThat(new RedisStore(redis).get(PROFILE)).isEqualTo("{\"name\":\"alice\"}");

        // 标记必须落在 PROFILE 自己的桶集合里（与数据 key 同 slot）
        Set<String> bucketMembers = redis.getSet(
                DirtyLedger.dirtySetOf(DataKeys.bucketOf(PROFILE)), StringCodec.INSTANCE).readAll();
        assertThat(bucketMembers).containsExactly(PROFILE);
        // 聚合视图同样可见
        assertThat(new DirtyLedger(redis).members()).containsExactly(PROFILE);
    }

    @Test
    void commitOverwritesExistingValue() {
        CommitLua lua = new CommitLua(redis);
        lua.commit(PROFILE, "{\"v\":1}");
        lua.commit(PROFILE, "{\"v\":2}");
        assertThat(new RedisStore(redis).get(PROFILE)).isEqualTo("{\"v\":2}");
    }
}
```

- [ ] **Step 2: 跑测试**

Run: `./mvnw -pl game-data -am test -Dtest=CommitLuaIT`
Expected: PASS（若 Task 4 已改 CommitLua）或 FAIL（`List.of(key, DirtyLedger.DIRTY_SET)` 编译错）。

- [ ] **Step 3: 定稿 CommitLua（javadoc 一并落位）**

```java
package io.github.brick.data.overlay;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;

/**
 * 原子提交脚本：{@code SET key json; SADD {bNNNN}::dirty key}，不解析 JSON（架构 spec §4.2，
 * primitives §3.2）。覆盖写唯一提交点——不暴露可分离的 {@code SET} 与 {@code SADD}，避免
 * 「SET 成功 SADD 失败致数据在 Redis 却不标脏、永不下沉 Mongo、Redis 崩即永久丢失」。
 *
 * <p>KEYS[2]（脏集合）由 key 推导：{@link DirtyLedger#dirtySetOf(String)}——桶号取自 key 的
 * {@code {bNNNN}} 前缀，集合与数据 key 同 hash tag 同 slot，这段跨 key Lua 在 Redis Cluster
 * 下合法（提交桶化设计 §4）。这也是架构 §4.2「不做跨 Key Lua」的修订兑现：Lua 只碰同一桶
 * 内的 key，跨实体实例的原子操作仍然不做。
 */
public final class CommitLua {

    private static final String SCRIPT =
            "redis.call('SET', KEYS[1], ARGV[1]); " +
            "redis.call('SADD', KEYS[2], KEYS[1]); " +
            "return 1";

    private final RedissonClient client;

    public CommitLua(RedissonClient client) {
        this.client = client;
    }

    public void commit(String key, String json) {
        client.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                SCRIPT,
                RScript.ReturnType.LONG,
                List.of(key, DirtyLedger.dirtySetOf(key)),
                json);
    }
}
```

- [ ] **Step 4: 回归 LockCtxIT / LockScopeIT（调用方零改动的证明）**

Run: `./mvnw -pl game-data -am test -Dtest='LockCtxIT,LockScopeIT,CommitLuaIT,DataAutoConfigurationTest'`
Expected: PASS——提交路径对外行为不变，`putCommitsUnderLock` 里的 `members().contains(PROFILE)` 走聚合视图照常成立。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(data): CommitLua 脏集合按 key 推导（同桶同 slot）"
```

---

### Task 6: FlushOrchestrator 两段流水线（+删过渡桥）

**Files:**
- Modify: `game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushOrchestrator.java`（`drainAndFlush` 重写、`flushChunk`→`flushBatch`、删桥调用）
- Modify: `game-data/src/main/java/io/github/brick/data/overlay/DirtyLedger.java`（删无参 `drainToInflight()` 桥）
- Modify: `game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushOrchestratorIT.java`、`FlushLockIT.java`（手动 drain 与 override 适配）

**Interfaces:**
- Consumes: `DirtyLedger.drainAll()`、`markAll`、`ackInflight`、`inflightMembers`（Task 4）、`RedisStore.mget`、`MongoStore.bulkUpsert`、`chunks`（本类既有静态方法，复用作桶内切片）。
- Produces: `protected ChunkStats flushBatch(List<String> members, Map<String, String> values)`（测试子类的 override 点，替代旧 `flushChunk`）；`flushOnce` 三态返回值契约不变。

- [ ] **Step 1: 改 FlushOrchestratorIT/FlushLockIT 中会破的点（先红）**

三处（其余用例不动）：
1. `FlushOrchestratorIT.concurrentWriteDuringFlushSurvivesAsNewDirtyMark` 与 `leftoverInflightFromCrashedRoundIsRecoveredAndFlushed` 里的 `d.drainToInflight()` 手动排空调用，改为：
```java
Set<String> snapshot = d.drainAll().values().stream()
        .flatMap(Set::stream).collect(java.util.stream.Collectors.toSet());
```
2. `FlushLockIT.lockIsReleasedEvenWhenTheRoundBlowsUp` 的 override：
```java
@Override
protected FlushOrchestrator.ChunkStats flushBatch(
        java.util.List<String> members, java.util.Map<String, String> values) {
    throw new IllegalStateException("mongo 抖了");
}
```
3. `FlushLockIT.losingLockMidRoundInterruptsAndLeavesRemainderInInflight` / `remainderLeftByAnInterruptedRoundIsPickedUpByTheNextOne` / `multiChunkRoundIsNotMistakenForALostLock`：逻辑不动（`stillHoldsLock` override 与 chunkSize 语义照旧——粒度从「片」变「批」，行为等价），跑一遍确认。

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-data,game-dbserver -am test -Dtest='FlushOrchestratorIT,FlushLockIT'`
Expected: 编译失败——`flushBatch` 不存在。

- [ ] **Step 3: 重写 FlushOrchestrator 轮次**

`flushOnce`、`stillHoldsLock`、`chunks`、`ChunkStats`、常量、字段全部不动。替换 `drainAndFlush` 与 `flushChunk`，新增 `Batch` 内部类，删除 DirtyLedger 桥：

```java
    private int drainAndFlush(RLock lock) {
        long startNanos = System.nanoTime();
        int flushed = 0, failed = 0, missing = 0;

        // 第一段：K 桶全量排空（含上轮残留合回），得 桶号 → 本轮成员快照
        Map<Integer, Set<String>> drained = dirty.drainAll();
        int total = 0;
        for (Set<String> members : drained.values()) {
            total += members.size();
        }
        if (total == 0) {
            metrics.recordRound(System.nanoTime() - startNanos, 0, 0, 0);
            return 0;
        }

        // 第二段：逐桶 MGET（同桶成员同 slot，Cluster 下单条 MGET 合法），
        // 值跨桶聚成 Mongo 批（批量收益与总量相关、与桶数无关）
        Batch batch = new Batch(chunkSize);
        for (Map.Entry<Integer, Set<String>> e : drained.entrySet()) {
            for (List<String> mchunk : chunks(e.getValue(), chunkSize)) {
                batch.add(mchunk, redis.mget(mchunk));
                while (batch.isFull()) {
                    ChunkStats s = flushBatch(batch.members(), batch.values());
                    flushed += s.flushed();
                    failed += s.failed();
                    missing += s.missing();
                    batch.clear();
                    if (!stillHoldsLock(lock)) {
                        // 与 LockCtx.put 的 isHeld 提交门控同构：绝不带着失效锁继续写。
                        // 剩余 key 留在 in-flight，由下一轮 drain 的恢复步骤合回（落盘 spec §2.3）
                        log.error("落盘中途失锁（租约 {}s 到期），中断本轮；剩余 {} 个 key 留在 in-flight 待下轮恢复",
                                lockLeaseSeconds, dirty.inflightMembers().size());
                        metrics.recordRound(System.nanoTime() - startNanos, flushed, failed, missing);
                        return INTERRUPTED;
                    }
                }
            }
        }
        if (!batch.isEmpty()) {
            ChunkStats s = flushBatch(batch.members(), batch.values());
            flushed += s.flushed();
            failed += s.failed();
            missing += s.missing();
        }
        metrics.recordRound(System.nanoTime() - startNanos, flushed, failed, missing);
        return total;
    }

    /**
     * 一个 Mongo 批的落盘。批内成员可能跨桶（MGET 已按桶分完组，这里只剩值搬运）。
     * 顺序约束（落盘 spec §2.4）：先 markAll 失败 key、后 ackInflight 整批成员。
     *
     * <p>受保护仅为可测——「跑到第 N 批时 Mongo 抛异常」用真实故障无法确定性复现，
     * 测试子类覆盖本方法即可触发（同 {@link #chunks} 的先例）。
     */
    protected ChunkStats flushBatch(List<String> members, Map<String, String> values) {
        // mget 不把不存在的 key 映射到 null，而是不放进 Map，故差值即 nil 数量。
        // 这些 key 跳过 upsert 且**不回 dirty**——回了就是永久重试的死循环。
        int missing = members.size() - values.size();
        if (missing > 0) {
            log.warn("本批 {} 个 key 在 Redis 已不存在（标记残留），跳过落盘且不回写 dirty", missing);
        }

        Set<String> failed = mongo.bulkUpsert(values);
        if (!failed.isEmpty()) {
            log.warn("落盘失败 {} 个 key，回写 dirty 等下轮重试: {}", failed.size(), failed);
            dirty.markAll(failed);      // 顺序要求：必须先 mark 再 ack（落盘 spec §2.4）
        }
        dirty.ackInflight(members);     // 两步之间崩溃只会导致整批被下轮重放，upsert 幂等
        return new ChunkStats(values.size() - failed.size(), failed.size(), missing);
    }

    /** 跨桶聚合的 Mongo 批缓冲：成员数到 chunkSize 即成批（与旧「片」同尺寸语义）。 */
    private static final class Batch {
        private final int capacity;
        private final List<String> members = new ArrayList<>();
        private final Map<String, String> values = new LinkedHashMap<>();

        Batch(int capacity) {
            this.capacity = capacity;
        }

        void add(List<String> mchunk, Map<String, String> vals) {
            members.addAll(mchunk);
            values.putAll(vals);
        }

        boolean isFull() {
            return members.size() >= capacity;
        }

        boolean isEmpty() {
            return members.isEmpty();
        }

        void clear() {
            members.clear();
            values.clear();
        }

        List<String> members() {
            return members;
        }

        Map<String, String> values() {
            return values;
        }
    }
```

imports 增补：`java.util.LinkedHashMap`、`java.util.Map`（`Set`/`ArrayList`/`List` 已有）。类 javadoc 末尾加一段：

```
 * <p>轮次结构（提交桶化设计 §5.3）：第一段 K 桶全量排空；第二段逐桶 MGET、跨桶聚合
 * Mongo 批。每批完成检查持锁。批内先 markAll 失败 key 再 ackInflight 整批（落盘 spec §2.4）。
```

同时删除 `DirtyLedger` 的无参 `drainToInflight()` 桥（Task 4 加的 `@Deprecated` 方法），全仓无剩余调用（FlushOrchestrator 主循环与 IT 已改）。

- [ ] **Step 4: 跑 dbserver 全量测试**

Run: `./mvnw -pl game-data,game-dbserver -am test`
Expected: PASS。重点用例：`flushSpansMultipleChunksAndLandsEverything`（12 key / chunk 5 → 3 批，返回 12）、`failedKeyGoesBackToDirtyWhileTheRestOfTheChunkLands`（跨桶失败回写）、`losingLockMidRoundInterruptsAndLeavesRemainderInInflight`（批粒度失锁中断）、`multiChunkRoundIsNotMistakenForALostLock`（chunkSize 1 逐批跑完）。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(dbserver): 落盘两段流水线——逐桶 drain/MGET + 跨桶 Mongo 批"
```

---

### Task 7: FlushMetrics 新增 buckets 指标

**Files:**
- Modify: `game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushMetrics.java`
- Modify: `game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushMetricsIT.java`

**Interfaces:**
- Consumes: `DirtyLedger.dirtyBucketCount()`（Task 4）。
- Produces: gauge `dbserver.dirty.buckets`。

- [ ] **Step 1: 在 FlushMetricsIT 写失败测试**

```java
    @Test
    void bucketsGaugeCountsNonEmptyBuckets() {
        FlushOrchestrator o = orchestrator(500);
        dirty.mark("player:1:profile");
        dirty.mark("player:2:bag");
        // 两个实例散进 1~2 个桶（确定性但依散列实现而定）
        assertThat(registry.get("dbserver.dirty.buckets").gauge().value()).isBetween(1.0, 2.0);

        new RedisStore(redis).set(DataKeys.key("player", 1, "profile"), "{}");
        new RedisStore(redis).set(DataKeys.key("player", 2, "bag"), "[]");
        o.flushOnce();
        assertThat(registry.get("dbserver.dirty.buckets").gauge().value()).isZero();
    }
```

（文件顶部补 `import io.github.brick.data.store.DataKeys;`；既有用例的字面量已在 Task 2 迁移。）

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-data,game-dbserver -am test -Dtest=FlushMetricsIT`
Expected: FAIL——`dbserver.dirty.buckets` 指标未注册。

- [ ] **Step 3: FlushMetrics 注册 gauge**

在构造器里 `dbserver.dirty.backlog` 的注册之后加：

```java
        Gauge.builder("dbserver.dirty.buckets", dirty, DirtyLedger::dirtyBucketCount)
                .description("非空脏桶数——落盘健康的最先该看的信号，比成员数粗")
                .register(registry);
```

类 javadoc 补一句：backlog 与 buckets 两个 gauge 各自独立做一次 K-SCARD 扫描（gauge 抓取一次批量往返，抓取间隔 15s 级别下开销可忽略）。

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -pl game-data,game-dbserver -am test -Dtest=FlushMetricsIT`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "feat(dbserver): dbserver.dirty.buckets 指标（非空脏桶数）"
```

---

### Task 8: spec 修订（架构 / 落盘 / 设计三份）

**Files:**
- Modify: `.superpowers/specs/2026-07-24-server-architecture-design.md`
- Modify: `.superpowers/specs/2026-09-04-dbserver-flush-design.md`
- Modify: `.superpowers/specs/2026-09-21-commit-clusterslot-design.md`

**Interfaces:** 无代码。修订文字如下，逐处替换。

- [ ] **Step 1: 架构 spec 修订（3 处）**

1. §4.1 的 key 清单（`player:{id}:profile` 等 4 行）之后追加一行：
```
  - **物理 key 形态**：`{bNNNN}:{entity}:{id}:{field}`——`{bNNNN}` 是 Cluster hash tag（桶号 = hash(entity:id) mod 4096），提交与落盘协议靠它把数据 key 与同桶脏标记集合绑进同一 slot（见提交桶化设计 §3）。
```
2. §4.2 条 3 首句「不做跨 Key Lua（预防成熟期 Redis Cluster 报错）。」替换为：
```
跨 Key Lua 仅限**同一 hash tag（同一桶）内**的 key——提交侧 `SET+SADD` 的两键同桶，原子且 Cluster 合法（见提交桶化设计 §2/§4）；**跨实体实例**的原子操作仍然不做（不同桶必不同 slot，Cluster 报 CROSSSLOT）。
```
3. §4.3 的「`dirty` 集合成员为数据 key（如 `player:123:profile`）」改为「（如 `{bNNNN}:player:123:profile`，按桶分片）」，并在「落盘进程 `SMEMBERS dirty`」句后加「（消费协议 2026-09-21 起为按桶排空，见落盘 spec §2 修订）」。

- [ ] **Step 2: 落盘 spec 修订（§2 顶部加修订块 + §2.5.1 了结 + §3/§7.1/§8 更新）**

§2 标题后插入：

```
> **2026-09-21 修订**：全局 `{dirty}` / `{dirty}:inflight` 集合改为**按桶分片**的
> `{bNNNN}::dirty` / `{bNNNN}::dirty:inflight`——桶号取自数据 key 的 hash tag 前缀，集合与
> 数据 key 同 slot。§2.1–§2.4 的论证对每个桶原样成立（作用域从全局变单桶）；排空入口从
> 「一次 drain」变为「每轮无条件排空全部 4096 个桶」。协议细节以
> [提交桶化设计](./2026-09-21-commit-clusterslot-design.md) §5 为准。
```

§2.5.1 标题改为「#### 2.5.1 提交侧的 CROSSSLOT（已由提交桶化设计了结）」，正文末尾追加一句：
```
2026-09-21 起已由 [提交桶化设计](./2026-09-21-commit-clusterslot-design.md) 落地解决。
```
§3 的流水线代码块替换为 Task 6 的两段结构（引用设计 §5.3，不必贴全代码）；§7.1 指标表加一行 `dbserver.dirty.buckets`；§8 的 `game-data` 改动清单按设计 §3–§5 更新（`DirtyLedger` 从「新增 3 个方法」改为「桶化重写」）。

- [ ] **Step 3: 设计 spec 自我校正（3 处）**

1. §8.3：「断言 max/mean ≤ 1.2」→「断言 χ² < 2K 且 max ≤ 3×mean——100k 样本下均匀散列的 max/mean 期望 ~1.8，1.2 的口径统计上立不住（计划阶段修正）」。
2. §6：`dbserver.dirty.buckets` 行的「同一次聚合顺带统计——零额外往返」→「独立的 K-SCARD 扫描（gauge 抓取一次批量往返）」。
3. §9.1 第一验证点「Redisson 批量执行 EVAL 的机制」标注：已用 `evalAsync` 异步扇出落地，不再依赖 RBatch（Task 4）。

- [ ] **Step 4: 提交**

```bash
git add .superpowers/specs/
git commit -m "docs(spec): 桶化落地后的 spec 修订——架构 §4.1/§4.2、落盘 §2/§2.5.1、设计口径校正"
```

---

### Task 9: 全量验证 + DEVELOPMENT.md 迁移提示

**Files:**
- Modify: `DEVELOPMENT.md`（Redis 小节加一条）

- [ ] **Step 1: 全仓测试**

Run: `./mvnw test`
Expected: 全绿（含 game-web/game-contract 若有测试）。

- [ ] **Step 2: DEVELOPMENT.md 补迁移提示**

在 Redis（Memurai）小节的 AOF 配置说明后追加：

```
- **数据 key 自 2026-09-21 起带 `{bNNNN}` 桶前缀**（提交桶化设计）。旧格式 key 会被
  `DataKeys` 拒绝——本地/测试 Redis 请 `FLUSHDB` 一次，数据由冷启动路径（get miss 从
  Mongo 加载回填）自然回灌；Mongo 零迁移。
```

- [ ] **Step 3: 提交**

```bash
git add DEVELOPMENT.md
git commit -m "docs: DEVELOPMENT.md 补桶前缀 key 的 flushdb 迁移提示"
```

---

## 计划自审记录

- **Spec 覆盖**：设计 §2（否决记录→无需任务）、§3（Task 2/3）、§4（Task 5）、§5（Task 4/6）、§6（Task 7）、§7（Task 2 字面量拒绝 + Task 9 提示）、§8（Task 1/2/3/4/5 的测试）、§9（Task 4 异步扇出落位 + Task 8 校正）、§10（Task 8）——全覆盖。
- **统计口径修正**：设计 §8.3 的「max/mean ≤ 1.2」在 100k/4096 的量纲下统计上不成立（均匀散列期望 ~1.8），计划改用 χ² 界并在 Task 8 回写设计原文——这是计划阶段发现并修正的 spec 缺陷，非偏离。
- **类型一致性**：`drainAll()` 返回 `Map<Integer,Set<String>>` 在 Task 4/6 一致；`flushBatch(List<String>, Map<String,String>)` 在 Task 6 定义、FlushLockIT 适配一致；`dirtySetOf(String)` 在 Task 4/5 一致。
- **每个提交绿色**：Task 4 的无参桥保证跨模块编译不断；Task 6 删桥与编排切换同提交。
