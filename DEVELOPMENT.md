# x-server 开发环境清单

> 仅服务端开发环境。技术栈版本钉定见 [架构设计](./.superpowers/specs/2026-07-24-server-architecture-design.md) §3.1；本文件是其「装到机器上」的落地清单。当前开发和验证状态见 [PROGRESS.md](./PROGRESS.md)。

协作与文档维护遵循 [AGENTS.md](./AGENTS.md)；CodeGraph、IDEA、编辑、调试和终端工具的使用见 [开发工具使用指南](./docs/development-tools.md)。

## 一、机器上要装的（Windows 原生）

| # | 软件 | 推荐版本 | 用途 / 备注 |
|---|------|---------|-----------|
| 1 | **JDK 25 LTS** | Eclipse Temurin 25 | spec §3.1 钉定 JDK 25（含 JEP 491，`synchronized` 不 pin 虚拟线程）。设 `JAVA_HOME`、加入 `PATH`。 |
| 2 | **Apache Maven** | 3.9.x（≥3.9） | Spring Boot 4.1 要求 Maven 3.9+。设 `MAVEN_HOME`、加入 `PATH`。 |
| 3 | **Memurai Developer** | 最新（Redis 7.x 兼容） | Windows 原生 Redis，免费开发版，Redisson 4.6.1 兼容。自带 `redis-cli`，装为 Windows 服务。 |
| 4 | **MongoDB Community Edition** | 8.0+（Windows MSI） | spec 用 MongoDB Driver 5.9.0（Sync），兼容 Server 3.6+，8.0 没问题。MSI 装为服务。 |
| 5 | **mongosh** | 随 MongoDB 安装包自带 | MongoDB Shell，验证连接用。 |
| 6 | **Git** | 已有 | 仓库已是 git repo，无需再装。 |
| 7 | **IntelliJ IDEA** | 2026.2.0.1（Community 可，Ultimate 有 Spring 专项支持）

### 装完验证

每条对一遍，输出符合预期才算装好：

```bash
java -version          # 期望 25
mvn -v                 # 期望 Apache Maven 3.9.x，Java 25
redis-cli ping         # 期望 PONG
mongosh --eval "db.runCommand({ping:1})"   # 期望 { ok: 1 }
```

## 二、Maven 管的依赖（写 pom.xml，不单独装）

| 依赖 | 钉定版本 | 来源 |
|------|---------|------|
| Spring Boot | 4.1.0 | spec §3.1 |
| Spring Framework（传递） | 7.0.8 | 随 Spring Boot |
| Redisson + Spring Boot Starter | 4.6.1 | spec §3.1 |
| MongoDB Driver（Sync） | 5.9.0 | spec §3.1 |
| Protobuf | 4.34.1 | spec §3.1（HTTP/WS Body 共用） |
| Netty BOM | 4.1.136.Final | spec §3.1（根 POM 钉版，随 WS 落地但 BOM 先埋） |
| Spring gRPC + gRPC-Java | 1.0.3 / 1.82.1 | spec §3.1（仅契约，菜鸟期不部署） |
| 编译目标 | Java 25 | `--release 25` |

## 三、运行时配置（装完顺手设）

- **Redis（Memurai）**：spec §4.4 要求 AOF `everysec`。在 `memurai.conf` 设 `appendonly yes`、`appendfsync everysec`，重启服务。
- **数据 key 自 2026-09-21 起带 `{bNNNN}` 桶前缀**（提交桶化设计）。旧格式 key 会被
  `DataKeys` 拒绝。一次性清库只适用于可丢弃的独立测试 Redis；有需保留数据时，先使用旧版本
  落盘并确认无 dirty/in-flight 残留、完成备份，再安排迁移。冷启动只能回填已落 Mongo 的数据。
- **MongoDB**：默认服务自启，无需特殊配置。
- **环境变量**：`JAVA_HOME` → JDK 25 目录；`PATH` 追加 JDK `bin`、Maven `bin`。

## 四、应用配置（`game.data.*`）

Redis/Mongo 的连接参数由 `game-data` 的 `DataProperties` 声明（`game.data` 前缀），实际值写在**应用**的 `application.yaml` 里——即 `game-web` 与 `game-dbserver` 各一份。`game-data` 是库模块，**不放 `application.yaml`**（库里的同名文件会与应用的在 classpath 上冲突，只有一个生效）。

两份 yaml 的值都是 `${环境变量:兜底值}` 形式：本地开发零配置即可启动，线上只改环境变量、不改代码重新打包。

| 环境变量 | 兜底值 | 说明 |
|---|---|---|
| `REDIS_ADDRESS` | `redis://127.0.0.1:6379` | Redis 地址 |
| `REDIS_PASSWORD` | 空（不鉴权） | **本机 Redis 设了 `requirepass` 就必须设它** |
| `REDIS_USERNAME` | 空 | 仅 Redis 6+ ACL 需要 |
| `REDIS_POOL_SIZE` | 100 | 限定 100~200，越界启动失败 |
| `MONGO_URI` | `mongodb://localhost:27018` | Mongo 连接串 |
| `MONGO_DB` | `game` | 库名 |
| `MONGO_POOL_SIZE` | 50（dbserver 100） | 限定 50~100，越界启动失败 |
| `LOCK_WAIT_MILLIS` | 2000 | 拿锁 fail-fast 等待上限（仅 game-web） |
| `LOCK_LEASE_SECONDS` | 10 | 固定租约、无看门狗（仅 game-web） |

## 四之二、落盘配置（`game.dbserver.*`，仅 game-dbserver）

由 `game-dbserver` 自己的 `DbServerProperties` 声明——落盘节奏是该进程的策略，不放进被两个
进程共用的 `game-data`。范围由 `@Min`/`@Max` 在启动期强制，越界值使启动失败。

| 环境变量 | 兜底值 | 范围 | 说明 |
|---|---|---|---|
| `FLUSH_INTERVAL_MILLIS` | 2000 | 500~60000 | 落盘轮次间隔（架构 §4.3 的 1~3s） |
| `FLUSH_CHUNK_SIZE` | 500 | 1~5000 | 单片 key 数；内存峰值只与它有关，与积压总量无关 |
| `FLUSH_LOCK_LEASE` | 60 | 10~600 | 落盘锁租约（秒），固定、无看门狗 |
| `FLUSH_SHUTDOWN_TIMEOUT` | 20 | 1~120 | 停机刷盘硬超时（秒）；超时不丢数据，残留下次启动接着落 |

> **`spring.data.redis.*` 在本项目静默无效。** `game-data` 自行创建 `RedissonClient`，绕开了 redisson-spring-boot-starter，所以那一族属性配了不报错也不生效。Redis 配置只认 `game.data.*`。
>
> 若本机曾跑过旧版（dirty 集合还不带花括号的时期），旧的 `dirty` 集合已不会被消费，
> 手工清理一次即可：`redis-cli DEL dirty`。生产环境从未跑过 dbserver，无此问题。

### 停服顺序（强制）

**先停 `game-web`，再停 `game-dbserver`。**

`game-dbserver` 停机时会循环把 dirty 刷进 Mongo，最多 `FLUSH_SHUTDOWN_TIMEOUT` 秒。若 `game-web`
还在跑，它会持续 `SADD dirty`——刷空一次下一毫秒又有新的，「刷到空」永远达不到，只能等超时。

超时**不丢数据**：残留仍在 dirty，下次启动接着落。但那时数据暂时只在 Redis，只有 AOF `everysec`
兜底，比落进 Mongo 弱。按顺序停即可让停机刷盘真正收敛。

### 多实例

`game-dbserver` **允许起多个实例**，多出的会作热备空转：每轮落盘前竞争 Redis 锁
`lock:dbserver:flush`，抢不到就跳过本轮（DEBUG 日志，不是故障）。主实例进程硬崩后，
备实例在租约（默认 60s）内自动接管。

正确性由这把锁保证，**不依赖部署纪律**——这与架构 §2 放弃 sticky 路由时立下的原则一致：
写正确性不押在运维正确配置上。

### 指标

`game-dbserver` 是 headless 进程（`web-application-type: none`），落盘指标经 **JMX** 暴露（Spring Boot
默认开启）：`jconsole` 连接进程后在 MBean 树 `io.micrometer.core.instrument.*` 可见，关键的有
`dbserver.dirty.backlog`（dirty 积压量——**最该配告警的指标**，持续增长说明落盘跟不上写入）、
`dbserver.flush.round`（单轮耗时）、`dbserver.flush.failed`（毒丸 key）。

成熟期若需要 HTTP 抓取（Prometheus 等），届时改用独立 management 端口或 push 网关——
`management.endpoints.web.exposure` 在无 web 容器的进程里是死配置，不要配。

## 五、跑测试

首个业务闭环已接入账号同步 Mongo（`accounts`）与玩家档案异步落盘；`counters` 仍同步写入。
HTTP 接口、会话/限流/日志环境变量、Protobuf 命令及验收见[业务闭环指南](./docs/first-business-loop.md)。
构建后分别启动 `game-web/target/game-web-0.0.1-SNAPSHOT.jar` 和
`game-dbserver/target/game-dbserver-0.0.1-SNAPSHOT-exec.jar`；后者普通 jar 仅供测试依赖。
对外服务须 HTTPS。本轮未提供找回密码、多角色或后续玩法。

单元测试不需要外部服务：

```bash
mvn -pl game-data test          # 仅 game-data 单元测试；数量以本次报告为准
```

集成测试（`*IT.java`）连**本地预起**的 Redis/Mongo，不用 Testcontainers。跑之前 Redis、Mongo 都得在跑：

```bash
mvn -pl game-data verify        # 单元 + 集成测试
```

连接参数（地址、密码、端口、库名）读 **`game-data/src/test/resources/it-config.yaml`**。该文件含本机密码，**不入库**（见 `.gitignore`），所以新 clone 的仓库里没有——先从模板复制一份：

```bash
cp game-data/src/test/resources/it-config.yaml.example \
   game-data/src/test/resources/it-config.yaml
```

然后改成你本机的值：

```yaml
game:
  data:
    redis-address: redis://127.0.0.1:6379
    redis-database: 15                # 独立、可丢弃的测试库；禁止 DB0
    allow-test-cleanup: true           # 核实两个目标都可丢弃后才启用；模板默认 false
    redis-password: <你的 Redis 密码>   # 无鉴权则留空
    mongo-uri: mongodb://localhost:27018
    mongo-db: game_test
```

忘了复制的话，IT 会直接告诉你缺哪个文件、该执行什么命令，不用猜。

换端口或改密码只改这一个文件，不必设环境变量，**也不要写进 `DataProperties` 的默认值**——那会进 git，并在部署时忘设环境变量的情况下静默充当线上兜底。

CI 上可用环境变量 `REDIS_TEST_PASSWORD` 覆盖 yaml 里的密码，无需改文件：

```bash
export REDIS_TEST_PASSWORD=xxx && mvn -pl game-data verify
```

集成测试基类是纯 JUnit、不启动 Spring 上下文，所以它自行解析上面那份 yaml，而不是通过 `DataProperties` 绑定。

> 集成测试会 `flushdb` 配置的 Redis 测试库并 drop 配置的 Mongo 测试库。
> 清理前强制检查：显式允许清理、Redis DB1～15、Mongo 库名以 `_test` 结尾、两者均为 loopback。
> 名称校验不代表库内数据可丢弃，启用开关前必须核实。web/dbserver 共用这份测试配置。

全仓回归使用 `mvn verify`（Windows 也可用 `.\mvnw.cmd verify`）。报告分别位于各模块
`target/surefire-reports` 和 `target/failsafe-reports`。检查测试数、失败、错误和跳过数量，
并在 [PROGRESS.md](./PROGRESS.md) 记录执行日期与范围；已有报告不能当作本次重新验证的结果。

