# P5-004：指标与 Dashboard 应用服务

> 原文 Electron 相关分工仅为历史背景；当前真实集成与发布验收以 [P5-008 浏览器闭环](./P5-008-metrics-dashboard-browser-e2e-performance.md) 和 [P7 路线图](./README.md) 为准。

- 状态：PARTIAL — 指标 CRUD、Dashboard 读取/布局事务、formula validate/preview/version、虚拟布局和分类/账户/时间/嵌套指标公式求值已实现；完整 breakdown 投影仍待补齐
- 单一结果：受 active-profile 锁保护的 Java application 层可执行指标/公式/布局用例，并在单一 SQLite snapshot 内返回符合 API 投影所需的数据，而不暴露 HTTP 或 SQL 细节。

## 1. 背景、范围与非目标

P5-003 只提供真实存取；本任务将其编排为可调用的 `MetricsApi` 与 `DashboardApi`。公共用例和结果类型以 [技术设计 §2、§5–§6](../design/P5-metrics-formulas-dashboard-technical-design.md#5-application-服务) 为准，具体外部字段仍由 P5-005 实现。

范围：application metrics package 的 API/draft/result/exception/service，`ProfileApplicationService` 和 `ApplicationRuntime` 的委托、锁/事务/dataRevision 协调，application→SQLite 集成测试。非目标：HTTP route/JSON、frontend、迁移、重写 P5-002 或 P5-003、GridStack。

## 2. 前置与允许文件

- 前置：P5-001..003、P4-005 PASS；阅读技术设计、两个 P5 API 文档、全局 API 的 revision/idempotency 规则。
- 允许：`src/main/java/com/ledgerx/application/metrics/**`、`ProfileApplicationService.java`、`ApplicationRuntime.java`、直接 tests、`docs/verification/P5-004-metrics-application.md`、本 Task。
- 禁止：`LedgerHttpServer`、migration、frontend/electron、API 文档公共字段改动、P4 record/cat/account 行为改动。

## 3. 实施契约

1. 新增窄接口 `MetricsApi`、`DashboardApi`；`ApplicationRuntime` 只 delegate/recovery guard，`ProfileApplicationService` 是 active profile、read/write lock、transaction 与 dataRevision 的唯一协调者。不得让 HTTP/前端直调 repository。
2. mutation 的顺序固定：认证/恢复由外层保证；profile active → If-Match/idempotency → closed draft/reference/formula validate → graph validate → 单 transaction persistence + operation + `dataRevision` 一次递增 → result。异常不改变 definition/version/layout/dataRevision。
3. custom metric create、complete replace、archive、system visibility update、formula validate/preview、formula version list、layout get/replace/reset 都有单独 application method；不可为了减少方法让 HTTP 传 SQL/JSON 对象进来。
4. dashboard read 获取一次 SQLite read snapshot、一次 dataRevision，解析 period 与所有 enabled metric；先聚合 facts，再用 request memoization 按 metric/version/asOf 计算。任何一个 card 的 `READY/EMPTY/UNAVAILABLE/FUTURE` 状态必须来自固定优先级，不能丢失 layout item。
5. hidden 指标只能返回 metadata/presentation.hidden，绝不返回 current/previous/change/trend/breakdown 的数值或 status。custom archived metric 不进入 dashboard；system metric 不允许 archive。
6. 创建后 `dashboardEnabled=true` 不立即改 layout：dashboard 投影追加确定性 unplaced/default card，下一次 layout save/reset 归一化。完整 layout 输入必须由 application 检查 widget 格式、known/allowed metric、x/y/w/h/min/max、12 列、重叠、100 项上限。
7. 所有 metadata/revision/result 按 API 文档的 DTO 语义组织，但不产生 HTTP status/header/JSON。以明确领域 error code/field path 表示 validation、conflict、not found、recovery/busy/idempotency。

## 4. 验收与测试

- 真实 active profile SQLite 路径覆盖 system list/detail、custom create→preview→replace→reopen、cycle/referenced archive、system visibility、layout save/reset/conflict 和 profile 隔离。
- record create/edit/trash/restore 后重新 dashboard read，验证 dataRevision 增一次、数值/状态更新、同 response 没有 snapshot 撕裂；hidden response 做深层数值字段 absence 断言。
- 验证 recovery mode 不进入 repository、stale revision/同 key replay无重复 version 或 layout mutation、候选 formula 失败不产生孤儿 formula。
- 必跑 `./mvnw.cmd -q '-Dmaven.compiler.fork=true' test package`；不适用 HTTP/Vue/Electron/发布包。

## 5. 禁止事项、升级与完成报告

禁止把 JSON/HttpExchange/SQL 泄入 application，做跨请求结果缓存，改变已固定 AST/期间/系统指标/布局口径，或以两个 revision 冒充 metric ETag。若需要改变 `ProfileApplicationService` 锁策略、operation/dataRevision 全局行为或 API/DLL 规则，停止并升级。

完成报告应分列 application unit/integration 测试数、真实 SQLite/reopen/profile 隔离证据、dataRevision/idempotency/hidden-projection 证据和不适用层。
