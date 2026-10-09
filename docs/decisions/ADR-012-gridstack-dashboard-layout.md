# ADR-012：使用 GridStack 作为 Vue 财务总览的桌面布局引擎

- 状态：已接受
- 日期：2026-09-15
- 范围：P5 财务总览的桌面卡片拖动、缩放、碰撞处理和本地编辑态；不改变后端 layout 契约

## 背景

[ADR-011](./ADR-011-enable-metrics-dashboard.md) 要求用户在显式编辑模式中用鼠标自由移动、缩放财务指标卡片，并保存 12 列无重叠布局。现有 Vue 项目没有网格布局依赖；旧 React 原型的手写 pointer 计算只适合演示，不具有可靠碰撞、压缩、生命周期和可访问性行为，不能直接迁入目标前端。

本产品是 Vue 3 + Vite + JavaScript 的本地桌面应用。移动端响应式布局不在本轮范围，持久化格式已经固定为 `x/y/w/h/minW/minH/maxW/maxH`，且 Java 仍是最终校验者。

## 决策

P5-007 引入并在 `frontend/package.json` 与 lockfile 中精确锁定 `gridstack` **13.3.0**，使用其官方 Vue 3 包装层和 `gridstack/dist/gridstack.css`。不采用独立第三方 Vue fork，不实现自有拖拽/碰撞引擎。

- `DashboardGrid.vue` 是唯一接触 GridStack 的本地适配组件；业务卡片、`OverviewPage.vue` 与 API client 不直接调用 GridStack。
- GridStack 只管理编辑中的 DOM 布局与拖拽/缩放事件；Vue state 中的完整 layout draft 才是前端权威，Java `/dashboard/layout` 才是跨重启权威。
- 适配组件在普通模式禁用 move/resize；编辑模式才启用，并将 `change`、`dragstop`、`resizestop` 的规范坐标复制进 draft。
- 提交前移除 GridStack 私有字段，只发送 API 定义的 item 字段。读取 server layout 后由适配组件加载，不调用 GridStack 的序列化结果作为持久化协议。
- 编辑模式提供每张卡片的“左/右/上/下移动、增/减宽度、增/减高度”按钮；它们调用同一 draft 布局变换器并保留可见 focus。这是鼠标交互的键盘等价路径。
- 当 `prefers-reduced-motion: reduce` 时禁用布局动画；卡片内容保持 Vue 组件所有权，不因移动而丢失焦点、草稿或异步状态。

## 依据与依赖评估

| 问题 | 结论 |
| --- | --- |
| 所需能力 | 12 列拖动、缩放、碰撞/压缩、动态 widget、变更事件和 Vue 组件渲染 |
| 现有能力为何不足 | CSS Grid 不能提供拖动/碰撞；手写 pointer 算法会重复实现坐标、容器、碰撞和清理逻辑 |
| 选择 | GridStack 13.3.0；官方仓库包含 Vue 3 wrapper，并暴露 change/drag/resize 事件与 grid instance |
| 许可 | MIT，适用于个人自用和小范围分享 |
| 运行时成本 | 包本身声明零 runtime dependencies；只在总览页面静态导入，首版不额外引入状态管理或 DOM 库 |
| 替换边界 | `DashboardGrid.vue` 和规范 layout DTO 隔离库；Java/API/数据库不存库私有 JSON |
| 安全与维护 | 不使用 CDN、远程脚本、Node integration 或 GridStack 自定义 HTML 注入；安装时运行 lockfile 审计与本地 Playwright 回归 |

官方 Vue wrapper 文档说明其提供 Vue 3 的 `<GridStack>` 组件、拖动/缩放事件和 Composition API；项目 package manifest 声明 13.3.0、MIT 和零运行时依赖。[Vue wrapper 文档](https://github.com/gridstack/gridstack.js/blob/master/vue/README.md) [包清单](https://github.com/gridstack/gridstack.js/blob/master/package.json) [MIT 许可证](https://github.com/gridstack/gridstack.js/blob/master/LICENSE)

## 备选方案

- **手写 CSS Grid + Pointer Events**：不选。它会把可恢复布局、碰撞、缩放、指针捕获、窗口边界和取消行为变成产品自维护的复杂交互系统。
- **`vue-grid-layout` 及其 fork**：不选。原项目公开版本主要面向 Vue 2；Vue 3 forks 的维护、接口和发布质量不如 GridStack 官方 Vue 包装层可验证。
- **只允许按钮移动、没有鼠标拖动**：不满足用户的直接操作要求。

## 后果与验证门槛

P5-007 必须在安装前确认 `gridstack@13.3.0` 的 lockfile 精确条目、生产构建无远程资源、审计结果和许可证记录。必须在 1024px、1440px、200% 缩放和长中文/英文名称下验证：拖动、缩放、碰撞后的 draft，取消、保存、重载、键盘等价操作、Esc 退出编辑、焦点和 reduced motion。若该版本无法在 Vue 3.5.42/Vite 8.2.2 下通过上述验证，停止 P5-007 并升级；不得无审查地换用 `latest`、fork 或手写替代。
