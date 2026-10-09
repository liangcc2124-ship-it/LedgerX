# LedgerX 本机浏览器架构

- 状态：当前架构；P7-002、P7-004 与 P7-003 本机自用基线已通过隔离真实链路验证
- 日期：2026-09-24
- 决策：[ADR-014 本机浏览器运行](./decisions/ADR-014-local-browser-only.md)；旧 Electron 设计见 [历史归档](./architecture-electron-legacy.md)

## 1. 规模与边界

LedgerX 是小型、单设备、单操作者、离线优先的个人账本。继续采用 Java 11 模块化单体、Vue 3 和 SQLite；没有独立扩缩容、多人协作或云服务需求。业务计算、日期、事务和迁移只在 Java；Vue 只负责交互与展示。每个 ledger 通过 V006 的 PENDING/REVIEW_REQUIRED/COMPLETED 状态先完成显式初始化或历史确认，再开放普通业务能力；设计见 [ADR-015](./decisions/ADR-015-ledger-initialization.md)。

```text
系统浏览器（Vue 静态资源）
          │ 同源 HTTP /api/v1；短期会话 + CSRF
          ▼
Java 11 本机服务（127.0.0.1:随机端口）
  ├─ HTTP：静态资源、会话、路由、DTO、错误
  ├─ application：用例、事务、幂等、状态
  ├─ domain：金额、日期、指标、公式等规则
  └─ persistence：JDBC、SQLite、迁移、健康检查
                          │
                          ▼
                 %LocalAppData%\LedgerX
```

Java 是唯一数据写者和唯一生产 HTTP 进程。系统浏览器是客户端，不承担 Java 进程管理。关闭浏览器页面不等于停止服务；服务需提供明确的退出方式，并在退出时释放数据库资源。首次启动可打开系统默认浏览器，无法打开时输出可复制的本机 URL；不能将秘密放在 URL 中。

## 2. 运行与部署

- Java 只绑定 `127.0.0.1` 随机端口，生产同源提供 Vue 构建产物和 `/api/v1`；不开启 CORS，不绑定 LAN/公网。HTTP `Host` 必须匹配实际地址和端口，变更请求的 `Origin` 必须精确同源。
- 数据根默认为 `%LocalAppData%\LedgerX`，隔离测试可用 `LEDGERX_TEST_DATA_DIR` 覆盖。正式启动入口在打开数据库前对数据根取得操作系统级独占锁；同一目录已有服务时第二个进程明确拒绝启动，停止或崩溃后锁由操作系统释放。锁文件本身保留为空，不代表服务仍运行。
- Java 可单独构建和运行；Vue/Vite 可单独开发。Vite 开发代理只转发浏览器同源请求，不提供 Bearer 注入；正式使用时 Vue/API 必须同源，构建产物纳入同一个 Java 服务。
- Java 启动传输就绪与账本业务就绪是不同状态。浏览器通过已认证的 `GET /api/v1/system/status` 判断 `STARTING`、`READY`、恢复或失败；不可把端口已监听当作可记账。
- 当前只支持个人本机运行，需预装 Java 11 与前端构建用 Node.js；不制作离线分享包、安装器或桌面快捷方式。将来确实需要分享时再单独设计分发与升级方式。

## 3. 本机浏览器安全边界

目标会话由 Java 在本机同源页面启动时建立：不透明短期标识置于 host-only、`HttpOnly; SameSite=Strict; Path=/` Cookie，Vue 不读取会话标识。单独的 CSRF token 经同源 bootstrap 传给 Vue，仅变更请求附带 `X-LedgerX-CSRF`。会话和 CSRF 值不得进入 URL、长期存储或日志。Java 校验会话、CSRF、Host、Origin，拒绝跨源/非 loopback；前端静态响应设置合适 CSP。`/health/live` 仅返回不含敏感信息的存活信息。

本机浏览器会话、CSRF 与 Host/Origin 负例由 [P7-004](./tasks/P7-004-local-browser-runtime.md) 实现，并通过 Java HTTP 单测及隔离数据目录中的真实浏览器链路验证。账本初始化和账户日期规则由 [P7-002](./tasks/P7-002-ledger-initialization-account-opening.md) 实现并验证。旧 Bearer 模式已从活动 Java 服务、开发代理和构建路径移除，仅归档代码和历史记录仍可见。个人自用主流程已通过；尚未实现的增强能力、自动备份和分享包不代表已完成。

## 4. 数据与业务契约

- Vue 调用相对 `/api/v1`，遵循 [全局 API 规范](./api.md) 和 `docs/api/` 的模块接口；不读取 SQLite、不复制 Java 业务规则。
- `application` 编排领域与持久化，HTTP 和 SQLite 实现都不得渗入 `domain`。schema 变化使用版本化迁移，迁移需在隔离数据副本上验证无数据丢失、幂等和可重载。
- 用户空间、账本初始化、分类、账户、三类基础记录、指标/公式/总览仍按 [需求](./requirements.md) 及相应 ADR/模块文档推进。初始化状态与账户/记录日期由 Java 校验；历史迁移不改金额或记录。浏览器化不改变余额方向或旧数据规则。
- 基础版手工备份须先停止 Java 服务，再复制整个数据目录；P9 的应用内备份/恢复若实施，必须由 Java 负责一致性快照和原子恢复，不允许浏览器直接指定本机任意路径。

## 5. 验证和演进

按变更范围验证 Vue 交互、HTTP 契约、Java 领域/SQLite 和迁移；跨层能力至少一次通过真实系统浏览器→Java→SQLite 完成新建、重载、编辑、删除/恢复与结果核对。测试用隔离数据；不直接操作个人账本。mock、Vite 开发代理或旧 Electron 冒烟均不能替代真实运行链路。性能压测只在数据规模增长或真实卡顿时安排。

仅当真实规模、独立部署或运维需求出现时才重新评估单体边界；不预建微服务、消息系统、通用插件或桌面壳。
