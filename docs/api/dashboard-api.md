# Dashboard API v1

- 状态：中级规格已接受，P5-004/P5-005/P5-007 的唯一契约来源
- 日期：2026-09-15
- 根路径：`/api/v1`
- 上游：[全局 API](../api.md)、[指标模块](../modules/metrics-formulas.md)、[P5 技术设计](../design/P5-metrics-formulas-dashboard-technical-design.md)、[ADR-012](../decisions/ADR-012-gridstack-dashboard-layout.md)

## 1. 范围与查询边界

Dashboard 是唯一的财务总览聚合读取入口。Vue 不得用 records/accounts/metrics collection 拼接指标，也不得发起按卡片数量增长的请求。所有请求隐含 active profile，不接收 `profileId`、原始 `start/end`、SQL filter、layout breakpoint 或任何隐藏卡片明文开关。

本接口只返回当前 `dashboardEnabled=true` 的 ACTIVE metric 卡片和 `financial-overview/desktop` 布局。资产、分摊、预警、报告、原始记录、公式 AST、记录 note、账户完整列表和跨 profile 信息不在响应中。

## 2. Dashboard 读取

### `GET /dashboard?granularity=<>&anchor=<>`

两个 query 均必填且只出现一次：

| key | 类型 | 规则 |
| --- | --- | --- |
| `granularity` | DAY/WEEK/MONTH/YEAR | 大写枚举 |
| `anchor` | `YYYY-MM-DD` | 合法 LocalDate；Java 规范化到其日/周/月/年 |

未知、重复、空白 query key 或 future/invalid date syntax 都返回 400 `VALIDATION_FAILED`。未来 anchor 本身合法，响应使用 FUTURE 状态；它不是 404。成功响应 `Cache-Control:no-store`，状态 200：

```json
{
  "data": {
    "period": {
      "granularity":"MONTH",
      "anchor":"2026-09-15",
      "start":"2026-09-01",
      "endExclusive":"2026-10-01",
      "asOf":"2026-09-15",
      "label":"2026年9月"
    },
    "previousPeriod": {
      "start":"2026-08-01",
      "endExclusive":"2026-09-01",
      "label":"2026年8月"
    },
    "comparisonWindow": {
      "start":"2026-08-01",
      "endExclusive":"2026-08-16",
      "asOf":"2026-08-15",
      "label":"2026年8月1日—15日"
    },
    "layout": {
      "viewKey":"financial-overview",
      "breakpoint":"desktop",
      "revision":3,
      "items":[{"widgetId":"metric:income","x":0,"y":0,"w":3,"h":2,"minW":3,"minH":2,"maxW":12,"maxH":6,"persisted":true}]
    },
    "cards":[{
      "widgetId":"metric:income",
      "metric":{"id":"income","name":"收入","description":"统计期间内按发生日确认的收入。","displayFormat":"CURRENCY","precision":2,"periodBehavior":"PERIOD","isSystem":true},
      "presentation":{"hidden":false},
      "value":{"value":"2000.00","displayFormat":"CURRENCY","precision":2,"dataStatus":"READY"},
      "previousValue":{"value":"1800.00","displayFormat":"CURRENCY","precision":2,"dataStatus":"READY"},
      "change":{"absolute":"200.00","percent":"11.11111111111111111111111111111111","dataStatus":"READY"},
      "trend":[{"start":"2026-09-01","endExclusive":"2026-09-02","label":"9月1日","value":"0.00","dataStatus":"READY"}],
      "breakdown":[],
      "explanation":"按发生日汇总收入。"
    }]
  },
  "meta":{"dataRevision":42}
}
```

### 2.1 Period and comparison semantics

- DAY is `[anchor,anchor+1)` and previous/comparison is the full preceding day.
- WEEK begins Monday and is seven days; MONTH/YEAR are natural calendar periods.
- A fully historical period has `asOf=endExclusive-1 day`; current or overlapping future period has `asOf=min(today,endExclusive-1 day)`; fully future period has `asOf:null`.
- `previousPeriod` always identifies the complete preceding natural period. `comparisonWindow` is the effective value-comparison range: historical periods use previousPeriod; incomplete current WEEK/MONTH/YEAR use the same elapsed count beginning at previousPeriod.start, capped at its end. DAY uses the full preceding day.
- Client titles use returned labels/boundaries, never reimplement week start, month length, leap year or current-period comparison rules.

### 2.2 Metric values, change and privacy

`MetricValue` has `value` canonical decimal string/null, displayFormat, precision and `dataStatus` READY/EMPTY/NOT_COMPUTABLE/DEPENDENCY_UNAVAILABLE/FUTURE. READY normally has a value; non-ready has null. `change` has absolute/percent decimal string/null and the same dataStatus semantics. Percent is unrounded DECIMAL128 result; Vue only formats it to the metric precision and must never use it as a calculation input.

If `presentation.hidden=true`, every numeric leaf in value, previousValue, change, trend and breakdown is omitted rather than null or masked. Compute statuses and static metric metadata remain available. This does not change server-side dependency calculation and cannot be bypassed with another dashboard query or metric detail endpoint.

### 2.3 Trend and breakdown shape

`trend` is always an array, but may be empty. PERIOD metric cards return continuous buckets for WEEK (7 days), MONTH (each calendar day) and YEAR (12 months). DAY returns `trend:[]` because the source has no time-of-day fact. AS_OF and MIXED cards return `trend:[]` in P5 rather than fabricated point-in-time history.

`breakdown` is always an array. For DAY and expense metrics it may contain up to ten descending category items plus at most one `{kind:"OTHER",label:"其他"}` item. Each normal item is `{kind:"CATEGORY",categoryId,label,value,dataStatus}`. Non-expense cards return `[]`. No bucket/breakdown entry returns record IDs, account IDs or notes.

An enabled metric that has no saved layout item is included with a deterministic virtual layout item (`persisted:false`) placed after stored items in the first non-overlapping grid location; all clients see the same position. It is written only when a successful PUT layout explicitly includes it.

### 2.4 Error behavior

Missing capability/recovery maps to 423, bad/missing auth to 401, and malformed query to 400. A single metric formula that cannot compute remains 200 with its card dataStatus. Database failure returns global 503. Dashboard does not use If-Match, idempotency or collection ETag because it is pure read; `layout.revision` and meta.dataRevision let the client reject stale edit state.

## 3. Read layout

### `GET /dashboard/layout`

No query/body. Success 200 returns:

```json
{
  "data":{"layout":{
    "viewKey":"financial-overview",
    "breakpoint":"desktop",
    "revision":3,
    "items":[{"widgetId":"metric:income","x":0,"y":0,"w":3,"h":2,"minW":3,"minH":2,"maxW":12,"maxH":6,"persisted":true}]
  }},
  "meta":{"dataRevision":42}
}
```

Response has strong `ETag:"3"`. It returns stored items plus deterministic virtual items for any newly dashboard-enabled metric, using the same ordering/placement as GET dashboard. Virtual items have `persisted:false`; stored items have true.

## 4. Replace layout

### `PUT /dashboard/layout`

Headers: JSON, X-Request-Id, Idempotency-Key and `If-Match:"<layout.revision>"` required. Body is closed and contains a complete intended layout:

```json
{
  "items":[
    {"widgetId":"metric:income","x":0,"y":0,"w":3,"h":2,"minW":3,"minH":2,"maxW":12,"maxH":6},
    {"widgetId":"metric:total-expense","x":3,"y":0,"w":3,"h":2,"minW":3,"minH":2,"maxW":12,"maxH":6}
  ]
}
```

Rules:

- items length is 1–100; widgetId must be exactly `metric:` plus an ACTIVE dashboard-enabled metric ID. Every enabled metric must occur once, and no disabled/archived/unknown metric item may occur.
- `x` 0–11, `y>=0`, `w` 1–12, `h>=1`, `x+w<=12`; min/max meet the database constraints and current `w/h` are within them. P5 card item minima are 3×2 and maxima 12×6; clients cannot lower them.
- Rectangles may touch edges but must not overlap. Java applies exactly `(a.x < b.x+b.w && b.x < a.x+a.w && a.y < b.y+b.h && b.y < a.y+a.h)` after validating bounds.
- Array order is irrelevant to storage; response normalizes to y, x, widgetId. Java does not silently compact, swap, resize or drop submitted rectangles. GridStack is responsible for a valid edit draft before save.
- On a successful update Java atomically deletes/reinserts `dashboard_layout_item`, increments layout revision and ledger dataRevision, writes operation, then returns 200, ETag of new layout revision and complete persisted layout. A repeated same idempotency key returns the exact original 200/ETag/body.

Stale ETag returns 409 `REVISION_CONFLICT` with `{currentRevision:<layout revision>}`. Missing If-Match is 428. Bounds, sizes, enabled set mismatch, duplicate widget ID or overlap returns 409 `LAYOUT_INVALID`; `details` contains bounded `widgetIds` and a stable reason `OUT_OF_BOUNDS|SIZE_CONSTRAINT|DUPLICATE_WIDGET|WIDGET_NOT_ENABLED|MISSING_ENABLED_WIDGET|OVERLAP`, never client DOM coordinates beyond the submitted items.

## 5. Reset layout

### `POST /dashboard/layout/reset`

Headers are the same as PUT, including If-Match and Idempotency-Key. Body must be exactly `{}`. Java builds the deterministic default layout from the eight default system metrics plus any currently enabled custom metrics appended in first-fit order, replaces all items atomically, increments layout/data revisions and returns 200 with ETag/new layout. This endpoint must not delete or change metric definitions/visibility.

## 6. Capability and client behavior

`system/status` adds `dashboard.read` only once GET dashboard has a real application/persistence path, and adds `dashboard.layout.write` only when GET/PUT/reset layout are all wired. Vue shows the editable total overview only when `dashboard.read` exists; a missing capability is not an empty dashboard. Edit controls additionally require `dashboard.layout.write`.

The client starts a layout draft from `GET /dashboard`/`GET /dashboard/layout` revision, disables navigation/metric mutations while save is pending, and on 409 retains the draft with a “布局已在其他操作中更新，请重新加载” choice. On network timeout it retains the same full body/If-Match/idempotency key for user-controlled retry or operation lookup; it must not manufacture a new key.

## 7. Contract fixtures and test minimum

Fixtures must cover all four granularities, Monday normalization, month-end/leap year, full history/current partial/future period, occurred-vs-settlement distinction, zero/hidden/non-ready value projection, continuous zero/future buckets, virtual item placement, stale layout ETag, every LAYOUT_INVALID reason, reset, idempotency replay and profile switch invalidation. Browser tests assert exact query strings, required headers and bodies; Java tests assert the response snapshot comes from real SQLite facts.
