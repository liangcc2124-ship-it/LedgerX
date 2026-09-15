# P4-002：基础记录 REST API

- 状态：PARTIAL（基础 HTTP 已实现；金额边界已补；完整筛选/operation fixtures 尚待补齐）
- 单一结果：三类基础记录可通过受认证 `/api/v1/records` 完成列表、详情、新建、完整编辑、移入回收站和恢复，且请求/响应、错误、幂等、ETag 与 SQLite 结果一致。

## 1. 背景、目标、范围与非目标

P4-001 已提供真实 application/SQLite 行为。本任务只把[具体记录 API](../api/ledger-records-api.md)接入 Java HTTP，并建立双方共享 fixtures。

范围：GET/POST `/records`，GET/PUT/DELETE `/records/{id}`，POST `/records/{id}/restore`；JSON DTO、闭合请求校验、filter/cursor、header/status/error/ETag/Location、capabilities、fixtures 和 Java HTTP 集成测试。

非目标：修改领域/数据库规则、Vue/Electron、purge/bulk、dashboard、资产/指标/应付款、文件 endpoint、新依赖或 migration。

## 2. 前置依赖与引用规范

- 前置：P3-001..005 与 P4-001 PASS。
- 必读：全局 AGENTS；[全局 API §1–§10、§12–§13](../api.md)；[具体 API §1–§2、§5–§12](../api/ledger-records-api.md)；[模块规格](../modules/ledger-records.md)；P4-001；现有 profiles/settings/categories/accounts HTTP 代码与测试。
- 复用 LedgerHttpServer 的认证、Host/Origin、body、JSON envelope、request ID、幂等、ETag、cursor/error 模式；不得复制第二套 server 或 envelope。

## 3. 允许/预计修改的模块和文件

- `src/main/java/com/ledgerx/http/LedgerHttpServer.java`：仅记录路由、解析、响应和错误映射。
- P4-001 的 application record DTO/result 类型：只允许为 HTTP 映射补充不改变语义的访问器；业务规则仍在 application/domain。
- `src/main/java/com/ledgerx/application/profile/ProfileApplicationService.java`：仅在 HTTP 接线确有需要时增加 records.read/records.write capability；不得重写 P4-001 行为。
- `src/test/java/com/ledgerx/http/RecordsHttpServerTest.java` 及直接相关 helper。
- `docs/contracts/api-v1/records/` 下精确成功/失败 fixtures。
- 本任务、具体 API、`docs/verification/P4-002-basic-records-rest.md`。

禁止修改 frontend/electron、SQL/migration、pom、分类/账户契约、全局 API、旧 WPF/React。

## 4. 实现步骤与接口契约

1. 只有 application 记录行为通过真实依赖注入时，system status capabilities 才增加 `records.read` 和 `records.write`。不得提前宣称 dashboard/assets/metrics/backup。
2. GET `/records` 只接受 status、occurredFrom、occurredToExclusive、重复 recordType/categoryId/accountId、query、amountMin/amountMax、limit、cursor。默认 status ACTIVE、limit 50；排序和 cursor 绑定严格按具体 API。未知或重复单值 query 返回 400。
3. GET `/records/{id}?status=` 返回 `data.record` 和强 ETag；status 不匹配/不存在/高级类型记录统一 404。响应 DTO逐字段匹配具体 API 2.3，不增 metric/allocation/asset 字段。
4. POST body 只接受 `id,occurredOn,recordType,amount,currency,categoryId,settlement,note`；PUT 相同但无 id。body 及 settlement 都是闭合对象，缺失、null、额外字段、非法类型或非字符串 money 为 `400 VALIDATION_FAILED`，fieldErrors 使用精确 JSON path。
5. POST 需要 X-Request-Id、Idempotency-Key 和 JSON Content-Type；成功 201、Location、ETag "0"。PUT 还需 If-Match，成功 200 和新 ETag。
6. DELETE `/records/{id}` 需要 If-Match/Idempotency-Key，无 body；成功 200 返回 TRASHED record/ETag。POST `/records/{id}/restore` 需要相同 headers，body 必须精确 `{}`；成功 200 返回 ACTIVE record/ETag。
7. application 的 validation/reference/state/revision/idempotency/busy 异常按具体 API 和全局错误表映射。401/403 必须发生在 application 调用前；错误不得带 SQL、路径、body、note 或 token。
8. 每个成功 mutation envelope 的 meta.dataRevision 与事务结果一致；ETag 与 body revision 一致。同 key 同请求回放首次 status/body/ETag/Location。
9. purge/bulk 和所有高级路径不注册，必须落入 404 ROUTE_NOT_FOUND；不能返回 501 或假成功。

## 5. 用户操作、反馈、跨层数据和结果

| 用户操作（后续 UI） | HTTP 反馈 | 请求关键字段 | 业务/持久化结果 | 边界 |
| --- | --- | --- | --- | --- |
| 打开记录页 | 200 items/page | status、filter、cursor | 只读 | 空列表、过期 cursor |
| 新增收支 | 201 record/ETag/Location | 闭合 Draft、key | P4-001 单事务保存 | 额外高级字段、非法引用/金额 |
| 编辑 | 200 新 ETag | 完整 Draft、If-Match、key | revision/dataRevision+1 | stale ETag、TRASHED |
| 删除/恢复 | 200 最终状态 | id、If-Match、key、restore `{}` | deletedAt 持久化变化 | 重复操作、归档历史引用 |

前端可直接展示 message/fieldErrors，但 HTTP 层不得自行计算余额或修正 Draft。

## 6. 验收标准

- 六个 endpoint 的合法请求、状态码、headers、envelope 和 fixtures 精确匹配文档；GET 可观察到 P4-001 真实持久化结果。
- 记录 DTO 只有基础字段；闭合 body 对 `metricIds/allocation/fixedAsset`、未知字段和多余 settlement 字段明确 400，数据库零调用或零写入。
- 三类类型、金额 string、category/account/date/note、归档引用、开户日前结算、status filter、amount/date/query filter 均有合同断言。
- 同 key 重放、同 key 异请求、缺 key、缺/过期 If-Match、401、403、415、423、503、非法/过期 cursor 均有最终状态断言。
- trash 后 ACTIVE GET 404/TRASHED GET 200；restore 后相反；ETag/revision/dataRevision/账户余额在真实 SQLite 重读一致。
- 404 路由测试覆盖 purge/bulk 和至少一个高级 endpoint；capabilities 只新增 records.read/write。
- Maven test package 通过并报告实际测试数量。

## 7. 测试层次、命令与真实链路

- HTTP 合同：共享 fixtures、合法/非法 JSON、header、filter/cursor、错误 envelope。
- HTTP→application→SQLite 集成：create/read/replace/trash/restore、账户余额、幂等和完整重开。
- 必跑：仓库根 `./mvnw.cmd -q '-Dmaven.compiler.fork=true' test package`。
- 本任务要求真实 Java HttpServer→application→sqlite-jdbc；Vue/Electron、隔离桌面根、发布包和快捷方式不适用。

## 8. 禁止事项、升级、文档与完成报告

禁止改变 API 字段/状态/错误，使用 mock repository冒充集成证据，注册高级路由，接受 JSON number 金额，忽略闭合 body 额外字段，或在 HTTP 层实现余额/事务。

若 P4-001 exception 无法稳定映射、现有 HTTP 基座不能返回所需 ETag/Location/cursor、需要修改全局 API/数据库/架构或真实 API 与 fixtures 冲突，停止并升级。

完成报告写：修改文件；六个 endpoint 验证；fixture 数与 Java 测试数；真实 HTTP→SQLite 和重开证据；高级路由 404/capability 证据；Vue/Electron/发布包/快捷方式不适用；未测项、限制、风险和文档同步。
