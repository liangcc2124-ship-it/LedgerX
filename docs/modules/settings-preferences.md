# 模块规格：settings-preferences

- 状态：P1-003 Java SQLite/HTTP 已实现；按 ADR-010，P4-004 后 Vue 仅展示基础版范围与手工备份说明，不暴露未执行策略控件
- 边界：当前 active profile 的设置读取与保存；设置行、revision、数据版本与 ledger 级幂等。
- 引用：[需求 §5–§7](../requirements.md)、[架构 §4、§6](../architecture.md)、[数据库 §4.1、§7–§8](../database.md)、[全局 API](../api.md)、[backup-recovery](./backup-recovery.md)。

## 1. 职责、术语、用例与非目标

设置是每个用户空间的少量持久偏好，不是第二份账本状态。`settings revision` 是这一行的乐观并发版本；`data revision` 是当前 ledger 的整体失效版本。用户可读取设置、保存一项或多项偏好，并在重启后看到同一结果。

数据库/API 仍可保存通知偏好、自动备份策略、安全垫金额、金额隐藏、内置主题和最后设置页，用于兼容已完成的 P1/P2。基础版没有对应执行器，P4-004 后 Vue 不提供这些策略控件；用户只看到准确范围和退出应用后复制完整数据目录的说明。

## 2. 入口、输出与依赖方向

| 项目 | 内容 |
| --- | --- |
| 入口 | `GET /api/v1/settings`、`PATCH /api/v1/settings`。 |
| 输出 | 脱敏 `Settings` DTO、`ETag`、active `dataRevision`。 |
| 依赖 | active profile context、ledger V002 migration、`ledger_setting`、`ledger_meta`、`processed_operation`。 |
| 被依赖方 | P2 设置合同；高级模块以后启用时可读取相应偏好。P4-004 后基础 Vue 不编辑这些字段。 |
| 方向 | HTTP → settings application service → active ledger persistence；不依赖 Vue、Electron 或 catalog。 |

所有普通 settings 请求只作用于 active profile，不能带 `profileId`。profile 切换使客户端此前的 ETag、dataRevision 和草稿失效。

## 3. 实体、规则、不变量与权限

`Settings` DTO 的字段和数据库映射以 [settings API](../api/settings-api.md) 为准。核心不变量：

- `ledger_setting` 始终只有 `id=1` 一行；V002 seed 的默认值为旧版 v3.1 行为：通知关闭、自动备份开启/7 天/10 份、安全垫 ¥3,000、暖铜主题、金额不隐藏。
- 币种和符号首期固定为 `CNY`/`¥`，只读；金额用全局金额 DTO，不能提交 JSON number。
- `lastAutoBackupAt` 是 backup 模块专用写字段，客户端 PATCH 不得清空或伪造。
- `themeName=CUSTOM` 和 `custom_theme_css` 只由后续受限主题接口写入。本期 PATCH 只接受三种内置主题。
- PATCH 至少含一个可写字段；所有字段先验证，随后在一个 SQLite transaction 中写 settings、ledger meta 与 operation。失败不能部分保存。
- 同一幂等键同 method/path/canonical patch 回放第一次 status/body/ETag；不同请求或被当前 ledger 其他 mutation 使用时为 `409 IDEMPOTENCY_CONFLICT`。

## 4. 状态、失败和补偿

设置没有业务状态机。每次 PATCH 在 `READY` 下以 active context 的排他写门串行执行：读取当前 row → 检查 idempotency/If-Match → 验证 merge patch → 更新或 no-op operation → commit。`RECOVERY_REQUIRED` 时 GET/PATCH 均返回 `423`，不得从损坏 ledger 猜测默认设置。

过期 ETag 返回 `409 REVISION_CONFLICT` 和 `details.currentRevision`；输入错误不建立 operation；SQLite busy/unavailable 不建立 operation 且映射全局短暂错误。No-op 返回 200 和当前 ETag，但只写 operation，以便客户端安全重试。

## 5. 数据、接口与可观测映射

| 领域/API | 持久化 | 说明 |
| --- | --- | --- |
| Settings DTO | `ledger_setting` | 只投影公开字段。 |
| ETag / revision | `ledger_setting.revision` | `ETag:"<revision>"`。 |
| dataRevision | `ledger_meta.data_revision` | 有效 PATCH +1；no-op 不变。 |
| PATCH 幂等 | `processed_operation` | 与 settings/meta 在同一 ledger transaction。 |

当前 HTTP 成功/失败响应都回显 `X-Request-Id`，持久化与 HTTP 回归测试可观察 revision、dataRevision、ETag 与 SQLite operation 行。项目尚未有统一结构化日志落点；在该基础设施实现前，不声称已写入 `settings.read`、`settings.updated` 或 `settings.update_failed` 日志。未来日志只可含 route 模板、request ID、耗时、result/error code、revision 和 profile hash，不能记录 token、完整偏好、金额或 CSS。

## 6. 验收与待决

建议覆盖：fresh V002 defaults、GET/ETag、单字段和多字段 PATCH、金额边界、未知字段忽略、空 patch、缺/过期 If-Match、同 key replay/不同 key conflict、重开、profile 隔离、recovery 和 HTTP Host/Origin/auth zero-service-call。

若需要修改 `currencyCode`、保存任意 CSS、实现通知投递、自动备份调度、添加新的设置页枚举或把偏好移到浏览器存储，必须先升级模块/API/数据库设计。
