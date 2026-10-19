# 首个业务闭环实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 状态：2026-10-19 八项任务完成；独立审查发现已修复，181 项测试全部通过。本地分支尚未合并/部署。

**Goal:** 完成账号密码注册、登录创角、鉴权读档/改名、业务日志、自动落盘与缓存冷回填的首个业务闭环，完成后停止扩展。

**Architecture:** Mongo accounts 同步保存凭证与默认角色绑定，玩家档案复用 LockScope/LockCtx → Redis/dirty → dbserver → Mongo。Redis 保存有限期会话及每账号当前会话标记。协议与领域分离，web 承载业务，data/dbserver 不引入业务 POJO。

**Tech Stack:** JDK 25、Spring Boot 4.1.0 MVC、Protobuf 4.34.1、Redisson 4.6.1、Mongo Sync Driver 5.9.0、JUnit/Mockito；沿用根 POM 版本，不添加响应式依赖或外部身份服务。

**Spec:** [已确认设计](../specs/2026-10-16-first-business-loop-design.md)。执行者须同时阅读设计与本计划。

## Global Constraints

- 每账号一个默认角色；accountId 与 uid 分别调用 `next("account")` / `next("player")`，不复用一个 ID。
- 生产依赖保持 web → data/contract、dbserver → data；web → dbserver 仅允许 test scope。
- 玩家 key 使用 `DataKeys.key("player", uid, "profile")`，锁顺序 `account → guild → player`，固定租约 10s，无看门狗。
- HTTP POST、`application/x-protobuf`、Bearer token；请求体上限 16 KiB，包括无 Content-Length 的流。
- 账号名 `[a-z0-9_]{3,32}`，去首尾空格后 Locale.ROOT 小写；密码不裁剪，15～128 Unicode 码点。
- PBKDF2-HMAC-SHA256 600000 次、16 字节随机盐、32 字节结果；256 位随机 token，默认 24 小时固定期限。
- 昵称去首尾空格后 1～24 Unicode 码点，拒绝控制字符和换行；同名改名不产生重复写或日志。
- 先同步追加并 force 业务意图日志，再 ctx.put；日志保留 48 小时，不自动重放；不得记录密码/hash/token/整个档案快照。
- 全局使用 UTC epoch milliseconds 表达持久化/协议时间，注入 `Clock` 便于测试；文档日期按用户 Asia/Shanghai。
- 每任务按失败测试 → 最小实现 → 测试通过推进；提交只包含本任务与同步文档。不得声称旧报告验证了新代码。
- 不启动独立生产服务、不访问真实业务库；集成测试只清理已核实的独立测试库。依赖服务不可用时如实记录，不用 mock 冒充闭环通过。

## Review Focus

1. 同名账号并发注册与账号名大小写归一：唯一身份、允许跳号、不得创建两个账户（Task 2）。
2. session 已写而 current 写失败，以及旧退出与新登录交错：孤立会话不能使用、新 token 不被误删（Task 3）。
3. Unicode 补充字符、控制字符、chunked 超大 body：按码点验证、上限对流式请求有效（Task 2/5/6）。
4. profile 已写而账号初始化标记失败：重试沿用 uid 且不覆盖现有昵称；已初始化档案失踪不得重建（Task 5）。
5. 日志磁盘失败、历史文件清理及线程复用：禁止后续业务写入、只清理自有过期文件、清掉请求身份（Task 4/5/6）。

## 文件与验证约定

下文 `W` = `game-web/src/main/java/io/github/brick/web/`，`WT` = `game-web/src/test/java/io/github/brick/web/`。
`C` = `game-contract/src/main/proto/`，`CT` = `game-contract/src/test/java/io/github/brick/contract/game/`。
均为仓库相对路径，缩写是明确的路径前缀，包名随目录一致。测试类与被测代码保持相同包。

| 文件组 | 职责 |
| --- | --- |
| C/login.proto、player.proto、common.proto | 注册/登录/退出、档案及统一错误消息 |
| W/domain/account/Account.java、MongoAccountRepository.java | 账号唯一性、凭证与预留 uid |
| W/auth/PasswordHasher.java、CredentialRules.java、SessionIdentity.java、SessionStore.java、LoginRateLimiter.java、AuthService.java | 密码、会话、限流与登录编排 |
| W/domain/player/PlayerProfile.java、PlayerService.java | 创角、读档、改名与数据归属 |
| W/log/BizLogger.java、FileBizLogger.java、BizEvent.java | 可替换日志接口、同步文件实现、字段变更记录 |
| W/api/GameException.java、ApiErrorWriter.java、ApiExceptionHandler.java、AuthController.java、PlayerController.java、RequestBodyLimitFilter.java | 稳定错误、HTTP 边界、请求限制 |
| W/auth/AuthFilter.java、RequestIdentity.java；W/codec/ProfileMapper.java | 请求身份与 DTO 转换 |
| W/config/WebProperties.java、WebConfiguration.java、LockPriorityConfig.java | 显式 bean 装配、参数校验和锁顺序 |
| WT/support/WebIntegrationSupport.java；WT/BusinessLoopIT.java | 隔离测试夹具、真实业务链路验收 |

执行前检查当前 checkout 和已附属 worktree；默认复用适合的 checkout，按 using-git-worktrees 技能处理隔离。
保留此前文档修改，不重置、不丢弃。先运行 `mvn -version` 确认 JDK 25 / Maven ≥3.9。

定向单测命令：`mvn -pl game-web -am "-Dtest=测试类名" -Dsurefire.failIfNoSpecifiedTests=false -DskipITs package`。
用 package 使 reactor 内 game-data 的 test-jar 可用；此命令仍运行单元测试，不能加 `-DskipTests`。
定向 IT 命令：`mvn -pl game-web -am "-Dit.test=测试类名" -Dfailsafe.failIfNoSpecifiedTests=false verify`。
最终全仓命令 `mvn verify`，不得携带跳过选项。Windows 缺 Maven 时改用 `.\mvnw.cmd`。

### Task 1：可编译且可往返的 Protobuf 契约

**Files:** 修改 C/login.proto、CT/LoginContractTest.java；新增 C/player.proto、C/common.proto、CT/PlayerContractTest.java。

**Interfaces:** Java 包 `io.github.brick.contract.game`，java_multiple_files=true。定义以下字段编号：

- `RegisterRequest(username=1,password=2)`；`RegisterResponse(account_id=1)`。
- `LoginRequest` reserved 1 与旧字段名 token，username=2/password=3。
- `LoginResponse(ok=1,user_id=2,token=3,expires_at=4,account_id=5,profile=6)`，user_id=角色 uid。
- `PlayerProfileMessage(uid=1,account_id=2,display_name=3,created_at=4,updated_at=5)`。
- `ProfileRequest()`、`RenameRequest(display_name=1)`、`ProfileResponse(profile=1)`、`LogoutRequest()`、`LogoutResponse(ok=1)`。
- `ErrorResponse(code=1,message=2,request_id=3)`，code 为字符串；ID/时间均 int64，其余按含义为 string/bool/message。

- [x] 写 LoginContractTest / PlayerContractTest：序列化后 parseFrom 保留密码输入、长整型 ID、中文昵称；断言 LoginResponse 旧字段号为 1/2，LoginRequest 字段号 1 不再是有效字段。
- [x] 跑 `mvn -pl game-contract test`，确认新契约尚不存在导致失败。
- [x] 更新/新增三份 proto 及 import，生成代码由 Maven 管理，禁止提交 target 下生成类。
- [x] 重跑同命令通过；每个新增请求/响应至少有一次真实 toByteArray/parseFrom 断言。
- [x] 提交 `feat(contract): define account and player loop messages`，同步 PROGRESS.md。

### Task 2：安全的账号注册与 Mongo 唯一身份

**Files:** 新增 W/domain/account/{Account,MongoAccountRepository}.java、W/auth/{PasswordHasher,CredentialRules}.java、W/api/GameException.java、W/config/WebProperties.java；新增 WT/auth/{PasswordHasherTest,CredentialRulesTest}.java、WT/domain/account/MongoAccountRepositoryIT.java、WT/config/WebPropertiesTest.java。
修改 game-web/pom.xml，启用 failsafe、game-data test-jar；修改 game-data 的 LocalRedisMongo.java 及其 it-config.yaml.example，新增同包 LocalRedisMongoConfigTest.java。

**Interfaces:**
- `Account(long accountId,String username,String passwordHash,long playerId,boolean playerInitialized,long createdAt)`，toString 必须脱敏。
- `MongoAccountRepository(MongoClient,String dbName)`；`void insert(Account)`、`Optional<Account> findByUsername(String)`、`Optional<Account> findById(long)`、`void markPlayerInitialized(long accountId,long playerId)`。
- `PasswordHasher(SecureRandom)`；`String hash(String raw)`、`boolean matches(String raw,String encoded)`；畸形编码不得泄漏或导致超大迭代。
- `CredentialRules.normalizeUsername(String): String`、`validatePassword(String): void`。
- `GameException(int status,String code,String publicMessage)`，不把敏感输入拼入异常。
- `WebProperties` 前缀 `game.web`：sessionTtlSeconds=86400（60～604800）、authWindowSeconds=60（1～3600）、authIpLimit=60（1～10000）、authAccountLimit=10（1～1000）、bizLogDirectory=`logs/biz`；启动校验，保留时间固定 48h。

- [x] 写失败用例：同密码两次 hash 不同但均匹配；错误密码失败；盐/输出字节数及工作因子正确；空密码/14或129码点拒绝、15/128允许，补充字符按码点计数。
- [x] 给测试基类增加 `game.data.redis-database`（默认15，范围1～15）、`game.data.allow-test-cleanup`（默认false）；Mongo 库名必须以 `_test` 结尾，Redis/Mongo host 必须为 loopback。纯测试用例断言库0、生产库名、非本地及未启用清理时，在 flush/drop 前失败。
- [x] 在测试配置中明确使用独立 Redis DB15 与 Mongo game_test；只读取/输出脱敏目标，不打印密码。未确认 DB15 可丢弃时不设置 cleanup=true、不执行 IT；选择其他已确认的1～15库或请求确认，不自行覆盖。
- [x] 写 Mongo IT：两仓库并发插入同一规范化 username，只有一个成功、失败映射409；同账号角色绑定固定；markPlayerInitialized 仅更新匹配账号/uid，找不到记录报完整性错误。
- [x] 运行定向单测与 IT 确认失败；实现 JDK PBKDF2、规则及仓库。注册 insert 前懒创建 `username_unique` 唯一索引；仅 DuplicateKey 转409，其他 Mongo 错误保留给503映射。掉库重建测试夹具需重建仓库实例，避免错误复用已建索引标记。
- [x] 单测与隔离 IT 全通过；验证普通 GameApplicationTest 不因构造仓库访问 Mongo。提交 `feat(web): add password credentials and unique accounts`。

### Task 3：可失效会话、并发退出与凭证限流

**Files:** 新增 W/auth/{SessionIdentity,SessionStore,LoginRateLimiter}.java；WT/auth/{SessionStoreTest,SessionStoreIT,LoginRateLimiterIT}.java。

**Interfaces:**
- `SessionIdentity(long accountId,long uid,long expiresAt,String tokenDigest)`；不持原始token。
- `SessionStore(RedissonClient,JsonCodec,SecureRandom,Clock,WebProperties)`；`IssuedSession issue(long accountId,long uid)`、`SessionIdentity authenticate(String token)`、`void revoke(SessionIdentity)`；内部 record `IssuedSession(String token,SessionIdentity identity)` 必须脱敏 toString。
- `LoginRateLimiter(RedissonClient,WebProperties)`；`void checkIp(String remoteAddress)`、`void checkAccount(String normalizedUsername)`。

- [x] 写 session 单测/IT：新token为43字符URL-safe Base64、仅摘要进入Redis；过期/伪造拒绝；再次issue旧token拒绝；current写失败留下的session拒绝；已鉴权旧identity revoke不得删除新current。
- [x] 写限流IT：同key窗口内前N次允许、N+1为429；TTL不会因被拒绝续期；到期恢复；不同IP/账号隔离；写异常不得允许继续。
- [x] 按验证约定跑失败测试，再实现 `auth:session:<sha256>`、`auth:current:<accountId>` 两种TTL键；所有时间与TTL由服务端给出。authenticate 检查格式、session、绝对到期与current。
- [x] revoke仅对current执行单key Lua compare-and-delete，随后删自己的session。限流单key Lua执行INCR，首次计数EXPIRE；账号限流键使用规范化用户名摘要，避免敏感原文/特殊字符进入key。
- [x] 定向单测/IT通过；确认脚本不跨slot，不标脏。提交 `feat(web): add expiring sessions and authentication throttling`。

### Task 4：可验证失败语义的磁盘业务日志

**Files:** 新增 W/log/{BizEvent,BizLogger,FileBizLogger}.java；WT/log/FileBizLoggerTest.java；修改 .gitignore 忽略 `/logs/`。

**Interfaces:**
- `BizEvent(String operationId,String requestId,long timestamp,long accountId,long uid,String operation,Map<String,String> before,Map<String,String> after)`。
- `BizLogger.append(BizEvent): void`，失败抛 `GameException(503,"BUSINESS_LOG_UNAVAILABLE",...)`。
- `FileBizLogger(Path directory,Clock clock)` implements BizLogger, AutoCloseable；new不连外部存储，首次append懒开文件；`close(): void`。

- [x] 写@TempDir测试：每事件一行合法JSON，反斜线/中文/引号不破坏记录；并发append不交错；目录是普通文件时append失败；轮换后仍可读取旧日志；只删除超过48h的自有文件，不删当前文件/其他前缀/软链接。
- [x] 运行 FileBizLoggerTest 确认失败，实现进程UUID区分文件、UTC按日轮换、FileChannel追加并force(true)，用同步临界区覆盖完整事件写入。时间命名包含本组件固定前缀及可解析创建时间。
- [x] JSON只含声明字段，before/after仅白名单变更字段；关闭文件操作不吞写入失败。保留窗口清理在轮换时执行，使用NOFOLLOW_LINKS，仅清本目录普通自有文件。
- [x] 测试通过并确认日志无凭证对象。提交 `feat(web): add durable business intent logging`。

### Task 5：默认角色的幂等初始化与档案服务

**Files:** 新增 W/domain/player/{PlayerProfile,PlayerService}.java；修改 W/config/LockPriorityConfig.java；新增 WT/domain/player/{PlayerServiceTest,PlayerServiceIT}.java。

**Interfaces:**
- `PlayerProfile(long uid,long accountId,String displayName,long createdAt,long updatedAt)`。
- `PlayerService(LockScope,MongoAccountRepository,BizLogger,Clock)`；`PlayerProfile ensureInitialized(long accountId,String requestId)`、`PlayerProfile get(SessionIdentity)`、`PlayerProfile rename(SessionIdentity,String displayName,String requestId)`。
- 使用 Task2 仓库/错误、Task3身份、Task4日志；只由本服务组装玩家key。

- [x] 写 mock单测：日志异常时verify(ctx,never()).put；先append后put的InOrder；同名rename不append/put；已初始化却缺档案返回500完整性错误；错误归属403；缺失账号不产生profile。
- [x] 写IT：双实例并发首次初始化均返回同一预留uid；先模拟mark失败、修改已有档案后重试，档案不被重置；读miss由Mongo回填；并发rename串行且持锁；非法昵称及Unicode码点边界拒绝。
- [x] 跑失败测试；锁优先级为account=-1/guild=0/player=1。初始化在锁前读取绑定，在一次account+player锁内重读并校验绑定不变；所有返回的profile均校验归属。
- [x] 默认档案时间由account.createdAt确定，昵称 `Player_<uid>`；只有未初始化且档案不存在才append+put；已有档案只补初始化标记，不重置。标记完成后才返回给登录编排。
- [x] get/rename仅持player锁；检查昵称后才改对象，rename只记displayName前后值。调用方不重用旧对象自动重试。
- [x] 单测/IT通过。提交 `feat(web): implement idempotent player creation and profile updates`。

### Task 6：HTTP 鉴权与完整业务入口

**Files:** 新增 W/auth/{AuthService,AuthFilter,RequestIdentity}.java，W/api/{ApiErrorWriter,ApiExceptionHandler,AuthController,PlayerController,RequestBodyLimitFilter}.java，W/codec/ProfileMapper.java，W/config/WebConfiguration.java。
修改 game-web/src/main/resources/application.yaml、WT/GameApplicationTest.java；新增 WT/api/BusinessApiTest.java、WT/auth/AuthServiceTest.java、WT/api/RequestBodyLimitFilterTest.java。

**Interfaces:**
- `AuthService(MongoAccountRepository,PasswordHasher,IdGenerator,PlayerService,SessionStore,Clock)`；`Account register(String username,String password)`、`LoginResult login(String username,String password,String requestId)`；嵌套 `LoginResult(SessionStore.IssuedSession session,PlayerProfile profile)`。
- `RequestIdentity.require(): SessionIdentity`，仅AuthFilter设值并finally remove；requestId由服务器UUID生成，通过MDC读取，禁止原样信任任意客户端字符串。
- `ProfileMapper.toMessage(PlayerProfile): PlayerProfileMessage`。
- `ApiErrorWriter.write(HttpServletResponse,int status,String code,String message,String requestId): void`，供filter与exception handler共用，直接写Protobuf二进制。

- [x] 写AuthServiceTest：注册先校验/散列、再发两个独立ID并插入；登录未知账号也matches假散列且401相同；错密码不创角不发token；只有profile初始化成功才issue。
- [x] 写MVC契约测试：五条spec接口完整成功响应；401/403/409/429/503/500错误为统一Protobuf；空/畸形body为400，错误媒体类型415；未实现方法405，不把其误映射500。
- [x] 写filter测试：身份在正常/异常路径均清理，MDC原有无关字段保留；旧身份不泄漏给下个请求；chunked与伪造Content-Length的16385字节为413，16384允许进入解析；过大请求拒绝发生在凭证散列前。
- [x] 跑失败测试，依赖接口按前任务实现 AuthService 与五个Controller入口。登录/注册路径严格匹配，其他受保护/api路径必须鉴权；非法Bearer不查询账号，接口不接受任意目标uid。
- [x] 用顺序明确的filter注册完成：请求ID及16KiB限制 → auth路径IP限流/受保护路径token验证 → controller输入校验及账号限流。body wrapper通过限制为上限+1的读取发现超大输入，不能先readAllBytes再判断。
- [x] 显式装配Clock/SecureRandom、各服务与Properties；启用ProtobufHttpMessageConverter和`spring.threads.virtual.enabled=true`。配置项绑定环境变量并给出默认值；保留懒连接和上下文单测，无启动期密码/数据库副作用。
- [x] 完整映射异常：锁/Redis/Mongo/日志为503，完整性错误500，返回短公共消息；未知错误仅记录requestId及异常类别，避免输出携带敏感值的异常message。
- [x] 定向单测通过。提交 `feat(web): expose authenticated protobuf account and player APIs`。

### Task 7：自动落盘与冷回填的端到端验收

**Files:** 修改 game-dbserver/pom.xml、game-web/pom.xml；新增 WT/support/WebIntegrationSupport.java、WT/BusinessLoopIT.java、WT/AccountConcurrencyIT.java。

**Interfaces:** 测试使用真实 Task1～6 产品对象与 MockMvc HTTP边界、真实Redis/Mongo；MockMvc穿过实际过滤器和MVC转换器。
落盘侧创建隔离的Spring调度上下文，注册生产FlushOrchestrator/FlushScheduler/GracefulShutdown与测试配置，无Mock落盘替代。
web test scope依赖dbserver普通jar及game-data test-jar，生产不引入dbserver。

- [x] dbserver可执行jar改classifier=`exec`，普通jar供test scope引用；web声明dbserver版本`${project.version}`、scope=test，核对运行时依赖树不含dbserver。
- [x] 写BusinessLoopIT失败用例：register→login→profile→rename→等待调度把JSON落入Mongo→确认dirty/inflight无该key→只删除该profile缓存→profile读取内容一致，Redis被回填。测试不手工调用mongo.upsert/flushOnce来冒充自动落盘。
- [x] 夹具先应用隔离参数，使用独立客户端代表web/dbserver；轮次500ms、最多等待15s、失败输出脱敏状态；清库之前先停止上一用例调度，防止后台与下一用例清理竞争。
- [x] 写AccountConcurrencyIT：两套service对象共享同库，栅栏控制并发注册/首次登录；断言账号与profile数量、稳定uid和current有效token唯一；已有昵称不被并发初始化重置。
- [x] 补端到端两账号隔离、再次登录/退出、过期token，以及故障注入日志写失败阻断profile修改。真实后端验证与单元异常模拟分别标注，不混称。
- [x] `mvn -pl game-web -am "-Dit.test=BusinessLoopIT,AccountConcurrencyIT" -Dfailsafe.failIfNoSpecifiedTests=false verify` 通过，确认两个IT实际执行且无skip。
- [x] 检查 `mvn -pl game-web dependency:tree -Dscope=runtime` 的生产依赖方向；提交 `test(web): verify full business loop through scheduled persistence`。

### Task 8：全仓验证、使用说明与状态收尾

**Files:** 更新 README.md、DEVELOPMENT.md、PROGRESS.md、总纲、本spec/plan、模块设计相关状态；新增 docs/first-business-loop.md。
仅更正与本轮实际交付有关的源码注释（包括 DbServerApplication 中“后续 Plan C”过期说明）。

- [x] 编写 docs/first-business-loop.md：五个HTTP接口字段/错误码、注册登录及改名示例、生成Protobuf二进制与解析命令、环境变量、日志路径、测试库隔离、启停和验收顺序。示例不得使用真实密码/token。
- [x] 更新 DEVELOPMENT.md：dbserver `*-exec.jar` 启动路径，账号同步Mongo例外，新配置和测试清理开关；说明上线须HTTPS、本轮未交付找回密码/多角色/后续玩法。
- [x] 执行 `mvn verify`；逐模块读取新报告统计失败/错误/跳过，测试失败先定位根因，不调整断言掩盖回归。修复后重跑受影响范围，必要时再跑全仓。
- [x] 依执行方式完成最终独立代码审查，重点检查凭证泄漏、失锁/跨存储失败、session竞态、文件清理和测试隔离；处理有效发现并复验。
- [x] 检查Markdown本地链接、`git diff --check`、生成物/日志/本地配置未入库；PROGRESS记录本次命令、日期、精确测试数及尚存限制。
- [x] 只有全部闭环验收通过后将spec与总纲标“已实现且已验证”，计划勾选反映实际执行；提交 `docs: document and verify the first business loop`。不未经证据标记已合并/已部署。

## 计划自审与执行交接

- 覆盖：协议 Task1；账号/密码/配置 Task2；会话与限流 Task3；日志 Task4；创角/档案 Task5；HTTP/MDC/错误/请求上限 Task6；自动落盘与并发 Task7；文档与全量验证 Task8。
- Review Focus 的五类失败条件已分配到所属任务的测试步骤；接口名与依赖顺序已交叉检查。
- 架构风险保持显式：账号与玩家并非跨存储事务；默认角色初始化后丢失档案会报错，不自动重建；Redis秒级数据丢失接受原有边界。
- 推荐 **Native**：由当前会话依序实现，最后独立审查。八项任务共享身份、异常和协议接口，连续实现能减少交接不一致。
- 备选 **Subagent-driven**：每项由独立实现者和审查者推进，检查更细，消耗更多上下文。
- 用户已确认 Native 连续执行；Task 1～8 已执行；最终全仓验证与独立审查修复记录见 PROGRESS.md。
