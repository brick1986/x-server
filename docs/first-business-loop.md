# 首个业务闭环：运行与验收

范围：账号密码注册 → 登录并创建默认角色 → 鉴权读档/改名 → dbserver 自动落盘 → 缓存 miss 回填。
每账号一个默认角色；没有背包、商城、多角色、第三方登录或找回密码。当前验证状态见 [PROGRESS](../PROGRESS.md)。

## 启动与停止

准备 JDK 25、Maven 3.9+、Redis 和 Mongo；连接配置见 [开发环境](../DEVELOPMENT.md)。
两个应用必须指向同一 Redis 与 Mongo 库；应用使用 Redis DB0，测试使用独立 DB15。
在仓库根目录构建并分别在两个终端启动：

```powershell
mvn -DskipITs package
java -jar game-dbserver/target/game-dbserver-0.0.1-SNAPSHOT-exec.jar
# 另一终端；默认 HTTP 端口 8080
java -jar game-web/target/game-web-0.0.1-SNAPSHOT.jar
```

先启动 dbserver，再启动 web。停止时先停 web、再停 dbserver，给落盘进程留出刷盘窗口。
dbserver 普通 jar 只供测试引用，运行时必须使用 `-exec.jar`。
对外部署必须使用 HTTPS；本地示例仅使用 loopback HTTP。

## HTTP 契约

所有接口为 POST，`Content-Type: application/x-protobuf`，成功响应为二进制 Protobuf。
受保护接口加 `Authorization: Bearer <token>`。请求体上限 16 KiB，包括流式请求。
API 仅接受规范路径；百分号编码、分号参数、重复斜杠等路径别名返回 400（INVALID_PATH），已登录请求也不能绕过此限制。
响应带服务端生成的 `X-Request-Id` 和 `Cache-Control: no-store`。
协议源文件：[login.proto](../game-contract/src/main/proto/login.proto)、[player.proto](../game-contract/src/main/proto/player.proto)、[common.proto](../game-contract/src/main/proto/common.proto)。

| 路径 | 请求 | 成功响应 | 鉴权 |
| --- | --- | --- | --- |
| `/api/auth/register` | RegisterRequest：username、password | RegisterResponse：account_id | 无 |
| `/api/auth/login` | LoginRequest：username、password | LoginResponse：ok、user_id、token、expires_at、account_id、profile | 无 |
| `/api/auth/logout` | LogoutRequest：空 | LogoutResponse：ok | 需要 |
| `/api/player/profile` | ProfileRequest：空 | ProfileResponse：profile | 需要 |
| `/api/player/rename` | RenameRequest：display_name | ProfileResponse：profile | 需要 |

账号名去首尾空白并转小写后须匹配 `[a-z0-9_]{3,32}`。密码不裁剪，15～128 Unicode 码点。
昵称去首尾空白后为 1～24 码点，不允许控制字符；同名改名不重复写数据或日志。
Profile 包含 uid、account_id、display_name、created_at、updated_at。所有时间为 UTC epoch 毫秒，ID 为 int64。
账号 ID 和角色 ID 分别发号；客户端不能指定操作目标 uid。
再次登录会使旧 token 失效；退出仅撤销当前会话，旧请求的退出不会删除新会话。token 到期不自动续期。

所有错误体使用 `ErrorResponse(code,message,request_id)`；根据 HTTP 状态选择消息类型再解码。

| HTTP | code 示例 | 含义 |
| --- | --- | --- |
| 400 | INVALID_ARGUMENT / INVALID_MESSAGE / INVALID_PATH | 字段、消息或路径非法 |
| 401 | UNAUTHORIZED | 凭证错误、token 过期/失效/伪造 |
| 403 | FORBIDDEN | 档案归属不符 |
| 404 / 405 / 406 / 415 | NOT_FOUND / METHOD_NOT_ALLOWED / NOT_ACCEPTABLE / UNSUPPORTED_MEDIA_TYPE | HTTP 契约不符 |
| 409 | ACCOUNT_EXISTS | 账号名重复 |
| 413 | PAYLOAD_TOO_LARGE | 请求体超过 16 KiB |
| 429 | RATE_LIMITED | IP 或账号限流 |
| 500 | DATA_INTEGRITY / INTERNAL_ERROR | 已初始化档案缺失、数据损坏或未分类错误 |
| 503 | DEPENDENCY_UNAVAILABLE / BUSINESS_LOG_UNAVAILABLE | 锁、Redis、Mongo 或磁盘日志失败 |

## Protobuf 二进制示例

下面在仓库根目录使用 **Git Bash**，需要 `curl`；不要把 PowerShell 的文本管道套用到二进制重定向。
`mvn package` 已下载 protoc；文件名带生成摘要，先选择非 grpc 的 protoc。示例账号和密码仅作演示。

```bash
PROTOC=$(find game-contract/target/protobuf-maven-plugin -name 'protoc-*.exe' ! -name 'protoc-gen-*' | head -n 1)
PROTO_DIR=game-contract/src/main/proto
mkdir -p /tmp/x-server-demo
printf 'username: "demo_player" password: "demo-password-12345"' |
  "$PROTOC" -I "$PROTO_DIR" --encode=brick.game.contract.RegisterRequest login.proto > /tmp/x-server-demo/register.bin
curl -sS -o /tmp/x-server-demo/register-response.bin -w '%{http_code}\n' \
  -H 'Content-Type: application/x-protobuf' --data-binary @/tmp/x-server-demo/register.bin \
  http://127.0.0.1:8080/api/auth/register
"$PROTOC" -I "$PROTO_DIR" --decode=brick.game.contract.RegisterResponse login.proto < /tmp/x-server-demo/register-response.bin

printf 'username: "demo_player" password: "demo-password-12345"' |
  "$PROTOC" -I "$PROTO_DIR" --encode=brick.game.contract.LoginRequest login.proto > /tmp/x-server-demo/login.bin
curl -sS -o /tmp/x-server-demo/login-response.bin -w '%{http_code}\n' \
  -H 'Content-Type: application/x-protobuf' --data-binary @/tmp/x-server-demo/login.bin \
  http://127.0.0.1:8080/api/auth/login
"$PROTOC" -I "$PROTO_DIR" --decode=brick.game.contract.LoginResponse login.proto < /tmp/x-server-demo/login-response.bin

# 将返回的 token 填入变量；不要提交或粘贴到日志/工单
TOKEN='<上一步返回的 token>'
printf 'display_name: "FirstPlayer"' |
  "$PROTOC" -I "$PROTO_DIR" --encode=brick.game.contract.RenameRequest player.proto > /tmp/x-server-demo/rename.bin
curl -sS -o /tmp/x-server-demo/profile-response.bin -w '%{http_code}\n' \
  -H 'Content-Type: application/x-protobuf' -H "Authorization: Bearer $TOKEN" \
  --data-binary @/tmp/x-server-demo/rename.bin http://127.0.0.1:8080/api/player/rename
"$PROTOC" -I "$PROTO_DIR" --decode=brick.game.contract.ProfileResponse player.proto < /tmp/x-server-demo/profile-response.bin
curl -sS -o /tmp/x-server-demo/profile-response.bin -w '%{http_code}\n' \
  -H 'Content-Type: application/x-protobuf' -H "Authorization: Bearer $TOKEN" \
  --data-binary '' http://127.0.0.1:8080/api/player/profile
curl -sS -o /tmp/x-server-demo/logout-response.bin -w '%{http_code}\n' \
  -H 'Content-Type: application/x-protobuf' -H "Authorization: Bearer $TOKEN" \
  --data-binary '' http://127.0.0.1:8080/api/auth/logout
```

若返回非 200，改用 `--decode=brick.game.contract.ErrorResponse common.proto` 解码对应响应文件。
测试后删除示例二进制文件（包含演示密码/token）。

## 配置和持久化边界

| 环境变量 | 默认 | 范围/作用 |
| --- | --- | --- |
| SESSION_TTL_SECONDS | 86400 | 60～604800，固定会话期限 |
| AUTH_WINDOW_SECONDS | 60 | 1～3600，固定限流窗口 |
| AUTH_IP_LIMIT | 60 | 1～10000，注册/登录共用 IP 额度 |
| AUTH_ACCOUNT_LIMIT | 10 | 1～1000，注册/登录共用规范化账号额度 |
| BIZ_LOG_DIRECTORY | logs/biz | 相对进程工作目录；需要可写、持久磁盘 |

`accounts`（账号及 PBKDF2 密码散列）和 `counters` 同步写 Mongo；玩家档案通过 Redis 标脏、dbserver 异步落 Mongo。
注册允许消耗但跳过 ID；账号初始化标记失败后，重试沿用已创建角色。已标记初始化但档案完全缺失会报完整性错误，不自动重建。
不存在跨 Mongo/Redis 的事务；保留原架构秒级落盘窗口和 Redis AOF 风险边界。

业务日志为 `logs/biz/biz-<创建毫秒>-<进程UUID>.jsonl`，UTC 每日轮换。
轮换时只清理所属 UTC 日结束已满 48 小时的自有普通文件，保证晚写记录至少保留 48 小时；整段保留会使较早记录保存更久，空闲期间清理延至下一次写入。
同步 append + force 成功后才修改档案；日志写失败返回 503。内容只记 CREATE_PLAYER/RENAME 及昵称前后值，不记密码、散列或 token。
它是**操作意图**记录，不证明 Redis 提交成功，也不自动重放。多实例日志需要分别收集并按 operationId/requestId 对账。

## 自动验收

按 [开发环境测试配置](../DEVELOPMENT.md#五跑测试) 准备可丢弃的 Redis DB15 和 Mongo `game_test`，显式启用 `allow-test-cleanup`。
测试仅允许 loopback 地址、Redis 非零测试库、以 `_test` 结尾的 Mongo 库；每用例会清空它们。

```powershell
mvn -pl game-web -am '-Dit.test=BusinessLoopIT,AccountConcurrencyIT' '-Dfailsafe.failIfNoSpecifiedTests=false' verify
mvn verify
```

`BusinessLoopIT` 穿过真实过滤器、Protobuf 转换、Controller、Service；后端为真实 Redis/Mongo。
独立 Spring 上下文和独立数据库客户端运行生产 `FlushScheduler`，间隔 500ms，最多等待 15s。
它断言 Mongo 已保存改名值、dirty/inflight 无该 key，才删除**这个已落盘的档案缓存**，验证读取值一致并重新回填 Redis。
同时覆盖旧 token、退出、到期、两账号隔离、关闭日志写入器后禁止改名。
`AccountConcurrencyIT` 验证并发同名注册唯一、首次创角唯一、当前 token 唯一、重复登录不重置昵称。
HTTP 使用 MockMvc，无外部监听端口；这些测试不代表公网部署、HTTPS 或压力测试已经完成。
