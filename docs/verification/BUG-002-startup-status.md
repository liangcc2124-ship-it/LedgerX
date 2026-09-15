# BUG-002 启动状态修复前后证据

本记录只保留状态、请求次数和可见文案，不包含令牌、完整路径或响应正文之外的敏感数据。

## 修复前（2026-09-13）

- 使用真实 Vite preview 和 Playwright，路由 `/api/v1/system/status` 返回共享 `status-starting.json`（HTTP 200、`state=STARTING`）。
- 原有 `npm test` 结果为 6/6，但首个用例断言了“本地服务已连接”和 `状态 STARTING` 同时可见；这证明测试通过掩盖了错误行为。
- 用户可见结果：`STARTING` 被当作 `ready`，没有初始化提示、轮询或业务禁用状态。

## 修复后（2026-09-13）

- `STARTING` 显示“正在初始化本地数据…”，每次响应完成后约 500 ms 顺序轮询，最多等待 15 秒；`READY` 才显示“本地服务已连接”。
- 连续 `STARTING` 在 15 秒后进入可重试超时；测试记录最大并发请求数为 1。
- `RECOVERY_REQUIRED` 显示恢复指引，只能手动刷新；无 bridge 的普通浏览器不会抛错，bridge 失败只显示通用反馈。
- 401、503、网络失败、坏 JSON、未知状态、错误 API major 和不完整 READY 均不显示成功；旧请求/定时器结果不会覆盖新一轮状态。
- Playwright 合同回归：11/11 通过；覆盖 READY、STARTING→READY、deadline、RECOVERY、401、503、异常响应、重试竞争、320px、页面缩放和键盘焦点。
