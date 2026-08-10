# 多模块 Maven 骨架实施计划（Plan A）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把当前单模块 `game` 工程重构为 5 模块 Maven reactor（game-parent / game-contract / game-data / game-web / game-dbserver），完成版本钉版与依赖声明，并让 game-contract 的 proto→Java 流水线跑通。

**Architecture:** 根 POM `game-parent` 继承 `spring-boot-starter-parent:4.1.0`、`packaging=pom`，集中做 BOM 钉版（Netty BOM 4.1.136.Final、Redisson 4.6.1、MongoDB Driver 5.9.0、Protobuf 4.34.1）与公共插件管理（lombok 注解处理器、protobuf-maven-plugin）。4 个子模块按 spec 依赖图单向依赖：`game-web → game-data + game-contract`、`game-dbserver → game-data`（唯一）。本计划只搭骨架 + 版本钉版 + contract proto 流水线；数据原语（RedisStore/LockManager/OverlayWriter 等）与 web 横切 / dbserver 落盘编排分别由后续 Plan B、Plan C 实现，本计划不展开。本计划交付后：`./mvnw verify` 全 reactor 绿；game-contract 生成 proto 类且测试可用；game-web / game-dbserver 空上下文可启动（不依赖外部 Redis/Mongo）。

**Tech Stack:** JDK 25，Spring Boot 4.1.0，Maven 3.9+，Protobuf 4.34.1，Redisson 4.6.1，MongoDB Driver 5.9.0，Netty 4.1.136.Final，Lombok，JUnit Jupiter。

## Global Constraints

- JDK 25（`java.version=25`，`spring-boot-starter-parent` 已含 JEP 491）。
- Spring Boot 4.1.0（由 `spring-boot-starter-parent` 继承，不在子模块重复声明版本）。
- Netty 统一由根 POM `netty-bom:4.1.136.Final` 钉版，子模块禁止单独声明 Netty 版本（架构 spec §3.1）。
- Redisson 4.6.1（`redisson-spring-boot-starter`，非 Spring Boot 管理项，根 POM 显式钉版）；Spring Boot 4.1 用 `RedissonAutoConfigurationV4`。
- MongoDB Driver 5.9.0（`mongodb-driver-sync`，纯 Sync 驱动，非 Spring Data Mongo），根 POM 钉版。
- Protobuf 4.34.1（通过 `${protobuf-java.version}` 属性钉版，ascopes `protobuf-maven-plugin` 由 `spring-boot-starter-parent` 管理版本 5.1.4，默认读 `src/main/proto`、protoc 版本跟随属性）。
- 禁用 Reactive / 响应式；统一同步命令式（本计划暂无业务代码，仅约束后续）。
- 依赖方向严格单向、禁环：`game-web → game-data + game-contract`、`game-dbserver → game-data`、`game-data / game-contract` 不依赖业务模块。
- 所有 Maven 命令用项目自带 wrapper `./mvnw`（Git Bash 可执行），在仓库根目录运行。

---

## File Structure

重构后目录（本计划产物）：

```
x-server/
  pom.xml                         ← game-parent（根 POM，packaging=pom，BOM 钉版）
  mvnw / mvnw.cmd / .mvn/          ← 保留不动
  .gitignore                       ← 保留不动
  game-contract/
    pom.xml                        ← proto 模块，jar，protobuf-maven-plugin 生成 wire 消息
    src/main/proto/login.proto     ← 菜鸟期登录 wire 契约（示例 + 后续复用）
    src/test/java/io/github/brick/contract/game/LoginContractTest.java
  game-data/
    pom.xml                        ← 数据原语库，jar，依赖 redisson-starter + mongodb-driver-sync
    src/main/java/io/github/brick/data/store/DataKeys.java   ← key 命名常量种子（后续 Plan B 扩展）
    src/test/java/io/github/brick/data/store/DataKeysTest.java
  game-web/
    pom.xml                        ← 业务进程，boot 可执行 jar，依赖 game-data + game-contract + webmvc
    src/main/java/io/github/brick/web/GameApplication.java    ← 自旧 root src 迁入并改包名
    src/main/resources/application.yaml
    src/test/java/io/github/brick/web/GameApplicationTest.java
  game-dbserver/
    pom.xml                        ← 落盘进程，boot 可执行 jar，依赖 game-data（唯一）
    src/main/java/io/github/brick/dbserver/DbServerApplication.java
    src/main/resources/application.yaml
    src/test/java/io/github/brick/dbserver/DbServerApplicationTest.java
```

删除：旧根 `src/`（迁移完成后在 Task 6 删除）。

责任划分：每个模块一个清晰职责；根 POM 只管版本与插件管理，不产出制品；contract 只放 wire 协议；data 只放数据原语与覆盖写模板（本计划仅种子）；web/dbserver 是两个可独立打包的进程。

---

## Task 1: 把根 POM 改造为 game-parent（BOM 钉版，暂不列子模块）

**Files:**
- Modify: `pom.xml`（仓库根，原 `game` 单模块 POM）

**Interfaces:**
- Consumes: `spring-boot-starter-parent:4.1.0`（外部 parent）。
- Produces: `io.github.brick:game-parent:0.0.1-SNAPSHOT`（packaging=pom），供 4 个子模块继承；版本属性 `${redisson.version}` / `${netty.version}` / `${protobuf-java.version}` / `${mongodb.version}`；`dependencyManagement` 中钉版 netty-bom、redisson-spring-boot-starter、mongodb-driver-sync、protobuf-java、及内部模块 game-contract / game-data；`pluginManagement` 中补 lombok 注解处理器路径。本任务**不**写 `<modules>`（子模块未建，加了会报 missing reactor）。

- [ ] **Step 1: 用 game-parent 内容覆盖根 `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
		xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
		xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>

	<parent>
		<groupId>org.springframework.boot</groupId>
		<artifactId>spring-boot-starter-parent</artifactId>
		<version>4.1.0</version>
		<relativePath/>
	</parent>

	<groupId>io.github.brick</groupId>
	<artifactId>game-parent</artifactId>
	<version>0.0.1-SNAPSHOT</version>
	<packaging>pom</packaging>
	<name>x-server parent</name>

	<properties>
		<java.version>25</java.version>
		<redisson.version>4.6.1</redisson.version>
		<netty.version>4.1.136.Final</netty.version>
		<protobuf-java.version>4.34.1</protobuf-java.version>
		<mongodb.version>5.9.0</mongodb.version>
	</properties>

	<dependencyManagement>
		<dependencies>
			<!-- Netty 统一 BOM 钉版（spec §3.1）：覆盖被 Redisson/gRPC/Spring 传递引入的 Netty -->
			<dependency>
				<groupId>io.netty</groupId>
				<artifactId>netty-bom</artifactId>
				<version>${netty.version}</version>
				<type>pom</type>
				<scope>import</scope>
			</dependency>
			<!-- Redisson（非 Spring Boot 管理项，显式钉版） -->
			<dependency>
				<groupId>org.redisson</groupId>
				<artifactId>redisson-spring-boot-starter</artifactId>
				<version>${redisson.version}</version>
			</dependency>
			<!-- MongoDB Driver（纯 Sync 驱动，非 Spring Data Mongo） -->
			<dependency>
				<groupId>org.mongodb</groupId>
				<artifactId>mongodb-driver-sync</artifactId>
				<version>${mongodb.version}</version>
			</dependency>
			<!-- Protobuf runtime（与 protoc 对齐） -->
			<dependency>
				<groupId>com.google.protobuf</groupId>
				<artifactId>protobuf-java</artifactId>
				<version>${protobuf-java.version}</version>
			</dependency>
			<!-- 内部模块互相引用 -->
			<dependency>
				<groupId>io.github.brick</groupId>
				<artifactId>game-contract</artifactId>
				<version>${project.version}</version>
			</dependency>
			<dependency>
				<groupId>io.github.brick</groupId>
				<artifactId>game-data</artifactId>
				<version>${project.version}</version>
			</dependency>
		</dependencies>
	</dependencyManagement>

	<build>
		<pluginManagement>
			<plugins>
				<!-- lombok 注解处理器（版本由 Spring Boot 管理的 lombok 隐式解析） -->
				<plugin>
					<groupId>org.apache.maven.plugins</groupId>
					<artifactId>maven-compiler-plugin</artifactId>
					<configuration>
						<annotationProcessorPaths>
							<path>
								<groupId>org.projectlombok</groupId>
								<artifactId>lombok</artifactId>
							</path>
						</annotationProcessorPaths>
					</configuration>
				</plugin>
			</plugins>
		</pluginManagement>
	</build>
</project>
```

- [ ] **Step 2: 校验根 POM 可被 Maven 解析（非递归，子模块未建）**

Run: `./mvnw -N validate`
Expected: BUILD SUCCESS。若报错 `Non-resolvable parent` 检查 `spring-boot-starter-parent:4.1.0` 是否可达（需联网拉取首次依赖）。

- [ ] **Step 3: 提交**

```bash
git add pom.xml
git commit -m "build: 根 POM 改造为 game-parent（packaging=pom + BOM 钉版 + 插件管理）"
```

---

## Task 2: 建 game-contract 模块（proto→Java 流水线 + 测试）

**Files:**
- Create: `game-contract/pom.xml`
- Create: `game-contract/src/main/proto/login.proto`
- Create: `game-contract/src/test/java/io/github/brick/contract/game/LoginContractTest.java`
- Modify: `pom.xml`（根，加 `<modules>`）

**Interfaces:**
- Consumes: `game-parent`（继承）；`protobuf-maven-plugin`（ascopes，版本由 Spring Boot 管理）；`com.google.protobuf:protobuf-java`（根 POM 钉 4.34.1）；`org.junit.jupiter:junit-jupiter`（Spring Boot 管理版本，test scope）。
- Produces: `io.github.brick:game-contract:0.0.1-SNAPSHOT`（jar），生成类 `io.github.brick.contract.game.LoginRequest` / `LoginResponse`，供 game-web 依赖、`LoginContractTest` 断言。

- [ ] **Step 1: 写 game-contract/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
		xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
		xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>

	<parent>
		<groupId>io.github.brick</groupId>
		<artifactId>game-parent</artifactId>
		<version>0.0.1-SNAPSHOT</version>
		<relativePath>../pom.xml</relativePath>
	</parent>

	<artifactId>game-contract</artifactId>
	<name>game-contract</name>
	<description>proto wire 消息 + gRPC 契约（预留）</description>

	<dependencies>
		<dependency>
			<groupId>com.google.protobuf</groupId>
			<artifactId>protobuf-java</artifactId>
		</dependency>
		<dependency>
			<groupId>org.junit.jupiter</groupId>
			<artifactId>junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>

	<build>
		<plugins>
			<plugin>
				<groupId>io.github.ascopes</groupId>
				<artifactId>protobuf-maven-plugin</artifactId>
			</plugin>
		</plugins>
	</build>
</project>
```

- [ ] **Step 2: 写 proto 契约 `game-contract/src/main/proto/login.proto`**

```proto
syntax = "proto3";

package brick.game.contract;

option java_package = "io.github.brick.contract.game";
option java_multiple_files = true;

// 菜鸟期登录 wire 契约：客户端带 token，服务端回 ok + userId。
message LoginRequest {
  string token = 1;
}

message LoginResponse {
  bool ok = 1;
  int64 user_id = 2;
}
```

- [ ] **Step 3: 写失败测试 `game-contract/src/test/java/io/github/brick/contract/game/LoginContractTest.java`**

```java
package io.github.brick.contract.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginContractTest {

	@Test
	void loginRequestGeneratedAndRoundTrips() {
		LoginRequest req = LoginRequest.newBuilder().setToken("abc").build();
		assertEquals("abc", req.getToken());
	}

	@Test
	void loginResponseGeneratedAndRoundTrips() {
		LoginResponse resp = LoginResponse.newBuilder().setOk(true).setUserId(42L).build();
		assertTrue(resp.getOk());
		assertEquals(42L, resp.getUserId());
	}
}
```

- [ ] **Step 4: 在根 pom.xml 的 `</properties>` 之后、`<dependencyManagement>` 之前插入 `<modules>`**

把根 `pom.xml` 中 `</properties>` 行之后插入：

```xml
	<modules>
		<module>game-contract</module>
	</modules>
```

- [ ] **Step 5: 运行测试，确认失败（proto 尚未生成）**

Run: `./mvnw -pl game-contract -am test`
Expected: 编译阶段 `LoginContractTest` 报 `cannot find symbol: class LoginRequest`（proto 生成产物尚未参与 test-compile，或插件 goal 未绑定）。记录报错，下一步确认插件执行。

> 说明：ascopes `protobuf-maven-plugin` 默认绑定 `generate-sources` 阶段，生成的源码加入 `target/generated-sources/protobuf/java` 并参与主编译；test-compile 默认可见主编译产物。若 Step 5 报 `cannot find symbol`，先检查 `./mvnw -pl game-contract generate-sources` 后 `target/generated-sources` 是否有 `LoginRequest.java`，无则说明插件未执行——确认 `protobuf-maven-plugin` 在 `<plugins>`（非 pluginManagement）中已声明（Step 1 已声明）。

- [ ] **Step 6: 先单独跑生成，确认 proto 流水线产出源码**

Run: `./mvnw -pl game-contract -am generate-sources`
Expected: BUILD SUCCESS，且 `game-contract/target/generated-sources/protobuf/java/io/github/brick/contract/game/LoginRequest.java` 存在。

校验文件存在（Git Bash）：
```bash
ls game-contract/target/generated-sources/protobuf/java/io/github/brick/contract/game/
```
Expected: 输出含 `LoginRequest.java`、`LoginResponse.java`。

- [ ] **Step 7: 运行测试，确认通过**

Run: `./mvnw -pl game-contract -am test`
Expected: BUILD SUCCESS，`LoginContractTest` 2 个测试 PASS。

- [ ] **Step 8: 提交**

```bash
git add pom.xml game-contract/pom.xml game-contract/src
git commit -m "feat(contract): 建 game-contract 模块，proto→Java 流水线跑通（login 契约）"
```

---

## Task 3: 建 game-data 模块（依赖钉版验证 + DataKeys 种子）

**Files:**
- Create: `game-data/pom.xml`
- Create: `game-data/src/main/java/io/github/brick/data/store/DataKeys.java`
- Create: `game-data/src/test/java/io/github/brick/data/store/DataKeysTest.java`
- Modify: `pom.xml`（根，`<modules>` 加 `game-data`）

**Interfaces:**
- Consumes: `game-parent`；`spring-boot-starter`（核心上下文，供 Plan B 定义 @Configuration）；`redisson-spring-boot-starter:4.6.1`；`mongodb-driver-sync:5.9.0`；`lombok`（optional）；`junit-jupiter`（test）。
- Produces: `io.github.brick:game-data:0.0.1-SNAPSHOT`（jar）；`io.github.brick.data.store.DataKeys`（key 命名常量 + entity→锁名映射，`lock:{entity}:{id}` / `player:{id}:profile` / `player:{id}:bag`）。后续 Plan B 的 RedisStore/MongoStore/LockManager 将消费并扩展此类。

- [ ] **Step 1: 写 game-data/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
		xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
		xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>

	<parent>
		<groupId>io.github.brick</groupId>
		<artifactId>game-parent</artifactId>
		<version>0.0.1-SNAPSHOT</version>
		<relativePath>../pom.xml</relativePath>
	</parent>

	<artifactId>game-data</artifactId>
	<name>game-data</name>
	<description>数据原语：Redis/Mongo 封装 + 连接池 + 锁 + 覆盖写模板 + JsonCodec</description>

	<dependencies>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter</artifactId>
		</dependency>
		<dependency>
			<groupId>org.redisson</groupId>
			<artifactId>redisson-spring-boot-starter</artifactId>
		</dependency>
		<dependency>
			<groupId>org.mongodb</groupId>
			<artifactId>mongodb-driver-sync</artifactId>
		</dependency>
		<dependency>
			<groupId>org.projectlombok</groupId>
			<artifactId>lombok</artifactId>
			<optional>true</optional>
		</dependency>
		<dependency>
			<groupId>org.junit.jupiter</groupId>
			<artifactId>junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>
</project>
```

- [ ] **Step 2: 写失败测试 `game-data/src/test/java/io/github/brick/data/store/DataKeysTest.java`**

```java
package io.github.brick.data.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DataKeysTest {

	@Test
	void playerProfileKey() {
		assertEquals("player:7:profile", DataKeys.playerProfile(7));
	}

	@Test
	void playerBagKey() {
		assertEquals("player:7:bag", DataKeys.playerBag(7));
	}

	@Test
	void lockKeyFollowsEntityIdFormat() {
		assertEquals("lock:player:7", DataKeys.lockKey("player", 7));
		assertEquals("lock:guild:3", DataKeys.lockKey("guild", 3));
	}
}
```

- [ ] **Step 3: 运行测试，确认失败（DataKeys 未建）**

Run: `./mvnw -pl game-data -am test -DfailIfNoTests=false`
Expected: 编译报 `cannot find symbol: class DataKeys`。

- [ ] **Step 4: 写最小实现 `game-data/src/main/java/io/github/brick/data/store/DataKeys.java`**

```java
package io.github.brick.data.store;

/**
 * 数据 key 命名常量 + entity→锁名映射。
 * 锁名格式遵循架构 spec §3.1：{@code lock:{entity}:{id}}。
 * 本类为骨架种子，后续 Plan B 的 RedisStore/MongoStore/LockManager 将消费并按需扩展。
 */
public final class DataKeys {

	private DataKeys() {
	}

	/** 玩家主档 key：{@code player:{id}:profile}。 */
	public static String playerProfile(long playerId) {
		return "player:" + playerId + ":profile";
	}

	/** 玩家背包 key：{@code player:{id}:bag}。 */
	public static String playerBag(long playerId) {
		return "player:" + playerId + ":bag";
	}

	/** 分布式锁名：{@code lock:{entity}:{id}}（架构 spec §3.1）。 */
	public static String lockKey(String entity, long id) {
		return "lock:" + entity + ":" + id;
	}
}
```

- [ ] **Step 5: 在根 pom.xml 的 `<modules>` 追加 game-data**

把根 `pom.xml` 的 `<modules>` 改为：

```xml
	<modules>
		<module>game-contract</module>
		<module>game-data</module>
	</modules>
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `./mvnw -pl game-data -am test`
Expected: BUILD SUCCESS，`DataKeysTest` 3 个测试 PASS。

- [ ] **Step 7: 验证依赖版本钉版命中 spec**

Run: `./mvnw -pl game-data dependency:tree`
Expected: 输出含
- `org.redisson:redisson-spring-boot-starter:jar:4.6.1`
- `org.mongodb:mongodb-driver-sync:jar:5.9.0`
- `io.netty:*:jar:4.1.136.Final`（任一 netty 传递依赖，如 `netty-transport`，版本须为 4.1.136.Final）

若 netty 传递依赖版本不是 4.1.136.Final，回 Task 1 确认 `netty-bom` import 已在 `dependencyManagement` 顶部（BOM import 顺序不影响最终解析，但须确认 `<scope>import</scope>` 与 `<type>pom</type>` 正确）。

- [ ] **Step 8: 提交**

```bash
git add pom.xml game-data/pom.xml game-data/src
git commit -m "feat(data): 建 game-data 模块 + DataKeys 种子，验证 Redisson/Mongo/Netty 版本钉版"
```

---

## Task 4: 把 Spring Boot 应用迁入 game-web 模块

**Files:**
- Create: `game-web/pom.xml`
- Create: `game-web/src/main/java/io/github/brick/web/GameApplication.java`（自旧 `src/main/java/io/github/brick/game/GameApplication.java` 迁入，改包名 `...game` → `...web`）
- Create: `game-web/src/main/resources/application.yaml`（自旧 `src/main/resources/application.yaml` 迁入，改 application.name）
- Create: `game-web/src/test/java/io/github/brick/web/GameApplicationTest.java`
- Modify: `pom.xml`（根，`<modules>` 加 `game-web`）

**Interfaces:**
- Consumes: `game-parent`；`game-data`（传递带入 redisson-spring-boot-starter）；`game-contract`；`spring-boot-starter-webmvc`；`lombok`（optional）；`spring-boot-starter-test`（test）。
- Produces: `io.github.brick:game-web:0.0.1-SNAPSHOT`（可执行 jar）；`io.github.brick.web.GameApplication`（Spring Boot 入口）。

> 注意：game-data 传递带入的 `redisson-spring-boot-starter` 会触发 `RedissonAutoConfigurationV4`，启动时尝试连接 Redis。本计划骨架测试不依赖外部 Redis，故测试用 `spring.autoconfigure.exclude` 排除该自动配置（Plan B/C 的集成测试再连真实 Redis）。

- [ ] **Step 1: 写 game-web/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
		xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
		xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>

	<parent>
		<groupId>io.github.brick</groupId>
		<artifactId>game-parent</artifactId>
		<version>0.0.1-SNAPSHOT</version>
		<relativePath>../pom.xml</relativePath>
	</parent>

	<artifactId>game-web</artifactId>
	<name>game-web</name>
	<description>业务进程（HTTP）：业务域 + proto↔domain 映射 + 鉴权 + 业务日志</description>

	<dependencies>
		<dependency>
			<groupId>io.github.brick</groupId>
			<artifactId>game-data</artifactId>
		</dependency>
		<dependency>
			<groupId>io.github.brick</groupId>
			<artifactId>game-contract</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.projectlombok</groupId>
			<artifactId>lombok</artifactId>
			<optional>true</optional>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-test</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>

	<build>
		<plugins>
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
			</plugin>
		</plugins>
	</build>
</project>
```

- [ ] **Step 2: 写 GameApplication `game-web/src/main/java/io/github/brick/web/GameApplication.java`**

```java
package io.github.brick.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GameApplication {

	public static void main(String[] args) {
		SpringApplication.run(GameApplication.class, args);
	}
}
```

- [ ] **Step 3: 写 `game-web/src/main/resources/application.yaml`**

```yaml
spring:
  application:
    name: game-web
```

- [ ] **Step 4: 写失败测试 `game-web/src/test/java/io/github/brick/web/GameApplicationTest.java`**

```java
package io.github.brick.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 排除 Redisson 自动配置，使骨架上下文测试不依赖外部 Redis（Plan B/C 集成测试再连真实 Redis）。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4")
class GameApplicationTest {

	@Test
	void contextLoads() {
	}
}
```

- [ ] **Step 5: 在根 pom.xml 的 `<modules>` 追加 game-web**

```xml
	<modules>
		<module>game-contract</module>
		<module>game-data</module>
		<module>game-web</module>
	</modules>
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `./mvnw -pl game-web -am test`
Expected: BUILD SUCCESS，`GameApplicationTest.contextLoads` PASS。
若失败提示 `RedissonAutoConfigurationV4` 连接 Redis 超时，确认 `@TestPropertySource` 的 exclude 值与 Step 4 一致；若提示「exclude 的类不是自动配置」，执行 `./mvnw -pl game-web dependency:tree` 确认 `redisson-spring-boot-starter:4.6.1` 已在树上，并用 `jar tf` 查其 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 中的实际类名替换 exclude 值。

- [ ] **Step 7: 提交**

```bash
git add pom.xml game-web/pom.xml game-web/src
git commit -m "feat(web): 建 game-web 模块，迁入 GameApplication，骨架上下文测试通过（排除 Redisson 自动配置）"
```

---

## Task 5: 建 game-dbserver 模块（落盘进程骨架）

**Files:**
- Create: `game-dbserver/pom.xml`
- Create: `game-dbserver/src/main/java/io/github/brick/dbserver/DbServerApplication.java`
- Create: `game-dbserver/src/main/resources/application.yaml`
- Create: `game-dbserver/src/test/java/io/github/brick/dbserver/DbServerApplicationTest.java`
- Modify: `pom.xml`（根，`<modules>` 加 `game-dbserver`）

**Interfaces:**
- Consumes: `game-parent`；`game-data`（**唯一**业务依赖，传递带入 redisson-starter + mongodb-driver）；`spring-boot-starter`（非 web，落盘进程无 Tomcat）；`lombok`（optional）；`spring-boot-starter-test`（test）。
- Produces: `io.github.brick:game-dbserver:0.0.1-SNAPSHOT`（可执行 jar）；`io.github.brick.dbserver.DbServerApplication`（落盘进程入口）。依赖规则强制：本模块 `pom.xml` 的 `<dependencies>` 中**不得**出现 game-contract、game-web、spring-boot-starter-webmvc（spec §4）。

> 落盘进程无 HTTP，故不引 webmvc；Spring Boot 检测 classpath 无 web 相关 starter 时默认 `WebApplicationType.NONE`，正合落盘进程形态。骨架测试同样排除 `RedissonAutoConfigurationV4` 以免依赖外部 Redis。

- [ ] **Step 1: 写 game-dbserver/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
		xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
		xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>

	<parent>
		<groupId>io.github.brick</groupId>
		<artifactId>game-parent</artifactId>
		<version>0.0.1-SNAPSHOT</version>
		<relativePath>../pom.xml</relativePath>
	</parent>

	<artifactId>game-dbserver</artifactId>
	<name>game-dbserver</name>
	<description>落盘进程：dirty 扫描 + 定时落 Mongo + 优雅停机刷盘</description>

	<dependencies>
		<!-- 唯一业务依赖：仅 game-data（spec §4 依赖规则） -->
		<dependency>
			<groupId>io.github.brick</groupId>
			<artifactId>game-data</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter</artifactId>
		</dependency>
		<dependency>
			<groupId>org.projectlombok</groupId>
			<artifactId>lombok</artifactId>
			<optional>true</optional>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-test</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>

	<build>
		<plugins>
			<plugin>
				<groupId>org.springframework.boot</groupId>
				<artifactId>spring-boot-maven-plugin</artifactId>
			</plugin>
		</plugins>
	</build>
</project>
```

- [ ] **Step 2: 写 DbServerApplication `game-dbserver/src/main/java/io/github/brick/dbserver/DbServerApplication.java`**

```java
package io.github.brick.dbserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 落盘进程入口。无 web（无 Tomcat），Spring Boot 默认 NONE web 类型。
 * 落盘编排（FlushOrchestrator/FlushScheduler/GracefulShutdown）由后续 Plan C 实现。
 */
@SpringBootApplication
public class DbServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(DbServerApplication.class, args);
	}
}
```

- [ ] **Step 3: 写 `game-dbserver/src/main/resources/application.yaml`**

```yaml
spring:
  application:
    name: game-dbserver
  main:
    web-application-type: none
```

- [ ] **Step 4: 写失败测试 `game-dbserver/src/test/java/io/github/brick/dbserver/DbServerApplicationTest.java`**

```java
package io.github.brick.dbserver;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 排除 Redisson 自动配置，骨架上下文测试不依赖外部 Redis。
 */
@SpringBootTest
@TestPropertySource(properties = "spring.autoconfigure.exclude=org.redisson.spring.starter.RedissonAutoConfigurationV4")
class DbServerApplicationTest {

	@Test
	void contextLoads() {
	}
}
```

- [ ] **Step 5: 在根 pom.xml 的 `<modules>` 追加 game-dbserver（至此 4 模块齐全）**

```xml
	<modules>
		<module>game-contract</module>
		<module>game-data</module>
		<module>game-web</module>
		<module>game-dbserver</module>
	</modules>
```

- [ ] **Step 6: 运行测试，确认通过**

Run: `./mvnw -pl game-dbserver -am test`
Expected: BUILD SUCCESS，`DbServerApplicationTest.contextLoads` PASS。

- [ ] **Step 7: 提交**

```bash
git add pom.xml game-dbserver/pom.xml game-dbserver/src
git commit -m "feat(dbserver): 建 game-dbserver 模块（落盘进程骨架，唯一依赖 game-data）"
```

---

## Task 6: 删除旧根 src/，全 reactor 验证收尾

**Files:**
- Delete: `src/`（旧单模块根源码，内容已迁入 game-web）

**Interfaces:**
- Consumes: Task 1–5 产物。
- Produces: 干净的 5 模块 reactor，`./mvnw verify` 全绿，无残留 `game` 单模块痕迹。

- [ ] **Step 1: 确认旧根 src/ 内容已全部迁出**

```bash
git status --short
```
Expected: 旧 `src/main/java/io/github/brick/game/GameApplication.java`、`src/main/resources/application.yaml`、`src/test/java/io/github/brick/game/GameApplicationTests.java` 仍在工作区（尚未删除），且 game-web 内对应文件已存在（Task 4 已建）。核对 GameApplication 逻辑无遗漏（旧文件仅 Spring Boot 空入口，已 1:1 迁入 game-web）。

- [ ] **Step 2: 删除旧根 src/**

```bash
git rm -r src
```

- [ ] **Step 3: 全 reactor 构建 + 测试**

Run: `./mvnw verify`
Expected: BUILD SUCCESS，全 4 模块编译、测试、打包（game-web/game-dbserver 产出可执行 jar；game-contract/game-data 产出普通 jar）均通过。

- [ ] **Step 4: 校验依赖规则未被破坏（dbServer 依赖最瘦）**

Run: `./mvnw -pl game-dbserver dependency:tree`
Expected: 树上**不含** `game-contract`、`game-web`、`spring-boot-starter-webmvc`；含 `game-data`、`redisson-spring-boot-starter:4.6.1`、`mongodb-driver-sync:5.9.0`。

Run: `./mvnw -pl game-web dependency:tree`
Expected: 树上含 `game-data`、`game-contract`、`spring-boot-starter-webmvc`；`game-dbserver` **不出现**（无环依赖）。

- [ ] **Step 5: 校验 Netty 版本全局归一**

Run: `./mvnw dependency:tree | grep "io.netty"`
Expected: 所有 `io.netty:*` 行版本均为 `4.1.136.Final`，无其它版本（BOM 钉版生效）。

- [ ] **Step 6: 提交**

```bash
git commit -m "build: 删除旧单模块 src/，5 模块 reactor 全 verify 通过、依赖规则与 Netty 钉版校验"
```

（注：Step 2 的 `git rm -r src` 已暂存删除，故 Step 6 直接 commit 即可。若 `git status` 显示还有未暂存改动一并 `git add -A` 后提交。）

---

## Self-Review

**1. Spec 覆盖：**
- §1 5 模块 + 依赖图 → Task 1（parent）+ 2/3/4/5（四子模块）+ 6（reactor 验证）覆盖。
- §3.1 game-parent（Netty BOM 钉版、Spring Boot 4.1.0 / Redisson 4.6.1 / MongoDB Driver 5.9.0 / Protobuf 4.34.1、packaging=pom）→ Task 1 properties + dependencyManagement + Task 6 Step 5 校验。
- §3.2 game-contract（proto + 生成类 + gRPC 契约预留）→ Task 2（proto 流水线 + 测试；gRPC 契约菜鸟期不部署，本计划只埋 proto，符合 spec「仅契约」）。
- §3.3 game-data（Redis/Mongo 封装 + 锁 + 覆盖写模板 + JsonCodec；连接池显式设限；禁嵌套跨池获取；POJO 无关模板）→ 本计划为骨架，仅建模块 + DataKeys 种子 + 版本钉版；原语实现属 Plan B，spec 对应条目在 Plan B 覆盖。DataKeys 锁名格式 `lock:{entity}:{id}` 已在 Task 3 验证，对齐 spec §3.1。
- §3.4 game-web（HTTP Controller + Spring MVC + Tomcat 虚拟线程、AuthFilter 依赖 RedisStore、BizLogger 独立、依赖 game-data+game-contract）→ 本计划建模块 + 迁 GameApplication + 依赖声明（game-data+game-contract+webmvc）；AuthFilter/BizLogger 属 Plan C。
- §3.5 game-dbserver（FlushOrchestrator/FlushScheduler/GracefulShutdown、唯一依赖 game-data、零转换、优雅停机、自带 Logback）→ 本计划建模块 + DbServerApplication + 唯一依赖校验（Task 6 Step 4）；落盘编排属 Plan C。
- §4 依赖规则（单向禁环、dbServer 最瘦、game-data 不认识业务实体、鉴权/日志归 web、Netty BOM 钉版、每进程自带日志）→ Task 6 Step 4 校验 dbServer 最瘦与无环；Netty 钉版 Task 6 Step 5；其余规则在本计划骨架中通过 pom 依赖声明体现（game-data 仅声明原语库依赖、不含业务实体类；game-web 承载 webmvc；dbServer 不含 web/contract）。
- §5 留路（facade/game-api、game-infra、业务域内部划分）→ 不在本计划范围，符合 spec「留路说明」。

**2. Placeholder 扫描：** 无 TBD/TODO；每步含具体命令、具体文件内容或具体校验输出预期。

**3. Type/命名一致性：** `DataKeys.playerProfile/playerBag/lockKey` 在 Task 3 定义并在同任务测试断言，Plan B 将消费（计划内已注明）；`GameApplication`（game-web）与 `DbServerApplication`（game-dbserver）命名一致；`RedissonAutoConfigurationV4` 在 Task 4/5 一致使用；内部模块坐标 `io.github.brick:game-*:0.0.1-SNAPSHOT` 在 Task 1 dependencyManagement 与 Task 2–5 引用一致。

---

## 执行交接

Plan A（本计划）落地后，再依次编写并执行：
- **Plan B**：game-data 数据原语（RedisStore / MongoStore / LockManager / CommitLua / DirtyLedger / OverlayWriter / JsonCodec + 连接池配置 + 真实 Redis/Mongo 集成测试）。
- **Plan C**：game-web 横切（AuthFilter / BizLogger）+ game-dbserver 落盘编排（FlushOrchestrator / FlushScheduler / GracefulShutdown）。
