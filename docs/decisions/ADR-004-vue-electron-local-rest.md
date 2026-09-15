# ADR-004：Vue/Electron + 本机 REST 的前后端分离

- 状态：已接受（Forge 版本约束由 [ADR-006](./ADR-006-electron-forge-6-4-2-audit.md) 部分取代）
- 日期：2026-09-12
- 替代：[ADR-002](./ADR-002-preserve-react-with-javafx-webview.md)

## 背景

用户要求前后端分离开发，前端使用 HTML、CSS、JavaScript 和 Vue，并允许 Electron。原 JavaFX WebView + 进程内 bridge 方案会让前端调试依赖 JavaFX、浏览器能力受 WebKit 版本限制，也不符合新的 Vue 技术选择。

LedgerX 仍是本地、离线、单用户产品。前后端分离不能被解释为云部署、微服务或允许远程访问财务数据。

## 决策

1. 使用 Vue 3 + Vite + JavaScript 重写/迁移可见界面，不使用 React、TypeScript、Nuxt 或 SSR 作为目标基线。
2. 使用 Electron 作为 Windows 桌面壳；main 管理窗口、单实例和 Java 子进程，renderer 只运行 Vue。
3. Java 11 后端通过仅绑定 `127.0.0.1` 的 `/api/v1` REST API 提供业务能力；使用 JDK `HttpServer` 和 Jackson 的小型适配层，不加入 Spring。
4. 开发时 Vite 代理 `/api`；生产时 Java 同源提供 Vue 静态资源，避免 CORS。
5. Electron main 为每次会话生成高熵 token，通过子进程环境传给 Java，并仅为精确 API 请求注入 Bearer header；renderer 不接触 token。
6. Electron BrowserWindow 使用 context isolation、sandbox、关闭 Node integration、CSP、导航/窗口/权限默认拒绝。preload 只暴露逐项白名单能力。
7. Java 是 SQLite 唯一写者；Electron 和 Vue 不访问数据库，也不复制财务规则。
8. 使用 Electron Forge 作为打包工具；Vue 3.5.42、Vite 8.2.2、Electron 44.3.0、Forge 6.4.2 和 Playwright 1.63.0 使用精确 lockfile。开发和验收直接使用用户当前本机 Node 24.19.0，不再以 Node 22 复测为门禁。Forge 的已知审计事实与个人分享取舍见 [ADR-006](./ADR-006-electron-forge-6-4-2-audit.md)。
9. Java 成功绑定并启动 loopback `HttpServer` 后立即、仅一次输出并 flush `LEDGERX_READY`，不等待首个请求。该行只承担随机端口发现和传输协议版本协商；Electron 随后必须通过已认证 `GET /api/v1/system/status` 判断应用是 `STARTING`、`READY` 还是恢复状态。

## 备选方案

- **继续 React + JavaFX WebView**：代码迁移少，但违反用户指定的 Vue/分离方向，且需承担 JavaFX WebKit 兼容验证。
- **Vue + 系统浏览器 + Java**：分离更彻底、包体小，但失去桌面窗口、单实例、文件和统一退出体验；本机 token 交付也更困难。
- **Vue + Electron + Spring Boot**：生态丰富，但 Java 11 只能停留在旧主线，当前有限路由不值得引入容器和自动配置。
- **Vue + Electron 中直接访问 SQLite**：少一个 Java API 层，但会绕开 Java 11 重构目标，破坏事务与领域单一事实源。
- **Tauri**：包体较小，但引入 Rust 工具链，用户已允许 Electron且项目需要 Java 后端；没有足够收益。

## 后果

- 好处：Vue 和 Java 可独立开发/测试；API 可用普通 HTTP 工具验证；Chromium 行为与 H5 更一致；桌面壳可替换而领域/数据库不变。
- 成本：安装包更大；Electron、Node/Chromium 和 Java runtime 都需要打包与安全更新；增加端口认证和子进程失败处理。
- 安全风险：renderer XSS 可借已注入 header 操作本地 API，因此必须保持无远程内容、严格 CSP、输入校验和最小 preload；token 只防其他本机进程/页面直接调用，不替代前端安全。
- 可靠性风险：Electron 与 Java 生命周期可能分离；必须验证启动超时、崩溃、刷新、优雅退出和孤儿进程。
- 启动语义：transport readiness 与 application readiness 分离；前者不能被 UI 当作数据库可写证据，后者失败时必须进入初始化/恢复/错误流程。
- 回退：Vue 只依赖 REST，未来可换壳；Java application/domain 不依赖 HTTP，可换 HTTP 适配器或升级 JDK。

## 官方依据

- [Vue Quick Start](https://vuejs.org/guide/quick-start)：无 SSR 时可直接使用 Vite。
- [Electron Process Model](https://www.electronjs.org/docs/latest/tutorial/process-model)：main、renderer、preload 的职责边界。
- [Electron Security](https://www.electronjs.org/docs/latest/tutorial/security)：隔离、sandbox、CSP、导航/窗口/IPC 安全建议。
- [Electron WebRequest](https://www.electronjs.org/docs/latest/api/web-request)：受限 session 请求头注入能力。
- [Electron Packaging](https://www.electronjs.org/docs/latest/tutorial/application-distribution/)：官方推荐 Electron Forge。
- [Electron release schedule](https://releases.electronjs.org/schedule) 与 [Forge releases](https://github.com/electron/forge/releases)：确认本次稳定线与支持状态。
- [Node child_process](https://nodejs.org/api/child_process.html)：异步启动和监督外部 Java 进程。
- [Java 11 HttpServer](https://docs.oracle.com/en/java/javase/11/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html)：JDK 内置简单 HTTP server、context 与 executor。
