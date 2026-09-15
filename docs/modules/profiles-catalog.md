# 模块规格：profiles-catalog

- 状态：P1-002 已实施（后续 UI/恢复工作另行拆分）
- 边界：本机用户空间目录、创建、切换、归档、catalog 版本与 catalog 级幂等。账本业务内容、旧 JSON 导入、备份恢复、设置偏好和 UI 不在本模块。
- 引用：[需求 §6.1、§7.2](../requirements.md)、[架构 §4、§6、§8](../architecture.md)、[数据库 §2–§3、§7–§8](../database.md)、[全局 API §3、§5、§10](../api.md)、[ADR-003](../decisions/ADR-003-sqlite-per-profile.md)。

## 1. 职责、术语、用例与非目标

**职责**：维护 `profiles.db` 中的空间目录与唯一活动指针；创建并验证每个空间的独立 `ledger.db`；切换活动 application context；为跨空间 catalog mutation 提供持久幂等与乐观并发。

| 术语 | 定义 |
| --- | --- |
| catalog | `%LocalAppData%\LedgerX\profiles.db`，只保存空间索引、活动指针、catalog revision 与 catalog 幂等结果。 |
| active profile | 当前 Java application context 唯一允许普通业务读写的空间。 |
| catalog revision | `catalog_setting.revision`；每个成功改变 catalog 的 mutation 恰好加一。 |
| profile revision | 单个 `profile.revision`；名称、归档状态、最近打开时间或活动状态变化时加一。 |

用例：列出空间；新建并立即切换到新空间；切换到未归档空间；归档非当前空间。非目标：重命名、恢复已归档空间、物理删除空间目录、跨空间业务查询、在线账户、权限角色、profile 备份或迁移向导。

## 2. 入口、输出、依赖和方向

| 项目 | 内容 |
| --- | --- |
| 入口 | `/api/v1/profiles` REST；应用启动 bootstrap。 |
| 输出 | `ProfileSummary`、`activeProfileId`、profile/catalog revision、目标账本 `dataRevision`。 |
| 依赖 | application 排他写门、`ProfileBootstrap`/`LedgerBootstrap`、catalog repository、Clock、文件系统。 |
| 被依赖方 | system status、所有 active-profile 业务模块、Vue 空间切换器、迁移与恢复模块。 |
| 方向 | HTTP → profile application service → persistence/filesystem；persistence 不依赖 HTTP 或 Vue。 |

当前 Windows 桌面会话是唯一主体。只有本模块可显式接收 profile ID 并改变 active context；其他业务模块只能读取当前 context。

## 3. 实体、规则、不变量与权限

`ProfileSummary`：`id`、`name`、`status`、`createdAt`、`lastOpenedAt`、`revision`。`status` 是
`ACTIVE|INACTIVE|ARCHIVED`，由 `catalog_setting.active_profile_id` 与 `archived_at` 投影，不单独持久化。

- ID 为前端生成的小写 UUID；目录固定 `Profiles/<id>`，API 不返回目录。
- 名称 trim 后 1–100 个 Unicode code point；本期允许重名，以 UUID 保持身份，不增加未有依据的唯一约束。
- catalog 始终恰有一行 `catalog_setting(id=1)`，且 active ID 指向未归档 profile。
- 新建成功后立即成为 active；旧 active 变为 INACTIVE。新账本创建、V001、完整性校验和 catalog 提交任一步失败都不能改变原 active 指针。
- 切换只允许目标 INACTIVE。目标是当前 ACTIVE 时返回成功 no-op；ARCHIVED/不存在不可切换。
- 归档只允许 INACTIVE；当前 ACTIVE 不可归档。归档保留目录、账本和备份，本期不能恢复或物理删除。
- 每个非 no-op catalog mutation 使 `catalog_setting.revision + 1`；受状态变化影响的 profile revision 各加一。
- profile mutation 使用 catalog 级 `processed_operation`。同 key/同 method/path/body hash 回放；同 key 不同请求冲突。应用写门必须同时检查 catalog 与 active ledger 的 key，防止跨作用域复用。

## 4. 状态转换、失败和补偿

| 操作 | 转换 | 前置 | 失败/补偿 |
| --- | --- | --- | --- |
| create | 新 profile → ACTIVE；旧 ACTIVE → INACTIVE | ID 未占用、目录不存在、名称合法 | 新 ledger 或 catalog 失败：原 active 不变；只清理本次创建且可确认安全的空/临时对象，非空孤儿留给恢复检查。 |
| activate | INACTIVE → ACTIVE；旧 ACTIVE → INACTIVE | If-Match 命中；目标 ledger profile ID/schema/health 有效 | 校验或 catalog 提交失败：继续使用旧 context；不得半切换。 |
| activate current | ACTIVE → ACTIVE | ID 和 If-Match 有效 | 成功 no-op；revision/catalogRevision/dataRevision 均不增加，但保存该幂等结果。 |
| archive | INACTIVE → ARCHIVED | If-Match 命中 | 回滚 catalog transaction；不删除文件。 |

活动切换顺序：取得 application 排他写门 → 停止接收新业务写 → 验证并打开目标 ledger → catalog transaction 更新 revision/pointer/operation → 原子替换内存 context → 释放旧连接。若 commit 后 context 替换异常，进入 `RECOVERY_REQUIRED`，不得继续在旧 profile 写入。

## 5. 数据库和接口映射

| 领域/API | 数据库 | 说明 |
| --- | --- | --- |
| ProfileSummary | `profile` + `catalog_setting.active_profile_id` | 不返回 `relative_directory`。 |
| catalogRevision | `catalog_setting.revision` | 列表游标与之绑定。 |
| profile mutation 幂等 | `catalog_processed_operation` | 与 catalog 变更同事务。 |
| active dataRevision | 目标 `ledger_meta.data_revision` | 切换/创建成功响应使用新 active ledger 值。 |

具体字段、错误和 HTTP 语义见 [profiles API](../api/profiles-api.md)。

## 6. 可观测行为、验收与测试

事件：`profile.list`、`profile.create`、`profile.activate`、`profile.archive`、`profile.switch_failed`。只记录 request ID、动作、计数、耗时、结果码、catalog revision 和 profile ID 的不可逆短哈希；不记录名称、路径、token 或账本内容。

| 场景 | 可验证结果 | 层次 |
| --- | --- | --- |
| 新建空间 | 新 ID 成为 active，旧空间数据不混入，新 ledger 可重开。 | SQLite + HTTP + Electron E2E |
| 同 key 重试创建 | 返回首次 201/body，只有一个目录/profile，revision 不再增加。 | SQLite/HTTP |
| 切换空间 | status 和 profiles 响应均指向目标；旧游标/草稿失效。 | HTTP + E2E |
| 目标库损坏 | 原 active 可继续使用，切换失败且不改 pointer。 | 故障注入 |
| 归档当前空间 | 400，catalog/文件均不变。 | 领域/SQLite |
| 过期 If-Match | 409，较新状态不被覆盖。 | HTTP合同 |

## 7. 待决与升级

- 本期不定义 profile 重命名/恢复/物理删除；产品若需要，先补 API、快照和文件保留策略。
- catalog 与文件系统不能形成单一 ACID 事务；当前通过“先验证新 ledger、最后提交 pointer、受控补偿和恢复模式”降低风险。若要求断电后自动清理/续作，需增加显式创建状态和恢复任务，由高级模型裁决。
- profile 数量预计很小但 API 仍使用游标；如需跨空间汇总或同时打开多个写连接，应重新评估架构。
