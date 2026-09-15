# P2-005：用户空间与设置的真实桌面集成验收

- 状态：PASS（本机 Node 24.19.0 受控权限真实链路 1/1；Java/Vue/Electron 回归通过）
- 单一结果：在隔离数据根中，用真实 Electron、Vue、Java HTTP 和 SQLite 验证用户空间和设置的可见操作、切换隔离与重开持久化；此任务默认不修复产品代码。

## 1. 背景、目标、范围与非目标

P1 分别验证后端，P2-002/P2-003 分别验证浏览器页面，P2-004 提供下一阶段的 schema 迁移。本任务证明用户实际点击能穿过桌面壳到 Java/SQLite，并在重开后得到同一业务结果，同时汇总 P2 已执行任务的状态。

范围：新增/调整真实 Electron E2E 测试和验证记录；默认 profile、创建/切换/归档 profile、每 profile settings 隔离、刷新/重开、会话安全和无公网请求。非目标：修改 Java/Vue/Electron 产品代码、分类/账户/记录、JSON migration、备份、安装包、签名、快捷方式、性能压测。

## 2. 前置依赖与引用规范

- 前置：S0-006、BUG-004、P1-002、P1-003、P2-001、P2-002、P2-003、P2-004 均 PASS，且没有未升级的公共契约变更。
- 必读：[requirements](../requirements.md) §6.1、§6.2、§7.1–§7.4、[architecture](../architecture.md) §5–§10、[desktop-shell](../modules/desktop-shell.md)、[UI 模块](../modules/ui-integration.md)、profiles/settings API、[S0-006](./S0-006-electron-rest-integration.md)、[BUG-004](./BUG-004-s0-startup-reacceptance.md)。
- 若发现缺陷，按 BUG 流程建新 Task Spec；不得把集成验收变成无规格的修复任务。

## 3. 允许/预计修改的文件

- 新增 `electron/tests/p2-005-catalog-settings.integration.spec.mjs`；仅在该文件内需要时新增专属测试 helper
- `docs/verification/P2-005-catalog-settings-ui.md`
- 本 Task Spec、`docs/tasks/README.md`；若命令被实际验证，可最小更新 `README.md`。P2-001/P2-002/P2-003 的状态汇总只能在这些依赖均 PASS 后由本任务写入。

禁止修改 `frontend/src/**`、`src/main/**`、migration、API/module/database 文档、package manifests、旧 WPF/React、正式 release 目录与桌面快捷方式。任何产品代码失败均必须停下并报告。

## 4. 实施步骤与必须遵守的契约

1. 创建并审计一个位于测试临时根的唯一隔离数据目录；它不得等于工作区、`%LOCALAPPDATA%\\LedgerX`、用户 home 或已有真实数据。Electron 启动时不传 `--no-sandbox`，使用打包/源码的现有安全配置和真正 Java child process。
2. 从 READY 首页进入“用户空间”。创建 profile A（例如“家庭账本”）；断言 UI 显示 A 为 ACTIVE。创建 profile B（例如“工作账本”）后，A 成为 INACTIVE、B 成为 ACTIVE；不得通过测试直接请求 Java REST 或写 SQLite 代替 UI 点击。
3. 在 B 的设置页把 `safetyBuffer.amount` 改为 `1234.50` 和 `autoBackupIntervalDays` 改为 `14` 并保存；页面必须仅宣称设置保存，不得声明已经备份或发送通知。切换回 A 后 A 仍显示自己的默认值；再切 B 后显示精确 `1234.50`/`14`。
4. 在 A active 时，使用页面内的二次确认归档 B。断言 B 从默认 profile list 消失、勾选“显示已归档空间”后显示 ARCHIVED，且 UI 没有恢复/物理删除能力。归档确认前不得发生 DELETE。
5. 关闭整个 Electron 与 Java，确认本次 child PID 正常退出。以同隔离根重新启动；等待 READY 后在 UI 中验证 A 为 active、B 的归档状态仍在（筛选后可见），A settings 未混入 B。可以在测试结束后只读检查各 ledger SQLite/health 作为补充，但 UI 重读是必需证据。
6. 采集并检查：renderer bundle/DevTools 不含 session token；无 token/错 Origin 的外部请求仍拒绝；网络观察没有非 loopback 请求；日志/报告不含 profile 名、金额、note、绝对路径或 token。保留脱敏摘要，安全清理已核验的隔离目录。

## 5. 用户操作、跨层字段、结果与边界

| 用户操作 | 前端反馈 | 跨层数据 | 业务/持久化结果 | 边界 |
| --- | --- | --- | --- | --- |
| 创建/切换 A、B | 活动状态即时变化，旧列表不残留 | profile UUID、ETag、key、catalog revision | Java 原子切换独立 ledger | 新 profile 自动 active；旧 profile settings 不串 |
| 在 B 保存设置 | 成功后显示 Java 返回 DTO | amount string、If-Match、key、revision | 仅 B ledger V002 setting 更新 | 不创建 backup/notification/theme file |
| 切 A/归档 B | 二次确认、筛选可见 archived | path/revision/key | B catalog archive，不删除 B 文件 | 确认前无 DELETE；active profile 不可归档 |
| 完整重开 | READY 后重新读取相同状态 | 新 session token/loopback port | 数据保留、无孤儿 Java | 旧 token 拒绝；无公网请求 |

## 6. 验收标准

- 必须通过实际 UI 完成“创建 A → 创建 B → B 设置保存 → A/B 切换隔离 → A active 归档 B → Electron/Java 全量重开”的顺序；每一步截图/DOM 断言及 HTTP/SQLite 脱敏证据可追溯。
- B 的 `1234.50` 和 `14` 精确保存且只在 B 可见；A 不能看到 B 值。重开后 A active、B ARCHIVED，筛选行为符合 profiles API。
- 设置页面不创造 backup/notification/theme 文件行为；profile 页面确认前不发送 archive request；任何 revision/operation conflict 都不是假成功。
- 真正 Electron renderer 不拥有 token/Node，默认 sandbox 仍启用；错误 token/Origin 拒绝，无外网请求，无本任务 Java 子进程残留。
- Java、Vue、Electron 的既有必跑测试与本新增 E2E 均通过。若任何一项没有证据，P2-005 不能标 PASS。

## 7. 测试层次、命令与真实链路

- 必跑：根目录 `./mvnw.cmd -q '-Dmaven.compiler.fork=true' test package`；`frontend/` 中 `npm test`；`electron/` 中 `npm test`，再直接执行 `node node_modules/@playwright/test/cli.js test tests/p2-005-catalog-settings.integration.spec.mjs --reporter=line`。该 P2 命令不修改 package script 或依赖。完成者须在验证记录写出实际命令、通过数量、Node 版本与任何 engine warning。
- 真实原生链路：**是，强制**。必须为 Electron → Vue renderer → 受认证 REST → Java HttpServer → sqlite-jdbc 文件库，并使用隔离目录、真实重开；mock、browser-only、手写 HTTP 或 SQL 都不能替代。
- 发布包和桌面快捷方式：不适用，除非任务范围发生获批改变；不得为完成验收而打包或改快捷方式。

## 8. 禁止事项、升级、文档与完成报告

禁止使用 `--no-sandbox`、测试真实用户数据、杀所有 java 进程、替换 token/Origin 防线、修改产品代码、把失败 UI 改成直接 HTTP/SQL，或将 Forge audit/Node 22 未复测说成已解决。发现公共 API/数据库/架构变化、默认数据根写入、token 泄露、数据串空间、重开丢数据、Electron sandbox 不可用或产品缺陷时，停止并创建/升级 BUG 规格。

完成报告固定包含：PASS/FAIL；测试/验证文件；每层实际命令和数量；真实进程/端口/SQLite 重开脱敏证据；隔离根复核与清理；发布包/快捷方式不适用；未测项、Node/Forge 残余风险、Review 关注点。 

## 9. 实施报告（2026-09-14）

- 变更：新增 `electron/tests/p2-005-catalog-settings.integration.spec.mjs`；同步 `electron/tests/s0-006.integration.spec.mjs` 中 ledger V004 的 `schemaVersion=4` 断言；更新 BUG-001/S0-006 验收文档，并新增本验证记录。未修改 Java/Vue/Electron 产品代码、API、数据库或依赖。
- Java：`$env:MAVEN_OPTS='-Duser.home=C:\\Users\\liang'; .\\mvnw.cmd -q '-Dmaven.compiler.fork=true' test package`，Surefire 31/31 通过，`target/cp.txt` 已由 package 生成。
- Vue：`frontend` 执行 `npm test`，配置 7/7、浏览器回归 36/36 通过，使用本机 Node 24.19.0；仅有 Playwright 的 `NO_COLOR` 环境提示，无 engine warning。
- Electron：`electron` 执行 `npm test`，单元 10/10、基础 E2E 2/2 通过；`npm run test:integration`（S0-006）2/2 通过；P2-005 专属真实 E2E 1/1 通过。全部默认 `sandbox:true`，未使用 `--no-sandbox`。
- 真实链路：隔离 `%TEMP%` 数据根完成创建 A/B、设置保存、空间切换隔离、归档二次确认、关闭/重开和 UI 重读；旧 token 返回 401，错误 Origin 被拒绝，renderer 无 Node/token，全请求为 loopback，Java 子进程均在关闭后退出。隔离目录已清理，日志与输出未包含 profile 名称、金额、绝对路径或 token。
- 不适用/残余：本任务未生成发布包、签名或桌面快捷方式；仍未认证 Node 22 基线，Forge 6.4.2 安全审计继续受 ADR-006 临时豁免约束。
