# S0-002：本机 REST 启动与认证合同

## 1. 背景、目标、范围、非目标

单一结果：Java 11 后端可在隔离配置下绑定 loopback 端口，按全局规范提供受限静态资源、`/health/live` 与已认证 `/api/v1/system/status`，并通过共享 HTTP fixtures 验证。

范围：最小 HttpServer、token/Host/Origin 校验、受 manifest 限制的静态资源、通用 JSON envelope/error、body 限制、request ID、readiness line、优雅停止测试接口和 fixtures。非目标：业务 endpoints、SQLite、Vue/Electron 实现、通用路由框架、CORS、远程监听。

## 2. 前置与引用

- 依赖：[S0-001](./S0-001-java-backend-baseline.md)。
- 必读：[api §1–§7/§10/§13](../api.md)、[architecture §5/§8](../architecture.md)、[local-api-contract](../modules/local-api-contract.md)、[desktop-shell §5](../modules/desktop-shell.md)。

## 3. 允许修改

- `src/main/java/com/ledgerx/http/**`、`application/system/**`、`observability/**` 中本任务所需最小类；
- `src/main/resources/web/**` 仅一个无业务的静态测试页/manifest；生产 Vue 产物由后续构建任务提供；
- 对应 `src/test/**`；
- `docs/contracts/api-v1/system/**` fixtures；
- 仅必要的 Maven test 配置和本任务证据。

不得修改数据库、业务模块、Vue/Electron、全局 API 或增加 Web 框架。

## 4. 实现契约

1. 启动必须读取 `LEDGERX_SESSION_TOKEN`；格式精确为 32 个随机 bytes 的无填充 base64url（43 个 `[A-Za-z0-9_-]` 字符），不匹配/缺失即失败。生产 bind 固定 `127.0.0.1:0`；测试可注入临时端口。
2. `HttpServer.start()` 成功且实际绑定地址复核为 IPv4 loopback 后，立即输出并 flush 一行 `LEDGERX_READY {"port":<1..65535>,"protocol":"1"}`。启动前不得输出，每个进程只输出一次，且不得等待首个请求；不含 token/path/profile。该行仅表示 HTTP 已监听，应用状态仍以已认证 status 为准。
3. `GET /` 返回 manifest 中的 `index.html`；`GET /assets/<manifest-entry>` 返回对应资源。只允许 GET/HEAD、规范化相对路径和 manifest 项，拒绝 `..`、反斜杠、编码穿越、目录列表和未知文件。HTML `Cache-Control:no-cache`，hash asset `public,max-age=31536000,immutable`，MIME 固定 allowlist。测试可通过构造器注入已解析的隔离 web root；生产只读 classpath manifest，不能从 HTTP/env 任意切换目录。
4. `GET /health/live` 无认证，固定 `200 {"status":"UP"}`；其他 method 405 + Allow。
5. `GET /api/v1/system/status` 需 Bearer；成功结构固定 `data={apiVersion:"1.0",applicationVersion:"0.1.0-SNAPSHOT",schemaVersion:null,backupFormatVersion:3,activeProfileId:null,state:"STARTING",capabilities:["system.status"]},meta={dataRevision:null}`。applicationVersion 必须从 Maven build metadata 注入，测试/dev fallback 只能是同一 POM 版本。
6. 无/错 token 一律 `401 AUTHENTICATION_REQUIRED`；无 application 调用。Host 必须匹配本机 origin；GET/HEAD 的 Origin 可缺失、存在时必须同源，mutation 必须有同源 Origin。外部 host/origin 返回 `403 REQUEST_ORIGIN_FORBIDDEN`。
7. `X-Request-Id` 合法时回显；缺失由客户端责任，但 system status 按全局规范返回 400 并生成响应关联 ID。未知路由为 `404 ROUTE_NOT_FOUND`；错误方法为 `405 METHOD_NOT_ALLOWED`；错误 media type 415；超限 413。
8. handler 使用显式有界 executor；队列参数集中且测试可覆盖 429。不得记录 token/header/body。
9. server stop 接口只供进程控制对象，不在 `/api/v1` 暴露公共 endpoint。

## 5. 操作与结果

| 操作 | 前端 | 传输 | 业务 | 持久化 | 边界 |
| --- | --- | --- | --- | --- | --- |
| 启动监听 | 暂不加载业务 | stdout 一次 transport readiness | 应用可仍为 STARTING | 无 | start 失败无 readiness；首请求不重复 |
| live | 固定存活 | 无 token GET | 不调用 application | 无 | 不能泄露版本/路径 |
| 加载静态页 | HTML/asset 可读 | GET/HEAD | 无业务 | 无 | path traversal/未知资源拒绝 |
| status 正确认证 | 可进入初始化页 | token + request ID | 返回固定启动能力 | 无 DB | 错 origin/token 拒绝 |
| malformed/超限 | 可展示稳定错误 | HTTP 状态+error | 零调用 | 无 | 空 body、数组、>1MiB |

## 6. 验收

- 只监听 IPv4 loopback，随机端口有效；非 loopback 配置 fail closed。
- start 前无 readiness；start 返回成功后、首个请求前恰好输出并 flush 一行；并发请求不产生第二行。
- live/status 的方法、header、status、body 与 fixtures 逐字/语义一致。
- 静态页/asset 的 MIME、HEAD、cache header 正确；未知/穿越/编码绕过路径均 404 且不读取 manifest 外文件。
- token、环境和绝对路径在 stdout/stderr/日志/响应均找不到。
- 两个并行 status 请求均成功；队列满返回 429 且 server 后续恢复。
- stop 后端口关闭、executor 终止，无非 daemon 遗留线程。
- 测试不访问真实 `%LocalAppData%`，不创建 SQLite。

## 7. 测试与真实链路

运行 Java 单元、loopback HTTP 黑盒、静态资源安全和 fixture 合同测试。真实 Java HttpServer 必须参与；Electron、Vue、SQLite、发布包不适用。使用内存直接调用 handler不能替代黑盒 HTTP。

## 8. 禁止、升级和报告

禁止 Spring/Javalin/Undertow、CORS `*`、URL/token query、默认无认证、业务路由和 shell 命令。若 HttpServer 无法满足基本 body 限制/关闭/并发，记录可复现证据并升级，不自行换框架。

完成报告：文件、fixture/测试数量、监听证据、认证负例、日志脱敏、线程/端口清理、未测项。公共行为不一致时停止并升级文档，而不是放宽断言。

## 9. 实际执行证据（2026-09-12）

- 实现文件：`src/main/java/com/ledgerx/http/**`、`src/main/java/com/ledgerx/application/system/**`、`src/main/resources/build.properties` 和 `src/main/resources/web/**`；未修改数据库、业务模块、`web/` React 或 `native/` WPF。
- 共享 fixture：1 个，见 [`status-starting.json`](../contracts/api-v1/system/status-starting.json)。测试将真实 status 响应解析后与该 fixture 比较。
- 验证命令：`$env:MAVEN_OPTS='-Duser.home=C:\Users\liang'; .\mvnw.cmd -q '-Dmaven.compiler.fork=true' test`；Java 11 基线 1/1 通过，S0-002 loopback 黑盒测试 6/6 通过。测试覆盖 readiness 单次输出、health/live、静态资源 GET/HEAD 与穿越拒绝、status 认证/Host/Origin/request id、并发 status、错误 media/JSON/超限、队列满 429、token 格式、非 loopback 拒绝及停止清理。
- 打包验证：同样的 Maven fork 配置执行 `package` 通过；`target/classes` 字节码 major version 为 55，build metadata 为 `0.1.0-SNAPSHOT`。
- 真实链路：使用 JDK `HttpServer` 在 `127.0.0.1` 随机端口上启动，并通过 Java 11 `HttpClient` 回环请求验证；不是内存 handler 模拟。测试使用 JUnit `@TempDir` 隔离静态根目录，不读取或写入真实 `%LocalAppData%`，不创建 SQLite。
- 负例与脱敏：缺失/错误认证、错误 Host/Origin、缺失/非法 request id、错误 method/media、非法 JSON、超过 1 MiB body、未知与编码穿越路径均验证了稳定错误；响应和 readiness 输出未包含 token，服务实现不记录 header/body。
- 清理：验证 `stop` 后端口不再接受请求、显式 executor 终止；HTTP 工作线程为 daemon。未验证 Electron/Vue、SQLite、发布 EXE、桌面快捷方式和完整业务流程，因为它们属于后续 S0-003 至 S0-006。
- 环境说明：Windows JDK 11 的 Maven 编译阶段会打印依赖 JAR ZipFS `AccessDeniedException` 诊断，但在 `maven.compiler.fork=true` 下命令退出码为 0，测试报告为上述通过数量；该环境问题未修改 Maven wrapper 或依赖缓存。
