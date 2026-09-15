# 模块规格：ledger-records

- 状态：分类/账户由 P3 实施；基础记录契约已按 ADR-010 收敛，可拆分 P4 实现
- 边界：分类、资金账户、三类基础财务记录、账户余额、记录回收站及其一致性
- 引用：[需求](../requirements.md)、[架构](../architecture.md)、[数据库 §4.2–§4.3](../database.md)、[全局 API](../api.md)、[具体 API](../api/ledger-records-api.md)、[ADR-010](../decisions/ADR-010-basic-ledger-scope.md)

## 1. 模块职责、术语、用例与非目标

| 术语 | 定义 |
| --- | --- |
| 基础记录 | `INCOME`、`FIXED_COST`、`VARIABLE_COST` 三类 `finance_record`；金额恒为正，类型决定余额方向。 |
| 发生日 | `occurredOn`，表示这笔收支归属的业务自然日。 |
| 结算日 | `settlementOn`，表示金额实际进入或离开账户的业务自然日。 |
| 回收站 | `deleted_at` 非空的软删除记录；它不再影响账户余额，但可恢复。 |
| 账户余额 | 期初余额加截至 `asOf` 的活动已结算记录投影，不保存累计值。 |

职责：管理分类和账户；新增、读取、完整编辑、筛选、移入回收站和恢复基础记录；提供准确账户余额。

核心用例：用户准备分类/账户；录入收入或支出；查看最近记录和账户余额；修改误录；删除后从回收站恢复；重启后继续读取相同结果。

非目标：转账、应付款、固定资产、成本分摊、指标归集、公式、仪表盘、报表、预警、批量操作、永久清理、自动重复扣款、导入导出、PDF 和复式记账。

## 2. 入口、输出、依赖与方向

| 项目 | 约定 |
| --- | --- |
| 入口 | `/api/v1/categories`、`/accounts`、`/records` 及 `/records/{id}/restore`。 |
| 输出 | 分类/账户投影、分页记录投影、实体 ETag/revision、`dataRevision`。 |
| 依赖方 | Vue 分类账户页、Vue 收支记录页、真实桌面集成验收。 |
| 被依赖方 | active profile/application 写门、SQLite transaction/repository、后端 `Clock`。 |
| 依赖方向 | `Vue → HTTP → application → domain`；persistence 实现 application 端口。UI 不复制余额规则，repository 不决定 HTTP 状态。 |

当前 Windows 桌面会话是唯一主体，没有角色和数据权限矩阵。所有业务请求隐含 active profile，请求不得携带 `profileId`。恢复模式下 mutation 按全局 API 拒绝。

## 3. 实体、字段、规则、不变量与权限

### 3.1 分类

- 名称 trim 后 1–100；最多两级，无环；同父活动名称不区分大小写唯一。
- 系统分类只由 V004 seed 管理；用户不能编辑、归档或作为 merge source。
- 活动系统分类只有 `recordTypes` 包含当前基础类型时才能选；活动自定义分类的空 `recordTypes` 表示可用于三类基础记录。
- 归档/merge source 必须没有活动直接子项；不级联处理子项。历史记录可继续引用归档分类。
- merge target 必须能承接所有被迁移记录类型；分类合并在一个事务更新活动/回收站记录引用并归档 source。

### 3.2 账户

- kind→balanceSide 固定：`CASH/BANK/WALLET/OTHER_ASSET → ASSET`，`CREDIT/LOAN/OTHER_LIABILITY → LIABILITY`。
- 负债账户不能计入可用现金；账户活动名称不区分大小写唯一。
- 新建/编辑记录只能选择活动账户。历史记录可继续引用归档账户；系统账户可编辑但不可归档。
- `asOf < openingOn` 时余额为 `0.00`。否则从 `openingBalance` 起算，只取 `deleted_at IS NULL`、`settlement_mode=PAID_FROM_ACCOUNT` 且 `settlement_on <= asOf` 的记录。
- ASSET：INCOME 加，FIXED_COST/VARIABLE_COST 减。LIABILITY：INCOME 减，FIXED_COST/VARIABLE_COST 加。

### 3.3 基础财务记录

公开字段及其业务含义以[具体 API §2.3、§5–§7](../api/ledger-records-api.md)为准。领域不变量如下：

- 只接受 `INCOME`、`FIXED_COST`、`VARIABLE_COST`；其他数据库允许值通过公开 API 一律 `400 VALIDATION_FAILED`。
- 金额必须大于 0，固定 CNY，最多两位小数；领域用 `BigDecimal`，持久化为 `amount_minor`。
- category、account 和 settlementOn 必填；分类/账户必须属于 active profile 且在新建/编辑时为 ACTIVE。
- `settlement.mode` 必须精确为 `PAID_FROM_ACCOUNT`；`settlementOn` 不得早于账户 `openingOn`。`occurredOn` 与 `settlementOn` 可不同，二者都是 `LocalDate`。
- note 必填但可为空字符串，最大 4,000 Unicode 字符，原样保存。
- 基础版固定写 `income_source=NULL`、`is_self_generated_income=0`、`is_non_essential=0`，不写 assignment/allocation/asset 表。
- mutation、实体 revision、`ledger_meta.data_revision` 和 `processed_operation` 在同一 SQLite 事务提交；一次成功 mutation 只使 dataRevision 加一。

## 4. 状态、转换、触发、失败与补偿

```text
NEW ──create──> ACTIVE ──trash──> TRASHED ──restore──> ACTIVE
```

| 操作 | 前置与成功结果 | 失败行为 |
| --- | --- | --- |
| Create | 新 UUID、活动引用、字段合法；插入 revision 0 的 ACTIVE 记录。 | 校验/引用/事务失败零写入，不建立 operation。 |
| Replace | ACTIVE、If-Match 命中；完整替换允许字段，保留 id/createdAt，revision+1。 | 冲突不覆盖现值；记录、dataRevision、operation 均不变。 |
| Trash | ACTIVE、If-Match 命中；写 deletedAt，revision+1，余额立即不再计入。 | 已 TRASHED 返回 `REFERENCE_CONFLICT`，不做 no-op 成功。 |
| Restore | TRASHED、If-Match 命中；清 deletedAt，revision+1，余额重新计入。分类/账户只要仍存在即可，即使已归档也允许历史恢复。 | 已 ACTIVE 返回 `REFERENCE_CONFLICT`；FK/事务失败保持 TRASHED。 |

基础版没有 PURGED 状态和永久删除 endpoint。请求超时后结果未知，客户端只可查询 operation 或以相同幂等键重试；服务端提交成功不能因客户端断开而回滚成未知状态。

## 5. 正常、异常、空数据与边界

1. 新建收入：Vue 默认把 `occurredOn` 和 `settlementOn` 预填为本地今天，请求仍显式提交 → 选择收入分类和活动账户 → 保存 → ASSET 余额增加或 LIABILITY 余额减少。
2. 新建支出：选择 FIXED_COST 或 VARIABLE_COST 适用分类和活动账户 → 保存 → ASSET 余额减少或 LIABILITY 余额增加。
3. 编辑：从详情/列表使用最新 ETag；成功后重新读取记录与账户，不能由前端自行修正余额。
4. 删除/恢复：确认删除后进入 TRASHED；切换回收站可见；恢复后回到 ACTIVE。两次状态变化都必须在重启后保持。
5. 空账本：记录列表返回空数组和合法 page；页面给出“添加第一笔记录”，账户列表仍可显示 seed。
6. 边界：金额 `0`、负数、number、指数或三位小数；空/超长 note；非法日期；归档/不匹配分类；归档账户；结算早于开户日；stale ETag；重复幂等键；过期 cursor；切换 profile 后旧草稿，均按具体 API 返回稳定失败且零部分写入。

## 6. 数据库与接口映射

| API/领域 | 数据库 | 事务/投影 |
| --- | --- | --- |
| CategorySummary | `category` + `category_record_type` | 只读或 P3 分类事务。 |
| AccountSummary | `financial_account` + `finance_record` 聚合 | `balance` 不持久化。 |
| FinanceRecord | `finance_record` + category/account join | 金额分↔十进制字符串；status 从 deletedAt 投影。 |
| create/replace/trash/restore | `finance_record`、`ledger_meta`、`processed_operation` | 单事务，全有或全无。 |

基础版所有高级关联表行数应保持 0。若运行时发现记录类型或关联行来自未支持功能，不能静默丢弃或修改；停止该记录的 mutation 并升级设计。

## 7. 可观测行为、验收和测试层次

日志事件限于 `record.created/updated/trashed/restored`、路由模板、requestId、耗时、状态、错误码和脱敏实体/profile hash；不记录金额、note、分类/账户名称、请求 body 或 token。

| 验收场景 | 可验证结果 | 建议层次 |
| --- | --- | --- |
| ASSET/LIABILITY 各录入收入和支出 | 四种余额方向及精确两位金额正确。 | domain + SQLite |
| 新建→重开→编辑→重开 | 字段、revision、dataRevision 和余额与预期一致。 | SQLite 生命周期 |
| 删除→重开→恢复 | deletedAt、列表归属和余额先取消后恢复。 | SQLite + 真实 E2E |
| 归档引用 | 新建/编辑拒绝；既有历史可读；TRASHED 可按原引用恢复。 | application + HTTP |
| 重复提交/并发冲突 | 同 key 同 body 只写一次；stale ETag 零覆盖。 | SQLite + HTTP |
| 空数据/20,000 条 | 空态正常；分页顺序稳定，常用读取达到需求门槛。 | HTTP + 性能抽查 |

前端合同测试必须断言实际 method/path/body/header、错误反馈和最终页面状态；P4 最终验收必须使用真实 Electron→Vue→REST→Java→SQLite，并完整重启验证持久化。

## 8. 冲突、待决与升级条件

[ADR-010](../decisions/ADR-010-basic-ledger-scope.md) 已解除 G-002/G-003：基础记录不包含资产/分摊或指标字段，也不依赖对应 API。G-004/G-005 降级为后续可选工作，不是基础版卡点。

基础版没有业务待决项。若实现需要新增 recordType/settlementMode、改变数据库表、加入高级关联、永久删除、文件恢复、角色/加密/审计或改变全局 API 语义，必须停止并升级；低级实现模型不得自行扩展。

