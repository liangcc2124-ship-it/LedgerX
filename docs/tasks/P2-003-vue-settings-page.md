# P2-003：Vue 设置页

- 状态：PASS（Node 24；Node 22 不再作为本机交付门槛）；依赖 P2-001
- 单一结果：用户能读取并并发安全地保存 active profile 的既有设置，页面不会把“设置值”误表现为已执行备份、通知或主题文件操作。

## 1. 背景、目标、范围与非目标

P1-003 已实现并验证 `GET/PATCH /settings`。本任务只完成 Vue 表单、错误处理与真实 HTTP 重读证据。

范围：读取 settings、编辑 API 明确可写字段、差异 PATCH、ETag/幂等/超时/冲突处理、CNY/只读字段展示。非目标：执行自动备份、投递 Windows 通知、CSS import/export、切换 profile、修改全局隐私渲染、Java API/SQLite/DDL、Electron 打包。

## 2. 前置依赖与引用规范

- 前置：P2-001、P1-003 均 PASS。
- 必读：[settings API](../api/settings-api.md) 全文、[settings 模块](../modules/settings-preferences.md)、[全局 API](../api.md) §5、§8、§10、[UI 模块](../modules/ui-integration.md) §3–§4、P2-001。
- 只使用 P2-001 的 `requestApiJson`、`createRequestId`、`ApiClientError`；不得新增 fetch 封装或客户端金额计算。

## 3. 允许/预计修改的文件

- `frontend/src/components/SettingsPage.vue`
- 新增 `frontend/tests/settings-page.spec.js`
- 新增 `frontend/tests/settings-real-http.mjs`（隔离数据目录下的 Java + Vite 真实链路）
- `frontend/tests/application-shell.spec.js`（为设置页导航补齐 settings GET 的既有壳测试 mock）
- 本 Task Spec、新增 `docs/verification/P2-003-vue-settings-page.md`

不得修改 `App.vue`、`apiClient.js`、`styles.css`、共享 settings fixtures、Java/Electron、API/module/database 文档、依赖清单。样式只能放在组件 scoped style；测试可读取既有 settings fixtures，不得重写其字段语义。

## 4. 实现步骤与必须遵守的契约

1. 页面激活时 `GET /api/v1/settings`，保存**响应头** ETag 和完整只读基准 DTO；GET 15 秒超时。初始 loading、成功、GET error 必须区分；GET 失败可以重试，不能以本地默认值伪造服务端设置。
2. 表单严格映射：`notificationsEnabled`、`autoBackupEnabled`、`autoBackupIntervalDays`（1–365）、`autoBackupRetentionCount`（1–100）、`lastSettingsSection`、`safetyBuffer.amount`、`hideAllAmounts`、`themeName`。`currency.code/symbol`、`lastAutoBackupAt`、`revision`、`updatedAt` 是只读显示，不能出现在 PATCH body。
3. 金额输入始终是 string；只做格式提示，不转 JavaScript Number。提交前仅接受 API 要求的非负 CNY、最多两位小数；`safetyBuffer` PATCH 值精确为 `{amount:<string>,currency:"CNY"}`。页面不得用 `CUSTOM` 作可提交主题选项；若服务端读到 `CUSTOM`，显示只读说明“自定义主题文件管理尚未交付”，其他字段仍可保存且 PATCH 不包含 `themeName`。
4. 点击保存时从初始/最后成功 DTO 计算**仅有变更的**可写字段；无差异禁用保存并显示“没有需要保存的更改”。mutation 生成新 key，使用保存的 ETag 作 `If-Match` 调用 `PATCH /api/v1/settings`。成功只信任 response DTO 与 response ETag，更新基准/表单并显示“设置已保存”；不能用本地值拼装 revision。
5. 400 的 `fieldErrors` 就地呈现并聚焦错误摘要；428/409 REVISION_CONFLICT 显示“设置已被其他操作更新”和“重新加载”，不自动重放 PATCH；401 显示需重启；423 显示恢复状态并禁用表单；其余错误可重试且保留表单值。
6. 写超时为 30 秒。超时时保留原 PATCH body、ETag 和 idempotency key，显示“保存结果待确认”；提供 `GET /api/v1/operations/{key}` 查询或用完全相同请求重试。仅 `COMPLETED` 后重读 GET 并显示保存成功；404 不得被解释为未提交。用户修改任一字段后的下一次保存必须使用新 key 与当前 ETag。
7. 自动备份/通知控件必须标注“保存策略，不会在本页面立即执行”；不得假装创建 backup、调用 OS 通知或访问文件。页面不把设置、金额或草稿写入 Web Storage。

## 5. 用户操作、跨层字段、结果与边界

| 用户操作 | 前端反馈 | 跨层字段 | 业务/持久化结果 | 边界 |
| --- | --- | --- | --- | --- |
| 打开设置 | loading 后显示 active profile DTO | GET、ETag、dataRevision | 只读 | GET 失败不伪造 defaults |
| 改安全缓冲为 `3000.01` 并保存 | 成功提示，读取到新 revision | PATCH `safetyBuffer`、`If-Match`、key | 单事务写 setting/revision/operation | `-1`、`1.234`、非 CNY 均不提交或显示字段错误 |
| 改多项设置 | 一次保存 | 仅变化字段 | 一个 settings/data revision 增量 | no-op 不发 PATCH |
| 并发修改/超时 | 冲突刷新或待确认查询 | ETag/key/operation | 不覆盖他人写，不重复写 | 404 operation 不等于失败 |
| 设置通知/备份策略 | 明确说明仅配置 | boolean/int/enum | 仅 SQLite setting 值 | 不发通知、不写 backup 文件 |

## 6. 验收标准

- 请求和响应字段与 `docs/api/settings-api.md` 完全一致；测试断言 PATCH 不含 `currency,lastAutoBackupAt,revision,updatedAt`，不发送 Authorization/Origin。
- 成功 GET/PATCH 使用 fixture 的精确 amount string、enum、ETag 与 dataRevision；成功后重新 GET 显示服务端值。
- no-op 禁用保存且零 PATCH；multi-field patch 只发送变更字段；`CUSTOM` 主题绝不作为 mutation 值。
- 400 field error、401、423、428、409、503、网络/timeout、operation 404/COMPLETED 均有可见结果，且冲突/超时绝不显示假成功或改写基准。
- 320px、200% 缩放、键盘依次可访问各输入、保存、刷新/查询；错误与保存结果可被屏幕阅读器获知。
- `npm run build` 与 `npm test` 通过；mock 和真实 HTTP 证据分开报告。

## 7. 测试层次、命令与真实链路

- 必跑：在 `frontend/` 执行 `npm run build`、`npm test`；Playwright mock 覆盖字段、错误、冲突和待确认路径。
- 真实 Java HTTP：**是，必须一次**。在隔离数据根中，经 Vite 精确 loopback proxy 执行“GET 默认设置 → PATCH 两个字段 → 页面重载 GET”，确认响应与重读完全一致；不能由 mock 或直接 SQL 代替。记录代理 token 未进入 renderer 的证据。
- Electron、发布包、桌面快捷方式：不适用；P2-005 覆盖 Electron 真实闭环。

## 8. 禁止事项、升级、文档与完成报告

禁止实现备份/通知/CSS 文件、改币种、保存 CUSTOM themeName、使用 number 计算金额、自动重试换 key、修改 API/DDL/公共 client，或把浏览器 mock 称持久化验证。若 settings DTO/API 缺字段、需要全局 privacy mask、真实代理无法安全注入 token，或服务端重读不能匹配成功响应，停止并升级。

完成报告必须写明：文件；mock 与真实 Java HTTP 各自测试数量；隔离数据根与重读结果；Electron/发布包/快捷方式为何不适用；未测试项、残余风险和页面没有执行备份/通知/CSS 文件的证据。只追加本任务验证记录和本 Task Spec 的实施报告；不得修改 `frontend/README.md`、`docs/tasks/README.md`、`App.vue`、`apiClient.js` 或共享 CSS。

## 9. 实施报告（2026-09-14）

- 修改：`frontend/src/components/SettingsPage.vue`、`frontend/tests/settings-page.spec.js`、`frontend/tests/settings-real-http.mjs`、`frontend/tests/application-shell.spec.js`、本验证记录。
- 结果：字段与 API 一致，金额始终为字符串；只读字段未进入 PATCH；CUSTOM themeName 只读；PATCH 使用响应 DTO/ETag 更新基准；无备份、通知、CSS 文件或 Web Storage 副作用。
- 验证：Vite 配置 7/7、API client 3/3、全量 Playwright 36/36、真实 Java HTTP + Vite 代理 1/1；构建通过。真实链路使用隔离临时目录并已清理。
- 不适用：Electron、发布包、签名和快捷方式由 P2-005 验证；Node 22 不作为本机交付门槛。
- 限制：Java Maven 重建受本机缓存/权限影响，本任务未修改 Java，真实流程使用 P1-003 已验证的既有 Java 构建产物。
