# P4-001：基础记录领域与持久化

- 状态：PARTIAL（基础 application/SQLite 已实现；P3-005 前置集成尚未完成）
- 单一结果：三类基础记录的查询、创建、完整编辑、软删除、恢复和账户余额影响可通过 Java application + 真实 SQLite 稳定执行并在重开后保持。

## 1. 背景、目标、范围与非目标

ADR-010 已把基础版收敛为收入、固定支出、弹性支出。P3 提供分类和账户；本任务只建立不依赖 HTTP 的领域/application/repository 能力，为下一任务接 REST。

范围：记录值对象和校验、记录 query/mutation application 接口、JDBC repository、事务/幂等/dataRevision、账户余额联动、真实 SQLite 生命周期测试和实际影响文档。

非目标：HTTP 路由/JSON、Vue/Electron、schema migration、资产/分摊/指标/应付款、批量、永久删除、文件备份、金融安全、通用 repository/DI 框架。

## 2. 前置依赖与引用规范

- 前置：P3-001 至 P3-005 PASS。
- 必读：全局 AGENTS；[ADR-010](../decisions/ADR-010-basic-ledger-scope.md)；[需求 §3、§5–§7](../requirements.md)；[架构 §4、§6、§9](../architecture.md)；[数据库 §4.2–§4.3、§5–§7](../database.md)；[模块规格](../modules/ledger-records.md)；[具体 API §1–§2、§5–§9](../api/ledger-records-api.md)；P3-003。
- 复用现有 ProfileApplicationService active context、TransactionRunner、LedgerCatalogRepository/账户读取、processed_operation 和 dataRevision 模式。内部 Java API 命名遵循 profile/settings/P3 既有方式，不新建通用框架。

## 3. 允许/预计修改的模块和文件

- 新增 `src/main/java/com/ledgerx/application/ledger/` 下基础记录专用的 query、draft、projection、result、exception/mutation 类型。
- `src/main/java/com/ledgerx/application/profile/ProfileApplicationService.java`：仅增加 active profile 基础记录 application 方法；不增加 capability。
- `src/main/java/com/ledgerx/persistence/LedgerCatalogRepository.java` 或 P3 已建立的同职责 repository：仅增加记录 CRUD、keyset 查询及余额所需参数化 SQL。
- `src/test/java/com/ledgerx/application/ledger/`、`src/test/java/com/ledgerx/persistence/` 下直接相关测试。
- 本任务、`docs/modules/ledger-records.md` 与 `docs/verification/P4-001-basic-records-persistence.md`。

禁止修改 HTTP server、frontend、electron、migration SQL、pom/package manifests、全局 API/数据库结构、旧 WPF/React。

## 4. 实现步骤与必须遵守的契约

1. 实现 `RecordType` 仅含 `INCOME/FIXED_COST/VARIABLE_COST` 的公开基础枚举。若数据库存在其他类型，基础 query 不返回，按 ID mutation 视为不可操作；不得转换、删除或修改该行。
2. Draft 精确含 `occurredOn,recordType,amount,currency,categoryId,settlement{mode,accountId,settlementOn},note`。金额进入 application 前是 decimal string 的已验证精确值；领域保存前再次保证 `>0`、CNY、最多两位并安全转 long minor。
3. `settlement.mode` 只能为 PAID_FROM_ACCOUNT；category/account 必须属于当前 active ledger 且 ACTIVE；系统分类须包含 recordType，自定义空 type 行可用；settlementOn 不早于 account.openingOn。occurredOn 与 settlementOn 不互相限制。
4. 插入 finance_record 时固定：income_source NULL、is_self_generated_income 0、is_non_essential 0、deleted_at NULL、revision 0；不得写 record_metric_assignment、allocation_plan、fixed_asset 或其他高级表。
5. Create/replace/trash/restore 使用现有 process-local write gate 与 TransactionRunner。成功事务内同时写业务行、ledger_meta.updated_at/data_revision、processed_operation；成功后才刷新内存 dataRevision。一次 mutation 只增加一次 dataRevision。
6. Replace 仅 ACTIVE，保留 id/createdAt，完整替换 draft 字段，revision+1。Trash 仅 ACTIVE，写 deletedAt/revision+1；Restore 仅 TRASHED，清 deletedAt/revision+1。Restore 允许引用已经归档但仍存在的原分类/账户。
7. Query 固定排序 `occurred_on DESC,id DESC`，只允许 API 文档列出的 status/date/type/category/account/query/amount/limit/keyset 条件；所有 SQL 参数化，limit 最大 200。query 只匹配 category.name 和 note 的受限子串。
8. 记录投影必须 join category/account 并返回引用的 name/status。账户余额继续由 P3-003 的读取计算；验证其只计 ACTIVE、PAID_FROM_ACCOUNT、settlementOn<=asOf 的三类记录，并按 BalanceSide 使用模块规格中的方向。
9. 幂等重放沿现有规则；校验、引用、revision、状态或 SQLite 错误均不得产生 operation、dataRevision 或部分业务写。

## 5. 用户操作、反馈、跨层数据、业务/持久化和边界

| 用户操作（后续 UI） | 本任务可观察结果 | 关键字段 | 业务/持久化结果 | 边界 |
| --- | --- | --- | --- | --- |
| 新增收入/支出 | application 返回完整 record/revision 0 | type、amount、两日期、categoryId、accountId、note | 一行 record + operation，余额方向正确 | 非法金额、归档引用、开户日前结算 |
| 编辑记录 | 返回 revision+1 | 完整 draft、expectedRevision | 原行完整替换，dataRevision+1 | stale revision、TRASHED |
| 删除/恢复 | 状态和余额随最终状态变化 | id、expectedRevision、key | deletedAt 写入/清除，可重开 | 重复命令、归档历史引用 |
| 列表/筛选 | 稳定 keyset 顺序 | filter、limit、last key | 只读，无 revision 变化 | 空数据、边界日期、20,000 条 |

本任务没有前端反馈实现；失败必须以稳定 application exception 类型和 field path 提供给 P4-002 映射。

## 6. 验收标准

- 三类记录各可创建、重开、读取、完整编辑；金额、日期、分类/账户投影、revision 和 dataRevision 精确。
- ASSET/LIABILITY 各验证收入和两类支出方向；trash 后余额取消，restore 后恢复。
- restore 在分类或账户已归档但仍存在时成功；新建/编辑引用归档对象失败且零写入。
- 0、负值、三位小数、指数/溢出金额，未知类型/模式，错类型分类，开户日前结算，stale revision，重复状态命令和同 key 不同请求均有明确失败断言。
- 同 key 同请求只产生一条记录/一次 revision/dataRevision；失败事务不留 processed_operation。
- 高级关联表在全部基础流程后仍为 0 行；无 migration、HTTP、Vue 或 Electron 改动。
- `mvn test package` 通过并报告实际 Surefire 数量。

## 7. 测试层次、命令与真实链路

- 领域单元：金额转换、分类适用性、结算日期、余额方向、状态转换。
- application/SQLite：真实临时 V004 ledger，create→reopen→replace→reopen→trash→reopen→restore；事务回滚、幂等、revision/dataRevision、筛选/排序。
- 性能抽查：合成 20,000 条基础记录，确认 limit 50/200 和 keyset 查询不全量返回；记录耗时但不把单台机器波动作为功能失败，除非明显超过需求门槛。
- 必跑：仓库根 `./mvnw.cmd -q '-Dmaven.compiler.fork=true' test package`。
- 真实原生链路：本任务只要求真实 Java application→sqlite-jdbc→SQLite；HTTP/Vue/Electron、隔离桌面数据、发布包和快捷方式不适用。

## 8. 禁止事项、升级、文档和完成报告

禁止新增依赖/migration/recordType/settlementMode，写高级表，添加 purge/bulk，修改全局契约，复制 SQL 到 application，或顺手重构 profiles/settings/P3。

若 P3 实际账户余额实现与已接受方向不符、V004 未 PASS、数据库列/约束不能表达 Draft、需要 HTTP 才能完成 transaction、或发现已有高级记录数据必须兼容，停止并升级，不自行猜测。

完成报告写：修改文件；用户操作对应的 application 结果；单元/SQLite/性能抽查命令和数量；真实重开与高级表 0 行证据；HTTP/UI/Electron/发布包/快捷方式不适用；未测试项、限制、残余风险和文档同步。
