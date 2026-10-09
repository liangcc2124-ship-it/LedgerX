# P5-007：Vue 财务总览与 GridStack 桌面布局

> 原文 Electron 相关分工仅为历史背景；当前真实集成与发布验收以 [P5-008 浏览器闭环](./P5-008-metrics-dashboard-browser-e2e-performance.md) 为准。

- 状态：PARTIAL — Vue 桌面总览、四级期间、GridStack 官方包装层拖动/缩放、键盘控制和布局保存已实现；真实拖拽/缩放浏览器断言与 P5-008 桌面验收待补齐
- 单一结果：桌面用户可按日/周/月/年读取服务端财务总览，在显式编辑模式通过鼠标或键盘调整卡片位置/尺寸并原子保存、取消或重置共享布局；卡片内容完整自适应。

## 1. 背景、范围与非目标

本任务实现用户最初提出的四层视图和自由布局。依赖选择已由 [ADR-012](../decisions/ADR-012-gridstack-dashboard-layout.md) 固定为 `gridstack@13.3.0` 官方 Vue wrapper；[Dashboard API](../api/dashboard-api.md) 是唯一数据契约。

范围：Overview/Dashboard 页面、period selector、MetricCard 三个展示 tier、仅封装 GridStack 的 `DashboardGrid`、布局草稿/保存/取消/重置、键盘替代控制、CSS、package lock、Playwright 浏览器测试。非目标：新增后端字段/算法、移动端适配、图表库、卡片内直接编辑公式、报表/预警、Electron/Java。

## 2. 前置与允许文件

- 前置：P5-005、P5-006、P4-005 PASS；阅读 ADR-012、Dashboard API、技术设计 §6 与现有 App/API client。
- 允许：新增 `frontend/src/components/OverviewPage.vue`、`DashboardGrid.vue`、`MetricCard.vue` 及直接 tests/helpers；`frontend/src/App.vue` 仅替换当前财务总览 placeholder/首页路由；`frontend/src/styles.css` 必要 shared desktop styles；`frontend/package.json`、lockfile；`frontend/tests/dashboard-*.spec.js`；本 Task、`docs/verification/P5-007-dashboard-ui.md`。
- 禁止：Java、HTTP contract、metrics editor 内部、Electron、其他依赖、全局 UI 重设计、移动端断点承诺。

## 3. 实施契约

1. 加入且只加入精确 `gridstack@13.3.0`，锁文件一致。`DashboardGrid.vue` 是唯一可 import `gridstack/dist/vue` 与 GridStack CSS 的组件；不在 parent/child 多点初始化 grid。
2. 总览仅在 `dashboard.read` capability 存在且 READY 后显示。用户选择 DAY/WEEK/MONTH/YEAR 与 anchor，发出 `GET /dashboard?granularity=...&anchor=...`；加载/失败/空卡/未来期间不伪造数值，切换或 profile 变化取消旧请求。
3. 读取 `period`、`comparisonWindow`、layout 和 cards 渲染。前端绝不计算指标/环比/趋势/金额；金额是服务端 decimal string，显示按 metadata format/precision。隐藏卡只显示 API 允许的“已隐藏”表示，完全不读取值字段。
4. 非编辑模式 `staticGrid=true` 或等效禁用拖拽和 resize。用户点击“编辑布局”后，Vue 深拷贝服务端 layout/ETag 作为 session draft，才启用 GridStack。drag/resize change 仅更新草稿；取消恢复最近服务端布局且不请求。
5. 保存用完整 `PUT /dashboard/layout`、当前 layout ETag、新 key；成功用 response layout 取代草稿。409/428 显示“布局已在其他地方更新”，保留本地草稿并给出重新加载/放弃选择；超时只允许相同 key/request 的待确认重试。重置需确认后 `POST /dashboard/layout/reset`，成功重读 dashboard。
6. 严格使用 shared 12 columns，card `minW/minH/maxW/maxH` 来自 API，禁止 local breakpoint reflow 或按 granularity 保存不同 layout。系统 resize observer/measure 仅决定 `compact/medium/expanded` 内容 tier，不写 layout、不改变用户 x/y/w/h。
7. 每个 tier 均完整显示 title、main value、period label、status 与必要 comparison/trend/breakdown；在卡片内纵向流式/可滚动，长名字/错误/零值不能被 `overflow:hidden` 裁掉。Grid row height、min height 需确保内容可达。
8. 提供等价键盘控制：编辑模式中卡片可聚焦，按钮以一个 grid unit 移动或调整大小，aria label 包含指标和方向；announce 结果。尊重 reduced-motion，不以 hover 作为唯一入口；鼠标拖拽测试后仍需键盘测试。

## 4. 验收与测试

- 1024px、1440px 桌面浏览器分别覆盖四个 granularity、当前/过去/未来期间、loading/error/retry、empty/unavailable/hidden 卡、长中文文本和不同 card tier；所有内容读到或滚到可见，不能裁切。
- 用真实 DOM drag/resize 或 GridStack 官方可观察事件验证 draft 变动；保存后刷新保留，取消零网络 mutation，reset 恢复八卡；请求 method/body/If-Match/key 与 dashboard API 精确一致。
- 覆盖 stale ETag、profile/period 切换竞态、keyboard move/resize/focus/reduced-motion 和 edit mode 外无拖拽。测试不可依赖私有 GridStack internals。
- 先执行 `npm audit` 并记录结果；必跑 `frontend` Node 22.18.0 下 `npm test`、生产 `npm run build`。报告依赖实际版本、测试数和 audit 结果。真实 Electron/SQLite/性能由 P5-008。

## 5. 禁止事项、升级与完成报告

禁止手写第二套 grid、非编辑模式持久化、卡片内前端计算/隐藏值泄漏、把 layout 绑到日期粒度、无限 localStorage 恢复、引入任意图表或移动适配依赖。若 GridStack Vue wrapper API 与 ADR-012 不兼容、API 无法表达完整 layout、卡片最小尺寸无法保证完整内容，停止并升级。

完成报告包含依赖锁定/audit、四层视图和鼠标/键盘布局证据、尺寸/长内容证据、浏览器测试与构建结果、真实链路不适用说明和文档同步。
