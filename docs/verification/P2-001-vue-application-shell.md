# P2-001 Vue 应用壳验证记录

- 日期：2026-09-14
- 结果：PASS（当前本机 Node 24.19.0；Node 22 不作为交付门槛）
- 任务：[P2-001](../tasks/P2-001-vue-application-shell.md)

## 修改范围

新增 READY 后的内存导航、首页和两个静态业务页骨架；扩展唯一 `apiClient.js` 的相对 JSON 请求、结构化错误和响应头解析；新增 shell 浏览器回归及 API client 单测。未修改 Java、Electron、旧 `web/`/`native/`、公共 API 文档、依赖或 lockfile。

## 实际验证

在 `D:\Project\ledgerX\frontend` 执行：

| 命令 | 结果 | 覆盖 |
| --- | --- | --- |
| `node --test tests/api-client.test.mjs` | 3/3 PASS | 相对路径、JSON/header、ETag/Location、结构化错误、坏 JSON、取消 |
| `npm run test:config`（由 `npm test` 执行） | 7/7 PASS | Vite loopback proxy 与 token 不进入 renderer 的既有配置合同 |
| `npm run build`（由 `npm test` 执行） | PASS | Vue production 构建 |
| `npm test` | 15/15 Playwright PASS | 原有 status 11 项 + shell 4 项 |

浏览器场景覆盖 READY 导航和焦点、非 READY 不显示导航、静态骨架不调用 `/api/v1/profiles`/`settings`、320/375/768/1024/1440 宽度无横向溢出，以及 200% 缩放键盘可达。测试使用 status 合同 mock，仅证明 renderer 行为。

## 安全与产物检查

生产 `frontend/dist` 扫描未发现 `LEDGERX_DEV_API_TOKEN`、`Bearer`、开发 `127.0.0.1:<port>` origin 或 `sourceMappingURL`。请求 client 没有 Authorization/Origin 写入逻辑；认证仍由 Electron/Vite 代理边界负责。

## 未验证项与风险

- 本记录保留 P2-001 完成时的 15/15 shell 基线证据。P2-002 随后把 profiles 静态骨架替换为真实页面，并相应扩展了 shell 合同测试；当前页面行为与最新总验证以 [P2-002 验证记录](./P2-002-vue-profiles-page.md) 为准。

- 未执行真实 Java HTTP、SQLite、Electron 或发布包链路，因为 P2-001 没有业务请求且任务明确将这些链路留给 P2-002/P2-003/P2-005。
- 当前结果基于本机 Node 24.19.0 / npm 11.17.0；交付不再等待 Node 22 基线。
- profiles/settings 真实字段、ETag、幂等重试和持久化重载由后续页面任务及 P2-005 集成验收负责。
