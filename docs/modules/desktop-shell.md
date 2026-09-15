# desktop-shell 模块规格（Electron）

- 状态：可实施基础设施部分；发布版本/签名仍待发布任务决定
- 引用：[需求](../requirements.md)、[架构 §5/§8](../architecture.md)、[全局 API](../api.md)、[ADR-004](../decisions/ADR-004-vue-electron-local-rest.md)、[ADR-006](../decisions/ADR-006-electron-forge-6-4-2-audit.md)

## 1. 职责、术语、用例与非目标

职责：Electron main 管理单实例、窗口、安全 session、Java 后端启动/监督/退出、最小错误页和白名单 OS 能力；preload 只提供一方法一能力的安全桥。

术语：`main`（可信 Node 上下文）、`renderer`（不可信 Vue 页面）、`preload`（隔离边界）、`backend session`（一次 Electron 启动对应的 Java 进程/端口/token）、`transport readiness`（Java stdout 的一次监听端口记录）、`application status`（已认证 API 返回的 `STARTING`/`READY`/恢复状态）、`recovery window`（后端不可用时的静态最小页面）。

用例：正常启动、第二实例激活原窗口、renderer 刷新、后端启动失败、后端运行时崩溃、用户重试、打开固定数据/日志目录、退出并回收子进程。

非目标：财务业务、REST DTO 转换、SQLite、自动更新、远程页面、浏览器扩展、通用 shell/文件系统桥、PDF、把 token 暴露给 Vue。

## 2. 入口、输出与依赖

| 项 | 约束 |
| --- | --- |
| 入口 | OS 启动、second-instance、窗口事件、Java stdout/stderr/exit、preload 白名单调用 |
| 输出 | 安全 BrowserWindow、后端进程状态、注入认证的 API 请求、可操作错误页、本地结构化日志 |
| 依赖方 | 最终用户、Vue renderer、发布打包 |
| 被依赖方 | Electron API、Node `child_process.spawn`、打包的 Java 可执行入口 |
| 方向 | `Electron → Java 启动协议`；`renderer → preload allowlist`；无业务反向依赖 |

## 3. 状态、不变量与权限

状态：

```text
CREATED → ACQUIRING_SINGLE_INSTANCE → STARTING_BACKEND → WAITING_LISTENER
                                                    ├─ FAILED_STARTUP
                                                    └→ CHECKING_STATUS
                                                       ├─ FAILED_STARTUP
                                                       └→ LOADING_UI → UI_ACTIVE
UI_ACTIVE ──backend exit──────────────────────────────> BACKEND_LOST
FAILED_STARTUP/BACKEND_LOST ─user retry→ STARTING_BACKEND
任意非终态 ─app quit────────> STOPPING_BACKEND → EXITED
```

不变量：

- 同一应用会话最多一个受管理 Java 子进程；PID/ChildProcess 句柄来自本次 `spawn`。
- token 精确使用 32 个随机字节并编码为无填充 base64url（43 个 `[A-Za-z0-9_-]` 字符），不写命令行、URL、renderer、日志或磁盘。
- transport readiness 只接受一行固定前缀、JSON object、loopback 端口 1–65535、声明协议 major `1`；其余 stdout 是日志。它不代表数据库或业务已就绪。
- 默认启动超时 15 秒；只能由实现配置缩短/延长并在发布验证记录，不可无限等待。
- transport readiness 后必须由 main 完成一次已认证 status 握手，成功后才加载 Vue 窗口；`STARTING` 只允许初始化视图，`READY` 才允许业务操作，恢复状态只允许恢复视图。失败页不能访问 API token 或 Node 通用能力。
- `nodeIntegration=false`、`contextIsolation=true`、`sandbox=true`；禁用 remote module，权限默认拒绝。
- navigation 只允许本次精确 loopback origin；新窗口默认拒绝。用户明确打开文档链接时仅允许 `https` allowlist 并交给系统浏览器。
- API header 注入只匹配本窗口 partition、本次 origin、路径 `/api/v1/*` 和 `xhr/fetch`；不能为任意 URL 注入。

权限：renderer 唯一可选 preload 能力为 `desktop.openDataFolder()`、`desktop.openLogsFolder()`、`desktop.retryBackend()`。每个 IPC channel 固定参数/返回值并校验 sender；不得暴露 `send(channel, ...)`。

## 4. 正常、异常和补偿

- 正常：获取单实例锁→生成 token→spawn Java（`shell:false`,`windowsHide:true`）→消费 stdout/stderr→验证 transport readiness→注册 session 过滤器→main 使用 token 和新 UUID v4 `X-Request-Id` 调用 status→按状态加载同源 UI。
- second instance：不启动第二 Java；恢复/聚焦现有窗口。
- renderer 刷新：保留 backend session；不得新建 Java、token 或端口。
- Java 在 transport readiness 前退出/超时：终止本次子进程，清理 header hook，进入 `FAILED_STARTUP`。
- status 握手超时、认证失败、版本不兼容或结构非法：终止本次子进程，清理 header hook，进入 `FAILED_STARTUP`；不得因已收到 transport readiness 而继续。
- Java 在应用 UI 激活后退出：UI 进入断开态；main 显示恢复动作。不得自动重放任何 mutation。
- 用户重试：确认旧进程已退出后创建全新 token/session；旧端口和旧 token 永久失效。
- app quit：先向后端受控 shutdown endpoint/标准输入发优雅停止，最多等待 5 秒；之后只终止本次句柄对应的进程。不得按 `java.exe` 名称批量结束。
- main 异常：OS 可能遗留子进程，Java 必须监测 parent PID/控制通道并在父进程消失后自行退出；这是发布验收项。

## 5. 启动协议与接口映射

Electron 每次启动 Java 均须解析并传入 `LEDGERX_SESSION_TOKEN`、`LEDGERX_PARENT_PID`、`LEDGERX_DATA_DIR` 和日志级别；正常 Windows 会话的 data dir 固定为 `%LOCALAPPDATA%\LedgerX`，仅隔离测试可通过 `LEDGERX_TEST_DATA_DIR` 覆盖。继承的普通 `LEDGERX_DATA_DIR` 不得决定 Electron 会话的数据根，且不得记录整个环境、token 或绝对路径。

transport readiness 示例：

```text
LEDGERX_READY {"port":49152,"protocol":"1"}
```

Java 在成功启动监听器后立即输出并 flush 该行，每个进程一次，不等待 HTTP 请求。Electron 不能仅凭该行显示业务已就绪；必须先完成已认证 `GET /api/v1/system/status`。后续业务全部走 [全局 REST API](../api.md)。preload 返回 `{ok:true}` 或 `{ok:false,error:{code,message}}`，不得复用业务 API envelope 或泄漏绝对路径。

## 6. 可观测与验收

事件：`desktop.start`、`backend.spawned`、`backend.listening`、`backend.status_checked`、`backend.start_failed`、`backend.exited`、`renderer.loaded`、`backend.retry_requested`、`desktop.stopping`。记录 PID、端口、应用状态、阶段耗时和退出码；不记录 token/环境/body。

验收：

- 正常启动一次只产生一个 Java，status 成功后进入 Vue 首屏。
- 第二实例只激活原窗口；刷新 10 次仍为同一 Java PID。
- transport readiness 畸形/超时/端口非 loopback，或 status 握手超时、认证失败、API major 错误，均进入可操作错误页。
- 在 renderer 控制台无法访问 `require`、`process`、token、ipcRenderer 或文件系统。
- 远程导航、新窗口、摄像头/麦克风/通知等未授权权限被拒绝。
- 杀死 Java 后写表单不显示伪成功；用户重试恢复新会话。
- 正常退出和强制关闭后无本次 Java 孤儿进程。

建议测试：main 状态机单元、启动协议/URL matcher 单元、Electron 集成、真实 Java handshake、最终打包进程清理冒烟。

## 7. 待决/升级

- 嵌套工具沙箱可能导致 Electron 44.3.0 renderer 沙箱 `launch-failed/49`（无法创建 Windows restricted token）；实现保留 `sandbox:true` 并仅启用硬件加速/GPU 回退。默认沙箱 E2E 已在受控主机权限下通过，诊断性 `--no-sandbox` 不计入验收，详见 [ADR-007](../decisions/ADR-007-electron-sandbox-runtime.md)。

- Electron/Forge/Node 精确版本、Windows 安装器和签名在实施/发布任务中基于当日官方支持决定并固定；当前 Forge 6.4.2 的开发期安全豁免和到期复审遵循 ADR-006，不能据此放宽发布门禁。
- 如果安全 session API 无法保证 token 仅注入目标请求，停止实现并升级；不得把 token 放入 Vue 环境变量替代。
- 如果 Java 父进程消失检测在 Windows 不可靠，升级评估 Job Object 或受控 IPC；不得用全局杀进程补偿。
- 需要任意文件路径、远程 URL、自动更新、系统通知或协议唤起时，先形成单独安全设计。
