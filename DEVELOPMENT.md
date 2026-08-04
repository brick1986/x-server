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
| 7 | **IntelliJ IDEA** | 2025.2+（Community 可，Ultimate 有 Spring 专项支持） | 可选，但 JDK 25 + Spring Boot 4.1 需较新版本。 |

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
