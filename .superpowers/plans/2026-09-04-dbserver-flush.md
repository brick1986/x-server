# game-dbserver 落盘编排实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `game-dbserver` 落盘进程——原子排空 dirty 集合、分片流水线把 Redis JSON 搬进 Mongo、失败 key 精确回写、单实例分布式锁保证、优雅停机刷盘，并为此补齐 `game-data` 的三个原语。

**Architecture:** 落盘是纯字节搬运，不反序列化。每轮 `flushOnce()` 先抢 `lock:dbserver:flush`（拿不到即跳过，多出的实例天然成热备），再用一段 Lua 原子地「恢复上轮残留 + `RENAME {dirty}` → `{dirty}:inflight` + 返回快照」，使落盘期间业务侧的 `SADD` 进的是新一轮 dirty、与本轮快照物理隔离。快照按 500 key/片流水线处理：`mget` 一次往返取值（还 Redis 连接）→ `bulkUpsert` unordered 写 Mongo → 失败 key `SADD` 回 dirty → 整片 `SREM` 出 inflight。`inflight` 恒等于「尚未落盘的 key」，故中断/崩溃路径零操作——残留由下一轮 drain 自愈，恢复路径唯一。

**Tech Stack:** JDK 25、Spring Boot 4.1.0、Redisson 4.6.1（Sync API）、MongoDB Driver 5.9.0（Sync）、Micrometer/Actuator、JUnit 5、AssertJ。

**Spec:**
- `.superpowers/specs/2026-09-04-dbserver-flush-design.md`（主，本计划逐节实现它）
- `.superpowers/specs/2026-08-11-game-data-primitives-design.md` §8（`game-data` 侧改动的授权范围）
- `.superpowers/specs/2026-07-24-server-architecture-design.md` §4.3（被主 spec §2.1 修订）
- `.superpowers/specs/2026-08-04-data-concurrency-fixes-design.md` §3.2、§4.1（禁嵌套跨池、固定租约无看门狗）

---

## Global Constraints

直接照搬 spec 钉定的项目级约束，每个 task 的要求都隐含本节：

- **JDK 25**，编译目标 `--release 25`（根 POM `java.version=25`）。
- **统一同步命令式编程**，禁用 Reactive / Mono/Flux（架构 §3.1）。
- **禁嵌套跨池获取**：一个虚拟线程绝不同时持有两个连接池的连接。落盘侧体现为 `mget` 返回即还 Redis 连接、之后才触达 Mongo（并发修订 §3.2）。
- **固定租约、禁用看门狗**：所有加锁一律传固定 `leaseTime`，不使用 Redisson 默认无限续约；落盘锁租约取 `60s`（并发修订 §4.1，落盘 spec §5）。
- **落盘零转换**：Redis 里的 JSON 字符串原样 upsert 进 Mongo 的 `{_id, v}` 文档，**不反序列化成 Java 对象**（架构 §4.1、§4.3）。
- **`game-data` 不认识业务实体**：新增方法全部 POJO 无关，只收发 `String` key 与 JSON 字符串；ArchUnit 规则 `io.github.brick.data..` 不得依赖 `web..`/`contract..`/`dbserver..`（`DependencyRuleTest`，改完必须仍绿）。
- **`game-dbserver` 唯一业务依赖是 `game-data`**：不得引入 `game-contract` / `game-web`（模块 §4 规则 2）。
- **key 命名钉死** `{entity}:{id}:{field}`，Mongo collection = `{entity}:{field}`、`_id = {id}`，均由 `DataKeys` 派生（primitives §3.1）。
- **dirty 集合名自带 hash tag**：`{dirty}` / `{dirty}:inflight`，二者 Cluster 下必然同 slot（落盘 spec §2.5）。
- **连接池显式设限**：Redis 100~200、Mongo 50~100（架构 §3.1）。`game-dbserver` 的 `application.yaml` 已配 `mongo-pool-size: 100`，不改。
- **集成测试连本地预起的 Redis/Mongo**，不引 Testcontainers、不引嵌入式实现（primitives §7）。`*IT.java` 由 failsafe 在 `verify` 阶段跑。

---

## File Structure

**`game-data`（补三个原语，均在 primitives §8「落盘进程消费」授权范围内）**

| 文件 | 责任 |
| --- | --- |
| `overlay/DirtyLedger.java` | 改：key 名加 hash tag；新增 `drainToInflight` / `ackInflight` / `markAll` / `inflightMembers` / `backlogSize` |
| `store/RedisStore.java` | 改：新增 `mget(Collection<String>)` 一次往返批量取值 |
| `store/MongoStore.java` | 改：`bulkUpsert` 返回失败 key 集合、改 `ordered(false)` |
| `test/.../LocalRedisMongo.java` | 改：配置文件改名，解除跨模块复用时的 classpath 遮蔽 |

**`game-dbserver`（新建落盘编排）**

| 文件 | 责任 |
| --- | --- |
| `flush/FlushOrchestrator.java` | 一轮落盘的完整编排：抢锁 → drain → 分片流水线 → 失败回写。无状态 |
| `flush/FlushScheduler.java` | `@Scheduled` 触发 + `accepting` 开关 + 进程内 `ReentrantLock`（§6.1 两层互斥的内层） |
| `flush/GracefulShutdown.java` | `SmartLifecycle`：停接新轮 → 等当前轮 → 循环刷到空 → 硬超时 |
| `flush/FlushMetrics.java` | Micrometer 指标载体（§7.1 六个指标） |
| `config/DbServerProperties.java` | `game.dbserver.*` 配置 + `@Validated` 范围强制 |
| `config/DbServerConfiguration.java` | 显式 `@Bean` 装配（沿用 `DataAutoConfiguration` 的显式风格，不用 `@Component` 扫描）+ `@EnableScheduling` |

**分片流水线为什么不是 `parallel`**：每片内部 `mget`（Redis）与 `bulkUpsert`（Mongo）必须顺序执行才满足禁嵌套跨池；片与片之间串行是为了让「失锁即中断」有确定的中断点。落盘吞吐靠单次往返批量化（500 key 一次 `MGET`、一次 `bulkWrite`）而非并发，符合架构 §3.1 的同步命令式纪律。

---

## Task 1: 打通跨模块复用 IT 基类

`game-dbserver` 的 IT 需要 `LocalRedisMongo`（Redis/Mongo 客户端 + 每用例 flush + `newClient()` 第二连接），但它在 `game-data` 的**测试**源码里，跨模块用不到。发布 test-jar 即可，但**直接发布会踩一个 classpath 遮蔽陷阱**：

`LocalRedisMongo` 用 `ClassPathResource("application.yaml")` 读连接参数，而 `game-dbserver` 自己有 `src/main/resources/application.yaml`。测试时本模块 `target/classes` 排在依赖 jar 之前，于是读到的是**应用配置**——里面是 `${MONGO_URI:mongodb://localhost:27018}` 这类占位符，而 `LocalRedisMongo` 是纯 JUnit、用 `YamlPropertySourceLoader` 裸读 yaml，**不做占位符解析**，`MongoClients.create("${MONGO_URI:...}")` 会直接失败。

故把 IT 配置文件改名为 `it-config.yaml`（它本来就不是 Spring 应用配置——基类自己都注明「纯 JUnit、不启动 Spring 上下文」），永久消除遮蔽可能。

**Files:**
- Rename: `game-data/src/test/resources/application.yaml` → `it-config.yaml`（未跟踪文件，用 `mv`）
- Rename: `game-data/src/test/resources/application.yaml.example` → `it-config.yaml.example`（已跟踪，用 `git mv`）
- Modify: `game-data/src/test/java/io/github/brick/data/LocalRedisMongo.java`（`CONFIG_FILE` 常量 + 错误提示文案 + 类注释）
- Modify: `game-data/pom.xml`（新增 `maven-jar-plugin` 的 `test-jar` 目标）
- Modify: `pom.xml`（`dependencyManagement` 新增 game-data test-jar 条目）
- Modify: `.gitignore`
- Modify: `DEVELOPMENT.md`（「跑测试」一节的 `cp` 命令与文件名引用）

**Interfaces:**
- Consumes: 无（本任务是后续所有 IT 的前置）
- Produces: `io.github.brick.data.LocalRedisMongo` 可被其他模块以 `test-jar` 依赖复用；受保护成员 `redis`（`RedissonClient`）、`mongo`（`MongoClient`）、`MONGO_DB`（`String`）、`db()`（`MongoDatabase`）、`newClient()`（`RedissonClient`，调用方负责 `shutdown()`）

- [ ] **Step 1: 改名两个配置文件**

```bash
cd game-data/src/test/resources
mv application.yaml it-config.yaml                      # 未跟踪（在 .gitignore 里）
git mv application.yaml.example it-config.yaml.example  # 已跟踪
```

若 `application.yaml` 不存在（新 clone 的仓库），只跑 `git mv` 那行即可。

- [ ] **Step 2: 改 `LocalRedisMongo` 的常量与提示文案**

`game-data/src/test/java/io/github/brick/data/LocalRedisMongo.java`——把常量与两处提示里的文件名换掉：

```java
	private static final String CONFIG_FILE = "it-config.yaml";
```

并把 `loadTestYaml()` 里的错误提示改成新文件名：

```java
			throw new IllegalStateException("""
					缺少集成测试配置 game-data/src/test/resources/%s。
					该文件含本机 Redis 密码，不入库（见 .gitignore）。请复制模板并改成你本机的值：
					    cp game-data/src/test/resources/%s.example game-data/src/test/resources/%s
					详见 DEVELOPMENT.md「跑测试」一节。"""
					.formatted(CONFIG_FILE, CONFIG_FILE, CONFIG_FILE));
```

在类注释里把「该文件含本机密码，在 `.gitignore` 中」那段的 `application.yaml` 字样改为 `it-config.yaml`，并补一句说明改名理由：

```java
 * <p>配置文件刻意**不叫** {@code application.yaml}：本基类经 test-jar 被 {@code game-dbserver} 复用，
 * 而那个模块自己有 {@code src/main/resources/application.yaml}。同名会被本模块 target/classes 抢先命中，
 * 读到的是带 {@code ${VAR:default}} 占位符的应用配置——本类裸读 yaml、不解析占位符，会直接连接失败。
```

- [ ] **Step 3: 跑一遍确认改名没破坏现有 IT**

Run: `./mvnw -pl game-data verify`
Expected: PASS（现有全部单测 + IT 仍绿）。若报「缺少集成测试配置」，按提示 `cp it-config.yaml.example it-config.yaml` 并填本机值。

- [ ] **Step 4: 让 `game-data` 产出 test-jar**

`game-data/pom.xml` 的 `<build><plugins>` 里，在已有的 failsafe 插件后追加：

```xml
			<!-- 产出 test-jar：LocalRedisMongo 等 IT 基础设施要被 game-dbserver 复用
			     （落盘 plan Task 1）。默认的 jar 目标不受影响，仍照常产出主制品。 -->
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-jar-plugin</artifactId>
				<executions>
					<execution>
						<goals>
							<goal>test-jar</goal>
						</goals>
					</execution>
				</executions>
			</plugin>
```

- [ ] **Step 5: 在根 POM 的 `dependencyManagement` 里钉 test-jar 版本**

`pom.xml` 中紧跟已有的 `game-data` 条目之后追加：

```xml
			<!-- game-data 的 test-jar：IT 基类（LocalRedisMongo）跨模块复用 -->
			<dependency>
				<groupId>io.github.brick</groupId>
				<artifactId>game-data</artifactId>
				<version>${project.version}</version>
				<type>test-jar</type>
			</dependency>
```

- [ ] **Step 6: 更新 `.gitignore`**

把最后一行的路径换成新文件名：

```
### 本机集成测试配置（含 Redis 密码、本机端口）
### 模板见同目录 it-config.yaml.example —— clone 后复制一份改成自己的
game-data/src/test/resources/it-config.yaml
```

- [ ] **Step 7: 更新 `DEVELOPMENT.md`**

「跑测试」一节里，把 `cp` 命令与两处文件名引用换掉：

```bash
cp game-data/src/test/resources/it-config.yaml.example \
   game-data/src/test/resources/it-config.yaml
```

并把该节「连接参数读 **`game-data/src/test/resources/application.yaml`**」一句改为 `it-config.yaml`。同时在 `it-config.yaml.example` 的注释里，把「仅测试目录，不会打进 jar，与应用的 application.yaml 无 classpath 冲突」改成：

```
# 刻意不叫 application.yaml：本文件随 test-jar 被 game-dbserver 的 IT 复用，
# 同名会被那个模块自己的 src/main/resources/application.yaml 抢先命中。
```

- [ ] **Step 8: 全量构建确认**

Run: `./mvnw -pl game-data verify` 后再 `./mvnw -q -DskipTests install -pl game-data -am`
Expected: PASS，且 `game-data/target/` 下同时出现 `game-data-0.0.1-SNAPSHOT.jar` 与 `game-data-0.0.1-SNAPSHOT-tests.jar`。

- [ ] **Step 9: Commit**

```bash
git add game-data/pom.xml pom.xml .gitignore DEVELOPMENT.md \
        game-data/src/test/resources/it-config.yaml.example \
        game-data/src/test/java/io/github/brick/data/LocalRedisMongo.java
git commit -m "test(data): IT 配置改名 it-config.yaml + 产出 test-jar 供跨模块复用

game-dbserver 的 IT 要复用 LocalRedisMongo，但它读的 application.yaml
会被 dbserver 自己 src/main/resources 下的同名文件抢先命中——那里面是
\${VAR:default} 占位符，而本基类裸读 yaml 不解析占位符，会直接连接失败。
改名永久消除遮蔽。"
```

---

## Task 2: `RedisStore.mget` — 一次往返批量取值

分片流水线要用一次 `MGET` 取回整片的值（500 次同步往返 → 1 次）。**Redisson 对不存在的 key 不放进返回 Map**，这个行为是 §3「nil key 跳过且不回 dirty」的实现基础，故先用测试把它钉住，而不是假设。

**Files:**
- Modify: `game-data/src/main/java/io/github/brick/data/store/RedisStore.java`
- Test: `game-data/src/test/java/io/github/brick/data/store/RedisStoreIT.java`（在现有文件追加用例）

**Interfaces:**
- Consumes: 现有 `RedisStore(RedissonClient)`、`set(String,String)`、`get(String)`
- Produces: `Map<String,String> RedisStore.mget(Collection<String> keys)` — 返回**只含实际存在的 key**；空输入返回空 Map。Task 6 的 `FlushOrchestrator` 靠 `chunk.size() - map.size()` 算 nil 数量

- [ ] **Step 1: 写失败的测试**

在 `RedisStoreIT.java` 追加（import 补 `java.util.List`、`java.util.Map`）：

```java
    @Test
    void mgetReturnsAllPresentValues() {
        RedisStore s = new RedisStore(redis);
        s.set("player:1:profile", "{\"a\":1}");
        s.set("player:2:bag", "[]");
        assertThat(s.mget(List.of("player:1:profile", "player:2:bag")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "player:1:profile", "{\"a\":1}",
                        "player:2:bag", "[]"));
    }

    @Test
    void mgetOmitsMissingKeysRatherThanMappingToNull() {
        // 该行为是落盘 spec §3「nil key 跳过且不回 dirty」的实现基础：
        // FlushOrchestrator 靠 chunk.size() - map.size() 算 nil 数量，
        // 若 Redisson 改为映射到 null，那个算法会静默失效。
        RedisStore s = new RedisStore(redis);
        s.set("player:1:profile", "{\"a\":1}");
        Map<String, String> got = s.mget(List.of("player:1:profile", "player:404:bag"));
        assertThat(got).containsOnlyKeys("player:1:profile");
    }

    @Test
    void mgetEmptyInputReturnsEmptyMapWithoutTouchingRedis() {
        assertThat(new RedisStore(redis).mget(List.of())).isEmpty();
    }

    @Test
    void mgetReturnsRawStringNotQuotedJson() {
        RedisStore s = new RedisStore(redis);
        s.set("player:1:profile", "{\"a\":1}");
        // StringCodec 对齐：若误用默认 JsonJacksonCodec，取回的会是带引号转义的字符串
        assertThat(s.mget(List.of("player:1:profile")).get("player:1:profile"))
                .isEqualTo("{\"a\":1}");
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-data verify -Dit.test=RedisStoreIT`
Expected: 编译失败，`cannot find symbol: method mget(List<String>)`

- [ ] **Step 3: 实现 `mget`**

`RedisStore.java`——补 import `org.redisson.api.RBuckets`、`java.util.Collection`、`java.util.Map`，并新增方法：

```java
    /**
     * 一次往返批量取值（{@code MGET}）。**不存在的 key 不出现在返回 Map 中**（不是映射到 null）
     * ——落盘进程据此识别「标记尚存但 key 已被删」的情形（落盘 spec §3）。
     *
     * <p>与 {@link #get}/{@link #set} 共用同一个 {@link StringCodec}：否则取回的是带引号的
     * JSON 字符串，与 {@code CommitLua} 写入的裸字符串不一致。
     */
    public Map<String, String> mget(Collection<String> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        return buckets().get(keys.toArray(String[]::new));
    }

    private RBuckets buckets() {
        return client.getBuckets(StringCodec.INSTANCE);
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -pl game-data verify -Dit.test=RedisStoreIT`
Expected: PASS（4 个新用例 + 原有用例全绿）

- [ ] **Step 5: Commit**

```bash
git add game-data/src/main/java/io/github/brick/data/store/RedisStore.java \
        game-data/src/test/java/io/github/brick/data/store/RedisStoreIT.java
git commit -m "feat(data): RedisStore.mget 一次往返批量取值

落盘分片流水线用它把 500 次同步往返压成 1 次 MGET。用例钉住
「不存在的 key 不进返回 Map」——FlushOrchestrator 靠 size 差算 nil 数量。"
```

---

## Task 3: `DirtyLedger` — hash tag 改名 + 排空协议

落盘 spec §2 的核心。一段 Lua 一次往返完成「恢复上轮残留 + 原子排空 + 返回快照」。

**Files:**
- Modify: `game-data/src/main/java/io/github/brick/data/overlay/DirtyLedger.java`
- Test: `game-data/src/test/java/io/github/brick/data/overlay/DirtyLedgerIT.java`（追加用例）

**Interfaces:**
- Consumes: 现有 `DirtyLedger(RedissonClient)`、`mark(String)`、`members()`
- Produces:
  - `DirtyLedger.DIRTY_SET` = `"{dirty}"`（常量值变更，`CommitLua` 引用同一常量自动跟随）
  - `DirtyLedger.INFLIGHT_SET` = `"{dirty}:inflight"`
  - `Set<String> drainToInflight()` — 恢复残留 + 排空 + 返回快照；dirty 空时返回空 Set
  - `void ackInflight(Collection<String> keys)` — 一片落盘完成，`SREM` 出 inflight
  - `void markAll(Collection<String> keys)` — 失败 key `SADD` 回 dirty
  - `Set<String> inflightMembers()` — 诊断/测试用
  - `int backlogSize()` — dirty 积压量，Task 10 的 gauge 与 Task 9 的超时日志用

- [ ] **Step 1: 写失败的测试**

在 `DirtyLedgerIT.java` 追加（import 补 `java.util.List`）：

```java
    @Test
    void keyNamesCarryClusterHashTag() {
        // Cluster 下 {dirty} 与 {dirty}:inflight 的 hash tag 同为 dirty，必然同 slot；
        // 二者在同一段 Lua 里操作，不同 slot 会报 CROSSSLOT（落盘 spec §2.5）。
        assertThat(DirtyLedger.DIRTY_SET).isEqualTo("{dirty}");
        assertThat(DirtyLedger.INFLIGHT_SET).isEqualTo("{dirty}:inflight");
    }

    @Test
    void drainMovesEverythingToInflightAndEmptiesDirty() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:profile");
        d.mark("guild:7:fund");
        assertThat(d.drainToInflight())
                .containsExactlyInAnyOrder("player:1:profile", "guild:7:fund");
        assertThat(d.members()).isEmpty();
        assertThat(d.inflightMembers())
                .containsExactlyInAnyOrder("player:1:profile", "guild:7:fund");
    }

    @Test
    void drainOnEmptyDirtyReturnsEmptyInsteadOfFailing() {
        // RENAME 对不存在的 key 会报错，故 Lua 必须先 EXISTS 判空
        assertThat(new DirtyLedger(redis).drainToInflight()).isEmpty();
    }

    @Test
    void drainRecoversLeftoverInflightFromAnInterruptedRound() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:profile");
        d.drainToInflight();                 // 第一轮排空后「崩溃」，不 ack
        d.mark("player:2:bag");              // 期间业务侧又标脏一个

        // 下一轮 drain 必须把残留合回来，两者一起返回
        assertThat(d.drainToInflight())
                .containsExactlyInAnyOrder("player:1:profile", "player:2:bag");
        assertThat(d.members()).isEmpty();
    }

    @Test
    void concurrentMarkDuringFlushIsNotSwallowed() {
        // 落盘 spec §2.1 的回归测试：这正是被修订掉的「GET 后 SREM」会丢掉的东西
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:bag");
        Set<String> snapshot = d.drainToInflight();

        d.mark("player:1:bag");              // 落盘期间业务侧写了 v2，重新标脏
        d.ackInflight(snapshot);             // 本轮落完 v1，只 ack 自己的快照

        // v2 的标记必须还在，下一轮会落它
        assertThat(d.members()).containsExactly("player:1:bag");
        assertThat(d.inflightMembers()).isEmpty();
    }

    @Test
    void ackInflightRemovesOnlyThatChunk() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:profile");
        d.mark("player:2:bag");
        d.mark("guild:7:fund");
        d.drainToInflight();
        d.ackInflight(List.of("player:1:profile", "player:2:bag"));
        assertThat(d.inflightMembers()).containsExactly("guild:7:fund");
    }

    @Test
    void ackInflightAndMarkAllTolerateEmptyInput() {
        DirtyLedger d = new DirtyLedger(redis);
        d.ackInflight(List.of());
        d.markAll(List.of());
        assertThat(d.members()).isEmpty();
    }

    @Test
    void markAllPutsFailedKeysBackForRetry() {
        DirtyLedger d = new DirtyLedger(redis);
        d.markAll(List.of("player:1:profile", "guild:7:fund"));
        assertThat(d.members())
                .containsExactlyInAnyOrder("player:1:profile", "guild:7:fund");
    }

    @Test
    void backlogSizeCountsDirtyOnly() {
        DirtyLedger d = new DirtyLedger(redis);
        d.mark("player:1:profile");
        d.mark("player:2:bag");
        assertThat(d.backlogSize()).isEqualTo(2);
        d.drainToInflight();
        assertThat(d.backlogSize()).isZero();   // 已排空，积压在 inflight 不算积压
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-data verify -Dit.test=DirtyLedgerIT`
Expected: 编译失败，`cannot find symbol: INFLIGHT_SET` / `drainToInflight()` 等

- [ ] **Step 3: 实现**

`DirtyLedger.java` 全文替换为：

```java
package io.github.brick.data.overlay;

import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * dirty 集合账本，落盘进程消费（落盘 spec §2）。集合名 {@link #DIRTY_SET} / {@link #INFLIGHT_SET}
 * 是唯一来源，{@link CommitLua} 引用前者。{@link RSet} 用 {@link StringCodec}：与 {@link CommitLua}
 * Lua 写入的裸 key 字符串对齐。
 *
 * <p><b>两个集合名自带 Cluster hash tag。</b>{@code {dirty}} 与 {@code {dirty}:inflight} 的 tag
 * 同为 {@code dirty}，必然落同一 slot——二者在 {@link #DRAIN_SCRIPT} 同一段 Lua 里操作，
 * 不同 slot 会报 {@code CROSSSLOT}。单机模式下花括号只是普通字符，行为无差别。
 *
 * <p><b>消费协议是「原子排空到 in-flight」，不是「GET 后 SREM」</b>（落盘 spec §2.1 修订架构 §4.3）：
 * 后者会在「落盘进程读完 v1、业务侧写入 v2 并重新标脏」之后把 v2 的标记一并 {@code SREM} 掉，
 * 使已提交的值永不下沉。排空后 {@code dirty} 立刻是空集，落盘期间的新标记进新一轮，物理隔离。
 *
 * <p><b>{@link #INFLIGHT_SET} 的含义是「已排空但尚未落盘的 key」</b>：每片落盘完成即
 * {@link #ackInflight} 移除。故中断与崩溃路径无需任何专门处理——残留留在 inflight，
 * 由下一轮 {@link #drainToInflight} 的恢复步骤合回。**刻意不提供「清空 inflight」的方法**：
 * 若 inflight 非空却被 DEL，那正是上面那种丢标记。
 */
public final class DirtyLedger {

    public static final String DIRTY_SET = "{dirty}";

    public static final String INFLIGHT_SET = "{dirty}:inflight";

    /**
     * 一次往返完成三件事：合回上轮残留、原子排空、返回快照。
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

    /**
     * 原子排空：先把上轮残留的 in-flight 合回 dirty，再把整个 dirty 改名为 in-flight 并返回快照。
     * 返回后 dirty 为空集，落盘期间业务侧的 {@code SADD} 进的是新一轮，不会被本轮吞掉。
     *
     * @return 本轮要落盘的 key 快照；dirty 与 in-flight 皆空时返回空 Set
     */
    public Set<String> drainToInflight() {
        List<Object> members = client.getScript(StringCodec.INSTANCE).eval(
                RScript.Mode.READ_WRITE,
                DRAIN_SCRIPT,
                RScript.ReturnType.LIST,
                List.of(DIRTY_SET, INFLIGHT_SET));
        Set<String> snapshot = new LinkedHashSet<>();
        for (Object m : members) {
            snapshot.add(m.toString());
        }
        return snapshot;
    }

    /**
     * 一片落盘完成，把它移出 in-flight。**必须在 {@link #markAll} 回写失败 key 之后调用**
     * （落盘 spec §2.4）：反序则「ack 后、mark 前」崩溃会让失败 key 既不在 in-flight
     * 也不在 dirty，静默丢标记。
     */
    public void ackInflight(Collection<String> keys) {
        if (!keys.isEmpty()) {
            inflight().removeAll(keys);
        }
    }

    /** 落盘失败的 key 回写 dirty 等下轮重试。upsert 幂等，重做无害。 */
    public void markAll(Collection<String> keys) {
        if (!keys.isEmpty()) {
            set().addAll(keys);
        }
    }

    /** 诊断与测试用：当前 in-flight（已排空但尚未落盘）的 key。 */
    public Set<String> inflightMembers() {
        return inflight().readAll();
    }

    /** dirty 积压量（不含 in-flight）。落盘进程以此作 gauge 指标——持续增长说明落盘跟不上写入。 */
    public int backlogSize() {
        return set().size();
    }

    private RSet<String> set() {
        return client.getSet(DIRTY_SET, StringCodec.INSTANCE);
    }

    private RSet<String> inflight() {
        return client.getSet(INFLIGHT_SET, StringCodec.INSTANCE);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -pl game-data verify -Dit.test=DirtyLedgerIT`
Expected: PASS

- [ ] **Step 5: 确认改名没破坏提交路径**

`CommitLua` 引用 `DirtyLedger.DIRTY_SET` 常量，改名应自动跟随。跑全量验证：

Run: `./mvnw -pl game-data verify`
Expected: PASS，特别是 `CommitLuaIT`（验证 `put` 后 dirty 集合含该 key）与 `LockCtxIT` 仍绿——它们走常量而非字面量，故不受改名影响。

- [ ] **Step 6: Commit**

```bash
git add game-data/src/main/java/io/github/brick/data/overlay/DirtyLedger.java \
        game-data/src/test/java/io/github/brick/data/overlay/DirtyLedgerIT.java
git commit -m "feat(data): DirtyLedger 原子排空协议 + key 名加 hash tag

drainToInflight 一次往返完成「合回上轮残留 + RENAME 排空 + 返回快照」，
替掉架构 §4.3 的「GET 后 SREM」——后者会把落盘期间业务侧重新标脏的
新版本标记一并删掉，使已提交的值永不下沉（落盘 spec §2.1 有时序论证）。

inflight 恒等于「尚未落盘的 key」，故中断/崩溃零操作、由下轮 drain 自愈；
刻意不提供清空 inflight 的方法。key 名 {dirty} / {dirty}:inflight 同 slot。"
```

---

## Task 4: `MongoStore.bulkUpsert` — unordered + 返回失败 key

落盘 spec §4。现在是默认 ordered + 返回 `void`：首条出错即停、后续一条不执行、异常也不指明哪些成功。分片流水线下这会变成「第 5 片挂了 → 前 4 片已落、第 5 片状态不明、后续片没跑」。

**Files:**
- Modify: `game-data/src/main/java/io/github/brick/data/store/MongoStore.java:45-60`
- Test: `game-data/src/test/java/io/github/brick/data/store/MongoStoreIT.java`（追加用例 + 改一处现有断言）

**Interfaces:**
- Consumes: 现有 `MongoStore(MongoClient,String)`、`load(String)`、`upsert(String,String)`、`DataKeys.collectionOf/docIdOf`
- Produces: `Set<String> MongoStore.bulkUpsert(Map<String,String> entries)` — 返回**写失败的 key**（空集表示全部成功）。连接级 `MongoException` **不捕获、原样抛出**，由 Task 6 的调用方按「整片失败」处理

> `bulkUpsert` 目前**没有生产调用方**（只有 `MongoStoreIT` 调它并忽略返回值），故改签名不波及生产代码。

- [ ] **Step 1: 写失败的测试**

在 `MongoStoreIT.java` 追加（import 补 `com.mongodb.client.model.IndexOptions`、`java.util.LinkedHashMap`，**不加 `java.util.Set`**——下述断言全走 AssertJ 类型推断，加它会成未用 import）：

```java
    @Test
    void bulkUpsertReturnsEmptySetWhenAllSucceed() {
        assertThat(store().bulkUpsert(Map.of("player:1:profile", "{\"p\":1}"))).isEmpty();
    }

    @Test
    void bulkUpsertEmptyInputReturnsEmptySet() {
        assertThat(store().bulkUpsert(Map.of())).isEmpty();
    }

    @Test
    void bulkUpsertReturnsOnlyFailedKeyAndStillWritesTheRest() {
        // 真实世界的失败触发是文档超 16MB（架构 §4.1 自己留了这个伏笔），但那要造 17MB
        // 字符串、慢且吃内存。此处用唯一索引冲突制造「同批中恰好一条写失败」——确定性更强、
        // 更快，且走的是同一条 MongoBulkWriteException + getWriteErrors() 反查路径。
        db().getCollection("player:profile")
                .createIndex(new Document("v", 1), new IndexOptions().unique(true));
        MongoStore s = store();
        s.upsert("player:1:profile", "{\"dup\":1}");

        Map<String, String> batch = new LinkedHashMap<>();
        batch.put("player:2:profile", "{\"dup\":1}");   // v 与 player:1 重复 → 唯一索引冲突
        batch.put("player:3:profile", "{\"ok\":3}");

        assertThat(s.bulkUpsert(batch)).containsExactly("player:2:profile");
        // unordered 的关键收益：失败那条没有拖累同批其余（ordered 下 player:3 根本不会执行）
        assertThat(s.load("player:3:profile")).isEqualTo("{\"ok\":3}");
        assertThat(s.load("player:2:profile")).isNull();
    }

    @Test
    void bulkUpsertMapsErrorIndexBackToKeyWithinItsOwnCollection() {
        // BulkWriteError.getIndex() 是「该次 bulkWrite 调用内 models 列表的下标」，
        // 不是整批 entries 的下标。多 collection 分组时若拿整批下标反查会错位到别的 key，
        // 把一个落盘成功的 key 误判为失败、并把真正失败的那个漏掉。
        db().getCollection("guild:fund")
                .createIndex(new Document("v", 1), new IndexOptions().unique(true));
        MongoStore s = store();
        s.upsert("guild:1:fund", "{\"dup\":1}");

        Map<String, String> batch = new LinkedHashMap<>();
        batch.put("player:1:profile", "{\"p\":1}");     // player:profile 组
        batch.put("player:2:profile", "{\"p\":2}");     // player:profile 组
        batch.put("guild:2:fund", "{\"dup\":1}");       // guild:fund 组内下标 0，冲突

        assertThat(s.bulkUpsert(batch)).containsExactly("guild:2:fund");
        assertThat(s.load("player:1:profile")).isEqualTo("{\"p\":1}");
        assertThat(s.load("player:2:profile")).isEqualTo("{\"p\":2}");
    }
```

同时把现有的 `bulkUpsertWritesAllAndLoadsBack` 里那句 `s.bulkUpsert(Map.of(...))` 改成带返回值断言：

```java
        assertThat(s.bulkUpsert(Map.of(
                "player:1:profile", "{\"p\":1}",
                "player:2:bag", "{\"b\":2}",
                "guild:7:fund", "{\"f\":7}"))).isEmpty();
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-data verify -Dit.test=MongoStoreIT`
Expected: 编译失败，`incompatible types: void cannot be converted to Set<String>`

- [ ] **Step 3: 实现**

`MongoStore.java`——import 补 `com.mongodb.MongoBulkWriteException`、`com.mongodb.bulk.BulkWriteError`、`com.mongodb.client.model.BulkWriteOptions`、`java.util.LinkedHashSet`、`java.util.Set`；在 `UPSERT` 常量后新增：

```java
    /**
     * unordered：落盘的每个 key 相互独立，一条失败不该让同批其余一条都不执行；
     * 且 unordered 会尝试全部 model，错误列表完整、每条带 index，可精确反查是哪个 key
     * （落盘 spec §4）。
     */
    private static final BulkWriteOptions UNORDERED = new BulkWriteOptions().ordered(false);
```

把 `bulkUpsert` 整个方法替换为：

```java
    /**
     * 批量整值 upsert。按 collection 分组各跑一次 {@code bulkWrite}（{@code WriteModel} 必须同集合）。
     *
     * @return 写失败的 key；空集表示全部成功。失败者由调用方回写 dirty 等下轮重试（落盘 spec §4）
     * @throws com.mongodb.MongoException 连接级故障（Mongo 不可用）**原样抛出**——那是整片失败，
     *         不是个别 key 的问题，处置策略归调用方
     */
    public Set<String> bulkUpsert(Map<String, String> entries) {
        if (entries.isEmpty()) {
            return Set.of();
        }
        // modelsByCol 与 keysByCol 在同一个循环里同序构建：BulkWriteError.getIndex() 是
        // 「该次 bulkWrite 调用内 models 列表的下标」，反查 key 只能靠这份同序列表——
        // 用整批 entries 的下标会错位到别的 collection 的 key。
        Map<String, List<WriteModel<Document>>> modelsByCol = new LinkedHashMap<>();
        Map<String, List<String>> keysByCol = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String key = e.getKey();
            String col = DataKeys.collectionOf(key);
            modelsByCol.computeIfAbsent(col, k -> new ArrayList<>())
                    .add(new ReplaceOneModel<>(
                            byId(key),
                            new Document("_id", DataKeys.docIdOf(key)).append("v", e.getValue()),
                            UPSERT));
            keysByCol.computeIfAbsent(col, k -> new ArrayList<>()).add(key);
        }

        Set<String> failed = new LinkedHashSet<>();
        modelsByCol.forEach((col, models) -> {
            try {
                db().getCollection(col, Document.class).bulkWrite(models, UNORDERED);
            } catch (MongoBulkWriteException e) {
                List<String> keys = keysByCol.get(col);
                for (BulkWriteError err : e.getWriteErrors()) {
                    failed.add(keys.get(err.getIndex()));
                }
            }
        });
        return failed;
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `./mvnw -pl game-data verify -Dit.test=MongoStoreIT`
Expected: PASS

- [ ] **Step 5: 全量验证 `game-data`**

Run: `./mvnw -pl game-data verify`
Expected: PASS，含 `DependencyRuleTest`（ArchUnit 规则仍绿——新增代码只用 `String`/`Map`/驱动类型，不触业务包）

- [ ] **Step 6: Commit**

```bash
git add game-data/src/main/java/io/github/brick/data/store/MongoStore.java \
        game-data/src/test/java/io/github/brick/data/store/MongoStoreIT.java
git commit -m "feat(data): bulkUpsert 改 unordered 并返回失败 key 集合

ordered 语义下首条出错即停、后续一条不执行、异常也不指明哪些成功；
分片流水线撞上这个会变成「前几片已落、当前片状态不明、后续片没跑」。

unordered 后错误列表完整且带 index，可精确反查失败 key 只回写它们。
index 是「该次 bulkWrite 调用内的下标」，故按 collection 分组时必须
同序留一份 key 列表——有专门用例钉住跨 collection 不错位。"
```

---

## Task 5: `game-dbserver` 骨架 —— POM 依赖与配置项

**Files:**
- Modify: `game-dbserver/pom.xml`
- Create: `game-dbserver/src/main/java/io/github/brick/dbserver/config/DbServerProperties.java`
- Modify: `game-dbserver/src/main/resources/application.yaml`
- Modify: `game-dbserver/src/test/java/io/github/brick/dbserver/DbServerApplicationTest.java`
- Test: `game-dbserver/src/test/java/io/github/brick/dbserver/config/DbServerPropertiesTest.java`
- Modify: `DEVELOPMENT.md`

**Interfaces:**
- Consumes: Task 1 产出的 `game-data` test-jar（`io.github.brick.data.LocalRedisMongo`）
- Produces: `DbServerProperties`，getter `getFlushIntervalMillis()`（`long`）、`getChunkSize()`（`int`）、`getLockLeaseSeconds()`（`long`）、`getShutdownTimeoutSeconds()`（`long`）。Task 6/8/9 的 `@Bean` 方法从它取值

- [ ] **Step 1: 加 POM 依赖与插件**

`game-dbserver/pom.xml` 的 `<dependencies>` 中，`spring-boot-starter` 之后追加：

```xml
		<!-- Actuator：落盘指标（dirty 积压量、单轮耗时、失败 key 数）导出，架构 §6 -->
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-actuator</artifactId>
		</dependency>
```

在 `spring-boot-starter-test` 之后追加：

```xml
		<!-- game-data 的 test-jar：复用 LocalRedisMongo（连本地预起 Redis/Mongo 的 IT 基类）。
		     跑本模块 IT 必须带 -am，否则 reactor 里没有这个制品：
		         ./mvnw -pl game-dbserver -am verify -->
		<dependency>
			<groupId>io.github.brick</groupId>
			<artifactId>game-data</artifactId>
			<type>test-jar</type>
			<scope>test</scope>
		</dependency>
```

`<build><plugins>` 中，`spring-boot-maven-plugin` 之后追加 failsafe 激活（配置继承自根 `pluginManagement`）：

```xml
			<!-- 激活 failsafe：让 *IT.java 在 integration-test 阶段执行 -->
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-failsafe-plugin</artifactId>
			</plugin>
```

> **不要**给 `game-dbserver` 建 `src/test/resources/application.yaml`——那会重新引入 Task 1 消除的遮蔽问题。IT 的连接参数来自 test-jar 里的 `it-config.yaml`。

- [ ] **Step 2: 写失败的测试**

Create `game-dbserver/src/test/java/io/github/brick/dbserver/config/DbServerPropertiesTest.java`：

```java
package io.github.brick.dbserver.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认值守卫。范围越界（@Min/@Max）的启动失败行为由 Spring 绑定校验保证，
 * 此处只钉默认值——它们是「零配置本地启动」的兜底，被改动应当是显式决定。
 */
class DbServerPropertiesTest {

    @Test
    void defaultsMatchSpec() {
        DbServerProperties p = new DbServerProperties();
        assertThat(p.getFlushIntervalMillis()).isEqualTo(2000L);   // 架构 §4.3 的 1~3s
        assertThat(p.getChunkSize()).isEqualTo(500);               // 落盘 spec §3
        assertThat(p.getLockLeaseSeconds()).isEqualTo(60L);        // 落盘 spec §5
        assertThat(p.getShutdownTimeoutSeconds()).isEqualTo(20L);  // 落盘 spec §6
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `./mvnw -pl game-dbserver -am test -Dtest=DbServerPropertiesTest`
Expected: 编译失败，`package io.github.brick.dbserver.config does not exist`

- [ ] **Step 4: 实现 `DbServerProperties`**

Create `game-dbserver/src/main/java/io/github/brick/dbserver/config/DbServerProperties.java`：

```java
package io.github.brick.dbserver.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 落盘进程运行时配置（落盘 spec §7）。
 *
 * <p>刻意**不放进 {@code game-data} 的 {@code DataProperties}**：落盘节奏是本进程的策略，
 * 而 {@code game-data} 是被 {@code game-web} 与本进程共用的库模块——把落盘参数塞进去，
 * {@code game-web} 会凭空多出一堆对它毫无意义的配置项。
 *
 * <p>与 {@code DataProperties} 同规格：{@link Min}/{@link Max} 在启动期强制范围。
 * 「合理区间」若只写在注释里，一行越界的 yml 就能让它失守——越界值应当使上下文启动失败，
 * 而不是留到线上以一个荒谬的落盘周期运行。
 */
@ConfigurationProperties(prefix = "game.dbserver")
@Validated
public class DbServerProperties {

    /**
     * 落盘轮次间隔（架构 §4.3 钉定 1~3s）。上限放到 60s 容许排障时临时调慢；
     * 下限 500ms——再快只是空转扫 dirty，往返开销大于收益。
     */
    @Min(500) @Max(60_000)
    private long flushIntervalMillis = 2000L;

    /**
     * 单片 key 数。内存峰值与单次往返量只与它有关、与积压总量无关（落盘 spec §3）。
     * 上限 5000：再大则单次 MGET 回包与一批 Document 的堆占用开始显著。
     */
    @Min(1) @Max(5_000)
    private int chunkSize = 500;

    /**
     * 落盘锁租约（秒，落盘 spec §5）。固定租约、无看门狗（并发修订 §4.1）。
     * 下限 10s：短于此，稍大的一片就可能在落盘中途失锁而白跑一轮。
     */
    @Min(10) @Max(600)
    private long lockLeaseSeconds = 60L;

    /**
     * 停机刷盘硬超时（秒，落盘 spec §6）。默认 20s，留在 Spring 默认 30s 关闭窗口内。
     * 超时不代表丢数据——残留仍在 dirty，下次启动接着落。
     */
    @Min(1) @Max(120)
    private long shutdownTimeoutSeconds = 20L;

    public long getFlushIntervalMillis() { return flushIntervalMillis; }
    public void setFlushIntervalMillis(long v) { this.flushIntervalMillis = v; }
    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int v) { this.chunkSize = v; }
    public long getLockLeaseSeconds() { return lockLeaseSeconds; }
    public void setLockLeaseSeconds(long v) { this.lockLeaseSeconds = v; }
    public long getShutdownTimeoutSeconds() { return shutdownTimeoutSeconds; }
    public void setShutdownTimeoutSeconds(long v) { this.shutdownTimeoutSeconds = v; }
}
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -pl game-dbserver -am test -Dtest=DbServerPropertiesTest`
Expected: PASS

- [ ] **Step 6: 补 `application.yaml` 配置段**

`game-dbserver/src/main/resources/application.yaml` 中，在现有 `game.data` 段之后追加 `dbserver` 子键（**与 `data` 平级，两个空格缩进**）：

```yaml
  # 落盘编排配置（可配项声明见 io.github.brick.dbserver.config.DbServerProperties）。
  # 范围由 @Min/@Max 在启动期强制，越界值使启动失败。
  dbserver:
    flush-interval-millis: ${FLUSH_INTERVAL_MILLIS:2000}     # 架构 §4.3 的 1~3s
    chunk-size: ${FLUSH_CHUNK_SIZE:500}                      # 单片 key 数
    lock-lease-seconds: ${FLUSH_LOCK_LEASE:60}               # 落盘锁租约，固定、无看门狗
    shutdown-timeout-seconds: ${FLUSH_SHUTDOWN_TIMEOUT:20}   # 停机刷盘硬超时
```

同文件末尾追加 Actuator 暴露（Task 10 用，现在一并配好）：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics
```

- [ ] **Step 7: 让骨架上下文测试不被定时轮次打扰**

Task 8 加上 `@Scheduled` 后，首轮会在上下文就绪后立刻触发并试着连 Redis。先把间隔调到远大于测试时长：

`DbServerApplicationTest` 的 `@TestPropertySource` 改为：

```java
@SpringBootTest
@TestPropertySource(properties = {
		"spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4",
		// Task 8 起本进程有 @Scheduled 落盘轮次；骨架上下文测试不连 Redis，
		// 把间隔调到远大于测试时长，避免无意义的连接失败噪声。
		"game.dbserver.flush-interval-millis=60000"
})
class DbServerApplicationTest {
```

- [ ] **Step 8: 补 `DEVELOPMENT.md` 的配置表**

在「四、应用配置（`game.data.*`）」一节的环境变量表之后追加一节：

```markdown
## 四之二、落盘配置（`game.dbserver.*`，仅 game-dbserver）

由 `game-dbserver` 自己的 `DbServerProperties` 声明——落盘节奏是该进程的策略，不放进被两个
进程共用的 `game-data`。范围由 `@Min`/`@Max` 在启动期强制，越界值使启动失败。

| 环境变量 | 兜底值 | 范围 | 说明 |
|---|---|---|---|
| `FLUSH_INTERVAL_MILLIS` | 2000 | 500~60000 | 落盘轮次间隔（架构 §4.3 的 1~3s） |
| `FLUSH_CHUNK_SIZE` | 500 | 1~5000 | 单片 key 数；内存峰值只与它有关，与积压总量无关 |
| `FLUSH_LOCK_LEASE` | 60 | 10~600 | 落盘锁租约（秒），固定、无看门狗 |
| `FLUSH_SHUTDOWN_TIMEOUT` | 20 | 1~120 | 停机刷盘硬超时（秒）；超时不丢数据，残留下次启动接着落 |
```

- [ ] **Step 9: 构建确认**

Run: `./mvnw -pl game-dbserver -am verify`
Expected: PASS（`DbServerPropertiesTest` + `DbServerApplicationTest` 绿）

- [ ] **Step 10: Commit**

```bash
git add game-dbserver/pom.xml \
        game-dbserver/src/main/java/io/github/brick/dbserver/config/ \
        game-dbserver/src/main/resources/application.yaml \
        game-dbserver/src/test/java/io/github/brick/dbserver/ \
        DEVELOPMENT.md
git commit -m "feat(dbserver): 落盘配置项 + Actuator 与 IT 基类依赖

game.dbserver.* 放本模块而非 game-data——落盘节奏是本进程策略，
塞进共用库会让 game-web 凭空多出一堆对它无意义的配置项。
范围由 @Min/@Max 在启动期强制，不只写在注释里。"
```

---

## Task 6: `FlushOrchestrator` — 分片流水线

落盘 spec §2.3/§2.4/§3/§4。本任务只做「搬字节」，**不含分布式锁**（Task 7 加）——评审边界：这一步验的是「字节搬得对不对」，下一步验的是「多实例下安不安全」。

**Files:**
- Create: `game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushOrchestrator.java`
- Create: `game-dbserver/src/main/java/io/github/brick/dbserver/config/DbServerConfiguration.java`
- Test: `game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushChunkingTest.java`
- Test: `game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushOrchestratorIT.java`

**Interfaces:**
- Consumes: `DirtyLedger.drainToInflight()` / `ackInflight(Collection)` / `markAll(Collection)` / `inflightMembers()`（Task 3）、`RedisStore.mget(Collection)`（Task 2）、`MongoStore.bulkUpsert(Map)` → `Set<String>`（Task 4）、`DbServerProperties.getChunkSize()`（Task 5）
- Produces:
  - `FlushOrchestrator(DirtyLedger, RedisStore, MongoStore, int chunkSize)` — Task 7 会给构造器加 `RedissonClient` 与 `lockLeaseSeconds` 两个参数
  - `int flushOnce()` — 返回本轮排空的 key 数；`0` 表示 drain 没找到东西（真干净）
  - `static final int INTERRUPTED = -1`（Task 7 才会返回它，此处先定义好契约常量）
  - `static List<List<String>> chunks(Collection<String>, int)` — 包级可见，供单测

- [ ] **Step 1: 写失败的单元测试（分片切分，不需要外部服务）**

Create `game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushChunkingTest.java`：

```java
package io.github.brick.dbserver.flush;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlushChunkingTest {

    @Test
    void emptyInputYieldsNoChunks() {
        assertThat(FlushOrchestrator.chunks(Set.of(), 500)).isEmpty();
    }

    @Test
    void exactMultipleSplitsEvenly() {
        assertThat(FlushOrchestrator.chunks(List.of("a", "b", "c", "d"), 2))
                .containsExactly(List.of("a", "b"), List.of("c", "d"));
    }

    @Test
    void remainderGoesIntoAShorterLastChunk() {
        assertThat(FlushOrchestrator.chunks(List.of("a", "b", "c"), 2))
                .containsExactly(List.of("a", "b"), List.of("c"));
    }

    @Test
    void chunkLargerThanInputYieldsSingleChunk() {
        assertThat(FlushOrchestrator.chunks(List.of("a"), 500))
                .containsExactly(List.of("a"));
    }

    @Test
    void everyKeyAppearsExactlyOnceAcrossChunks() {
        List<String> keys = java.util.stream.IntStream.range(0, 1200)
                .mapToObj(i -> "player:" + i + ":profile").toList();
        assertThat(FlushOrchestrator.chunks(keys, 500))
                .hasSize(3)
                .flatExtracting(c -> c)
                .containsExactlyElementsOf(keys);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-dbserver -am test -Dtest=FlushChunkingTest`
Expected: 编译失败，`package io.github.brick.dbserver.flush does not exist`

- [ ] **Step 3: 写失败的集成测试**

Create `game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushOrchestratorIT.java`：

```java
package io.github.brick.dbserver.flush;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FlushOrchestratorIT extends LocalRedisMongo {

    private DirtyLedger dirty() {
        return new DirtyLedger(redis);
    }

    private FlushOrchestrator orchestrator(int chunkSize) {
        return new FlushOrchestrator(
                new DirtyLedger(redis), new RedisStore(redis),
                new MongoStore(mongo, MONGO_DB), chunkSize);
    }

    @Test
    void emptyDirtyReturnsZeroSoShutdownLoopCanTellItIsClean() {
        assertThat(orchestrator(500).flushOnce()).isZero();
    }

    @Test
    void flushMovesRedisJsonIntoMongoByteForByte() {
        // 架构 §4.1「落盘零转换」：Mongo 文档的 v 字段必须与 Redis 里的 JSON 逐字节相同
        RedisStore r = new RedisStore(redis);
        String json = "{\"name\":\"alice\",\"coin\":70}";
        r.set("player:1:profile", json);
        dirty().mark("player:1:profile");

        assertThat(orchestrator(500).flushOnce()).isEqualTo(1);
        assertThat(new MongoStore(mongo, MONGO_DB).load("player:1:profile")).isEqualTo(json);
    }

    @Test
    void flushClearsBothDirtyAndInflightOnSuccess() {
        new RedisStore(redis).set("player:1:profile", "{}");
        DirtyLedger d = dirty();
        d.mark("player:1:profile");

        orchestrator(500).flushOnce();

        assertThat(d.members()).isEmpty();
        assertThat(d.inflightMembers()).isEmpty();   // 每片 ack 后 inflight 自然空
    }

    @Test
    void flushSpansMultipleChunksAndLandsEverything() {
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = dirty();
        for (int i = 0; i < 12; i++) {
            r.set("player:" + i + ":profile", "{\"i\":" + i + "}");
            d.mark("player:" + i + ":profile");
        }

        assertThat(orchestrator(5).flushOnce()).isEqualTo(12);   // 5+5+2 三片

        MongoStore m = new MongoStore(mongo, MONGO_DB);
        for (int i = 0; i < 12; i++) {
            assertThat(m.load("player:" + i + ":profile")).isEqualTo("{\"i\":" + i + "}");
        }
        assertThat(d.members()).isEmpty();
    }

    @Test
    void keyMissingFromRedisIsSkippedAndNotPutBackIntoDirty() {
        // 落盘 spec §3：key 已被删/过期但标记尚存 → 跳过 upsert，且绝不回 dirty
        // （回了就是永久重试的死循环，每轮都失败、每轮都告警）
        DirtyLedger d = dirty();
        d.mark("player:404:bag");

        assertThat(orchestrator(500).flushOnce()).isEqualTo(1);   // 排空了 1 个
        assertThat(d.members()).isEmpty();                        // 但没回来
        assertThat(d.inflightMembers()).isEmpty();
        assertThat(new MongoStore(mongo, MONGO_DB).load("player:404:bag")).isNull();
    }

    @Test
    void concurrentWriteDuringFlushSurvivesAsNewDirtyMark() {
        // 落盘 spec §2.1 的端到端回归：被修订掉的「GET 后 SREM」会在这里丢掉 v2 的标记
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = dirty();
        r.set("player:1:bag", "[1]");
        d.mark("player:1:bag");

        Set<String> snapshot = d.drainToInflight();       // 模拟本轮已排空
        r.set("player:1:bag", "[1,2]");                   // 业务侧写 v2
        d.mark("player:1:bag");                           // 并重新标脏
        d.ackInflight(snapshot);                          // 本轮 ack 自己的快照

        assertThat(d.members()).containsExactly("player:1:bag");   // v2 的标记还在

        orchestrator(500).flushOnce();                    // 下一轮落 v2
        assertThat(new MongoStore(mongo, MONGO_DB).load("player:1:bag")).isEqualTo("[1,2]");
    }

    @Test
    void leftoverInflightFromCrashedRoundIsRecoveredAndFlushed() {
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = dirty();
        r.set("player:1:profile", "{\"v\":1}");
        d.mark("player:1:profile");
        d.drainToInflight();                              // 排空后「崩溃」，不 ack
        assertThat(d.members()).isEmpty();
        assertThat(d.inflightMembers()).containsExactly("player:1:profile");

        // 下一轮必须自愈：drain 的恢复步骤把残留合回，照常落盘
        assertThat(orchestrator(500).flushOnce()).isEqualTo(1);
        assertThat(new MongoStore(mongo, MONGO_DB).load("player:1:profile")).isEqualTo("{\"v\":1}");
        assertThat(d.inflightMembers()).isEmpty();
    }

    @Test
    void failedKeyGoesBackToDirtyWhileTheRestOfTheChunkLands() {
        // 落盘 spec §4：唯一索引冲突制造「同批恰好一条写失败」，
        // 验证只有它回 dirty、同片其余照落（unordered 的收益）
        db().getCollection("player:profile").createIndex(
                new org.bson.Document("v", 1),
                new com.mongodb.client.model.IndexOptions().unique(true));
        MongoStore m = new MongoStore(mongo, MONGO_DB);
        m.upsert("player:1:profile", "{\"dup\":1}");

        RedisStore r = new RedisStore(redis);
        DirtyLedger d = dirty();
        r.set("player:2:profile", "{\"dup\":1}");    // 与 player:1 的 v 冲突
        r.set("player:3:profile", "{\"ok\":3}");
        d.mark("player:2:profile");
        d.mark("player:3:profile");

        orchestrator(500).flushOnce();

        assertThat(d.members()).containsExactly("player:2:profile");   // 只有失败者回来
        assertThat(d.inflightMembers()).isEmpty();                     // 整片都已 ack
        assertThat(m.load("player:3:profile")).isEqualTo("{\"ok\":3}");
    }

    @Test
    void chunkSizeOneStillWorks() {
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = dirty();
        r.set("player:1:profile", "{}");
        r.set("player:2:bag", "[]");
        d.mark("player:1:profile");
        d.mark("player:2:bag");

        assertThat(orchestrator(1).flushOnce()).isEqualTo(2);
        assertThat(d.members()).isEmpty();
    }

    @Test
    void flushOnceIsIdempotentWhenRunTwice() {
        RedisStore r = new RedisStore(redis);
        r.set("player:1:profile", "{\"v\":1}");
        dirty().mark("player:1:profile");
        FlushOrchestrator o = orchestrator(500);

        assertThat(o.flushOnce()).isEqualTo(1);
        assertThat(o.flushOnce()).isZero();     // 第二轮无事可做
        assertThat(new MongoStore(mongo, MONGO_DB).load("player:1:profile")).isEqualTo("{\"v\":1}");
    }

    @Test
    void listVersionOfChunksPreservesEveryKey() {
        assertThat(FlushOrchestrator.chunks(List.of("a", "b", "c"), 2))
                .flatExtracting(c -> c).containsExactly("a", "b", "c");
    }
}
```

- [ ] **Step 4: 跑测试确认失败**

Run: `./mvnw -pl game-dbserver -am verify -Dit.test=FlushOrchestratorIT`
Expected: 编译失败，`cannot find symbol: class FlushOrchestrator`

- [ ] **Step 5: 实现 `FlushOrchestrator`**

Create `game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushOrchestrator.java`：

```java
package io.github.brick.dbserver.flush;

import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一轮落盘的完整编排（落盘 spec §2~§4）。无状态，谁都能调——定时轮次调它，停机刷盘也调它。
 *
 * <p>流程：原子排空 dirty → 按 {@code chunkSize} 分片 → 每片 {@code MGET} 取值（还 Redis 连接）
 * → {@code bulkUpsert} 写 Mongo → 失败 key 回写 dirty → 整片移出 in-flight。
 *
 * <p><b>禁嵌套跨池</b>（并发修订 §3.2）：{@code mget} 返回时 Redis 连接已归还，之后才触达 Mongo，
 * 一个虚拟线程任一时刻只持有一个池的连接。
 *
 * <p><b>分片是为了让内存与单次往返量只与片大小有关、与积压总量无关</b>：冷启动首轮或积压上万时，
 * 逐个 {@code GET} 是上万次同步往返，而一次攒上万个 {@code Document} 才是真问题（落盘 spec §3）。
 *
 * <p><b>中断与崩溃路径零操作</b>：剩余 key 留在 in-flight，由下一轮 {@code drainToInflight}
 * 的恢复步骤合回 dirty（落盘 spec §2.3）。故本类没有任何「回滚」或「清理」分支。
 */
public class FlushOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(FlushOrchestrator.class);

    /** {@link #flushOnce()} 的第三态：本轮没干成事（非「已刷空」）。见落盘 spec §3.1 返回值契约。 */
    public static final int INTERRUPTED = -1;

    private final DirtyLedger dirty;
    private final RedisStore redis;
    private final MongoStore mongo;
    private final int chunkSize;

    public FlushOrchestrator(DirtyLedger dirty, RedisStore redis, MongoStore mongo, int chunkSize) {
        this.dirty = dirty;
        this.redis = redis;
        this.mongo = mongo;
        this.chunkSize = chunkSize;
    }

    /**
     * 跑一轮落盘。
     *
     * @return 本轮排空的 key 数（含其中落盘失败、已回写 dirty 的）；{@code 0} 表示 drain 没找到
     *         任何 key——**只有这一种情况才代表「真刷空了」**，停机循环据此收敛（落盘 spec §3.1）。
     *         返回排空数而非成功数是刻意的：若一批全部失败却返回 0，停机循环会把「dirty 里还有货」
     *         误判成刷完而提前退出。
     * @throws com.mongodb.MongoException Mongo 连接级故障，本轮中断；剩余 key 留在 in-flight
     */
    public int flushOnce() {
        Set<String> keys = dirty.drainToInflight();
        if (keys.isEmpty()) {
            return 0;
        }
        for (List<String> chunk : chunks(keys, chunkSize)) {
            flushChunk(chunk);
        }
        return keys.size();
    }

    /** 本片完整落盘处理。**受保护仅为可测**——「锁在片间过期/异常」的确定性测试
     * 在子类里覆盖它（落盘 spec §5）；生产语义就是逐个分片处理。 */
    protected void flushChunk(List<String> chunk) {
        Map<String, String> values = redis.mget(chunk);      // 一次往返；返回即还 Redis 连接

        // mget 不把不存在的 key 映射到 null，而是不放进 Map，故差值即 nil 数量。
        // 这些 key 跳过 upsert 且**不回 dirty**——回了就是永久重试的死循环。
        int missing = chunk.size() - values.size();
        if (missing > 0) {
            log.warn("本片 {} 个 key 在 Redis 已不存在（标记残留），跳过落盘且不回写 dirty", missing);
        }

        Set<String> failed = mongo.bulkUpsert(values);
        if (!failed.isEmpty()) {
            log.warn("落盘失败 {} 个 key，回写 dirty 等下轮重试: {}", failed.size(), failed);
            dirty.markAll(failed);      // 顺序要求：必须先 mark 再 ack（落盘 spec §2.4）
        }
        dirty.ackInflight(chunk);       // 两步之间崩溃只会导致整片被下轮重放，upsert 幂等
    }

    /** 定长切片。包级可见供单测——切分边界（空集/整数倍/余数）值得独立于 Redis 验证。 */
    static List<List<String>> chunks(Collection<String> keys, int size) {
        List<String> all = new ArrayList<>(keys);
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            out.add(all.subList(i, Math.min(i + size, all.size())));
        }
        return out;
    }
}
```

- [ ] **Step 6: 装配 bean**

Create `game-dbserver/src/main/java/io/github/brick/dbserver/config/DbServerConfiguration.java`：

```java
package io.github.brick.dbserver.config;

import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import io.github.brick.dbserver.flush.FlushOrchestrator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 落盘编排装配。沿用 {@code DataAutoConfiguration} 的**显式 @Bean** 风格，不用 @Component 扫描
 * ——构造依赖一眼可见，且落盘各组件的协作顺序（编排 ← 调度 ← 停机）在这里读得出来。
 *
 * <p>{@link DirtyLedger}/{@link RedisStore}/{@link MongoStore} 由 {@code game-data} 的
 * 自动装配提供；本模块不登记 {@code EntityPriorities}，故 {@code LockScope} 不装配——落盘不加实体锁。
 */
@Configuration
@EnableConfigurationProperties(DbServerProperties.class)
public class DbServerConfiguration {

    @Bean
    FlushOrchestrator flushOrchestrator(DirtyLedger dirty, RedisStore redis,
                                        MongoStore mongo, DbServerProperties p) {
        return new FlushOrchestrator(dirty, redis, mongo, p.getChunkSize());
    }
}
```

- [ ] **Step 7: 跑测试确认通过**

Run: `./mvnw -pl game-dbserver -am verify`
Expected: PASS（`FlushChunkingTest` 5 个 + `FlushOrchestratorIT` 11 个 + 原有测试全绿）

- [ ] **Step 8: Commit**

```bash
git add game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushOrchestrator.java \
        game-dbserver/src/main/java/io/github/brick/dbserver/config/DbServerConfiguration.java \
        game-dbserver/src/test/java/io/github/brick/dbserver/flush/
git commit -m "feat(dbserver): FlushOrchestrator 分片流水线

drain → 分片 → MGET（还 Redis 连接）→ bulkUpsert → 失败回写 → ack，
内存与单次往返量只与片大小有关、与积压总量无关。

flushOnce 返回「排空数」而非「成功数」：若一批全部失败却返回 0，
停机循环会把「dirty 里还有货」误判成刷完而提前退出。

中断/崩溃路径零操作——剩余留在 inflight 由下轮 drain 自愈，
故本类没有任何回滚或清理分支。IT 含 §2.1 丢标记窗口的端到端回归。"
```

---

## Task 7: 落盘锁 —— 单实例保证

落盘 spec §5。`RENAME` 是覆盖语义，**多实例并发在新协议下会直接丢标记**（旧的 `SMEMBERS`+`SREM` 协议只是重复劳动）：实例 A 排空拿到 100 个 key，实例 B 紧接着 `RENAME` 覆盖掉 A 的 in-flight，A 的 100 个标记蒸发，A 落完再 ack 又把 B 的一并清掉。

**Files:**
- Modify: `game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushOrchestrator.java`
- Modify: `game-dbserver/src/main/java/io/github/brick/dbserver/config/DbServerConfiguration.java`
- Test: `game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushLockIT.java`
- Modify: `DEVELOPMENT.md`

**Interfaces:**
- Consumes: Task 6 的 `FlushOrchestrator`、`DbServerProperties.getLockLeaseSeconds()`
- Produces:
  - 构造器变为 `FlushOrchestrator(RedissonClient, DirtyLedger, RedisStore, MongoStore, int chunkSize, long lockLeaseSeconds)`
  - `flushOnce()` 新增第三态返回 `INTERRUPTED`（`-1`）：抢不到锁，或落盘中途失锁
  - `public static final String FLUSH_LOCK = "lock:dbserver:flush"`

- [ ] **Step 1: 写失败的测试**

Create `game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushLockIT.java`：

```java
package io.github.brick.dbserver.flush;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class FlushLockIT extends LocalRedisMongo {

    private FlushOrchestrator orchestrator() {
        return new FlushOrchestrator(redis, new DirtyLedger(redis), new RedisStore(redis),
                new MongoStore(mongo, MONGO_DB), 500, 60L);
    }

    @Test
    void lockNameIsProcessLevelNotAnEntityLock() {
        // 刻意不走 DataKeys.lockKey()——那是 lock:{entity}:{id} 的实体锁命名。
        // 本锁是进程级互斥锁，不复用以免两种语义混淆。
        assertThat(FlushOrchestrator.FLUSH_LOCK).isEqualTo("lock:dbserver:flush");
    }

    @Test
    void roundSkipsWhenAnotherInstanceHoldsTheLock() {
        // 落盘 spec §5 的回归测试：验证第二实例跳过，且**零标记丢失**
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = new DirtyLedger(redis);
        r.set("player:1:profile", "{\"v\":1}");
        d.mark("player:1:profile");

        RedissonClient other = newClient();           // 模拟另一个 dbserver 实例
        try {
            RLock held = other.getLock(FlushOrchestrator.FLUSH_LOCK);
            assertThat(held.tryLock(0, 60, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(orchestrator().flushOnce()).isEqualTo(FlushOrchestrator.INTERRUPTED);
                // 关键：本轮什么都没做，dirty 一个不少（而不是被排空后丢掉）
                assertThat(d.members()).containsExactly("player:1:profile");
                assertThat(d.inflightMembers()).isEmpty();
            } finally {
                held.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            other.shutdown();
        }
    }

    @Test
    void roundProceedsOnceTheLockIsFree() {
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = new DirtyLedger(redis);
        r.set("player:1:profile", "{\"v\":1}");
        d.mark("player:1:profile");

        assertThat(orchestrator().flushOnce()).isEqualTo(1);
        assertThat(new MongoStore(mongo, MONGO_DB).load("player:1:profile")).isEqualTo("{\"v\":1}");
    }

    @Test
    void lockIsReleasedAfterASuccessfulRoundSoTheNextOneCanRun() {
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = new DirtyLedger(redis);
        r.set("player:1:profile", "{}");
        d.mark("player:1:profile");
        FlushOrchestrator o = orchestrator();
        o.flushOnce();

        // 锁必须已释放：否则下一轮永远抢不到，落盘就此停摆
        assertThat(redis.getLock(FlushOrchestrator.FLUSH_LOCK).isLocked()).isFalse();
        r.set("player:2:bag", "[]");
        d.mark("player:2:bag");
        assertThat(o.flushOnce()).isEqualTo(1);
    }

    @Test
    void lockIsReleasedEvenWhenTheRoundBlowsUp() {
        // Mongo 连接级异常会穿透 flushOnce（落盘 spec §4），锁仍须释放，
        // 否则一次 Mongo 抖动会让落盘停摆到租约到期。
        // 用覆盖 flushChunk 抛异常的子类，确定性地制造「一轮中途爆炸」，
        // 不依赖某版 Mongo 驱动对非法库名的校验行为。
        DirtyLedger d = new DirtyLedger(redis);
        new RedisStore(redis).set("player:1:profile", "{}");
        d.mark("player:1:profile");

        FlushOrchestrator broken = new FlushOrchestrator(
                redis, d, new RedisStore(redis), new MongoStore(mongo, MONGO_DB), 500, 60L) {
            @Override
            protected void flushChunk(java.util.List<String> chunk) {
                throw new IllegalStateException("mongo 抖了");
            }
        };

        assertThat(catchThrowable(broken::flushOnce)).isNotNull();
        assertThat(redis.getLock(FlushOrchestrator.FLUSH_LOCK).isLocked()).isFalse();
        // 剩余 key 留在 inflight，等下轮 drain 自愈
        assertThat(d.inflightMembers()).containsExactly("player:1:profile");
    }

    @Test
    void multiChunkRoundIsNotMistakenForALostLock() {
        // 每片之后都会检查持锁；正常路径下多片必须完整跑完，不能被误判成中途失锁
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = new DirtyLedger(redis);
        for (int i = 0; i < 6; i++) {
            r.set("player:" + i + ":profile", "{\"i\":" + i + "}");
            d.mark("player:" + i + ":profile");
        }
        FlushOrchestrator perKeyChunks = new FlushOrchestrator(
                redis, d, r, new MongoStore(mongo, MONGO_DB), 1, 60L);

        assertThat(perKeyChunks.flushOnce()).isEqualTo(6);
        assertThat(d.members()).isEmpty();
        assertThat(d.inflightMembers()).isEmpty();
    }
}
```

> **给实现者的说明**：真正的「中途失锁 → 返回 `INTERRUPTED` 且剩余留在 in-flight」无法用真实租约时序稳定复现（要精确卡在两片之间到期）。**Step 7 把持锁判定抽成一个可覆盖的方法，用测试子类让它确定性发生**——本步先只覆盖「正常路径不误判中断」，失锁分支留给 Step 7。

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-dbserver -am verify -Dit.test=FlushLockIT`
Expected: 编译失败，`constructor FlushOrchestrator cannot be applied to given types`（6 参构造器还不存在）

- [ ] **Step 3: 给 `FlushOrchestrator` 加锁**

`FlushOrchestrator.java`——import 补 `org.redisson.api.RLock`、`org.redisson.api.RedissonClient`、`java.util.concurrent.TimeUnit`；加常量与字段：

```java
    /**
     * 落盘进程级互斥锁。**刻意不走 {@code DataKeys.lockKey()}**——那是 {@code lock:{entity}:{id}}
     * 的实体锁命名，本锁不是实体锁，不复用以免两种语义混淆（落盘 spec §5）。
     */
    public static final String FLUSH_LOCK = "lock:dbserver:flush";

    private final RedissonClient redisson;
    private final long lockLeaseSeconds;
```

构造器改为 6 参（字段赋值照加）：

```java
    public FlushOrchestrator(RedissonClient redisson, DirtyLedger dirty, RedisStore redis,
                             MongoStore mongo, int chunkSize, long lockLeaseSeconds) {
        this.redisson = redisson;
        this.dirty = dirty;
        this.redis = redis;
        this.mongo = mongo;
        this.chunkSize = chunkSize;
        this.lockLeaseSeconds = lockLeaseSeconds;
    }
```

`flushOnce()` 改为先抢锁，并把排空+分片挪到私有方法：

```java
    /**
     * 跑一轮落盘。
     *
     * @return 三态（落盘 spec §3.1 返回值契约）：<ul>
     *   <li>{@code 0} —— 排空后无 key，**只有这一种代表「真刷空了」**，停机循环据此收敛；</li>
     *   <li>{@code n > 0} —— 本轮排空的 key 数（含其中落盘失败、已回写 dirty 的）；</li>
     *   <li>{@link #INTERRUPTED} —— 抢不到锁（另一实例在落盘）或中途失锁，本轮没干成事。</li></ul>
     *   返回排空数而非成功数是刻意的：若一批全部失败却返回 0，停机循环会把「dirty 里还有货」
     *   误判成刷完而提前退出。
     * @throws com.mongodb.MongoException Mongo 连接级故障，本轮中断；剩余 key 留在 in-flight
     */
    public int flushOnce() {
        RLock lock = redisson.getLock(FLUSH_LOCK);
        boolean acquired;
        try {
            // tryLock(0, lease, …)：不等待，抢不到立刻走。固定租约、无看门狗（并发修订 §4.1）
            acquired = lock.tryLock(0L, lockLeaseSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return INTERRUPTED;
        }
        if (!acquired) {
            // 这是热备实例的**正常**状态，不是故障，故 DEBUG 而非 WARN
            log.debug("未抢到落盘锁，本轮跳过（另一实例正在落盘）");
            return INTERRUPTED;
        }
        try {
            return drainAndFlush(lock);
        } finally {
            // 必须先判 isHeld：租约已到期时 unlock() 会抛 IllegalMonitorStateException，
            // 那会盖掉 try 块里真正的异常
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private int drainAndFlush(RLock lock) {
        Set<String> keys = dirty.drainToInflight();
        if (keys.isEmpty()) {
            return 0;
        }
        for (List<String> chunk : chunks(keys, chunkSize)) {
            flushChunk(chunk);
            if (!lock.isHeldByCurrentThread()) {
                // 与 LockCtx.put 的 isHeld 提交门控同构：绝不带着失效锁继续写。
                // 剩余 key 留在 in-flight，由下一轮 drain 的恢复步骤合回（落盘 spec §2.3）
                log.error("落盘中途失锁（租约 {}s 到期），中断本轮；剩余 {} 个 key 留在 in-flight 待下轮恢复",
                        lockLeaseSeconds, dirty.inflightMembers().size());
                return INTERRUPTED;
            }
        }
        return keys.size();
    }
```

- [ ] **Step 4: 更新 bean 装配**

`DbServerConfiguration.java`——import 补 `org.redisson.api.RedissonClient`，`flushOrchestrator` 方法改为：

```java
    @Bean
    FlushOrchestrator flushOrchestrator(RedissonClient redisson, DirtyLedger dirty,
                                        RedisStore redis, MongoStore mongo, DbServerProperties p) {
        return new FlushOrchestrator(redisson, dirty, redis, mongo,
                p.getChunkSize(), p.getLockLeaseSeconds());
    }
```

- [ ] **Step 5: 更新 Task 6 的 IT 以适配新构造器**

`FlushOrchestratorIT` 的 `orchestrator(int)` 与 `failedKeyGoesBackToDirty...` 里的构造调用都要补两个参数：

```java
    private FlushOrchestrator orchestrator(int chunkSize) {
        return new FlushOrchestrator(
                redis, new DirtyLedger(redis), new RedisStore(redis),
                new MongoStore(mongo, MONGO_DB), chunkSize, 60L);
    }
```

- [ ] **Step 6: 跑测试确认通过**

Run: `./mvnw -pl game-dbserver -am verify`
Expected: PASS（`FlushLockIT` + `FlushOrchestratorIT` + `FlushChunkingTest` 全绿）

- [ ] **Step 7: 补一个确定性的「中途失锁」测试**

真实时序难稳定复现，故把失锁判定抽成可覆盖的受保护方法，用子类注入。

先在 `FlushOrchestrator` 里把那句检查换成方法调用：

```java
            if (!stillHoldsLock(lock)) {
```

并新增：

```java
    /**
     * 是否仍持有落盘锁。**受保护仅为可测**——「跑到第 N 片时失锁」用真实租约到期无法稳定复现，
     * 测试子类覆盖本方法即可确定性地触发中断分支。生产语义就是 isHeldByCurrentThread()。
     */
    protected boolean stillHoldsLock(RLock lock) {
        return lock.isHeldByCurrentThread();
    }
```

然后在 `FlushLockIT` 里追加两个确定性用例（Step 1 的 `multiChunkRoundIsNotMistakenForALostLock` 保留——它守的是「正常路径不误判」这一侧）：

```java
    @Test
    void losingLockMidRoundInterruptsAndLeavesRemainderInInflight() {
        // 落盘 spec §5：每片处理完检查持锁，失锁立即中断本轮、剩余留在 in-flight。
        // 覆盖 stillHoldsLock 让「第一片之后失锁」确定性发生。
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = new DirtyLedger(redis);
        for (int i = 0; i < 4; i++) {
            r.set("player:" + i + ":profile", "{\"i\":" + i + "}");
            d.mark("player:" + i + ":profile");
        }

        FlushOrchestrator losesLock = new FlushOrchestrator(
                redis, d, r, new MongoStore(mongo, MONGO_DB), 1, 60L) {
            private int chunksDone = 0;

            @Override
            protected boolean stillHoldsLock(org.redisson.api.RLock lock) {
                return ++chunksDone < 2;      // 第一片之后即视为失锁
            }
        };

        assertThat(losesLock.flushOnce()).isEqualTo(FlushOrchestrator.INTERRUPTED);
        // 第一片已落，剩下三个仍在 in-flight（没被 ack、也没丢）
        assertThat(new MongoStore(mongo, MONGO_DB).load("player:0:profile")).isNotNull();
        assertThat(d.inflightMembers()).hasSize(3);
        assertThat(d.members()).isEmpty();
    }

    @Test
    void remainderLeftByAnInterruptedRoundIsPickedUpByTheNextOne() {
        RedisStore r = new RedisStore(redis);
        DirtyLedger d = new DirtyLedger(redis);
        for (int i = 0; i < 3; i++) {
            r.set("player:" + i + ":profile", "{\"i\":" + i + "}");
            d.mark("player:" + i + ":profile");
        }
        new FlushOrchestrator(redis, d, r, new MongoStore(mongo, MONGO_DB), 1, 60L) {
            @Override
            protected boolean stillHoldsLock(org.redisson.api.RLock lock) {
                return false;                 // 第一片之后就中断
            }
        }.flushOnce();
        assertThat(d.inflightMembers()).hasSize(2);

        // 下一轮（正常持锁）必须把残留捡起来落完
        assertThat(orchestrator().flushOnce()).isEqualTo(2);
        MongoStore m = new MongoStore(mongo, MONGO_DB);
        for (int i = 0; i < 3; i++) {
            assertThat(m.load("player:" + i + ":profile")).isEqualTo("{\"i\":" + i + "}");
        }
        assertThat(d.inflightMembers()).isEmpty();
    }
```

Run: `./mvnw -pl game-dbserver -am verify -Dit.test=FlushLockIT`
Expected: PASS

- [ ] **Step 8: 补 `DEVELOPMENT.md` 的多实例说明**

在 Task 5 新增的「四之二、落盘配置」一节末尾追加：

```markdown
### 多实例

`game-dbserver` **允许起多个实例**，多出的会作热备空转：每轮落盘前竞争 Redis 锁
`lock:dbserver:flush`，抢不到就跳过本轮（DEBUG 日志，不是故障）。主实例进程硬崩后，
备实例在租约（默认 60s）内自动接管。

正确性由这把锁保证，**不依赖部署纪律**——这与架构 §2 放弃 sticky 路由时立下的原则一致：
写正确性不押在运维正确配置上。
```

- [ ] **Step 9: Commit**

```bash
git add game-dbserver/src/main/java/io/github/brick/dbserver/ \
        game-dbserver/src/test/java/io/github/brick/dbserver/flush/ \
        DEVELOPMENT.md
git commit -m "feat(dbserver): 落盘锁保证单实例 + 每片 isHeld 门控

RENAME 是覆盖语义，多实例并发在新协议下会直接丢标记：A 排空拿到 100 个 key，
B 紧接着 RENAME 覆盖掉 A 的 inflight，A 的标记蒸发，A 落完再 ack 又清掉 B 的。
旧的 SMEMBERS+SREM 协议下这只是重复劳动，新协议下是丢数据。

每片处理完检查 isHeldByCurrentThread()，与 LockCtx.put 的提交门控同构。
白赚一个热备能力：抢不到锁的实例空转待命，主实例崩后租约内接管。

stillHoldsLock 抽成受保护方法仅为可测——真实租约到期无法稳定卡在片间。"
```

---

## Task 8: `FlushScheduler` — 定时触发与进程内串行

落盘 spec §6.1 的**内层**互斥。跨进程由 Task 7 的 Redis 锁管，进程内由一把 `ReentrantLock` 管——停机路径**不能靠「抢不到就跳过」**（那就刷不成了），必须真等当前轮结束。

**Files:**
- Create: `game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushScheduler.java`
- Modify: `game-dbserver/src/main/java/io/github/brick/dbserver/config/DbServerConfiguration.java`
- Test: `game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushSchedulerTest.java`

**Interfaces:**
- Consumes: `FlushOrchestrator.flushOnce()`（Task 6/7）
- Produces:
  - `FlushScheduler(FlushOrchestrator)`
  - `void tick()` — `@Scheduled`，`accepting` 为假或抢不到进程内锁时直接返回
  - `int flushBlocking()` — 阻塞拿进程内锁后跑一轮，返回 `flushOnce()` 的原值。Task 9 的停机循环用
  - `void stopAccepting()` — 关掉定时轮次的入口。Task 9 先调它再进循环

- [ ] **Step 1: 写失败的测试**

Create `game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushSchedulerTest.java`：

```java
package io.github.brick.dbserver.flush;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯单测：用一个计数的 FlushOrchestrator 子类替掉真实落盘，只验调度门控逻辑，不连 Redis/Mongo。
 */
class FlushSchedulerTest {

    /** 计数用的假编排器：不碰 Redis/Mongo，构造参数全传 null 也不会被解引用。 */
    private static class CountingOrchestrator extends FlushOrchestrator {
        final AtomicInteger calls = new AtomicInteger();
        int result = 0;

        CountingOrchestrator() {
            super(null, null, null, null, 500, 60L);
        }

        @Override
        public int flushOnce() {
            calls.incrementAndGet();
            return result;
        }
    }

    @Test
    void tickRunsARound() {
        CountingOrchestrator o = new CountingOrchestrator();
        new FlushScheduler(o).tick();
        assertThat(o.calls).hasValue(1);
    }

    @Test
    void tickIsANoOpAfterStopAccepting() {
        // 停机时必须立刻停止起新轮，否则停机循环与定时轮次会互相抢 Redis 锁、拖长停机
        CountingOrchestrator o = new CountingOrchestrator();
        FlushScheduler s = new FlushScheduler(o);
        s.stopAccepting();
        s.tick();
        assertThat(o.calls).hasValue(0);
    }

    @Test
    void flushBlockingStillRunsAfterStopAccepting() {
        // 关键：stopAccepting 只关定时入口，不能把停机自己的刷盘也关掉
        CountingOrchestrator o = new CountingOrchestrator();
        FlushScheduler s = new FlushScheduler(o);
        s.stopAccepting();
        assertThat(s.flushBlocking()).isZero();
        assertThat(o.calls).hasValue(1);
    }

    @Test
    void flushBlockingPassesThroughTheReturnValue() {
        // 停机循环靠返回值区分「真刷空」与「没干成事」，不能被包装掉
        CountingOrchestrator o = new CountingOrchestrator();
        FlushScheduler s = new FlushScheduler(o);
        o.result = 7;
        assertThat(s.flushBlocking()).isEqualTo(7);
        o.result = FlushOrchestrator.INTERRUPTED;
        assertThat(s.flushBlocking()).isEqualTo(FlushOrchestrator.INTERRUPTED);
    }

    @Test
    void tickSwallowsExceptionsSoTheScheduleSurvives() {
        // 一次 Mongo 抖动不该让定时轮次此后再也不跑
        FlushOrchestrator boom = new FlushOrchestrator(null, null, null, null, 500, 60L) {
            @Override
            public int flushOnce() {
                throw new IllegalStateException("mongo 抖了");
            }
        };
        FlushScheduler s = new FlushScheduler(boom);
        s.tick();       // 不抛出即通过
        s.tick();
    }

    @Test
    void concurrentTickDoesNotOverlapWithARunningRound() throws Exception {
        // 进程内串行（spec §6.1 内层）：一轮在跑时，另一个触发必须跳过而不是并发进去
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();

        FlushOrchestrator slow = new FlushOrchestrator(null, null, null, null, 500, 60L) {
            @Override
            public int flushOnce() {
                maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrent.decrementAndGet();
                return 1;
            }
        };
        FlushScheduler s = new FlushScheduler(slow);

        Thread first = new Thread(s::tick);
        first.start();
        inside.await();          // 确认第一轮已进到 flushOnce 内部
        s.tick();                // 第二次触发：应当直接跳过
        release.countDown();
        first.join();

        assertThat(maxConcurrent).hasValue(1);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-dbserver -am test -Dtest=FlushSchedulerTest`
Expected: 编译失败，`cannot find symbol: class FlushScheduler`

- [ ] **Step 3: 实现**

Create `game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushScheduler.java`：

```java
package io.github.brick.dbserver.flush;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 定时触发落盘轮次，并保证**进程内**串行（落盘 spec §6.1 的内层互斥）。
 *
 * <p>两层互斥合起来才完整：<ul>
 *   <li><b>进程内</b>——本类的 {@link ReentrantLock}：定时轮次与停机刷盘不重叠；</li>
 *   <li><b>跨进程</b>——{@link FlushOrchestrator#FLUSH_LOCK}：多实例不重叠。</li></ul>
 *
 * <p>为什么进程内还要单独一把锁：停机路径**不能靠「抢不到 Redis 锁就跳过」**——那正是要刷盘的
 * 时候，跳过就刷不成了。它必须阻塞等当前轮真正结束，故需要一个可阻塞等待的进程内闩。
 *
 * <p>{@code @Scheduled} 的 {@code initialDelayString} 与 {@code fixedDelayString} 取同一个配置：
 * 首轮延后一个间隔而非上下文就绪即刻触发——避免与启动过程抢资源，也让不连外部服务的
 * 上下文测试（把间隔调大即可）不被打扰。
 */
public class FlushScheduler {

    private static final Logger log = LoggerFactory.getLogger(FlushScheduler.class);

    private final FlushOrchestrator orchestrator;

    /** 进程内串行闩。定时轮次用 tryLock（抢不到就跳过），停机刷盘用 lock（真等）。 */
    private final ReentrantLock gate = new ReentrantLock();

    private volatile boolean accepting = true;

    public FlushScheduler(FlushOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(
            initialDelayString = "${game.dbserver.flush-interval-millis:2000}",
            fixedDelayString = "${game.dbserver.flush-interval-millis:2000}")
    public void tick() {
        if (!accepting) {
            return;
        }
        if (!gate.tryLock()) {
            log.debug("上一轮落盘仍在进行，本次触发跳过");
            return;
        }
        try {
            orchestrator.flushOnce();
        } catch (RuntimeException e) {
            // 吞掉并记日志：一次 Mongo 抖动不该让定时轮次此后再也不跑。
            // 剩余 key 留在 in-flight，由下一轮 drain 的恢复步骤合回（落盘 spec §2.3）
            log.error("落盘轮次异常结束，剩余 key 留在 in-flight 待下轮恢复", e);
        } finally {
            gate.unlock();
        }
    }

    /**
     * 停机路径专用：**阻塞**等当前轮跑完，再自己跑一轮。
     *
     * @return {@link FlushOrchestrator#flushOnce()} 的原值，不做任何包装——停机循环靠它区分
     *         「真刷空」（{@code 0}）与「没干成事」（{@link FlushOrchestrator#INTERRUPTED}）
     */
    public int flushBlocking() {
        gate.lock();
        try {
            return orchestrator.flushOnce();
        } finally {
            gate.unlock();
        }
    }

    /** 关掉定时轮次入口。**不影响 {@link #flushBlocking()}**——那是停机自己要用的。 */
    public void stopAccepting() {
        accepting = false;
    }
}
```

- [ ] **Step 4: 装配 bean 并开启调度**

`DbServerConfiguration.java`——加 `@EnableScheduling`（import `org.springframework.scheduling.annotation.EnableScheduling`）与 bean：

```java
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DbServerProperties.class)
public class DbServerConfiguration {
```

```java
    @Bean
    FlushScheduler flushScheduler(FlushOrchestrator orchestrator) {
        return new FlushScheduler(orchestrator);
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -pl game-dbserver -am verify`
Expected: PASS（`FlushSchedulerTest` 6 个 + 既有全部绿；`DbServerApplicationTest` 因 Task 5 Step 7 把间隔调到 60s，首轮不会在测试期间触发）

- [ ] **Step 6: Commit**

```bash
git add game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushScheduler.java \
        game-dbserver/src/main/java/io/github/brick/dbserver/config/DbServerConfiguration.java \
        game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushSchedulerTest.java
git commit -m "feat(dbserver): FlushScheduler 定时触发 + 进程内串行

进程内单独一把 ReentrantLock 而非只靠 Redis 锁：停机路径不能靠
「抢不到就跳过」——那正是要刷盘的时候，跳过就刷不成了，它必须
阻塞等当前轮真正结束。

tick 吞掉 RuntimeException：一次 Mongo 抖动不该让定时轮次此后
再也不跑，剩余 key 留在 inflight 由下轮 drain 自愈。

initialDelayString 让首轮延后一个间隔，不在上下文就绪时即刻触发。"
```

---

## Task 9: `GracefulShutdown` — 循环刷到空 + 硬超时

落盘 spec §6。**「全量刷入」在 dbserver 单独停机时不是可达状态**（`game-web` 仍在 `SADD dirty`），故真实语义只能是「尽力刷 + 有界超时」。

**Files:**
- Create: `game-dbserver/src/main/java/io/github/brick/dbserver/flush/GracefulShutdown.java`
- Modify: `game-dbserver/src/main/java/io/github/brick/dbserver/config/DbServerConfiguration.java`
- Test: `game-dbserver/src/test/java/io/github/brick/dbserver/flush/GracefulShutdownTest.java`
- Modify: `DEVELOPMENT.md`

**Interfaces:**
- Consumes: `FlushScheduler.stopAccepting()` / `flushBlocking()`（Task 8）、`DirtyLedger.backlogSize()`（Task 3）、`DbServerProperties.getShutdownTimeoutSeconds()`（Task 5）
- Produces: `GracefulShutdown(FlushScheduler, DirtyLedger, long timeoutSeconds)` 实现 `SmartLifecycle`；`getPhase()` 返回 `Integer.MAX_VALUE`

- [ ] **Step 1: 写失败的测试**

Create `game-dbserver/src/test/java/io/github/brick/dbserver/flush/GracefulShutdownTest.java`：

```java
package io.github.brick.dbserver.flush;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GracefulShutdownTest {

    /** 可编排返回序列的假调度器：不碰 Redis/Mongo。 */
    private static class ScriptedScheduler extends FlushScheduler {
        final AtomicInteger rounds = new AtomicInteger();
        private final int[] results;
        boolean stopAcceptingCalled = false;

        ScriptedScheduler(int... results) {
            super(new FlushOrchestrator(null, null, null, null, 500, 60L));
            this.results = results;
        }

        @Override
        public int flushBlocking() {
            int i = rounds.getAndIncrement();
            return i < results.length ? results[i] : 0;
        }

        @Override
        public void stopAccepting() {
            stopAcceptingCalled = true;
        }
    }

    private static GracefulShutdown shutdown(ScriptedScheduler s, long timeoutSeconds) {
        GracefulShutdown g = new GracefulShutdown(s, null, timeoutSeconds);
        g.start();
        return g;
    }

    @Test
    void phaseIsMaxSoItStopsBeforeConnectionsAreDestroyed() {
        // Spring 关闭时先按 phase 降序执行 Lifecycle.stop()，之后才销毁 singleton；
        // 取最大 phase 确保刷盘跑在 RedissonClient.shutdown()/MongoClient.close() 之前。
        assertThat(shutdown(new ScriptedScheduler(), 20L).getPhase()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void startMakesItRunningSoSpringWillCallStop() {
        // SmartLifecycle.stop() 只在 isRunning() 为真时被调用——忘了置位，停机刷盘就静默不执行
        ScriptedScheduler s = new ScriptedScheduler();
        GracefulShutdown g = new GracefulShutdown(s, null, 20L);
        assertThat(g.isRunning()).isFalse();
        g.start();
        assertThat(g.isRunning()).isTrue();
    }

    @Test
    void stopFlushesUntilDrainComesBackEmpty() {
        ScriptedScheduler s = new ScriptedScheduler(5, 3, 0);
        GracefulShutdown g = shutdown(s, 20L);
        g.stop();
        assertThat(s.rounds).hasValue(3);          // 5 → 3 → 0，收到 0 即收敛
        assertThat(s.stopAcceptingCalled).isTrue();
        assertThat(g.isRunning()).isFalse();
    }

    @Test
    void stopClosesTheSchedulerEntranceBeforeFlushing() {
        // 顺序要求：先关定时入口，否则停机循环与定时轮次会互相抢 Redis 锁、拖长停机
        ScriptedScheduler s = new ScriptedScheduler(0) {
            @Override
            public int flushBlocking() {
                assertThat(stopAcceptingCalled).isTrue();
                return super.flushBlocking();
            }
        };
        shutdown(s, 20L).stop();
        assertThat(s.rounds).hasValue(1);
    }

    @Test
    void stopGivesUpAtTheDeadlineInsteadOfHangingForever() {
        // dbserver 单独停机时 game-web 仍在 SADD dirty，「刷到空」可能永远达不到；
        // 超时必须放行退出——残留仍在 dirty，下次启动接着落，不是丢数据。
        ScriptedScheduler neverClean = new ScriptedScheduler() {
            @Override
            public int flushBlocking() {
                rounds.incrementAndGet();
                return 1;                          // 永远还有货
            }
        };
        GracefulShutdown g = shutdown(neverClean, 1L);
        long start = System.nanoTime();
        g.stop();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isBetween(900L, 5_000L);   // 大约 1s 后放弃
        assertThat(neverClean.rounds.get()).isPositive();
    }

    @Test
    void stopKeepsRetryingWhenRoundsReportInterrupted() {
        // INTERRUPTED 不能被当成「刷完了」——那样另一实例持锁时停机会立刻放弃
        ScriptedScheduler s = new ScriptedScheduler(
                FlushOrchestrator.INTERRUPTED, FlushOrchestrator.INTERRUPTED, 0);
        shutdown(s, 20L).stop();
        assertThat(s.rounds).hasValue(3);
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-dbserver -am test -Dtest=GracefulShutdownTest`
Expected: 编译失败，`cannot find symbol: class GracefulShutdown`

- [ ] **Step 3: 实现**

Create `game-dbserver/src/main/java/io/github/brick/dbserver/flush/GracefulShutdown.java`：

```java
package io.github.brick.dbserver.flush;

import io.github.brick.data.overlay.DirtyLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.TimeUnit;

/**
 * 停机刷盘（落盘 spec §6）：关定时入口 → 等当前轮 → 循环刷到空 → 硬超时放行。
 *
 * <p><b>为什么不是「全量刷入」</b>：架构 §4.3 原文是「正常停服时 dirty 数据全量刷入 MongoDB」，
 * 但 dbserver 单独停机时 {@code game-web} 仍在运行并持续 {@code SADD dirty}——刷空一次，
 * 下一毫秒又有新的。真实语义只能是「尽力刷 + 有界超时」。配套的运维约定是
 * <b>先停 game-web、再停 game-dbserver</b>（见 DEVELOPMENT.md），否则这里刷的是持续注水的池子。
 *
 * <p><b>超时不等于丢数据</b>：残留仍在 dirty（或 in-flight，下轮合回），下次启动自然接着落。
 * 后果是「数据暂时只在 Redis」，而 Redis 有 AOF everysec 兜底。
 *
 * <p><b>为什么用 {@link SmartLifecycle} 而非 {@code @PreDestroy}</b>：Spring 关闭时先按 phase
 * 降序执行 {@code Lifecycle.stop()}，之后才销毁 singleton bean。{@code @PreDestroy} 相对其他 bean
 * 的销毁顺序不够可靠——{@code RedissonClient}（{@code destroyMethod="shutdown"}）或
 * {@code MongoClient}（{@code destroyMethod="close"}）可能已被关掉，刷盘就无连接可用。
 * 取 {@link Integer#MAX_VALUE} 作 phase 确保最先 {@code stop()}。
 */
public class GracefulShutdown implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdown.class);

    /** 两轮之间的间歇。抢不到 Redis 锁时 flushBlocking 会立刻返回，没有它就是个烧 CPU 的紧循环。 */
    private static final long PAUSE_MILLIS = 200L;

    private final FlushScheduler scheduler;
    private final DirtyLedger dirty;
    private final long timeoutSeconds;

    private volatile boolean running = false;

    public GracefulShutdown(FlushScheduler scheduler, DirtyLedger dirty, long timeoutSeconds) {
        this.scheduler = scheduler;
        this.dirty = dirty;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 最大 phase：最先 stop()，抢在 Redisson/Mongo 的 destroyMethod 之前。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void start() {
        running = true;
    }

    /** SmartLifecycle 只在此返回 true 时才调 {@link #stop()}——忘了置位，停机刷盘会静默不执行。 */
    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void stop() {
        running = false;
        // 先关定时入口：否则停机循环与定时轮次会互相抢 Redis 锁，把停机拖长
        scheduler.stopAccepting();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (scheduler.flushBlocking() == 0) {
                log.info("停机刷盘完成，dirty 已排空");
                return;
            }
            // INTERRUPTED（抢不到锁 / 中途失锁）也走这里继续重试到超时：
            // 另一实例正在刷同一份 dirty，超时后放行退出、残留由对方或下次启动落完
            if (!pause()) {
                break;
            }
        }
        log.error("停机刷盘超时（{}s），dirty 仍有 {} 个 key 未落盘；数据仍在 Redis，下次启动后会接着落",
                timeoutSeconds, dirty == null ? -1 : dirty.backlogSize());
    }

    /** @return false 表示被中断，应立即结束刷盘循环 */
    private boolean pause() {
        try {
            Thread.sleep(PAUSE_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("停机刷盘被中断，放弃剩余轮次");
            return false;
        }
    }
}
```

> `dirty == null ? -1 : …` 是为了让不连 Redis 的单测能构造它（测试传 `null`）；生产路径永远非空。

- [ ] **Step 4: 装配 bean**

`DbServerConfiguration.java` 追加：

```java
    @Bean
    GracefulShutdown gracefulShutdown(FlushScheduler scheduler, DirtyLedger dirty,
                                      DbServerProperties p) {
        return new GracefulShutdown(scheduler, dirty, p.getShutdownTimeoutSeconds());
    }
```

- [ ] **Step 5: 跑测试确认通过**

Run: `./mvnw -pl game-dbserver -am verify`
Expected: PASS（`GracefulShutdownTest` 6 个 + 既有全绿）

- [ ] **Step 6: 补 `DEVELOPMENT.md` 的停服顺序约定**

在「四之二」一节的「多实例」小节之前插入：

```markdown
### 停服顺序（强制）

**先停 `game-web`，再停 `game-dbserver`。**

`game-dbserver` 停机时会循环把 dirty 刷进 Mongo，最多 `FLUSH_SHUTDOWN_TIMEOUT` 秒。若 `game-web`
还在跑，它会持续 `SADD dirty`——刷空一次下一毫秒又有新的，「刷到空」永远达不到，只能等超时。

超时**不丢数据**：残留仍在 dirty，下次启动接着落。但那时数据暂时只在 Redis，只有 AOF `everysec`
兜底，比落进 Mongo 弱。按顺序停即可让停机刷盘真正收敛。
```

- [ ] **Step 7: Commit**

```bash
git add game-dbserver/src/main/java/io/github/brick/dbserver/flush/GracefulShutdown.java \
        game-dbserver/src/main/java/io/github/brick/dbserver/config/DbServerConfiguration.java \
        game-dbserver/src/test/java/io/github/brick/dbserver/flush/GracefulShutdownTest.java \
        DEVELOPMENT.md
git commit -m "feat(dbserver): GracefulShutdown 循环刷到空 + 硬超时

架构 §4.3 说「停服时 dirty 全量刷入」，但 dbserver 单独停机时 game-web
仍在 SADD dirty，「全量」不是可达状态。真实语义是尽力刷 + 有界超时，
配套写进 DEVELOPMENT.md 的运维约定：先停 game-web 再停 dbserver。

用 SmartLifecycle 而非 @PreDestroy：Spring 先按 phase 降序 stop() 再销毁
singleton，取 MAX_VALUE 确保刷盘跑在 Redisson/Mongo 的 destroyMethod 之前，
否则刷盘时连接可能已被关掉。

两轮之间 sleep 200ms：抢不到锁时 flushBlocking 立刻返回，没有它就是
一个烧 20s CPU 的紧循环。"
```

---

## Task 10: 落盘指标

落盘 spec §7.1 的六个指标。**`dirty` 积压量（gauge）是最该看的**——持续增长说明落盘跟不上写入，而其余指标只在事后排障时有用。

**Files:**
- Create: `game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushMetrics.java`
- Modify: `game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushOrchestrator.java`
- Modify: `game-dbserver/src/main/java/io/github/brick/dbserver/config/DbServerConfiguration.java`
- Test: `game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushMetricsIT.java`

**Interfaces:**
- Consumes: `MeterRegistry`（Actuator 自动装配，Task 5 已加依赖）、`DirtyLedger.backlogSize()`（Task 3）
- Produces:
  - `FlushMetrics(MeterRegistry, DirtyLedger)` — 构造时注册 backlog gauge
  - `void recordRound(long nanos, int flushedKeys, int failedKeys, int missingKeys)`
  - `void recordSkippedRound()`
  - `FlushOrchestrator` 构造器新增末位参数 `FlushMetrics metrics`

- [ ] **Step 1: 写失败的测试**

Create `game-dbserver/src/test/java/io/github/brick/dbserver/flush/FlushMetricsIT.java`：

```java
package io.github.brick.dbserver.flush;

import io.github.brick.data.LocalRedisMongo;
import io.github.brick.data.overlay.DirtyLedger;
import io.github.brick.data.store.MongoStore;
import io.github.brick.data.store.RedisStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class FlushMetricsIT extends LocalRedisMongo {

    private MeterRegistry registry;
    private DirtyLedger dirty;

    private FlushOrchestrator orchestrator(int chunkSize) {
        registry = new SimpleMeterRegistry();
        dirty = new DirtyLedger(redis);
        return new FlushOrchestrator(redis, dirty, new RedisStore(redis),
                new MongoStore(mongo, MONGO_DB), chunkSize, 60L,
                new FlushMetrics(registry, dirty));
    }

    @Test
    void backlogGaugeReflectsDirtySize() {
        // 最该看的指标：持续增长说明落盘跟不上写入
        FlushOrchestrator o = orchestrator(500);
        dirty.mark("player:1:profile");
        dirty.mark("player:2:bag");
        assertThat(registry.get("dbserver.dirty.backlog").gauge().value()).isEqualTo(2.0);

        new RedisStore(redis).set("player:1:profile", "{}");
        new RedisStore(redis).set("player:2:bag", "[]");
        o.flushOnce();
        assertThat(registry.get("dbserver.dirty.backlog").gauge().value()).isZero();
    }

    @Test
    void roundRecordsTimerAndFlushedCount() {
        RedisStore r = new RedisStore(redis);
        FlushOrchestrator o = orchestrator(500);
        r.set("player:1:profile", "{}");
        dirty.mark("player:1:profile");

        o.flushOnce();

        assertThat(registry.get("dbserver.flush.round").timer().count()).isEqualTo(1);
        assertThat(registry.get("dbserver.flush.keys").counter().count()).isEqualTo(1.0);
    }

    @Test
    void missingKeysAreCountedSeparatelyFromFlushedOnes() {
        FlushOrchestrator o = orchestrator(500);
        dirty.mark("player:404:bag");          // Redis 里没有

        o.flushOnce();

        assertThat(registry.get("dbserver.flush.missing").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("dbserver.flush.keys").counter().count()).isZero();
    }

    @Test
    void failedKeysAreCounted() {
        db().getCollection("player:profile").createIndex(
                new org.bson.Document("v", 1),
                new com.mongodb.client.model.IndexOptions().unique(true));
        FlushOrchestrator o = orchestrator(500);
        new MongoStore(mongo, MONGO_DB).upsert("player:1:profile", "{\"dup\":1}");
        RedisStore r = new RedisStore(redis);
        r.set("player:2:profile", "{\"dup\":1}");
        dirty.mark("player:2:profile");

        o.flushOnce();

        assertThat(registry.get("dbserver.flush.failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    void skippedRoundIsCountedWhenAnotherInstanceHoldsTheLock() {
        FlushOrchestrator o = orchestrator(500);
        RedissonClient other = newClient();
        try {
            RLock held = other.getLock(FlushOrchestrator.FLUSH_LOCK);
            assertThat(held.tryLock(0, 60, TimeUnit.SECONDS)).isTrue();
            try {
                o.flushOnce();
                assertThat(registry.get("dbserver.flush.skipped").counter().count()).isEqualTo(1.0);
                assertThat(registry.get("dbserver.flush.round").timer().count()).isZero();
            } finally {
                held.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            other.shutdown();
        }
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./mvnw -pl game-dbserver -am verify -Dit.test=FlushMetricsIT`
Expected: 编译失败，`cannot find symbol: class FlushMetrics`

- [ ] **Step 3: 实现 `FlushMetrics`**

Create `game-dbserver/src/main/java/io/github/brick/dbserver/flush/FlushMetrics.java`：

```java
package io.github.brick.dbserver.flush;

import io.github.brick.data.overlay.DirtyLedger;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * 落盘指标（落盘 spec §7.1）。
 *
 * <p><b>{@code dbserver.dirty.backlog} 是最该配告警的那个</b>：它持续增长说明落盘跟不上写入，
 * 数据在 Redis 里越积越多、只有 AOF everysec 兜底。其余指标基本只在事后排障时有用。
 *
 * <p>backlog 是 gauge，每次抓取都会打一次 {@code SCARD}——落盘进程本身就在高频访问 Redis，
 * 这点额外开销可忽略。
 */
public class FlushMetrics {

    private final Timer round;
    private final Counter flushedKeys;
    private final Counter failedKeys;
    private final Counter missingKeys;
    private final Counter skippedRounds;

    public FlushMetrics(MeterRegistry registry, DirtyLedger dirty) {
        this.round = Timer.builder("dbserver.flush.round")
                .description("单轮落盘耗时——逼近 flush 周期说明该调大周期或分片")
                .register(registry);
        this.flushedKeys = Counter.builder("dbserver.flush.keys")
                .description("成功落盘的 key 数")
                .register(registry);
        this.failedKeys = Counter.builder("dbserver.flush.failed")
                .description("落盘失败并回写 dirty 的 key 数——持续非零即毒丸 key 告警")
                .register(registry);
        this.missingKeys = Counter.builder("dbserver.flush.missing")
                .description("标记尚存但 Redis 已无该 key 的数量")
                .register(registry);
        this.skippedRounds = Counter.builder("dbserver.flush.skipped")
                .description("抢不到落盘锁而跳过的轮次——热备实例上属正常，主实例上持续非零才异常")
                .register(registry);
        Gauge.builder("dbserver.dirty.backlog", dirty, DirtyLedger::backlogSize)
                .description("dirty 积压量——持续增长说明落盘跟不上写入")
                .register(registry);
    }

    public void recordRound(long nanos, int flushed, int failed, int missing) {
        round.record(nanos, TimeUnit.NANOSECONDS);
        if (flushed > 0) {
            flushedKeys.increment(flushed);
        }
        if (failed > 0) {
            failedKeys.increment(failed);
        }
        if (missing > 0) {
            missingKeys.increment(missing);
        }
    }

    public void recordSkippedRound() {
        skippedRounds.increment();
    }
}
```

- [ ] **Step 4: 让 `FlushOrchestrator` 上报**

`FlushOrchestrator.java`——构造器末位加 `FlushMetrics metrics` 并存字段。`flushOnce()` 的两处分支加上报：

抢不到锁的分支（`if (!acquired)` 内，`log.debug` 之后）：

```java
            metrics.recordSkippedRound();
```

`drainAndFlush` 改为累计计数并在返回前上报。把 `flushChunk` 的签名改成返回本片统计，新增一个小载体：

```java
    /** 单片落盘统计，仅用于指标累计。 */
    private record ChunkStats(int flushed, int failed, int missing) {}
```

`flushChunk` 末尾改为返回统计（其余逻辑不变）：

```java
    protected ChunkStats flushChunk(List<String> chunk) {
        Map<String, String> values = redis.mget(chunk);

        int missing = chunk.size() - values.size();
        if (missing > 0) {
            log.warn("本片 {} 个 key 在 Redis 已不存在（标记残留），跳过落盘且不回写 dirty", missing);
        }

        Set<String> failed = mongo.bulkUpsert(values);
        if (!failed.isEmpty()) {
            log.warn("落盘失败 {} 个 key，回写 dirty 等下轮重试: {}", failed.size(), failed);
            dirty.markAll(failed);
        }
        dirty.ackInflight(chunk);
        return new ChunkStats(values.size() - failed.size(), failed.size(), missing);
    }
```

`drainAndFlush` 改为：

```java
    private int drainAndFlush(RLock lock) {
        long startNanos = System.nanoTime();
        int flushed = 0, failed = 0, missing = 0;
        Set<String> keys = dirty.drainToInflight();
        if (keys.isEmpty()) {
            metrics.recordRound(System.nanoTime() - startNanos, 0, 0, 0);
            return 0;
        }
        for (List<String> chunk : chunks(keys, chunkSize)) {
            ChunkStats s = flushChunk(chunk);
            flushed += s.flushed();
            failed += s.failed();
            missing += s.missing();
            if (!stillHoldsLock(lock)) {
                log.error("落盘中途失锁（租约 {}s 到期），中断本轮；剩余 {} 个 key 留在 in-flight 待下轮恢复",
                        lockLeaseSeconds, dirty.inflightMembers().size());
                metrics.recordRound(System.nanoTime() - startNanos, flushed, failed, missing);
                return INTERRUPTED;
            }
        }
        metrics.recordRound(System.nanoTime() - startNanos, flushed, failed, missing);
        return keys.size();
    }
```

- [ ] **Step 5: 装配 bean 并更新既有构造调用**

`DbServerConfiguration.java` 加 bean（import `io.micrometer.core.instrument.MeterRegistry`）：

```java
    @Bean
    FlushMetrics flushMetrics(MeterRegistry registry, DirtyLedger dirty) {
        return new FlushMetrics(registry, dirty);
    }
```

并给 `flushOrchestrator` 加参数：

```java
    @Bean
    FlushOrchestrator flushOrchestrator(RedissonClient redisson, DirtyLedger dirty,
                                        RedisStore redis, MongoStore mongo,
                                        DbServerProperties p, FlushMetrics metrics) {
        return new FlushOrchestrator(redisson, dirty, redis, mongo,
                p.getChunkSize(), p.getLockLeaseSeconds(), metrics);
    }
```

`FlushOrchestratorIT`、`FlushLockIT`、`FlushSchedulerTest`、`GracefulShutdownTest` 里所有 `new FlushOrchestrator(...)` 都要补末位参数。测试里用 `new FlushMetrics(new SimpleMeterRegistry(), dirtyLedgerInstance)`；纯单测（`FlushSchedulerTest`/`GracefulShutdownTest`）传 `null` 即可——那些子类覆盖了 `flushOnce()`，永远不会解引用它。

- [ ] **Step 6: 跑测试确认通过**

Run: `./mvnw -pl game-dbserver -am verify`
Expected: PASS（`FlushMetricsIT` 5 个 + 全部既有测试绿）

- [ ] **Step 7: 全量构建**

Run: `./mvnw clean verify`
Expected: PASS——四个模块全绿。这是本计划的最终验收：`game-data` 的三处原语改动 + `game-dbserver` 的落盘编排全部落地且有测试覆盖。

- [ ] **Step 8: Commit**

```bash
git add game-dbserver/src/main/java/io/github/brick/dbserver/ \
        game-dbserver/src/test/java/io/github/brick/dbserver/
git commit -m "feat(dbserver): 落盘指标（Micrometer）

六个指标里 dbserver.dirty.backlog 是最该配告警的：持续增长说明落盘
跟不上写入，数据在 Redis 越积越多、只有 AOF everysec 兜底。

dbserver.flush.skipped 在热备实例上持续增长属正常，主实例上非零才异常
——两种实例读同一个指标，含义相反，故 description 里写明。"
```

- [ ] **Step 9: 更新 spec 状态与总纲**

`.superpowers/specs/00-architecture-overview.md`：
- §1 文档地图里 `2026-09-04-dbserver-flush-design.md` 的状态列从「⏳ 待实现」改为「✅ 已实现并合并（plan: `2026-09-04-dbserver-flush.md`）」
- §7 里程碑表「Plan C · 落盘编排」的状态从「📝 已设计、待实现」改为「✅ 已实现并合并」
- §1 表末的 plans 说明行补上新计划文件名

```bash
git add .superpowers/specs/00-architecture-overview.md
git commit -m "docs: 落盘编排（Plan C）状态更新为已实现"
```

---

## 附录：跑测试的两个前提

1. **必须带 `-am`**：`game-dbserver` 的 IT 依赖 `game-data` 的 test-jar，单独 `-pl game-dbserver` 时 reactor 里没有那个制品。

```bash
./mvnw -pl game-dbserver -am verify
```

2. **Redis 与 Mongo 要预起**，且连接参数取自 `game-data/src/test/resources/it-config.yaml`（Task 1 改名而来，不入库，需从 `.example` 复制）。IT 会 `flushdb` 当前 Redis 库并 drop `game_test` 库——**别指向有真实数据的实例**。

