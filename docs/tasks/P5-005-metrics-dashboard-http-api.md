# P5-005：Metrics、Formula 与 Dashboard REST API

> 原文 Electron 相关分工仅为历史背景；当前真实集成与发布验收以 [P5-008 浏览器闭环](./P5-008-metrics-dashboard-browser-e2e-performance.md) 和 [P7 路线图](./README.md) 为准。

- 状态：PARTIAL — metrics、formula 与 dashboard REST、ETag、幂等、集合过滤/分页、system visibility 和真实 HTTP 测试已实现；完整错误矩阵仍待补齐
- 单一结果：已认证的 loopback REST 服务完整提供 P5 指定 endpoints、JSON/ETag/idempotency/error 契约和真实 Java→SQLite 证据，并仅在此时公开相关 capability。

## 1. 背景、范围与非目标

本任务把已验收的 application 行为映射到 HTTP；[Metrics/Formula API](../api/metrics-formulas-api.md) 和 [Dashboard API](../api/dashboard-api.md) 是唯一外部契约来源。范围包含 routes、严格 body/query/header 解析、response/error projection、fixtures、HTTP integration tests、status capabilities。非目标：业务求值/SQL 算法、Vue、Electron、migration、新依赖和前端友好改写 API。

## 2. 前置与允许文件

- 前置：P5-004、P4-005 PASS；阅读全局 API、两个 P5 API 文档和现有 records HTTP 实现。
- 允许：`src/main/java/com/ledgerx/http/LedgerHttpServer.java`，只有 HTTP 映射必要时的 `ApplicationRuntime` accessors，`ProfileApplicationService` capability 拼装，`src/test/java/com/ledgerx/http/**`，`docs/contracts/api-v1/metrics/**`、`docs/contracts/api-v1/dashboard/**`，本 Task 与验证记录。
- 禁止：metrics domain/persistence/migration/frontend/electron/pom；不得替 application 重算金额或写 SQL。

## 3. 实施契约

1. 注册且只注册：metrics list/detail/create/replace/archive，formulas validate/preview/version list，dashboard read/layout read/layout replace/layout reset；方法、path、status、query、body 逐字匹配两个 API 文档。未知 P5 路由为 `404 ROUTE_NOT_FOUND`。
2. 复用 `LedgerHttpServer` 认证、loopback/Host/Origin、content type、request ID、error envelope、JSON size、idempotency 和 cursor 基座；认证/恢复失败必须在 application 调用前停止。
3. 所有 object body 闭合，重复 scalar query、未知 query、非法 UUID/date/enum、JSON number money、空/额外 AST node key 都走指定 400 field path；不宽松忽略字段。
4. metric detail/update/archive 使用 definition revision 的强 ETag；layout GET/PUT 使用 layout revision。PUT/DELETE/POST mutation 均按文档要求 If-Match/Idempotency-Key；回放首个 status/body/ETag/Location，key 重用不同请求为全局冲突。
5. dashboard 的 `granularity` 和 `anchor` 必填，期间输出规范化。hidden card 发送 metadata 与 `presentation.hidden`，不得序列化任一数值、trend、breakdown 或 raw AST/records。
6. `system.status` 仅在路由通过真实 application 测试后增加 `metrics.read`、`metrics.write`、`formulas.validate`、`formulas.write`、`dashboard.read`、`dashboard.layout.write`；不会误报 reports/assets/direct-metrics 等能力。
7. error response 不包含 SQL、文件路径、token、profile、金额、名称、formula AST/tokens 或 note。所有写入 response `meta.dataRevision` 与 application 事务结果一致。

## 4. 验收与测试

- fixtures 至少覆盖 system/custom list/detail、create/replace/archive、每种 AST node、中文 token normalize、cycle/token mismatch、archived refs、version cursor、validate/preview 所有状态、ETag/idempotency replay/profile isolation、dashboard four granularities/hidden/FUTURE/layout error。
- 对合法和非法请求断言 method/path/headers/status/Location/ETag/完整 envelope/真实 SQLite 重读；不是只验证 route 可达。
- 覆盖 401、403、415、423、428、409、400、404、503，未知路由和重复 query；验证 application 未被调用或无写入。
- 必跑 `./mvnw.cmd -q '-Dmaven.compiler.fork=true' test package`。本任务要求真实 Java HttpServer→application→sqlite-jdbc；Vue/Electron/发布包不适用。

## 5. 禁止事项、升级与完成报告

禁止在 HTTP 层修改 facts、公式或 layout，修改已定 API 字段，使用 mock application 作为唯一证据，提前展示 capability，或注册未授权报表/预警路由。发现 API 文档与 application result 不可无损映射、全局 HTTP 基座不支持 required ETag/ID replay，停止并升级。

完成报告列出 endpoint/fixture/测试数、真实 HTTP→SQLite/reopen 证据、所有 capability、security/error no-leak 检查和不适用层。
