# 首个业务闭环：账号登录、玩家档案与落盘

> 状态：2026-10-16 用户已确认设计；实现计划待审阅，业务代码尚未实现。
> 本文细化架构 §5.2，修订 Mongo 唯一写者的范围和发号设计 §6 的本轮业务接入方式。
> 设计已定不代表功能已交付。开发与验证状态见 [PROGRESS.md](../../PROGRESS.md)，执行任务见[实现计划](../plans/2026-10-16-first-business-loop.md)。

## 1. 目标与截止范围

交付可重复验证的服务端闭环：注册账号 → 登录并首次创建默认角色 → 携带 token 读取档案
→ 修改昵称 → 独立 dbserver 自动落 Mongo → 删除已落盘的对应玩家缓存 → 读取并正确回填。

本轮只支持每账号一个默认角色，账号 ID 与角色 uid 仍分别由 `IdGenerator` 发号。
多角色管理、背包、装备、商城、货币操作、第三方登录、找回密码及 WS 不在本轮范围。
验收不需要 Unity 客户端，使用 Protobuf HTTP 测试与开发验收说明。

## 2. 方案比较与建议

| 方案 | 优点 | 代价 |
| --- | --- | --- |
| **账号同步存 Mongo，玩家沿用 Write-Behind（建议）** | 账号名唯一约束由 Mongo 保证；账号注册结果同步持久化；复用现有玩家数据链路 | 明确新增账号存储的同步写入例外 |
| 账号和玩家全部 Write-Behind | 表面上统一持久化路径 | 需要新增非数字账号名索引、注册唯一性及索引恢复协议，扩大本轮范围 |
| 外部身份服务 | 游戏服务不保存密码 | 引入额外服务和部署依赖，无法仅靠本仓库独立完成闭环 |

选择第一种方案。`game-dbserver` 仍是 **玩家业务档案** 在 Mongo 的唯一写者；
Mongo `counters`（已有）和 `accounts`（本轮新增）分别由发号器、账号仓库同步写入。
账号仓库属于 `game-web`，不得向 `game-data` 引入账号/玩家类型。

## 3. 模块职责

- `game-contract`：注册、登录、退出、档案读取/改名及错误响应的 Protobuf 定义。
- `game-web/auth`：密码散列、账号凭证校验、Redis session、AuthFilter 和请求身份上下文。
- `game-web/domain/account`：账号文档及 MongoAccountRepository，唯一账号名和默认角色绑定。
- `game-web/domain/player`：PlayerProfile、PlayerService；所有档案读写使用 LockScope/LockCtx。
- `game-web/log`：BizLogger，记录创角及改名的修改前后字段，独立磁盘追加。
- `game-web/api` 与 `codec`：HTTP 参数/错误处理、Protobuf 与领域对象转换。
- `game-data`：复用原语；业务词汇留在 web。`game-dbserver` 无需理解账号/玩家 POJO。

启用 Spring MVC 的 Protobuf 消息转换与虚拟线程。生产模块依赖方向保持不变。
端到端测试可增加 web 到 dbserver 的 **test scope** 依赖：dbserver 保留普通 jar，
可执行包使用独立 classifier，避免测试依赖不可直接加载的 Boot 重打包 jar。

## 4. 数据模型与注册

Mongo `accounts` 文档：`_id=accountId`、`username`、`passwordHash`、`playerId`、
`playerInitialized`、`createdAt`。`username` 唯一索引必须在注册前确认存在；
初始化失败时请求失败，不忽略索引错误。无需在纯装配测试中连接真实数据库。

账号名统一为小写 ASCII，允许 `[a-z0-9_]{3,32}`；输入先去首尾空格并按 Locale.ROOT 小写。
密码不裁剪、不截断，长度 15～128 个 Unicode 码点；超限在执行密码散列前拒绝。
采用 JDK PBKDF2-HMAC-SHA256：600000 次迭代、每账号随机 16 字节盐、32 字节输出，
保存包含算法版本/参数/盐/结果的编码；比较使用恒定时间比较。未知账号同样执行一次假凭证校验，
登录错误统一为“账号或密码错误”。不存明文、不向日志写密码、散列或 token。

注册步骤：校验输入 → 散列密码 → 锁外分配 accountId 与 playerId → 唯一索引约束下插入账号，
`playerInitialized=false`。并发重名只允许一个成功；其他请求返回 409，消耗的号段允许空洞。
响应丢失时重复注册仍返回重名，用户可用相同凭证登录，不再次创建身份。

账号同步存储及独立角色 ID 保留未来多角色扩展能力；本轮不创建发号设计 §6 示例中的
`account:{id}:roles` 异步文档，默认角色绑定以 `accounts.playerId` 为唯一来源。

## 5. 登录、创角与异常边界

密码校验通过后，以账号预留的稳定 playerId 创角，避免失败重试分配第二个角色。
锁全局顺序扩展为 `account → guild → player`，保留原有 guild 先于 player 的相对次序。
创角一次 `lockAll(account, player)`，不嵌套调用另一次 lockAll。

在锁内重读账号及 `ctx.get(playerProfileKey)`：

1. 已有档案：校验 accountId/uid 归属；不重置昵称或其他字段。
2. 档案不存在且账号未完成初始化：BizLogger 先写创角记录，再 `ctx.put` 默认档案。
3. 档案就绪后将账号 `playerInitialized` 标为 true，再签发会话。
4. 账号已经初始化但 Redis/Mongo 均无档案：返回数据完整性错误，禁止静默新建或重置。

Profile 文档：`uid`、`accountId`、`displayName`、`createdAt`、`updatedAt`。
初始昵称由服务器生成 `Player_<uid>`，不要求全服唯一。
key 统一使用 `DataKeys.key("player", uid, "profile")`；Mongo 集合为 `player:profile`，
形态为 `{_id: uid, v: JSON字符串}`，与当前 MongoStore 一致。

创角与账号状态更新之间不是跨存储事务。若写 profile 成功但标记失败，再次登录读取同一
playerId 档案并完成标记，不覆盖已有数据。若标记成功而后续发生 Redis 未落盘数据丢失，
按现有 AOF 秒级丢失口径暴露错误，人工核查；不引入自动日志重放。
Mongo/Redis 不可用、失锁、日志失败均不得返回成功或创建假会话。

## 6. 会话与鉴权

使用 SecureRandom 产生 32 字节随机 token，URL-safe Base64 编码，默认固定有效期 24 小时。
Redis 保存 token 的 SHA-256 摘要作为键，不保存原始 token；值含 accountId、uid、到期时间。
另存每账号当前有效 token 摘要，用于单点登录；两类键均设置 TTL，不进入 dirty/落盘流程。

签发顺序：先写带 TTL 的 session，再替换账号 current 标记。鉴权必须同时确认 session 存在、
未过期且 current 与其摘要一致。替换 current 是单点登录的生效点；并发登录最后替换者有效。
写 current 失败不返回 token，孤立 session 因缺少 current 匹配而无效并由 TTL 清理。
这些是独立单 key 命令，不使用跨 slot Lua。旧 session 即使暂未删除也无法通过鉴权。

受保护请求仅接收 `Authorization: Bearer <token>`，身份只来自服务器 session，
不信任请求中的 accountId/uid。AuthFilter 放行注册与登录；受保护 API 在进入业务前校验。
请求上下文仅保存身份，MDC 写 requestId/accountId/userId；在 finally 清理所有本次注入字段。
Redis 故障返回 503，不能当作匿名请求继续执行。

退出时对账号 current 标记进行 **单 key compare-and-delete**，再删除当前 session，
防止旧会话的并发退出删除新登录会话。已完成鉴权的在途请求允许完成，后续请求拒绝旧 token。

凭证接口具备可配置的每 IP/每账号短窗频率限制，使用带 TTL 的 Redis 计数；单 key 原子更新，
不采信客户端自行传入的 X-Forwarded-For。限流返回 429，存储故障返回 503。
部署通过 HTTPS 暴露；本机验收允许 loopback HTTP。

## 7. HTTP 契约与档案修改

所有接口使用 POST，业务消息 Content-Type 为 `application/x-protobuf`。

| 路径 | 请求 | 成功结果 | 鉴权 |
| --- | --- | --- | --- |
| `/api/auth/register` | username、password | accountId | 无 |
| `/api/auth/login` | username、password | token、expiresAt、accountId、uid、profile | 无 |
| `/api/auth/logout` | 空消息 | 成功确认 | Bearer |
| `/api/player/profile` | 空消息 | 当前角色 profile | Bearer |
| `/api/player/rename` | displayName | 更新后的 profile | Bearer |

LoginRequest 旧 token 字段编号 1 保留为 reserved（旧的 token 登录种子不再支持），
username/password 使用新编号；LoginResponse 已有 ok=1、user_id=2 保留，user_id 明确为角色 uid，
新增 token、expiresAt、accountId 和 profile 使用新编号，禁止复用编号改变含义。

读取与改名均只处理会话绑定的角色，持 player 锁并校验档案 accountId。
昵称去首尾空格后为 1～24 个 Unicode 码点，禁止控制字符及换行；允许重名。
改名是整字段赋值，重试同名不再次修改时间或写业务日志；并发改名按实体锁串行，最后一次生效。
客户端不能提交货币、等级、其他人的 uid 或整个任意档案 JSON。

错误统一为 Protobuf `ErrorResponse(code, message, requestId)`：400 参数/消息错误，401 未认证，
403 归属错误，409 账号已存在，413 请求过大，415 媒体类型错误，429 限流，503 依赖/锁/日志失败，
500 数据完整性/未知内部错误。响应不泄露异常堆栈、密码或内部连接信息。
API 请求体限制为 16 KiB，流式读取时同样限制，不能只相信 Content-Length。

## 8. 业务日志

BizLogger 使用独立目录的 JSON Lines 文件，每个进程独立文件，日切且保留 48 小时。
写入调用为同步追加并 force；写满、权限错误等写入失败会中止后续 ctx.put。
记录 operationId/requestId、时间、accountId、uid、操作类型及变更字段的 before/after。
创角与改名不涉及数值增量；后续引入货币操作时再强制 delta/before/after 三元组。
禁止写整个凭证对象、完整档案快照、密码或 token。

日志先于业务提交，语义为 **变更意图**，不能据此断言 Redis 已提交成功。
提交失败时同样可能存在意图记录，人工对账以现状为准；不自动重放/回滚。
留存清理仅处理本组件命名的历史文件；不得删除任意目录内容。

## 9. 测试与验收

单元测试覆盖密码校验、输入边界、Protobuf 往返、错误映射、身份上下文清理、日志失败阻断写入。
集成测试使用隔离的 Redis DB 和独立 Mongo 测试库，明确复用现有清理约定；禁止操作开发业务库。
在确认测试连接和隔离范围前不运行会清库的套件，测试日志输出到 target 下。

验收用例：

1. 注册 → 登录首次创角 → 读取 → 改名，HTTP 响应与 Redis 内容一致。
2. 使用真实 dbserver 调度/编排等待 Mongo 出现对应内容，再只删除该档案缓存，HTTP 读取回填同一内容。
3. 同账号再次登录 uid 不变，新 token 可用，旧 token 失效；退出后拒绝，旧退出不能影响新 token。
4. 并发同名注册只创建一个账号；并发首次登录只使用预留的一个 uid，不覆盖已经修改的档案。
5. 两账号隔离，过期/伪造 token 拒绝；MDC/身份在线程复用及异常后无泄漏。
6. 创角中途失败后重试不新增角色；已初始化账号的档案缺失时禁止静默重建。
7. 日志失败、拿锁失败、Redis/Mongo 故障不得响应成功；敏感字段不进入响应/日志。
8. 全仓单元和集成测试通过，报告无未解释的跳过；记录准确命令与结果。

闭环验收后同步本设计、总纲、PROGRESS.md、实现计划和 DEVELOPMENT.md；到此停止功能扩展。

## 10. 安全参数依据

- [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)：PBKDF2-HMAC-SHA256 的工作因子参考值为 600000 次；本轮采用 JDK 内置实现以控制依赖。
- [OWASP Session Management Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html)：会话标识需不可预测并有足够熵；本设计采用 256 位随机值并限制有效期。
