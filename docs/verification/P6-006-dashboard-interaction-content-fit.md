# P6-006 Dashboard 布局与期间导航验证记录

- 日期：2026-09-22
- 目标：验证布局 capability、save/cancel/reset 语义、GridStack 内容自适应和日/周/月/年期间导航。

## 实现结果

Dashboard 写入口由 `dashboard.layout.write` capability 控制。非编辑网格启用 GridStack `sizeToContent` 并通过公开 `getGridItems()`/`resizeToContent()` 让中文标题、说明、状态和隐藏提示参与高度；编辑 draft 使用持久化 `h`，键盘调整和鼠标事件仍只改变 draft。保存和恢复默认走统一 mutation 状态，取消不产生请求。

## 测试证据

在 `frontend` 目录执行：

- `npm run build`：通过。
- `node node_modules/@playwright/test/cli.js test tests/dashboard-metrics.spec.js`：4/4 通过，覆盖日视图与布局保存、递归指标回归、无写 capability 隐藏写入口、上一期/下一期使用服务端 period anchor。
- `npm test`：配置测试 7/7、composable 单元测试 3/3、Playwright 全量 43/43 通过。

浏览器测试使用真实 Vite preview 服务器和 API fixture；未把 fixture 当成 Java/SQLite 持久化证据。1024/1440 的长内容由 GridStack 公共内容尺寸 API 和 `overflow: visible` 保障，本任务未引入移动端一列适配或新布局 schema。
