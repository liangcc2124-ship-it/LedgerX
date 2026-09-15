# 模块规格：persistence-migration

- 状态：P2-004 的 V003 物理表已实施；按 ADR-010，旧 JSON/format 2 导入与应用内恢复延后，不阻塞基础版
- 边界：SQLite catalog/账本、JDBC repository、事务、schema 迁移、profile 生命周期、v3.1 JSON/格式 2 导入与健康检查。
- 引用：[数据库总体设计](../database.md)、[架构 §3.2、§6、§8](../architecture.md)、[需求 §7.1、§8.2](../requirements.md)、[API §5、§10](../api.md)、[ADR-003](../decisions/ADR-003-sqlite-per-profile.md)。

## 1. 职责、术语、用例与非目标

**职责**：维护 `profiles.db` 及每 profile 一个 `ledger.db` 的可靠来源；为 application 提供显式事务和 repository 端口；把仅支持的旧数据旁路导入为 SQLite；在启动、备份和恢复前执行健康检查。

术语：`catalog`（profile 索引库）、`ledger`（单 profile 账本库）、`dataRevision`（每成功业务写事务递增的 ledger 版本）、`source hash`（旧 JSON SHA-256）、`旁路导入`（在 `Imports/<id>/` 建库后验证再原子切换）、`migration issue`（阻止或记录的旧数据问题）。

用例：创建/切换/归档 profile，启动 schema upgrade，读写领域聚合，导入 schema 4 JSON，预览/导入 format 2 备份，重试中断导入，catalog 损坏后扫描重建索引。非目标：领域计算、HTTP DTO、备份包编排、跨 profile 汇总、ORM/连接池、旧 schema 2/3 或 backup 1 的兼容实现。

## 2. 入口、输出、依赖与权限

| 项目 | 内容 |
| --- | --- |
| 入口 | application 启动、profile use cases、各领域 use case 的 repository/transaction port、backup 的一致性快照端口。 |
| 输出 | 领域对象/只读投影、事务提交结果、`dataRevision`、迁移报告/issue、健康结果、catalog/profile 状态。 |
| 依赖方 | application、所有领域模块、backup-recovery、release-verification。 |
| 被依赖方 | JDBC + sqlite-jdbc、Jackson（仅导入）、文件系统、Clock、observability。 |
| 方向 | `application/domain ports ← persistence adapters`；persistence 不依赖 HTTP、Electron、Vue 或 domain 计算实现。 |

授权：所有普通 repository 请求隐含当前活动 profile，不能传任意路径或 profile ID；只有 `profiles.switch`、启动和恢复流程可改变活动库。写入需要 application 排他写门；catalog/ledger 切换、恢复、schema migration 互斥。

## 3. 实体、规则与不变量

- 表、字段、外键、CHECK、索引和删除原则以[数据库](../database.md)为唯一来源。本模块不得新增“全量 LedgerState JSON”缓存表。
- 每个数据库连接在首次使用前验证 `foreign_keys=ON` 并设置已批准的 `busy_timeout`；未启用外键即拒绝该连接。
- 所有 application 写用例在一个显式 SQLite 事务内完成业务变更、`ledger_meta.data_revision + 1`、`processed_operation` 写入；repository 从不自行提交。
- 同 `Idempotency-Key`/同 method/path/body hash 返回保存的 status/result；不同请求返回全局 `IDEMPOTENCY_CONFLICT`。`X-Request-Id` 只用于追踪。默认 7 天清理；危险恢复记录保留至下一次成功备份，清理只在成功事务后进行。
- 正常启动运行 `quick_check`；迁移、导入、备份/恢复验证运行 `integrity_check` 与 `foreign_key_check`。任何失败使该 ledger 只读并向 application 返回 `RECOVERY_REQUIRED`。
- SQLite 未知较高 schema 拒绝打开写入；`schema_history`/`catalog_schema_history` 已成功 migration 的 checksum 永不改写。

### Profile 生命周期

| 状态 | 允许转换 | 规则 |
| --- | --- | --- |
| `ACTIVE` | 切换为另一 active profile；归档其他 profile | `catalog_setting.active_profile_id` 必须指向它；同一时刻仅一个。 |
| `INACTIVE` | `ACTIVE`、`ARCHIVED` | 切换前关闭旧 ledger 连接，旧游标/`dataRevision` 失效。 |
| `ARCHIVED` | 无本期恢复命令 | 不可成为 active，不可正常打开写入；账本文件与备份仍保留。 |

创建 profile 在 catalog 事务插入 `profile` 后建立空账本并应用迁移/seed；空账本失败则回滚 catalog 行和仅本次创建的空目录。创建成功后按当前产品行为成为 active。归档当前 profile 返回 `VALIDATION_FAILED`；归档不是文件删除。

## 4. Schema 与旧数据迁移状态

内部导入状态（不额外暴露表）为 `DISCOVERED → COPYING → IMPORTING → VALIDATING → READY_TO_SWITCH → SWITCHED`；任一失败为 `BLOCKED`。状态依据临时目录、schema history、`source_import_hash` 和 `migration_issue` 可恢复判定，不依赖只在内存中的标记。

| 阶段 | 必须行为 | 失败与补偿 |
| --- | --- | --- |
| 发现 | 仅识别 schema 4/format 2；计算源哈希。 | schema 2/3、format 1/未知高版本：`MIGRATION_BLOCKED`，提示先用 C# v3.1；不猜测转换。 |
| 导入 | 在 `Imports/<profile-id>/ledger.db` 以保留 UUID 写入所有关系。 | 解析/金额/引用异常记录去重 issue；会改变财务事实的问题阻止切换。原 JSON 不改。 |
| 验证 | 核对记录/软删数、金额、引用、公式依赖、资产、规则/事件、布局与设置，并跑完整检查。 | 删除临时可重建文件或保留供诊断；旧活动库不动。 |
| 切换 | 关闭目标 profile 旧连接，原子移动已验证 DB，最后更新 catalog active 指针。 | 任一步失败恢复旧连接/旧 pointer；不产生双写。 |

已确认映射：旧 `profiles.json`→`profile`/`catalog_setting`；普通 finance record 的 `MetricTargetIds`→`record_metric_assignment`；旧 custom metric→`metric_definition`+公式；旧 JSON 备份 envelope 2 先校验 SHA-256 和 profile 后按同一导入器处理。按 [ADR-008](../decisions/ADR-008-direct-metric-multi-assignment.md)，`CustomIncrease`/`CustomDecrease` 转为同 ID `direct_metric_entry`，旧 `CustomMetricId` 与 0..n `MetricTargetIds` 去重后写入 `direct_metric_assignment`，零目标条目仍保留。金额超过两位小数、未知枚举、坏 UUID/FK、无法验证公式均生成 `migration_issue`；前两类及会改变账务结果的坏引用为阻断项。

## 5. 接口与数据映射

| 接口 | 输入/输出 | 事务/错误 |
| --- | --- | --- |
| profile application ports | profile 摘要及 revision；不返回绝对目录。 | catalog 事务；切换取得应用排他锁。 |
| `TransactionRunner.write` | application lambda → result + `dataRevision`。 | `BEGIN`/提交或完整回滚；记录幂等。 |
| repositories | 领域 ID、过滤和投影 → 聚合/分页结果。 | 参数化 SQL；不泄露 rowid/table。 |
| `MigrationService.inspect/import` | 文件能力输入 → 可展示的脱敏报告/issue。 | 旁路库；`MIGRATION_BLOCKED` 不改活动库。 |
| `HealthService.quick/full` | 健康摘要。 | 只读；失败转恢复模式。 |

常用查询必须使用数据库中已定义索引，记录列表固定按 `occurred_on DESC, id DESC` 游标；不加载整库模拟旧 `LedgerState`。`EXPLAIN QUERY PLAN` 是集成测试抽查项。

## 6. 可观测行为、验收与建议测试

事件：`db.open`, `db.schema-migrate`, `db.transaction`, `db.busy`, `db.health-check`, `migration.stage`, `migration.blocked`, `profile.switch`。仅记录版本、阶段、计数、耗时、错误码、脱敏 profile hash；不得记录 SQL 参数、路径或账本内容。

| 场景 | 可验证结果 | 建议层次 |
| --- | --- | --- |
| 空 profile 新建 | catalog 与空 ledger 均存在、seed 完整、成为 active。 | SQLite 集成 |
| 写用例中途异常 | 所有表、`dataRevision`、`processed_operation`均不变。 | SQLite 事务集成 |
| 相同 schema 4 导入两次 | 第二次不复制记录或 issue；源 JSON 未修改。 | 迁移集成 |
| schema 2/3、backup 1 | 稳定拒绝与升级指引，无新活动 DB。 | 迁移合同 |
| 导入断电/异常 | 旧 JSON/活动 DB 可读；重启可安全重试。 | 文件系统故障注入 |
| catalog 损坏、profile DB 完好 | 可扫描合法目录重建 catalog；不删除账本。 | 恢复集成 |
| 20,000 条基础记录分页 | 使用目标索引，首屏/写入达到全局暂定门槛。 | 性能/查询计划 |

## 7. 待决与升级

- WAL 是否启用只能由备份、杀进程和目标 Windows 文件系统实测决定；默认 journal 策略见 ADR-003，实施者不得提前切换。
- 需求 O2/O3（匿名真实样本、性能参考机）影响发布信心；先以合成 fixture 验证。
- 若需支持旧 schema、跨 profile 汇总或多进程写入，属于高级架构变更，不能在本模块私自扩展。
