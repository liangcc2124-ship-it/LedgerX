# P5-006：Vue 指标与中文公式管理

> 原文 Electron 相关分工仅为历史背景；当前真实集成与发布验收以 [P5-008 浏览器闭环](./P5-008-metrics-dashboard-browser-e2e-performance.md) 为准。

- 状态：PARTIAL — Vue 指标列表、中文公式提示、结构化创建/编辑、校验/预览、版本浏览、归档和 system visibility 交互已实现；复杂嵌套 AST 可视编辑与完整异常回归待补齐
- 单一结果：桌面用户可从 Vue 管理系统/自定义指标，安全地用中文提示搭建、验证、预览并保存公式，所有状态来自真实 P5 REST 契约。

## 1. 背景、范围与非目标

本任务只做“指标管理”体验，不做可拖拽总览。[Metrics/Formula API](../api/metrics-formulas-api.md) 是请求/响应唯一来源；[模块规格](../modules/metrics-formulas.md) 定义用户可见术语和不支持的能力。

范围：指标列表/详情、创建和完整替换 custom metric、system visibility、archive 确认、中文公式 builder、validate/preview、formula version 浏览、桌面样式和 Playwright 合同测试。非目标：dashboard/GridStack、任何前端公式计算、直接指标赋值、资产/分摊/报表/预警、Java/Electron/包依赖变更。

## 2. 前置与允许文件

- 前置：P5-005、P4-005 PASS；阅读两个 API 文档、P2 application shell、P4 records page 的 request/error/retry 模式。
- 允许：新增 `frontend/src/components/MetricsPage.vue`、`MetricEditorModal.vue`、`FormulaBuilder.vue`、`FormulaVersionHistory.vue` 和仅它们直接依赖的 helper/test fixture；`frontend/src/App.vue` 只增加指标导航/首页进入点；`frontend/src/styles.css` 只作共享桌面可用性小改；`frontend/tests/metrics-*.spec.js`；本 Task、`docs/verification/P5-006-metrics-formula-ui.md`。
- 禁止：Java/electron、`apiClient.js` 公共语义、package/lock、DashboardPage/GridStack、现有非 P5 页面的大重构。

## 3. 实施契约

1. 仅当 status capability 有 `metrics.read` 才显示“指标管理”入口；进入后焦点到 `metrics-page-title`。创建、替换、归档和 system visibility 控件还要求 `metrics.write`；validate/preview 分别要求 `formulas.validate`，保存 custom formula 还要求 `formulas.write`。无 capability/后端未 READY 不伪造页面或数据。
2. 列表请求 `GET /metrics`，用服务端 metadata/visibility/revision 渲染；system metric 只能编辑 `hidden/dashboardEnabled`，custom metric 可打开完整编辑/历史/归档。页面不计算当前指标值。
3. custom editor 的字段、默认值、精度范围和 closed Draft 与 API §4/§5 完全一致。创建由客户端分别生成 metric `id` 和 Idempotency-Key；替换持有最新 ETag，成功后从服务端 detail 重读。
4. FormulaBuilder 通过可组合的中文块生成 AST/tokens：常数、指标、收入/支出分类、账户余额、时间变量、运算和九个函数均展示中文名/简短中文说明。不得接受任意代码、SQL 或自由文本表达式作为执行语义。
5. 编辑过程中调用 validate；仅用户明确点“预览”才调用 preview。validate/preview loading、fieldErrors、`UNAVAILABLE/EMPTY/FUTURE` 和 warning 逐项可见；预览只作提示，保存仍交由服务端完整校验。
6. API 返回的规范 AST 是唯一真值；别名被归一化时更新 builder state，不以 tokens 自行猜测 AST。服务端 field path 聚焦对应节点/字段；409/428 显示重新加载选择并保留未提交草稿；超时显示“结果待确认”且只可用相同 key/request 重试或查询 operation。
7. archive 必须明确说明不可恢复、引用会阻止归档；确认前零 mutation。history 只读，分页使用 server cursor，不暴露/拼接 raw personal record 数据。
8. 面向桌面最低 1024px 与常规宽度，长中文标签、长名称、错误摘要和 formula tree 可换行/滚动而不遮挡保存/取消。移动端不在 P5 验收范围；但不应把固定宽度弹窗渲染到视口外。

## 4. 验收与测试

| 用户操作 | 必须观察到 |
| --- | --- |
| 打开管理页 | capability gating，真实/fixture list、loading/error/empty/retry 独立可见 |
| 新建公式 | 中文模块形成合规 AST/tokens；validate/preview 的请求精确且无本地求值 |
| 保存/编辑 | POST/PUT body、ETag/key 和成功后的 server re-read 正确 |
| 改系统可见性/归档 | 限制和确认正确；stale/conflict 后不丢草稿 |
| 浏览版本 | cursor、历史不可编辑、归档/引用错误可理解 |

- Playwright 使用 API fixtures 验证 request body/header、错误/retry、keyboard focus、1024px 和 1440px 长内容布局；不得只断言元素存在。
- 必跑：`frontend` 目录当前 Node 22.18.0 执行 `npm test`，报告 Node 版本和测试数。真实 Java/Electron/SQLite 由 P5-008 负责。

## 5. 禁止事项、升级与完成报告

禁止 `eval`、直接 fetch/IPC/SQLite、将金额转 Number、保存未定义字段、用新 key 自动重试、把 preview 当保存、将 formula AST 写 localStorage 或自行恢复跨重启草稿。若 API 没有表达所需 editor 状态、App 导航和既有页面冲突、或移动端需求被重新启用，停止并升级。

完成报告写明组件/请求、各中文函数/引用覆盖、浏览器测试数/尺寸、mock 与真实边界、Java/Electron/发布包不适用及文档同步。
