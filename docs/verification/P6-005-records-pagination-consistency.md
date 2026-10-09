# P6-005 收支记录分页与 mutation 一致性验证记录

- 日期：2026-09-22
- 目标：验证当前记录/回收站的 cursor 分页、视图切换竞态，以及记录写入冲突时的草稿保留。

## 实现结果

记录列表以 `status + contextEpoch` 作为查询键。replace 立即清空旧数据并取消上一请求；append 只复用当前查询键和服务端 cursor，按 ID 去重。新增、编辑、移入回收站、恢复统一经过 `useApiMutation`，成功后提交服务端 `meta.dataRevision`，再独立刷新列表和账户数据。

## 测试证据

在 `frontend` 目录执行：

- `npm run build`：通过。
- `node node_modules/@playwright/test/cli.js test tests/records-page.spec.js`：6/6 通过，覆盖新增、弹窗 Escape、51 条 ACTIVE/TRASHED cursor 分页、列表去重、延迟 ACTIVE→TRASHED 竞态、409 冲突保留表单和字段错误绑定。
- `npm test`：配置测试 7/7、composable 单元测试 3/3、Playwright 全量 42/42 通过。

浏览器测试使用真实 Vite preview 服务器和 API 路由 fixture；本次未新增 Java/SQLite 代码，也未重复 P6-001 已通过的原生 formula round-trip。按任务要求的 records 新增→第 51 条→删除→恢复原生链路仍需在 P6-008 集成验收中补充，当前没有把模拟 fixture 误报为真实原生证据。未增加新的筛选维度。
