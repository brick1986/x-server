# game-data 数据原语实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `game-data` 模块的数据原语——`LockScope`/`LockCtx` 锁作用域、Redis/Mongo 存储封装、原子提交 Lua、标脏账本、JSON 编解码、连接池配置，使 `game-web` 与 `game-dbserver` 可据此安全读写。

**Architecture:** `game-data` 对外暴露一个 `LockCtx` 锁作用域对象（try-with-resources），内部闭环持锁断言、提交门控、原子 Lua `SET+SADD`、有序多锁 all-or-nothing。POJO 全泛型 `<T>`，`game-data` 不 import 任何业务实体类。读写共用同一把互斥 `RLock`，读路径回填进锁（普通 `SET`），写路径锁内 miss 加载。锁固定 `leaseTime=10s` 无看门狗，提交前 `isHeldByCurrentThread()` 门控，失锁 fail-fast。

**Tech Stack:** JDK 25、Spring Boot 4.1.0、Redisson 4.6.1（Sync API）、MongoDB Driver 5.9.0（Sync）、Jackson（Spring Boot 自带）、JUnit 5、AssertJ、Mockito、ArchUnit。

**Spec:**
- `.superpowers/specs/2026-08-11-game-data-primitives-design.md`（主，Plan B 数据原语设计）
- `.superpowers/specs/2026-08-04-data-concurrency-fixes-design.md`（并发修订，已并入主 spec 与架构 spec §3.1）
- `.superpowers/specs/2026-07-24-server-architecture-design.md` §3.1/§4（架构层）

---

## Global Constraints

直接照搬 spec 钉定的项目级约束，每个 task 的要求都隐含本节：

- **JDK 25**，编译目标 `--release 25`（根 POM `java.version=25`）。
- **统一同步命令式编程**，禁用 Reactive / Mono/Flux（架构 spec §3.1）。
- **连接池显式设限**：Redis 100~200、Mongo 50~100，禁止无上限；按池自身峰值定，不与载体线程池绑定（架构 spec §3.1）。
- **禁嵌套跨池获取**：一个虚拟线程绝不同时持有两个连接池的连接，用完一个池归还后再取下一个（并发修订 §3.2）。
- **锁租约固定 `leaseTime=10s`、禁用看门狗**：加锁一律传固定租约，不使用 Redisson 默认无限续约（并发修订 §4.1）。
- **提交门控**：覆盖写唯一提交点 `SET+SADD` Lua 执行前强制 `isHeldByCurrentThread()` 校验，失锁抛 `LockLostException`、绝不写（并发修订 §4.2）。
- **读写共用同一把互斥 `RLock`**，不引入读写锁；回填是持锁下的普通 `SET`，不用 `SET NX`（并发修订 §1.2、§2.1）。
- **key 命名钉死** `{entity}:{id}:{field}`（如 `player:123:profile`），锁名 `lock:{entity}:{id}`；`DataKeys` 是该约定唯一来源（primitives §3.1）。
- **`game-data` 不认识业务实体**：`get`/`put` 全泛型 `<T>`，`JsonCodec` 不 import 业务域包；ArchUnit 强制 `io.github.brick.data..` 不依赖 `io.github.brick.web..`/`contract..`/`dbserver..`（primitives §6、§7 用例 9）。
- **集成测试连本地预起** Redis（`127.0.0.1:6379`）与 Mongo（`127.0.0.1:27018`），不引 Testcontainers（primitives §7）。
- **菜鸟期 Redis 只用 String 类型**，不使用 hash/sortedset/list（primitives §6.1）。

## 本计划锁定的设计决策（spec 未钉死处）

1. **Mongo 映射**（用户已拍板，方案 A）：`MongoStore` 把 key 映射为 `collection = {entity}:{field}`、`_id = {id}`（如 `player:123:profile` → collection `player:profile`、`_id` `123`）。纯 key 派生、无业务知识，与 Redis key 一一对应、落盘零转换。文档 JSON 存于字段 `v`（即 doc = `{_id, v}`）。由 `DataKeys.collectionOf(key)`/`DataKeys.docIdOf(key)` 提供。
2. **加锁用 `tryLock` 而非 `lock(leaseTime)`**：spec 字面写 `lock.lock(10, SECONDS)`，但该重载**无限等待**，与架构 spec §4.4「锁不可用时写操作快速失败」矛盾。本计划落地为 `tryLock(waitMillis, leaseSeconds, SECONDS)`：`leaseSeconds=10`（无看门狗，固定租约——这是 spec §4.1 的实质要求），`waitMillis` 可配（默认 2000ms，满足 fail-fast）。配在 `DataProperties`。
3. **未登记实体类型抛异常**：`LockScope` 的全局类型优先级表只含菜鸟期 `guild=0`、`player=1`。遇到未登记的 `entity` 抛 `IllegalArgumentException`，强制「新增实体类型时在此表登记其优先级位置」（并发修订 §1.3）。
4. **dirty 集合名单常量 `dirty`**，定义于 `DirtyLedger.DIRTY_SET`，`CommitLua` 引用同一常量——单一来源。
5. **Redis 字符串用 `StringCodec`**：`RedisStore` 的 `RBucket` 与 `DirtyLedger` 的 `RSet` 必须显式 `StringCodec`，否则 Redisson 默认 `JsonJacksonCodec` 会把 `player:123:profile` 存成带引号的 JSON 字符串，与 Lua 写入的裸字符串不一致。`CommitLua` 的 Lua 直接写裸字符串，故读侧必须 `StringCodec` 对齐。

## File Structure

`game-data` 新增/修改文件（包 `io.github.brick.data`）：

| 文件 | 职责 |
| --- | --- |
| `codec/JsonCodec.java` | POJO↔JSON（Jackson），不持有业务实体类 |
| `store/DataKeys.java`（改） | key 命名/解析：`{entity}:{id}:{field}`；锁名；collection/docId 映射 |
| `store/RedisStore.java` | `get`/`set`/`del` 裸 JSON 字符串（`StringCodec`） |
| `store/MongoStore.java` | `load`/`upsert`/`bulkUpsert` JSON 文档（`{_id, v}`） |
| `lock/LockReq.java` | `(entity, id)` record |
| `lock/LockAcquireException.java` | 拿锁失败（all-or-nothing 回滚后抛） |
| `lock/LockLostException.java` | 持锁断言/提交门控失锁抛 |
| `lock/LockCtx.java` | 锁作用域接口 + `RedissonLockCtx` 实现：`get`/`put`/`close` |
| `lock/LockScope.java` | `lockAll` 接口 + `RedissonLockScope` 实现：排序/all-or-nothing/租约 |
| `overlay/DirtyLedger.java` | dirty 集合 `SADD`/`SMEMBERS`/`SREM`；`DIRTY_SET` 常量 |
| `overlay/CommitLua.java` | 原子 `SET key json; SADD dirty key` |
| `config/DataProperties.java` | `@ConfigurationProperties("game.data")`：池大小/地址/租约 |
| `config/DataAutoConfiguration.java` | `@AutoConfiguration`：`RedissonClient`/`MongoClient`/各原语 bean 装配 |
| `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 注册 `DataAutoConfiguration` |
| `src/test/.../LocalRedisMongo.java` | 集成测试基类：建/关 Redisson+Mongo 客户端，每用例 flush |
| 各 `*IT.java`/`*Test.java` | 单元 + 集成测试 |

依赖顺序（leaf-first）：`JsonCodec` → `DataKeys` → `lock 基础类型` → `RedisStore`/`MongoStore` → `DirtyLedger`/`CommitLua` → `LockCtx` → `LockScope` → `DataConfig` → `ArchUnit`。

---

## §7 正确性用例 → task 映射

| §7 用例 | 覆盖 task / 测试 |
| --- | --- |
| 1 回填竞态：不存在无锁回填路径 | Task 8 `LockCtxIT.getWithoutLockThrowsAndDoesNotSet` |
| 2 写路径 miss 加载 | Task 8 `LockCtxIT.getMissLoadsFromMongoAndBackfills` |
| 3 提交门控 | Task 8 `LockCtxIT.putWhenLockLostThrowsAndRedisUnchanged`（mock） |
| 4 原子 Lua | Task 7 `CommitLuaIT.commitSetsValueAndMarksDirty` |
| 5 跨实体加锁顺序 | Task 9 `LockScopeIT.sortReqsOrdersByPriorityThenId`（白盒）+ `lockAllAcquiresAll` |
| 6 all-or-nothing | Task 9 `LockScopeIT.lockAllRollsBackOnSecondFailure` |
| 7 try-with-resources 放锁 | Task 9 `LockScopeIT.closeReleasesLocksOnException` |
| 8 禁嵌套跨池 | Task 5 `MongoStoreIT` + Task 8 miss 路径覆盖 `GET Redis→LOAD Mongo→SET Redis`；结构性纪律，文档说明不机械断言「不同时持两池」 |
| 9 game-data 不认识业务实体 | Task 11 `DependencyRuleTest`（ArchUnit） |
| 10 resolveLock 命中本实体锁 | Task 8 `LockCtxIT.resolveLockHitsCorrectEntityLock`（mock） |
| 11 多实体部分提交不重试 | Task 8 `LockCtxIT.multiEntityPartialCommitLeavesInconsistency` + 单实体失锁无部分提交 |

---

## Task 1: JsonCodec

**Files:**
- Create: `game-data/src/main/java/io/github/brick/data/codec/JsonCodec.java`
- Test: `game-data/src/test/java/io/github/brick/data/codec/JsonCodecTest.java`

**Interfaces:**
- Consumes: Jackson `ObjectMapper`（Spring Boot 自带 jackson-databind）
- Produces: `JsonCodec` 类——`String encode(Object pojo)`、`<T> T decode(String json, Class<T> type)`；无参构造用默认 ObjectMapper，`JsonCodec(ObjectMapper)` 可注入。

- [ ] **Step 1: 写失败测试**

```java
package io.github.brick.data.codec;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonCodecTest {

    public record Sample(String name, int level, List<String> tags) {}

    private final JsonCodec codec = new JsonCodec();

    @Test
    void encodeDecodeRoundTrip() {
        Sample s = new Sample("alice", 7, List.of("a", "b"));
        String json = codec.encode(s);
        assertThat(json).contains("\"name\":\"alice\"").contains("\"level\":7");
        assertThat(codec.decode(json, Sample.class)).isEqualTo(s);
    }

    @Test
    void encodeNullProducesNullJson() {
        assertThat(codec.encode(null)).isEqualTo("null");
        assertThat(codec.decode("null", Sample.class)).isNull();
    }

    @Test
    void decodeBadJsonThrows() {
        assertThatThrownBy(() -> codec.decode("{not json", Sample.class))
                .isInstanceOf(RuntimeException.class);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl game-data test -Dtest=JsonCodecTest`
Expected: 编译失败，`JsonCodec` 不存在。

- [ ] **Step 3: 写最小实现**

```java
package io.github.brick.data.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * POJO↔JSON 序列化工具。不持有任何业务实体类（primitives spec §6）。
 * 对 POJO 形态（record 或带 setter 的普通类）透明——Jackson 自行处理。
 */
public final class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec() {
        this(new ObjectMapper());
    }

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(Object pojo) {
        try {
            return mapper.writeValueAsString(pojo);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 编码失败: " + pojo, e);
        }
    }

    public <T> T decode(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 解码失败: " + json, e);
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl game-data test -Dtest=JsonCodecTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add game-data/src/main/java/io/github/brick/data/codec/JsonCodec.java game-data/src/test/java/io/github/brick/data/codec/JsonCodecTest.java
git commit -m "feat(data): JsonCodec POJO↔JSON 编解码（Plan B §codec）"
```

---

## Task 2: DataKeys 扩展

**Files:**
- Modify: `game-data/src/main/java/io/github/brick/data/store/DataKeys.java`
- Test: `game-data/src/test/java/io/github/brick/data/store/DataKeysTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `DataKeys.key(entity,id,field)`、`entityOf(key)`、`idOf(key)`、`fieldOf(key)`、`collectionOf(key)`、`docIdOf(key)`；保留既有 `playerProfile`/`playerBag`/`lockKey`。key 约定 `{entity}:{id}:{field}`（field 不含 `:`）。

- [ ] **Step 1: 写失败测试（追加到既有测试类）**

```java
    @Test
    void keyComposesEntityIdField() {
        assertEquals("player:123:profile", DataKeys.key("player", 123, "profile"));
        assertEquals("guild:7:fund", DataKeys.key("guild", 7, "fund"));
    }

    @Test
    void entityOfParsesFirstSegment() {
        assertEquals("player", DataKeys.entityOf("player:123:profile"));
        assertEquals("guild", DataKeys.entityOf("guild:7:fund"));
    }

    @Test
    void idOfParsesSecondSegmentAsLong() {
        assertEquals(123L, DataKeys.idOf("player:123:profile"));
        assertEquals(7L, DataKeys.idOf("guild:7:fund"));
    }

    @Test
    void fieldOfParsesThirdSegment() {
        assertEquals("profile", DataKeys.fieldOf("player:123:profile"));
        assertEquals("fund", DataKeys.fieldOf("guild:7:fund"));
    }

    @Test
    void collectionOfIsEntityColonField() {
        assertEquals("player:profile", DataKeys.collectionOf("player:123:profile"));
        assertEquals("player:bag", DataKeys.collectionOf("player:123:bag"));
        assertEquals("guild:fund", DataKeys.collectionOf("guild:7:fund"));
    }

    @Test
    void docIdOfIsId() {
        assertEquals(123L, DataKeys.docIdOf("player:123:profile"));
    }

    @Test
    void parseRejectsMalformedKey() {
        assertThrows(IllegalArgumentException.class, () -> DataKeys.entityOf("no-colons"));
        assertThrows(IllegalArgumentException.class, () -> DataKeys.idOf("player:notnum:profile"));
    }
```

（文件顶部 `import static org.junit.jupiter.api.Assertions.assertThrows;` 与 `throws` 一起补。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl game-data test -Dtest=DataKeysTest`
Expected: FAIL，`key`/`entityOf` 等方法不存在。

- [ ] **Step 3: 写最小实现（改写 `DataKeys`）**

```java
package io.github.brick.data.store;

import java.util.List;

/**
 * 数据 key 命名常量 + 解析。key 约定钉死为 {@code {entity}:{id}:{field}}（如
 * {@code player:123:profile}），锁名 {@code lock:{entity}:{id}}（架构 spec §3.1、§4.1，
 * 并发修订 §1.1，primitives §3.1）。本类是该约定的唯一来源，业务域不得自造违反约定的 key。
 */
public final class DataKeys {

    private DataKeys() {}

    /** 数据 key：{@code {entity}:{id}:{field}}。field 不得含 {@code :}。 */
    public static String key(String entity, long id, String field) {
        return entity + ":" + id + ":" + field;
    }

    public static String playerProfile(long playerId) {
        return key("player", playerId, "profile");
    }

    public static String playerBag(long playerId) {
        return key("player", playerId, "bag");
    }

    /** 分布式锁名：{@code lock:{entity}:{id}}。 */
    public static String lockKey(String entity, long id) {
        return "lock:" + entity + ":" + id;
    }

    private static List<String> parts(String key) {
        String[] s = key.split(":", -1);
        if (s.length != 3 || s[0].isEmpty() || s[1].isEmpty() || s[2].isEmpty()) {
            throw new IllegalArgumentException("非法 key（应为 {entity}:{id}:{field}）: " + key);
        }
        return List.of(s[0], s[1], s[2]);
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

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl game-data test -Dtest=DataKeysTest`
Expected: PASS（含既有 3 个测试）。

- [ ] **Step 5: 提交**

```bash
git add game-data/src/main/java/io/github/brick/data/store/DataKeys.java game-data/src/test/java/io/github/brick/data/store/DataKeysTest.java
git commit -m "feat(data): DataKeys 补 entityOf/idOf/fieldOf/collectionOf/docIdOf 解析"
```

---

## Task 3: lock 基础类型（LockReq + 两个异常）

**Files:**
- Create: `game-data/src/main/java/io/github/brick/data/lock/LockReq.java`
- Create: `game-data/src/main/java/io/github/brick/data/lock/LockAcquireException.java`
- Create: `game-data/src/main/java/io/github/brick/data/lock/LockLostException.java`
- Test: `game-data/src/test/java/io/github/brick/data/lock/LockPrimitivesTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `LockReq(String entity, long id)` record + `static LockReq of(String, long)`；`LockAcquireException extends RuntimeException`；`LockLostException extends RuntimeException`。

- [ ] **Step 1: 写失败测试**

```java
package io.github.brick.data.lock;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LockPrimitivesTest {

    @Test
    void lockReqOfAndAccessors() {
        LockReq r = LockReq.of("guild", 7);
        assertThat(r.entity()).isEqualTo("guild");
        assertThat(r.id()).isEqualTo(7L);
    }

    @Test
    void lockReqEqualsByValue() {
        assertThat(LockReq.of("player", 1)).isEqualTo(LockReq.of("player", 1));
    }

    @Test
    void exceptionsAreRuntimeExceptions() {
        assertThat(new LockAcquireException("拿不到锁")).isInstanceOf(RuntimeException.class);
        assertThat(new LockLostException("失锁")).isInstanceOf(RuntimeException.class);
        assertThat(new LockAcquireException("x", new Throwable()).getCause()).isNotNull();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl game-data test -Dtest=LockPrimitivesTest`
Expected: 编译失败，类不存在。

- [ ] **Step 3: 写最小实现**

```java
package io.github.brick.data.lock;

/** 加锁请求：(entity, id)。 */
public record LockReq(String entity, long id) {
    public static LockReq of(String entity, long id) {
        return new LockReq(entity, id);
    }
}
```

```java
package io.github.brick.data.lock;

/**
 * 拿锁失败：{@code LockScope.lockAll} 在 all-or-nothing 回滚已获取的锁后抛此异常
 *（并发修订 §1.3，primitives §2.1、§3.3）。
 */
public class LockAcquireException extends RuntimeException {
    public LockAcquireException(String message) { super(message); }
    public LockAcquireException(String message, Throwable cause) { super(message, cause); }
}
```

```java
package io.github.brick.data.lock;

/**
 * 失锁：{@code LockCtx.get} 持锁断言失败、{@code LockCtx.put} 提交门控失败（isHeld=false）
 * 均抛此异常，使块内任意失锁点走同一条重试/中止路径（primitives §2.2、§4）。
 */
public class LockLostException extends RuntimeException {
    public LockLostException(String message) { super(message); }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl game-data test -Dtest=LockPrimitivesTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add game-data/src/main/java/io/github/brick/data/lock/LockReq.java game-data/src/main/java/io/github/brick/data/lock/LockAcquireException.java game-data/src/main/java/io/github/brick/data/lock/LockLostException.java game-data/src/test/java/io/github/brick/data/lock/LockPrimitivesTest.java
git commit -m "feat(data): lock 基础类型 LockReq/LockAcquireException/LockLostException"
```

---

## Task 4: 集成测试基类 + RedisStore

**Files:**
- Modify: `game-data/pom.xml`（加 `spring-boot-starter-test`，引入 AssertJ/Mockito/JUnit5）
- Create: `game-data/src/test/java/io/github/brick/data/LocalRedisMongo.java`
- Create: `game-data/src/main/java/io/github/brick/data/store/RedisStore.java`
- Test: `game-data/src/test/java/io/github/brick/data/store/RedisStoreIT.java`

**Interfaces:**
- Consumes: `RedissonClient`（Redisson 4.6.1）
- Produces: `RedisStore(RedissonClient)`——`String get(String key)`、`void set(String key, String json)`、`void del(String key)`；裸 JSON 字符串、`StringCodec`。`LocalRedisMongo` 基类供后续 IT 复用。

- [ ] **Step 1: 加测试依赖（改 `game-data/pom.xml`）**

在 `<dependencies>` 末尾、既有 `junit-jupiter` 之后，把测试栈换成 `spring-boot-starter-test`（它传递包含 junit-jupiter/assertj/mockito）：

```xml
        <!-- 删除下方独立的 junit-jupiter（spring-boot-starter-test 已含），替换为： -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
```

即移除原 `<dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>` 一整块，改为上面的 `spring-boot-starter-test`。

- [ ] **Step 2: 写失败测试 + 基类**

`LocalRedisMongo.java`：
```java
package io.github.brick.data;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 集成测试基类：连本地预起的 Redis(6379)/Mongo(27018)，每用例 flush（primitives §7）。
 * 不引 Testcontainers。
 */
public abstract class LocalRedisMongo {

    protected static final String MONGO_DB = "game_test";

    protected static RedissonClient redis;
    protected static MongoClient mongo;

    @BeforeAll
    static void startClients() {
        Config cfg = new Config();
        cfg.useSingleServer().setAddress("redis://127.0.0.1:6379").setConnectionPoolSize(8);
        redis = Redisson.create(cfg);
        mongo = MongoClients.create("mongodb://localhost:27018");
    }

    @AfterAll
    static void stopClients() {
        if (redis != null) redis.shutdown();
        if (mongo != null) mongo.close();
    }

    @BeforeEach
    void flushAll() {
        redis.getKeys().flushdb();
        mongo.getDatabase(MONGO_DB).drop();
    }

    /** 当前 Mongo 测试库。 */
    protected com.mongodb.client.MongoDatabase db() {
        return mongo.getDatabase(MONGO_DB);
    }
}
```

`RedisStoreIT.java`：
```java
package io.github.brick.data.store;

import io.github.brick.data.LocalRedisMongo;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RedisStoreIT extends LocalRedisMongo {

    private RedisStore store() {
        return new RedisStore(redis);
    }

    @Test
    void setGetRoundTripRawString() {
        RedisStore s = store();
        s.set("player:1:profile", "{\"name\":\"alice\"}");
        assertThat(s.get("player:1:profile")).isEqualTo("{\"name\":\"alice\"}");
    }

    @Test
    void getMissingReturnsNull() {
        assertThat(store().get("player:1:profile")).isNull();
    }

    @Test
    void delRemoves() {
        RedisStore s = store();
        s.set("player:1:profile", "{}");
        s.del("player:1:profile");
        assertThat(s.get("player:1:profile")).isNull();
    }

    @Test
    void storesRawStringNotQuotedJson() {
        RedisStore s = store();
        String raw = "player:1:profile";   // 一个裸字符串，若被 JsonJacksonCodec 会带引号
        s.set("k", raw);
        assertThat(s.get("k")).isEqualTo(raw);
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -q -pl game-data test -Dtest=RedisStoreIT`
Expected: 编译失败，`RedisStore` 不存在。（确保本地 Redis 已起：`redis-cli ping` 返回 PONG。）

- [ ] **Step 4: 写最小实现**

```java
package io.github.brick.data.store;

import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * Redis 裸 JSON 字符串封装：{@code get}/{@code set}/{@code del}（连接池 100~200，见 DataProperties）。
 * 必须用 {@link StringCodec}：否则 Redisson 默认 JsonJacksonCodec 会把字符串存成带引号的 JSON，
 * 与 {@code CommitLua} 写入的裸字符串不一致（决策 5）。
 */
public final class RedisStore {

    private final RedissonClient client;

    public RedisStore(RedissonClient client) {
        this.client = client;
    }

    public String get(String key) {
        return bucket(key).get();
    }

    public void set(String key, String json) {
        bucket(key).set(json);
    }

    public void del(String key) {
        bucket(key).delete();
    }

    private RBucket<String> bucket(String key) {
        return client.getBucket(key, StringCodec.INSTANCE);
    }
}
```

> 若编译报 `StringCodec.INSTANCE` 不存在，改用 `new org.redisson.client.codec.StringCodec()`（Redisson 4.6.1 有该单例；保留作首选）。

- [ ] **Step 5: 跑测试确认通过**

Run: `mvn -q -pl game-data test -Dtest=RedisStoreIT`
Expected: PASS（4 个用例）。

- [ ] **Step 6: 提交**

```bash
git add game-data/pom.xml game-data/src/test/java/io/github/brick/data/LocalRedisMongo.java game-data/src/main/java/io/github/brick/data/store/RedisStore.java game-data/src/test/java/io/github/brick/data/store/RedisStoreIT.java
git commit -m "feat(data): RedisStore 裸 JSON 字符串封装（StringCodec）+ 集成测试基类"
```

---

## Task 5: MongoStore

**Files:**
- Create: `game-data/src/main/java/io/github/brick/data/store/MongoStore.java`
- Test: `game-data/src/test/java/io/github/brick/data/store/MongoStoreIT.java`

**Interfaces:**
- Consumes: `MongoClient`（5.9.0 Sync）、`DataKeys.collectionOf`/`docIdOf`
- Produces: `MongoStore(MongoClient, String dbName)`——`String load(String key)`、`void upsert(String key, String json)`、`void bulkUpsert(Map<String,String> entries)`。文档形态 `{_id: id, v: json}`。

- [ ] **Step 1: 写失败测试**

```java
package io.github.brick.data.store;

import io.github.brick.data.LocalRedisMongo;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class MongoStoreIT extends LocalRedisMongo {

    private MongoStore store() {
        return new MongoStore(mongo, MONGO_DB);
    }

    @Test
    void loadMissingReturnsNull() {
        assertThat(store().load("player:1:profile")).isNull();
    }

    @Test
    void upsertThenLoadRoundTrip() {
        MongoStore s = store();
        s.upsert("player:1:profile", "{\"name\":\"alice\"}");
        assertThat(s.load("player:1:profile")).isEqualTo("{\"name\":\"alice\"}");
    }

    @Test
    void upsertIsIdempotentReplace() {
        MongoStore s = store();
        s.upsert("player:1:profile", "{\"v\":1}");
        s.upsert("player:1:profile", "{\"v\":2}");
        assertThat(s.load("player:1:profile")).isEqualTo("{\"v\":2}");
    }

    @Test
    void loadUsesCollectionEntityColonFieldAndIdDocId() {
        MongoStore s = store();
        s.upsert("player:123:profile", "{}");
        Document doc = db().getCollection("player:profile").find(new Document("_id", 123L)).first();
        assertThat(doc).isNotNull();
        assertThat(doc.get("_id")).isEqualTo(123L);
    }

    @Test
    void bulkUpsertWritesAllAndLoadsBack() {
        MongoStore s = store();
        s.bulkUpsert(Map.of(
                "player:1:profile", "{\"p\":1}",
                "player:2:bag", "{\"b\":2}",
                "guild:7:fund", "{\"f\":7}"));
        assertThat(s.load("player:1:profile")).isEqualTo("{\"p\":1}");
        assertThat(s.load("player:2:bag")).isEqualTo("{\"b\":2}");
        assertThat(s.load("guild:7:fund")).isEqualTo("{\"f\":7}");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl game-data test -Dtest=MongoStoreIT`
Expected: 编译失败。（确保本地 Mongo 已起：`mongosh --eval "db.runCommand({ping:1})"` 返回 `{ ok: 1 }`。）

- [ ] **Step 3: 写最小实现**

```java
package io.github.brick.data.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOneModel;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.WriteModel;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mongo JSON 文档封装：{@code load}/{@code upsert}/{@code bulkUpsert}。文档形态 {@code {_id, v}}，
 * 落盘零转换——存的就是 Redis 那份 JSON 字符串，不反序列化成 Java 对象（架构 spec §4.1、§4.3）。
 * collection = {@code {entity}:{field}}、_id = {id}（Mongo 映射决策 A，由 DataKeys 派生）。
 */
public final class MongoStore {

    private static final ReplaceOptions UPSERT = new ReplaceOptions().upsert(true);
    private final MongoClient client;
    private final String dbName;

    public MongoStore(MongoClient client, String dbName) {
        this.client = client;
        this.dbName = dbName;
    }

    public String load(String key) {
        Document doc = collection(key).find(byId(key)).first();
        return doc == null ? null : doc.getString("v");
    }

    public void upsert(String key, String json) {
        collection(key).replaceOne(byId(key), new Document("_id", DataKeys.docIdOf(key)).append("v", json), UPSERT);
    }

    public void bulkUpsert(Map<String, String> entries) {
        List<WriteModel<Document>> ops = new ArrayList<>(entries.size());
        entries.forEach((key, json) -> ops.add(new ReplaceOneModel<>(
                byId(key),
                new Document("_id", DataKeys.docIdOf(key)).append("v", json),
                UPSERT)));
        if (!ops.isEmpty()) {
            // 按 collection 分组各跑一次 bulkWrite（WriteModel 必须同集合）
            Map<String, List<WriteModel<Document>>> byCol = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, String> e : entries.entrySet()) {
                byCol.computeIfAbsent(DataKeys.collectionOf(e.getKey()), k -> new ArrayList<>())
                        .add(new ReplaceOneModel<>(byId(e.getKey()),
                                new Document("_id", DataKeys.docIdOf(e.getKey())).append("v", e.getValue()), UPSERT));
            }
            byCol.forEach((col, models) -> db().getCollection(col, Document.class).bulkWrite(models));
        }
    }

    private MongoCollection<Document> collection(String key) {
        return db().getCollection(DataKeys.collectionOf(key), Document.class);
    }

    private Document byId(String key) {
        return new Document("_id", DataKeys.docIdOf(key));
    }

    private MongoDatabase db() {
        return client.getDatabase(dbName);
    }
}
```

> `bulkUpsert` 内已按 collection 分组；上方 `ops` 列表仅为可读性，实际写入走 `byCol` 分组——实现时删除冗余 `ops` 段，只保留 `byCol` 分组循环。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl game-data test -Dtest=MongoStoreIT`
Expected: PASS（5 个用例）。

- [ ] **Step 5: 提交**

```bash
git add game-data/src/main/java/io/github/brick/data/store/MongoStore.java game-data/src/test/java/io/github/brick/data/store/MongoStoreIT.java
git commit -m "feat(data): MongoStore JSON 文档 upsert/load/bulkUpsert（collection=entity:field,_id=id）"
```

---

## Task 6: DirtyLedger

**Files:**
- Create: `game-data/src/main/java/io/github/brick/data/overlay/DirtyLedger.java`
- Test: `game-data/src/test/java/io/github/brick/data/overlay/DirtyLedgerIT.java`

**Interfaces:**
- Consumes: `RedissonClient`
- Produces: `DirtyLedger(RedissonClient)`——`static final String DIRTY_SET = "dirty"`；`void mark(String key)`、`Set<String> members()`、`void remove(String key)`、`void removeAll(Collection<String>)`。`RSet` 用 `StringCodec`。

- [ ] **Step 1: 写失败测试**

```java
package io.github.brick.data.overlay;

import io.github.brick.data.LocalRedisMongo;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class DirtyLedgerIT extends LocalRedisMongo {

    @Test
    void markAndMembers() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:profile");
        d.mark("player:2:bag");
        assertThat(d.members()).containsExactlyInAnyOrder("player:1:profile", "player:2:bag");
    }

    @Test
    void removeDropsMember() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:profile");
        d.mark("player:2:bag");
        d.remove("player:1:profile");
        assertThat(d.members()).containsExactly("player:2:bag");
    }

    @Test
    void removeAllDropsAll() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:profile");
        d.mark("guild:7:fund");
        d.removeAll(Set.of("player:1:profile", "guild:7:fund"));
        assertThat(d.members()).isEmpty();
    }

    @Test
    void membersStoresRawStringNotQuotedJson() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:profile");
        assertThat(d.members()).first().isEqualTo("player:1:profile");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl game-data test -Dtest=DirtyLedgerIT`
Expected: 编译失败。

- [ ] **Step 3: 写最小实现**

```java
package io.github.brick.data.overlay;

import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Collection;
import java.util.Set;

/**
 * dirty 集合账本：{@code SADD}/{@code SMEMBERS}/{@code SREM}，落盘进程（Plan C）消费。
 * dirty 集合名 {@link #DIRTY_SET} 是唯一来源，{@link CommitLua} 引用同一常量。
 * {@link RSet} 用 {@link StringCodec}：与 {@link CommitLua} Lua 写入的裸 key 字符串对齐（决策 5）。
 */
public final class DirtyLedger {

    public static final String DIRTY_SET = "dirty";

    private final RedissonClient client;

    public DirtyLedger(RedissonClient client) {
        this.client = client;
    }

    public void mark(String key) {
        set().add(key);
    }

    public Set<String> members() {
        return set().readAll();
    }

    public void remove(String key) {
        set().remove(key);
    }

    public void removeAll(Collection<String> keys) {
        if (!keys.isEmpty()) {
            set().removeAll(keys);
        }
    }

    private RSet<String> set() {
        return client.getSet(DIRTY_SET, StringCodec.INSTANCE);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl game-data test -Dtest=DirtyLedgerIT`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add game-data/src/main/java/io/github/brick/data/overlay/DirtyLedger.java game-data/src/test/java/io/github/brick/data/overlay/DirtyLedgerIT.java
git commit -m "feat(data): DirtyLedger dirty 集合 SADD/SMEMBERS/SREM（StringCodec）"
```

---

## Task 7: CommitLua

**Files:**
- Create: `game-data/src/main/java/io/github/brick/data/overlay/CommitLua.java`
- Test: `game-data/src/test/java/io/github/brick/data/overlay/CommitLuaIT.java`

**Interfaces:**
- Consumes: `RedissonClient`、`DirtyLedger.DIRTY_SET`
- Produces: `CommitLua(RedissonClient)`——`void commit(String key, String json)`。原子 `SET key json; SADD dirty key`，不解析 JSON，不暴露可分离的 `SET`/`SADD`。

- [ ] **Step 1: 写失败测试（§7 用例 4）**

```java
package io.github.brick.data.overlay;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CommitLuaIT extends LocalRedisMongo {

    @Test
    void commitSetsValueAndMarksDirty() {
        CommitLua lua = new CommitLua(redis);
        lua.commit("player:1:profile", "{\"name\":\"alice\"}");

        RedisStore rs = new RedisStore(redis);
        assertThat(rs.get("player:1:profile")).isEqualTo("{\"name\":\"alice\"}");
        assertThat(new DirtyLedger(redis).members()).containsExactly("player:1:profile");
    }

    @Test
    void commitOverwritesExistingValue() {
        CommitLua lua = new CommitLua(redis);
        lua.commit("player:1:profile", "{\"v\":1}");
        lua.commit("player:1:profile", "{\"v\":2}");
        assertThat(new RedisStore(redis).get("player:1:profile")).isEqualTo("{\"v\":2}");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl game-data test -Dtest=CommitLuaIT`
Expected: 编译失败。

- [ ] **Step 3: 写最小实现**

```java
package io.github.brick.data.overlay;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import java.util.List;

/**
 * 原子提交脚本：{@code SET key json; SADD dirty key}，不解析 JSON（架构 spec §4.2，primitives §3.2）。
 * 覆盖写唯一提交点——不暴露可分离的 {@code SET} 与 {@code SADD}，避免「SET 成功 SADD 失败致数据在
 * Redis 却不标脏、永不下沉 Mongo、Redis 崩即永久丢失」（架构 spec §4.2）。
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
        client.getScript(RScript.Mode.ALL).eval(
                RScript.Mode.ALL,
                SCRIPT,
                RScript.ReturnType.INTEGER,
                List.of(key, DirtyLedger.DIRTY_SET),
                json);
    }
}
```

> Redisson `getScript()` 无参重载返回 `RScript`；`eval(mode, script, type, keys, args...)` 形参 keys 与 args 分别传入。若 4.6.1 API 形参不同，以编译器为准调整，语义保持：KEYS[1]=data key、KEYS[2]=dirty set、ARGV[1]=json。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl game-data test -Dtest=CommitLuaIT`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add game-data/src/main/java/io/github/brick/data/overlay/CommitLua.java game-data/src/test/java/io/github/brick/data/overlay/CommitLuaIT.java
git commit -m "feat(data): CommitLua 原子 SET+SADD 提交脚本（不解析 JSON）"
```

---

## Task 8: LockCtx

**Files:**
- Create: `game-data/src/main/java/io/github/brick/data/lock/LockCtx.java`（接口 + `RedissonLockCtx` 实现）
- Test: `game-data/src/test/java/io/github/brick/data/lock/LockCtxIT.java`

**Interfaces:**
- Consumes: `RLock`（Redisson）、`RedisStore`、`MongoStore`、`CommitLua`、`JsonCodec`、`DataKeys`、`LockLostException`
- Produces: `LockCtx` 接口——`<T> T get(String key, Class<T> type)`、`<T> void put(String key, T pojo)`、`void close()`；`RedissonLockCtx` 实现持有有序 `RLock` 列表 + `Map<String,RLock>`（key=锁名），`get`/`put` 经 `resolveLock(key)` 命中本实体锁。

**构造签名**（供 `LockScope` 与测试构造）：
```java
RedissonLockCtx(List<RLock> acquiredInOrder, RedisStore redis, MongoStore mongo, CommitLua commitLua, JsonCodec codec)
```
`acquiredInOrder` 为按 acquire 顺序的列表（close 逆序释放）。内部建 `Map<lockName, RLock>`，`lockName = DataKeys.lockKey(entityOf(key), idOf(key))`。

- [ ] **Step 1: 写失败测试（§7 用例 1、2、3、10、11）**

```java
package io.github.brick.data.lock;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockCtxIT extends LocalRedisMongo {

    private static final String PROFILE = DataKeys.playerProfile(1);

    private record Profile(String name, int coin) {}

    /** 直接构造持单锁的 LockCtx（绕过 LockScope，便于聚焦 ctx 行为）。 */
    private LockCtx singleLockCtx(String entity, long id) {
        RLock lock = redis.getLock(DataKeys.lockKey(entity, id));
        lock.lock(10, java.util.concurrent.TimeUnit.SECONDS);
        return new RedissonLockCtx(
                List.of(lock),
                new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec());
    }

    // §7 用例 1：不存在无锁回填路径
    @Test
    void getWithoutLockThrowsAndDoesNotSet() {
        LockCtx ctx = new RedissonLockCtx(
                List.of(), new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec());   // 空锁列表
        assertThatThrownBy(() -> ctx.get(PROFILE, Profile.class))
                .isInstanceOf(LockLostException.class);
        assertThat(new RedisStore(redis).get(PROFILE)).isNull();   // 没回填
    }

    // §7 用例 2：写路径 miss 加载（get miss 从 Mongo 加载并回填）
    @Test
    void getMissLoadsFromMongoAndBackfills() {
        new MongoStore(mongo, MONGO_DB).upsert(PROFILE, "{\"name\":\"alice\",\"coin\":100}");
        try (LockCtx ctx = singleLockCtx("player", 1)) {
            Profile p = ctx.get(PROFILE, Profile.class);
            assertThat(p).isEqualTo(new Profile("alice", 100));
            // 回填后 Redis 命中
            assertThat(new RedisStore(redis).get(PROFILE)).contains("\"name\":\"alice\"");
            assertThat(ctx.get(PROFILE, Profile.class).coin()).isEqualTo(100);
        }
    }

    @Test
    void putCommitsUnderLock() {
        try (LockCtx ctx = singleLockCtx("player", 1)) {
            ctx.put(PROFILE, new Profile("bob", 50));
        }
        assertThat(new RedisStore(redis).get(PROFILE)).contains("\"name\":\"bob\"");
        assertThat(new DirtyLedger(redis).members()).contains(PROFILE);
    }

    // §7 用例 3：提交门控失锁绝不写（mock RLock isHeld=false）
    @Test
    void putWhenLockLostThrowsAndRedisUnchanged() {
        RLock mockLock = org.mockito.Mockito.mock(RLock.class);
        org.mockito.Mockito.when(mockLock.isHeldByCurrentThread()).thenReturn(false);
        LockCtx ctx = new RedissonLockCtx(
                List.of(mockLock), new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec());
        assertThatThrownBy(() -> ctx.put(PROFILE, new Profile("x", 1)))
                .isInstanceOf(LockLostException.class);
        assertThat(new RedisStore(redis).get(PROFILE)).isNull();
        assertThat(new DirtyLedger(redis).members()).doesNotContain(PROFILE);
    }

    // §7 用例 10：resolveLock 命中本实体锁（player:1 失锁而 player:2 仍持，对 player:1 key 操作命中 player:1 锁）
    @Test
    void resolveLockHitsCorrectEntityLock() {
        RLock l1 = org.mockito.Mockito.mock(RLock.class);
        RLock l2 = org.mockito.Mockito.mock(RLock.class);
        org.mockito.Mockito.when(l1.isHeldByCurrentThread()).thenReturn(false);
        org.mockito.Mockito.when(l2.isHeldByCurrentThread()).thenReturn(true);
        Map<String, RLock> byName = Map.of(
                DataKeys.lockKey("player", 1), l1,
                DataKeys.lockKey("player", 2), l2);
        LockCtx ctx = new RedissonLockCtx(byName, List.of(l1, l2),
                new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec());
        assertThatThrownBy(() -> ctx.get(DataKeys.playerProfile(1), Profile.class))
                .isInstanceOf(LockLostException.class);   // 命中 player:1 锁而非 player:2
    }

    // §7 用例 11（单实体）：失锁那次未写、无部分提交，可安全重试
    @Test
    void singleEntityPutLostLockNoPartialCommit() {
        RLock mockLock = org.mockito.Mockito.mock(RLock.class);
        org.mockito.Mockito.when(mockLock.isHeldByCurrentThread()).thenReturn(false);
        LockCtx ctx = new RedissonLockCtx(
                List.of(mockLock), new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec());
        assertThatThrownBy(() -> ctx.put(PROFILE, new Profile("x", 1)))
                .isInstanceOf(LockLostException.class);
        assertThat(new RedisStore(redis).get(PROFILE)).isNull();
    }
}
```

> 上面 `resolveLockHitsCorrectEntityLock` 用了一个按锁名建 `Map` 的额外构造器；故 `RedissonLockCtx` 需提供两个构造器：`RedissonLockCtx(List<RLock> acquired, …)`（内部按 `DataKeys.lockKey` 推导锁名建 Map）与 `RedissonLockCtx(Map<String,RLock> locksByName, List<RLock> order, …)`（测试直传）。实现见 Step 3。

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl game-data test -Dtest=LockCtxIT`
Expected: 编译失败，`LockCtx`/`RedissonLockCtx` 不存在。

- [ ] **Step 3: 写最小实现**

```java
package io.github.brick.data.lock;

import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.redisson.api.RLock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 锁作用域：承载锁内读与覆盖写。必须用 try-with-resources 包住，{@code close()} 逆序释放全部锁
 *（primitives §2.2、§3.1）。
 */
public interface LockCtx extends AutoCloseable {

    /** 锁内读。miss 则锁内从 Mongo 加载并普通 SET 回填。失锁抛 {@link LockLostException}。 */
    <T> T get(String key, Class<T> type);

    /** 锁内覆盖写：序列化 + isHeld 门控 + 原子 Lua SET+SADD。单 key。失锁抛 {@link LockLostException}。 */
    <T> void put(String key, T pojo);

    @Override
    void close();
}
```

```java
package io.github.brick.data.lock;

import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.redisson.api.RLock;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redisson 实现。持有按 acquire 顺序的锁列表（close 逆序释放）+ 锁名→RLock 映射（resolveLock 命中本实体锁）。
 * 安全不变量在此一处闭环：持锁断言、提交门控、原子提交、逆序放锁（primitives §2.2、§3.1）。
 */
public final class RedissonLockCtx implements LockCtx {

    private final List<RLock> order;                       // acquire 顺序，close 逆序
    private final Map<String, RLock> locksByName;          // lock:{entity}:{id} → RLock
    private final RedisStore redis;
    private final MongoStore mongo;
    private final CommitLua commitLua;
    private final JsonCodec codec;
    private boolean closed = false;

    /** 生产构造：由 acquire 顺序列表内部推导锁名（锁名 = DataKeys.lockKey(entity,id)，需调用方保证
     * 列表中每把锁的实体/id 与被操作的 data key 同源——LockScope 保证）。 */
    public RedissonLockCtx(List<RLock> acquiredInOrder, RedisStore redis, MongoStore mongo,
                           CommitLua commitLua, JsonCodec codec) {
        this.order = List.copyOf(acquiredInOrder);
        this.locksByName = new LinkedHashMap<>();
        // 锁名未知（RLock 自身不暴露 entity/id），故此构造器仅用于 N=1 或全同实体场景：
        // 单锁时锁名由 data key 推导，可命中。多锁异实体请用下方 Map 构造器（LockScope 走那条）。
        if (order.size() == 1) {
            // 锁名占位为 RLock.getName()——但 resolveLock 用 DataKeys 推导的锁名查不到。
            // 故多实体必须走 Map 构造器。为避免误用，此处仅放 RLock 自身 name 映射。
            this.locksByName.put(order.get(0).getName(), order.get(0));
        }
        this.redis = redis;
        this.mongo = mongo;
        this.commitLua = commitLua;
        this.codec = codec;
    }

    /** LockScope/测试用：直传锁名映射 + 顺序。锁名 = DataKeys.lockKey(entity,id)。 */
    public RedissonLockCtx(Map<String, RLock> locksByName, List<RLock> order,
                           RedisStore redis, MongoStore mongo, CommitLua commitLua, JsonCodec codec) {
        this.order = List.copyOf(order);
        this.locksByName = new LinkedHashMap<>(locksByName);
        this.redis = redis;
        this.mongo = mongo;
        this.commitLua = commitLua;
        this.codec = codec;
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        RLock lock = resolveLock(key);
        if (!lock.isHeldByCurrentThread()) {
            throw new LockLostException("读路径失锁: " + key);
        }
        String v = redis.get(key);
        if (v == null) {
            v = mongo.load(key);          // 锁内从 Mongo 加载
            if (v != null) {
                redis.set(key, v);         // 普通回填（持锁，无并发写，无需 SET NX）
            }
        }
        return codec.decode(v, type);
    }

    @Override
    public <T> void put(String key, T pojo) {
        String json = codec.encode(pojo);
        RLock lock = resolveLock(key);
        if (!lock.isHeldByCurrentThread()) {     // 提交门控
            throw new LockLostException("提交门控失锁: " + key);
        }
        commitLua.commit(key, json);             // 唯一提交点，原子 SET+SADD
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // 逆序释放，异常路径也执行（try-with-resources 强制）；已由 TTL 释放的 unlock 抛异常忽略
        for (int i = order.size() - 1; i >= 0; i--) {
            try {
                order.get(i).unlock();
            } catch (Exception ignored) {
                // 锁已过期/非本线程持有，忽略
            }
        }
    }

    /** 由 data key 解析到本实体的 RLock（primitives §3.1 resolveLock 命中本实体锁）。 */
    private RLock resolveLock(String key) {
        String lockName = DataKeys.lockKey(DataKeys.entityOf(key), DataKeys.idOf(key));
        RLock lock = locksByName.get(lockName);
        if (lock == null) {
            throw new LockLostException("未持锁即调用: " + key + "（锁名 " + lockName + "）");
        }
        return lock;
    }
}
```

> 两个构造器中，**单参 `List<RLock>` 构造器无法可靠推导锁名**（`RLock` 不暴露 entity/id），故 LockScope 必须用 `Map` 构造器。实现注意：上方单参构造器的 `locksByName` 用 `RLock.getName()` 作 key，与 `resolveLock` 用 `DataKeys.lockKey(...)` 推导的 key **对不上**——这是有意暴露的约束：**LockScope 一定走 `Map` 构造器**。为避免误用，实现时把单参构造器改为抛 `UnsupportedOperationException` 或干脆删除，只保留 `Map` 构造器；测试 `singleLockCtx` 与 `getWithoutLockThrowsAndDoesNotSet`/`putWhenLockLostThrows…` 改为通过 `Map` 构造器构建：

```java
// 测试辅助改为：
private LockCtx singleLockCtx(String entity, long id) {
    RLock lock = redis.getLock(DataKeys.lockKey(entity, id));
    lock.lock(10, java.util.concurrent.TimeUnit.SECONDS);
    return new RedissonLockCtx(
            Map.of(DataKeys.lockKey(entity, id), lock), List.of(lock),
            new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
            new CommitLua(redis), new JsonCodec());
}
```

并把 Step 3 实现里**只保留 `Map` 构造器**（删除单参 `List` 构造器），`getWithoutLock…`/`putWhenLockLost…`/`singleEntityPutLostLock…` 测试用 `Map.of()`（空）或 mock 构造。空 `Map` 时 `resolveLock` 因 `locksByName.get(lockName)==null` 抛 `LockLostException`，满足用例 1。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl game-data test -Dtest=LockCtxIT`
Expected: PASS（6 个用例）。

- [ ] **Step 5: 提交**

```bash
git add game-data/src/main/java/io/github/brick/data/lock/LockCtx.java game-data/src/test/java/io/github/brick/data/lock/LockCtxIT.java
git commit -m "feat(data): LockCtx 锁作用域 get/put/close + resolveLock + isHeld 门控"
```

---

## Task 9: LockScope

**Files:**
- Create: `game-data/src/main/java/io/github/brick/data/lock/LockScope.java`（接口 + `RedissonLockScope` 实现）
- Test: `game-data/src/test/java/io/github/brick/data/lock/LockScopeIT.java`

**Interfaces:**
- Consumes: `RedissonClient`、`RedisStore`、`MongoStore`、`CommitLua`、`JsonCodec`、`LockReq`、`LockAcquireException`、`LockCtx`
- Produces: `LockScope` 接口——`LockCtx lockAll(List<LockReq> reqs)`；`RedissonLockScope(client, redis, mongo, commitLua, codec, waitMillis, leaseSeconds, priority)` 实现。`static List<LockReq> sortReqs(List<LockReq>, Map<String,Integer> priority)` 包私有（白盒测排序）。租约固定 `leaseSeconds=10` 无看门狗，`tryLock(waitMillis, leaseSeconds, SECONDS)`，all-or-nothing。

- [ ] **Step 1: 写失败测试（§7 用例 5、6、7）**

```java
package io.github.brick.data.lock;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LockScopeIT extends LocalRedisMongo {

    private static final Map<String, Integer> PRIORITY = Map.of("guild", 0, "player", 1);

    private LockScope scope() {
        return new RedissonLockScope(redis, new RedisStore(redis), new MongoStore(mongo, MONGO_DB),
                new CommitLua(redis), new JsonCodec(), 1000L, 10L, PRIORITY);
    }

    // §7 用例 5：跨实体加锁顺序——传入乱序 [player, guild] 实际按 guild>player
    @Test
    void sortReqsOrdersByPriorityThenId() {
        List<LockReq> reqs = List.of(
                LockReq.of("player", 3), LockReq.of("guild", 9),
                LockReq.of("player", 1), LockReq.of("guild", 2));
        List<LockReq> sorted = RedissonLockScope.sortReqs(reqs, PRIORITY);
        assertThat(sorted).containsExactly(
                LockReq.of("guild", 2), LockReq.of("guild", 9),
                LockReq.of("player", 1), LockReq.of("player", 3));
    }

    @Test
    void sortReqsRejectsUnknownEntity() {
        assertThatThrownBy(() -> RedissonLockScope.sortReqs(List.of(LockReq.of("auction", 1)), PRIORITY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("auction");
    }

    @Test
    void lockAllAcquiresAllAndReleasesOnClose() {
        try (LockCtx ctx = scope().lockAll(List.of(LockReq.of("guild", 7), LockReq.of("player", 1)))) {
            assertThat(redis.getLock(DataKeys.lockKey("guild", 7)).isLocked()).isTrue();
            assertThat(redis.getLock(DataKeys.lockKey("player", 1)).isLocked()).isTrue();
        }
        assertThat(redis.getLock(DataKeys.lockKey("guild", 7)).isLocked()).isFalse();
        assertThat(redis.getLock(DataKeys.lockKey("player", 1)).isLocked()).isFalse();
    }

    // §7 用例 6：all-or-nothing——第二把锁 acquire 失败 → 第一把已释放 + 抛 LockAcquireException
    @Test
    void lockAllRollsBackOnSecondFailure() {
        // 另一客户端长租占住 guild:7，使 lockAll 的第一把 tryLock 失败
        Config cfg = new Config();
        cfg.useSingleServer().setAddress("redis://127.0.0.1:6379").setConnectionPoolSize(4);
        try (RedissonClient blocker = Redisson.create(cfg)) {
            RLock held = blocker.getLock(DataKeys.lockKey("guild", 7));
            held.lock(30, java.util.concurrent.TimeUnit.SECONDS);
            try {
                assertThatThrownBy(() -> scope().lockAll(List.of(
                        LockReq.of("guild", 7), LockReq.of("player", 1))))
                        .isInstanceOf(LockAcquireException.class);
                // player:1（本应被回滚释放）未被占住
                assertThat(redis.getLock(DataKeys.lockKey("player", 1)).isLocked()).isFalse();
            } finally {
                held.unlock();
            }
        }
    }

    // §7 用例 7：try-with-resources 块内抛异常 → 锁已释放（不等 TTL）
    @Test
    void closeReleasesLocksOnException() {
        try {
            try (LockCtx ctx = scope().lockAll(List.of(LockReq.of("player", 1)))) {
                throw new IllegalStateException("boom");
            }
        } catch (IllegalStateException ignored) {
        }
        assertThat(redis.getLock(DataKeys.lockKey("player", 1)).isLocked()).isFalse();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl game-data test -Dtest=LockScopeIT`
Expected: 编译失败。

- [ ] **Step 3: 写最小实现**

```java
package io.github.brick.data.lock;

import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 按全局类型优先级 + 同类型 ID 序排序后依次 acquire，all-or-nothing（primitives §2.1、§3.3）。
 */
public interface LockScope {
    LockCtx lockAll(List<LockReq> reqs);
}
```

```java
package io.github.brick.data.lock;

import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.store.DataKeys;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class RedissonLockScope implements LockScope {

    private final RedissonClient client;
    private final RedisStore redis;
    private final MongoStore mongo;
    private final CommitLua commitLua;
    private final JsonCodec codec;
    private final long waitMillis;
    private final long leaseSeconds;
    private final Map<String, Integer> priority;   // entity → 优先级（小者先加锁）

    public RedissonLockScope(RedissonClient client, RedisStore redis, MongoStore mongo,
                             CommitLua commitLua, JsonCodec codec,
                             long waitMillis, long leaseSeconds,
                             Map<String, Integer> priority) {
        this.client = client;
        this.redis = redis;
        this.mongo = mongo;
        this.commitLua = commitLua;
        this.codec = codec;
        this.waitMillis = waitMillis;
        this.leaseSeconds = leaseSeconds;
        this.priority = priority;
    }

    @Override
    public LockCtx lockAll(List<LockReq> reqs) {
        List<LockReq> sorted = sortReqs(reqs, priority);
        List<RLock> acquired = new ArrayList<>(sorted.size());
        Map<String, RLock> byName = new LinkedHashMap<>();
        try {
            for (LockReq r : sorted) {
                String lockName = DataKeys.lockKey(r.entity(), r.id());
                RLock lock = client.getLock(lockName);
                boolean ok = lock.tryLock(waitMillis, leaseSeconds, TimeUnit.SECONDS);  // 固定租约无看门狗
                if (!ok) {
                    throw new LockAcquireException("加锁失败（被占）: " + lockName);
                }
                acquired.add(lock);
                byName.put(lockName, lock);
            }
        } catch (InterruptedException e) {
            rollback(acquired);
            Thread.currentThread().interrupt();
            throw new LockAcquireException("加锁被中断", e);
        } catch (LockAcquireException e) {
            rollback(acquired);
            throw e;
        } catch (RuntimeException e) {
            rollback(acquired);
            throw new LockAcquireException("加锁异常", e);
        }
        return new RedissonLockCtx(byName, acquired, redis, mongo, commitLua, codec);
    }

    /** 排序：按实体类型全局优先级升序，同类型内按 id 升序（并发修订 §1.3）。 */
    static List<LockReq> sortReqs(List<LockReq> reqs, Map<String, Integer> priority) {
        List<LockReq> copy = new ArrayList<>(reqs);
        copy.sort(Comparator
                .comparingInt((LockReq r) -> {
                    Integer p = priority.get(r.entity());
                    if (p == null) {
                        throw new IllegalArgumentException("未登记的实体类型: " + r.entity() + "，请在优先级表登记");
                    }
                    return p;
                })
                .thenComparingLong(LockReq::id));
        return copy;
    }

    private void rollback(List<RLock> acquired) {
        for (int i = acquired.size() - 1; i >= 0; i--) {
            try {
                acquired.get(i).unlock();
            } catch (Exception ignored) { }
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl game-data test -Dtest=LockScopeIT`
Expected: PASS（5 个用例）。

- [ ] **Step 5: 提交**

```bash
git add game-data/src/main/java/io/github/brick/data/lock/LockScope.java game-data/src/test/java/io/github/brick/data/lock/LockScopeIT.java
git commit -m "feat(data): LockScope lockAll 排序/all-or-nothing/固定租约无看门狗"
```

---

## Task 10: DataProperties + DataAutoConfiguration（连接池 + Spring 装配）

**Files:**
- Create: `game-data/src/main/java/io/github/brick/data/config/DataProperties.java`
- Create: `game-data/src/main/java/io/github/brick/data/config/DataAutoConfiguration.java`
- Create: `game-data/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `game-data/src/test/resources/application-data.yml`（可选，测试属性）
- Test: `game-data/src/test/java/io/github/brick/data/config/DataAutoConfigurationTest.java`

**Interfaces:**
- Consumes: Spring Boot 4 autoconfig、Redisson、MongoDB Driver
- Produces: `DataProperties`（`game.data.*`）、`DataAutoConfiguration`（`@AutoConfiguration` 注册于 imports 文件）——提供 `RedissonClient`（`@ConditionalOnMissingBean`，覆盖 redisson starter）、`MongoClient`（池 50-100）、`JsonCodec`/`RedisStore`/`MongoStore`/`CommitLua`/`DirtyLedger`/`LockScope` bean。池大小 Redis 100-200、Mongo 50-100。

- [ ] **Step 1: 写失败测试（上下文装载 + bean 装配 + 池大小绑定，无需 live 服务——client 懒连接）**

```java
package io.github.brick.data.config;

import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.lock.LockScope;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = DataAutoConfiguration.class,
        properties = "game.data.redisPoolSize=150")
class DataAutoConfigurationTest {

    @Autowired ApplicationContext ctx;
    @Autowired DataProperties props;
    @Autowired LockScope lockScope;

    @Test
    void wiresAllPrimitives() {
        assertThat(ctx.getBean(RedisStore.class)).isNotNull();
        assertThat(ctx.getBean(MongoStore.class)).isNotNull();
        assertThat(ctx.getBean(CommitLua.class)).isNotNull();
        assertThat(ctx.getBean(DirtyLedger.class)).isNotNull();
        assertThat(ctx.getBean(JsonCodec.class)).isNotNull();
        assertThat(ctx.getBean(LockScope.class)).isSameAs(lockScope);
    }

    @Test
    void propertiesBound() {
        assertThat(props.getRedisPoolSize()).isEqualTo(150);
        assertThat(props.getMongoPoolSize()).isBetween(50, 100);
        assertThat(props.getLockLeaseSeconds()).isEqualTo(10L);   // 固定租约无看门狗
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -q -pl game-data test -Dtest=DataAutoConfigurationTest`
Expected: 编译失败/上下文加载失败，类不存在。

- [ ] **Step 3: 写最小实现**

`DataProperties.java`：
```java
package io.github.brick.data.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * game-data 运行时配置（primitives §3.4 连接池 + 决策 2 租约）。
 * 池大小显式设限：Redis 100~200、Mongo 50~100（架构 spec §3.1）。
 */
@ConfigurationProperties(prefix = "game.data")
public class DataProperties {

    private String redisAddress = "redis://127.0.0.1:6379";
    private int redisPoolSize = 100;            // 100~200
    private String mongoUri = "mongodb://localhost:27018";
    private String mongoDb = "game";
    private int mongoPoolSize = 50;              // 50~100
    private long lockWaitMillis = 2000L;         // fail-fast 等待（决策 2）
    private long lockLeaseSeconds = 10L;         // 固定租约无看门狗

    // getters/setters
    public String getRedisAddress() { return redisAddress; }
    public void setRedisAddress(String v) { this.redisAddress = v; }
    public int getRedisPoolSize() { return redisPoolSize; }
    public void setRedisPoolSize(int v) { this.redisPoolSize = v; }
    public String getMongoUri() { return mongoUri; }
    public void setMongoUri(String v) { this.mongoUri = v; }
    public String getMongoDb() { return mongoDb; }
    public void setMongoDb(String v) { this.mongoDb = v; }
    public int getMongoPoolSize() { return mongoPoolSize; }
    public void setMongoPoolSize(int v) { this.mongoPoolSize = v; }
    public long getLockWaitMillis() { return lockWaitMillis; }
    public void setLockWaitMillis(long v) { this.lockWaitMillis = v; }
    public long getLockLeaseSeconds() { return lockLeaseSeconds; }
    public void setLockLeaseSeconds(long v) { this.lockLeaseSeconds = v; }
}
```

`DataAutoConfiguration.java`：
```java
package io.github.brick.data.config;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.MongoClientSettings;
import io.github.brick.data.codec.JsonCodec;
import io.github.brick.data.lock.LockScope;
import io.github.brick.data.lock.RedissonLockScope;
import io.github.brick.data.overlay.CommitLua;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * game-data 自动装配：Redis 池 100~200、Mongo 池 50~100（架构 spec §3.1）。
 * 自定义 {@link RedissonClient}（@ConditionalOnMissingBean，使 redisson-spring-boot-starter 的
 * RedissonAutoConfigurationV4 退避），统一池大小与地址。
 */
@AutoConfiguration
@AutoConfigureBefore(name = "org.redisson.spring.starter.RedissonAutoConfigurationV4")
@EnableConfigurationProperties(DataProperties.class)
public class DataAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    RedissonClient redissonClient(DataProperties p) {
        Config c = new Config();
        c.useSingleServer().setAddress(p.getRedisAddress())
                .setConnectionPoolSize(p.getRedisPoolSize());
        return Redisson.create(c);
    }

    @Bean(destroyMethod = "close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    MongoClient mongoClient(DataProperties p) {
        return MongoClients.create(MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(p.getMongoUri()))
                .applyToConnectionPoolSettings(b -> b
                        .maxSize(p.getMongoPoolSize())
                        .minSize(Math.max(1, p.getMongoPoolSize() / 4)))
                .build());
    }

    @Bean JsonCodec jsonCodec() { return new JsonCodec(); }
    @Bean RedisStore redisStore(RedissonClient c) { return new RedisStore(c); }
    @Bean MongoStore mongoStore(MongoClient c, DataProperties p) { return new MongoStore(c, p.getMongoDb()); }
    @Bean CommitLua commitLua(RedissonClient c) { return new CommitLua(c); }
    @Bean DirtyLedger dirtyLedger(RedissonClient c) { return new DirtyLedger(c); }

    @Bean
    LockScope lockScope(RedissonClient c, RedisStore rs, MongoStore ms, CommitLua cl,
                        JsonCodec jc, DataProperties p) {
        return new RedissonLockScope(c, rs, ms, cl, jc,
                p.getLockWaitMillis(), p.getLockLeaseSeconds(),
                Map.of("guild", 0, "player", 1));   // 菜鸟期全局类型优先级
    }
}
```

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：
```
io.github.brick.data.config.DataAutoConfiguration
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -q -pl game-data test -Dtest=DataAutoConfigurationTest`
Expected: PASS（上下文装载，bean 装配，属性绑定）。client 懒连接，无需 live Redis/Mongo。

> 若 `@AutoConfigureBefore` 的类名在 redisson-spring-boot-starter 4.6.1 不是 `RedissonAutoConfigurationV4`，编译/启动会报「找不到前置配置类」——以 starter 实际类名为准（查 `redisson-spring-boot-starter` 的 `AutoConfiguration.imports`）。若仍冲突，回退方案：在 `DataAutoConfigurationTest` 与消费应用的测试里加 `spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4`（沿用骨架既有做法）。

- [ ] **Step 5: 提交**

```bash
git add game-data/src/main/java/io/github/brick/data/config/DataProperties.java game-data/src/main/java/io/github/brick/data/config/DataAutoConfiguration.java game-data/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports game-data/src/test/java/io/github/brick/data/config/DataAutoConfigurationTest.java
git commit -m "feat(data): DataProperties + DataAutoConfiguration 连接池/Spring 装配（Redis 100-200/Mongo 50-100）"
```

---

## Task 11: ArchUnit 依赖规则（§7 用例 9）

**Files:**
- Modify: `pom.xml`（根 POM `dependencyManagement` 钉 archunit 版本）
- Modify: `game-data/pom.xml`（加 archunit-junit5 test 依赖）
- Test: `game-data/src/test/java/io/github/brick/data/DependencyRuleTest.java`

**Interfaces:**
- Consumes: ArchUnit
- Produces: 强制 `io.github.brick.data..` 不依赖 `io.github.brick.web..`/`io.github.brick.contract..`/`io.github.brick.dbserver..`（game-data 不认识业务实体）。

- [ ] **Step 1: 钉 archunit 版本（根 `pom.xml` `<properties>` 加一行）**

```xml
		<archunit.version>1.4.0</archunit.version>
```

`<dependencyManagement>/<dependencies>` 末尾加：
```xml
		<!-- ArchUnit（依赖规则校验，非 Spring Boot 管理项） -->
		<dependency>
			<groupId>com.tngtech.archunit</groupId>
			<artifactId>archunit-junit5</artifactId>
			<version>${archunit.version}</version>
		</dependency>
```

`game-data/pom.xml` 测试依赖加：
```xml
		<dependency>
			<groupId>com.tngtech.archunit</groupId>
			<artifactId>archunit-junit5</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: 写失败测试**

```java
package io.github.brick.data;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class DependencyRuleTest {

    private final JavaClasses classes = new ClassFileImporter()
            .importPackages("io.github.brick.data..");

    @Test
    void dataDoesNotDependOnBusinessOrContractOrDbserver() {
        noClasses().that().resideInAPackage("io.github.brick.data..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("io.github.brick.web..",
                        "io.github.brick.contract..",
                        "io.github.brick.dbserver..")
                .check(classes);
    }
}
```

- [ ] **Step 3: 跑测试确认通过（实现已是 POJO 无关，应直接 PASS）**

Run: `mvn -q -pl game-data test -Dtest=DependencyRuleTest`
Expected: PASS（game-data 当前不依赖业务域；若有违例则按规则修正 import）。

- [ ] **Step 4: 全量校验**

Run: `mvn -q -pl game-data test`
Expected: 所有 test（含各 IT）PASS。确保本地 Redis/Mongo 已起。

Run: `mvn -q verify`（根）
Expected: reactor 全绿，依赖规则与 Netty 钉版校验通过。

- [ ] **Step 5: 提交**

```bash
git add pom.xml game-data/pom.xml game-data/src/test/java/io/github/brick/data/DependencyRuleTest.java
git commit -m "test(data): ArchUnit 强制 game-data 不依赖 web/contract/dbserver 业务域（§7 用例 9）"
```

---

## Self-Review

**1. Spec 覆盖：**
- 并发修订 §1 锁模型（按实体分锁、互斥 RLock、全局类型优先级）→ Task 2（DataKeys lockKey）+ Task 9（sortReqs 优先级）✓
- 并发修订 §2 读写路径（持锁回填普通 SET、写路径锁内 miss 加载）→ Task 8 `get`/`put` ✓
- 并发修订 §3 禁嵌套 + 按需定池 → Task 10（池大小）+ Task 5/8 路径覆盖（文档说明不机械断言）✓
- 并发修订 §4 固定 leaseTime 无看门狗 + isHeld 门控 → Task 9（tryLock 固定租约）+ Task 8（门控）✓
- primitives §2 对外 API（LockScope.lockAll/LockCtx.get/put/close）→ Task 8/9 ✓
- primitives §3 内部实现（resolveLock、CommitLua 不解析 JSON、LockScope 排序）→ Task 7/8/9 ✓
- primitives §4 异常与重试（LockAcquireException/LockLostException、单实体可重试、多实体部分提交不重试）→ Task 3/8 ✓
- primitives §5 对象传递（返回值/局部变量，不用 ThreadLocal）→ LockCtx.get 返回值传递，文档/代码无 ThreadLocal ✓
- primitives §6 POJO 约定（泛型、覆盖写）→ Task 1/8 ✓
- primitives §6.1 String+JSON 范围 + 留路 → RedisStore StringCodec、不用 hash/sortedset ✓（留路非 API 不实现）
- primitives §7 11 用例 → §7 映射表逐条有覆盖 ✓
- primitives §8 不在范围（game-web 横切、game-dbserver 落盘编排）→ 本计划不涉及 ✓

**2. 占位符扫描：** 无 TBD/TODO；各 Step 均含实际代码。`CommitLua` 与 `MongoStore.bulkUpsert` 实现处各有一处「以编译器/API 实际为准」的提示——属可执行的实现提示，非占位符。

**3. 类型一致性：** `LockCtx`/`RedissonLockCtx` 两构造器在 Task 8 内已统一为「仅 `Map` 构造器」并相应改测试；`LockScope.lockAll` 返回 `LockCtx`，`RedissonLockScope` 构造器参数 `(client, redis, mongo, commitLua, codec, waitMillis, leaseSeconds, priority)` 与 Task 10 装配、Task 9 测试一致。`DataKeys.lockKey(entity,id)`、`entityOf/idOf/collectionOf/docIdOf` 全程同名。`DirtyLedger.DIRTY_SET` 被 `CommitLua` 引用，Task 6 先于 Task 7 创建 ✓。

---

## Execution Handoff

**Plan complete and saved to `.superpowers/plans/2026-08-13-game-data-primitives.md`.**

集成测试需本地预起 Redis（`redis://127.0.0.1:6379`）与 Mongo（`mongodb://localhost:27018`），按 `DEVELOPMENT.md` 预起；`redis-cli ping` 返回 PONG、`mongosh --eval "db.runCommand({ping:1})"` 返回 `{ ok: 1 }` 方可跑 `mvn -pl game-data test`。

两种执行方式：

**1. Subagent-Driven（推荐）** — 每个 task 派新 subagent，task 间 review，迭代快。

**2. Inline Execution** — 本会话内用 executing-plans 批量执行 + 检查点 review。

选哪种？
