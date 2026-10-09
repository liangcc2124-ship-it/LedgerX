# ADR-014：本机浏览器作为唯一产品入口

- 状态：已接受
- 日期：2026-09-23
- 取代：ADR-004 中的 Electron 桌面壳、会话注入和 Forge 发布决定；ADR-006/007 仅保留历史证据

## 背景

用户决定该项目不再使用 Electron，采用“本机浏览器访问本机服务”的网页版，保留现有本地 SQLite。P7-004 已实现 Java 同源静态资源与浏览器会话，并在隔离数据目录中完成浏览器→Java→SQLite 新建、重载、编辑、服务重启、删除和恢复验证；剩余正式发布工作由 P7-002 与 P7-003 负责。

项目仍是单设备、单操作者、本地优先应用；本决定不引入云托管、在线账号或局域网服务。

## 决定

1. Java 11 模块化单体是唯一业务与数据进程，只绑定数值 `127.0.0.1` 的随机端口；正式启动入口在打开数据库前对数据根取得操作系统级独占文件锁，同一数据根的第二个服务进程会明确拒绝启动。Java 同源提供 Vue 静态文件和 `/api/v1`。Vue 只使用相对 URL，不接触 SQLite、Java 类或操作系统 API。
2. 用户通过受控的 Java 启动命令启动服务，再用系统浏览器打开 Java 输出或主动打开的本机地址。浏览器关闭不代表 Java 已退出；退出服务以启动进程的停止命令为准，并在界面中明确说明。
3. 正式浏览器路径由 Java 管理本次服务进程的短期会话。会话标识放在 `HttpOnly; SameSite=Strict; Path=/` 的 host-only Cookie 中，不放进 URL、localStorage、Vue bundle 或日志。Java 提供同源 bootstrap 响应；写请求额外带 `X-LedgerX-CSRF`，由服务端验证与会话绑定的防伪值。服务端仍校验精确 `Host` 和写请求 `Origin`，拒绝跨源请求与通配 CORS。开发代理只作开发工具，不成为生产认证方案。
4. Java 使用既有数据根和 schema；认证迁移不改财务领域规则或 SQLite 格式。P7-003 已从活动 Java 服务、Vite 代理和构建链移除 Electron Bearer 兼容；归档实现和历史验证只用于迁移审阅，不作为运行路径。
5. 交付路径是 Vue 构建产物 + Java 后端 + 明确的启动说明；不构建 Electron/Forge 包、桌面快捷方式或原生窗口。若以后需要供无 JRE 用户离线分享，再单独决定 Java runtime 的分发形式和许可证，不恢复桌面壳。

## 安全与运行边界

- 本机浏览器会自动携带 Cookie；因此 Cookie 的 SameSite 属性只是其中一层防护，写请求还必须验证同源、CSRF 值和既有幂等/并发约束。静态资源和无敏感信息的存活探针可公开，财务 API 必须验证会话。
- 页面与 API 必须同源，保持严格 CSP、不加载公网脚本或 CDN。不能通过关闭认证、开放 `0.0.0.0`、加入 `Access-Control-Allow-Origin: *` 或在前端保存旧 Bearer token 让页面“能用”。
- 会话生命周期、刷新/多标签、服务重启、Cookie 失效、进程中断、数据根权限与备份恢复都要在普通浏览器中验证。当前 Vite 代理能工作不能替代上述正式链路。

## 取舍与回退

- 去掉 Chromium 桌面壳、进程桥接和 Forge 维护成本；代价是需要完成浏览器会话、服务启动/停止与浏览器错误反馈，且关闭浏览器不会自动结束 Java。
- 如网页版迁移失败，保留当前 Java/SQLite 数据和历史 Electron 代码供诊断，但不得把 Electron 重新作为新任务的默认交付路径；改变产品方向需新的明确决定。

## 依据

- [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)：同源自定义请求头、服务端防伪值和来源校验的组合。
- [MDN Secure Cookie Configuration](https://developer.mozilla.org/en-US/docs/Web/Security/Practical_implementation_guides/Cookies)：`HttpOnly`、`SameSite`、host-only Cookie 的作用与限制。
- [Java 11 HttpServer](https://docs.oracle.com/en/java/javase/11/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html)：现有 JDK HTTP 适配器可继续作为本机单用户服务。
