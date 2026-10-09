# Ledger initialization REST API

- 状态：P7-002 已实现并通过验收；范围与限制见[任务规格](../tasks/P7-002-ledger-initialization-account-opening.md)
- 接口：`GET/POST /api/v1/ledger-initialization`
- 范围：active profile 的 SQLite ledger；Java 是唯一业务校验与写入方
- 通用认证、CSRF、错误 envelope、金额/日期与幂等规则遵循[全局 API](../api.md)
- 验收证据：[P7-002 验证记录](../verification/P7-002-ledger-initialization-account-opening.md)

## GET 初始化与审查摘要

`GET /api/v1/ledger-initialization` 在 setup 为 `PENDING` 或 `REVIEW_REQUIRED` 时用于首启表单；完成后可读取已确认状态。响应不包含绝对路径、SQL 或完整账本内容。

```json
{
  "data": {
    "setupState": "PENDING",
    "today": "2026-09-23",
    "ledgerStartOn": null,
    "suggestedLedgerStartOn": null,
    "defaultAccount": {
      "id": "f3a0c2ec-6b64-48c7-9f7f-0b604e4d8901",
      "name": "现金储备",
      "openingOn": "2026-09-23",
      "openingBalance": "0.00",
      "earliestSettlementOn": null,
      "status": "ACTIVE"
    },
    "accountsNeedingOpeningDateReview": []
  },
  "meta": { "dataRevision": 0 }
}
```

`suggestedLedgerStartOn` 是历史数据最早可观察日期，仅供审查说明；新账本的日期必须由用户明确选择。`accountsNeedingOpeningDateReview` 只含非默认账户，包含 `accountId`、`name`、当前 `openingOn`、最早 PAID_FROM_ACCOUNT 结算日 `earliestSettlementOn` 和 `ACTIVE|ARCHIVED` 状态。该列表包含回收站记录的结算日约束。

## POST 完成初始化

Headers：`Idempotency-Key` 为 UUID，必须与本次草稿稳定绑定；浏览器会话还必须携带 `X-LedgerX-CSRF`。Body 是闭合对象，未知字段拒绝。

| 字段 | 类型 | 要求 |
| --- | --- | --- |
| `ledgerStartOn` | `YYYY-MM-DD` | 必填；不得晚于 Asia/Shanghai 的今天，也不得晚于任一现有记录日期 |
| `defaultAccountOpeningOn` | `YYYY-MM-DD` | 必填；不得早于起始日或晚于该账户最早结算日；PENDING 时必须等于账本起始日 |
| `openingBalance` | decimal string | 必填；CNY 精确到分，可为正、零或负，不接受 JSON number |
| `accountName` | string/null | 可选；省略或 null 时保留迁移前名称；提供时 trim 后 1–100 字且不可与其他活动账户重名 |
| `confirmExistingData` | boolean | 必填；PENDING 必须 false；REVIEW_REQUIRED 必须 true |
| `accountOpeningDates` | array | 必填；每项 `{ "accountId": "<UUID>", "openingOn": "YYYY-MM-DD" }`，按小写 accountId 严格升序且不得重复 |

新账本请求示例：

```json
{
  "ledgerStartOn": "2026-08-01",
  "defaultAccountOpeningOn": "2026-08-01",
  "openingBalance": "2500.00",
  "confirmExistingData": false,
  "accountOpeningDates": []
}
```

审查请求必须显式包含预览中所有非默认冲突账户，且不得包含其他账户。默认账户日期单独通过 `defaultAccountOpeningOn` 提交；其他账户只在冲突列表中明确选择新的日期，其余历史配置不变。所有账户日期不得早于 `ledgerStartOn`，也不得晚于其 ACTIVE/TRASHED 记录最早结算日；`ledgerStartOn` 不得晚于已有发生日或结算日。

成功返回 `200`，包括 `data.setupState=COMPLETED`、已确认 `ledgerStartOn`、更新后的默认账户摘要，以及递增后的 `meta.dataRevision`。一次 SQLite transaction 原子完成账户日期/余额更新、setup 字段、`ledger_setting.revision`、`ledger_meta.data_revision` 和 operation 记录。默认账户投影字段变化时其 entity revision 加一，否则保持不变。

### 错误与重试

| HTTP/code | 条件 |
| --- | --- |
| `400 VALIDATION_FAILED` | 日期/金额/字段/排序格式非法，或不满足历史日期边界；fieldErrors 定位到 body 字段 |
| `409 LEDGER_SETUP_REQUIRED` | setup 未完成时调用普通业务 API |
| `409 REVIEW_CONFIRMATION_REQUIRED` | 未确认审查，或提交的冲突账户 ID 集合与当前摘要不同 |
| `409 IDEMPOTENCY_CONFLICT` | 同一 key 被用于不同 method/path/body |
| `409 ALREADY_INITIALIZED` | 已完成后以新 key 再次初始化 |
| `409 REFERENCE_CONFLICT` | 默认账户名称与另一活动账户冲突 |
| `423 RECOVERY_REQUIRED` | profile/ledger 处于恢复状态 |

服务端先在事务内查 operation，再判断 setup 状态。相同 key、路径和 body 重放原状态码与完整响应；同 key 不同 body 冲突；提交成功后不同 key 不会重复初始化。任一校验/持久化失败均不留下部分 setup、账户、revision 或 operation 更新。
