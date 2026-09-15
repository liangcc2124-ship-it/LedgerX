# P3-001：核心分类与账户 seed V004

- 状态：PASS（2026-09-14，真实 SQLite 2/2）
- 单一结果：每个 V003 ledger 在首次升级到 V004 时精确获得核心系统分类、分类类型关联和系统账户“现金储备”，重开不重复写入或覆盖数据。

## 1. 背景、目标、范围与非目标

P2-004 已创建 category、category_record_type、financial_account 和 finance_record 物理表，但刻意没有 seed。本任务实施已接受的核心 catalog 契约，使后续分类与账户 REST 可依赖相同的初始数据。

范围：新增 V004 core_catalog_seed SQL、将 V004 追加到 ledger migration runner、真实 SQLite 的 fresh/V003-upgrade/reopen/failure 测试，以及实际影响的文档和验证记录。

非目标：HTTP、application/domain 服务、Vue/Electron、记录写入、资产/分摊、指标、导入、备份、修改 V001–V003、修改账户/分类 API、修改旧 WPF/React、增加依赖。

## 2. 前置依赖及引用规范

- 前置：P2-004 PASS，当前 ledger schema 为 V003。
- 必读：AGENTS.md；docs/database.md 第 1、4.2、4.2.1、6、7 节；docs/decisions/ADR-009-core-catalog-seed.md；docs/contracts/core-catalog-v1.md 全文；docs/modules/ledger-records.md 第 3.1 节；docs/tasks/README.md；P2-004。
- seed 的唯一内容来源是 docs/contracts/core-catalog-v1.md。若该清单、数据库字段或当前 V003 实物不一致，停止实现并升级；不得按旧 native 或 web 源码自行补数据。

## 3. 允许/预计修改的模块和文件

- 新增 src/main/resources/db/ledger/migration/V004__core_catalog_seed.sql。
- src/main/java/com/ledgerx/persistence/LedgerBootstrap.java，仅追加 V004 resource 到现有有序列表。
- src/test/java/com/ledgerx/persistence 下仅与 V004 migration/seed/reopen/rollback 直接相关的测试；必要时增加一个仅用于 migration-runner failure 的测试 SQL fixture。
- docs/database.md（将“待实施”改为实际迁移状态，不改变 seed 契约）；本任务；新增 docs/verification/P3-001-core-catalog-seed.md。

禁止修改 API、HTTP、application、Vue/Electron、pom.xml、catalog migration、V001/V002/V003、旧 WPF/React、docs/contracts/core-catalog-v1.md 的业务内容。若 V004 发现 seed 契约有错误，只报告，不修改该契约。

## 4. 实现步骤与必须遵守的持久化契约

1. migration 文件名和 description 必须精确为 V004__core_catalog_seed.sql 与 core_catalog_seed。LedgerBootstrap 的资源列表只能从 V001、V002、V003、V004 顺序追加；不得改已有 checksum。
2. V004 必须在单一既有 MigrationRunner transaction 中插入 seed 契约列出的全部 71 个 category 行、65 个 category_record_type 行和 1 个 financial_account 行。先插入父分类，再插入子分类，再插入类型关联；所有 ID、name、parent_id、sort_order、default_recognition_method、recommended_depreciation_method、kind、balance_side 和现金标记逐项等于 seed 契约。
3. 全部 system category 都写 is_system=1、is_legacy_custom=0、archived_at=null、revision=0。无类型父分类不插入 category_record_type 行。不得插入任何 finance_record、processed_operation、allocation、asset 或指标数据。
4. 系统账户必须精确为 seed 契约的 ID、name=现金储备、kind=CASH、balance_side=ASSET、opening_balance_minor=0、include_in_available_cash=1、is_system=1、archived_at=null、revision=0。opening_on 使用 V004 运行机器的本地自然日；不能写固定日期、UTC 日期或 Java 测试当前日期。SQLite SQL 必须使用本机 localtime 语义。
5. category/account 的 created_at 和 updated_at 都写 V004 执行时的 UTC RFC3339 instant；可使用 SQLite UTC 时间函数。不得依赖 ledger_meta：fresh ledger 在 migration 运行时尚未插入 ledger_meta。
6. 使用普通 INSERT，而不是 OR REPLACE 或冲突忽略。固定 ID、活动名称或账户名称冲突必须令 V004 整体失败并由 MigrationRunner 回滚，绝不覆盖、归档或删除已有行。P2 没有这三张表的业务写入口，因此该失败是保护性异常，不应设计自动合并。
7. V004 是 schema seed：不写 processed_operation，不更新 ledger_meta.data_revision，也不修改 ledger_setting。fresh ledger 随后由既有 bootstrap 写入 data_revision=0；V003 upgrade 保留升级前 data_revision 原值。

## 5. 用户操作、跨层字段、结果与边界

| 用户操作 | 前端反馈 | 跨层字段 | 业务/持久化结果 | 边界 |
| --- | --- | --- | --- | --- |
| 首次启动新空间 | 本任务不新增 UI | Java bootstrap、schema history | V001→V004 后有 71 分类、65 类型关联、1 系统账户 | ledger_meta 在 migration 后才创建 |
| 打开已有 V003 空间 | 现有 READY 行为保持 | schema history、已有 dataRevision | 原 V003 数据保留并一次性 seed | 名称/ID 冲突使升级回滚，不能半 seed |
| 重开 V004 空间 | 无可见重复项 | schema history | 不重复插入、不覆盖系统账户可编辑字段 | 已成功 V004 checksum 被校验 |

## 6. 验收标准

- fresh ledger 的 schema_history 有 V001 至 V004 四条成功记录；分类数=71、类型关联数=65、账户数=1，所有 seed 字段与 core-catalog-v1 逐项一致。
- 从真实 V003 ledger 升级后，原 ledger_meta、ledger_setting、processed_operation 值逐列不变；仅新增约定 seed，schemaVersion=4。
- fresh 和 V003-upgrade 都通过 foreign_key_check 与 integrity_check；重开后计数、ID、revision、dataRevision 和账户 openingOn 不变。
- 系统账户 openingOn 是迁移执行主机 localtime 当日，金额响应前的存储值为 0 分；不得以测试机 UTC 日期替代。
- 制造 stable ID 或活动名称冲突时，V004 失败且该连接中的 category、category_record_type、financial_account 与 schema_history 都回到升级前状态；V003 ledger 可再次重开。
- 不新增 HTTP route、领域用例、Vue 页面或任何财务记录。全量 Maven test package 通过。

## 7. 测试层次、命令与真实链路

- 持久化：必须在隔离临时目录使用 sqlite-jdbc 文件库验证 fresh、V003-upgrade、reopen、约束冲突回滚及 health check；不能只检查 SQL 字符串。
- 必跑命令：仓库根执行 .\mvnw.cmd -q '-Dmaven.compiler.fork=true' test package。若本机 Maven 需要已批准的用户目录参数，报告实际参数和 Surefire 数量。
- HTTP、Vue、Electron、发布包和桌面快捷方式：不适用；不得把 migration 测试报告成真实用户界面验证。

## 8. 禁止事项、升级、文档与完成报告

禁止随意补更多“常用”分类、使用显示名生成 ID、改变 type 枚举、清理冲突用户数据、增改 V003、增加 trigger/WAL/ORM、把 seed 当成 API mutation 或更新 dataRevision。

以下情况必须停止并升级：seed 契约与 V003 字段/约束不兼容；MigrationRunner 不能原子回滚 V004；无法可靠获得系统 localtime 日期；P2 后已有真实业务数据与 seed 名称冲突；需要改变 API、数据库约束或旧 JSON 映射。

完成时更新本任务状态和验证记录，报告：修改文件；新增测试和总通过数量；fresh/upgrade/reopen/conflict rollback 的证据；真实隔离 SQLite 位置已核验；HTTP/Vue/Electron/发布包/快捷方式为何不适用；未测试项与残余风险。不得声称分类或账户 REST 已完成。
