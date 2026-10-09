# local-api-contract 模块规格

- 状态：当前网页版 HTTP 契约已实现；模块能力仍按 API 与各任务验收状态推进
- 引用：[架构](../architecture.md)、[全局 REST API](../api.md)、[ADR-014](../decisions/ADR-014-local-browser-only.md)；旧 Electron 传输见 [ADR-004 历史决定](../decisions/ADR-004-vue-electron-local-rest.md)

## 1. 职责、术语、用例与非目标

职责：在 Java 11 中提供最小 loopback HTTP server、浏览器会话认证、路由、请求限制、JSON DTO、通用响应/错误、classpath Vue 静态资源和 operation 状态查询；把合法请求映射到 application use case。

术语：`transport`（HTTP 适配）、`browser session`（仅当前 Java 进程有效的 HttpOnly Cookie + CSRF 生命周期）、`route`（方法+规范路径）、`operation`（带幂等键的 mutation）、`dataRevision`（活动账本整体版本）、`recovery mode`（业务写禁用状态）。

用例：live/status、静态资源、认证读写、字段拒绝、分页、并发冲突、幂等重试、队列满、优雅停止。

非目标：业务规则、SQL、CORS 网关、TLS/远程客户端、WebSocket、事件总线、反射式路由、通用 Web 框架或 Electron IPC。

## 2. 入口、输出与依赖

| 项 | 说明 |
| --- | --- |
| 入口 | loopback HTTP/1.1；浏览器 Cookie/CSRF bootstrap；启动配置；application ports |
| 输出 | `/health/live`、`/api/v1/*` JSON、静态资源、一次 transport readiness line、结构化日志 |
| 依赖方 | Vue API client、HTTP 合同测试、同源静态资源加载器 |
| 被依赖方 | JDK `HttpServer`、Jackson、application ports、资源 manifest |
| 方向 | `http → application → domain`；application/domain 不依赖 HTTP 类型 |

## 3. 规则、不变量与权限

- 绑定地址必须显式 `127.0.0.1`，生产端口 `0`；bind 后核验地址。
- `HttpServer.start()` 成功且绑定地址复核通过后，必须立即输出并 flush 一次 `LEDGERX_READY {"port":<port>,"protocol":"1"}`；启动前不输出、首个请求不触发第二次输出。它只证明监听器可连接。
- `/api/v1/system/session` 校验精确 Host，并建立/复用仅驻留本进程的会话；写请求还须通过 Origin 和会话绑定 CSRF 检查。不得提供 Bearer 或无认证的业务访问回退。
- 应用是否可用只看已认证 `system/status`；transport readiness 不能把 `STARTING`、迁移失败或恢复状态提升为 `READY`。
- `/health/live` 之外先认证再解析业务 body，减少未授权解析成本。
- 路由表显式注册方法/路径/body 上限和恢复状态权限；未知路径 404，路径存在但方法错 405 + `Allow`。
- JSON DTO 使用明确字段；禁用 Jackson default typing，不直接反序列化领域实体或 map 到 SQL。
- 普通 JSON body 1 MiB；上传 512 MiB，流式进入隔离临时文件，hash/格式失败后清理自身临时文件。
- HTTP handler 使用固定大小有界 executor；队列满返回 429，不能在接收线程执行长 I/O。
- 一个 mutation 对应一个 application use case；HTTP 层不开始嵌套业务事务。
- 响应发送前完成错误脱敏；已开始 streaming 后的失败只记录 request ID，不能改成伪成功 JSON。

## 4. 请求状态与失败行为

```text
RECEIVED → HOST/ORIGIN_CHECKED → AUTHENTICATED → ROUTED → BODY_VALIDATED
         → APPLICATION_RUNNING → RESPONSE_READY → SENT
```

- 任一步校验失败不调用 application、不写数据库。
- mutation 在 application 前检查幂等键；已完成同请求直接回放，冲突返回 409。
- 客户端断开：尚未进入 application 可取消；已进入事务则由 application 完成提交/回滚并保存 operation 结果。
- Java 正在恢复/迁移：未允许业务路由返回 423；live 仍 200，status 返回结构化恢复状态。
- 服务生命周期由受控启动进程管理；Java shutdown hook 进入 draining、释放数据库与本机端口。关闭浏览器标签不代表服务停止。

## 5. DTO、数据库与接口映射

- transport 字段与 [全局 API](../api.md) 完全一致；HTTP DTO→application command/query 的转换逐字段显式实现。
- `Idempotency-Key` 映射 `processed_operation.idempotency_key`；`X-Request-Id` 仅进入日志/错误。
- `If-Match` 映射 application `expectedRevision`，不作为数据库查询字符串直接拼接。
- `meta.dataRevision` 映射 `ledger_meta.data_revision`。
- active profile 来自 application context，不从普通请求 body/query/header读取。
- 具体财务资源映射见 [ledger-records API](../api/ledger-records-api.md) 和相应模块。

## 6. 可观测、验收与测试

事件字段：request ID、方法、规范路由模板、status、duration、body byte count、active profile 短 hash、error code；不记录 URL 中未知 query、认证、body、备注或文件名正文。

验收：

- 非 loopback bind 配置、无效会话/CSRF、重复路由、资源 manifest 缺失均 fail closed。
- start 前 stdout 没有 readiness；start 成功后、首个请求前恰好输出一行并 flush，后续请求和并发访问不重复输出。
- live 无认证仅返回固定 UP；status 缺少有效浏览器会话时 401，正确会话返回 API/application/schema/capability。
- 非法 JSON、超限、错误 content type、未知方法/路径均有规定状态且零业务调用。
- 同幂等键同请求返回相同状态/body；不同请求 409；重开数据库后仍可查询结果。
- 两个 GET 可并行；写事务按 application 规则串行；队列满返回 429 而非挂死。
- Vue 静态资源只从 manifest allowlist 读取，拒绝 `..`、编码绕过和未知文件；HTML/hashed assets 缓存头正确。
- 真实浏览器使用同源 Cookie 可访问，缺失/错误会话、跨源、错误 Host 或缺失/错误 CSRF 均不能读取或改变账本。

建议测试：路由/校验单元、HTTP 黑盒合同、真实临时 SQLite 集成、恶意 path/body 安全测试、普通浏览器真实握手与页面刷新/重开。

## 7. 冲突、待决与升级

- JDK `HttpServer` 没有完整框架级路由/body/error设施，允许实现最小项目专用适配，不允许抽象成通用框架。
- 如果备份下载/上传无法可靠流式或取消，升级比较维护中的 Java 11 小型 HTTP 库；不得先引入 Spring。
- 新增 CORS、远程绑定、WebSocket、长期 token 或角色会改变全局安全模型，必须由高级模型裁决。
