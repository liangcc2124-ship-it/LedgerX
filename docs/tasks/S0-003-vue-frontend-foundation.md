# S0-003：Vue 3/JavaScript 前端基座

## 1. 背景、目标、范围、非目标

单一结果：在新 `frontend/` 建立可独立开发/构建/测试的 Vue 3 JavaScript SPA，能通过合同 mock 或 Vite proxy 调用 `GET /api/v1/system/status` 并展示加载、就绪和错误状态。

范围：HTML、CSS、Vue app、单一 API client、status 页面、mock fixtures、响应式/可访问基线。非目标：迁移业务页面、修改旧 `web/` React、Electron preload、持久化、状态/路由/UI 通用框架。

## 2. 前置与引用

- 可与 S0-001 并行；接真实 Java 验证时依赖 [S0-002](./S0-002-local-rest-contract.md)。
- 必读：[requirements §3/§7.1](../requirements.md)、[architecture §3/§5.1](../architecture.md)、[api](../api.md)、[ui-integration](../modules/ui-integration.md)、S0-002 status DTO。
- 固定基线：Node `22.18.0`、Vue `3.5.42`、Vite `8.2.2`、`@vitejs/plugin-vue 6.0.8`、Playwright `1.63.0`；精确锁入 `package-lock.json`，不得写 `latest`/范围。若 peer/engine 不兼容，停止升级。

## 3. 允许修改

- 新目录 `frontend/**`；
- `.gitignore` 中仅新增 `frontend/node_modules`, `frontend/dist`, 测试产物规则（避免重复已有规则）；
- 本 Task Spec 的验证证据。

禁止修改 `web/**`、`native/**`、Java、Electron、全局规范和旧 v3.1 页面。

## 4. 实现契约

1. 使用 Vue SFC 和 `<script setup>` 的纯 JavaScript；文件扩展 `.js/.vue`，不得新增 `.ts/.tsx`、TypeScript 或 JSX plugin。
2. 只有生产 dependency `vue`；dev dependencies 仅 Vite、官方 Vue plugin、Playwright（以及它们锁定传递依赖）。不加 router/Pinia/UI 库/Axios。
3. `apiClient.js` 只用相对 `/api/v1` 和 `fetch`；生成 UUID v4 `X-Request-Id`，解析全局成功/错误；不得读取 token/port/Electron IPC。
4. Vite proxy 从 Node-only `LEDGERX_DEV_API_ORIGIN` 和 `LEDGERX_DEV_API_TOKEN` 读取开发值，并在代理层注入 Authorization；变量不得以 `VITE_` 开头、不得传进 `define` 或客户端 bundle。
5. status 页面只有：加载；成功显示“本地服务已连接”和非敏感版本/state；401 显示“桌面会话无效”；网络/5xx 显示可重试错误。不能把 HTTP 非 2xx 当成功。
6. contract mock 读取/镜像 `docs/contracts/api-v1/system` 的相同 status/error 语义；不能固定所有请求成功。
7. CSP 通过生产 `index.html` 声明 self-only script/connect/style，开发所需放宽仅在 dev 配置；不使用 CDN/远程字体/inline executable script。
8. CSS 提供 320px、200%缩放、键盘可见焦点和 `aria-live` 状态；不复制全部旧设计系统。

## 5. 用户操作与结果

| 操作 | 前端反馈 | 跨层字段 | 业务 | 持久化 | 边界 |
| --- | --- | --- | --- | --- | --- |
| 打开页面 | 加载后已连接 | X-Request-Id/status DTO | 无业务 | 无 | 慢响应不白屏 |
| 服务错误 | 明确错误+重试 | status/error code | 无 | 无 | 401、503、坏 JSON、断网 |
| 窄屏/键盘 | 内容可读、焦点可见 | 无 | 无 | 无 | 320px、200% |

## 6. 验收

- `npm ci`、`npm run build`、`npm test` 使用 `frontend/` 独立完成。
- package/lock 中只有批准直接依赖及精确版本；无 React/TypeScript/Axios/router/Pinia。
- mock 覆盖 200、401、503、malformed JSON、网络失败；错误文案和重试行为可见。
- 接 S0-002 真实 Java 时 status 成功；发送的 URL/header/字段正确，浏览器代码不含 token。
- build 后扫描 `dist` 不含开发 token、绝对开发 API origin、远程 URL或 source map（生产默认不生成）。
- 320px 与 200%缩放无横向关键内容丢失；键盘能触发重试，状态由屏幕阅读器获知。

## 7. 测试与真实链路

Playwright 用 fixture mock 覆盖 5 类响应；S0-002 完成后至少一次接真实 Java loopback（不需 Electron）。业务/SQLite/发布包不适用。报告必须分开写 mock 场景数和真实 HTTP 场景数。

## 8. 禁止、升级与报告

禁止改旧 React、迁移业务页面、把 token 放 `VITE_*`/localStorage、添加未来库、隐藏 HTTP 错误或把 mock 称原生链路。若固定版本 engine/peer 不兼容，或 CSP 与 Vite production 无法兼容，停止并给出官方证据/候选版本。

完成报告：文件、直接/传递依赖、mock/真实 HTTP 数量、页面状态、320px/200%证据、bundle secret 扫描、未测项。

## 9. 实际完成报告（2026-09-13）

- 文件：新增 `frontend/` Vue 3/JavaScript SPA、API client、状态页、响应式样式、Vite 配置、Playwright 合同测试、独立测试启动脚本、README 和精确 `package-lock.json`；`.gitignore` 仅补充前端产物规则。未修改 `web/`、`native/`、Java、Electron 或全局规范。
- 直接依赖：生产 `vue@3.5.42`；开发 `vite@8.2.2`、`@vitejs/plugin-vue@6.0.8`、`@playwright/test@1.63.0`。传递依赖均由锁文件固定；未引入 React、TypeScript、Axios、router、Pinia 或 UI 库。
- `npm ci`：通过（38 packages added，0 vulnerabilities）。当前机器 Node 为 `24.19.0`，因此 npm 对任务要求的 Node `22.18.0` 输出 engine warning；未声称已在 Node 22 上验证。
- `npm run build`：通过；生产 CSP 为 self-only，未生成 source map。
- `npm test`：当时通过，Playwright 合同场景 `6/6`；覆盖共享 200 fixture、401、503 重试恢复、坏 JSON、网络失败，以及 320px/200% 缩放和键盘重试。
- 真实 HTTP：当时通过 `1/1` 浏览器链路，浏览器 → Vite 开发代理 → 隔离启动的 Java loopback `GET /api/v1/system/status`；页面显示“本地服务已连接”、`0.1.0-SNAPSHOT` 和 `STARTING`，代理注入的会话 token 未进入前端代码。
- **验收更正（2026-09-13）**：上一条把 `STARTING` 与“本地服务已连接”同时出现当作成功，是与全局启动状态语义冲突的错误验收；该结论已由 [BUG-002](./BUG-002-startup-status-lifecycle.md) 撤回。其测试数量仅保留为历史执行记录，不能作为当前 UI/安全验收通过的证据。
- **BUG-002 修复报告（2026-09-13）**：`apiClient.js` 现在校验 status major、公共字段、状态枚举和 READY/非 READY 专属字段；`App.vue` 实现 `STARTING` 有界顺序轮询、`READY` 成功、`RECOVERY_REQUIRED` 恢复视图、401 会话错误、非法响应/网络错误和旧请求取消；新增 READY/RECOVERY fixtures 与 11 个 Playwright 场景。当前 `npm test` 为 11/11，旧的“STARTING 显示已连接”行为不再接受。
- 可观测 UI：加载、成功、认证失败和可重试错误均有明确状态；状态区域使用 `aria-live`，按钮有键盘焦点样式。
- 产物扫描：`frontend/dist` 未发现 `LEDGERX_DEV_API`、`LEDGERX_DEV_API_TOKEN`、`Authorization`、`Bearer`、开发 loopback 地址或 `sourceMappingURL`。
- 未测/不适用：未做 Electron、SQLite、业务流程、发布 EXE、桌面快捷方式和 Node 22 专项验证；这些属于后续任务或当前任务明确非目标。
