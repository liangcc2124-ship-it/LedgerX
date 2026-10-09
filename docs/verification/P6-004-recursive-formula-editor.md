# P6-004 递归中文公式编辑器验证记录

- 日期：2026-09-22
- 目标：验证指标详情能恢复递归 AST，使用中文控件编辑引用/函数，独立浏览公式版本，并保持 validate/preview/save 的请求契约。

## 实现结果

公式编辑状态只保存 `formula.ast.root` external AST。`FormulaNodeEditor` 递归渲染节点，`ReferencePicker` 显示 ACTIVE 指标、收入/支出分类、账户及固定时间变量，`FormulaVersionHistory` 使用服务端游标加载更早版本。复杂度上限在客户端提供即时反馈，服务端仍是最终校验者。校验和预览请求互相取消上一轮同类请求，服务端 `fieldErrors` 映射到 AST path 并聚焦首个错误。

## 测试证据

在 `frontend` 目录执行：

- `npm run build`：通过。
- `npm test`：配置测试 7/7、composable 单元测试 3/3、Playwright 39/39 通过。
- Playwright 指标场景覆盖：默认 AST 创建不发送 `formula.tokens`；中文函数提示；递归 `ADD → SAFE_DIVIDE → REF` 详情重开；历史版本首屏与 `nextCursor` 加载更早版本；AppDialog 的焦点、Escape、inert 与滚动锁回归。

浏览器测试使用真实 Vite preview 服务器和路由级 API fixture；P6-001 已提供 Java/SQLite formula create→stop/restart→detail/dashboard round-trip 证据，本任务未新增 Java 或数据库代码，因此未重复运行同一原生链路。未覆盖的后续范围是 Records 分页一致性、Dashboard 内容自适应及最终 Electron 闭环。
