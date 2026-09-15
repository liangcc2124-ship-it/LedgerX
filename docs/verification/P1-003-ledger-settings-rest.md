# P1-003 Ledger settings REST 验证记录

- 日期：2026-09-14
- 结果：PASS
- 环境：Windows 11 x64、Java `11.0.15.1`、Maven Wrapper `3.9.11`、Node `24.19.0`（本任务未调用 Node）

## 交付范围

- ledger 按严格 V001→V002 顺序迁移；V002 创建唯一 `ledger_setting(id=1)` defaults 行。fresh 与既有 V001 ledger 都可重开，已有设置不会被重置。
- `ProfileApplicationService` 继续持有唯一 active profile context 与公平写门，并提供 settings port；`LedgerSettingsRepository` 只执行参数化 SQL，HTTP 不直接访问 SQLite。
- 真实 loopback HTTP 提供 `GET/PATCH /api/v1/settings`，实现金额字符串/分转换、只读字段拒绝、强 ETag、revision/dataRevision、no-op、7 天持久幂等和跨重启回放。READY status 声明 `settings.read`/`settings.write`。
- 自动备份、通知、CSS 主题文件、Vue 页面、Electron 和发布 EXE 不在本期范围；PATCH 只保存偏好值。

## 已完成验证

| 层次 | 命令/数量 | 结果 |
| --- | --- | --- |
| SQLite 迁移/重开 | `PersistenceBootstrapTest`，7 项 | PASS：fresh V002、V001→V002、重开、默认 settings 行、schema version 2、迁移完整性和恢复行为。 |
| application + SQLite | `SettingsApplicationServiceTest`，2 项 | PASS：多字段更新、分金额精度、no-op、ETag、profile A/B 隔离、重开、重放、无效/旧版本不创建 operation。 |
| 真实 Java loopback HTTP | `SettingsHttpServerTest`，2 项 | PASS：GET/PATCH fixtures、readonly/金额/If-Match/幂等冲突、跨重启重放、认证/Origin 在 settings port 前拒绝且零调用。 |
| 完整 Java 回归 + package | `mvnw.cmd -q -Dmaven.compiler.fork=true test package`，27 项 | PASS：profile service 3、settings service 2、Java 11 baseline 1、既有 HTTP 6、profiles HTTP 1、settings HTTP 2、migration 5、persistence 7；0 failure/error/skipped。JAR 已重新生成。 |

所有持久化测试使用 JUnit `@TempDir` 下的真实 SQLite catalog/ledger；没有读取或写入 `%LocalAppData%` 的真实用户数据。HTTP 测试使用真实 JDK loopback server，不是 bridge/mock 替代。

## 不适用与残余风险

- 本期没有 Vue settings 页面、Electron 生命周期、真实桌面壳、发布 EXE、签名或桌面快捷方式交付；因此未声称这些链路已验证或更新。
- 最终完整 Maven 运行退出码为 0、Surefire XML 为 27/27 且 JAR 已生成；不过当前 Windows/JDK 11 组合仍在该运行退出阶段输出 ZipFS/JAR close `AccessDeniedException` 诊断。它没有令 Maven 或测试失败，但仍作为工具链风险保留观察，不能表述为干净输出。
- 统一结构化日志基础设施尚未落地；本期可观察证据为请求 ID、HTTP envelope、ETag/revision/dataRevision 与 SQLite operation 行，未伪称已写入 settings 事件日志。
