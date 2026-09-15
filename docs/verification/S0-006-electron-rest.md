# S0-006 真实集成验收记录

- 结果：PASS（用户授权 Node 24 的当前环境；Node 22 基线未认证；不代表正式发布）
- 日期：2026-09-13
- 原生链路：是，真实 Electron 44.3.0（`sandbox:true`）→ Java 11 `HttpServerMain` → JDBC/sqlite-jdbc → SQLite 文件 → Vue dist
- 数据：仅使用 `%TEMP%\ledgerx-s0-006-*` 新建隔离目录；测试结束已清理。未读取或修改 `%LocalAppData%\LedgerX`、工作区旧 JSON、WPF 或 React 数据。

## 验收状态更正（2026-09-13）

本记录中的历史集成场景将 `LEDGERX_TEST_DATA_DIR` 显式传入 Electron；BUG-004 追加了不传测试数据变量的默认启动/重开和首导航观察。**修复前**在无 `LEDGERX_TEST_*`、无 `LEDGERX_DATA_DIR` 的受控源码启动中，日志为 `backend.source_fallback`、`backend.spawned`、`renderer.loaded state=STARTING`，且临时默认数据根没有 `profiles.db`；BUG-001/BUG-002/BUG-003 已分别补齐数据根、状态语义和 Vite 开发代理安全边界，BUG-004 已在用户授权 Node 24 环境完成重验。

下表保留历史隔离测试数量，同时补充 BUG-004 当前 Node 24 授权环境的重新运行结果；只有后者覆盖默认启动、首导航和重开断言，Node 22 基线仍不在本次结论内。

## 运行环境与命令

| 层次 | 命令 | 结果 |
| --- | --- | --- |
| Java | `MAVEN_OPTS=-Duser.home=C:\Users\liang .\mvnw.cmd -q '-Dmaven.compiler.fork=true' test package` | 12/12：Java 11 基线 1、HTTP 6、SQLite/profile 5 |
| Java classpath | 由同一次 `test package` 的 Maven `package` 生命周期生成 `target/cp.txt` | 通过；无需另行运行 dependency goal |
| Vue | `npm ci`、`npm run build` | 通过；Vite 8.2.2 产出 dist（Node 24 有 engine warning） |
| Vue 浏览器 | `npm test` | 配置 7/7；BUG-002/前端回归 11/11 通过 |
| Electron 单元 | `npm run test:unit` | 10/10 通过 |
| Electron 基础 E2E | `npm run test:e2e` | 2/2 通过 |
| S0-006 集成 | `npm run test:integration` | 2/2 通过；首导航/默认数据根/重开纳入观察 |
| 源码 `npm start` 冒烟 | 受控主机直接启动 Electron，**显式临时** `LEDGERX_DATA_DIR`，等待 8 秒后读取脱敏主进程日志 | Java readiness、`renderer.loaded`、正常退出；通过，但非默认启动证据 |

Electron 与 Playwright 命令在受控主机权限下运行；首次 Electron 缓存/嵌套权限失败未计入通过，受控提升权限重跑通过。嵌套工具沙箱无法创建 Windows restricted token 的限制见 [ADR-007](../decisions/ADR-007-electron-sandbox-runtime.md)。测试没有使用 `--no-sandbox`。

## 验收覆盖

- 首次启动：transport readiness、已认证 status、`READY`、当前 ledger V004 的 `schemaVersion=4`、默认 `activeProfileId` UUID、`dataRevision=0` 和 Vue“本地服务已连接”。
- 安全边界：renderer 中 `require/process/ipcRenderer/token` 不可访问；外部无 token 返回 401；错误 Origin 返回 403；Vue bundle、主进程日志和 status 响应不含 token、Bearer、隔离绝对路径或账本内容。
- 网络边界：renderer 请求全部为同一 loopback origin；无公网请求。
- 生命周期：第二实例退出且不产生第二个 Java；10 次 renderer refresh 保持同一 backend origin/PID；kill Java 后显示本地错误页，点击“重试连接”创建新 PID/session；Electron 重启后 profile UUID 保持不变、`dataRevision` 仍为 0；旧 session token 对新端口返回 401。
- 退出：正常关闭后本次 Java 进程退出；异常终止 Electron 父进程后 Java parent monitor 回收子进程。
- 持久化：隔离目录存在 `profiles.db`，首启/重启由 Java persistence tests 验证 profile、schema history、外键和 checksum 不重复；真实 Electron status 验证相同 profile/revision。

## 本次修复

- Electron retry 销毁错误页时抑制 `window-all-closed` 提前退出，确保可以重新创建窗口和 Java 会话。
- 错误页在点击时动态读取 preload bridge，避免加载时序导致 retry/open-folder 按钮失效。
- 修复 Electron 测试 runner 在 Windows 下传递绝对路径导致 Playwright `No tests found` 的问题。
- 修复源码工作区 `npm start` 仍只查找打包资源的问题：检测到 `target/classes`、`target/cp.txt` 和 `frontend/dist` 时使用源码开发回退；正式包路径不变。
- BUG-001 已修复默认数据根：无 `LEDGERX_TEST_*`、即使继承通用 `LEDGERX_DATA_DIR` 时，Electron 仍把 `%LOCALAPPDATA%\LedgerX` 的同一绝对路径传给 Java；BUG-004 默认启动/重开和隔离 SQLite 回归通过。BUG-002 已修复 STARTING/RECOVERY_REQUIRED 的 renderer 语义，浏览器合同回归 `11/11` 通过。BUG-003 已收紧 Vite `/api/v1` loopback 代理，配置回归 `7/7`、前端回归 `11/11` 通过。整体 S0 在用户授权的 Node 24 环境 PASS，Node 22 基线仍待复测。

## 未包含与后续风险

- BUG-004 最终重验已通过用户授权的 Node 24.19.0 环境；Node 22.18.0 基线仍未复测，详见 [BUG-004 实际验收报告](../tasks/BUG-004-s0-startup-reacceptance.md#8-实际验收报告)。
- 正式安装包、签名、自动更新、桌面快捷方式不属于 S0-006，未执行。
- Forge 6.4.2 的 18 high / 1 critical 仍受 ADR-006 临时开发豁免约束，正式发布前必须复审或修复。
- 当前机器 Node 为 24.19.0，项目记录的构建基线为 Node 22.18.0；Electron/Java/SQLite 集成已在本机验证，但如需声明 Node 22 基线支持，仍需单独复测。
- 验收后发现机器上有测试前已存在且未归属的 Java 进程；未擅自终止它们。BUG-004 已验证本次启动记录中的 Java PID 在正常关闭、重试和异常父进程场景退出，但不宣称机器范围内没有其他 Java 进程。
- 源码启动回退依赖先完成 Java 和 Vue 构建；缺少这些产物时仍会显示启动失败页，不会自动下载或生成构建产物。
