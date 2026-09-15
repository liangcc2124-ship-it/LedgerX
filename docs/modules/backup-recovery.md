# 模块规格：backup-recovery

- 状态：按 ADR-010 延后；基础版采用退出应用后复制完整数据目录，不实现本模块
- 边界：保留的未来设计草案；每 profile 应用内备份、自动备份、旧备份导入、隔离恢复和主题文件。基础版不得据此实现 endpoint。
- 引用：[需求 §4.1、§7.1/§7.5、§8.2/§8.5](../requirements.md)、[数据库 §2、§8](../database.md)、[架构 §6.4/§9](../architecture.md)、[API §5、§11–§12](../api.md)、[ADR-003](../decisions/ADR-003-sqlite-per-profile.md)。

## 1. 职责、术语、用例与非目标

术语：`protection snapshot`（危险操作前的本地一致性副本）、`backup`（用户/策略创建的可搬运包）、`selected upload`（HTML 文件选择后上传的内容，不是路径）、`download`（HTTP 流式响应）、`staging`（隔离解包/验证目录）、`active switch`（关闭连接后原子替换活动 ledger）。

用例：手动创建/预览/恢复单 profile 备份、首次 JSON 导入、策略触发的自动备份、恢复失败后保持旧账本、清空/恢复出厂前快照、从损坏状态创建空账本、导入/导出受限主题 CSS。非目标：PDF 报表、云备份、加密备份、跨 profile 合并恢复、自动上传、直接暴露本机路径。

## 2. 入口、输出、依赖方向与权限

| 项目 | 内容 |
| --- | --- |
| 入口 | `/backups`、`/backup-previews`、`/recoveries` REST 用例、启动迁移、正常写后的自动备份检查、主题导入/导出。 |
| 输出 | 脱敏 manifest preview、一次性 `previewId`、下载流、备份/快照摘要、恢复状态、主题 CSS DTO。 |
| 依赖方 | local-api-contract（上传/下载）、persistence-migration（一致性快照/导入/健康）、ui-integration、release-verification。 |
| 被依赖方 | application 排他锁、JDBC SQLite snapshot/health、Jackson、JDK ZIP/SHA-256/file APIs、Clock。 |
| 方向 | `HTTP → backup application port → persistence`；不从 Vue 取得文件路径，不改写领域表。 |

只有 active profile 的已认证桌面会话可请求文件操作。Vue 使用 HTML 文件选择上传内容；后端创建一次性 `previewId`，绑定文件 hash、purpose、会话和 active profile，不能伪造、跨 profile、跨重启或复用作不同用途。用户在选择器取消时前端保持原状态，不发送 API，不属于错误。

## 3. 格式、实体与不变量

新格式固定为**format 3**：扩展名 `.ledgerx-backup` 的 ZIP，包含 `manifest.json` 与一致性 `ledger.db`；manifest 至少含 format、app/schema、profile ID、createdAt UTC、记录/软删/指标/规则计数、业务日期范围、数据库 SHA-256、manifest canonical-content SHA-256。写入顺序为 staging 临时包 → 重开 ZIP 校验 manifest/hash/DB 完整性 → 原子移动至目标。

兼容清单仅为：v3.1 schema 4 裸 JSON、备份 envelope `formatVersion=2`（先验证既有 SHA-256）和 format 3。schema 2/3、backup 1、未知未来 format/schema 均拒绝，显示“请使用现有 C# v3.1 先升级”或“使用更新的 LedgerX”，不尝试猜测。新 format 3 不承诺 C# 可读取。

备份/快照均为单 profile；manifest profile ID 必须等于当前 active profile 才可恢复。不得复制运行中的裸 `ledger.db` 或假设 `-wal/-shm` 是备份。自动备份采用 `ledger_setting` 的开关、间隔和 1–100 保留数：启动和每次成功业务写后仅检查一次，满足间隔才创建；失败只记可观察告警，不能撤销已提交业务写。

主题不是备份：导入只保留 `--lx-*` CSS declarations，最大 64 KiB，拒绝 `url(`、`@import`、`expression(`、规则块/脚本；主题导出为该受限 CSS。不得存储原选择路径。

## 4. 状态、恢复流程与补偿

```text
NORMAL → SNAPSHOTTING → STAGING → VALIDATING → SWITCHING → NORMAL
                         └───────────────失败───────────────→ NORMAL
启动健康失败 ───────────────────────────────────────────────→ RECOVERY_REQUIRED
RECOVERY_REQUIRED ──验证恢复/创建空账本──> NORMAL
```

| 操作 | 必须步骤 | 失败/补偿 |
| --- | --- | --- |
| 创建/下载备份 | 请求一致性 snapshot → 建 format 3 staging 包 → 完整验证 → 返回有界 download。 | 下载取消/磁盘满/哈希失败不更新 `lastBackupAt`，不触碰 ledger。 |
| 预览上传 | 流式接收用户选择文件 → 只读取安全 manifest/格式 2 摘要 → 完整性校验，返回 previewId + preview。 | 坏/不兼容包为 `BACKUP_INVALID`，清理 staging，不留下可恢复引用。 |
| 恢复 | 接收先前 previewId + 精确 confirmation → 对当前 ledger 创建 protection snapshot → staging 解包/旧 JSON 导入 → schema/完整性/领域验证 → 关闭连接、原子切换、重开/复检。 | 切换前失败删除 staging，当前 ledger 不变；切换后重开失败立即用 snapshot 回切并进入恢复页。 |
| 危险清空/出厂 | 先建 protection snapshot，完成领域事务后暴露 `recovery.undoLastDanger`。 | 快照失败则危险操作不开始；undo 同样 staging 验证后切换。 |
| `recovery.createEmpty` | 仅恢复页、明确 confirmation；保存损坏 DB/日志为不可覆盖诊断副本，创建并验证新空 profile ledger。 | 失败保持恢复页；绝不删除原损坏文件。 |

恢复、profile 切换、schema migration 全程持 application 排他锁。恢复 operation 按 `processed_operation` 记录直到下一次成功备份；响应中不泄露 staging/原始绝对路径。

## 5. API 与表/文件映射

| API | 输入 | 输出 |
| --- | --- | --- |
| `POST /backups` | 可选安全建议文件名；无路径。 | backup 元数据和 `downloadUrl`。 |
| `GET /backups/{id}/download` | backup ID。 | `.ledgerx-backup` 流；ID 不可跨 profile。 |
| `POST /backup-previews` | multipart 单文件；最大 512 MiB。 | `{previewId,preview}`，包含 checksum/compatibility。 |
| `POST /recoveries` | `previewId,confirmation,expectedProfileId`。 | 已切换的 profile/health 摘要；profile 不符为 `BACKUP_INVALID`。 |
| recovery undo | 最近 snapshot ID + confirmation。 | 恢复摘要或 `NOT_FOUND`。 |
| themes import/export | 上传受限 CSS 或下载；无路径。 | 受限 CSS/主题名。 |

`ledger_setting.last_auto_backup_at`/保留策略和 `ledger_meta` 是唯一持久化元数据；临时 `previewId`、staging 状态和系统路径不得进入公开 DTO 或长期表。原 JSON 至少保留到用户确认 Java 切换成功且已有一份可验证 format 3 备份。

## 6. 可观测、验收与建议测试

事件：`backup.created`, `backup.failed`, `backup.previewed`, `restore.started/succeeded/rolled-back`, `snapshot.created`, `recovery.required`, `theme.rejected`。记录 format/schema、计数、大小范围、耗时、错误码、profile hash；不记录文件名、路径、manifest 原文、CSS 内容或账本内容。

| 场景 | 可验证结果 | 层次 |
| --- | --- | --- |
| 运行时创建 format 3 | 在独立目录解包后 hash、`integrity_check`、`foreign_key_check`、计数均通过。 | 文件/SQLite 集成 |
| 损坏 ZIP、错 hash、错 profile | preview/restore 稳定拒绝，活动数据库字节不变。 | 故障注入 |
| restore 中断/磁盘满 | 当前账本仍能重开；staging 可安全清理。 | 文件系统集成 |
| schema 4/format 2 | 能导入到旁路 SQLite；format 1/schema 3 明确拒绝。 | 迁移黄金测试 |
| 自动备份失败 | 新记录仍保存，日志/界面可见备份失败。 | 集成 |
| 无效 CSS | 不保存任何主题变更；合法变量 CSS 重启后仍生效。 | 安全/生命周期 |
| `undoLastDanger` | 清空前数据完整恢复，且不会跨 profile。 | E2E |

## 7. 待决与升级

应用锁、数据库加密、加密备份明确不在本期；不得在 manifest/UI 宣称“已加密”。若增加云同步、密码、跨 profile 恢复、异地副本或格式 3 以外读取，需高级模型先定义密钥、身份、冲突、保留和兼容策略。
