# P1-001 Catalog V002 验证记录

- 日期：2026-09-13
- 结果：PASS
- 环境：Windows 11 x64、Java `11.0.15.1`、Maven Wrapper `3.9.11`

## 变更证据

- `MigrationRunner` 现在按显式列表读取严格 `Vddd__name.sql` 资源，要求从 V001 连续且顺序唯一；每个版本独立校验 SHA-256，在单一 SQLite transaction 中应用全部缺失版本。
- catalog 新增 `V002__catalog_operations.sql`：`catalog_setting.revision`、`catalog_processed_operation` 和 expires 索引。
- `ProfileBootstrap` 对 catalog 应用 V001+V002，但 `BootstrapSnapshot.schemaVersion` 改为来自 ledger V001，避免 catalog version 2 被错误暴露为业务 schema 2。
- catalog V001 SHA-256：`020cc0611162d01acc01677a37fb1447b507096cf724cbd1ee0cb97474078d42`（未修改）。
- catalog V002 SHA-256：`9d9b5e8a51847aa5d3e8e8515dc40ac7bfc1a1e5cd2cd0a9f7ac213c3cd8ff60`。
- ledger V001 SHA-256：`80a47f7411716f67102c269bf548b6774db2008eb25d32d1aef4b5968d9c572d`（未修改）。

## 实际验证

| 层次 | 命令/数量 | 结果 |
| --- | --- | --- |
| 定向 migration + persistence | Maven 定向测试，5 + 7 = 12 | 12/12；fresh、V001 升级、重开幂等、非法资源、坏 checksum、未完成/高版本、后续版本失败回滚、V002 CHECK/PK/索引均覆盖 |
| Java 完整回归 | `mvnw.cmd -q -Dmaven.compiler.fork=true test package` | 19/19：baseline 1、HTTP 6、migration 5、persistence 7；0 failure/error/skipped |
| 构建产物 | Maven package | JAR 生成；现有 runtime classpath 文件存在且非空 |

真实文件链路使用 JUnit `@TempDir` 下的 SQLite catalog/ledger：人工 V001 catalog 升级后 ID、名称、相对目录、创建/最近打开时间、归档、profile revision、active pointer、ledger bytes 和 dataRevision 保持；随后正常 bootstrap 仅按既有语义更新 last-opened 时间，status schemaVersion 仍为 1。

## 范围与残余风险

- 使用真实 SQLite 文件并关闭/重开；没有接触正式 `%LocalAppData%\LedgerX`。
- Node、Vue、Electron、真实桌面链路、发布包和快捷方式不适用；本任务没有修改这些层。
- Java 11 定向编译仍偶发输出已知 Windows ZipFS/JAR close `AccessDeniedException` 诊断，但 Maven 进程为 0 且 Surefire XML 明确 12/12；随后完整 `test package` 无该诊断并为 19/19。
- 本任务只提供 profiles API 的迁移前置，profiles HTTP/use case 尚未实现，由 P1-002 执行。
