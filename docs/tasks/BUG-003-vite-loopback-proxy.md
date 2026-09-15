# BUG-003：Vite 开发代理可把会话 token 转发到任意来源

- 状态：已实施；BUG-003 配置与前端回归通过，当前 Node 24 授权环境整体 S0 已由 BUG-004 重验通过
- 分类：开发期安全配置
- 优先级：高；阻塞安全重验

## 1. 已确认诊断

`frontend/vite.config.js` 将任意 `LEDGERX_DEV_API_ORIGIN` 交给 `new URL(...).origin`，为 `/api` 配置代理，并在 `proxyReq` 中附加 `Authorization: Bearer ${LEDGERX_DEV_API_TOKEN}`。因此如环境误配为远程 HTTP/HTTPS host，Vite 会把本地桌面会话 token 发送给该 host。

链路为：开发者环境变量 → Vite Node 配置 → `/api` proxy → 代理请求头 → 任意目标。该问题发生在开发服务器，不涉及 Java、SQLite 或已有历史数据；但 token 泄漏会让同一受控 loopback 会话的攻击面扩大，不能以“仅开发”忽略。

根因是开发代理未把已接受的“Java 仅 IPv4 loopback”安全边界编码为输入校验，也把 token 注入范围放宽为 `/api`。

## 2. 目标、范围与非目标

目标：Vite 只可代理到精确 IPv4 loopback Java 开发实例，并且只对 `/api/v1/*` 代理请求在 Node 侧注入格式有效的 token；错误配置要在开发服务器启动前明确失败且不泄漏 token。

范围：Vite config 的纯校验/代理构造、无依赖的 Node 配置测试、frontend scripts/package 入口、开发说明和安全文档。

非目标：给 Java 增加 CORS、支持远程开发 API、改变生产 Electron header 注入、修改 REST/DDL、添加 dotenv/代理/secret 管理依赖、把 token 暴露给 Vue。

## 3. 已固定配置契约

1. 开发模式只有以下 origin 有效：`http://127.0.0.1:<port>` 或同义的单个末尾 `/`，其中 port 是显式十进制 `1..65535`。必须拒绝 `https`、`localhost`、IPv6、其他 IPv4/域名、缺端口、凭据、非根 path、query、fragment 和空白/无法解析值。
2. `LEDGERX_DEV_API_ORIGIN` 为空且 token 为空时，不创建 proxy。token 非空而 origin 为空时是配置错误，不能静默忽略。
3. token 若提供，必须严格匹配现有 desktop session token 格式 `[A-Za-z0-9_-]{43}`；格式不符必须失败，错误文本只可提变量名/原因，绝不包含 token 值。
4. proxy key 固定为 `/api/v1`，target 是已校验的 origin，`changeOrigin:true`；`Origin` 固定为该 origin。只有该 proxy 的 Node request hook 才可写 `Authorization`，且 token 不存在时不写该头。
5. 只在 Vite `development` mode 应用此代理配置。生产 build 不创建 proxy、不读取 token 到 client 配置，并且构建产物不含测试 sentinel token、`Authorization: Bearer` 或开发 endpoint。
6. Vue 请求路径、Java Host/Origin 校验和 Electron 生产 session header hook 均不变。

选型依据：Vite 官方 `server.proxy` 文档明确 proxy key 是请求路径前缀，且 `configure` 可取得 proxy 实例；故本任务用已有官方机制收窄到 `/api/v1` 并在 Node 侧设置 header，不新增代理库。[Vite server.proxy](https://vite.dev/config/server-options.html#server-proxy)

## 4. 允许修改与依赖

本任务可与 BUG-002 并行；BUG-004 依赖本任务完成。无需等待 BUG-001。

允许/预计修改：

- `frontend/vite.config.js`；
- 新增 `frontend/scripts/vite-config.test.mjs`；
- `frontend/package.json`（仅新增/接入 `test:config`，使 `npm test` 先跑它）；
- `frontend/README.md`、`docs/api.md`、本任务实际报告。

引用：[architecture §5.1/§8](../architecture.md)、[api §1/§3/§5](../api.md)、[ui-integration §2/§3/§7](../modules/ui-integration.md)、[S0-003](./S0-003-vue-frontend-foundation.md)。

不得修改 `frontend/src/**`、Electron、Java、数据库、lockfile或依赖版本。

## 5. 实施步骤

1. 从 Vite 配置导出一个不读取全局环境、可用 `env` 和 `mode` 调用的纯开发代理解析函数；`defineConfig` 仅负责 `loadEnv` 后调用它。不要引入配置框架或 dotenv。
2. 逐项解析 URL，而非仅使用 `.origin`：检查 scheme、hostname、显式 port、username/password、pathname、search、hash 和原始空白。返回值只含已验证的 canonical origin 和 `/api/v1` proxy 配置。
3. 在 Node 内存 fake proxy server/request 上测试 hook 实际写入的 header；不得通过字符串搜索代替行为测试。
4. 把 `node --test scripts/vite-config.test.mjs` 接入 `npm test` 的第一步，再运行现有 build/Playwright。配置测试不得请求公网，允许的测试 target 仅为假对象或 `127.0.0.1`。
5. 更新 frontend README：开发者只设置本机 Java origin 和短会话 token；不得把它们写成 `VITE_*`、`.env` 提交内容、前端源码或远程 URL。更新 API 文档以记录 loopback allowlist，不改变 REST 契约。

## 6. 验收与回归测试

| 场景 | 必须结果 |
| --- | --- |
| `development` + `http://127.0.0.1:49152` + 有效 43 字符 token | 仅有 `/api/v1` proxy；hook 写入该 Bearer 和精确 Origin。 |
| 有效 origin、无 token | proxy 存在，但 hook 不写 Authorization。 |
| 两变量均为空 | 无 proxy、无 header hook。 |
| token 有值而 origin 空 | 启动前配置失败，输出不含 token。 |
| `https://…`、`http://localhost:…`、IPv6、外部 IP/域名、userinfo/path/query/hash、缺/非法 port | 每项被拒绝，输出不含 token。 |
| 无效 token | 被拒绝，输出不含 token。 |
| `production` mode，环境带有效 sentinel token/origin | 没有开发 proxy；`npm run build` 成功且 `dist` 不含 sentinel、`Bearer `、开发 origin 或 `LEDGERX_DEV_API_*`。 |
| 既有 status browser 测试 | `npm test` 仍运行并通过；相对 `/api/v1/system/status` 无字段/路径变化。 |

## 7. 风险、禁止与升级

- 不得接受 `localhost` 作为便利别名：Java 只绑定/校验 `127.0.0.1`，宽松 alias 会削弱同源和 header 边界。
- 不得把“远程开发 API”做成开关、CORS 例外、代理 allowlist 可配置项或环境变量回显；这是新的架构/安全决策。
- 不得更改 production CSP、Electron header matcher、session token 格式或将 token编入 bundle。
- 若 Vite 的公开 proxy API 无法实现精确 `/api/v1`/hook 行为，或现有 Java 开发流程需要非 loopback target，停止并升级高级模型，不要用通配 proxy 兜底。

## 8. 完成报告与文档同步

报告应给出配置单元场景数量、`npm test` 结果、sentinel 扫描结果和“未发送任何公网请求”的证据。列出所有修改的文档；BUG-004 前不得以本任务单独结果宣称完整 Electron 网络边界已验收。

## 9. 实际实施报告（2026-09-13）

- 根因所在层：`frontend/vite.config.js` 的开发期 Node 配置层。已将 origin 校验收紧为精确 IPv4 loopback、代理键收紧为 `/api/v1`，并让 token 只在该代理的 Node `proxyReq` hook 中注入；production mode 不加载开发环境变量。
- 修改文件：`frontend/vite.config.js`、`frontend/scripts/vite-config.test.mjs`、`frontend/package.json`、`frontend/README.md`、`docs/api.md`、`docs/verification/BUG-003-vite-loopback-proxy.md`及本报告。
- 修复前证据：远程 `https://external.example:8443` 可生成 `/api` proxy，详见[验证记录](../verification/BUG-003-vite-loopback-proxy.md)。
- 配置层验证：`npm run test:config`，7/7 通过；使用内存 fake proxy server/request 断言真实 hook 的 Authorization/Origin 行为，未发起网络请求。
- 前端回归：`npm test` 中配置测试 7/7、生产 build 成功、状态/错误/恢复/轮询/无并发/320px 与 200% 缩放浏览器测试 11/11 通过。执行时 4173 曾有已有本地 preview listener，脚本提示端口占用但 Playwright 仍使用本机同一 dist 完成 11/11；未把该提示误报为独立启动验证。
- 构建产物：`dist` 扫描未发现 sentinel token、`Authorization: Bearer`、`LEDGERX_DEV_API_*` 或测试开发 origin；production build 未创建 proxy。
- 安全范围：测试只使用假对象和 `127.0.0.1`，未发送公网请求；未修改 Vue 业务、Electron、Java、数据库、REST 字段或依赖版本。
- 未验证/后续：本任务本身不重复记录真实 Electron 默认启动；BUG-004 已在用户授权 Node 24 环境完成首导航和退出回收验证。Node 22 基线、正式安装包、签名和发布链路不属于本任务。
