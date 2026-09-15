# S0-005：SQLite catalog/profile 启动基座

## 1. 背景、目标、范围、非目标

单一结果：Java 后端在隔离目录创建或重开 `profiles.db` 与一个默认 profile 的 `ledger.db`，应用 V001 bootstrap migrations，验证外键/完整性，并把真实 schema/profile/READY 状态提供给 system status。

范围：catalog、ledger bootstrap 表、JDBC 连接/事务、迁移器、健康检查、默认 profile。非目标：财务业务表、旧 JSON 导入、备份、WAL、业务 endpoints、领域 seed。

## 2. 前置与引用

- 依赖：[S0-001](./S0-001-java-backend-baseline.md)；与 S0-002 可并行开发，最终 status 接线依赖 S0-002。
- 必读：[database §1–§3/§4.1/§4.7/§7–§9](../database.md)、[persistence-migration](../modules/persistence-migration.md)、[ADR-003](../decisions/ADR-003-sqlite-per-profile.md)、[api §10](../api.md)。

## 3. 允许修改

- `src/main/java/com/ledgerx/persistence/**`、`application/bootstrap/**`、`application/profile/**` 的最小 port/use case；
- `src/main/resources/db/{catalog,ledger}/migration/**`；
- 对应 `src/test/**`、测试 fixtures；
- S0-002 system status 的只读 provider/fixture 仅用于从 STARTING/null 更新为 READY/真实值；
- 本任务证据。

不得修改 Vue/Electron/旧代码、全局数据库/API 文档、财务业务表或引入 Flyway/Liquibase/连接池/ORM。

## 4. 数据与实现契约

1. catalog V001 只创建：`catalog_schema_history`、`profile`、`catalog_setting` 及数据库文档索引/约束。
2. ledger V001 只创建：`schema_history`、`ledger_meta`、`processed_operation`。其列/语义精确遵循 database；业务表留给后续 migration。
3. migration 文件名 `V001__bootstrap.sql`，SHA-256 写 history；已成功版本 checksum 不符、未知高版本或 success=0 时拒绝写入。
4. 空目录创建 UUID profile，名称精确“默认空间”，目录 `Profiles/<lowercase-uuid>/ledger.db`，并设 active。创建 catalog 行、目录、ledger 任一步失败时回滚行并只清理本次确认创建的空文件/目录。
5. 已有 catalog 时重开其 active profile；不得再建第二默认空间。active 缺失/归档/路径越界进入 `RECOVERY_REQUIRED`，不猜另一个空间。
6. `relative_directory` 规范化后必须仍位于应用根 `Profiles` 下；禁止绝对路径、`..`、链接逃逸。
7. 每个连接先执行并验证 `PRAGMA foreign_keys=ON`，设置固定 `busy_timeout=5000`。本期不启用 WAL，不使用连接池。
8. 正常启动执行 `quick_check` + `foreign_key_check`；测试/完整检查执行 `integrity_check` + FK。非 `ok` 进入只读恢复状态。
9. `TransactionRunner` 负责 begin/commit/rollback，repository 不提交。测试异常后连接可继续使用。
10. S0 完成时 status 成功变为 `schemaVersion:1, activeProfileId:<uuid>, state:"READY"`，capabilities 仅列当时已实现的 `system.status`；后续 P1-002 已实际实现 profiles API，并在 READY 时增加 `profiles.read`、`profiles.write`，见 [profiles API](../api/profiles-api.md)。

## 5. 用户与持久化结果

| 操作 | 前端 | 跨层 | 业务 | 持久化 | 边界 |
| --- | --- | --- | --- | --- | --- |
| 首次启动 | status READY | schema/profile | 一个默认空间 | 两 DB/V001/history | 空目录、创建中断 |
| 再次启动 | 同 profile READY | 同 UUID | 不重复创建 | 行数/checksum 不变 | 重复迁移 |
| 坏 catalog/ledger | recovery 状态 | 脱敏错误 | 禁止业务写 | 原文件不删 | 高版本/checksum/path逃逸 |

## 6. 验收

- 空隔离目录创建恰好 1 profile/ledger，重开后 ID、name、active、schema/history/checksum 一致。
- 所有预期表/列/约束/索引存在；没有任何 finance/category/account/metric 等业务表。
- foreign_keys 实际为 1；quick/full checks 成功。人工插入坏 FK 在约束处失败。
- transaction 中途抛错后所有本事务行回滚；connection 可进行后续成功事务。
- 修改 migration checksum/写高版本/relative path 逃逸均进入恢复状态，原文件和目录不被删除。
- status 通过真实 HTTP 返回 READY/1/profile；日志不含完整绝对路径或 profile 原 ID。
- 测试只接触每例新建隔离目录，完成后只清理已验证的该测试目录。

## 7. 测试与真实链路

运行 SQL schema/constraint、JDBC transaction、migration idempotence、路径安全、健康检查和重开测试；并经 S0-002 真实 HTTP 验证 status。Vue/Electron/旧数据/发布包不适用。

## 8. 禁止、升级与报告

禁止业务表、WAL、内存数据库替代 SQLite 文件、真实用户目录、自动修复坏库、修改已成功 migration、引入持久化框架。若 sqlite-jdbc/Java11 不能提供 FK/check，或文档列不足，停止升级。

完成报告：文件/DDL checksum、测试表/索引/约束数量、首次/重开/故障案例、真实 HTTP 证据、隔离目录、未测项。

## 9. 实际完成报告（2026-09-13）

- 文件：新增 `persistence` 下 SQLite 连接约束、V001 迁移器、健康检查、事务 runner、catalog/profile/ledger bootstrap 和脱敏快照；新增 `application/bootstrap/ApplicationBootstrap`；新增 catalog/ledger `V001__bootstrap.sql`；将既有 `system/status` 环境接线到 `LEDGERX_DATA_DIR`。未修改 Vue、Electron、旧 `web/`/`native/` 或全局数据库/API 文档。
- DDL：catalog V001 SHA-256 `020cc0611162d01acc01677a37fb1447b507096cf724cbd1ee0cb97474078d42`；ledger V001 SHA-256 `80a47f7411716f67102c269bf548b6774db2008eb25d32d1aef4b5968d9c572d`。迁移 history 成功行、checksum、未知高版本和 `success=0` 均有验证。
- Schema 验证：catalog 恰好 3 张表、1 个文档索引；ledger 恰好 3 张表、1 个 `expires_at` 索引；未创建 finance/category/account/metric 等业务表。连接实际验证 `foreign_keys=1`、`busy_timeout=5000`，并检查 quick/full integrity 与 foreign-key check；默认 journal 下未生成 WAL/SHM。
- 生命周期验证：隔离目录首次启动创建一个“默认空间”、UUID profile、`Profiles/<uuid>/ledger.db`、两份 V001 history 和 `dataRevision=0`；重开保持同一 profile、行数和 history；路径越界、checksum 改动、未来版本、未完成迁移均拒绝并由 status provider 返回 `RECOVERY_REQUIRED`，不删除原账本。
- 事务验证：中途异常回滚全部写入，连接随后可提交成功事务；catalog 外键坏引用在 SQLite 约束处失败。
- Java 验证：`mvnw.cmd -q -Dmaven.compiler.fork=true test` 通过 `12/12`（Java11 baseline `1/1`、S0-002 REST `6/6`、S0-005 persistence `5/5`）。JDK 11 会对 Windows 缓存 JAR 打印已知 ZipFS `AccessDeniedException` 诊断，但 Maven 测试进程以 0 退出。
- 最后一次符号链接拒绝安全改动后的定向回归：`-Dtest=com.ledgerx.persistence.PersistenceBootstrapTest test` 通过 `5/5`。
- 真实 HTTP：`HttpServerMain` 使用隔离 `LEDGERX_DATA_DIR` 启动真实 Java 进程，读取 `LEDGERX_READY` 后经 loopback HTTP 请求 status，返回 `200`、`READY`、`schemaVersion=1`、`dataRevision=0` 和 profile UUID；该链路 `1/1`，测试目录已清理。
- 未测/不适用：未执行 Electron、Vue、业务表/旧 JSON 导入、WAL/备份、发布包和桌面快捷方式；这些是后续任务或本任务明确非目标。Node/前端验证记录在 [S0-003](./S0-003-vue-frontend-foundation.md)。
