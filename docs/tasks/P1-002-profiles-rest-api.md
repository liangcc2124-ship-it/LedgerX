# P1-002：Profiles catalog REST

- 状态：已实施，PASS
- 单一结果：真实 Java API 支持列出、创建并激活、切换、归档用户空间，具备持久幂等、乐观并发、故障回滚和重开证据。

## 1. 范围与非目标

范围：profiles application service、active context、catalog repository/operation、四个 profiles endpoints、共享 fixtures、Java/SQLite/HTTP 测试。非目标：Vue 页面、settings、profile 重命名/恢复/物理删除、旧 JSON 导入、业务表、备份和发布包。

## 2. 前置和引用

- 前置：P1-001 PASS。
- 必读：[profiles 模块](../modules/profiles-catalog.md)、[profiles API](../api/profiles-api.md)、[全局 API](../api.md)、[数据库](../database.md)、[persistence-migration](../modules/persistence-migration.md)。

## 3. 允许修改

- `src/main/java/com/ledgerx/application/profile/**`
- `src/main/java/com/ledgerx/persistence/**` 中 profile catalog repository/transaction 的最小新增
- `src/main/java/com/ledgerx/http/**` 的路由、JSON 和 error 映射
- `src/test/**` 对应测试、`docs/contracts/api-v1/profiles/**`
- 本任务实际报告及受影响的能力/运行文档

不得修改 DDL、全局 API、Electron/Vue、旧实现或引入框架/依赖。

## 4. 实施与契约

1. 建立单一 `ActiveProfileContext`/排他写门，status 和 profiles use case 读取同一活动 snapshot；不建立第二套 active ID。
2. 精确实现 profiles API 的 DTO、校验、headers、状态码、ETag、分页游标和错误；HTTP 不直接执行 SQL。
3. 创建：安全创建目标目录/ledger并完整校验，随后 catalog transaction 写 profile/pointer/revisions/operation，最后切换 context；失败保持旧 active。
4. 激活：先验证目标 ledger，再 transaction 更新旧/目标 revision、lastOpenedAt、pointer/catalogRevision/operation；当前目标是成功 no-op。
5. 归档：只更新 INACTIVE profile；不读取、移动或删除目标账本。
6. catalog operation 与业务 operation key 跨作用域检查；同 key 同请求回放首次 status/body/ETag/Location，不再次更改任何 revision。
7. 所有 SQL 参数化；日志只含路由模板、request ID、结果、耗时、revision 和 profile hash。

## 5. 验收标准

- profiles API 文档第 7 节每个示例均有合同或集成断言。
- 新建→list→关闭→重开保持新 active/profile/revision/dataRevision，旧 ledger 不变。
- 两个空间各写入可辨识测试元数据后反复切换，不发生跨空间读取；本任务无业务表时以各自 `ledger_meta.profile_id` 证明。
- 同 key 重试和 operation 查询真实跨重启回放；不同 body/path 冲突。
- If-Match 缺失/过期、归档当前、损坏目标、目录逃逸、错误 token/origin 均零部分变更。
- profile switch 后 system status 的 activeProfileId/dataRevision 与 profiles 响应一致。
- 现有 Java 全量回归继续通过。

## 6. 测试与升级

运行领域单元、SQLite 文件集成、HTTP合同、Java完整 `test package`；真实 Electron/Vue/发布包不适用，留给后续 UI 集成任务。测试使用隔离临时数据根并只清理已确认的本例目录。

若实现需要改 DDL、profiles API、全局 operation 语义或在 catalog/文件故障下无法维持旧 active，停止并升级；不得以进程内 Map 冒充持久幂等或放宽断言。

## 7. 实施记录

- 新增单一 `ProfileApplicationService`/`ActiveProfileContext` 与公平排他写门；system status 和 profiles use case 读取同一活动 snapshot。READY 状态声明 `system.status`、`profiles.read`、`profiles.write`。
- 新增 SQL-only `ProfileCatalogRepository`，catalog transaction 同时写 profile/pointer/revision 和 `catalog_processed_operation`；未改动任何 migration 或 DDL。
- 已实现 `GET/POST /api/v1/profiles`、`POST /api/v1/profiles/{id}/activate`、`DELETE /api/v1/profiles/{id}` 及既有 `GET /api/v1/operations/{key}` 的 profiles 具体投影。HTTP adapter 不执行 SQL。
- 已覆盖真实临时 SQLite 和 loopback HTTP：trim/非法输入、分页游标过期、创建/切换/归档、If-Match、catalog/ledger 跨作用域幂等冲突、持久 replay、目录逃逸、缺账本、认证/Origin、恢复列表与 423 写拒绝。
- 最终命令和准确数量见 [P1-002 验证记录](../verification/P1-002-profiles-rest.md)。Vue/Electron 页面、正式安装包、签名、桌面快捷方式不在本任务范围。
