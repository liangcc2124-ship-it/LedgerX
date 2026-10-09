# P6-003 读取竞态与幂等 mutation 验证

- 日期：2026-09-22
- 目标：验证共享读取/写入状态机不会让过期响应覆盖当前页面，并在写入等待超时后保留同一用户意图。

## 实现结果

`useAsyncResource` 负责读取 generation、AbortController、replace/append 查询键和 15 秒等待上限。`useApiMutation` 负责请求快照、深复制/冻结、提交超时、相同幂等键重试和 operation 查询。Profiles/Catalog 已完成迁移；Records、Metrics、Dashboard 按 P6-003 非目标保持原实现。

`App.vue` 现在提供只读应用上下文，profile 改变时推进 `contextEpoch`，mutation 只从服务端 `meta.dataRevision` 更新版本，不在前端自行加一。

## 测试证据

在 `frontend` 目录执行：

- `npm run build`：通过。
- `npm run test:config`：7/7 通过。
- `node --test tests/composables.test.mjs`：3/3 通过，覆盖最新 replace 胜出、timeout pending、same-key retry、不同 signal、请求冻结、400 validation 与 409 conflict。
- `node node_modules/@playwright/test/cli.js test tests/catalog-page.spec.js tests/profiles-page.spec.js`：15/15 通过，覆盖 Profiles/Catalog 创建、切换、归档、合并、错误、超时后查询、分页和 320px 键盘路径。

浏览器测试使用真实预览服务器；单元测试使用可控 request/Promise 替身，仅用于验证 composable 状态机，不替代真实 Java HTTP。真实 Electron 发布包和 Records/Metrics/Dashboard 迁移留给后续 P6 任务。
