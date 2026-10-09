# P5-007 Vue 财务总览验证记录

状态：PARTIAL。

已实现并验证：桌面端日/周/月/年选择；Dashboard 单一聚合接口读取；服务端 decimal/status 展示；显式编辑模式；`DashboardGrid.vue` 独占 `gridstack/dist/vue` 和 `gridstack/dist/gridstack.css`，使用官方 Vue 包装层管理鼠标拖动、缩放与碰撞；非编辑模式禁用移动/缩放；键盘移动、宽高调整、Esc 取消；If-Match + Idempotency-Key 保存、取消、重置；隐藏卡片不读取数值字段；长中文文本使用可换行布局。

执行命令：

```text
cd frontend
npm run build
npm test
npm exec --yes playwright test tests/dashboard-metrics.spec.js --reporter=line
```

结果：生产构建通过；Vue 浏览器全量测试 38/38 通过；P5 dashboard/metrics 浏览器测试 2/2 通过（另一次修正后重跑 2/2）。截图已在 `frontend/test-results/dashboard-overview.png` 进行视觉检查。

通过 npm 官方源和独立项目缓存执行 `npm audit --audit-level=high --json`：0 个漏洞（high/critical 均为 0）。`gridstack@13.3.0` 已精确写入 manifest 与 lockfile，包使用 MIT 许可且无运行时依赖。

限制：真实 Java/SQLite/性能验收属于 P5-008，尚未执行。鼠标拖拽/缩放已由正式 GridStack 组件实现，但当前 Playwright 合同回归以键盘等价操作和保存请求为主，尚未加入稳定的真实拖拽/缩放断言。

### P7-004 集成回归补充（2026-09-23）

本机浏览器真实启动首次打开总览时发现 GridStack 在 Vue Teleport 卡片内容挂载前测量高度，控制台报 `resizeToContent ... firstElementChild is null`。修复为关闭 GridStack 初始化期自动测量，在浏览器渲染帧后对已挂载卡片显式测量；新增控制台回归断言。`npm test` 全量 44/44 通过；Dashboard/metrics 定向 4/4 通过。P5-007 仍为 PARTIAL，待真实拖拽/缩放和 P5-008 性能/完整浏览器验收。
