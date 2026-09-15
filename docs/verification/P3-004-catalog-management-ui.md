# P3-004 分类与账户管理页验证记录

状态：PARTIAL。Vue 管理页已覆盖读取、完整响应校验、分页加载、CRUD/归档、merge 确认、超时待确认和 operation 查询；完整异常矩阵仍需补齐。

## 已验证

- `frontend/npm test`：Vite 配置 7/7、Playwright 35/35；目录页用例验证导航焦点、分类/账户读取、最小创建请求体和 merge 二次确认/If-Match/source revision。
- 浏览器用 fixture/mock HTTP 验证了 `id`、`name`、`parentId` 请求字段，以及不提交 `sortOrder`、`recordTypes`；该层不是 Java/SQLite 真实链路。
- 真实 Electron 专用用例 `electron/tests/p3-005-catalog.integration.spec.mjs`：1/1；隔离数据目录中通过可见 UI 新建自定义分类和账户，退出后使用同一目录重启并重新读取。

## 未覆盖

- ACTIVE_CHILDREN、类型兼容和冲突恢复的浏览器错误矩阵。
- 15 秒/30 秒超时和 operation 查询的浏览器专用 mock 断言，以及 profile 切换期间旧 cursor/ETag 的回归。
- 发布包、签名、快捷方式和真实用户目录。
