# S0-004：Electron 安全桌面壳与 Java 生命周期

## 1. 背景、目标、范围、非目标

单一结果：Electron 能安全启动/监督 S0-002 Java 后端，验证 transport readiness 并完成已认证 status 握手后加载 S0-003 Vue 页面，为精确 API 请求注入会话认证，并在退出时回收 Java。

范围：`electron/` main/preload、状态机、单实例、session、最小错误页、进程/安全测试。非目标：SQLite/业务、正式安装器、自动更新、任意文件/命令桥、业务 API 转发。

## 2. 前置与引用

- 依赖：[S0-002](./S0-002-local-rest-contract.md)、[S0-003](./S0-003-vue-frontend-foundation.md)。
- 必读：[architecture §5/§8](../architecture.md)、[desktop-shell](../modules/desktop-shell.md)、[ADR-004](../decisions/ADR-004-vue-electron-local-rest.md)、[api §3](../api.md)。
- 固定直接版本：Electron `44.3.0`、Electron Forge `6.4.2`（经用户批准并按 [ADR-006](../decisions/ADR-006-electron-forge-6-4-2-audit.md) 临时豁免）；Node 构建基线 `22.18.0`。所有 Forge packages 使用同一精确 `6.4.2`，不使用 Forge 8 alpha。安装后必须运行 audit/许可证检查；若豁免条件失效或影响扩展到运行时，立即停止并升级。
- `electron/package.json` 必须 `private:true`；运行依赖为空，devDependencies 仅 `electron 44.3.0`、`@electron-forge/cli 6.4.2`、`@playwright/test 1.63.0`。S0 不配置 maker/publisher/auto-updater；这些属于 release task。

## 3. 允许修改

- 新目录 `electron/**`；
- Java 中仅 S0-002 已定义的 parent-liveness/优雅 stop 启动适配，以及 [ADR-005](../decisions/ADR-005-controlled-java-web-root.md) 定义的受控 `LEDGERX_WEB_ROOT` 静态资源接线；
- `frontend/` 中仅为桌面错误/重试接线所需的已定义 preload 类型检查（JavaScript）；
- 对应测试、`.gitignore` 和本任务证据。

不得修改旧 `web/native`、业务/数据库、全局 API 或依赖版本之外的前端设计。

## 4. 实现契约

1. main 在 ready 前取得 single instance lock；第二实例只 restore/focus，绝不 spawn Java。
2. token 用 Node crypto 生成 32 random bytes 并编码为无填充 base64url（43 字符）；传入 Java environment，禁止 argv/URL/renderer/disk/log。
3. 使用 `spawn(javaExecutable,args,{shell:false,windowsHide:true,stdio:[...]})`；Java 路径/args 从打包/测试配置的固定结构构造，不拼用户输入。
4. 启动状态和 timeout 逐字遵循 desktop-shell；只接受受限 transport readiness。收到端口后先注册 header 注入，再由 main 使用 session token 和新 UUID v4 `X-Request-Id` 请求已认证 status；status 失败或不兼容不加载 Vue URL。`STARTING` 加载初始化视图且禁用业务，`READY` 才允许业务，恢复状态加载恢复视图。测试成功链路把已构建的 `frontend/dist` 作为 S0-002 的受控静态 web root，使页面与 API 位于同一 Java origin；不得以 Vite proxy 代替 header 注入验证。
5. 使用独立 persistent-or-ephemeral session partition；`webRequest.onBeforeSendHeaders` 只对精确 origin `/api/v1/*` 的 xhr/fetch 注入 Authorization。其他 origin/path/resource 不注入。
6. BrowserWindow：nodeIntegration false、contextIsolation true、sandbox true；devTools 生产 false；permission request/check deny；will-navigate 和 window-open deny，允许的 https 外链需用户手势并用系统浏览器。
7. preload 只暴露 `openDataFolder()`, `openLogsFolder()`, `retryBackend()`；main 验证 sender 为当前主 frame。目录由 main 固定，不接收 path；返回模块规范的小结果。
8. renderer refresh 不重启后端。后端退出进入错误/断开状态；用户 retry 先确认旧句柄结束，再生成新 token。
9. app quit 优雅等待 5 秒后仅终止本次 ChildProcess；Java 根据 parent PID/控制管道自退出。测试绝不按进程名 kill。
10. stdout/stderr 必须持续消费以免 pipe 满；日志过滤 readiness，且所有内容经 token/路径脱敏。
11. 状态机和 URL/path/token 匹配使用 Node 标准库；不得为了这些小功能加入状态库、进程库、日志库或 IPC 框架。

## 5. 用户与跨层结果

| 操作 | 前端 | 跨层 | 业务 | 持久化 | 边界 |
| --- | --- | --- | --- | --- | --- |
| 启动 | Vue 初始化/status 成功 | spawn→transport readiness→auth status | 无业务 | 无 DB | 监听/status timeout、坏行、错 major |
| 刷新 | 页面重载 | 同 PID/port/token | 无 | 无 | 10 次不重复进程 |
| 后端崩溃 | 断开页，可重试 | 新 session 才恢复 | 不重放写 | 无 | 崩溃循环禁止 |
| 第二实例 | 原窗口聚焦 | 无新 Java | 无 | 无 | 最小化窗口恢复 |
| 退出 | 窗口关闭 | Java 优雅/受控结束 | 无 | 无 | 无孤儿进程 |

## 6. 验收

- renderer 中 `require/process/ipcRenderer/token` 不可访问；安全 webPreferences 有自动断言。
- 普通外部 HTTP 客户端无 token访问 status 为 401；Electron Vue 为 200。
- 远程/错误端口/API 以外请求抓包确认无 Authorization。
- 第二实例和 10 次 refresh 均只有一个 Java PID；retry 才创建新 PID/token。
- transport readiness 或 status 握手 timeout/畸形/提前退出显示本地错误页且无业务窗口。
- 后端崩溃后不显示写成功；重试恢复。
- 正常退出、窗口强关、main 异常测试后无本任务 Java 孤儿进程。
- `npm audit` high/critical 为 0；当前非零结果仅可依据 ADR-006 的临时豁免继续，且必须满足其隔离、期限和复审条件；不能用 `--force` 自动改变 major。

## 7. 测试与真实链路

运行 main 状态机/URL matcher 单元、Electron 集成和真实 S0-002 Java handshake。S0-003 Vue 必须在真正 Electron renderer 中加载；模拟 Java可用于故障分支但不替代成功链路。SQLite/业务/正式包/快捷方式不适用。

## 8. 禁止、升级与报告

禁止 utilityProcess 启动 Java、`shell:true`、token query/localStorage、关闭 sandbox、泛化 IPC、自动循环重启或杀所有 java.exe。header 注入/parent cleanup 无法安全满足时停止升级。

完成报告：文件/依赖、真实 PID/端口链路、各状态测试数、安全设置/token 泄漏检查、audit、孤儿进程证据、未测试项。

## 9. 当前执行报告与审计豁免（2026-09-13）

- 已完成前置接线：Java 受控 `LEDGERX_WEB_ROOT` 静态根、Vue dist manifest、`LEDGERX_PARENT_PID` 存活监测和 `LEDGERX_STOP` stdin 优雅停止，记录于 [ADR-005](../decisions/ADR-005-controlled-java-web-root.md)。Java 全量回归仍为 `12/12`。
- 已建立 `electron/package.json` 并生成精确 lockfile；按 ADR-006 豁免完成 `npm ci`，新增 `main.js`、`preload.js`、最小错误页及 Node/Playwright 测试。
- 审计结果：2026-09-13 执行 `npm audit --json --registry=https://registry.npmjs.org`（退出码 1）对固定 `@electron-forge/cli@6.4.2` 仍报告 `18 high / 1 critical`（传递依赖包含 `tar`、`extract-zip` 等）；审计建议的可用修复为 Forge `7.11.2`，属于 major 变更。该结果不代表安全门禁通过，当前开发仅依据 ADR-006 临时豁免继续。
- 维护版本评估：隔离目录的 Forge `7.11.2` + Electron `44.3.0` + Playwright `1.63.0` lockfile 审计为 `3 low / 19 high / 1 critical`（23 total，退出码 1），仍不满足门禁，故未切换版本。
- 用户已批准 ADR-006 临时安全豁免（复审截止 2026-10-13 或首次正式发布前，以较早者为准）；可继续 S0-004 开发，但每次依赖变更仍须复跑 audit/许可证检查。
- 单元/静态配置测试：`npm run test:unit`，8/8 通过，覆盖 token、UUID、readiness、状态合同、header 注入范围、日志脱敏和 BrowserWindow 安全基线。
- 语法检查：`node --check main.js`、`node --check preload.js` 通过；当前验证主机为 Node `v24.19.0`、npm `11.17.0`、PowerShell 7，满足 Electron `44.3.0` 的 Node 引擎下限，但不是项目记录的 Node `22.18.0` 构建基线。
- `npm test` 完整编排现已通过：单元 8/8、默认 `sandbox:true` Electron E2E 2/2；同时修复了测试 runner 在 Windows 下传递绝对路径导致 Playwright 找不到测试的问题。
- Electron 官方 `44.3.0` Windows x64 二进制已从官方 release 下载并按 `electron/node_modules/electron/checksums.json` 校验 SHA-256 后安装到被忽略的 `node_modules/electron/dist`。嵌套工具沙箱内曾因无法创建 Windows restricted token 报 `render-process-gone(reason=launch-failed, exitCode=49)`；在受控主机权限下使用同一默认 `sandbox:true` 参数重新执行 `npm run test:e2e`，2/2 通过，包含真实 Java status 握手、Vue renderer、无 token HTTP 401 和 10 次刷新保持同一 backend origin。
- 诊断性 `--no-sandbox` 运行未计入验收；详见 [ADR-007](../decisions/ADR-007-electron-sandbox-runtime.md)。发布包、快捷方式、第二实例和后端崩溃重试仍属于后续集成/发布任务。
- 受控主机 E2E 完成后检查未发现本任务启动的 `electron`/`java` 孤儿进程；这不替代发布包强关路径验收。
