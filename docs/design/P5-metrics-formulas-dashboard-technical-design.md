# P5 指标、公式与财务总览：中级技术设计

- 状态：可拆分为实现 Task Spec；未实现
- 日期：2026-09-15
- 上游约束：[ADR-011](../decisions/ADR-011-enable-metrics-dashboard.md)、[ADR-012](../decisions/ADR-012-gridstack-dashboard-layout.md)、[模块规格](../modules/metrics-formulas.md)

## 1. 摘要

P5 在既有单 profile SQLite 账本之上新增一组只读计算能力和少量定义/布局数据：系统指标由 Java 对现有记录与账户计算；自定义指标保存受限 AST 的不可变版本；dashboard 一次返回当前期间、可比上一窗口、趋势/构成和布局。任何记录写入不同步更新指标；下一次 dashboard 读取以新 `dataRevision` 重算。

本设计不拆服务、不引入 ORM、缓存、前端财务计算或新的 Electron 能力。唯一新增前端运行时依赖是 ADR-012 固定的 `gridstack@13.3.0`，它被局限在一个 Vue 适配组件。

## 2. 实现边界与组件

### 2.1 Java 包与职责

新增 `src/main/java/com/ledgerx/application/metrics/`：

| 类型 | 职责 |
| --- | --- |
| `MetricsApi`、`DashboardApi` | ApplicationRuntime 对 HTTP 暴露的窄接口；不暴露 JDBC 类型 |
| `MetricApiResult`、`DashboardApiResult`、`MetricException` | 沿用现有 API result/exception 模式的 HTTP 无关结果 |
| `MetricDraft`、`FormulaDraft`、`FormulaAst`、`FormulaToken`、`LayoutDraft` | 经 HTTP 映射后的闭合输入；金额/常量保留 string，绝不使用 double |
| `PeriodResolver`、`DashboardPeriod` | 唯一的 DAY/WEEK/MONTH/YEAR、asOf、比较窗口、bucket 边界实现 |
| `FormulaValidator`、`FormulaSerializer`、`FormulaEvaluator` | AST/token 白名单、canonical token 重建、dependency 抽取、图检测与 BigDecimal 求值 |
| `SystemMetricCalculator`、`MetricEvaluationService` | 系统指标、公式拓扑排序、状态传播、previous/trend 计算 |
| `MetricCatalogService`、`DashboardService` | 生命周期、事务编排、DTO 投影；由 ProfileApplicationService 在既有锁内调用 |

新增 `src/main/java/com/ledgerx/persistence/LedgerMetricsRepository.java`。它只包含 definitions/formulas/layout 的 SQL、记录/分类/账户的只读聚合和 schema mapping；不返回 HTTP envelope，不创建 SQLite connection 之外的缓存。`LedgerCatalogRepository` 继续拥有分类、账户和基础记录 mutation，P5 不修改其写入语义。

`ApplicationRuntime` 新增 `MetricsApi`、`DashboardApi` 的委托和 recovery-mode 423 guard；`ProfileApplicationService` 实现两个接口并继续是 active profile、`ReentrantReadWriteLock` 和 dataRevision 的唯一协调者。`LedgerHttpServer` 只做路由、JSON/headers/ETag、DTO 映射和稳定错误投影。

### 2.2 Clock 与业务日期

P5 使用注入的 `Clock`，但 production `ProfileApplicationService.open(..., null)` 必须改用 `Clock.systemDefaultZone()`，不能继续默认 UTC；显式传入的测试 Clock 保留其 zone。PeriodResolver 只以 `LocalDate.now(clock)` 求“今天”。这会同时修正现有账户默认日期的生产时区语义，P5-002 必须重新运行基础记录回归。

### 2.3 单次 dashboard 流程

```text
GET /dashboard?granularity&anchor
  → PeriodResolver(current, previous, comparison, buckets)
  → LedgerMetricsRepository.readFacts(...)
  → SystemMetricCalculator(current / comparison / each bucket)
  → FormulaValidator.loadActiveGraph + MetricEvaluationService
  → DashboardService.project(cards, trend/breakdown, layout)
  → HTTP envelope + current dataRevision
```

`readFacts` 必须对一个 dashboard 请求批量读取，不允许每张卡、每条依赖或每个 bucket 打开连接。公式图只在请求内构建一次，针对 current/comparison/bucket 重复求值。请求读取期间取得 `gate.readLock()`，从同一连接读取 `ledger_meta.data_revision` 和事实；响应 meta 与计算事实属于同一稳定快照。

## 3. V005 迁移与确定性 seed

### 3.1 文件、升级与回滚

- 新文件：`src/main/resources/db/ledger/migration/V005__metrics_formulas_dashboard.sql`。
- 在 `src/main/resources/db/ledger/migration/` 新增 `V005__metrics_formulas_dashboard.sql`；`LedgerBootstrap` 以新常量 `LEDGER_V005="/db/ledger/migration/V005__metrics_formulas_dashboard.sql"` 在现有 V001–V004 后追加它。不得改旧资源内容、顺序或 checksum。
- MigrationRunner 已在一个 SQLite transaction 中执行 V005。失败时整个 V005 和 history 行回滚；成功后没有 down migration，回退程序版本须拒绝 schema 5 数据库。
- V005 只增加 schema/系统数据，不改变 `finance_record`、分类、账户、历史余额或 `ledger_meta.data_revision`。新库中 V005 在 `ledger_meta` 初始插入之前执行亦必须成功。
- P5-001 必须以空库、已执行 V004 的库和含真实特征匿名记录的 V004 副本验证：数据不丢失、重开幂等、schema history=5、system seed 无重复、dataRevision 不变。

### 3.2 精确 DDL

实现必须采用下列等价 DDL（名称、列、约束、索引不可自由改名）：

```sql
CREATE TABLE formula_definition (
    id TEXT PRIMARY KEY NOT NULL,
    scope TEXT NOT NULL CHECK (scope = 'METRIC'),
    result_type TEXT NOT NULL CHECK (result_type IN ('CURRENCY','PERCENT','NUMBER','INTEGER')),
    is_template INTEGER NOT NULL DEFAULT 0 CHECK (is_template IN (0,1)),
    archived_at TEXT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE metric_definition (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 100),
    description TEXT NOT NULL DEFAULT '' CHECK (length(description) <= 500),
    display_format TEXT NOT NULL CHECK (display_format IN ('CURRENCY','PERCENT','NUMBER','INTEGER')),
    precision INTEGER NOT NULL CHECK (precision BETWEEN 0 AND 8),
    period_behavior TEXT NOT NULL CHECK (period_behavior IN ('PERIOD','AS_OF','MIXED')),
    current_formula_version_id TEXT NULL REFERENCES formula_version(id) ON DELETE RESTRICT,
    is_system INTEGER NOT NULL CHECK (is_system IN (0,1)),
    archived_at TEXT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    CHECK ((display_format <> 'CURRENCY' OR precision = 2)
        AND (display_format <> 'INTEGER' OR precision = 0))
);

CREATE TABLE formula_version (
    id TEXT PRIMARY KEY NOT NULL,
    formula_id TEXT NOT NULL REFERENCES formula_definition(id) ON DELETE RESTRICT,
    version INTEGER NOT NULL CHECK (version >= 1),
    ast_json TEXT NOT NULL CHECK (length(ast_json) BETWEEN 2 AND 65536),
    tokens_json TEXT NOT NULL CHECK (length(tokens_json) BETWEEN 2 AND 65536),
    created_at TEXT NOT NULL,
    UNIQUE (formula_id, version)
);

CREATE TABLE formula_dependency (
    formula_version_id TEXT NOT NULL REFERENCES formula_version(id) ON DELETE CASCADE,
    dependency_kind TEXT NOT NULL CHECK (dependency_kind IN ('METRIC','CATEGORY_INCOME','CATEGORY_EXPENSE','ACCOUNT_BALANCE','TIME')),
    dependency_key TEXT NOT NULL CHECK (length(dependency_key) BETWEEN 1 AND 120),
    referenced_metric_id TEXT NULL REFERENCES metric_definition(id) ON DELETE RESTRICT,
    referenced_category_id TEXT NULL REFERENCES category(id) ON DELETE RESTRICT,
    referenced_account_id TEXT NULL REFERENCES financial_account(id) ON DELETE RESTRICT,
    PRIMARY KEY (formula_version_id, dependency_kind, dependency_key),
    CHECK (
        (dependency_kind = 'METRIC' AND referenced_metric_id IS NOT NULL AND referenced_category_id IS NULL AND referenced_account_id IS NULL)
        OR (dependency_kind IN ('CATEGORY_INCOME','CATEGORY_EXPENSE') AND referenced_metric_id IS NULL AND referenced_category_id IS NOT NULL AND referenced_account_id IS NULL)
        OR (dependency_kind = 'ACCOUNT_BALANCE' AND referenced_metric_id IS NULL AND referenced_category_id IS NULL AND referenced_account_id IS NOT NULL)
        OR (dependency_kind = 'TIME' AND referenced_metric_id IS NULL AND referenced_category_id IS NULL AND referenced_account_id IS NULL)
    )
);

CREATE TABLE metric_visibility (
    metric_id TEXT PRIMARY KEY NOT NULL REFERENCES metric_definition(id) ON DELETE CASCADE,
    hidden INTEGER NOT NULL DEFAULT 0 CHECK (hidden IN (0,1)),
    dashboard_enabled INTEGER NOT NULL DEFAULT 0 CHECK (dashboard_enabled IN (0,1)),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at TEXT NOT NULL
);

CREATE TABLE dashboard_layout (
    id TEXT PRIMARY KEY NOT NULL,
    view_key TEXT NOT NULL CHECK (view_key = 'financial-overview'),
    breakpoint TEXT NOT NULL CHECK (breakpoint = 'desktop'),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    updated_at TEXT NOT NULL,
    UNIQUE (view_key, breakpoint)
);

CREATE TABLE dashboard_layout_item (
    layout_id TEXT NOT NULL REFERENCES dashboard_layout(id) ON DELETE CASCADE,
    widget_id TEXT NOT NULL,
    x INTEGER NOT NULL CHECK (x BETWEEN 0 AND 11),
    y INTEGER NOT NULL CHECK (y >= 0),
    w INTEGER NOT NULL CHECK (w BETWEEN 1 AND 12),
    h INTEGER NOT NULL CHECK (h >= 1),
    min_w INTEGER NOT NULL CHECK (min_w BETWEEN 1 AND 12),
    min_h INTEGER NOT NULL CHECK (min_h >= 1),
    max_w INTEGER NOT NULL CHECK (max_w BETWEEN 1 AND 12),
    max_h INTEGER NOT NULL CHECK (max_h >= 1),
    PRIMARY KEY (layout_id, widget_id),
    CHECK (x + w <= 12),
    CHECK (min_w <= w AND w <= max_w),
    CHECK (min_h <= h AND h <= max_h)
);

CREATE UNIQUE INDEX ux_metric_definition_active_name
    ON metric_definition(name COLLATE NOCASE) WHERE archived_at IS NULL;
CREATE INDEX idx_formula_version_formula_version_desc
    ON formula_version(formula_id, version DESC);
CREATE INDEX idx_formula_dependency_metric ON formula_dependency(referenced_metric_id);
CREATE INDEX idx_formula_dependency_category ON formula_dependency(referenced_category_id);
CREATE INDEX idx_formula_dependency_account ON formula_dependency(referenced_account_id);
CREATE INDEX idx_metric_definition_active_name
    ON metric_definition(archived_at, name COLLATE NOCASE);
```

`metric_definition` references `formula_version` before that table is created; SQLite accepts the forward foreign-key declaration. New custom metrics are inserted with `current_formula_version_id=NULL`, then their definition/version/dependency rows are inserted and the pointer is set in the same transaction. Java validates this cross-table ownership before commit.

### 3.3 System metric seed

All timestamps use `strftime('%Y-%m-%dT%H:%M:%fZ','now')`; the static layout ID is `00000000-0000-0000-0000-000000000005`. V005 inserts every row exactly once:

| ID | name | format / precision | behavior | dashboard enabled |
| --- | --- | --- | --- | --- |
| income | 收入 | CURRENCY / 2 | PERIOD | yes |
| fixed-expense | 固定支出 | CURRENCY / 2 | PERIOD | yes |
| variable-expense | 弹性支出 | CURRENCY / 2 | PERIOD | yes |
| total-expense | 总支出 | CURRENCY / 2 | PERIOD | yes |
| net-result | 净收支 | CURRENCY / 2 | PERIOD | yes |
| savings-rate | 储蓄率 | PERCENT / 1 | PERIOD | yes |
| cash-inflow | 现金流入 | CURRENCY / 2 | PERIOD | no |
| cash-outflow | 现金流出 | CURRENCY / 2 | PERIOD | no |
| net-cash-flow | 净现金流 | CURRENCY / 2 | PERIOD | no |
| available-cash | 可用现金 | CURRENCY / 2 | AS_OF | yes |
| total-assets | 资产合计 | CURRENCY / 2 | AS_OF | no |
| total-liabilities | 负债合计 | CURRENCY / 2 | AS_OF | no |
| net-assets | 净资产 | CURRENCY / 2 | AS_OF | yes |
| average-daily-expense | 日均支出 | CURRENCY / 2 | PERIOD | no |
| fixed-expense-ratio | 固定支出占比 | PERCENT / 1 | PERIOD | no |
| variable-expense-ratio | 弹性支出占比 | PERCENT / 1 | PERIOD | no |
| transaction-count | 记录笔数 | INTEGER / 0 | PERIOD | no |

Every system metric has `is_system=1` and `current_formula_version_id=NULL`; its formula lives in `SystemMetricCalculator`, not a mutable database formula. V005 inserts one `metric_visibility` row for every metric with `hidden=0`; no future startup upsert may overwrite user visibility. A visibility mutation is one transaction: increment `metric_visibility.revision` and its timestamp, then increment the owning `metric_definition.revision` and its timestamp. The latter is the sole metric HTTP ETag for both custom and system metrics.

The initial layout contains only the eight enabled rows, each `widget_id='metric:<id>'`, `min_w=3,min_h=2,max_w=12,max_h=6`:

| metric | x | y | w | h |
| --- | --- | --- | --- | --- |
| income | 0 | 0 | 3 | 2 |
| total-expense | 3 | 0 | 3 | 2 |
| net-result | 6 | 0 | 3 | 2 |
| savings-rate | 9 | 0 | 3 | 2 |
| fixed-expense | 0 | 2 | 3 | 2 |
| variable-expense | 3 | 2 | 3 | 2 |
| available-cash | 6 | 2 | 3 | 2 |
| net-assets | 9 | 2 | 3 | 2 |

## 4. Data access and calculation rules

### 4.1 Fact reads

`LedgerMetricsRepository` opens the active ledger database only through its normalized file path and parameterized statements. It must provide one read model containing:

- current/comparison/bucket income, fixed cost, variable cost and record count by `occurred_on`;
- current/comparison/bucket cash inflow/outflow by `settlement_on`;
- category income/expense aggregates for all referenced category IDs, including their current direct children; and
- account opening data plus record-based balance deltas as of all needed dates.

No query may filter out archived accounts from total-assets/total-liabilities; `available-cash` alone only includes currently ACTIVE accounts with `include_in_available_cash=1`. A saved `ACCOUNT_BALANCE` reference includes its archived account. All queries only include active records, `settlement_mode='PAID_FROM_ACCOUNT'`, and `INCOME|FIXED_COST|VARIABLE_COST`.

The repository may issue a bounded constant number of aggregate queries plus a query per distinct required asOf date, never one per card or formula node. P5-003 must capture `EXPLAIN QUERY PLAN` against existing record indexes and show no full table scan caused by unindexed join/filter mistakes.

### 4.2 System formulas and status

Amounts are read as `long amount_minor`, converted exactly to `BigDecimal` at the domain boundary, and returned as canonical decimal strings. System formula order is:

```text
income, fixed-expense, variable-expense, cash-inflow, cash-outflow,
available-cash, total-assets, total-liabilities, transaction-count
  → total-expense, net-result, net-cash-flow, net-assets,
    average-daily-expense, savings-rate, fixed-expense-ratio, variable-expense-ratio
```

No matching active record in an effective current/historical range is READY `0`. Divisors of zero produce NOT_COMPUTABLE/null. A formula result propagates the highest non-ready dependency state: FUTURE, DEPENDENCY_UNAVAILABLE, NOT_COMPUTABLE, then EMPTY. `change.absolute = current - previous` only when both values are READY; `change.percent = safeDivide(absolute, abs(previous))*100` only when previous is nonzero. `change.status` independently explains unavailable comparison.

### 4.3 Formula graph

The database stores AST root `{schemaVersion:1,root:<node>}`. Node forms are closed:

```json
{"kind":"CONSTANT","value":"12.5"}
{"kind":"REF","referenceKind":"METRIC","key":"income"}
{"kind":"ADD","children":[<node>,<node>]}
{"kind":"ROUND","children":[<node>,<node>]}
```

`referenceKind` is one of METRIC, CATEGORY_INCOME, CATEGORY_EXPENSE, ACCOUNT_BALANCE, TIME. `operator` and `function` values are exactly those in the module spec. `tokens` is a canonical infix token sequence generated by `CanonicalFormulaTokens` from AST. API submissions include only AST; Java regenerates tokens and dependencies, persists the regenerated values, and rejects a client-supplied `formula.tokens` field. Labels are reconstructed from current names for UI responses.

Graph construction loads active custom metrics and their current versions. A candidate create/update substitutes only its candidate edges before DFS color-cycle detection; the returned `FORMULA_CYCLE` details contain only `metricIds`, never names. Topological order is stable by metric ID. Formula version `n+1` is inserted before its metric pointer changes; pointers and dependency rows cannot be observed half-updated.

## 5. Dashboard and API projection design

`DashboardService` returns data that is sufficient for compact, medium and expanded card tiers without returning original records or AST. It includes:

- normalized `period` (granularity, anchor, start, endExclusive, asOf, label), `previousPeriod`, and `comparisonWindow`;
- `layout` with ETag/revision and API layout items;
- cards sorted by layout y/x/widget ID, containing metric metadata, `presentation.hidden`, current/previous `MetricValue`, change, optional period trend and optional expense breakdown;
- `meta.dataRevision` read from the same snapshot.

Trend points are continuous: WEEK returns seven daily buckets; MONTH returns every day of the calendar month; YEAR returns twelve monthly buckets. Past empty buckets are READY 0; entirely future buckets are FUTURE/null. DAY omits trend, and may return up to ten expense category breakdown items plus a single `OTHER` aggregate item. AS_OF cards do not synthesize point-in-time bucket histories in P5; expanded cards use prior comparison and explanation.

For a hidden card the service computes internally, but returns `presentation.hidden=true` and omits every numeric value, previous value, change number, trend number and breakdown amount. It returns the compute status separately so the UI can distinguish “hidden” from “cannot calculate”; no alternate field may disclose the number.

## 6. Vue design and GridStack adapter

P5 adds, at minimum, `OverviewPage.vue`, `MetricEditorModal.vue`, `FormulaBuilder.vue`, `DashboardGrid.vue`, `MetricCard.vue`, `frontend/src/metricsApi.js` (or tightly scoped additions to `apiClient.js`) and browser specs. `App.vue` replaces the current placeholder home with OverviewPage; profiles, catalog, records and settings remain intact.

`DashboardGrid.vue` receives immutable server layout, `editing` and card slot data. It creates a local GridStack instance through the official Vue wrapper, disables move/resize outside edit mode, and emits only normalized draft items. The parent owns draft, save/cancel/reset and mutation retry state. The adapter must destroy/unsubscribe on unmount and ignore library events while loading a server layout to prevent feedback loops.

FormulaBuilder does not execute or parse server truth in JavaScript. It assists user entry by mapping Chinese aliases to permitted token types, keeps the draft AST/token structure, calls validate/preview with debounce/cancellation, and displays server errors at the relevant token or form field. Metric cards format decimal strings for display only; no `Number()` conversion may affect calculation or outgoing formula constants.

Although phone layouts are out of scope, P5 must test desktop widths 1024 and 1440, 200% zoom, long Chinese/English names, full values, editing overlay bounds, keyboard focus and no page overflow. 320px tests remain applicable to the rest of the shell but are not an acceptance target for draggable-grid reflow.

## 7. Dependency, rollout and rollback

P5-007 alone adds `gridstack@13.3.0`, package lock and its CSS import. It must run npm audit and production build before merging. No feature flag is needed because schema/route/capability order makes the feature unavailable until P5-005 has a real backend; Vue must not show the metrics navigation before `dashboard.read` is present.

Rollout order is strict: deploy code that understands V005 before opening any schema-4 data; migration then runs atomically at startup; status capabilities are added only after MetricsApi/DashboardApi are fully wired. If migration fails, the existing recovery state handles startup and no partially seeded schema remains. After a successful V005, an older binary rejects the newer schema by existing MigrationRunner behavior; users restore a V004 file backup only while the application is fully closed.

## 8. Verification strategy

| Layer | Required evidence |
| --- | --- |
| migration | V004 upgrade, empty database, repeated open, constraint/index/seed counts, dataRevision unchanged, bad migration rollback |
| domain | period boundaries, time zones, all system formulas, zero denominator, AST limits/arity, rounding, status priority, graph cycles |
| persistence | real SQLite fact aggregates, archived account/category behavior, query plans, versions/dependencies/layout atomicity and reopen |
| HTTP/contracts | every endpoint, closed bodies, UUID/path/query validation, 401/423/409/428, ETag/idempotency replay, no hidden value leak |
| Vue | Chinese editor, validate/preview state, errors/retry, four views, exact API requests, GridStack edit/cancel/save/reset and keyboard controls |
| E2E | 系统浏览器→Vue→REST→Java→SQLite：记录变化在重载后更新 Dashboard，公式/布局在 Java 服务完整重启后持久化 |
| performance | 20,000 active records, 50 enabled metrics, 100 items: dashboard P95 ≤750ms; report host/data generator and measured distribution |

Implementation tasks and exact commands are in `docs/tasks/P5-*.md`; a task may not claim a later layer's evidence as its own.
