# P7-004 本机浏览器入口与会话迁移验证

- 状态：PASS（本任务验收）；不代表正式发行就绪
- 本机网页版验收：PASS
- 日期：2026-09-23
- 数据：真实浏览器 E2E 和启动脚本冒烟均使用全新系统临时目录；未读写默认 `%LOCALAPPDATA%\LedgerX`。测试账本已清理，截图证据保留在系统临时目录。
- 环境：Windows PowerShell 7.6.6、Java 11.0.15.1、Playwright 1.63.0 / Chromium headless，桌面视口 1280×900；全量 UI 回归另覆盖 320px、200% 缩放和键盘焦点。
- Node：本机验证使用 Node 24.19.0/npm 11.17.0，符合 Vite 所需版本范围；项目不再要求某个特定补丁版本。

## 结果

- Java 定向：`LedgerHttpServerTest` 8/8、`ApplicationBootstrapTest` 3/3、`DataDirectoryLockTest` 1/1。覆盖浏览器会话 Cookie 属性、Host/Origin、401/403、CSRF、静态资源根目录强制配置、数据目录优先级/隔离路径和独占锁获取/释放。
- 前端：`npm test` 的 Vite 配置 7/7、浏览器 API client 单测 4/4、Playwright 44/44；Vite production build 成功。Dashboard/metrics 定向 4/4。320px、200% 缩放、键盘可达性、导航和错误恢复均在既有用例中覆盖。
- 启动器：实际运行 `scripts/start-local.ps1`，Maven 用户目录在首次 Maven 调用前指向仓库 `.maven-home`，完成 Vue 构建、Maven package、Java 随机端口启动；普通 Chromium 请求页面为 HTTP 200，标题 `LedgerX`，连接状态 READY，可进入“收支记录”。使用同一隔离数据根启动第二个 Java 进程，第二个进程在数据库打开前被独占锁拒绝。控制台与 `pageerror` 均为 0。发出停止信号后确认监听端口关闭、独占锁可再次获取，临时数据根清理完成。Windows 上 JVM 对 Ctrl+C 返回状态码 1，脚本现在以说明提示处理；非该状态码仍作为错误报告。
- 真实数据闭环：运行 `node frontend/scripts/local-browser-e2e.mjs`，由真实 Chromium → Java 同源 HTTP → 隔离 SQLite 完成：会话 Cookie 为 HttpOnly/SameSite=Strict/Path=/ 且 JS 不可读；创建 12.30、刷新重读、编辑为 13.50、停止并重启 Java 后重读、删除到回收站、恢复；最终余额 13.50 CNY。4 次写操作均携带 CSRF 且无 Authorization；页面异常、控制台错误/警告均为 0。
- 该 E2E 使用独立 Java 子进程，不经 Electron、Vite proxy 或固定成功的 mock。`scripts/start-local.ps1` 另行通过真实服务与浏览器做启动冒烟。

## 额外发现与修复

真实浏览器启动时捕获到 GridStack 在 Vue Teleport 卡片内容挂载前执行自动高度测量，产生 `firstElementChild is null` 控制台错误。现改为关闭初始化期自动测量，并在渲染帧后对已挂载卡片显式测量；Dashboard 浏览器测试加入控制台断言，现已通过。P5-007 仍是 PARTIAL，因为真实鼠标拖拽/缩放和 P5-008 性能验收未完成。

## 未通过/未覆盖项

- 全量 Maven 套件仍有既存记录测试失败：部分测试使用固定日期 2026-09-14，而 V004 默认账户开户日按数据库执行日生成（本次为 2026-09-23），导致 `SETTLEMENT_BEFORE_OPENING` 冲突。涉及 `RecordApplicationServiceTest` 和 `RecordsHttpServerTest`，属于 [P7-002](../tasks/P7-002-ledger-initialization-account-opening.md) 已识别的领域初始化根因；本次未通过放宽校验或改写财务日期规则规避。P7-004 定向 Java 测试及真实浏览器数据闭环通过。
- 没有在 22.18.0 Node 精确运行时测试；见上方环境记录。
- 未进行 20,000 条数据性能采样、真实鼠标拖拽/缩放验收或离线分享包验证；性能样本仅在实际需要时执行，分享包不属于当前范围。
- 本机网页版验收：PASS（同源 Java + SQLite 隔离目录；见 2026-09-24 P7-003 复核）。旧 Bearer 分支已从活动 Java 服务、Vite 代理、Vue 页面桌面桥接入口和构建依赖移除；归档 Electron 实现与历史报告不计入现行门禁。

## 可复核命令

```powershell
# Java 定向验证（Maven 用户目录需可写；本次使用仓库隔离 Maven home）
$env:MAVEN_OPTS = '-Duser.home=D:\Project\ledgerX\.maven-home'
.\mvnw.cmd -q '-Dmaven.compiler.fork=true' '-Dtest=LedgerHttpServerTest,ApplicationBootstrapTest' test

# 前端完整构建与测试
cd frontend
npm test
cd ..

# 真实浏览器 → Java → SQLite 全生命周期
node frontend/scripts/local-browser-e2e.mjs

# Windows 正式源码启动入口
.\scripts\start-local.ps1
```

真实浏览器截图保存在本轮系统临时证据目录，不写入仓库。个人本机使用状态、下一任务顺序和限制见 [任务路线图](../tasks/README.md)；P7-002 与 P7-003 的本机基线已完成验证。
