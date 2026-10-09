# P7-002 验证记录：账本初始化与账户启用日期

- 结果：PASS
- 日期：2026-09-23
- 数据范围：全部使用临时隔离数据目录/SQLite；未访问或修改默认用户账本
- 使用范围：源码与 Vue 页面已验证；未生成离线分享包，本任务不负责分发

## 自动化结果

| 层次 | 命令/证据 | 结果 |
| --- | --- | --- |
| Java application、SQLite migration、HTTP 与领域回归 | `.\mvnw.cmd -q test` | 21 个测试类、68 项通过，0 失败/错误；包括 PENDING 初始化、历史审查、并发同键、失败回滚与重试、V005→V006 分类、旧 revision/记录保留、日期边界和业务 API 初始化门禁 |
| Vue 配置与 API/composable 单元 | `npm --prefix frontend test` 中的 Node 测试 | Vite 配置 7 项通过；API client/composable 7 项通过 |
| 浏览器交互 | 同上 Playwright 回归 | 46 项通过；覆盖 PENDING 首次设置、REVIEW_REQUIRED 显示原值并要求显式校正、窄屏和既有页面回归 |
| 真实功能闭环 | `node frontend/scripts/local-browser-e2e.mjs` | Chromium → Java 同源 HTTP → 隔离 SQLite 通过；包括初始化前直接读取返回 409、初始化、HttpOnly 会话/CSRF、记录新增/编辑/回收/恢复、刷新、Java 重启后重载及余额核对。测试确认同数据目录第二个 Java 写者被拒绝，结束后清理临时账本 |

## 数据迁移与失败保护

- V005 空账本和未更改默认 seed 升级到 `PENDING`；修改过的默认账户和多账户空账本进入 `REVIEW_REQUIRED`，原账户事实保留。
- 有效历史记录自动使用最早可观察业务日期完成迁移；回收站记录被纳入日期与账户约束。归档账户存在结算早于开户日时进入 `REVIEW_REQUIRED`，不改写历史。
- 非 ISO 日期、未知结算模式和孤立账户引用会使 V006 DDL、分类 hook 与 schema history 整体回滚；原 V005 schema/行仍在，坏数据不降级为 PENDING。
- 初始化审查中注入数据库更新失败，确认默认账户更改、账户更正、setup 状态、revision、dataRevision 与 operation 全部回滚；移除故障后使用同一幂等键/请求体重试成功。
- 首启和历史审查均验证了未来日期、账本日期下限、账户开户日与 ACTIVE/TRASHED 结算约束。相同初始化幂等键并发只提交一个 operation；后续调用回放同一响应。

## 未覆盖与后续门槛

本任务未验证离线分享包、安装/升级/回滚、发布物哈希以及 20,000 条记录性能；这些不属于当前个人本机使用门槛。数据库恢复/备份 UI 和旧 JSON 导入也不属于本任务。
