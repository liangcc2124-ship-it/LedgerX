# P6-002 共享弹窗与键盘可达性验证

- 日期：2026-09-22
- 目标：验证统一 `AppDialog` 的弹窗交互，以及 Profiles、Catalog、Records、Metrics 页面迁移后的可观察行为。

## 实现范围

1. `frontend/src/components/common/AppDialog.vue` 统一处理 Teleport、焦点首选/回退、Tab 和 Shift+Tab 循环、capture 阶段 Escape、backdrop 自身点击、busy 防关闭、body overflow 锁定、`#app.inert` 和关闭后的触发焦点恢复。
2. Profiles、Catalog、Records、Metrics 的创建/编辑/归档/记录确认使用共享组件；Dashboard 的恢复默认布局确认也迁移，避免产品代码继续调用 `window.confirm`。
3. 共享 CSS 保留桌面端弹窗的可滚动高度边界；没有添加第三方 focus-trap 或 UI 框架依赖。

## 实际验证

在 `frontend` 目录执行：

- `npm run build`：通过，Vite 生产资源生成成功。
- `npm run test:config`：7/7 通过。
- `node node_modules/@playwright/test/cli.js test tests/catalog-page.spec.js tests/dashboard-metrics.spec.js tests/records-page.spec.js tests/profiles-page.spec.js`：20/20 通过。

Playwright 使用真实预览服务器和真实键盘事件，覆盖：

- Catalog 新建分类：打开后名称输入框首焦点，Escape 关闭并恢复“新增分类”触发按钮焦点。
- Metrics 编辑器：首焦点、`#app.inert`、body overflow、Tab 正向循环、Shift+Tab 反向循环、Escape 和关闭焦点恢复。
- Records 表单：金额输入首焦点、Escape 关闭和触发按钮焦点恢复。
- Profiles：创建表单及 320px/200% 缩放下的键盘可达性；归档确认已改为共享 dialog。
- Dashboard：日/周/月/年入口和布局保存回归仍通过；恢复默认确认不再调用原生 confirm。

## 限制与未执行项

- 本次验证使用 Vue/Vite 预览服务器，不等同于 Electron 打包启动验收；Electron 真实闭环由 P6-008 负责。
- 本次未新增独立的 1024×768、1440×900 截图矩阵；弹窗 max-height/overflow 规则已由共享 CSS 固定，完整多视口验收仍留给 P6-008。
- 产品源码中 `window.confirm` 搜索结果为零；测试中仍可保留对旧行为的兼容性断言，但不代表产品调用原生确认框。
