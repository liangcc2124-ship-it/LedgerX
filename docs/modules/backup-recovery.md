# 模块规格：本机备份快照

- 状态：P9-001 手动备份已完成；应用内恢复能力仍未启用
- 决策：[ADR-016](../decisions/ADR-016-local-backup-snapshot.md)
- API：[backups-api](../api/backups-api.md)
- 任务：[P9-001](../tasks/P9-001-backup-format-snapshot.md)

## 1. 当前职责

本模块只为请求开始时的 active profile 创建、列出、深校验和下载本机备份。Java 是唯一文件写者；浏览器只看到 opaque UUID 和下载流，不提交或获得本机路径。

当前明确不做恢复、上传/导入、自动备份、保留数量删除、保护快照、云同步、加密、主题文件、旧 JSON 兼容或分享包。旧设计中的 restore preview、active switch、自动策略和 format 2 导入均不属于 P9-001，后续必须重新立项和定义破坏性流程后才能实现。

## 2. 一致性与并发

ProfileApplicationService 的 gate 只用于捕获不可变的 `(profileId, ledgerFile, schemaVersion, dataRevision)`；捕获后立即释放。BackupStore 使用 sqlite-jdbc 已有的 SQLite online backup 能力生成数据库快照，因此普通业务写可以继续，不能用长时间全局写锁代替 SQLite 一致性。

Profile 切换可与备份并发：已开始的请求始终写入捕获 profile 的 `Backups/<profileId>/`，manifest 也必须是该 profile；它不会跟随新的 active profile。后续 list/verify/download 只从各自请求开始时的 active profile 查找，同 ID 在其他 profile 中按不存在处理。

## 3. 格式与发布

Format 3 文件扩展名是 `.ledgerx-backup`，ZIP 恰含：

- `manifest.json`：`formatVersion=3`、backup/profile ID、schema/application version、UTC createdAt、dataRevision、记录/分类/账户/指标总数、`encrypted=false` 与 ledger 元数据；
- `ledger.db`：SQLite online backup 产生的一致快照，manifest 保存其字节数和 SHA-256。

流程固定为：在目标 profile 的备份目录内创建随机 staging → online snapshot → `integrity_check` 与 `foreign_key_check` → 查询 manifest 计数并计算 hash → 写包并 force/关闭 → 重新打开包、提取数据库并复查成员/hash/metadata/count/integrity/FK → 同卷 `ATOMIC_MOVE` 发布。卷不支持原子移动时失败关闭，不降级为可观察的非原子文件。所有失败都不修改活动 ledger，也不产生公开目标文件。

幂等键使用小写 UUID v4，并直接作为 backup ID/文件名。同一 profile 下最终文件存在时，重试必须先深校验该文件再返回；不新增数据库表或 schema migration。

## 4. 安全与错误

所有 endpoint 都需要进程内 HttpOnly 浏览器会话；创建还需要 CSRF 和 Idempotency-Key。备份没有加密，UI 和下载 DTO 必须明确展示这一点。ZIP reader 限制 manifest 大小、成员名称/数量和解压数据库大小，不按 entry 名直接拼接落盘路径。

格式错误、坏 hash/count、未知 format/schema、profile mismatch 及 SQLite/FK 失败统一为 `422 BACKUP_INVALID`。路径 ID 无效、不存在、或存在于其他 profile 统一为 `404 NOT_FOUND`，不得透露跨 profile 信息。响应和日志不含绝对路径、金额、备注、分类/账户名称或文件内容。

## 5. 完成边界

P9-001 的后端完成证据至少覆盖 online snapshot→ZIP→重开校验→原子发布、同 key 重试、profile 隔离、坏 hash/未知 format、源 ledger 状态不变，以及真实 HTTP 会话/CSRF/下载流。恢复能力必须以后续任务为准，不能仅因 format 3 包可验证就声称“可恢复”。
