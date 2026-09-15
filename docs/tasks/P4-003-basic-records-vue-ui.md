# P4-003：Vue 基础收支记录页

- 状态：PARTIAL（基础记录页已实现；完整错误/超时/分页合同尚待补齐）
- 单一结果：用户可在 Vue 页面查看账户余额和记录，新增/编辑收入或两类支出，并通过确认移入回收站或恢复，所有结果来自真实 REST 契约。

## 1. 背景、目标、范围与非目标

P4-002 已提供基础 records API。本任务交付用户可操作的“收支记录”页面和导航，不实现后端或桌面文件能力。

范围：RecordsPage、App 导航/首页引导、记录/分类/账户读取、表单、列表筛选、回收站、错误/冲突/超时反馈、余额刷新、320px 布局、浏览器合同测试和文档。

非目标：Java/Electron、图表/dashboard endpoint、资产/指标/应付款、批量/永久删除、导入导出、自动备份、前端余额计算、UI 全面重设计。

## 2. 前置依赖与引用规范

- 前置：P3-004/P3-005、P4-002 PASS。
- 必读：全局 AGENTS；[需求 §6–§7](../requirements.md)；[架构 §3–§6](../architecture.md)；[全局 API §1–§10](../api.md)；[具体 API §2、§5–§12](../api/ledger-records-api.md)；[模块规格](../modules/ledger-records.md)；[ui-integration](../modules/ui-integration.md)；P2-001、P3-004、P4-002。
- 所有请求只通过 `frontend/src/apiClient.js` 的既有公共能力访问相对 `/api/v1`；不得直接 fetch、保存 token/profileId 或访问 IPC/SQLite。

## 3. 允许/预计修改的模块和文件

- 新增 `frontend/src/components/RecordsPage.vue`。
- `frontend/src/App.vue`：只增加 records 导航、挂载、焦点和首页“添加记录”入口；保留 profiles/settings/catalog 行为。
- `frontend/src/styles.css`：仅共享导航/窄屏确需的最小样式；页面样式优先 scoped。
- 新增 `frontend/tests/records-page.spec.js`；必要时增加本页面 fixture/helper。
- `docs/contracts/api-v1/records/`：只读取 P4-002 fixture，发现缺失时升级，不自行改后端契约。
- 本任务、`docs/modules/ui-integration.md`、`docs/verification/P4-003-basic-records-ui.md`。

禁止修改 Java、electron、apiClient 公共语义、package/vite 配置、ProfilesPage/CatalogPage/旧前端。

## 4. 实现步骤与前端/接口契约

1. READY 后导航增加“收支记录”，activePage 合法值加入 records；进入后焦点移到 `records-page-title`。首页主行动按钮也进入 records，但不改变系统状态页面。
2. RecordsPage 挂载时并发读取：`GET /categories?limit=200`、`GET /accounts?limit=200`、`GET /records?status=ACTIVE&limit=50`。三块独立显示 loading/error/retry；任一失败不伪造空数据。每次 mutation 成功后重新读取 records 与 accounts，余额只展示服务端字符串。
3. 顶部账户摘要按 accounts API 顺序显示 ACTIVE 账户 name、balance、currency、balanceSide；不在 JS 中相加。没有账户时显示“请先在分类与账户中新增账户”并提供导航提示，禁用新增记录。
4. 新增/编辑表单字段固定为 recordType、amount、occurredOn、categoryId、settlement.accountId、settlement.settlementOn、note。mode 固定提交 `PAID_FROM_ACCOUNT`，currency 固定 `CNY`；页面不显示高级字段。
5. 日期初值使用浏览器本地年月日组件形成 YYYY-MM-DD，禁止 `toISOString()` 造成时区偏移。新增时 occurredOn 与 settlementOn 均预填本地今天；编辑使用服务端原值。
6. 类型选择只含“收入/固定支出/弹性支出”。分类候选必须 ACTIVE、canUseForRecords=true，并满足 custom recordTypes=[] 或 system recordTypes 包含当前类型；账户候选只含 ACTIVE。切换类型导致当前分类不适用时清空 categoryId 并提示重新选择。
7. amount 用文本/inputmode decimal，预校验正十进制最多两位；绝不转 Number 或格式化后提交。note 可空，最大 4000，保留字符。前端校验只帮助反馈，服务端 fieldErrors 是最终结果。
8. Create 使用 body `{id,...RecordDraft}`，id 与 Idempotency-Key 分别生成；成功显示“记录已保存”，关闭/重置表单并刷新。Edit 使用完整 RecordDraft、最新 If-Match 和新 key；成功显示“记录已更新”。
9. ACTIVE 列表按服务端顺序显示日期、类型、金额、分类、账户、结算日、note；每项提供编辑和删除。删除必须先进入明确确认，取消不发请求；确认用该行 ETag/key。
10. 页面提供“当前记录/回收站”切换。TRASHED 列表不显示编辑/删除，只提供恢复；恢复确认后 POST body `{}`、If-Match/key，成功刷新记录和账户。
11. 400 fieldErrors 原路径映射控件；409/428 显示“记录已更新，请重新加载”且不覆盖草稿；423 禁用 mutation；401提示重启；超时显示“结果待确认”，保留完全相同 method/path/body/If-Match/key，提供相同请求重试和 operation 查询。
12. 列表翻页只使用 nextCursor；改变 status/filter/profile 后清 cursor。页面卸载/重复读取取消旧请求，旧响应不得覆盖新状态。

## 5. 用户操作、前端反馈、跨层数据与结果

| 用户操作 | 前端反馈 | 跨层数据 | 业务/持久化结果 | 边界 |
| --- | --- | --- | --- | --- |
| 打开页面 | 余额卡、记录或空态 | 3 个 GET | 无写入 | 部分失败、无账户/分类 |
| 新增收入/支出 | 成功提示、列表/余额刷新 | 闭合 Draft、key | record 保存一次 | 金额/日期/归档引用 |
| 编辑 | 草稿保留或成功刷新 | PUT、If-Match、key | revision+1 | stale ETag、超时 |
| 删除 | 确认后移到回收站、余额刷新 | DELETE、If-Match、key | deletedAt 写入 | 取消不请求、重复状态 |
| 恢复 | 回到当前记录、余额刷新 | POST `{}`、If-Match、key | deletedAt 清除 | 归档历史引用允许 |

## 6. 验收标准

- 浏览器中可从导航/首页进入收支记录，320px 和常规宽度可完成新增、编辑、删除确认、回收站和恢复；主要按钮/弹窗不被裁切。
- 所有请求 method/path/body/header 与具体 API 完全一致；没有 asset/metric/allocation/payable/bulk/purge 字段或入口。
- amount 在输入、请求、响应展示中保持字符串精度；日期无 UTC 偏移；类型切换正确过滤/清除分类。
- mutation 成功后 records/accounts 均从服务端重读，测试证明 UI 没有自行加减余额。
- 空数据、无可选分类/账户、400 fieldErrors、401、409/428、423、超时待确认、operation 查询、分页、取消删除、恢复归档引用和 profile 变化均有最终页面状态断言。
- frontend `npm test` 通过并报告数量；没有 Java/Electron/发布包改动，不把 mock 称为真实持久化。

## 7. 测试层次、命令与真实链路

- 浏览器合同：fixture 驱动 mock，检查完整请求和最终用户反馈，不只检查元素存在。
- 视觉/交互：Playwright 在 320px 与常规桌面尺寸覆盖表单、长 note、空态、错误摘要、确认和滚动。
- 必跑：`frontend` 目录使用当前本机 Node 执行 `npm test`，报告实际 Node 版本和通过数量。
- 真实 Java/Electron/SQLite 由 P4-005 负责；隔离数据、发布包和快捷方式本任务不适用。

## 8. 禁止事项、升级、文档与完成报告

禁止直接 fetch/IPC/SQLite、前端算余额、提交未定义字段、用新 key 自动重试、取消时发 mutation、用 UTC ISO 截日期、修改 apiClient 契约或顺手改其他页面。

若 P4-002 fixture/真实 API 不一致、apiClient 无法表达所需 header/operation 查询、App 与 P3-004 合并后导航冲突、或需要改全局 API/数据库/架构，停止并升级。

完成报告写：修改文件；五类用户操作及反馈；请求字段核对；浏览器/构建命令和测试数；320px 证据；mock 与真实链路区分；Java/Electron/发布包/快捷方式不适用；未测项、限制、风险和文档同步。
