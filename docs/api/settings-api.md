# Settings REST API

- 状态：已实现；P1-003 已通过 Java/SQLite/HTTP 验证
- 类型：REST
- 调用方：Vue 3 renderer
- 提供方：Java 11 settings-preferences 模块
- 引用：[全局 API](../api.md)、[settings-preferences 模块](../modules/settings-preferences.md)、[数据库](../database.md)。

认证、来源、request ID、错误 envelope、金额、幂等、并发与重试遵循[全局 API](../api.md)。本文件只定义 active profile settings。恢复状态下两个端点都返回 `423 RECOVERY_REQUIRED`。共享成功 fixtures 见 [`get-default.json`](../contracts/api-v1/settings/get-default.json) 和 [`patch-settings-success.json`](../contracts/api-v1/settings/patch-settings-success.json)。

## 1. Settings DTO

```json
{
  "data": {
    "notificationsEnabled": false,
    "autoBackupEnabled": true,
    "autoBackupIntervalDays": 7,
    "autoBackupRetentionCount": 10,
    "lastAutoBackupAt": null,
    "lastSettingsSection": "GENERAL",
    "currency": {"code":"CNY","symbol":"¥"},
    "safetyBuffer": {"amount":"3000.00","currency":"CNY"},
    "hideAllAmounts": false,
    "themeName": "WARM_COPPER",
    "revision": 0,
    "updatedAt":"2026-09-14T00:00:00Z"
  },
  "meta":{"dataRevision":0}
}
```

`lastSettingsSection` 只能为 `GENERAL|PROFILES|CATEGORIES|ACCOUNTS`；`themeName` 可读 `WARM_COPPER|GRAPHITE|DEEP_SEA_BLUE|CUSTOM`，但本期 PATCH 只接受前三者。`currency`、`lastAutoBackupAt`、`revision`、`updatedAt` 只读。`safetyBuffer.amount` 是非负 CNY 规范金额字符串，最多两位小数。

## 2. Read — `GET /api/v1/settings`

认证：桌面会话；范围：当前 active profile。无 query、无 body、无副作用。成功 `200` 返回上方 DTO、`ETag:"<revision>"`。没有 active profile 或恢复状态不使用默认值伪装，返回 `423 RECOVERY_REQUIRED`。

## 3. Update — `PATCH /api/v1/settings`

Headers：`Authorization`、`X-Request-Id`、`Idempotency-Key`、`If-Match` 必填。`If-Match` 是当前 settings `revision` 的强 ETag。Body 是至少一个字段的 JSON object；`null` 不表示清空。未知普通字段按全局规则忽略，但不能使空的有效 patch 成功。

| 可写字段 | 类型/校验 | 默认/映射 |
| --- | --- | --- |
| `notificationsEnabled` | boolean | → `notifications_enabled`。 |
| `autoBackupEnabled` | boolean | → `auto_backup_enabled`；仅策略配置。 |
| `autoBackupIntervalDays` | integer 1–365 | → `auto_backup_interval_days`。 |
| `autoBackupRetentionCount` | integer 1–100 | → `auto_backup_retention_count`。 |
| `lastSettingsSection` | 上述 enum | → `last_settings_section`。 |
| `safetyBuffer` | `{amount,currency}`；CNY、非负、最多两位小数 | 精确转 `safety_buffer_minor`。 |
| `hideAllAmounts` | boolean | → `hide_all_amounts`。 |
| `themeName` | `WARM_COPPER|GRAPHITE|DEEP_SEA_BLUE` | → `theme_name`，同时清空旧 `custom_theme_css`。 |

成功修改返回 `200` 新 DTO、新 `ETag` 和 `meta.dataRevision`；settings revision 和 dataRevision 都加一。若所有有效字段与当前值相同，返回 `200` 当前 DTO/ETag/dataRevision，只有 operation 被保存。成功 operation 在 `processed_operation` 保存 7 天；同 key/同 canonical patch 回放首次 JSON，并从**存储响应中的 revision**重建 ETag，不能使用随后更新过的 revision。不同 method/path/body 的同 key 返回 `409 IDEMPOTENCY_CONFLICT`。

缺 If-Match 返回 `428 PRECONDITION_REQUIRED`；过期 ETag 返回 `409 REVISION_CONFLICT`，`details.currentRevision` 为当前 settings revision；字段错误返回 `400 VALIDATION_FAILED`；`CUSTOM`、改币种、客户端传 `lastAutoBackupAt`/`revision`/`updatedAt` 均为 400。PATCH 不创建备份、通知或主题文件。

## 4. 验收示例

| 操作 | 期望 |
| --- | --- |
| fresh ledger GET | V002 defaults、ETag `"0"`、dataRevision 0。 |
| PATCH `hideAllAmounts:true` | 200，settings revision/dataRevision 都为 1，重开保持。 |
| PATCH safetyBuffer `"0"`/`"3000.01"` | 精确保存 0/300001 分；负数、三位小数、非 CNY 为 400。 |
| PATCH 两项字段 | 一个 transaction、一个 revision/dataRevision 增量。 |
| 同 key 同 PATCH 重试/重开 | 原 200 body/ETag，不重复增加。 |
| 同 key 修改 body；缺/旧 If-Match；空 body | 409/428/409/400，无部分写入。 |
| 错 token 或 Origin | 401/403，service/repository 零调用。 |
