# BUG-003 Vite 开发代理验证记录

## 修复前证据

在修复前以 `development` 模式加载 `frontend/vite.config.js`，设置：

- `LEDGERX_DEV_API_ORIGIN=https://external.example:8443`
- `LEDGERX_DEV_API_TOKEN=` 后跟 43 个 `A`

配置成功返回 `/api` 代理，target 为 `https://external.example:8443`，`changeOrigin=true`，且存在 `configure` 钩子。该结果证明任意远程 origin 会被接受，满足 BUG-003 的稳定复现条件。

## 修复目标

只接受带显式端口的 `http://127.0.0.1:<1..65535>`（可有一个末尾 `/`），代理键固定为 `/api/v1`，并在 Node 代理请求钩子中验证实际 header 写入；无效配置在 Vite 启动前失败且错误不包含 token。

## 修复后证据

由 `frontend/scripts/vite-config.test.mjs` 使用 Node 内存 fake proxy server/request 验证：

- 有效 origin/token 的 proxy key、target、`changeOrigin`、Bearer 和精确 Origin；
- 无 token 时不写 Authorization；
- 双空变量不创建 proxy，token 无 origin、无效 token 和所有非 loopback origin 均在配置阶段失败；
- production mode 不创建 proxy；
- 构建产物扫描不含 sentinel token、`Authorization: Bearer`、开发 origin 或 `LEDGERX_DEV_API_*`。

该测试不发送网络请求；前端完整回归另按 BUG-003 任务说明执行。
