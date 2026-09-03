# x-server 开发环境清单

> 仅服务端开发环境。技术栈版本钉定见 [`docs/superpowers/specs/2026-07-24-server-architecture-design.md`](./docs/superpowers/specs/2026-07-24-server-architecture-design.md) §3.1；本文件是其「装到机器上」的落地清单。

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

> **`spring.data.redis.*` 在本项目静默无效。** `game-data` 自行创建 `RedissonClient`，绕开了 redisson-spring-boot-starter，所以那一族属性配了不报错也不生效。Redis 配置只认 `game.data.*`。

## 五、跑测试

单元测试不需要外部服务：

```bash
mvn -pl game-data test          # 21 个单元测试
```

集成测试（`*IT.java`）连**本地预起**的 Redis/Mongo，不用 Testcontainers。跑之前 Redis、Mongo 都得在跑：

```bash
mvn -pl game-data verify        # 单元 + 集成测试
```

连接参数（地址、密码、端口、库名）读 **`game-data/src/test/resources/application.yaml`**。该文件含本机密码，**不入库**（见 `.gitignore`），所以新 clone 的仓库里没有——先从模板复制一份：

```bash
cp game-data/src/test/resources/application.yaml.example \
   game-data/src/test/resources/application.yaml
```

然后改成你本机的值：

```yaml
game:
  data:
    redis-address: redis://127.0.0.1:6379
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

> 集成测试会 `flushdb` 当前 Redis 库并 drop Mongo 的 `game_test` 库。**别把它指向有真实数据的实例。**

