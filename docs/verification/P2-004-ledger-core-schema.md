# P2-004 Ledger V003 核心事实数据表验证记录

- 日期：2026-09-14
- 任务：[P2-004](../tasks/P2-004-ledger-core-schema-v003.md)
- 结果：实现完成；本机 Java 11 隔离验证和标准 Maven `test package` 均通过。

## 变更范围

已将账本迁移 runner 从 V002 接入 V003 `ledger_core_facts`，新增 `category`、`category_record_type`、`financial_account`、`finance_record` 四张物理表，以及任务规格要求的普通索引和活动名称 partial unique index。未新增 seed、业务写入、HTTP endpoint、领域服务、资产/指标/预警表或 Vue/Electron 代码。

## 验证结果

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| fresh profile | PASS | 创建 ledger 后 schema version=3、history 三条；四张新表为空；字段/默认值/索引/partial 条件匹配 |
| V002 → V003 | PASS | settings、ledger_meta、processed_operation 行和值保持；无业务 seed；`data_revision` 不变 |
| 重开 | PASS | V003 history 数量和安装时间不变；重复打开不重复 DDL/索引 |
| 约束与关系 | PASS | 外键、显式非空主键、记录类型、CNY、金额大于零、note 长度、活动同级/账户名称唯一、归档名称复用、负债不可用现金均按预期执行 |
| 健康检查 | PASS | 真实 sqlite-jdbc 文件执行 `integrity_check` 与 `foreign_key_check` |
| 失败回滚 | PASS | 人为失败的 V003 fixture 不留下半成品表或 history 记录；V002 ledger 可再次打开，随后真实 V003 可成功应用 |

新增 `LedgerV003SchemaTest` 4 个测试全部通过；连同受 V003 影响更新断言的 `PersistenceBootstrapTest`，使用 Java 11、SQLite JDBC 3.53.4.0、JUnit Jupiter 5.11.4 的隔离执行共 11/11 通过。每个测试使用 JUnit `@TempDir` 临时 SQLite 文件，运行后自动清理。

初次 Maven 尝试曾受 wrapper 用户目录和镜像/插件缓存限制；在用户批准的本机环境设置 `MAVEN_OPTS=-Duser.home=C:\Users\liang` 后重新执行 `test package`，Surefire 31/31 通过并生成最新 `target/cp.txt`/class 产物。随后既有 settings 真实 Java HTTP 冒烟仍为 1/1 PASS，确认 V003 产物不会破坏已交付的设置保存/重载路径。

## Maven 工具链限制

按任务规格尝试了：

```text
./mvnw.cmd -q '-Dmaven.compiler.fork=true' test package
```

初次 wrapper 默认尝试创建 `C:\.m2`，且本机镜像/插件缓存曾无法解析依赖；本次通过 `MAVEN_OPTS=-Duser.home=C:\Users\liang` 使用本机缓存重跑后成功。项目源码和依赖未因此改动，Surefire 31/31 结果已纳入本记录。

## 不适用与残余风险

- HTTP、Vue、Electron、发布包、签名和桌面快捷方式：本任务没有这些改动，由后续任务验证。
- Node 22：本任务是 Java/SQLite 迁移，不执行 Node 22 复测。
- 分类/账户/记录的领域校验、seed、REST 和旧 JSON 导入仍未下发；后续必须遵守 `docs/tasks/README.md` 的契约门禁。
