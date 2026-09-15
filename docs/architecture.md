# LedgerX Java 11 + Vue/Electron 架构设计

- 状态：已接受，替代 JavaFX/WebView 方案
- 日期：2026-09-14
- 对应需求：[requirements.md](./requirements.md)
- 决策记录：[ADR-001](./decisions/ADR-001-java11-modular-monolith.md)、[ADR-003](./decisions/ADR-003-sqlite-per-profile.md)、[ADR-004](./decisions/ADR-004-vue-electron-local-rest.md)、[ADR-005](./decisions/ADR-005-controlled-java-web-root.md)、[ADR-006](./decisions/ADR-006-electron-forge-6-4-2-audit.md)、[ADR-009](./decisions/ADR-009-core-catalog-seed.md)、[ADR-010](./decisions/ADR-010-basic-ledger-scope.md)

## 1. 架构结论

采用**前后端独立开发、单产品部署的本地模块化单体**：

- `frontend/`：Vue 3 + Vite + JavaScript，只有 HTML/CSS/交互和 API client；
- `electron/`：Electron main/preload，只有窗口、安全策略、Java 子进程和必要 OS 能力；
- `src/main/java/`：Java 11 后端模块化单体，提供 loopback REST API、领域规则和 SQLite；
- 最终安装包包含三者和 Java 11 runtime，用户只启动 LedgerX 一次。

不使用 JavaFX、进程内 JavaScript bridge、Spring、ORM、微服务、消息系统或 SSR。这里的“前后端分离”是代码、构建、测试和契约分离，不是把个人账本部署成远程服务。

当前可用版本只启用 profile、settings、category、account 和 basic records。高级领域的旧表/文档可以保留，但 `assets/allocation/metrics/formulas/reports/warnings/backup import` 没有 endpoint、capability 或 Vue 入口；详见 [ADR-010](./decisions/ADR-010-basic-ledger-scope.md)。

## 2. 系统上下文

```text
用户
  │
  ▼
Electron main ──启动/监督──> Java 11 后端（127.0.0.1:随机端口）
  │                              │
  │ 创建安全 BrowserWindow       ├─ REST /api/v1
  │ 注入仅本会话认证头            ├─ application/domain
  ▼                              ├─ JDBC/SQLite
Vue 3 renderer ──同源 HTTP───────┘
  │                              │
  └─最小 preload API             └─ %LocalAppData%\LedgerX
```

运行时是多个进程，但只有一个部署单元和一个 Java 数据写者。Vue 不知道数据库位置，Electron 不实现财务规则，Java 不依赖 Electron 类。

## 3. 目录与独立开发边界

```text
ledgerX/
├─ frontend/               Vue 3/JavaScript 目标前端
├─ web/                    旧 React/TypeScript 前端（迁移对照，验收前保留）
├─ electron/               main、preload、Forge 配置
├─ src/main/java/com/ledgerx/
│  ├─ http/                路由、认证、DTO、错误映射、静态资源
│  ├─ application/         用例、活动空间、事务/幂等编排
│  ├─ domain/              财务规则和计算
│  ├─ persistence/         JDBC、SQL、schema migration
│  ├─ backup/              快照、导入、恢复
│  └─ observability/       日志和诊断
├─ src/main/resources/     SQL、seed、生产前端产物/manifest
├─ docs/contracts/         双方共享 JSON fixtures
└─ docs/                   规范、ADR 和 Task Specs
```

约束：

- 前端开发只需要 Node/npm 和一个遵循 fixtures 的 Java 实例或合同 mock。
- 后端开发只需要 JDK 11/Maven，通过 HTTP 集成测试验证，不需要 Electron。
- Electron 开发可用最小健康服务替身验证进程生命周期，但最终验收必须启动真实 Java。
- 生产 Vue 产物由发布编排复制到后端静态资源目录；源目录之间不通过相对路径运行时耦合。
- 当前仓库仍有 `native/` WPF 和 `web/` React/TypeScript；它们是旧版基线，验收前不得删除。目标 Vue 代码放入新 `frontend/`，避免覆盖正在使用的旧版和用户未提交修改。

## 4. 组件职责与依赖方向

| 组件 | 职责 | 可依赖 | 禁止依赖 |
| --- | --- | --- | --- |
| Vue UI | 页面、表单、路由、可访问反馈、展示格式 | REST DTO、浏览器 API、白名单 preload API | Java 类、SQL、Node 通用 API、财务计算副本 |
| API client | 相对 `/api/v1` 调用、request ID、幂等键、错误和游标处理 | `fetch`、共享 fixtures | Electron IPC 细节、数据库行 |
| Electron main | 单实例、窗口、启动/停止 Java、就绪超时、日志转发、认证头注入 | Electron、Node `child_process` | 领域/SQL/业务 DTO |
| Electron preload | 将少量 OS 动作按一方法一能力暴露 | `contextBridge`、严格 IPC | `ipcRenderer` 整体暴露、文件系统、任意命令 |
| Java HTTP | loopback 监听、认证、路由、body 限制、JSON、静态资源、错误映射 | application、Jackson、JDK HttpServer | Electron 类、repository 具体实现 |
| application | 用例、活动 profile、事务、幂等、DTO 组装 | domain、端口接口 | HTTP、Electron、SQL 细节 |
| domain | 基础记录和账户余额不变量；高级领域后续按需启用 | Java 11 标准库 | HTTP、JSON、JDBC、UI |
| persistence | JDBC repository、事务、迁移、健康检查 | application/domain 端口、sqlite-jdbc | Vue/Electron |
| backup（后续可选） | 应用内快照、导入和恢复；基础版不实例化、不暴露 API | application/persistence、文件系统、Jackson | renderer 状态 |

依赖只能向内：`Vue → HTTP DTO → application → domain`，`persistence/backup` 实现 application 定义的端口。Electron 与 Java 只共享启动协议，不共享业务代码。

## 5. 开发与生产运行方式

### 5.1 开发

1. Java 开发实例绑定 `127.0.0.1:<明确开发端口>`，从环境变量读取仅开发会话 token。
2. Vite dev server 只把相对 `/api/v1` 代理到经校验的 `http://127.0.0.1:<port>`，并由该 Node 代理添加认证头；token 不写入 Vue 源码或浏览器存储。
3. Vue 在普通浏览器中可独立开发；需要桌面能力时 Electron 加载 Vite URL。
4. 开发 CORS 默认不开启，因为浏览器仍访问 Vite 同源 `/api`；禁止把 Java 改成 `Access-Control-Allow-Origin: *`。

### 5.2 生产启动

1. Electron main 获取单实例锁，生成至少 256 bit 的随机会话 token。
2. main 先解析 Windows 规范数据根 `%LOCALAPPDATA%\LedgerX`（仅隔离测试可由 `LEDGERX_TEST_DATA_DIR` 覆盖），再用异步 `child_process.spawn` 直接启动打包的 Java 可执行入口，不经过 shell；每次 child 都收到同一有效 `LEDGERX_DATA_DIR`，token 只放子进程环境，命令行和日志均不包含 token。继承的普通 `LEDGERX_DATA_DIR` 不能决定桌面会话的数据根。
3. Java 绑定 `127.0.0.1:0`，确认 `HttpServer.start()` 成功且实际地址仍为 IPv4 loopback 后，立即向 stdout 输出并 flush 一行 `LEDGERX_READY {"port":49152,"protocol":"1"}`。每个进程只输出一次且不等待首个 HTTP 请求；它仅表示监听器可连接，不表示数据库或业务已就绪。
4. Electron 校验行格式、端口和超时后，为本窗口独立 session 注册精确 URL 过滤器；仅对该端口的 `/api/v1/*` XHR/fetch 注入 `Authorization: Bearer <token>`，并在 main 中完成一次已认证 `GET /api/v1/system/status` 握手。
5. `system/status` 是应用就绪状态的唯一依据：`STARTING` 只能显示初始化界面并禁用业务操作，`READY` 才进入业务界面，恢复状态进入恢复界面。握手成功后 Electron 才加载 Java 同源提供的 `http://127.0.0.1:<port>/`；renderer 永远看不到 token。
6. 关闭应用时先请求后端优雅停止，超时后终止自己创建且 PID/句柄已验证的子进程；不能按进程名批量杀 Java。

主页面静态资源无需认证；所有业务、健康详情和文件能力 API 必须认证。`GET /health/live` 只返回固定存活状态，不泄漏版本、路径或数据。

Electron 通过 `LEDGERX_WEB_ROOT` 将随包 Vue dist 交给 Java 的受控静态 manifest；Java 仅接受根 `index.html` 与 `assets/` 普通文件，未设置时保留 classpath manifest 回退。该环境变量不由 renderer 控制，详见 [ADR-005](./decisions/ADR-005-controlled-java-web-root.md)。

### 5.3 后端失败行为

- 监听超时、非法 transport readiness、进程提前退出或已认证 status 握手失败：显示 Electron 自带的最小本地错误页，提供重试、打开脱敏日志目录和退出。合法 transport readiness 不能覆盖随后发生的初始化或恢复错误。
- 后端运行中退出：Vue 进入只读断开态；Electron 可由用户明确触发一次重启，不自动循环重启写操作。
- renderer 刷新不重启 Java；Electron 整体退出必须回收子进程。

## 6. 关键数据流

### 6.1 读请求

`Vue fetch → Electron session 注入 token → Java HTTP 鉴权/校验 → application query → repository → SQLite → DTO/envelope → Vue`。

读 API 不返回巨型全状态。分类、账户和记录列表使用有界游标分页；基础版没有仪表盘聚合接口。

### 6.2 写请求

1. Vue 生成 `X-Request-Id` 和 `Idempotency-Key`；更新类请求携带 `If-Match`。
2. Java 在 HTTP 边界校验认证、content type、body 大小、字段格式和当前恢复状态。
3. application 在一个事务内执行基础记录规则、写 `finance_record`、递增 `data_revision` 并保存幂等结果；高级领域表不参与该事务。
4. 提交成功才返回 `2xx`；超时或连接断开不代表未提交，Vue 必须使用同一幂等键重试。
5. 提交后账户余额由查询重新投影；基础版没有指标/预警的同步副作用。

### 6.3 基础版数据备份

- 基础版不提供上传、下载、导入或恢复 endpoint，也不新增文件选择 preload 能力。
- 用户完全退出应用并确认 Java 子进程结束后，复制整个 `%LocalAppData%\LedgerX` 目录作为备份；恢复前先复制当前目录，再整体替换。
- 不允许在应用运行时只复制某个 `ledger.db`。若以后需要应用内一致性备份，再启用 backup 模块并重新固定 API。

## 7. 技术选择

| 选择 | 理由 | 备选与取舍 |
| --- | --- | --- |
| Vue 3 + Vite + JavaScript | Vue 官方对非 SSR SPA 推荐直接使用 Vite；满足用户指定 HTML/CSS/JS/Vue，并降低 TS 迁移门槛 | 保留 React 可少改代码但不符合新决定；Nuxt 的 SSR/全栈能力无需求 |
| Electron | 现代 Chromium 与完整 H5 兼容；main/renderer 职责清晰，便于前后端独立开发 | JavaFX 已废止；WPF 双技术栈仅保留旧版；系统浏览器桌面一体性差 |
| Electron Forge | Electron 官方推荐的打包工具，提供 package/make 流程 | electron-builder 成熟但非 Electron 官方推荐基线；手工打包维护成本更高 |
| Node `child_process.spawn` | Java 是外部可执行进程；异步 spawn 可直接管理 stdio/退出且不需要 shell | Electron `utilityProcess` 面向 Node 模块，不适合启动 Java；`exec` 会引入 shell和缓冲风险 |
| JDK `HttpServer` | Java 11 自带简单 HTTP server；本机单用户、有限路由足够，避免旧版 Web 框架 | Spring Boot 主线要求更高 JDK且过重；Javalin/Undertow 只在路由/流式能力实测不足时评估 |
| Jackson 2.x | Java 11 无 JSON 映射；成熟、现有基线已采用 | 手写 JSON 风险高；Jackson 3 要求更高 JDK |
| JDBC + Xerial SQLite | 单文件、事务、约束、Windows x64 驱动成熟 | JSON 缺关系/局部事务；H2 无实际优势；ORM隐藏 SQL且增加复杂度 |

2026-09-12 建议初始精确基线为：Vue `3.5.42`、Vite `8.2.2`、`@vitejs/plugin-vue 6.0.8`、Electron `44.3.0`、Electron Forge `6.4.2`（用户已批准）、Playwright `1.63.0`。当前开发和验收直接使用用户本机 Node `24.19.0`，不再折腾 Node 22。版本不得写 `latest`/范围，必须进入 lockfile。

维护版本复核（2026-09-13）：官方 stable Forge `7.11.2` 与 Electron `44.3.0` 的隔离 lockfile仍有审计告警，因此未替换已批准的 6.4.2。该事实在个人分享说明中披露，但不作为基础功能开发门禁；只有未来转为公开商业发布时才重新设为发布门禁。

## 8. 安全设计

本节只描述已存在的桌面进程和本机 HTTP 技术边界，不是金融安全、风控或合规需求；基础版不在此基础上增加角色、审批、审计或加密。

- Java 只绑定数值 loopback 地址；启动后核对实际地址，不监听 `0.0.0.0`/`::`。
- 每次桌面会话使用独立高熵 token；只由 Electron main 和 Java 子进程持有，不进 URL、renderer、localStorage、崩溃报告或日志。
- API 校验 `Authorization` 和精确 Host；mutation 要求同源 Origin，GET/HEAD 的 Origin 若存在也必须同源；不使用 cookie。
  - BrowserWindow 设置 `nodeIntegration:false`、`contextIsolation:true`、`sandbox:true`；默认拒绝权限、远程导航和新窗口。
  - Electron 启动前禁用硬件加速并启用进程内 GPU 回退以兼容无 GPU 主机；该兼容措施不改变 renderer 沙箱，嵌套工具沙箱的 restricted-token 限制记录于 [ADR-007](decisions/ADR-007-electron-sandbox-runtime.md)。
- CSP 至少限制为自身脚本/样式/连接；生产不加载 CDN、远程字体或远程脚本。
- preload 不暴露原始 `ipcRenderer`；每个消息验证 sender、参数和固定目标。
- JSON 默认上限 1 MiB；基础版没有文件导入 endpoint。
- 日志只记录 request ID、动作、耗时、脱敏 profile hash 和错误码，不记录认证头、完整 payload、备注或私人路径。
- 依赖安装需要联网，但正常产品运行不发起公网请求；自动更新不在首期范围。

## 9. 性能、可靠性与可观测性

### 性能

- Java HTTP 使用有界 executor；接收线程不执行 schema migration 或长时间文件 I/O。
- 单写事务串行；只读可并行。先用索引和有界查询，不加缓存层。
- 静态资源使用正确 MIME、ETag/immutable hash 名；HTML 禁止长期缓存。
- 达不到门槛时先 profile SQL、序列化和前端渲染，再决定派生表或缓存。

### 可靠性

- Electron 监督唯一 Java 子进程；启动、退出、崩溃和重试均有状态机。
- SQLite 外键每连接开启；schema migration 只前进、带校验和，拒绝未知高版本。
- 基础版依赖正常退出后复制完整数据目录；应用运行时不执行恢复或旧 JSON 导入。
- API 写操作使用幂等键和乐观并发，网络断开不会靠猜测决定结果。

### 可观测性

- 本地结构化日志统一 `timestamp, level, component, event, requestId, durationMs, errorCode`。
- 记录启动各阶段、HTTP 状态分组、数据库 busy/rollback、schema migration 和后端异常退出。
- 默认不上报遥测；诊断导出由用户主动触发并预览脱敏摘要。

## 10. 测试与发布层次

1. domain 单元测试：金额、日期、三类记录、余额方向和回收站状态。
2. persistence 集成：真实临时 SQLite，验证事务、约束、重开和迁移幂等。
3. HTTP 合同：共享 fixtures 覆盖认证、字段、错误、分页、幂等和并发。
4. Vue 组件/浏览器：表单、空态、错误、响应式和可访问性；mock 必须遵循相同 fixtures。
5. Electron 集成：安全配置、Java 启停、token 不暴露、后端崩溃/刷新/退出。
6. 真实 E2E：Electron→REST→Java→SQLite 的新增/重载/编辑/删除/恢复/余额闭环。
7. 分享包：隔离数据目录、断网启动、发布产物和快捷方式；只在用户要求生成可分享版本时执行。

模拟 API 只能证明前端行为，不能替代第 5–7 层。

2026-09-13，S0-006 的隔离 Electron→REST→Java→SQLite 证据已记录；BUG-001 至 BUG-004 已在用户授权的 Node 24.19.0 本机环境完成重验。当前后续主线是 P3 分类/账户和 P4 基础记录，不再以 Node 22、资产/指标或旧数据迁移阻塞。

## 11. 重新评估触发器

- 需要手机、云同步、远程浏览器或多设备并发写：重新设计身份、TLS、同步和服务端部署。
- 需要多人共享/权限/审计：引入真正认证授权和租户模型，不复用本机会话 token。
- 单空间超过 20,000 条且常用 P95 持续越线：先分析 SQL/索引/渲染，再评估缓存或派生表。
- 用户明确需要资产、分摊、指标/公式、报表/预警、旧数据导入或应用内备份：重新启用对应模块设计，不从保留表直接猜接口。
- Java `HttpServer` 在真实压测中无法满足流式、取消、安全补丁或可维护性：在保持 API 契约下评估小型 Java 11 HTTP 库。
- Electron 的包体、安全维护或平台策略不可接受：保持 Vue/REST/Java 边界，重新选择壳或系统浏览器。
- Java 11 或关键依赖失去安全维护：优先升级运行时 LTS。
- 需要后台在线任务或可靠外部投递：先设计作业持久化；不要直接引入分布式消息系统。
- 团队/模块确需独立部署、扩缩容或故障隔离：有证据后再讨论服务拆分。

## 12. 官方资料核查

- [Vue Quick Start](https://vuejs.org/guide/quick-start) 说明官方脚手架基于 Vite，且无 SSR 时可直接使用 Vite。
- [Vite Guide](https://vite.dev/guide/) 提供标准 dev/build 模型，适合独立前端工程。
- [Electron Process Model](https://www.electronjs.org/docs/latest/tutorial/process-model) 区分 main、renderer 与 preload 的职责。
- [Electron Security](https://www.electronjs.org/docs/latest/tutorial/security) 要求及时更新、context isolation、sandbox、CSP、限制导航/窗口并验证 IPC sender。
- [Electron Context Isolation](https://www.electronjs.org/docs/latest/tutorial/context-isolation) 与 [Sandbox](https://www.electronjs.org/docs/latest/tutorial/sandbox) 支持本设计的 renderer 隔离。
- [Electron WebRequest](https://www.electronjs.org/docs/latest/api/web-request) 支持在受限 session 中修改精确匹配请求的请求头。
- [Electron Packaging](https://www.electronjs.org/docs/latest/tutorial/application-distribution/) 推荐 Electron Forge；签名仍是发布前独立决定。
- [Electron Releases](https://releases.electronjs.org/) 与 [release schedule](https://releases.electronjs.org/schedule) 用于确认 44.3.0 的 Chromium/Node 组合和支持窗口；不能长期冻结安全补丁。
- [Electron Forge releases](https://github.com/electron/forge/releases) 显示 7.11.2 是稳定线、8.x 仍是 alpha。
- npm registry 的 [Vue](https://www.npmjs.com/package/vue)、[Vite](https://www.npmjs.com/package/vite)、[@vitejs/plugin-vue](https://www.npmjs.com/package/%40vitejs/plugin-vue)、[Electron](https://www.npmjs.com/package/electron) 和 [Playwright](https://www.npmjs.com/package/%40playwright/test) 页面用于记录本次精确初始版本与许可证。
- [Node child_process](https://nodejs.org/api/child_process.html) 说明 `spawn` 异步创建外部进程并提供 stdio/生命周期事件。
- [Java 11 HttpServer](https://docs.oracle.com/en/java/javase/11/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html) 提供绑定地址/端口、context 和 executor 的简单 HTTP server。
- [SQLite Backup API](https://www.sqlite.org/backup.html)、[foreign keys](https://www.sqlite.org/foreignkeys.html) 和 [PRAGMA integrity_check](https://www.sqlite.org/pragma.html#pragma_integrity_check) 支持一致性备份、外键和健康检查设计。
- [Xerial sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) 提供 Windows x64 原生库随 JAR 的 JDBC 驱动。
- [Jackson databind](https://github.com/FasterXML/jackson-databind/) 的兼容表支持 Java 11 采用 2.x 线；禁止默认多态反序列化。

## 13. 明确取舍

- Electron 增加包体和 Chromium 安全更新责任，但换来稳定的现代 H5 能力和明确的前后端分离；这是用户当前选择。
- 本机 REST 增加端口、认证和跨进程失败面，但比私有 WebView bridge 更易独立开发、合同测试和替换桌面壳。
- `HttpServer` 减少框架依赖，但路由、body 限制和错误映射需小而明确的自有适配层；只做本项目需要的能力。
- 同源静态资源让生产无需 CORS；代价是 Java 同时承担少量静态文件服务，但不改变前端独立构建。
- 不使用 TypeScript 符合用户指定；共享 JSON fixtures、运行时校验和清晰 API 文档承担跨层类型风险。
