# P7-004：本机网页版入口与会话迁移

- 状态：PASS（实现与验证记录见[验证报告](../verification/P7-004-local-browser-runtime.md)）
- 类型：运行方式变更、HTTP 认证、启动与真实浏览器验收
- 依赖：[ADR-014](../decisions/ADR-014-local-browser-only.md)；先于 P7-002 和 P7-003 本机使用基线
- 影响：Java HTTP/启动入口、Vue API client、浏览器测试、需求/架构/API/运行说明；不修改财务规则或 SQLite schema

## 目标与非目标

用户通过一个受控命令启动本机 Java 服务，在普通浏览器打开 Java 同源提供的 Vue 页面，完成真实记账并在刷新、服务重启后保留数据。生产路径不需要 Electron、Vite 开发代理、Node 运行时或桌面窗口。

本任务不做云部署、账号体系、多人访问、LAN 监听、自动启动服务、原生安装器或数据库迁移。

## 实施合同

1. 提供仓库内受版本控制的 `scripts/start-local.ps1` 作为 Windows 源码运行入口，调用 `HttpServerMain`，不要求手工设置会话令牌。Java 只监听 `127.0.0.1:0`，先完成数据根解析与初始化，再输出可打开的本机 URL；可尝试用系统默认浏览器打开，失败时明确显示 URL。用户在启动终端按 Ctrl+C 停止服务，shutdown hook 释放资源；界面提示“关闭标签页不会停止本机服务”。不得依赖 Electron 父进程 PID 或 stdin 指令维持浏览器模式生命周期。
2. Java 同源提供 Vue dist 与 `/api/v1`。源码启动脚本先执行受锁定依赖的 Vue build；正式 JAR 通过 classpath manifest 提供嵌入式网页资源，不依赖 `LEDGERX_WEB_ROOT` 或开发代理。缺少/损坏资源时稳定报错，不显示空白页面；浏览器刷新和深链仍可加载应用壳。静态文件只允许既有 manifest 白名单和普通文件，不接受路径穿越或 symlink。
3. Java 负责服务进程内的浏览器会话。`GET /api/v1/system/session` 是唯一无需已有会话的 bootstrap：校验精确 `Host`，若请求带 `Origin` 则必须同源；有效 Cookie 可复用，否则生成 256 bit 随机会话标识和独立的 256 bit CSRF 值，设置 `ledgerx_session=<opaque>; HttpOnly; SameSite=Strict; Path=/`（不设置 `Domain`，仅本机 HTTP，不持久化）。浏览器模式响应 `200` JSON `{"data":{"authMode":"browser","csrfToken":"<base64url>"}}`，`Cache-Control: no-store`；不返回会话标识。会话只在当前 Java 进程内有效，服务重启后失效；启动链接、URL、Vue bundle、localStorage 和日志均不含凭据。浏览器使用同源 Cookie，Vue 只在内存中保存 CSRF 值，刷新时重新 bootstrap；请求共用一次在途 bootstrap，不能为每笔写入生成新会话。
4. 除静态资源、`/health/live` 和上述 bootstrap 外，所有财务 API 校验会话与精确 `Host`；mutation 还须同源 `Origin` 和与会话绑定的 `X-LedgerX-CSRF`。无效会话返回 `401 AUTHENTICATION_REQUIRED`，无效来源返回 `403 REQUEST_ORIGIN_FORBIDDEN`，缺失/错误 CSRF 返回 `403 INVALID_CSRF_TOKEN`；均不开放 CORS、不得改变账本。静态资源和探针不返回账本内容。开发代理仍可用于本地开发，但不能成为生产认证的必需环节。
5. 浏览器 API client 对会话失效显示明确恢复动作；用户再次打开本机地址后能重新建立会话。未开始的 GET 可在 bootstrap 后发出；已提交的写请求收到 401/403 或结果未知时不得自动换会话重放，须保留同一幂等键并让用户确认重试/查询。
6. 历史 Electron 启动/测试文件保留供迁移核对；它们不属于本机网页版活动构建、依赖或验收路径。P7-003 负责验证本机网页构建与使用基线。

## 验收矩阵

| 用户操作 | 前端结果 | 跨层数据 | 业务与持久化 | 边界 |
| --- | --- | --- | --- | --- |
| 启动服务并打开浏览器 | 页面和状态加载，无 Vite/Electron | 同源 Cookie、status capability | 打开正确本地 profile | 缺 dist、端口冲突、浏览器未安装/无法自动打开 |
| 新建记录并刷新 | 记录和余额仍正确 | CSRF、Idempotency-Key、request ID | Java/SQLite 只提交一次 | 重复点击、请求超时、刷新 |
| 关闭标签页后重开 | 回到当前数据 | 会话复用或安全重建 | Java PID 和数据不变 | 多标签、过期 Cookie |
| 停止并重启 Java | 新会话恢复页面 | 旧 Cookie 失效 | 数据和 revision 不变 | 非正常退出、未确认写入 |
| 从异站发起请求 | 无账本内容或写入 | 错 Host/Origin/CSRF 拒绝 | 活动数据不变 | DNS rebinding、CORS 预检、无 Origin |

## 验证

- Java HTTP：Cookie 属性、会话过期、Host/Origin/CSRF、无 CORS、错误码和无数据泄漏。
- Vue 浏览器：会话失效提示、刷新/多标签、写操作幂等、键盘/窄屏。
- 真实链路：普通浏览器 → Java 同源静态资源/API → SQLite，新建→重载→编辑→重启→删除/恢复；无 Electron/Vite 代理参与。
- 启动/退出：仅本机监听、数据根隔离、Java 停止后端口关闭且无遗留写者。

实现阶段命令：仓库根 `./mvnw.cmd -q '-Dmaven.compiler.fork=true' test package`；`frontend` 目录 `npm test`；随后在仓库根执行 `node frontend/scripts/local-browser-e2e.mjs`。最后一项必须使用真实 Java、真实 SQLite 隔离目录和 Playwright Chromium，断言 Cookie 属性、浏览器不可读会话、新建记录请求带 CSRF 且无 Authorization、页面刷新和 Java 服务重启后的记录仍在，并检查控制台与页面异常。该脚本只删除自己在系统临时目录创建且名称匹配的测试数据目录。P7-004 完成结果见验证报告；完整 Maven 套件仍受 P7-002 已记录的日期用例失败阻塞，不影响本任务定向 HTTP 验证。

## 禁止事项与升级条件

不得关闭认证、把 Bearer token 放进浏览器、开放局域网/CORS、添加云账号或改财务规则。若需要远程访问或多用户，先升级需求、数据所有权和安全架构，不扩展本任务的本机会话协议。
