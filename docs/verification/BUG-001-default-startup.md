# BUG-001 默认启动修复前后证据

本记录只保留可比较的脱敏结果，不包含会话令牌、完整本机路径或账本内容。

## 修复前（2026-09-13）

- 前置产物：`target/classes`、`target/cp.txt` 和 `frontend/dist/index.html` 均存在。
- 操作：以临时 `LOCALAPPDATA`、临时 Electron `--user-data-dir` 启动真实 Electron；未设置任何 `LEDGERX_TEST_*` 或 `LEDGERX_DATA_DIR`；保持默认 `sandbox:true`。
- 受控主机结果：`backend.source_fallback` → `backend.spawned` → `renderer.loaded`，其中 `renderer.loaded.state=STARTING`；临时规范根下不存在 `profiles.db`，Electron `userData/data` 下也不存在该文件。
- 进程：真实 Java 子进程已被本次 Electron 会话回收；日志未包含令牌或完整数据路径。
- 受限执行环境另一次结果为 Chromium `launch-failed/49`，仅记录为环境限制，不作为 BUG-001 根因证据。

该结果证明默认 Electron 启动没有把运行时数据根传给 Java，导致 Java 使用 `InitialSystemStatusProvider` 而没有执行 profile bootstrap。

## 修复后（2026-09-13）

- Maven `clean package` 在没有预先保留 `target/cp.txt` 的情况下生成了非空 classpath 文件（407 bytes），包含 Jackson runtime 和 `sqlite-jdbc`；`HttpServerMain.class` 同时存在。
- 单元回归：Electron `10/10` 通过；覆盖测试覆盖优先级、绝对路径校验、缺少 `LOCALAPPDATA`、通用环境变量隔离和安全基线。
- 真实原生回归：`npm run test:integration` 使用默认 `sandbox:true`，2/2 通过（既有隔离链路 + BUG-001 默认启动链路）。
- 默认启动环境仍未设置 `LEDGERX_TEST_*`，并故意继承了一个无效的通用 `LEDGERX_DATA_DIR`；临时 `LOCALAPPDATA` 下创建 `LedgerX/profiles.db`，继承目录和 Electron `userData/data` 均没有数据库。
- 脱敏阶段顺序：`backend.source_fallback` → `desktop.start` → `backend.spawned` → `backend.listening` → `backend.status_checked(state=READY)` → `renderer.loaded(state=READY)`。
- 同一隔离根关闭并重开后，真实 status 仍为 `READY`，`activeProfileId` 保持不变，`dataRevision=0`；`Profiles/<id>/ledger.db` 可重读。
- 缺少 `LOCALAPPDATA` 的真实边界：日志只有 `backend.start_failed`、原因码为 `LOCALAPPDATA is unavailable`，没有 `backend.spawned`；未记录绝对目录、令牌或响应体，临时根已清理。
- 测试结束只清理本次创建的临时根；未读取或修改真实 `%LocalAppData%\LedgerX`、工作区旧数据或账本内容。
- 受控主机运行时为 Node `v24.19.0`、Java `11.0.15.1`；BUG-004 已在同一用户授权 Node 24 环境完成最终重验，Node `22.18.0` 基线仍未认证。
