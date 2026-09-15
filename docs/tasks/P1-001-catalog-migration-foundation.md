# P1-001：Catalog V002 与有序迁移基座

- 状态：已实施，PASS
- 单一结果：既有 V001 catalog 可无损升级到 V002，并提供 catalog revision 与 catalog mutation 幂等表；ledger schemaVersion 仍正确报告自身版本 1。

## 1. 背景、目标、范围与非目标

profiles mutation 会创建或切换活动 ledger，幂等结果不能可靠存入“操作前的 active ledger”。全局 API 又要求 mutation 的成功结果可在重启后回放。因此 `profiles.db` 需要 catalog 级 operation 表。当前 `MigrationRunner` 只支持一个硬编码为 version 1 的资源，且 `ProfileBootstrap` 把 catalog 版本误当 ledger schemaVersion；在加入 V002 前必须修正。

范围：有序 checksummed migration runner、catalog V002、profile bootstrap 版本投影、SQLite 回归和文档。非目标：实现 profiles HTTP/use case、业务表、旧 JSON 导入、Vue/Electron、修改 V001 文件或启用 WAL。

## 2. 前置与引用

- 依赖：S0-005、BUG-004 已通过。
- 必读：[数据库 §3、§8](../database.md)、[API §10](../api.md)、[profiles-catalog](../modules/profiles-catalog.md)、[profiles API](../api/profiles-api.md)。

## 3. 允许修改

- `src/main/java/com/ledgerx/persistence/MigrationRunner.java`
- `src/main/java/com/ledgerx/persistence/ProfileBootstrap.java`
- `src/main/resources/db/catalog/migration/V002__catalog_operations.sql`
- `src/test/java/com/ledgerx/persistence/**`
- 本 Task Spec、`docs/database.md`、`docs/api.md`、`docs/tasks/README.md` 及对应验证记录

禁止修改 V001、ledger DDL、HTTP、Vue、Electron、旧 WPF/React 或新增依赖。

## 4. 实现契约

1. `MigrationRunner` 接受一个有序 migration resource 列表；从文件名严格解析正整数版本，拒绝重复、缺口、乱序语义和非法名称。
2. 每个资源各自计算 SHA-256；存在 history 的每一成功版本必须与当前资源 checksum 一致。未知高版本、缺失资源对应的历史版本或 `success!=1` 拒绝打开。
3. 一个 migrate 调用在同一 SQLite transaction 中按版本应用所有缺失资源并逐条写 history；任何版本失败全部回滚。重复执行不重复 SQL/history。
4. 保留单资源构造方式供 ledger V001 使用；不得改变 V001 bytes/checksum。
5. catalog V002：给 `catalog_setting` 添加 `revision INTEGER NOT NULL DEFAULT 0 CHECK (revision>=0)`；创建 `catalog_processed_operation`，字段与约束以数据库文档为准；创建 expires 索引。
6. `ProfileBootstrap` 对 catalog 应用 V001+V002，对 ledger 仍只应用 V001。返回的 `BootstrapSnapshot.schemaVersion` 必须来自 `ledger.db`，不得使用 catalog latest version。
7. 单独执行 V001→V002 migration 不改变 profile ID/name/path/timestamps/archive/revision、active pointer或任何 ledger bytes/rows；只添加 V002 结构/history。随后正常 bootstrap 可按既有语义更新 active profile 的 `last_opened_at`。

## 5. 用户、跨层和持久化结果

| 用户操作 | 前端 | 跨层 | 业务 | 持久化 | 边界 |
| --- | --- | --- | --- | --- | --- |
| 启动旧 V001 数据 | 仍 READY | status schemaVersion=1 | 同 active profile | catalog history 1→2，revision=0 | V002 失败则恢复模式，旧事实不改 |
| 再次启动 | 无变化 | 同 profile/revision | 不重复迁移 | history 仍2 | checksum/高版本拒绝 |

## 6. 验收标准

- 新目录创建 catalog V001+V002、ledger V001；status/BootstrapSnapshot schemaVersion=1。
- 人工构造的 V001 catalog 在 migration 边界升级后 profile 全字段、active pointer、ledger 文件内容和 dataRevision 不变；随后 bootstrap 只允许按既有规则更新 `last_opened_at`。
- `catalog_setting.revision=0`；`catalog_processed_operation` 为空且所有 CHECK/PK/索引有效。
- 重跑 migration history 恰好两行，checksum/installed_at 不变化。
- 修改任一已成功 checksum、插入 version3、`success=0`、资源版本重复/缺口/非法名称均稳定拒绝并回滚。
- V002 中途 SQL 失败的合成测试不留下 revision 列、operation 表或 version2 history。
- Java 现有 HTTP/status、持久化测试继续通过。

## 7. 测试、禁止与升级

运行 Java 定向 persistence 测试和完整 `test package`。必须使用每例独立临时 SQLite 文件并验证保存/关闭/重开；不需要 Node、Vue、Electron、发布包或快捷方式。

禁止修改 V001 checksum、删除失败库、自动修复未知版本、引入 Flyway/Liquibase/ORM。若 SQLite 当前版本不能事务化本 V002 的 ALTER，停止并升级迁移方案；不得通过忽略错误继续。

完成报告：迁移资源/checksum、fresh/upgrade/reopen/rollback 数量、完整 Java 测试数量、隔离数据和未测试项。

## 8. 实际完成报告（2026-09-13）

- 实现：`MigrationRunner` 支持严格、连续、有序的多资源迁移；新增 catalog V002；修正 catalog version 误作为 ledger schemaVersion 的潜在错误。没有修改 V001、ledger DDL、HTTP、Vue 或 Electron。
- 校验和：catalog V001 `020cc0611162d01acc01677a37fb1447b507096cf724cbd1ee0cb97474078d42`；catalog V002 `9d9b5e8a51847aa5d3e8e8515dc40ac7bfc1a1e5cd2cd0a9f7ac213c3cd8ff60`；ledger V001 `80a47f7411716f67102c269bf548b6774db2008eb25d32d1aef4b5968d9c572d`。两份 V001 均未修改。
- 定向验证：migration 5/5、persistence 7/7；覆盖 fresh、真实 catalog V001→V002、重开、非法/缺口资源、坏历史、失败回滚与 V002 约束。
- 完整验证：Java `test package` 19/19，0 failure/error/skipped；JAR 与非空 classpath 产物存在。
- 数据隔离：全部 SQLite 生命周期测试使用 `@TempDir`，未读取或修改正式用户目录。
- 不适用：Node/Vue/Electron、真实桌面链路、发布包与快捷方式；本任务只改变 Java migration/persistence。
- 详细证据：[P1-001 验证记录](../verification/P1-001-catalog-v002.md)。
