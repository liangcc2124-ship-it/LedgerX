# Metrics 与 Formulas API v1

- 状态：中级规格已接受，P5-004/P5-005 的唯一 HTTP 契约来源
- 日期：2026-09-15
- 根路径：`/api/v1`
- 上游：[全局 API](../api.md)、[模块规格](../modules/metrics-formulas.md)、[P5 技术设计](../design/P5-metrics-formulas-dashboard-technical-design.md)

## 1. 范围与公共规则

本规范只覆盖 system/custom metric、公式验证/预览、公式历史和 metric 归档。所有请求隐含 active profile；任何 body/query 均不得包含 `profileId`。资产、分摊、direct metric、预警和报表字段属于闭合对象以外字段，必须返回 `400 VALIDATION_FAILED`，不能静默忽略。

除全局 API 已规定的认证、Origin、request ID、1 MiB body 上限、envelope、金额 decimal string、幂等和 `If-Match` 外，本资源使用下列 ID：

- 系统 metric ID 是数据库 seed 的小写稳定字符串，如 `income`、`net-assets`；不能由客户端创建、更新定义或归档。
- 自定义 metric ID 必须匹配 `custom-` 加小写 UUID v4，例如 `custom-3d407ca8-ec1c-4a48-8bd6-edc34c7894b3`。
- formula ID、formula version ID、category/account reference ID 都是小写 UUID v4；URI 中 metric ID 不做 UUID 假设。

所有 mutation 成功都递增 `ledger_meta.data_revision` 一次，写入当前 profile 的 `processed_operation`，并按全局规则重放同一个幂等键。validate、preview、GET 永远不写 definition/version/dependency/layout，也不建立 operation 记录。

## 2. 资源投影

### 2.1 `MetricSummary`

```json
{
  "id":"income",
  "name":"收入",
  "description":"统计期间内按发生日确认的收入。",
  "displayFormat":"CURRENCY",
  "precision":2,
  "periodBehavior":"PERIOD",
  "isSystem":true,
  "status":"ACTIVE",
  "visibility":{"hidden":false,"dashboardEnabled":true},
  "currentFormulaVersion":null,
  "revision":0,
  "createdAt":"2026-09-15T01:00:00Z",
  "updatedAt":"2026-09-15T01:00:00Z"
}
```

`status` 只能为 ACTIVE/ARCHIVED。`currentFormulaVersion` 是 `{formulaId,versionId,version,createdAt}` 或 null；collection 不返回 AST/tokens。`hidden` 只控制 dashboard 数值投影，不能关闭计算或依赖；`dashboardEnabled` 只控制 dashboard 卡片，不归档 metric。

### 2.2 `MetricDetail`

GET 单项时在 Summary 基础上增加：

```json
{
  "formula": {
    "formulaId":"9c9d2d1f-2dc0-4bfe-bc55-e5bf266aa226",
    "versionId":"0c663855-13bb-48b5-8c1a-6a06b4738639",
    "version":2,
    "ast":{"schemaVersion":1,"root":{"kind":"REF","referenceKind":"METRIC","key":"income"}},
    "tokens":[{"type":"reference","value":"METRIC:income","path":"formula.ast.root"}],
    "dependencies":[{"kind":"METRIC","key":"income","label":"收入"}],
    "createdAt":"2026-09-15T01:00:00Z"
  }
}
```

系统 metric 的 `formula` 为 null。`label` 是服务器按当前 active profile 名称补充的显示字段，不能回传作为稳定 key 或授权依据。

### 2.3 `FormulaDraft`

```json
{
  "ast": {"schemaVersion":1,"root":{"kind":"MULTIPLY","children":[
    {"kind":"SAFE_DIVIDE","children":[
      {"kind":"REF","referenceKind":"METRIC","key":"net-result"},
      {"kind":"REF","referenceKind":"METRIC","key":"income"}
    ]},
    {"kind":"CONSTANT","value":"100"}
  ]}}
}
```

AST root is exactly `{schemaVersion:1,root:<FormulaNode>}`. FormulaNode is one of:

| kind | required fields | allowed values |
| --- | --- | --- |
| `CONSTANT` | `value` | canonical decimal, abs ≤ `10^15`, scale ≤8 |
| `REF` | `referenceKind`,`key` | kind METRIC/CATEGORY_INCOME/CATEGORY_EXPENSE/ACCOUNT_BALANCE/TIME |
| `ADD/SUBTRACT/MULTIPLY/DIVIDE/...` | `children` | ADD/SUBTRACT/MULTIPLY/DIVIDE exactly 2; NEGATE 1; MIN/MAX/AVG 2–32; ROUND 1–2; ABS 1; CLAMP 3; SAFE_DIVIDE 2 |

写入请求只接受 `formula.ast`，不能提交 `formula.tokens`、依赖列表或其他派生字段；服务端会校验 AST，并从 AST 生成只读的 canonical tokens 与 dependencies。返回 token 的字段为 `type/value/path`，类型为 constant/reference/operator/punctuation，操作符使用 ADD/SUBTRACT/MULTIPLY/DIVIDE/NEGATE 等稳定名称。这样 Vue 中的中文提示（如“安全除法”）只在构建 AST 时映射为 `SAFE_DIVIDE`，不会成为数据库事实。

TIME keys are exactly `period-days`,`elapsed-days`,`complete-months`. CATEGORY and ACCOUNT references must be active at the time a new draft is validated/saved. A saved reference remains valid if its target is later archived. METRIC references can target a system metric or an active custom metric only.

## 3. List and read metrics

### 3.1 `GET /metrics`

Query:

| key | values/default | validation |
| --- | --- | --- |
| `status` | ACTIVE default, ARCHIVED | exactly one enum |
| `dashboardEnabled` | omitted, `true`, `false` | exactly one boolean literal |
| `limit` | 50, 1–200 | global pagination rule |
| `cursor` | omitted or opaque cursor | bound to profile, filter and dataRevision |

Success 200 returns `{data:{items:[MetricSummary],page},meta:{dataRevision}}`; item order is `isSystem DESC, name COLLATE NOCASE ASC, id ASC`. The response has no ETag because collection mutation uses entity/layout ETags. Unknown or repeated scalar query keys return 400.

### 3.2 `GET /metrics/{metricId}`

Only an ACTIVE metric is visible without a status query; `?status=ARCHIVED` reads an archived custom metric. System metrics cannot be archived. Success 200 returns `{data:{metric:MetricDetail},meta:{dataRevision}}` and strong `ETag:"<metric.revision>"`. Missing/status mismatch is 404. The detail contains no calculated dashboard value; callers use `/dashboard` or `/formulas/preview` for values.

## 4. Create custom metric

### `POST /metrics`

Headers: JSON, X-Request-Id, Idempotency-Key required. The request body is closed:

```json
{
  "id":"custom-3d407ca8-ec1c-4a48-8bd6-edc34c7894b3",
  "name":"餐饮占比",
  "description":"当前期间餐饮及其子分类支出占总支出的比例。",
  "displayFormat":"PERCENT",
  "precision":1,
  "visibility":{"hidden":false,"dashboardEnabled":true},
  "formula": {"ast":{"schemaVersion":1,"root":{"kind":"CONSTANT","value":"0"}}}
}
```

Rules:

- ID must be unused custom ID; name is trimmed but original body must not have leading/trailing whitespace, preventing ambiguous idempotency hash. Description may be empty but not whitespace-only if nonempty.
- display format/precision follow §2.1; CURRENCY=2 and INTEGER=0. `periodBehavior` is not supplied: Java derives it from dependencies.
- Formula is mandatory, produces an owned formula definition and version 1. Formula and version UUIDs are server-generated and included in the result; client never chooses them.
- System display names and all active metric names are reserved case-insensitively. Formula graph uses candidate metric ID, so direct self-reference and indirect cycles are rejected before any write.

Success is 201 with Location `/api/v1/metrics/{id}`, `ETag:"0"`, `{data:{metric:MetricDetail},meta:{dataRevision}}`. It atomically inserts metric definition, formula definition/version/dependencies, visibility and operation. `dashboardEnabled=true` does not automatically mutate layout in this request; the next dashboard projection exposes the enabled metric with a deterministic unplaced/default item, and user layout is normalized on its next save/reset.

## 5. Replace metric or visibility

### `PUT /metrics/{metricId}`

Headers: JSON, Idempotency-Key, `If-Match:"<metric.revision>"` required.

For a custom metric, body is the same closed shape as POST except no `id`; it is a complete replacement and always creates a new formula version, even when the AST is semantically unchanged. The metric revision increments by one, `updatedAt` changes, and the response is 200 with the new ETag/detail/dataRevision.

For a system metric, body is exactly:

```json
{"visibility":{"hidden":false,"dashboardEnabled":true}}
```

Name, description, format, precision and formula fields are rejected at `body`. Every visibility mutation increments both `metric_visibility.revision` and its owning `metric_definition.revision`, and updates both timestamps. The resulting metric definition revision is the single strong ETag for `GET` and `PUT`, including system metrics; system definitions themselves remain immutable. Response meta always carries dataRevision.

Any candidate formula cycle returns 409 `FORMULA_CYCLE`, details `{metricIds:[...]}`. Formula node/type/reference faults return 400 `FORMULA_INVALID` with stable `fieldErrors` rooted at `formula.ast`; a submitted `formula.tokens` field is rejected at `formula.tokens`. Revision mismatch returns the global 409 `REVISION_CONFLICT` with `currentRevision`; failed writes leave old version and visibility untouched.

## 6. Archive custom metric

### `DELETE /metrics/{metricId}`

Requires Idempotency-Key and matching If-Match. System metric returns 409 `REFERENCE_CONFLICT` with `details.reason=SYSTEM_METRIC`. An active custom metric referenced by another active custom formula returns 409 `REFERENCE_CONFLICT`, `details.reason=METRIC_REFERENCED`, and a bounded `details.metricIds` list sorted by ID. Unreferenced ACTIVE custom metric is marked archived, its visibility is set `dashboardEnabled=false`, its own formula history remains readable via `?status=ARCHIVED`, and dataRevision increments once.

Success 200 returns the archived metric detail and a new ETag. Repeated archive/status mismatch is 404; no restore or physical delete endpoint exists.

## 7. Validate and preview formula drafts

### 7.1 Shared body

Both endpoints accept this exact object:

```json
{
  "candidateMetricId":"custom-3d407ca8-ec1c-4a48-8bd6-edc34c7894b3",
  "displayFormat":"PERCENT",
  "formula": {"ast":{"schemaVersion":1,"root":{"kind":"CONSTANT","value":"0"}}},
  "granularity":"MONTH",
  "anchor":"2026-09-15"
}
```

`candidateMetricId` is required when validating a create/update candidate, must be an existing active custom ID or a syntactically valid unused custom ID, and lets Java detect self/cycles. `displayFormat` is required to validate format/precision compatibility. `granularity`/`anchor` are required for deterministic preview and validate response period; validate does not compute metric values.

### 7.2 `POST /formulas/validate`

Success 200 returns:

```json
{
  "data":{
    "valid":true,
    "formula":{"ast":{},"tokens":[]},
    "dependencies":[{"kind":"METRIC","key":"income","label":"收入"}],
    "periodBehavior":"PERIOD",
    "period":{"granularity":"MONTH","start":"2026-09-01","endExclusive":"2026-10-01","asOf":"2026-09-15"}
  },
  "meta":{"dataRevision":42}
}
```

Structure/reference/type/depth/node/arity/cycle invalidity is 400 `FORMULA_INVALID` or 409 `FORMULA_CYCLE`; no partial `valid:false` 200 response exists. The response contains the canonical AST/tokens and resolved dependencies; save repeats the same validation and never trusts client tokens.

### 7.3 `POST /formulas/preview`

Uses the shared body and validation behavior. Success 200 adds:

```json
{
  "value":{"value":"12.5","displayFormat":"PERCENT","precision":1,"dataStatus":"READY"},
  "components":[
    {"kind":"METRIC","key":"income","label":"收入","value":"2000.00","dataStatus":"READY"}
  ]
}
```

Runtime zero divisor, no sample, dependency non-ready or future period is a valid 200 preview with null value and corresponding dataStatus, not a saveable success guarantee. Components are bounded to direct dependencies (max 32), contain no record IDs/notes and hide values for hidden dependencies.

## 8. Read formula versions

### `GET /formulas/{formulaId}/versions`

Query `limit` 50 (1–100) and opaque `cursor`. Formula must belong to a metric readable in the active profile; otherwise 404. Success returns newest-first versions:

```json
{
  "data":{
    "items":[{"id":"...","version":2,"ast":{},"tokens":[],"dependencies":[],"createdAt":"..."}],
    "page":{"nextCursor":null,"hasMore":false,"limit":50}
  },
  "meta":{"dataRevision":42}
}
```

The current version is not mutable through this endpoint. A historical version can be inspected but cannot be activated, deleted or copied without a normal metric PUT; this preserves the immutable-version rule.

## 9. Error map and field paths

| condition | HTTP/code | field path/details |
| --- | --- | --- |
| invalid custom ID/name/format/precision | 400 VALIDATION_FAILED | `id`,`name`,`displayFormat`,`precision` |
| unknown field/incorrect JSON shape | 400 VALIDATION_FAILED | `body` or exact nested path |
| AST schema/node/constant/arity fault, or submitted derived tokens | 400 FORMULA_INVALID | `formula.ast...` or `formula.tokens` |
| unknown/archived reference for new draft | 409 REFERENCE_CONFLICT | `formula.ast...key`, reason `METRIC_NOT_FOUND`, `METRIC_ARCHIVED`, `CATEGORY_NOT_FOUND`, `CATEGORY_ARCHIVED`, `ACCOUNT_NOT_FOUND`, or `ACCOUNT_ARCHIVED` |
| candidate graph contains a cycle | 409 FORMULA_CYCLE | `details.metricIds` |
| reserved/duplicate active name | 409 REFERENCE_CONFLICT | `name`, reason `METRIC_NAME_CONFLICT` |
| archive system/referenced metric | 409 REFERENCE_CONFLICT | reasons in §6 |
| absent/stale ETag | 428/409 global codes | `details.currentRevision` for stale |
| recovery/auth/media/body/idempotency failures | global API codes | no application/repository mutation |

## 10. Contract fixtures and test minimum

P5-005 must add shared JSON fixtures for system list/detail, custom create/update/archive, AST node variants, Chinese alias-normalized tokens, cycle, submitted-derived-token rejection, archived references, version pagination, validate/preview ready and null statuses, ETag/idempotency replays and profile isolation. Java and Vue tests consume the same success/error fixtures where possible. Formula examples must never contain real personal names, account names, amounts or notes.
