# S0-006：Vue/Electron/REST/SQLite 基座集成验收

## 1. 背景、目标、范围、非目标

单一结果：在真实 Windows Electron 中，从隔离目录启动真实 Java，初始化/重开 SQLite，通过受认证 REST 加载 Vue status 页面，并形成明确 PASS/FAIL 证据。

范围：只集成 S0-001..005 已有能力，修复不改变公共契约的接线缺陷，验证安全/生命周期。非目标：财务业务、旧 JSON迁移、备份、正式安装器、性能 100k、PDF、删除旧 WPF/React。

## 2. 前置与引用

- 必须先完成 S0-001、S0-002、S0-003、S0-004、S0-005；其未解决升级项为 0。
- 必读：[requirements §7](../requirements.md)、[architecture §5/§8/§10](../architecture.md)、[desktop-shell](../modules/desktop-shell.md)、[local-api-contract](../modules/local-api-contract.md)、[ui-integration](../modules/ui-integration.md)、[persistence-migration](../modules/persistence-migration.md)。

## 3. 允许修改

- 集成脚本/测试与 `docs/verification/**` 证据；
- S0 模块原归属文件中仅修复明确接线错误，每个修改必须报告其来源任务；
- README 的目标架构开发命令仅在命令实际验证后更新；
- 本任务证据。

不得新增能力/依赖、改 API/DDL/状态字段、迁移业务 UI、接触真实用户数据或制作正式 release。

## 4. 集成步骤与契约

1. 创建并解析绝对隔离目录，确认位于测试临时根且不等于 workspace、用户 home 或正式 `%LocalAppData%\LedgerX`；把它作为 `LEDGERX_DATA_DIR`。
2. 用 wrapper 构建 Java；用 `npm ci/build` 构建 frontend；Electron 开发运行加载真实 frontend（可为 Vite dev 或 Java 同源 production staging，但必须标明）。
3. Electron 获取单实例、生成 token、spawn Java；Java 成功启动 loopback 监听后立即输出一次 transport readiness。Electron 注册精确 header 注入并完成已认证 status 握手；无论 V001 bootstrap 在监听前还是监听后完成，都不能仅凭 readiness 判断应用可用。
4. `GET /api/v1/system/status` 必须最终返回 READY、schema 1、默认 profile UUID、dataRevision 0 后才启用业务；若先返回 STARTING，只显示初始化状态并在有界超时内重查。DOM 最终显示“本地服务已连接”。
5. 关闭并重新启动整个 Electron；同一隔离目录重开同 profile，schema/history无重复。
6. 验证第二实例、10 次 renderer refresh、错误 token 外部请求、错误 origin、后端 kill、用户 retry、正常退出和父进程异常清理。
7. 验证断网时没有公网请求；静态资源、API、错误页全部本地可用。
8. 收集脱敏日志/测试结果，最后只删除本次创建且路径已复核的隔离目录；若需要保留失败证据，复制脱敏摘要，不保留账本原文。

## 5. 验收矩阵

| 用户操作 | 前端反馈 | 跨层数据 | 业务 | 持久化 | 边界 |
| --- | --- | --- | --- | --- | --- |
| 首启 | 初始化后已连接 | transport readiness/token注入/status READY | 默认空间 | DB/V001/revision0 | 断网/无JRE配置/status超时 |
| 重启 | 同样已连接 | 新 token/port，可同 profile | 无重复 | 同 UUID/history | 旧 token 失效 |
| 刷新/第二实例 | 页面恢复/原窗聚焦 | 同 Java PID | 无 | 无重复 DB | 10 次刷新 |
| kill Java | 断开+重试 | 新 PID/token/port | 不伪成功 | 原 DB 可重开 | 不循环重启 |
| 退出 | 窗口关闭 | 进程结束 | 无 | DB可再开 | 无孤儿进程 |

## 6. 可逐项验收

- 所有 S0 指定构建/测试命令在同一源码/lockfile 下通过；数量逐层报告。
- 真实 Electron→Vue→HTTP→Java→JDBC→SQLite 启动/重开链路完成，不使用 mock 替代。
- 浏览器 renderer 看不到 token/Node；外部无 token/错 origin 不能访问 status；旧 session token 在重启后 401。
- 首启/重启数据库 profile 数均 1、ID 相同、dataRevision 0、migration history 一条且 checksum相同。
- 第二实例/刷新不产生额外 Java；kill/retry 恰好新建一个；退出后无本任务 Java PID。
- readiness/日志/响应/前端 bundle 不含 token、完整路径、用户名或账本内容。
- 断网网络记录没有非 loopback请求。
- 不修改/读取正式 LedgerX 数据目录，工作区旧 JSON/C#/React hash 不变（构建输出除外）。

## 7. 测试与真实原生链路

必跑：Java test/package、frontend build/Playwright、Electron integration、SQLite重开/health。真实原生链路：**是**，必须用真正 Electron、真正 Java HttpServer、真正 sqlite-jdbc 文件库。发布包/快捷方式不适用，因为本任务不是发布。

## 8. 禁止、升级、文档和报告

禁止用浏览器 mock宣称 PASS、为了通过而放宽 auth/CSP/path、改全局契约、杀所有 java、使用真实用户数据或删除旧实现。任何需要改 API、数据库、依赖或架构的缺陷都停止并升级。

完成报告固定包含：结果 PASS/FAIL；改动文件及归属；每层命令/通过数量；真实链路 PID/端口已脱敏证据；隔离目录确认/清理；原生链路是；发布包/快捷方式不适用；未测试、限制、残余风险和 Review 关注点。

## 9. 当前执行报告与验收更正（2026-09-13）

- 结果：**PASS（用户授权 Node 24 环境；Node 22 基线未认证）**。历史与当前证据见 [docs/verification/S0-006-electron-rest.md](../verification/S0-006-electron-rest.md)，重新验收由 [BUG-004](./BUG-004-s0-startup-reacceptance.md) 完成。
- 新增/修复：`electron/tests/s0-006.integration.spec.mjs`、`electron/package.json` 的 `test:integration`、Electron retry 生命周期和错误页 bridge 接线、源码 `npm start` 的 target/dist 回退、`docs/verification/S0-006-electron-rest.md`。
- 通过数量：Java 12/12、Vue 6/6、Electron unit 8/8、Electron 基础 E2E 2/2、S0-006 集成 1/1。
- 真实链路使用隔离临时目录和真实 Electron/Java/JDBC/SQLite；覆盖测试变量驱动的首次启动、重开、第二实例、刷新、错误认证/Origin、Java kill/retry、旧 token 失效、父进程异常清理和无公网请求。
- BUG-001 已补齐默认 `%LOCALAPPDATA%\LedgerX` 数据根、Java 子进程环境接线和 Maven `package` classpath 产物；BUG-002 已修复 renderer 的启动状态语义并通过 11/11 浏览器回归；BUG-003 已收紧 Vite loopback `/api/v1` 代理并通过 7/7 配置及 11/11 前端回归；BUG-004 已在用户授权 Node 24 环境完成首导航、默认数据根、重开和真实 Electron/Java/SQLite 重验。当前环境 S0 PASS，Node 22 基线仍待单独复测。
- 发布包、快捷方式不适用；Forge 审计豁免和 Node 22.18.0 基线差异仍是发布前 Review 项。
- 追加源码启动冒烟：受控主机启动 8 秒内记录 `backend.source_fallback`、真实 Java PID 和 `renderer.loaded`，随后正常退出；该冒烟**显式传入临时 `LEDGERX_DATA_DIR`**，因此不是默认启动证据。
- 历史审查曾发现：不传 `LEDGERX_TEST_*`/`LEDGERX_DATA_DIR` 时默认数据根未传给 Java，Vue 将 `STARTING` 显示为成功，且 Vite 开发代理可将 token 发向任意 origin；BUG-001/BUG-002/BUG-003 已分别修复，BUG-004 已补充默认启动、首导航和完整安全边界证据。
