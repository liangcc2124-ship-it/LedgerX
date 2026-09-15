# ui-integration 模块规格（Vue 3）

- 状态：P2 基座已实现；P3/P4 只迁移分类、账户和三类基础记录，高级页面按 ADR-010 延后
- 边界：HTML/CSS/JavaScript/Vue 3 + Vite renderer 和唯一 REST API client
- 引用：[需求](../requirements.md)、[架构](../architecture.md)、[全局 API](../api.md)、[local-api-contract](./local-api-contract.md)、[ADR-004](../decisions/ADR-004-vue-electron-local-rest.md)

## 1. 职责、术语、用例与非目标

职责：交付 Profiles、分类/账户、三类基础 Records 和准确的 Settings 说明；统一相对 URL API client、loading/error/empty/stale 状态、表单校验、分页、可访问反馈和响应式布局。Metrics、Analysis、Warnings、Recovery 等高级页面不属于基础版。

术语：`API client`（唯一 fetch 封装）、`view model`（DTO 的展示投影）、`stale view`（profile/dataRevision 改变后不可提交）、`contract mock`（读取同一 fixtures 的开发替身）、`privacy mask`（视觉隐藏，不是加密）。

非目标：保留 React/TypeScript 目标栈、SSR/Nuxt、复制 Java 财务计算、renderer 直接 Node/Electron/SQLite、巨型全量 snapshot、PDF、用 mock 替代真实 Electron 验收。

## 2. 入口、输出和依赖

| 项 | 说明 |
| --- | --- |
| 入口 | Java 同源或 Vite 加载的 `index.html`、用户操作、REST response、有限 preload API |
| 输出 | API 请求、页面/表单状态、可访问提示、分页游标、用户确认 |
| 依赖方 | 用户、Electron renderer、UI 自动化 |
| 被依赖方 | 浏览器 API、Vue、Vite、全局/具体 REST DTO、白名单 preload |
| 方向 | `Vue → API DTO`；UI 不决定 domain/数据库结构 |

## 3. 数据与业务边界

- 仅使用相对 `/api/v1`；禁止读取端口/token或手工设置 Authorization。
- 每次请求生成 `X-Request-Id`。每次用户 mutation 生成一个 `Idempotency-Key` 并保留到明确成功/冲突/取消；超时重试复用该 key。
- 金额在表单和 API 中保持字符串；允许用格式化器展示，不得用 JavaScript number 得出正式财务结果。
- 页面不使用数据库列名；所有 ID、枚举、空值、日期、分页、revision 以 API 文档为准。
- 更新/删除使用最近读取的 ETag；冲突显示“数据已变化”，提供刷新，不静默覆盖。
- profile 切换后取消/忽略旧 GET、清空 cursor/view cache/草稿；已经发出的写请求通过 operation status 确认，不能假设取消。
- 基础版直接展示服务端金额字符串；金额隐藏偏好暂不作为已交付界面能力。
- 基础版不显示公式、资产、指标、报表或预警入口；以后启用时仍只能显示后端结果。

## 4. 页面状态与失败行为

应用状态：`BOOTSTRAPPING → READY | RECOVERY | DISCONNECTED`，切换时 `READY → SWITCHING_PROFILE → READY`。启动 status 消费固定为：`STARTING → BOOTSTRAPPING`（500 ms 顺序轮询，15 秒截止）、`READY → READY`、`RECOVERY_REQUIRED → RECOVERY`（仅手动刷新）。未知状态、字段缺失或错误 major 进入 `DISCONNECTED`，不能猜测为 READY。每个数据块为 `IDLE/LOADING/LOADED/EMPTY/ERROR/STALE`。

- boot status 失败显示全页错误与“重试连接”；401 显示会话无效且不自动重试，网络/5xx/非法响应可重试；不能无限 loading。
- `RECOVERY_REQUIRED` 显示恢复指引和手动刷新；只有已存在且逐项检查的方法才显示打开日志/数据目录，bridge 失败只显示通用反馈。
- GET 失败保留仍有效的旧显示并标 stale，给出重试；跨 profile 旧数据不得保留。
- mutation 进行中禁用同一提交动作；15/30 秒客户端超时显示“结果待确认”，以同 key 查询 operation。
- 字段错误就地关联控件并聚焦摘要；非字段错误用可访问通知，不丢失安全输入。
- 空记录、无可用分类或无可用账户均提供具体下一步；不能当异常。
- 320px 宽、200% 缩放、低高度弹窗、键盘/焦点、滚动锁定、拖动/缩放布局必须可用。
- Electron 后端丢失时所有写入口禁用；恢复后重新 handshake 和读数据，不自动重放表单。
- profile 创建/切换成功事件触发的 system status 刷新属于后台刷新：保留发起操作的页面挂载并显示成功结果；启动、手动重试和恢复流程仍可使用全页 loading/recovery 状态。

## 5. 旧前端迁移映射

| 旧实现 | 目标 |
| --- | --- |
| React `.tsx`/TypeScript types | Vue SFC `.vue` + JavaScript modules；共享 fixtures 提供运行时合同证据 |
| `bridge.ts` / `chrome.webview` | 单一 `apiClient.js`，只用 fetch 相对 `/api/v1` |
| `getState` 巨型快照 | categories/accounts/records 各自分页 endpoint；基础版不建 dashboard 聚合 |
| C# 命令字符串 | REST method/path；具体映射见 API 文档 |
| 浏览器固定成功 mock | 根据共享 fixtures 返回真实 status/header/body 的 contract mock |
| `exportReportPdf` | 删除入口；首期不提供 |

组件可按用户行为复用旧 HTML/CSS 视觉语言，但不得机械转换 React 内部状态或把 TypeScript 编译产物当 Vue 源码。

## 6. 接口映射与副作用

- Records 使用 [ledger-records REST API](../api/ledger-records-api.md)。其他页面在对应具体 API 文档完成前只能做无业务提交的静态/合同 UI，不得猜 endpoint。
- 只有 API `2xx` 或 operation 查询确认完成才显示保存成功；DOM/localStorage 变化不是持久化证据。
- 基础版没有下载/导入。设置说明可调用既有 `window.desktop.openDataFolder()` 定位目录，但必须提示退出应用后再复制完整目录。
- 主题可保存非敏感显示偏好；账本、金额、token、业务草稿默认不持久化到 Web Storage。

## 7. 可观测、验收和测试

前端日志只记录 route、API 路由模板、request ID、status/error code 和耗时；不记录 token、payload、备注、金额或文件正文。

验收：

- 普通浏览器 + Vite proxy 可独立完成合同 mock/真实 Java 的页面开发。
- 生产代码不存在 React/ReactDOM/TSX 运行依赖，也不读取 `window.chrome.webview`。
- 无授权 token 由 Electron/Vite proxy 处理，Vue bundle/DevTools 中找不到 token。
- 创建、编辑、删除、恢复的字段和返回值与具体 API 完全一致，保存重载后仍可见。
- 写超时复用同一 key，无重复行；revision 冲突不覆盖。
- 100+ 条分页无重复/遗漏，改变过滤清 cursor。
- 320px、200% 缩放、键盘、焦点和错误提示通过。
- 浏览器 mock、真实 Java API、真实 Electron E2E 分别报告，不能混称。

建议测试：纯格式/状态 reducer 单元、Vue 组件、API fixture 合同、Playwright 浏览器、Electron 真实 E2E 和发布包冒烟。

## 8. 待决/升级

- 任何尚无具体 API 文档的页面写操作都必须等待，不可从旧 C# 命令猜 REST 字段。
- 资产、指标、报表、预警、恢复等高级页面按 ADR-010 延后；不得从旧 UI 或保留表猜 endpoint。
- 需要状态库、路由库或组件库时先证明多个页面的真实重复；基础版本优先 Vue 自身能力和现有 CSS。
- 远程内容、任意 preload、浏览器持久化账本或 PDF 会改变安全/范围，必须升级。
