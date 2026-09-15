# P1-003：Ledger settings V002 与 REST

- 状态：PASS；依赖 P1-002 PASS
- 单一结果：每个 active profile 有可重开、并发安全、持久幂等的 settings REST；只保存真实偏好，不实现备份/通知/主题文件业务。

## 1. 范围与非目标

范围：ledger V002 migration、settings application/persistence、GET/PATCH settings、fixtures、Java SQLite/HTTP tests、受影响运行文档。非目标：Vue/Electron 页面、自动备份执行、Windows 通知、CSS import/export、profile API、旧 JSON 导入、分类/账户/记录、发布包。

## 2. 前置与允许修改

必读：[settings 模块](../modules/settings-preferences.md)、[settings API](../api/settings-api.md)、[数据库](../database.md)、[全局 API](../api.md)、[P1-002](./P1-002-profiles-rest-api.md)。

允许修改：`src/main/resources/db/ledger/migration/V002__ledger_settings.sql`、settings application/persistence、active runtime/HTTP router 的最小接线、对应 Java tests/fixtures、任务/验证/README/CHANGELOG。不得修改 V001、catalog DDL、profiles API、Vue/Electron、旧 WPF/React 或引入依赖。

## 3. 实施契约

1. 扩展 `LedgerBootstrap` 的有序 migration 列表，严格从 V001 到 V002；V002 创建精确的 `ledger_setting` 表并 seed `id=1`。fresh 与 V001 existing ledger 都得到一行 defaults；V002 重开不重置用户修改。
2. 让 active context 持有可安全打开的 settings service；HTTP 不直接写 SQL。所有 settings write 与未来普通 active-ledger write 使用同一 application write gate。
3. 精确实现 settings API：JSON 类型、enum、金额字符串/分换算、readonly 拒绝、unknown ordinary fields 忽略、empty effective patch 拒绝、ETag/header/error 映射、强 If-Match。
4. 有效 PATCH 在一个 transaction 写 settings、`ledger_meta.updated_at/data_revision`、`processed_operation`；仅 no-op 写 operation。SQL 全部参数化。
5. 同 key replay必须跨重开返回原 body 和原 ETag。因 ledger `processed_operation` 不存 ETag，replay 只能从 stored response 的 `data.revision` 读取，不能读取当前 row。
6. 通知、自动备份、主题仅是设置值；不得由 PATCH 启动任务、写文件、发 Windows 通知或接受 CSS。

## 4. 验收标准

- V001→V002、fresh V002、重复重开都恰有一行 defaults；profile A/B 的 settings 完全隔离。
- GET/PATCH 逐项符合 settings API；金额、enum、read-only、空/非法 body 有稳定 field error，且不写 operation。
- 正常 PATCH、multi-field PATCH、no-op、缺/旧 If-Match、同 key retry/different body、跨重启均断言 revision、dataRevision、body、ETag 和 SQLite 行。
- 错 token/Origin、recovery、损坏 DB、path/SQL injection input 均不形成部分写；认证失败 service/repository 零调用。
- 全量 Java `test package` PASS。真实 SQLite 临时根是必需；Vue/Electron/发布 EXE 不适用，不能冒充验证。

## 5. 升级、文档与报告

若需新增设置字段、修改固定币种、执行自动备份/通知、接受 CSS、改变 DDL/API 全局规则或无法从 stored response 稳定回放 ETag，停止并升级，不自行发明行为。完成时更新 API/module/database/fixtures/verification/README/CHANGELOG，并报告：改动文件、测试数量、真实 SQLite/HTTP 证据、未测 Vue/Electron/发布链路及残余工具链风险。

## 6. 实施报告（2026-09-14）

- 新增 ledger `V002__ledger_settings.sql`，`LedgerBootstrap` 按 V001→V002 迁移；fresh 与 V001 existing ledger 都有唯一 defaults 行，重开不复位用户值。
- `ProfileApplicationService` 继续作为唯一 active context 与公平写门所有者，新增 settings 端口、SQL-only repository 与 `/api/v1/settings` GET/PATCH 路由；未引入依赖、Vue/Electron、备份/通知/CSS 文件行为。
- 真实 `@TempDir` SQLite 与 loopback HTTP 已覆盖 defaults、V001 升级、档案隔离、金额/readonly 校验、If-Match、no-op、重启回放及认证/Origin 拦截。完整结果见 [P1-003 验证记录](../verification/P1-003-ledger-settings-rest.md)。
