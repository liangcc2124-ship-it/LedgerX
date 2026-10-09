# P9-001：版本化备份格式与一致性快照

- 状态：已完成；Java、设置页、真实浏览器→Java→SQLite 验收通过，详见 [验证记录](../verification/P9-001-backup-snapshot.md)
- 类型：数据安全、文件格式与 Java application/persistence
- 依赖：先同步 backup-recovery 模块、database/api 和 ADR；不依赖正式发行门禁
- 影响：Java backup 模块、受控文件目录、HTTP 元数据 API、真实文件/SQLite 测试

## 用户结果

用户可在应用运行时创建一份可独立校验、可长期识别版本、不会复制到一半的用户空间备份。备份失败不影响记账，成功后能看到时间、记录数量、格式版本、大小和完整性状态。

## 格式与所有权

1. Java 是备份事实和文件的唯一写者；浏览器不复制数据库文件。备份位于数据根下受控 `Backups/<profile-id>/`，公开 API 只使用 opaque backup ID，不接受任意路径。
2. 使用 `.ledgerx-backup` ZIP 容器，恰含规范 JSON `manifest.json` 与 SQLite 一致性 `ledger.db`。manifest 明确 `formatVersion=3/backupId/schemaVersion/applicationVersion/profileId/createdAt/dataRevision/counts/encrypted=false/ledger(sizeBytes,sha256)`。
3. 使用当前 SQLite/driver 官方支持的在线一致性能力（优先在线 backup API；若采用 `VACUUM INTO`，必须由应用生成目标路径并验证事务/锁行为）。禁止运行中直接 `Files.copy(ledger.db)`。
4. 生成流程：application gate 短暂捕获 profile 上下文后释放 → 同目录 staging → online snapshot → integrity/foreign-key/count/hash 校验 → force/关闭 → 重开包复检 → 同卷原子 rename。任何失败清理本次 staging，不更新账本或公开备份。
5. 格式读取器按版本分派，未知高版本明确拒绝；旧 reader 不猜测字段。格式升级只新增 reader，不就地改写旧备份。
6. 默认不加密，UI 和文档必须如实说明依赖 Windows 账户/磁盘权限；不能用“安全备份”暗示加密。

## API

- `POST /api/v1/backups`：创建当前 profile 备份，要求幂等键；返回 backup metadata。
- `GET /api/v1/backups?limit&cursor`：有界列出当前 profile 备份。
- `GET /api/v1/backups/{id}/verify`：只读重新读取并验证；不恢复。
- `GET /api/v1/backups/{id}/download`：流式下载已发布包；成功响应明确 UUID 文件名和备份媒体类型。
- 网页版只通过受控备份 API 展示、验证和下载备份；不接收浏览器提交的本机绝对路径，也不依赖原生目录能力。

## 验收

- 运行中有真实 application 写入时生成的备份仍可在隔离目录打开，并通过 hash、manifest count、`integrity_check` 与 `foreign_key_check`。
- 坏 hash、截断 ZIP、未知格式、跨 profile ID 均稳定失败；快照创建本身不递增或改写活动账本业务状态。
- 同一 profile 的同幂等键并发或响应丢失重试只对应一份已发布备份；幂等范围不跨 profile 泄漏。
- 备份列表分页和下载文件名不泄漏原始私人路径；磁盘/权限/原子移动失败不得暴露半成品或修改源账本。

## 禁止事项

禁止复制运行中的单个 SQLite 主文件；禁止让 renderer 传绝对路径；禁止把 ZIP 成功写出等同于备份有效；禁止在本任务顺带实现云同步或加密。
