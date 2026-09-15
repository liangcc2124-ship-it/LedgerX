# P2-001：Vue 应用壳、导航与通用 REST 请求能力

- 状态：PASS（当前本机 Node 24.19.0 环境；Node 22 不作为交付门槛）
- 单一结果：在不改变既有启动状态语义的前提下，Vue 应用在 `READY` 时提供可访问的首页、空间和设置导航，并提供后续页面共用的相对 REST 请求封装。

## 1. 背景、目标、范围与非目标

P1 已交付 profiles 和 settings 的真实 Java API，但现有 Vue 页面只有启动状态。此任务只建立业务页面共用的外壳，令后续两个页面能够各自独立实现而不同时修改 `App.vue` 或 `apiClient.js`。

范围：保留并重构已有 `STARTING/READY/RECOVERY_REQUIRED` 展示；在 READY 后显示“首页 / 用户空间 / 设置”导航；新增两个无业务请求的页面骨架；在唯一 API client 中增加通用 JSON request 函数。非目标：调用 profiles/settings 业务 endpoint、修改 Java/Electron、引入 Vue Router/Pinia/Axios/组件库、持久化前端路由或草稿、迁移分类/记录页面、发布包。

## 2. 前置依赖与引用规范

- 前置：S0-003、BUG-002、BUG-003、P1-002、P1-003 均为 PASS。
- 必读：[全局 API](../api.md) §3、§5–§10、[UI 模块](../modules/ui-integration.md) §2–§4、[profiles API](../api/profiles-api.md)、[settings API](../api/settings-api.md)、[S0-003](./S0-003-vue-frontend-foundation.md)。
- P2-002 和 P2-003 都依赖本任务；它们不得再修改本任务规定的壳或通用请求接口。

## 3. 允许/预计修改的文件

- `frontend/src/App.vue`
- `frontend/src/apiClient.js`
- `frontend/src/components/ProfilesPage.vue`（仅静态骨架）
- `frontend/src/components/SettingsPage.vue`（仅静态骨架）
- `frontend/src/styles.css`（仅壳的共享样式）
- `frontend/tests/status.spec.js`、新增 `frontend/tests/application-shell.spec.js`
- `frontend/README.md`、本 Task Spec、新增 `docs/verification/P2-001-vue-application-shell.md`

不得修改 `web/**`、`native/**`、`electron/**`、Java、全局 API、任何具体 API 文档、`package.json`/lockfile，或添加依赖。后续页面必须用 SFC 的 scoped style，避免与此任务争用 `styles.css`。

## 4. 实现步骤与必须遵守的契约

1. 保留当前启动状态机：只有 `READY` 可进入业务导航；`STARTING` 仍以 500 ms 顺序轮询、15 秒截止；`RECOVERY_REQUIRED` 只允许手动刷新；401、未知 state、错误 API major、缺失必需字段均不可显示业务内容。现有 status 回归不得被删弱。
2. 在 `READY` 中提供无需第三方 router 的内存页签：`home`、`profiles`、`settings`。使用语义化 `<nav aria-label="主导航">` 和按钮；当前页使用 `aria-current="page"`。刷新默认回到首页，不能把页面选择写入 localStorage 或 URL hash。首页继续显示当前 application version、state，及可用时 active profile ID（不得显示 token/路径）。
3. `ProfilesPage.vue` 和 `SettingsPage.vue` 只渲染各自标题、“正在接入本地服务功能”的静态提示和可访问说明；不得在本任务请求 `/profiles` 或 `/settings`。`App.vue` 要预留 `profile-activation-complete` 事件监听：收到后重新执行 status 读取、使旧状态失效；骨架无需发出该事件。
4. 在 `apiClient.js` 导出 `requestApiJson(path, options)`。它只接受以 `/api/v1/` 开头的相对 path，options 为 `{method, body, ifMatch, idempotencyKey, signal, fetchImpl}`；method 默认 `GET`。每次调用生成新的 `X-Request-Id`，总是发送 `Accept: application/json`；有 body 时才发送 `Content-Type: application/json` 和 JSON body；仅调用方提供时发送 `If-Match`/`Idempotency-Key`。绝不设置/读取 `Authorization`、`Origin`、token、端口或 Electron IPC。
5. `requestApiJson` 成功时只接受 JSON object envelope，返回 `{payload, etag, location, requestId}`；HTTP 非 2xx、坏 JSON、网络/中止以扩展的 `ApiClientError` 表示。错误对象必须保留 `status`、`code`、`retryable`、`requestId`、object 形态的 `fieldErrors` 和 `details`；不得把 HTTP 错误伪装成成功。它不自动重试 mutation，也不擅自为 `body` 填 `{}`。
6. 既有 `getSystemStatus` 仍使用同一基础错误语义并继续进行 status DTO 的严格校验。不得放宽 UUID v4、API major 或 READY 专属字段校验。

## 5. 用户操作、跨层字段、结果与边界

| 用户操作 | 前端反馈 | 跨层数据 | 业务/持久化结果 | 边界 |
| --- | --- | --- | --- | --- |
| 启动，后端返回 READY | 显示首页与三项导航 | 仅 `GET /system/status`、`X-Request-Id` | 无写入 | 不显示 token、路径或业务菜单外的旧内容 |
| 点击“用户空间”或“设置” | 切换到对应静态骨架，焦点落到页面标题 | 无业务 HTTP | 无 | 刷新返回首页，不持久化页签 |
| STARTING/RECOVERY/401/坏响应 | 保持既有初始化、恢复或错误页，业务导航不可达 | status DTO/error envelope | 无 | 不得将 STARTING 当 READY，不能无限 loading |
| 后续页面调用 request 函数 | 请求头、JSON、ETag/Location/error 信息可用 | `X-Request-Id`、可选 `If-Match`/`Idempotency-Key` | 本任务无写入 | 非 `/api/v1/` path、非 object envelope、abort 都有稳定失败语义 |

## 6. 验收标准

- `READY` 时可用键盘从主导航到三个页签；当前页有唯一 `aria-current`，切换后焦点在新页面 `<h1>`，320px 与 200% 缩放没有横向裁掉的导航或焦点。
- STARTING、READY、RECOVERY_REQUIRED、401、503、坏 JSON、网络失败、重试/旧请求取消的既有可观察行为均仍通过；非 READY 时页面没有主业务导航。
- 两个骨架组件在本任务不产生 `/profiles` 或 `/settings` 请求，且提示不宣称功能已完成。
- 通用 client 对 GET、JSON mutation、错误 envelope、错误 JSON、网络失败和 abort 的 header/body/error 字段有自动化断言；浏览器请求中没有 Authorization/Origin。
- `frontend/` 独立 `npm run build`、`npm test` 通过；生产 `dist` 不含 `LEDGERX_DEV_API_TOKEN`、`Bearer`、开发 API origin 或 source map。

## 7. 测试层次、命令与真实链路

- 必跑：在 `frontend/` 执行 `npm run build` 与 `npm test`。Playwright mock 必须覆盖本任务的状态和导航行为；它只证明 renderer 行为。
- 真实 Java HTTP：**否**。本任务没有业务请求，已有 status 的真实链路证据可复用，不重复把 mock 说成真实链路。
- Electron、隔离 SQLite、发布包、桌面快捷方式：不适用。

## 8. 禁止事项、升级、文档与完成报告

禁止引入 router/state 库、修改 Vite token proxy、访问 `window.desktop` 以外的新 bridge、把请求成功写入 localStorage，或借此实现 profiles/settings 业务。若需要 URL 路由、持久化前端状态、增加公共 client header/response 规则，或 status 行为与现有规范不兼容，停止并升级。

完成报告必须写明：实际改动文件；mock 测试数量及 build/test 命令；是否真实 Java HTTP（本任务应为否）；隔离数据、Electron、发布包、快捷方式为何不适用；未测项、残余风险；对 P2-002/P2-003 的实际导出接口是否仍为 `requestApiJson` 与 `profile-activation-complete`。只追加本任务验证记录和本 Task Spec 的实施报告；不得在并行任务期间修改 `docs/tasks/README.md`。

## 9. 实际完成报告（2026-09-14）

- 修改：`frontend/src/App.vue`、`frontend/src/apiClient.js`、`frontend/src/components/ProfilesPage.vue`、`frontend/src/components/SettingsPage.vue`、`frontend/src/styles.css`、`frontend/tests/status.spec.js` 未改动、`frontend/tests/application-shell.spec.js`、`frontend/tests/api-client.test.mjs`；新增验证记录见 [`P2-001 验证记录`](../verification/P2-001-vue-application-shell.md)。未修改 Java、Electron、旧 `web/`/`native/` 或依赖清单。
- 行为：READY 才显示内存导航；STARTING/RECOVERY/401/错误响应语义保持；静态页面骨架不发业务请求；通用 `requestApiJson` 只发送相对 `/api/v1/` 请求并解析 envelope、ETag、Location 和结构化错误，不处理认证 token。
- 验证：API client Node 单测 3/3；Vite 配置测试 7/7；前端构建通过；Playwright 15/15。Playwright 覆盖原有 status 11 项及新增 shell 4 项，含导航、非 READY 禁用、320/375/768/1024/1440 宽度、200% 缩放和键盘焦点。`node --version` 为 `v24.19.0`，npm 为 `11.17.0`；交付以当前本机环境为准。
- 真实链路：本任务无业务请求，未做真实 Java HTTP；仅使用浏览器合同 mock。Electron、隔离 SQLite、发布包和桌面快捷方式不适用。
- 产物：`frontend/dist` 未发现开发 token、Bearer、开发 loopback origin 或 source map。P2-002/P2-003 可使用的导出接口保持为 `requestApiJson`；profile 事件监听已预留。
- 限制：未验证 P2-002/P2-003 业务页面、真实 Java API、Electron sandbox 和正式发布；Forge 审计风险仍按全局任务顺序待后续验证。
