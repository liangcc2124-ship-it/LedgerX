# Ledger records REST API

- 状态：分类/账户 API 已拆为 P3；三类基础记录 API 已按 ADR-010 收敛并可拆为 P4
- 接口类型：REST/JSON over loopback HTTP
- 调用方：Vue 3 API client；提供方：Java 11 local API → ledger-records application use cases
- 引用：[全局 API](../api.md)、[ledger-records 模块](../modules/ledger-records.md)、[数据库](../database.md)、[需求](../requirements.md)、[ADR-010](../decisions/ADR-010-basic-ledger-scope.md)

## 1. 范围与公共规则

本文件定义分类、资金账户和三类基础财务记录。active profile 由 Java application context 决定；以下请求都不得传 `profileId`。

认证、`X-Request-Id`、mutation 的 `Idempotency-Key`、`If-Match`、envelope、日期/金额、分页、超时、重试和错误格式遵循[全局 API](../api.md)，本文件只写特有规则。所有 mutation 在恢复模式返回 `423 RECOVERY_REQUIRED`。

普通 JSON 不允许 `null` 代替必需对象。分类/账户请求沿既有规则处理未知普通字段；基础记录的 create/replace body 是闭合对象，任何未列字段返回 `400 VALIDATION_FAILED`，防止未实现的资产/指标字段被误判为已保存。创建 ID 均由 Vue 生成小写 UUID。成功 mutation 在业务事务内写 `processed_operation`；已提交的同 key 同请求回放首次成功 HTTP status/body，同 key 不同请求返回 `409 IDEMPOTENCY_CONFLICT`。提交前失败不写 operation。

### 1.1 枚举

| 名称 | 值 |
| --- | --- |
| `RecordType`（基础 API） | `INCOME`, `FIXED_COST`, `VARIABLE_COST` |
| `SettlementMode`（基础 API） | `PAID_FROM_ACCOUNT` |
| `AccountKind` | `CASH`, `BANK`, `WALLET`, `CREDIT`, `LOAN`, `OTHER_ASSET`, `OTHER_LIABILITY` |
| `BalanceSide` | `ASSET`, `LIABILITY`（只读） |
| `RecordStatus` | `ACTIVE`, `TRASHED` |

非法大小写或未知值返回 `400 VALIDATION_FAILED`。

### 1.2 公共失败

| HTTP/code | 本模块触发 |
| --- | --- |
| `400 VALIDATION_FAILED` | 字段、金额、日期、枚举、状态组合、确认词、批量/筛选上限不合法 |
| `404 NOT_FOUND` | 当前 profile/请求状态内不可见 |
| `409 REFERENCE_CONFLICT` | 归档/合并/清理会破坏关联或状态不允许 |
| `409 REVISION_CONFLICT` | ETag/批量 expectedRevision 过期；`details.currentRevision` 可返回 |
| `409 IDEMPOTENCY_CONFLICT` | key 被不同 method/path/body 复用 |
| `423 RECOVERY_REQUIRED` | 当前账本只读 |
| `503 DATABASE_BUSY` | SQLite 短时忙；可按全局策略重试 |

任何 4xx 校验/冲突都不得产生业务表、dataRevision 或 operation 的部分变更；幂等冲突本身可记录诊断但不覆盖原 operation。

## 2. 响应 DTO

### 2.1 `CategorySummary`

```json
{
  "id":"f4203e2c-5d74-4dd7-902d-1e47b664ca15",
  "revision":3,
  "name":"餐饮",
  "parentId":"3ba5aaf5-8be4-4646-a7e5-6434a1d2f45b",
  "recordTypes":["VARIABLE_COST"],
  "isSystem":true,
  "isLegacyCustom":false,
  "status":"ACTIVE",
  "sortOrder":12,
  "defaultRecognitionMethod":"IMMEDIATE",
  "recommendedDepreciationMethod":null,
  "canUseForRecords":true
}
```

`recordTypes=[]` 表示活动自定义分类无类型限制；`parentId=null` 表示顶级。推荐折旧方法只是提示。

canUseForRecords 始终存在且由服务端推导：活动自定义分类为 true；活动系统分类仅在 recordTypes 非空时为 true；归档分类和无类型系统父分类为 false。客户端不得只凭名称、层级或 isSystem 推断可选性。

### 2.2 `AccountSummary`

```json
{
  "id":"327fc40b-25c7-4b59-8e3d-e611c8b5a7e0",
  "revision":2,
  "name":"日常银行卡",
  "kind":"BANK",
  "balanceSide":"ASSET",
  "openingOn":"2026-01-01",
  "openingBalance":"5000.00",
  "balance":"4618.50",
  "currency":"CNY",
  "includeInAvailableCash":true,
  "isSystem":false,
  "status":"ACTIVE"
}
```

`balance` 按 `asOf` 由后端推导，不可写。归档账户可在历史记录摘要显示，但不能用于新建/编辑。

### 2.3 `FinanceRecord`

```json
{
  "id":"c7d8c04c-3487-4d74-93b7-89e0cfa81922",
  "revision":1,
  "status":"ACTIVE",
  "occurredOn":"2026-09-10",
  "recordType":"FIXED_COST",
  "amount":"199.00",
  "currency":"CNY",
  "category":{"id":"f4203e2c-5d74-4dd7-902d-1e47b664ca15","name":"订阅服务","status":"ACTIVE"},
  "settlement":{
    "mode":"PAID_FROM_ACCOUNT",
    "account":{"id":"327fc40b-25c7-4b59-8e3d-e611c8b5a7e0","name":"日常银行卡","status":"ACTIVE"},
    "settlementOn":"2026-09-10"
  },
  "note":"年度软件订阅",
  "createdAt":"2026-09-10T08:30:00Z",
  "updatedAt":"2026-09-10T08:30:00Z",
  "deletedAt":null
}
```

`category` 和 `settlement.account` 都是保存时引用的当前投影；引用后来归档时 status 返回 `ARCHIVED`，历史 name 仍由关联表当前名称投影。基础 DTO 不包含资产、分摊、指标、应付款和派生汇总字段。

`category` 精确包含 `id,name,status`；`settlement.account` 精确包含 `id,name,status`，不嵌入余额或 account kind。`status` 均为 `ACTIVE|ARCHIVED`。`deletedAt` 在 ACTIVE 为 null，在 TRASHED 为 UTC instant；列表和详情使用同一 FinanceRecord 结构。

## 3. 分类 API

### 3.0 已收敛的分类规则与 V004 seed

P3-001 之后每个 active profile 都有 [核心分类与账户 seed 契约](../contracts/core-catalog-v1.md) 中精确的 71 个系统分类。它们有固定 UUID 和全局 sortOrder；无类型的系统父分类只用于分组，不能作为记录的 categoryId。自定义分类默认没有 recordTypes 行，因而可用于全部六类财务记录。

创建自定义分类时，服务端把 sortOrder 设为同一 parentId 下所有活动或归档同级分类的最大 sortOrder 加一；没有同级时为 0。仅改名称保留 sortOrder；改 parentId 时重新按目标父节点追加。客户端不传 sortOrder、recordTypes、默认确认方式或推荐折旧方式。

归档分类和作为 merge source 的分类都必须没有活动直接子分类；否则返回 409 REFERENCE_CONFLICT，details.activeChildCount 为正整数。服务端绝不级联归档、移动或合并子分类。归档后的分类没有恢复 endpoint；历史 finance_record 引用不阻止归档，也不被重写。

### 3.1 List categories — `GET /api/v1/categories`

用途：读取分类选择器/管理列表。认证：桌面会话；范围：active profile。

Query：`includeArchived` boolean 默认 `false`；`limit` integer 默认/最大 `200`、范围 1–200；`cursor` 可缺失，不可空字符串。无 body；mutation headers 不适用。

成功 `200`：`data.items: CategorySummary[]` 和标准 page；排序固定顶级优先、`sortOrder ASC,name ASC,id ASC`。空库返回 `items:[]`。非法/过期 cursor 为 `400 VALIDATION_FAILED`。

### 3.2 Create category — `POST /api/v1/categories`

Body：

| 字段 | 类型 | 必填/空值 | 校验与映射 |
| --- | --- | --- | --- |
| `id` | UUID | 是，不可 null | 新 ID → `category.id` |
| `name` | string | 是 | trim 后 1–100；同父活动名称不区分大小写唯一 |
| `parentId` | UUID/null | 是 | null 顶级；父项 ACTIVE；结果最多两级，无环 |

客户端不得提交 `revision/isSystem/status/recordTypes/sortOrder` 等只读 seed 字段。成功 `201`，`Location`、`ETag:"0"`，`data.category`。ID/名称重复为 `409 REFERENCE_CONFLICT`，父项不存在为 `404 NOT_FOUND`，层级/名称非法为 `400`。事务：插入 category、递增 dataRevision、保存 operation。

父项必须 ACTIVE，且新分类最多位于第二层。新分类可以是顶级，或挂在顶级分类下；任何 parentId 指向第二层分类都返回 400 VALIDATION_FAILED，fieldErrors.parentId 指出“分类最多两级”。系统父分类可作为自定义子分类的父项。

### 3.3 Replace category — `PUT /api/v1/categories/{id}`

Path `id` UUID 必填；`If-Match` 必填。Body 只有 `name`（同上）和 `parentId`（同上）。不得改系统分类；不得通过移动形成第三级/环。

成功 `200` + 新 ETag，`data.category`。系统分类/归档目标为 `409 REFERENCE_CONFLICT`；过期 ETag 为 `409 REVISION_CONFLICT`；无 `If-Match` 为 `428 PRECONDITION_REQUIRED`。事务完整替换允许字段，revision/dataRevision 各加一。

### 3.4 Archive category — `DELETE /api/v1/categories/{id}`

Path UUID、`If-Match`、`Idempotency-Key` 必填；无 body。成功 `200`，`data={category:<ARCHIVED summary>}`。系统/已归档为 `409 REFERENCE_CONFLICT`；历史记录引用不阻止归档，且历史引用不改。

若存在活动直接子分类，返回 409 REFERENCE_CONFLICT，details 为 activeChildCount；不改变分类、dataRevision 或 operation。只有归档子分类不阻止归档父项。

### 3.5 Merge categories — `POST /api/v1/categories/{targetId}/merge`

Path `targetId` 必须 ACTIVE。`If-Match` 是目标 revision。Body：

```json
{"sources":[{"id":"9203dbcb-d2ea-4057-a9aa-3332bb8ea488","expectedRevision":2}]}
```

`sources` 必填、去重、1–200；源必须 ACTIVE、非系统，目标不能是源/源的子项。成功 `200`：`data={targetId,mergedIds,updatedRecordCount}`。一个事务检查全部 revision、更新 `finance_record.category_id`、递增被改记录 revision、归档源、递增 dataRevision并保存 operation；任一失败全回滚。

每个 source 均必须无活动直接子分类。target 必须 canUseForRecords=true，且对所有将被移动的活动或回收站 finance_record 都适用其 recordType：自定义 target 适用全部类型；系统 target 仅适用其 recordTypes。任一不满足时返回 409 REFERENCE_CONFLICT，details.reason 分别为 ACTIVE_CHILDREN、TARGET_NOT_RECORD_USABLE 或 TARGET_RECORD_TYPE_MISMATCH，事务零写入。

## 4. 账户 API

### 4.0 已收敛的账户规则与 V004 seed

P3-001 之后每个 active profile 都有 [核心分类与账户 seed 契约](../contracts/core-catalog-v1.md) 中固定 ID 的系统账户“现金储备”。它初始为 CASH、ASSET、0.00、计入可用现金；V004 的 openingOn 是迁移执行机器的本地自然日。系统账户可按普通 replace 规则编辑名称、kind、openingOn、openingBalance、includeInAvailableCash，但不可 archive。

AccountKind 到服务端只读 BalanceSide 的映射固定为：CASH、BANK、WALLET、OTHER_ASSET 为 ASSET；CREDIT、LOAN、OTHER_LIABILITY 为 LIABILITY。客户端提交 balanceSide 一律按未知只读字段忽略，响应必须返回服务端推导值。LIABILITY 的 includeInAvailableCash 必须是 false；ASSET 可由用户选择 true 或 false。

账户余额的计算不持久化累计值。asOf 早于 openingOn 时余额为 0.00；否则以 openingBalance 起算，叠加 deletedAt 为 null、settlementMode=PAID_FROM_ACCOUNT、settlementOn 不晚于 asOf 的记录。INCOME 对 ASSET 加额、对 LIABILITY 减额；FIXED_COST/VARIABLE_COST 方向相反。基础 records API 拒绝 settlementOn 早于所选账户 openingOn。

### 4.1 List accounts — `GET /api/v1/accounts`

Query：`asOf` Date 默认后端本地今天；`includeArchived` boolean 默认 false；`limit` 默认/最大 200；`cursor` 与相同 asOf/filter/dataRevision 绑定。

成功 `200`：`data.items:AccountSummary[]`；排序 ACTIVE 优先、`name ASC,id ASC`。余额只计算 `PAID_FROM_ACCOUNT` 且 `settlementOn <= asOf` 的有现金影响记录。

### 4.2 Create account — `POST /api/v1/accounts`

### 4.3 Replace account — `PUT /api/v1/accounts/{id}`

两者共享 body；create 含 `id`，replace 的 ID 在 path 且需 `If-Match`：

| 字段 | 类型 | 必填 | 校验/映射 |
| --- | --- | --- | --- |
| `id` | UUID | 仅 create | 新 ID |
| `name` | string | 是 | trim 后 1–100；活动名称不区分大小写唯一 |
| `kind` | AccountKind | 是 | 后端推导 `balanceSide` |
| `openingOn` | Date | 是 | → `opening_on` |
| `openingBalance` | decimal string | 是 | 可正、零、负，最多两位，→ `opening_balance_minor` |
| `currency` | string | 是 | 固定 `CNY` |
| `includeInAvailableCash` | boolean | 是 | LIABILTY 必须 false |

禁止 body 提交 `balance/balanceSide/revision/status`。create 成功 `201` + Location/ETag；replace 成功 `200` + 新 ETag；均返回 `data.account`。系统账户允许编辑上述属性但不得 archive。归档账户不可 replace。

replace 的 openingOn 不得晚于该账户任一 ACTIVE 且 PAID_FROM_ACCOUNT 记录的 settlementOn；否则返回 409 REFERENCE_CONFLICT，details.earliestSettlementOn 给出最早阻断日期。TRASHED 记录不阻止该变更。该限制在 records API 实施前已经生效，因此 P3 的空账本账户替换不受影响。

### 4.4 Archive account — `DELETE /api/v1/accounts/{id}`

Path UUID、`If-Match`、幂等键必填；无 body。历史引用不阻止归档且不变。成功 `200` 返回 ARCHIVED summary；系统/已归档为 409。没有恢复/物理删除 endpoint。

归档账户不再可被新记录选择，但历史记录和 asOf 余额读取仍会按其保存的 openingBalance 与已结算记录投影。归档账户永不计入可用现金；返回的 AccountSummary 仍保留其原 includeInAvailableCash 值，调用方以 status 判断其不参与可用现金汇总。

## 5. 记录读取 API

### 5.1 List records — `GET /api/v1/records`

| Query | 类型 | 默认/校验 |
| --- | --- | --- |
| `status` | RecordStatus | `ACTIVE`；一次不可混合状态 |
| `occurredFrom` | Date | 含起点 |
| `occurredToExclusive` | Date | 不含终点；必须晚于 from |
| `recordType` | 重复枚举 | 0–3、去重；缺失为三类基础记录全部 |
| `categoryId`,`accountId` | 重复 UUID | 各 0–200、去重 |
| `query` | string | trim 后 1–200；只查分类名/note 的受限子串 |
| `amountMin`,`amountMax` | CNY decimal string | 大于等于 0；min ≤ max |
| `limit` | integer | 默认 50，1–200 |
| `cursor` | string | 与完整 filter/profile/dataRevision 绑定 |

排序固定 `-occurredOn,-id`，不接受 `sort`。成功 `200`：`FinanceRecord[]` + page。更改 filter/profile 后 Vue 必须清 cursor；服务端收到过期游标返回 400，不泄漏 SQL。

### 5.2 Get record — `GET /api/v1/records/{id}`

Path UUID；query `status` 默认 ACTIVE。成功 `200` 返回 `data.record`、`ETag:"<revision>"`。状态不匹配与不存在均 404，避免泄露回收站存在性。

## 6. `RecordDraft`

POST/PUT 使用完整草稿；缺字段不表示保留旧值：

| 字段 | 类型 | 必填/空值 | 规则与映射 |
| --- | --- | --- | --- |
| `occurredOn` | Date | 是 | → `finance_record.occurred_on` |
| `recordType` | RecordType | 是 | → `record_type` |
| `amount` | decimal string | 是 | >0、最多两位 → `amount_minor` |
| `currency` | string | 是 | `CNY` |
| `categoryId` | UUID | 是 | ACTIVE 且适用于 recordType |
| `settlement` | object | 是 | `{mode,accountId,settlementOn}`，见下表 |
| `note` | string | 是 | 0–4000 Unicode，原样保存 |

Settlement：

| mode | accountId/settlementOn | 允许类型 | 余额语义 |
| --- | --- | --- | --- |
| `PAID_FROM_ACCOUNT` | 均必填、账户 ACTIVE；settlementOn 不早于 openingOn | 三类基础记录 | ASSET 收入加/支出减；LIABILITY 收入减/支出加 |

RecordDraft 是闭合对象。缺失字段、额外字段、`metricIds`、`allocation`、`fixedAsset`、`incomeSource`、`isSelfGeneratedIncome`、`isNonEssential` 或任何应付款字段都返回 `400 VALIDATION_FAILED`；服务端不得忽略后假装已保存。写入时数据库扩展列使用 NULL/0 默认值，高级关联表零写入。

引用错误固定为 `409 REFERENCE_CONFLICT`：不存在分类 `details.reason=CATEGORY_NOT_FOUND`，归档分类 `CATEGORY_ARCHIVED`，系统分类类型不匹配 `CATEGORY_TYPE_MISMATCH`，不存在账户 `ACCOUNT_NOT_FOUND`，归档账户 `ACCOUNT_ARCHIVED`。同时使用 `fieldErrors.categoryId` 或 `fieldErrors.settlement.accountId`。`settlementOn < openingOn` 是字段值错误，返回 `400 VALIDATION_FAILED` 和 `fieldErrors.settlement.settlementOn`。以上失败均零写入。

## 7. 记录 mutation API

### 7.1 Create record — `POST /api/v1/records`

Body 为 `{id,...RecordDraft}`。成功 `201` + `Location` + `ETag:"0"`，`data.record`。单事务只写 finance_record、dataRevision 和 operation；任一字段或引用失败零写入。

请求示例：

```http
POST /api/v1/records
X-Request-Id: 3d407ca8-ec1c-4a48-8bd6-edc34c7894b3
Idempotency-Key: b225f659-299d-4391-86d6-d21465b931da
Content-Type: application/json
```

```json
{
  "id":"c7d8c04c-3487-4d74-93b7-89e0cfa81922",
  "occurredOn":"2026-09-10",
  "recordType":"FIXED_COST",
  "amount":"199.00",
  "currency":"CNY",
  "categoryId":"f4203e2c-5d74-4dd7-902d-1e47b664ca15",
  "settlement":{"mode":"PAID_FROM_ACCOUNT","accountId":"327fc40b-25c7-4b59-8e3d-e611c8b5a7e0","settlementOn":"2026-09-10"},
  "note":"年度软件订阅"
}
```

### 7.2 Replace record — `PUT /api/v1/records/{id}`

Path UUID、`If-Match`、幂等键必填；body 为 RecordDraft，不含 ID/revision。只允许 ACTIVE 记录。成功 `200`、新 ETag、`data.record`；不改变 id/createdAt，revision 和 dataRevision 各加一。分类、账户、日期或金额任一失败时整笔不变。

### 7.3 Trash record — `DELETE /api/v1/records/{id}`

仅 ACTIVE，`If-Match` 和幂等键必填，无 body。成功 `200` 返回 TRASHED record；设置 deleted_at，revision/dataRevision 各加一，该记录立即不再影响余额。重复 trash 为 `409 REFERENCE_CONFLICT`。

### 7.4 Restore record — `POST /api/v1/records/{id}/restore`

仅 TRASHED，`If-Match` 和幂等键必填，body `{}`。成功 `200` 返回 ACTIVE record；清除 deleted_at，revision/dataRevision 各加一，余额重新计入。原分类/账户仍存在时即使已经归档也允许恢复；重复 restore 为 `409 REFERENCE_CONFLICT`。

基础版不注册 purge、bulk-patch、bulk-trash、bulk-restore 或 bulk-purge 路由；调用这些路径返回全局 `404 ROUTE_NOT_FOUND`。

## 8. 数据库、事务和副作用映射

| API 字段/概念 | 领域/表 | 说明 |
| --- | --- | --- |
| CategorySummary | `category`,`category_record_type` | status 从 archived_at 投影 |
| AccountSummary | `financial_account` + 余额领域计算 | balance 不持久化累计 |
| FinanceRecord | `finance_record` + category/account join | amount 精确换 amount_minor；status 从 deleted_at |
| 幂等 | `processed_operation` | 与业务事实同事务保存 method/path/hash/status/response |
| 整体版本 | `ledger_meta.data_revision` | 每成功 mutation 递增一次 |

记录成功后不触发指标、报告、预警、资产、分摊或文件副作用。账户余额在下一次 accounts 读取时由活动记录重新投影。

## 9. 超时、重试与兼容

- GET 客户端 15 秒超时；普通 mutation 30 秒。前端超时显示“结果待确认”，调用 `GET /operations/{Idempotency-Key}` 或用相同 key 重试。
- mutation 不能换新 key 自动重试；GET 可按全局 429/503 退避。
- list cursor 与 filter/profile/dataRevision 绑定；不存在 offset。
- 本文是尚未公开发布的 `/api/v1` 基础契约，不承担旧 C# bridge/JSON 兼容。若以后实现导入，必须通过独立迁移模块完成，不把旧字段直接加入本 endpoint。

## 10. 失败响应示例

```http
HTTP/1.1 400 Bad Request
X-Request-Id: 3d407ca8-ec1c-4a48-8bd6-edc34c7894b3
Content-Type: application/json; charset=utf-8
```

```json
{
  "error": {
    "code":"VALIDATION_FAILED",
    "message":"请检查记录内容。",
    "requestId":"3d407ca8-ec1c-4a48-8bd6-edc34c7894b3",
    "fieldErrors":{
      "amount":"金额必须大于 0 且最多两位小数。",
      "settlement.accountId":"已结算记录必须选择活动资金账户。"
    },
    "retryable":false,
    "recoverySuggestion":"修正字段后重新提交。",
    "details":{}
  }
}
```

revision 冲突返回 409，`details={currentRevision:4,updatedAt:"..."}`；不返回整个他人/旧 profile 实体。

## 11. 验收场景与测试

| 场景 | 期望 |
| --- | --- |
| 空 profile 创建 BANK opening 5000.00 | 201、ASSET、balance 5000.00，重开仍可读 |
| INCOME + PAID_FROM_ACCOUNT | 账户余额增加；list 首项为精确 string 金额 |
| FIXED_COST/VARIABLE_COST + ASSET | 账户余额减少；删除后恢复原余额，恢复后再次扣减 |
| INCOME/支出 + LIABILITY | 收入减少负债、支出增加负债 |
| 引用不存在/归档/类型不匹配分类或账户 | 409 + 固定 reason/field path，零 finance_record 变更 |
| settlementOn 早于 openingOn | 400，fieldErrors 为 `settlement.settlementOn`，零写入 |
| `FIXED_ASSET_PURCHASE` 或额外 `metricIds` | 400，不写记录或高级表 |
| 同幂等键重发 create | 只有一行，status/body/dataRevision 与首次相同 |
| 同 key 改 note | 409 IDEMPOTENCY_CONFLICT，原记录不变 |
| PUT 的 If-Match 过期 | 409，较新数据不被覆盖 |
| DELETE 后重开再 restore | ACTIVE/TRASHED 列表和账户余额按最终状态正确 |
| 旧 cursor 在换 filter/profile 后提交 | 400，无 SQL/路径泄漏 |
| `"1.234"`、`"1e2"` 或 number `1.23` | 400，不舍入、不走浮点 |
| 无 token/错 token | 401；application/repository 零调用 |

建议层次：领域单元覆盖余额方向/状态；SQLite 集成覆盖事务/FK/幂等/重开；HTTP fixtures 覆盖字段/header/status；Vue 合同覆盖真实名称；Electron E2E 覆盖新增、编辑、回收站、余额和完整重启。

可观测字段只有路由模板、request ID、profile hash、duration/status/error/dataRevision 和批量数量；禁止金额、名称、note、原始 category/account/metric/asset/body。

## 12. 延后能力边界

资产/分摊、指标/公式、应付款、报表/预警、批量操作、永久清理和旧数据导入均按 [ADR-010](../decisions/ADR-010-basic-ledger-scope.md) 延后。数据库存在相关表不代表 API 支持；基础实现不得读取旧草案推测字段或提前注册 endpoint。以后恢复任一能力时，先更新需求、模块和具体 API，再创建独立 Task Spec。
