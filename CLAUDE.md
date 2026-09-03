# 工具优先级说明

本项目可用三类工具:原生工具(Read/Edit/Write/Grep/Glob/Bash)、CodeGraph MCP、idea MCP(连接本机正在运行的 IntelliJ IDEA,项目为 Maven 多模块 Java/Spring Boot 工程,含 game-data/game-web/game-dbserver/game-contract)。默认按以下优先级选择,而不是逐次询问。

## 1. 理解代码 / 定位符号
1. **CodeGraph 可用时优先**:`codegraph_explore` 回答"这段代码怎么工作""X 在哪里定义""调用链是什么"这类问题。一次调用返回多个相关文件的**逐行完整源码** + **blast radius**(每个符号有哪些调用方、涉及哪些测试文件),能跨模块串起接口/实现/装配链路(如 `LockScope` → `RedissonLockScope` → `DataAutoConfiguration` → `EntityPriorities`)。它返回的源码等同于已 Read 过,**不要再对同一文件调 Read**。
   - **可用性判断**:索引是用户自己决定建不建的,可能随时被建或被删,不要把某一时刻的状态当成永久事实。判断依据是 **`.codegraph/codegraph.db` 是否存在**,而不是 `.codegraph/` 目录是否存在——踩过一次坑:目录在、里面只有一个 `.gitignore`,`codegraph_explore` 直接拒绝。
   - **不可用时**:不要反复重试,也不要改本文档。直接降级为「idea `search_symbol` 定位 + 原生 Read 读文件」,照样能干活。若用户提到刚跑了 `codegraph init`,直接调用即可(新索引会被实时识别,无需重启)。
2. 需要 **IDE 语义级校验** 时(重载方法辨析、精确的符号位置行列号),用 idea 的 `search_symbol`(参数名是 `--q`)/ `get_symbol_info` / `analyze_calls`。CodeGraph 不可用时,这也是主力定位手段。
3. 需要读**第三方依赖源码**(如 `.m2` 仓库里 jar 包内的类,例如 Redisson、Spring Boot 源码)时,**只能**用 idea 的 `read_file`(参数名是 `--file_path`),路径形如 `<jar绝对路径>!/<包路径>/<类>.java`。CodeGraph 只索引本项目源码,原生 Read 也读不了这种 `!/` 虚拟路径(均已实测)。
4. 纯文本搜索(找字符串、找文件名模式)用原生 Grep/Glob——更快、无 IDE 依赖。

## 2. 编辑代码
- 默认用原生 **Edit/Write**,速度快、无副作用、不依赖 IDE 是否在跑。
- 仅当需要 **项目级安全重命名**(多模块间引用同一个类/方法,想确保所有引用点都同步改名)时,用 idea 的 `rename_refactoring`,比 grep+sed 替换更可靠,能感知 Java 语义(重载、接口实现等)。

## 3. 编译 / 静态检查
- 改完 Java 文件后,若怀疑改动可能引入编译错误或明显问题(未用的 import、类型不匹配等),可用 idea 的 `get_file_problems` 快速拿 IDE 实时诊断,比等 `mvnw compile` 跑完更快。
- 最终验证是否能构建成功,仍以 **Bash 跑 `./mvnw compile`/`test`** 为准——这是权威结果,`get_file_problems` 只做前置快速反馈,不能替代真实构建。

## 4. 调试(xdebug_*)
- 这是 idea MCP **最有价值但副作用最大**的能力,只在静态阅读代码无法确认行为时使用(比如 Spring 装配是否生效、运行时状态到底是什么)。
- **能跑单测就不启动完整 Spring Boot 应用**:`GameApplication`/`DbServerApplication` 这类完整应用会真的连 Redis/MongoDB、占用端口。优先对着具体的 `@Test` 方法用 `xdebug_start_debugger_session --filePath ... --line ...` 调试。
- 启动完整应用做调试前,**必须先跟用户确认**——这是有真实副作用的操作(起服务、连外部依赖),不能默认执行。
- 调试结束后主动 `xdebug_remove_breakpoint` 清理自己加的断点,不要动用户原本就有的断点。

## 5. 数据库(execute_sql_query / introspect_schema / preview_table_data 等)
- 项目数据层是 **Redis + MongoDB**,不是 JDBC 关系库,这组工具多半用不上——需要查数据时先想清楚目标库是什么,别默认套 SQL 工具。
- 真要用时,先 `list_database_connections` 看有没有配置好的连接(可能为空,取决于用户当时怎么配的 IDE),不要假定连接一定存在。
- 涉及写操作或不确定连接指向哪个环境(本地/测试/共享开发库)时,先确认连接目标,不要默认执行写入或不可逆查询。

## 6. Git / 终端 / 运行
- Git 操作(status/diff/log/commit)一律用 **Bash 原生 git**,不用 idea 的 `git_status`——原生工具输出更适合我处理,且commit工作流已经按 Bash git 约定好。
- 一般终端命令(跑测试、跑脚本)用 **Bash 工具**,不用 idea 的 `execute_terminal_command`,除非明确需要在 IDE 的运行环境/运行配置上下文里执行。

## 7. 兜底原则
- idea MCP 依赖 IDE 进程存活、项目已在 IDE 中打开。调用失败或超时时,直接回退到 CodeGraph/原生工具,不要重试太多次卡住流程。
- 涉及"启动服务""跑调试会话""执行 SQL"这类有真实副作用的 idea 调用,遵循和 Bash 命令一样的确认原则——不确定范围或影响时先问用户。
- **本机真实系统是 Windows**(Git Bash/MSYS2 提供 shell),尽管环境信息报的是 `Platform: darwin`。idea MCP 返回反斜杠路径、`C:\...` 临时路径都是正常现象,不是 bug。
- **idea 工具的参数命名不统一**,踩过多次:`read_file` 用 `--file_path`(下划线),`get_file_problems`/`open_file_in_editor` 用 `--filePath`(驼峰),`search_symbol` 用 `--q`。报 "Missing required parameters: X" 时,报错信息里的 X 就是正确参数名,照着改一次即可,别乱猜。
