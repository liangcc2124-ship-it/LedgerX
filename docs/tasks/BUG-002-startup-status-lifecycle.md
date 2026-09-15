# BUG-002：Vue 把 `STARTING` 和恢复状态误显示为“已连接”

- 状态：已实施；BUG-002 验收通过，当前 Node 24 授权环境整体 S0 已由 BUG-004 重验通过
- 分类：前端状态机、合同校验和无障碍反馈
- 优先级：阻塞 S0 基座验收

## 1. 已确认诊断

### 稳定复现与链路

让 `/api/v1/system/status` 返回现有共享 `status-starting.json`：

`200 status STARTING` → `getSystemStatus()` 只检查 `applicationVersion` 与字符串 `state` →
`App.vue/loadStatus()` 无条件写入 `view.kind='ready'` → DOM 显示“本地服务已连接 · 状态 STARTING”。

`RECOVERY_REQUIRED` 会走同一错误路径，因此同样可显示成功。当前 Electron main 合法地允许三个状态并加载 Vue；它不是此 bug 的根因。没有写 API、Java 业务或 SQLite，本 bug 的影响在 renderer 展示层。

### 症状、直接原因与根因

| 层次 | 已确认事实 |
| --- | --- |
| 用户可见症状 | 初始化未完成或数据库需要恢复时，用户看到绿色“本地服务已连接”，可能误以为可以操作账本。 |
| 直接原因 | `frontend/src/App.vue` 将每个成功 HTTP response 都映射为 `ready`；现有 Playwright 成功用例甚至断言 `STARTING` 与“已连接”同时可见。 |
| 根因 | S0-003 只实现 HTTP 可达性，未落实全局 status 的状态语义、有限等待和恢复视图。 |
| 持久化影响 | 无直接写入；风险是未来业务入口在错误状态下被启用。 |

## 2. 目标、范围与非目标

目标：Vue 只在完整的 `READY` projection 时显示已连接；`STARTING` 有界轮询并禁用业务，`RECOVERY_REQUIRED` 显示恢复指引，结构/状态非法时显示可重试错误。

范围：`system/status` client 校验、status 首屏状态机、样式/可访问性、共享状态 fixtures、浏览器回归和对应模块文档。

非目标：修改 Java status 产生时机、REST 字段/错误码、Electron preload 名称或能力、创建实际恢复 API、自动修复数据、业务页面/路由/状态库、数据库修改。

## 3. 不可改变的接口契约

接口仍是 `GET /api/v1/system/status`，相对 `/api/v1`、`Accept: application/json`、每次请求新的 UUID v4 `X-Request-Id`，认证仍只由 Electron/Vite 代理注入。Vue 不读取 token、端口、路径或数据库。

在不改变 API 的前提下，前端须按下表消费现有字段：

| 状态 | 所需字段 | 前端状态与可见文案 | 自动行为 |
| --- | --- | --- | --- |
| `STARTING` | 有效 `apiVersion` major 1、非空 `applicationVersion`、`capabilities` 含 `system.status`；`schemaVersion`、`activeProfileId`、`meta.dataRevision` 为 `null` | `BOOTSTRAPPING`；显示“正在初始化本地数据…”，不显示“本地服务已连接”，不显示业务入口 | 从首次 `STARTING` response 起最多 15 秒；每次完成后 500 ms 发起下一次 GET，任何时刻最多一个 in-flight 请求。 |
| `READY` | 上述公共字段，且 `schemaVersion` 为正整数、`activeProfileId` 为小写 UUID、`meta.dataRevision` 为非负安全整数 | `READY`；唯一允许显示“本地服务已连接” | 停止 timer/abort 旧请求，不再轮询。 |
| `RECOVERY_REQUIRED` | 与 `STARTING` 相同的 nullable 持久化字段 | `RECOVERY`；显示“本地数据需要恢复”，绝不显示成功或业务入口 | 不自动重试/重启。仅允许用户“刷新状态”；若现有安全 bridge 存在，可显示“打开日志目录”“打开数据目录”。 |

未知枚举、缺失上述必须字段、READY 的 nullable/格式错误或错误 API major 均为 `INVALID_RESPONSE`；进入可重试错误页，不能猜测为 `READY`。401 仍显示会话无效且不自动重试；网络/5xx/无效响应显示现有可重试错误。15 秒仍为 `STARTING` 时停止轮询，显示初始化超时和“重试连接”。该按钮重新开始一轮 status 检查，**不**调用 `desktop.retryBackend()`、不生成业务写入。

`window.desktop` 是已存在的最小 bridge 名称；使用前必须逐项检查方法存在。文件夹 bridge 返回失败时只显示通用失败反馈，不能显示绝对路径或 token。恢复页不得伪造“修复”“继续使用”或修改数据的动作。

## 4. 允许修改与依赖

依赖：BUG-001 的默认启动修复应先合入；本任务的前端开发可与 BUG-003 并行，但二者不得同时编辑 `frontend/package.json` 或同一文档。

允许/预计修改：

- `frontend/src/App.vue`、`frontend/src/apiClient.js`、`frontend/src/styles.css`、`frontend/tests/status.spec.js`；
- 新增 `docs/contracts/api-v1/system/status-ready.json` 与 `status-recovery-required.json`，内容必须是上表已有 DTO 的示例，不能发明字段；
- `docs/modules/ui-integration.md`、`docs/tasks/S0-003-vue-frontend-foundation.md`、本任务实际报告。

引用：[requirements §6.1/§7](../requirements.md)、[architecture §5.2/§10](../architecture.md)、[api §1/§2/§3/§10/§13](../api.md)、[ui-integration §3/§4/§7](../modules/ui-integration.md)、[desktop-shell §3/§5](../modules/desktop-shell.md)、[S0-003](./S0-003-vue-frontend-foundation.md)。

不得修改 `electron/**`、`src/main/java/**`、`pom.xml`、SQLite/DDL、全局 API 文档或旧前端。

## 5. 实施步骤

1. 在 API client 中把 status 的公共字段和上述状态专属字段校验为一个明确边界；无效 response 抛出已有 `ApiClientError`，`code='INVALID_RESPONSE'`，且不把原始 body 记录/展示。
2. 在 App 的挂载、手动重试和卸载之间实现单一启动检查生命周期。用一次性 `setTimeout` 在前一次响应完成后安排轮询；通过 `AbortController`/代次标识清理过期请求和 timer，避免卸载后更新 DOM、重复请求或并发轮询。
3. 将模板明确分为 loading、initializing、ready、recovery、auth-error、retryable error。所有状态区保持 `aria-live="polite"`；按钮有可见焦点、可键盘操作，恢复/错误文本可在 320px 和 200% 缩放下换行且不水平溢出。
4. 新增 READY 和 RECOVERY shared fixtures，并让浏览器 mock 按真实 HTTP status、headers 和 body 返回它们。不要把 mock 固定成成功。
5. 将 S0-003 历史报告中的“STARTING 显示已连接”标为被 BUG-002 取代的错误验收，而不是保留为可接受行为。

## 6. 验收场景

| 输入/操作 | 必须结果 |
| --- | --- |
| 第一个 response 为 READY fixture | 仅显示“本地服务已连接”；状态详情为 READY；没有轮询残留。 |
| STARTING 后下一次 response 为 READY | 初始化期间不显示成功；约 500 ms 后再次请求；READY 后停止并显示成功。 |
| 连续 STARTING 超过 15 秒 | 无并发请求、无无限 loading；进入可重试初始化超时页，且“已连接”始终不存在。 |
| RECOVERY_REQUIRED | 显示恢复状态和手动刷新；不自动请求循环、不显示业务/成功。普通浏览器无 bridge 时不抛错；有 bridge 且调用失败只显示通用反馈。 |
| 401 | 只显示“桌面会话无效”，没有自动/手动普通重试。 |
| 503、网络失败、坏 JSON、未知 state、READY 缺 profile/revision 或错误 major | 显示可重试错误；不把任何状态当成功。 |
| 用户在 STARTING 期间点击重试或页面卸载 | 旧 timer/request 被取消或结果被忽略；最终只由最新一轮请求更新页面。 |
| 320px、设置 200% zoom 后 | 状态文字和可见按钮无横向溢出，Tab 可到达当前可用操作。 |

## 7. 测试要求

- 扩展 `frontend/tests/status.spec.js`：READY、STARTING→READY、STARTING 15 秒 deadline、recovery、401、503/网络/坏 JSON/非法 status、最新请求胜出，以及 token 不在 renderer request header。
- 对连续 STARTING 响应加入延迟并断言最大 in-flight 数为 1；deadline 是行为断言，不能仅测试常量存在。
- 将 200% 缩放后的 `scrollWidth <= clientWidth` 断言放在 zoom **之后**，并验证键盘焦点。
- 运行 `npm test`；报告浏览器 mock 覆盖与真实 Electron 覆盖分开。BUG-004 才负责最终真实 Electron 默认启动重验。

## 8. 风险、禁止与升级

- 不得用 `STARTING`/恢复状态放宽为 ready，缩短/删除 15 秒上限，添加无限重试，或通过 mock 固定 `READY`。
- 不得把 token 放入 Vue env、DOM、日志、localStorage，或通过 renderer 调用原始 IPC。
- 若 Java 需要新增 status 枚举、字段、恢复操作，或正常受支持启动实测稳定超过 15 秒，停止并升级高级模型；这会改变全局 API/启动性能决策。
- 若为了测试必须引入状态管理/组件测试框架，先停止并说明现有 Playwright + Vue 能力为何不足；不自行扩依赖。

## 9. 完成报告与文档同步

报告分别给出 browser mock、真实 Java（如有）和真实 Electron（BUG-004）的证据；列出状态转移次数、deadline、无并发轮询、无障碍/缩放验证和未测 bridge 分支。完成后同步本任务列出的模块/历史报告，但不得在 BUG-004 前将整个 S0-006 结论写为 PASS。

## 10. 实际执行报告（2026-09-13）

- 修改：`frontend/src/App.vue`、`frontend/src/apiClient.js`、`frontend/src/styles.css`、`frontend/tests/status.spec.js`；新增 `status-ready.json` 和 `status-recovery-required.json`；同步 `ui-integration`、S0-003 历史报告和本任务证据。
- 修复前证据：共享 `STARTING` fixture 的 HTTP 200 被渲染为“本地服务已连接 · 状态 STARTING”，旧 Playwright 虽为 6/6，但断言接受了该错误行为。
- 修复后：`npm test` 构建通过，Playwright 11/11；覆盖 READY、STARTING→READY、15 秒 deadline、最大并发 1、RECOVERY、401/503/网络/坏 JSON/非法响应、旧请求竞争、320px、Chromium 页面缩放和键盘焦点。
- Browser 插件在当前环境不可用，使用仓库既有 Playwright preview 流程；本任务未修改 Java/Electron，因此未宣称真实 Electron 证据，BUG-004 负责最终原生链路。
- 未修改 API/数据库/架构；未引入依赖。Node 本机为 24.19.0，Node 22.18.0 基线仍待单独复测。
