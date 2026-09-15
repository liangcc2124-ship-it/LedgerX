# P2-004：Ledger V003 核心事实数据表

- 状态：PASS（本机 Java 11 隔离验证 11/11；Maven test package 31/31）
- 单一结果：每个 profile 的 ledger 从 V002 可无损升级到 V003，获得分类、账户和财务事实的物理表、关系、约束和索引；本任务不暴露新业务 API。

## 1. 背景、目标、范围与非目标

P1 只有 ledger bootstrap、settings 与幂等表。后续分类/账户/记录 use case 需要先有一个可重开、可验证的 SQLite 物理基础。本任务只落实已接受数据库模型的第一组表，不提前实现资产、指标、预警或旧 JSON 导入。

范围：新增 V003 migration、将它纳入有序 runner、真实 SQLite migration/constraint/index/reopen 测试、相应数据库/任务状态文档。非目标：任何 HTTP endpoint、Java 领域服务、分类 seed 值、业务写入、`record_metric_assignment`、`allocation_plan`、`fixed_asset`、指标/公式、报告/预警、备份、旧 JSON、WAL 策略、Vue/Electron、依赖变更。

## 2. 前置依赖与引用规范

- 前置：P1-003 PASS；ledger 数据库处于 V002 或 fresh state。
- 必读：[数据库](../database.md) §4.2、§4.3（仅 `finance_record`）、§6–§8、[persistence-migration 模块](../modules/persistence-migration.md) §3–§4、[ledger-records 模块](../modules/ledger-records.md) §3、[全局 API](../api.md) §8、§10。数据库文档是字段、关系、索引的唯一来源。
- 此任务只形成数据底座；后续 REST task 仍需先解决 `docs/tasks/README.md` 列出的契约设计门禁。

## 3. 允许/预计修改的文件

- 新增 `src/main/resources/db/ledger/migration/V003__ledger_core_facts.sql`
- `src/main/java/com/ledgerx/persistence/LedgerBootstrap.java`（仅按既有模式登记 V003）
- `src/test/java/com/ledgerx/persistence/**` 中与 V003 migration/SQLite 验证直接相关的测试
- 必要时 `src/test/resources/**` 的 V002/V003 rollback fixture
- `docs/database.md`（记录实际已执行版本 V003；可注明 SQLite 为保持主键非空而采用的物理声明，不改变领域字段设计）、本 Task Spec、新增 `docs/verification/P2-004-ledger-core-schema.md`

禁止修改 V001/V002、catalog migration、HTTP/application、Vue/Electron、API 契约、已执行 migration checksum、`pom.xml`/依赖及旧 WPF/React。

## 4. 实现步骤与必须遵守的数据契约

1. 新 migration 版本必须严格为 V003，description 为 `ledger_core_facts`；通过既有 checksum/history runner 执行。fresh ledger 的 V001→V002→V003 与现有 V002 的 V003 升级都必须成功；重开不重复 DDL/索引、不修改 `ledger_meta`、settings 或 processed operation。
2. 创建 `category`：`id` TEXT PK NOT NULL；`parent_id` TEXT NULL 自 FK `ON DELETE RESTRICT`；`name` TEXT NOT NULL；`is_system`/`is_legacy_custom` INTEGER NOT NULL DEFAULT 0 CHECK 0/1；`archived_at` TEXT NULL；`sort_order` INTEGER NOT NULL DEFAULT 0；`default_recognition_method` TEXT NOT NULL DEFAULT `IMMEDIATE`；`recommended_depreciation_method` TEXT NULL；`created_at,updated_at` TEXT NOT NULL；`revision` INTEGER NOT NULL DEFAULT 0 CHECK >=0。名称长度、两级/无环、枚举适用性由未来领域层校验，V003 不创建未获批准的 trigger 或 seed。SQLite 对 `TEXT PRIMARY KEY` 的 NULL 行为与 SQL 标准不同，因此迁移显式声明 `NOT NULL`。
3. 创建 `category_record_type(category_id TEXT NOT NULL FK category ON DELETE RESTRICT, record_type TEXT NOT NULL)`，复合主键 `(category_id,record_type)`；record type CHECK 精确为 `INCOME,FIXED_COST,VARIABLE_COST,FIXED_ASSET_PURCHASE,PAYABLE_CREATED,PAYABLE_PAYMENT`。
4. 创建 `financial_account`：`id` TEXT PK NOT NULL；`name` TEXT NOT NULL；`kind` TEXT NOT NULL CHECK 为 `CASH,BANK,WALLET,CREDIT,LOAN,OTHER_ASSET,OTHER_LIABILITY`；`balance_side` TEXT NOT NULL CHECK 为 `ASSET,LIABILITY`；`opening_on` TEXT NOT NULL；`opening_balance_minor` INTEGER NOT NULL；`include_in_available_cash` INTEGER NOT NULL DEFAULT 0 CHECK 0/1；`is_system` INTEGER NOT NULL DEFAULT 0 CHECK 0/1；`archived_at` TEXT NULL；`created_at,updated_at` TEXT NOT NULL；`revision` INTEGER NOT NULL DEFAULT 0 CHECK >=0。SQL 必须加 `balance_side='ASSET' OR include_in_available_cash=0`，其余 kind/side 映射仍由以后固定的领域契约处理。
5. 创建 `finance_record`，列/类型/默认/外键必须精确为数据库 §4.3：`id` TEXT PK NOT NULL、`occurred_on` TEXT NOT NULL、`record_type` 为上列六值、`amount_minor` INTEGER NOT NULL CHECK >0、`currency_code` TEXT NOT NULL DEFAULT `CNY` CHECK =`CNY`、`category_id` TEXT NULL FK category `ON DELETE RESTRICT`、`account_id` TEXT NULL FK financial_account `ON DELETE RESTRICT`、`settlement_mode` TEXT NOT NULL、`settlement_on` TEXT NULL、`income_source` TEXT NULL、`is_self_generated_income`/`is_non_essential` INTEGER NOT NULL DEFAULT 0 CHECK 0/1、`note` TEXT NOT NULL DEFAULT '' CHECK length <=4000、`created_at,updated_at` TEXT NOT NULL、`deleted_at` TEXT NULL、`revision` INTEGER NOT NULL DEFAULT 0 CHECK >=0。不得添加余额累计列、allocation/asset FK、metric 字段、JSON snapshot 或 trigger。
6. 索引必须为：`category(parent_id,archived_at,sort_order)`；`category_record_type(record_type,category_id)`；`financial_account(archived_at,name COLLATE NOCASE)`；`finance_record(deleted_at,occurred_on DESC,id DESC)`、`(account_id,deleted_at,occurred_on)`、`(category_id,deleted_at,occurred_on)`、`(record_type,deleted_at,occurred_on)`、`(settlement_on,deleted_at)`。活动分类名称不区分大小写唯一必须用两个 partial unique index 表达：顶级 `(name COLLATE NOCASE) WHERE parent_id IS NULL AND archived_at IS NULL`，子级 `(parent_id,name COLLATE NOCASE) WHERE parent_id IS NOT NULL AND archived_at IS NULL`；活动账户名称不区分大小写唯一使用 `(name COLLATE NOCASE) WHERE archived_at IS NULL`。索引名稳定、可读且不以表的 rowid 为接口。
7. 所有验证测试必须通过真实临时 sqlite-jdbc 文件连接，显式确认 foreign keys 已开；不经由 in-memory Map、不直接改 schema history。迁移失败仍由既有 runner 回滚，保留原 V002 可重开。

## 5. 用户操作、跨层字段、结果与边界

| 用户操作 | 前端反馈 | 跨层数据 | 业务/持久化结果 | 边界 |
| --- | --- | --- | --- | --- |
| 未来启动一个旧 V002 profile | 本任务没有新增 UI | Java bootstrap/schema history | 一次升级至 V003，既有 settings/meta/operation 不变 | V003 失败时不把库部分升级或伪装 READY |
| 未来创建分类/账户/记录 | 本任务不提供入口 | 无 HTTP | 表/关系/索引准备就绪 | V003 本身不 seed、不创建业务事实 |
| 重开已升级 ledger | 无 | `schema_history` | 不重复表/索引，不改 user data | checksum/未知高版本沿用既有拒绝语义 |
| 违反 FK/unique/CHECK 的测试写 | 无 | 参数化测试 SQL | SQLite 拒绝不合法物理关系 | NULL parent 顶级唯一、archived 名称复用、LIABILITY 可用现金均按上述约束 |

## 6. 验收标准

- fresh 与 V002 upgrade 均精确得到 V003，`schema_history` 三条成功记录和正确 checksum；重开后数量/版本不变。
- V002 中既有 settings、ledger_meta、processed_operation 的行和值逐列保持；V003 不插入 category/account/record seed 或业务记录。
- 每个表、列、NOT NULL、CHECK、FK、partial unique index 和普通索引均由 SQLite schema/行为测试验证；同名 archived category/account 可复用，活动同级/账户不可复用。
- `foreign_key_check`、`integrity_check` 在 fresh/upgrade 通过；错误外键、重复 active 名称、非法 record type、0 amount、非 CNY、LIABILITY 可用现金均被拒绝。
- V003 migration 人为失败时，既有 V002 ledger 可重开且 schema history 没有成功 V003；不得修改 V001/V002 或其 checksum。
- 全量 Java `test package` 通过，并明确报告本次新增测试数量。

## 7. 测试层次、命令与真实链路

- 必跑：在仓库根执行 `./mvnw.cmd -q '-Dmaven.compiler.fork=true' test package`；需包含真实临时 SQLite 的 fresh、V002 upgrade、重开、约束、失败回滚与 health tests。
- 真实持久化：**是**，必须是隔离临时目录下的 sqlite-jdbc 文件并重开验证。
- HTTP/Vue/Electron/发布包/桌面快捷方式：不适用；本任务没有 API 或 UI，不能声称原生用户链路通过。

## 8. 禁止事项、升级、文档与完成报告

禁止 seed 分类、写业务数据、实现 REST、预留资产/指标/预警表、启用 WAL、加 trigger、改变删档策略或建新通用持久化框架。若实际数据库文档与本任务列出的物理字段/约束冲突、migration runner 不能按序登记、需要改已执行 migration、或旧 V002 不能无损升级，停止并升级。

完成报告必须写明：migration/Java test/文档文件；fresh、upgrade、reopen、failure rollback 各自证据及测试数量；真实 SQLite 隔离根；HTTP/Vue/Electron/发布包/快捷方式为何不适用；没有 seed/业务数据/API 的证明；残余风险（领域 API 尚未下发）。只追加本任务验证记录和本 Task Spec 的实施报告；不得在 P2-001 并行期间修改 `docs/tasks/README.md`。

## 9. 实施报告（2026-09-14）

- 修改：`src/main/resources/db/ledger/migration/V003__ledger_core_facts.sql`、`src/main/java/com/ledgerx/persistence/LedgerBootstrap.java`、`src/test/java/com/ledgerx/persistence/PersistenceBootstrapTest.java`、新增 `src/test/java/com/ledgerx/persistence/LedgerV003SchemaTest.java`、新增 `src/test/resources/db/testmigration/V003__broken.sql`、`docs/database.md` 及本验证记录；最终 Maven package 已刷新对应 `target/classes` 产物。
- 结果：ledger V003 已按 V001→V002→V003 顺序登记；分类、分类类型、账户、财务记录表及规定索引/约束已落地；fresh、V002 upgrade、重开和失败回滚均通过；没有 seed 或业务数据写入。
- 验证：新增 V003 测试 4/4；包含既有启动回归在内的 Java 11 隔离 JUnit 执行 11/11；真实临时 SQLite 文件、foreign key、integrity_check 和 foreign_key_check 均覆盖。
- Maven：初次尝试曾因 wrapper 用户目录和镜像/插件缓存受阻；在用户批准的本机环境设置 `MAVEN_OPTS=-Duser.home=C:\Users\liang` 后，`.\mvnw.cmd -q '-Dmaven.compiler.fork=true' test package` 成功，Surefire 31/31 通过并生成 `target/cp.txt`。
- 不适用：本任务不改 HTTP、Vue、Electron、发布包或桌面快捷方式；领域 REST 与业务 seed 仍待后续契约门禁。
