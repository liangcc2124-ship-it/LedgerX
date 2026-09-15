# ADR-003：每用户空间一个 SQLite 账本，替代活动 JSON

- 状态：已接受
- 日期：2026-09-12

## 背景

当前实现把整个 `LedgerState` 序列化为每用户空间一个 JSON。原子替换和 SHA-256 备份提供了基础恢复能力，但关系约束、局部查询、事务边界和大数据量写放大有限。旧需求文档也已把 SQLite 作为长期本地存储方向。

LedgerX 的 Java 后端是单进程、单写者；Electron/Vue 不访问数据库，因此没有部署数据库服务器的理由。当前用户空间的数据物理隔离和独立备份语义应尽量保留。

## 决策

1. 使用 SQLite；`profiles.db` 保存用户空间索引，每个用户空间使用独立 `Profiles/<id>/ledger.db`。
2. 使用标准 JDBC + Xerial `sqlite-jdbc`，不用 ORM、连接池或通用 migration 框架。
3. 每连接显式 `PRAGMA foreign_keys=ON`；所有 application 写用例使用事务。
4. schema 通过带校验和的顺序 SQL 迁移；启动拒绝未知更高版本。
5. 当前 v3.1 schema 4 JSON/备份格式 2 只读导入到旁路数据库，完整校验后原子切换；原文件不删除、不双写。更旧格式由现有 C# v3.1 先升级，Java 版不重复维护旧迁移链。
6. 备份使用 SQLite 一致性快照 + manifest + SHA-256；恢复在隔离文件验证。
7. 首期不持久化指标/报告缓存，也不默认启用 WAL；HTTP 并发由 application 事务边界收敛，有性能证据后再决定。

## 备选方案

- **继续 JSON**：迁移最少，但不能给跨实体关系和局部事务提供数据库级保证，记录增长时每次整文件重写。
- **单一 SQLite 包含所有 profiles**：文件更少、全局备份方便，但每条查询都必须正确带 profile 作用域，且改变当前独立恢复语义。
- **H2/Derby**：纯 Java 或传统 JDBC 体验好，但 SQLite 在单文件、工具生态和跨版本可读性上更适合本地个人数据。
- **PostgreSQL/MySQL**：需要服务进程和运维，不符合离线单机规模。
- **ORM + Flyway/Liquibase**：对目前有限 schema 增加依赖和抽象；迁移冲突/团队规模增长时再评估。

## 后果

- 好处：原子事务、外键/CHECK/UNIQUE 约束、索引、分页和成熟恢复工具；继续保持每空间隔离。
- 成本：需要明确 SQL、迁移器、备份一致性和 JDBC native library 打包测试。
- 风险：错误文件复制可能遗漏 WAL；因此备份不得复制正在写入的裸文件。
- 风险：Java 版运行后的新数据不会回写旧 JSON；回滚只能使用切换前快照，这必须在 UI 和发布说明中清楚表达。
- 回退/变更路径：迁移期保留原 JSON；若 Java 版未切换成功，继续使用旧应用。成功切换后通过新版备份恢复，不做隐式反向迁移。

## 依据

- [Xerial sqlite-jdbc](https://github.com/xerial/sqlite-jdbc)
- [SQLite foreign key support](https://www.sqlite.org/foreignkeys.html)
- [SQLite transactions](https://www.sqlite.org/lang_transaction.html)
- [SQLite integrity check](https://www.sqlite.org/pragma.html#pragma_integrity_check)
