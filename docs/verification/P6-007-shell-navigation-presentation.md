# P6-007 应用壳导航与中文呈现验证记录

- 日期：2026-09-22
- 目标：验证 hash 导航、capability 入口、刷新/前进后退同步和页面可见枚举映射。

## 实现结果

应用壳只接受 `home|records|catalog|metrics|profiles|settings`。导航点击写入 hash，刷新和浏览器前进后退恢复页面；未知或无 capability 的深链回到 `#home` 并给出一次状态提示。应用上下文继续只保存 profile、revision、capabilities 和 epoch，不缓存业务实体。

## 测试证据

在 `frontend` 目录执行：

- `npm run build`：通过。
- `node node_modules/@playwright/test/cli.js test tests/application-shell.spec.js`：5/5 通过，覆盖 READY 导航、刷新恢复设置页、320px/200% 键盘路径、无 capability 深链回 home 及 hash 后退。
- `npm test`：配置测试 7/7、composable 单元测试 3/3、Playwright 全量 44/44 通过。
- 映射覆盖账户类型、资产/负债方向、ACTIVE/INACTIVE/ARCHIVED、CURRENCY/PERCENT/NUMBER/INTEGER、记录类型和 metric dataStatus；未知值由纯函数保留原值诊断。

本任务未引入 Vue Router、i18n、Pinia 或 API 枚举改名。真实 Java profile 切换→hash→数据重读留给 P6-008 集成验收；本次浏览器场景使用 Vite preview 和 API fixture。
