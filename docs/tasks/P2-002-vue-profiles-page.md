# P2-002：Vue 用户空间管理页

- 状态：PASS（当前本机 Node 24；Node 22 不作为交付门槛）；依赖 P2-001
- 单一结果：用户能在 Vue 页面通过已实现的 profiles REST API 创建、查看、切换和归档本地用户空间，并对超时、冲突与归档边界给出不丢数据的反馈。

## 1. 背景、目标、范围与非目标

Java profiles API 已在 P1-002 以真实 SQLite/HTTP 验证。本任务只实现该 API 的 Vue 页面与浏览器合同验证。

范围：读取分页列表（含归档筛选）、创建 profile、激活 INACTIVE profile、二次确认归档 INACTIVE profile、显示 API 错误/冲突/待确认结果。非目标：profile 重命名、恢复、物理删除、文件/目录展示、旧 JSON 导入、Java API/DDL 修改、Electron 打包、设置页面。

## 2. 前置依赖与引用规范

- 前置：P2-001、P1-002 均 PASS。
- 必读：[profiles API](../api/profiles-api.md) 全文、[profiles 模块](../modules/profiles-catalog.md)、[UI 模块](../modules/ui-integration.md) §3–§4、[全局 API](../api.md) §5、§9–§10、P2-001。
- 只可使用 P2-001 导出的 `requestApiJson`、`createRequestId`、`ApiClientError`；不得自行再封装 fetch 或加 token。

## 3. 允许/预计修改的文件

- `frontend/src/components/ProfilesPage.vue`
- `frontend/src/App.vue`（仅允许将 profile 成功事件触发的状态刷新改为后台刷新，保持当前页挂载）
- 新增 `frontend/tests/profiles-page.spec.js`
- 新增 `frontend/tests/profiles-real-http.mjs`（隔离数据目录下的 Java + Vite 真实链路）
- 新增 `docs/contracts/api-v1/profiles/list-profiles.json`（仅将既有 API 的成功投影固化为共享 fixture）
- 本 Task Spec、新增 `docs/verification/P2-002-vue-profiles-page.md`

不得修改 `apiClient.js`、`styles.css`、Java/Electron、既有 profiles API/module/database 文档或依赖清单。`App.vue` 只能按本任务的后台状态刷新约束修改，不得改变启动、恢复或导航契约。组件样式须写在本 SFC 的 scoped style；测试不得依赖真实用户数据。

## 4. 实现步骤与必须遵守的契约

1. 页面激活时请求 `GET /api/v1/profiles?includeArchived=false&limit=50`。按 API 固定排序渲染 `name`、`status`、`createdAt`、`lastOpenedAt`；`activeProfileId` 决定当前空间，不用列表位置猜测。空 items 在该 API 语义中是错误状态，不显示“可创建的空账本”假象。
2. 提供“显示已归档空间”复选框。切换筛选时取消/忽略旧 GET、清空 cursor 和当前 items，再从第一页读取。若 `page.hasMore=true`，显示“加载更多”并使用同一 `includeArchived` 与 `nextCursor`；不得重复、乱序或在筛选变化后复用 cursor。
3. 创建表单仅有 `name`。提交前 trim，空白名称给出本地必填提示；有效提交生成小写 UUID v4 `id` 与一个新 `Idempotency-Key`，调用 `POST /api/v1/profiles`。成功后使用响应 profile 更新显示、清空表单、宣布成功，并 emit `profile-activation-complete`；不可在 UI 先假定创建成功或手工拼 profile revision。
4. INACTIVE 行只显示“切换到此空间”。使用该行 `revision` 生成 `If-Match: "<revision>"`，body 必须为 `{}`，调用 `POST /api/v1/profiles/{id}/activate`。ACTIVE 不显示切换按钮；ARCHIVED 不显示 mutation。成功后 emit `profile-activation-complete`，清空本页 cursor/cache 并重新读取第一页。
5. INACTIVE 行的“归档”先显示页面内确认区，明确说明“不会删除账本或备份”；仅用户再次点击“确认归档”才以该行 revision、无 body调用 `DELETE /api/v1/profiles/{id}`。取消不发送 HTTP。成功后从当前显示移除（未勾选归档时）或更新为 ARCHIVED（已勾选时）。不得归档 ACTIVE/ARCHIVED 行或在 UI 中杜撰恢复功能。
6. 所有 mutation 在进行中禁用对应动作和同一表单提交。正常 API 400 显示 `fieldErrors.name` 于 name 控件；428/409 REVISION_CONFLICT 显示“数据已变化”及刷新按钮，绝不覆盖；401 显示重新启动提示；423 表示恢复模式并禁用写；其他错误以 `aria-live` 公告且保留安全输入。
7. 读超时为 15 秒、写超时为 30 秒，使用 AbortController；写超时显示“提交结果待确认”。该动作保存原 method/path/body/If-Match/idempotency key，提供“查询结果”调用 `GET /api/v1/operations/{key}`，或“用同一键重试”。仅响应 `data.status="COMPLETED"` 才宣布成功并 reload；404 只表示未找到已提交结果，不能宣称失败。可修改表单后的新提交必须新建 key。
8. 所有输入/输出仅使用 `ProfileSummary` 和 API 规定的 field 名称。不得展示 profile 目录、SQLite 路径、token、raw error details 或 request payload；不使用 Number 改写 revision。

## 5. 用户操作、跨层字段、结果与边界

| 用户操作 | 前端反馈 | 跨层字段 | 业务/持久化结果 | 边界 |
| --- | --- | --- | --- | --- |
| 打开用户空间页 | loading 后列出当前/其他空间 | `includeArchived,limit,cursor` | 只读 catalog | 空项不是正常空态；GET 失败可重试 |
| 创建“家庭账本” | 表单禁用，成功后切到新空间并提示 | `id,name,X-Request-Id,Idempotency-Key` | Java 创建独立 ledger 并把新 profile 设 ACTIVE | 空白/101 字符/重复 ID、超时同 key |
| 切换 INACTIVE | 切换中、成功后显示新 active | path id、`If-Match`, `{}`、key | catalog pointer 与 active context 原子切换 | 过期 revision、坏目标账本、423 |
| 归档 INACTIVE | 两次点击后状态改变 | path id、`If-Match`、key，无 body | 仅 catalog 归档，文件不删除 | ACTIVE/ARCHIVED 不可操作；取消零请求 |
| 翻页或切换归档筛选 | 只显示当前查询的无重复结果 | cursor 绑定 filter/revision | 无写入 | cursor 过期、旧 GET 回来、网络失败 |

## 6. 验收标准

- 共享 list fixture 与 profiles API DTO 字段一致；浏览器测试断言 path/query、没有 renderer 注入的 Authorization，且 mutation 的 Origin（若由浏览器自动发送）与当前 loopback origin 一致；创建 UUID 和每个 mutation 的 idempotency key 格式正确。
- 创建、切换、归档分别断言正确 method/path/body/If-Match、成功文案和后续列表状态；归档的第一击和取消均为零 DELETE。
- 400 name field error、401、423、428、revision conflict、503、网络/超时、operation 404/COMPLETED 和分页 cursor 过期均有可见且可操作的结果；冲突不得覆盖或假成功。
- 切换成功确实 emit 事件，使应用重新读取 status；切换筛选/重新读取不能显示旧 profile 的残留行。
- 320px、200% 缩放、键盘可到达表单/确认/加载更多/刷新，状态和错误由 `aria-live` 宣告。
- `npm run build` 与 `npm test` 通过。新增 mock 仅是浏览器合同证据，不能称真实 Java 或 SQLite 证据。

## 7. 测试层次、命令与真实链路

- 必跑：在 `frontend/` 执行 `npm run build`、`npm test`；新增 Playwright 覆盖上述验收场景。
- 真实 Java HTTP：**是，必须一次**。用新建、可删除的隔离数据根启动现有 Java 与 Vite 开发代理，实际完成“创建 profile → 刷新/重开页面 → 切换回旧 profile”，确认 Java API 返回和页面 active profile 一致。测试数据不得使用 `%LOCALAPPDATA%\\LedgerX` 或真实账本；测试结束后只清理已核对的隔离目录。
- Electron、发布包、桌面快捷方式：不适用；P2-005 才验证完整 Electron 链路。

## 8. 禁止事项、升级、文档与完成报告

禁止改 API/DDL、请求或显示路径、自动重试 mutation 时换 key、把操作超时当作回滚、用 mock 代替真实 HTTP，或给 ARCHIVED 增加恢复按钮。不得让 profile 成功事件再次卸载发起操作的页面。若需要 profile rename/restore/purge、API 不能提供 operation 查询、真实代理暴露 token、或 P2-001 的共用 client 不足以遵守本任务契约，停止并升级。

完成报告必须写明：改动文件；mock 场景数；真实 Java HTTP 的隔离根、操作序列与结果；是否 Electron/发布包/快捷方式（均应说明不适用）；前后端字段核对；未测项及残余风险。只追加本任务验证记录和本 Task Spec 的实施报告；不得修改 `frontend/README.md`、`docs/tasks/README.md`、`apiClient.js` 或共享 CSS。`App.vue` 的改动须限于本任务授权的后台刷新行为。

## 9. 实施报告（2026-09-14）

- 修改：`frontend/src/components/ProfilesPage.vue`、`frontend/src/App.vue`、`frontend/tests/application-shell.spec.js`、`frontend/tests/profiles-page.spec.js`、`frontend/tests/profiles-real-http.mjs`、`docs/contracts/api-v1/profiles/list-profiles.json`、`docs/modules/ui-integration.md`、本验证记录。
- 结果：真实字段核对为 `id/name/status/createdAt/lastOpenedAt/revision/activeProfileId/includeArchived/limit/cursor/If-Match/Idempotency-Key`；mutation 不由 renderer 注入 Authorization，浏览器自动 Origin 与 loopback origin 一致。
- 验证：Vite 配置 7/7、API client 3/3、Playwright 28/28、真实 Java HTTP + Vite 代理 1/1；构建通过。真实链路使用隔离临时目录并已清理。
- 不适用：Electron、发布包、签名和快捷方式由 P2-005 验证；交付不再等待 Node 22 复测。
- 限制：Java Maven 重建因本机 Maven 缓存/权限失败，真实流程使用 P1-002 已验证的既有 `target/classes` 与 `target/cp.txt`；本任务未修改 Java。
