# LedgerX Java 11 数据库总体设计

- 状态：已接受
- 日期：2026-09-14
- 数据库：SQLite，经 JDBC 访问
- 边界：`profiles.db` 管用户空间目录；每个用户空间一个 `ledger.db`
- 说明：前端改为 Vue、桌面壳改为 Electron、传输改为 REST 不改变领域表；基础记账版范围见 [ADR-010](./decisions/ADR-010-basic-ledger-scope.md)
- 迁移状态：ledger V001–V004 已纳入有序 runner；V004 seed 已由真实 SQLite 测试验证，分类、账户和基础财务记录表已落地

## 1. 原则

1. SQLite 是 Java 版活动数据的唯一事实来源；Vue 页面状态、报表和指标值都是投影，不是第二份权威数据。
2. 一个 application use case 对应一个显式数据库事务；禁止在 repository 内隐式提交。
3. 每个连接都执行 `PRAGMA foreign_keys=ON`，并验证结果；数据库健康检查同时运行 `integrity_check` 和 `foreign_key_check`。
4. UUID 使用小写、带连字符的 36 字符 `TEXT`；系统预置项使用固定 UUID，不由显示名称临时生成。
5. 业务日期存 ISO `YYYY-MM-DD`；时间点存 UTC ISO-8601（如 `2026-09-12T08:30:00Z`）。
6. CNY 金额存 `INTEGER` 分；Java 领域层使用 `BigDecimal`，只在存储边界精确换算。超过两位小数或超出 64 位范围时拒绝，不舍入。
7. 普通数量/比例使用规范十进制字符串并在 Java 中以 `BigDecimal` 计算；SQLite 不负责这些值的算术聚合。
8. 删除财务记录使用软删除；分类、账户、指标使用归档。默认不级联删除财务事实。
9. schema 迁移只前进、可重试、带校验和；涉及现有数据的升级前保留数据库副本。基础版不导入旧 JSON。
10. 首期不存派生指标缓存。可由原始事实重算的数据不成为权威列。

## 2. 文件布局

```text
%LocalAppData%\LedgerX\
├─ profiles.db
├─ Profiles\<profile-uuid>\ledger.db
├─ Imports\（后续旧数据迁移才使用）
├─ Snapshots\（后续应用内恢复才使用）
├─ Backups\（后续应用内备份才使用）
└─ Logs\
```

- `profiles.db` 损坏不应导致账本文件被删除；恢复器可扫描合法 profile 目录重建索引。
- 基础版只在应用完全退出后由用户复制整个 `%LocalAppData%\LedgerX` 目录；恢复也整体替换，避免遗漏 `profiles.db` 或其他 profile。
- SQLite 的 `-wal`/`-shm` 不是可独立恢复的账本。不得在应用运行中把单个数据库文件称为完整备份。

## 3. Profile catalog

### 3.1 `catalog_schema_history`

| 列 | 类型/约束 | 说明 |
| --- | --- | --- |
| `version` | INTEGER PK CHECK > 0 | 迁移版本 |
| `description` | TEXT NOT NULL | 迁移说明 |
| `checksum` | TEXT NOT NULL | SQL 资源 SHA-256 |
| `installed_at` | TEXT NOT NULL | UTC instant |
| `success` | INTEGER NOT NULL CHECK IN (0,1) | 迁移是否完成 |

### 3.2 `profile`

| 列 | 类型/约束 | 说明 |
| --- | --- | --- |
| `id` | TEXT PK | profile UUID |
| `name` | TEXT NOT NULL CHECK `trim(name) <> ''` | 显示名 |
| `relative_directory` | TEXT NOT NULL UNIQUE | 仅允许 profile 根下相对路径 |
| `created_at` | TEXT NOT NULL | UTC instant |
| `last_opened_at` | TEXT NOT NULL | UTC instant |
| `archived_at` | TEXT NULL | 归档时间 |
| `revision` | INTEGER NOT NULL DEFAULT 0 | 乐观并发版本 |

### 3.3 `catalog_setting`

仅一行 `id=1`：`active_profile_id` FK→`profile(id)`、`updated_at`、`revision INTEGER NOT NULL DEFAULT 0 CHECK >= 0`。不能把已归档 profile 设置为活动空间，此规则由 application 在事务内维护。每个成功改变 catalog 的 mutation 使 revision 恰好加一；成功 no-op 不增加。

### 3.4 `catalog_processed_operation`

profile 创建、切换和归档会跨越 active ledger，不能把幂等结果只保存在操作前或操作后的账本中。catalog 因此保存：

- `idempotency_key TEXT` PK，lowercase UUID；
- `http_method TEXT`，只允许 `POST|DELETE`；
- `canonical_path TEXT`，长度 1–300；
- `request_hash TEXT`，64 位小写 SHA-256；
- `response_status INTEGER`，200–299；
- `response_json TEXT NOT NULL`；
- `etag TEXT NULL`、`location TEXT NULL`；
- `completed_at TEXT`、`expires_at TEXT CHECK expires_at > completed_at`。

索引：`catalog_processed_operation(expires_at)`。catalog mutation 必须在同一 catalog transaction 写业务变化、catalog/profile revision 与 operation；application 写门同时检查 catalog 与 active ledger 的 operation key，禁止跨作用域复用。

索引：`profile(archived_at, last_opened_at DESC)`。

## 4. Ledger database 核心

### 4.1 基础与配置

#### `schema_history`

与 catalog 同结构；不得修改已成功迁移的校验和。

#### `ledger_meta`

单行 `id=1`：

- `profile_id TEXT NOT NULL UNIQUE`
- `created_at TEXT NOT NULL`
- `updated_at TEXT NOT NULL`
- `data_revision INTEGER NOT NULL DEFAULT 0`
- `source_json_schema INTEGER NULL`
- `source_import_hash TEXT NULL UNIQUE`
- `import_completed_at TEXT NULL`

`data_revision` 在每个成功写事务中加一，用于缓存失效、快照版本和并发检查。

#### `ledger_setting`

每个 `ledger.db` 恰有一行 `id=1`。P1-003 的 `V002__ledger_settings.sql` 创建和 seed 该行；它不修改 `ledger_meta.data_revision`。字段如下：

| 列 | 类型/默认/约束 | 说明 |
| --- | --- | --- |
| `id` | INTEGER PK CHECK `id=1` | 单行设置。 |
| `notifications_enabled` | INTEGER NOT NULL DEFAULT 0 CHECK IN (0,1) | 仅保存用户偏好；不表示 Windows 通知已投递。 |
| `auto_backup_enabled` | INTEGER NOT NULL DEFAULT 1 CHECK IN (0,1) | 仅保存备份策略开关。 |
| `auto_backup_interval_days` | INTEGER NOT NULL DEFAULT 7 CHECK 1–365 | 自动备份间隔。 |
| `auto_backup_retention_count` | INTEGER NOT NULL DEFAULT 10 CHECK 1–100 | 自动备份最多保留数。 |
| `last_auto_backup_at` | TEXT NULL | 只由成功的 backup 模块写入，客户端不可 PATCH。 |
| `last_settings_section` | TEXT NOT NULL DEFAULT `GENERAL` CHECK IN (`GENERAL`,`PROFILES`,`CATEGORIES`,`ACCOUNTS`) | 最后打开的设置页。 |
| `currency_code` | TEXT NOT NULL DEFAULT `CNY` CHECK = `CNY` | 首期固定币种。 |
| `currency_symbol` | TEXT NOT NULL DEFAULT `¥` CHECK = `¥` | 与固定 CNY 一致的展示符号。 |
| `safety_buffer_minor` | INTEGER NOT NULL DEFAULT 300000 CHECK >= 0 | 安全垫目标，单位为分。 |
| `hide_all_amounts` | INTEGER NOT NULL DEFAULT 0 CHECK IN (0,1) | 仅显示隐私偏好，不是加密。 |
| `theme_name` | TEXT NOT NULL DEFAULT `WARM_COPPER` CHECK IN (`WARM_COPPER`,`GRAPHITE`,`DEEP_SEA_BLUE`,`CUSTOM`) | 内置或受限自定义主题标识。 |
| `custom_theme_css` | TEXT NULL CHECK length <= 65536 | 仅由后续 theme import/export 写入；仍须做 CSS 安全校验。 |
| `revision` | INTEGER NOT NULL DEFAULT 0 CHECK >= 0 | settings 的强 ETag 版本。 |
| `updated_at` | TEXT NOT NULL | V002 初始化或最后成功设置写入的 UTC instant。 |

`GET /settings` 将 `safety_buffer_minor` 映射为金额 DTO，绝不直接暴露分单位或数据库列名。每个有效 PATCH 在同一事务中更新该行、`ledger_meta.updated_at`、`ledger_meta.data_revision` 与 `processed_operation`；settings revision 和 data revision 均恰好加一。无实际字段变化的成功 PATCH 只记录 operation，不增加任一 revision。

### 4.2 分类与账户

#### `category`

`id` TEXT PK NOT NULL、`parent_id` 自 FK、`name`、`is_system`、`is_legacy_custom`、`archived_at`、`sort_order`、`default_recognition_method`、`recommended_depreciation_method`、`created_at`、`updated_at`、`revision`。SQLite 对 `TEXT PRIMARY KEY` 的 NULL 行为与 SQL 标准不同，物理迁移显式声明 `NOT NULL`。

辅助表 `category_record_type(category_id, record_type)`，复合 PK；`category_id` FK `ON DELETE RESTRICT`。

约束：

- 名称非空，长度 1–100。
- 最大两级；父分类不能等于自身；应用层拒绝祖先环。
- 系统分类不能删除，只能由版本化 seed migration 更新。
- 同一父节点下活动分类名大小写不敏感唯一；SQLite partial unique index 实现。

#### `financial_account`

`id` TEXT PK NOT NULL、`name`、`kind`、`balance_side`、`opening_on`、`opening_balance_minor`、`include_in_available_cash`、`is_system`、`archived_at`、审计时间、`revision`。

约束：

- `kind` 和 `balance_side` 组合合法；负债类不能标记为可用现金。
- 名称非空；活动账户名大小写不敏感唯一。
- 被记录引用的账户只能归档，不能物理删除。

索引：

- `category(parent_id, archived_at, sort_order)`
- `category_record_type(record_type, category_id)`
- `financial_account(archived_at, name COLLATE NOCASE)`

#### 4.2.1 核心分类与账户 seed（V004，已实施）

- Seed 决策见 [ADR-009](./decisions/ADR-009-core-catalog-seed.md)。精确 ID、名称、父子关系、排序、可用记录类型、默认确认方式和推荐折旧方式由 [核心分类与账户 seed 契约](./contracts/core-catalog-v1.md) 唯一规定；实现不得从显示名称重新生成 ID，也不得自行增加、删除或翻译 seed。
- V004 对每个 V003 ledger 只执行一次。它插入 71 个系统分类、其适用类型关联，以及一个固定 ID 的系统账户“现金储备”；已成功 V004 的库重开时不重写任何 seed，也不覆盖用户后来允许修改的系统账户字段。
- 系统账户的 opening_on 是 V004 在执行机器本地日期写入的日期，opening_balance_minor 为 0，kind 为 CASH、balance_side 为 ASSET、include_in_available_cash 为 1、revision 为 0。分类与账户的 created_at/updated_at 都是 V004 执行时的 UTC instant。
- 这是一项 schema seed，不是用户 mutation：不写 processed_operation，不递增已有 ledger_meta.data_revision，也不改 V001–V003 的 checksum。启动期间不存在可与迁移并发的 renderer 请求；迁移完成后的首次 API 读取以现有 dataRevision 为准。
- 基础版不导入 JSON。若以后恢复旧数据迁移，导入不得复用 V004 覆盖源分类/账户，且必须先重新固定兼容和映射契约。

### 4.3 财务记录、归集与分摊

基础记账版只公开写入 `finance_record`，且只允许 `INCOME`、`FIXED_COST`、`VARIABLE_COST`。每条记录的 `settlement_mode` 固定为 `PAID_FROM_ACCOUNT`，`category_id`、`account_id`、`settlement_on` 均不能为空。application 同时把 `income_source` 写为 NULL、两个布尔扩展写为 0；`record_metric_assignment`、`direct_metric_entry`、`direct_metric_assignment`、`allocation_plan`、`fixed_asset` 和后续指标/报表表保持为空。数据库保留较宽的 CHECK 和高级表用于避免逆向迁移，但这不表示对应 API 已启用。

#### `finance_record`

| 列 | 类型/约束 |
| --- | --- |
| `id` | TEXT PK NOT NULL |
| `occurred_on` | TEXT NOT NULL |
| `record_type` | TEXT NOT NULL CHECK IN (`INCOME`,`FIXED_COST`,`VARIABLE_COST`,`FIXED_ASSET_PURCHASE`,`PAYABLE_CREATED`,`PAYABLE_PAYMENT`) |
| `amount_minor` | INTEGER NOT NULL CHECK > 0 |
| `currency_code` | TEXT NOT NULL DEFAULT 'CNY' CHECK = 'CNY' |
| `category_id` | TEXT NULL FK `category` ON DELETE RESTRICT |
| `account_id` | TEXT NULL FK `financial_account` ON DELETE RESTRICT |
| `settlement_mode` | TEXT NOT NULL |
| `settlement_on` | TEXT NULL |
| `income_source` | TEXT NULL |
| `is_self_generated_income` | INTEGER NOT NULL DEFAULT 0 |
| `is_non_essential` | INTEGER NOT NULL DEFAULT 0 |
| `note` | TEXT NOT NULL DEFAULT '' CHECK length <= 4000 |
| `created_at`,`updated_at`,`deleted_at` | TEXT；`deleted_at` NULL 表示活动 |
| `revision` | INTEGER NOT NULL DEFAULT 0 |

高级版本若启用服务期、周期或固定资产，继续使用既有 `allocation_plan`/`fixed_asset` 关系；基础版不创建这些行。

数据库可容纳以下 `settlement_mode`；基础版公开 API 只接受第一项：

- `PAID_FROM_ACCOUNT` 需要账户和结算日。
- `INCLUDED_IN_OPENING_BALANCE`、`NON_CASH` 不产生现金变动。
- `CREATE_PAYABLE` 仅允许 `PAYABLE_CREATED`，不产生现金变动；迁移把旧 C# `Payable`/前端 `CreatePayable` 映射为该 canonical 值。
- 只有 `INCOME` 可设置收入来源/自主收入；其他类型保存时清空这些字段。

#### `record_metric_assignment`

后续可选；基础版不写入，也不投影到公开 DTO。

`record_id` FK→`finance_record`、`metric_id` FK→`metric_definition`，复合 PK。只允许目标指标 `accepts_direct_record_assignment=1`；该跨表条件由领域层校验。

#### `direct_metric_entry`

后续可选；基础版无对应 endpoint。

把当前 `CustomIncrease`/`CustomDecrease` 从财务记录中分离：`id` PK、`occurred_on`、`direction`、`decimal_value TEXT`、`note`、审计/软删除/版本列。指标目标不放在本表，按 [ADR-008](./decisions/ADR-008-direct-metric-multi-assignment.md) 进入独立关联表。

约束：`decimal_value` 必须由 Java 解析为正 `BigDecimal`，规范化后最多 8 位小数、绝对值不超过 `10^15`；正负由 `direction` 表示。API 可继续把它投影到统一“记录”列表，但不能影响账户现金。

#### `direct_metric_assignment`

后续可选；基础版无对应 endpoint。

`entry_id` FK→`direct_metric_entry` `ON DELETE CASCADE`、`metric_id` FK→`metric_definition` `ON DELETE RESTRICT`，复合 PK。每条 direct entry 允许 0–200 个目标；目标必须未归档且 `accepts_direct_record_assignment=1`，由领域层在同一事务校验。

#### `allocation_plan`

后续可选；基础版不创建。

`id` PK、`record_id` NOT NULL UNIQUE FK、`amount_minor`、`service_start`、`service_end_exclusive`、`recognition_method`、`formula_version_id` 可空 FK、审计/版本列。

约束：`service_end_exclusive > service_start`、金额与主记录相同、只有相关成本类型可有分摊计划。

#### `recurring_plan`

保留当前可持久化模型以免迁移丢失：`id`、名称、cadence、next_on、`amount_minor`、paused、ended_at、审计/版本。首期不新增自动生成行为；若当前 UI 不使用，只迁移并保持可读。

主要索引：

- `finance_record(deleted_at, occurred_on DESC, id DESC)`：主列表游标分页
- `finance_record(account_id, deleted_at, occurred_on)`
- `finance_record(category_id, deleted_at, occurred_on)`
- `finance_record(record_type, deleted_at, occurred_on)`
- `finance_record(settlement_on, deleted_at)`
- `record_metric_assignment(metric_id, record_id)`
- `direct_metric_entry(deleted_at, occurred_on DESC, id DESC)`
- `direct_metric_assignment(metric_id, entry_id)`
- `allocation_plan(service_start, service_end_exclusive)`

### 4.4 固定资产

本节是保留的数据设计，基础记账版不创建、不读取、不迁移固定资产，也不暴露资产 capability。

#### `fixed_asset`

`id` PK、`name`、`category_id` FK、`acquisition_record_id` NOT NULL UNIQUE FK、`in_service_on`、`cost_minor`、`salvage_minor`、`useful_life_months`、`total_units_decimal`、`used_units_decimal`、`depreciation_method`、`formula_version_id` 可空 FK、`status`、`disposed_on`、`disposal_proceeds_minor`、审计/版本列。

约束：

- `cost_minor > 0`
- `0 <= salvage_minor <= cost_minor`
- 按月折旧法 `useful_life_months > 0`
- 工作量法总工作量 > 0 且已用工作量 >= 0
- `DISPOSED` 必须有 `disposed_on`，非处置状态必须为空
- 购置记录类型必须为 `FIXED_ASSET_PURCHASE`，由领域层校验
- 不持久化可重算的账面价值和累计折旧；如以后证明昂贵，再加带 `as_of` 与 `data_revision` 的派生快照

索引：`fixed_asset(status, in_service_on)`、`fixed_asset(category_id, status)`。

### 4.5 指标与公式

本节是保留的数据设计，基础记账版不创建、不读取指标/公式，也不暴露相关 capability。

#### `formula_definition`

`id` PK、`scope`、`result_type`、`is_template`、`archived_at`、`created_at`。

#### `formula_version`

`id` PK、`formula_id` FK、`version`、`ast_json`、`tokens_json`、`effective_from`、`created_at`，UNIQUE(`formula_id`,`version`)。

AST 作为原子 JSON 保存，因为表达式树总是整体读取/验证/替换，拆成节点表不会增加有效约束，只会增加 join。读取时必须按 JSON schema/白名单节点验证，限制深度 32、节点 256。

#### `formula_dependency`

`formula_version_id` FK、`dependency_kind`、`dependency_key`、`referenced_metric_id` 可空 FK，复合唯一。它是保存公式时由已验证 AST 生成的可查询索引，不接受前端直接写入。

#### `metric_definition`

`id` TEXT PK（系统稳定 ID 或 `custom-<uuid>`）、`name`、`description`、`display_format`、`precision`、`current_formula_version_id` FK、`template_id`、`template_version`、`period_behavior`、`accepts_direct_record_assignment`、`is_system`、`enabled`、`archived_at`、审计/版本列。

约束：

- `precision BETWEEN 0 AND 8`
- 当前公式 scope 必须为 METRIC，由领域层校验
- 系统指标不能物理删除
- 被活动公式/预警引用的指标不能归档或删除

#### `metric_visibility`

每个指标一行：`metric_id` PK/FK、`hidden`、`dashboard_enabled`。把当前 `HiddenMetrics`/`EnabledMetrics` 集合显式化。

索引：

- `formula_version(formula_id, version DESC)`
- `formula_dependency(referenced_metric_id)`
- `metric_definition(archived_at, enabled, name COLLATE NOCASE)`
- 活动自定义指标名称可选唯一约束；系统和自定义是否允许同名由中级模块规格确认

### 4.6 仪表盘、预警和事件

本节是保留的数据设计，基础记账版不创建、不读取仪表盘、预警或事件，也不暴露相关 capability。

#### `dashboard_layout`

`id` PK、`breakpoint`、`version`、`updated_at`，UNIQUE(`breakpoint`)。

#### `dashboard_layout_item`

`layout_id` FK、`widget_id`、`x/y/w/h/min_w/min_h/max_w/max_h`，复合 PK。坐标和尺寸均非负，min ≤ current ≤ max。

#### `warning_rule`

`id` PK、名称/说明、enabled、主 metric、operator、threshold decimal text、unit、period、comparison mode/window、consecutive periods、severity、repeat policy、cooldown、effective dates、notification method、show amount、审计/版本。

#### `warning_condition`

`id` PK、`rule_id` FK `ON DELETE CASCADE`、position、metric FK、operator、threshold、comparison mode/window；UNIQUE(rule_id, position)。

#### `warning_event`

`id` PK、`rule_id` FK `ON DELETE RESTRICT`、status、measured decimal text、threshold decimal text、period start/end、triggered/last evaluated/resolved/snoozed times、explanation。

#### `warning_event_evidence`

`event_id` FK、`record_id` FK，复合 PK。永久清理仍被事件引用的记录时，先按产品规则删除事件或保留不可识别 evidence ID；不得靠级联悄悄改变预警历史。

索引：

- `warning_rule(enabled, effective_from, effective_to)`
- `warning_condition(metric_id, rule_id)`
- `warning_event(rule_id, status, triggered_at DESC)`
- `warning_event(status, snoozed_until)`

### 4.7 幂等、迁移问题与诊断

#### `processed_operation`

| 列 | 类型/约束 | 说明 |
| --- | --- | --- |
| `idempotency_key` | TEXT PK，36 字符 UUID | `Idempotency-Key` |
| `http_method` | TEXT NOT NULL CHECK IN (`POST`,`PUT`,`PATCH`,`DELETE`) | canonical 大写方法 |
| `canonical_path` | TEXT NOT NULL CHECK length 1–300 | 不含 origin/query 的路由模板+资源 ID |
| `request_hash` | TEXT NOT NULL CHECK length=64 | method、canonical path、canonical body 的 SHA-256 hex |
| `response_status` | INTEGER NOT NULL CHECK BETWEEN 200 AND 299 | 首次已提交的成功 HTTP status |
| `response_json` | TEXT NOT NULL | 首次完整 envelope；写入前由 Jackson 验证为 object |
| `profile_id` | TEXT NOT NULL | 必须等于本 ledger 的 `ledger_meta.profile_id` |
| `completed_at`,`expires_at` | TEXT NOT NULL | UTC instant；expires_at 晚于 completed_at |

- `idempotency_key` 是 API 的 `Idempotency-Key` UUID；`X-Request-Id` 仅用于追踪，不作为持久化幂等键。
- 相同 key + 相同 method/path/hash 返回原状态码与响应。
- 相同 key + 不同 method/path/hash 返回 `IDEMPOTENCY_CONFLICT`。
- `profile_id` 必须等于事务所属 ledger 的 `ledger_meta.profile_id`；不能跨 profile 查询或重放结果。
- 默认保留 7 天；危险恢复操作的记录保留到下一次成功备份。

索引：`processed_operation(expires_at)`，只用于成功事务后的有界清理。

#### `migration_issue`

`id` INTEGER PK、`source_kind`、`source_entity_id`、`code`、`message`、`details_json`、`created_at`、`resolved_at`，UNIQUE(source_kind, source_entity_id, code)。重复导入不会重复生成。

## 5. 关系与所有权

```text
profile (catalog)
  └─ owns one ledger.db
       ├─ category ─< finance_record >─ financial_account
       │                    ├─ 0..1 allocation_plan
       │                    ├─ 0..1 fixed_asset (by acquisition_record_id)
       │                    └─ * record_metric_assignment >─ metric_definition
       ├─ direct_metric_entry ─< direct_metric_assignment >─ metric_definition
       ├─ metric_definition ─> formula_version ─< formula_dependency
       ├─ warning_rule ─< warning_condition ─> metric_definition
       │              └─< warning_event ─< warning_event_evidence >─ finance_record
       └─ dashboard_layout ─< dashboard_layout_item
```

聚合所有权（第一项为基础版；其余为保留设计）：

- 基础记录用例只拥有 `finance_record` 的三类记录及其余额影响；不调用高级表。
- 高级记录用例以后启用时，才拥有分摊计划、购置固定资产和指标关联的一致性。
- 指标模块拥有公式版本、依赖和指标定义；记录模块只能引用公开的指标 ID。
- 预警模块拥有规则/条件/事件，不拥有财务记录。
- 仪表盘只保存 widget 标识和布局，不拥有指标。
- 备份模块后续启用时拥有文件级快照和 manifest，不改写领域事实。

## 6. 删除、归档与保留

- 基础版 `finance_record` 只做软删除；恢复清空 `deleted_at`，不提供永久删除。`direct_metric_entry` 未启用。
- `category`、`financial_account`：被引用后只归档；合并分类在单事务更新引用并保留源分类为归档。
- `metric_definition`：被公式、预警或记录归集引用时禁止永久删除；可先归档并修复依赖。
- `formula_version`：不可变，不物理删除仍被指标/资产/分摊引用的版本。
- `warning_event`：规则删除默认应连同事件删除还是保留历史，当前 C# 会删除；目标默认保持现状，但中级规格需明确。
- 自动备份、保护快照和原始迁移 JSON 保留规则只在后续启用相应模块时生效。

## 7. 事务边界

必须单事务：

- 基础版新建/编辑财务记录 + `ledger_meta.data_revision` + `processed_operation`；高级关联表零写入。
- 基础版删除/恢复记录 + revision/dataRevision/operation；余额由查询投影，不另写累计表。
- 高级版以后启用时，新建/编辑记录与分摊、固定资产、指标关联必须同事务。
- 保存公式版本 + 依赖 + 指标当前版本切换。
- 合并分类及所有记录引用更新。
- 写业务事实 + `ledger_meta.data_revision` + `processed_operation`。
- 写 settings + `ledger_setting.revision` + `ledger_meta.data_revision` + `processed_operation`。
- profile 创建/切换/归档 + `catalog_setting.revision` + `catalog_processed_operation`。

基础版没有运行中的文件恢复事务。以后启用应用内备份/恢复时采用“临时文件→验证→原子重命名/切换”的补偿流程，不引入 XA。

## 8. 迁移原则与步骤

### 8.1 Schema migration

1. 迁移资源命名 `V001__bootstrap.sql`、`V002__ledger_settings.sql` 等，版本严格递增，文件 SHA-256 写入 `schema_history`。
2. 启动时若数据库版本高于应用支持版本，拒绝写入并给出升级提示。
3. 结构变更优先 expand/读兼容/backfill/验证/contract；SQLite 不便直接修改的表采用新表复制，并在单事务核对行数。
4. 迁移 runner 必须依赖 SQLite 事务保证失败回滚；涉及已有业务数据的破坏性重建才要求先创建一致性保护副本。V004 只做固定 seed，不引入应用内备份依赖。
5. 重复启动不得重复 seed、重复回填或修改历史业务事实。

### 8.2 旧 JSON 导入（后续可选，不属于基础版）

1. 先读取 `profiles.json`，保留 profile UUID、名称、归档和活动状态；同时发现默认根目录与 `Profiles/<id>` 下的账本。索引缺失时可创建一个默认 profile，但不得把多个账本猜成同一空间。
2. 对每个 `ledger.json` 计算源文件 SHA-256；若对应 `source_import_hash` 已完成则不重复导入。
3. 在 `Imports/<uuid>/ledger.db` 建新库，不直接写活动路径。
4. 用 Jackson 2.x 严格解析当前 v3.1 schema 4，再写关系表；schema 2/3 不在 Java 版内重复实现迁移逻辑，明确提示先用现有 C# v3.1 升级。
5. 保留原 UUID；普通记录的旧 `CustomMetricId` 合并到 `record_metric_assignment`；`CustomIncrease`/`CustomDecrease` 转为同 ID `direct_metric_entry`，并把旧 `CustomMetricId` 与 0..n `MetricTargetIds` 去重后写入 `direct_metric_assignment`；零目标条目仍保留。旧自定义指标转权威 `metric_definition`；资产购置按现有规则补关联。
6. 逐项验证：profile、记录数/软删除数、每条金额、分类/账户引用、公式依赖、资产成本、规则/事件、布局、设置。
7. 运行 `integrity_check`、`foreign_key_check` 和领域不变量校验。
8. 发现未知枚举、>2 位货币、坏引用或公式无法映射时记录 `migration_issue`；影响财务结果的问题阻止该 profile 切换。
9. 全部可导入 profile 核对后才写 `profiles.db` 的活动指针；单个 profile 失败不能污染其他空间。
10. 成功后写 manifest 并原子移动到 profile 活动目录；原 `profiles.json`/`ledger.json` 改为只读保留或由用户显式归档，不删除。

### 8.3 备份兼容（后续可选，不属于基础版）

- Java 版直接读取当前 v3.1 schema 4 裸账本 JSON 和备份 envelope 格式 2。
- schema 2/3 或备份格式 1 明确拒绝，并提示先用现有 C# v3.1 打开和升级后再迁移。
- 新备份格式建议为 3：manifest + 一致性 `ledger.db` + SHA-256；不承诺旧 C# 版能读取新格式。
- 新版恢复任何不在明确兼容清单中的格式只允许读取安全元数据或直接拒绝，不允许猜测导入。
- 回滚到 C# 版只能继续使用迁移前 JSON；Java 运行期间新增数据不会自动反写 JSON，避免双写分叉。

## 9. 容量与性能说明

- 当前基础验收按单 profile 20,000 条记录；该规模远低于 SQLite 能力边界。
- 首先依赖正确组合索引和有界查询；列表默认 50，最大 200。
- 记录和账户余额查询只取所需日期、类型和列；避免加载整个数据库为一个状态对象。
- WAL 是否启用由真实备份、杀进程和 Windows 文件系统测试决定。单写者应用默认可先使用 SQLite 默认 journal 模式；没有并发读写证据时不为吞吐开启 WAL。
- 数据库连接设置、索引使用和慢查询在集成测试用 `EXPLAIN QUERY PLAN` 抽查，不为所有查询建立重复索引。
