# BUG-004：S0 默认启动与安全链路重新验收

- 状态：已实施；用户授权的 Node `v24.19.0` 环境通过，Node 22 基线仍未认证
- 分类：集成验收、证据修复与文档状态恢复
- 优先级：S0 完成门禁

## 1. 背景、目标、范围与非目标

此前 S0-006 的 Java/Vue/Electron/SQLite 隔离链路有价值，但它通过
`LEDGERX_TEST_DATA_DIR` 掩盖了默认数据根缺失，且网络监听和启动状态 UI 证据不完整。因此 S0-006 的 PASS 曾被撤回；本任务现已补充可复现的默认启动、安全和状态证据。

目标：在记录的 Node `22.18.0` 基线或本次用户明确批准的 Node `v24.19.0`、JDK 11、Windows 受控主机上，以真实 sandbox Electron 重新确认
`Electron → Vue → loopback REST → Java → JDBC/SQLite → 重读 → Vue`；所有数据仅存在于本次隔离目录。

范围：更新/运行集成测试与验收记录，验证 BUG-001 至 BUG-003 的端到端结果和源代码启动前置条件。非目标：修复运行时代码、改 API/DDL、迁移真实数据、生成正式安装包、签名、快捷方式或放宽任何断言。

## 2. 前置依赖与允许修改

依赖：BUG-001、BUG-002、BUG-003 的验收全部 PASS，且对应文档已同步。Node 24 仅因本次用户明确授权而可执行；结果必须标记为非 Node 22 基线证据。任一前置失败或使用 `--no-sandbox`，本任务不得通过。

允许/预计修改：

- `electron/tests/s0-006.integration.spec.mjs` 与仅为其隔离/观察服务的测试 helper；
- `docs/verification/S0-006-electron-rest.md`、`docs/tasks/S0-006-electron-rest-integration.md`、`docs/tasks/README.md`；
- 成功后才可更新根 `README.md`、`docs/requirements.md`、`docs/architecture.md`、`CHANGELOG.md` 的 S0 状态。

不得修改 `electron/main.js`、Vue/Java/SQLite、公共 API、依赖/lockfile、旧 WPF/React。发现代码缺陷时停止并新建/升级独立 Bug Spec。

引用：[requirements §6/§7/§8](../requirements.md)、[architecture §5/§8/§10](../architecture.md)、[database §1/§2](../database.md)、[api §1/§3/§13](../api.md)、[desktop-shell](../modules/desktop-shell.md)、[S0-006](./S0-006-electron-rest-integration.md)、[BUG-001](./BUG-001-default-electron-startup-data-root.md)、[BUG-002](./BUG-002-startup-status-lifecycle.md)、[BUG-003](./BUG-003-vite-loopback-proxy.md)。

## 3. 固定验收环境与链路

1. 在执行前记录 `node --version`；记录 JDK/Maven 版本，Java bytecode 仍为 55。本次用户已明确批准当前 `v24.19.0`，因此可执行，但报告必须明确 Node 22.18.0 未验证，不得把结果称为 Node 22 基线通过。
2. 构建 Java 后断言本次 `package` 生成 `target/cp.txt`；构建 frontend dist。测试的 Electron 启动不得传 `LEDGERX_TEST_*` 或普通 `LEDGERX_DATA_DIR`，不得传测试 Java args/web root。
3. 为实际 launch 设置新建临时 `LOCALAPPDATA`，并以 Electron CLI `--user-data-dir` 隔离 Chromium 用户数据。临时根必须先解析并确认不等于 workspace、home 或实际 `%LocalAppData%\LedgerX`；只在 finally 中删除这个已确认目录。
4. 在取得第一个 BrowserWindow 前，在 Electron `BrowserContext` 注册请求观察器；测试必须观察到首个 Java-origin document/navigation request。若观察器未看到该请求，测试以“首导航未被观察”失败，不能把后续请求记录当作无外联证据。
5. 记录所有 renderer 请求直到首屏 READY 和一次刷新结束。允许的正常网络仅是 `http://127.0.0.1:<本次 Java port>/…`；任何其他 `http/https/ws/wss` origin、非 loopback host 或未观察到首 document 都失败。静态扫描不能替代该运行时断言。
6. 读取真实已认证 status 与 UI：首启必须最终显示“本地服务已连接”和 READY，持久化字段为 schema 1、UUID、revision 0。验证 `profiles.db` 与 profile `ledger.db` 都位于临时 `%LOCALAPPDATA%\LedgerX`，关闭/重开后 profile UUID 不变、history 不重复。
7. 保留既有第二实例、10 次刷新、错误 token/origin、后端 kill/用户 retry、旧 token、退出/父进程清理测试；其状态/进程断言不能被新的默认启动测试替换。
8. BUG-002 的 STARTING→READY、deadline、RECOVERY_REQUIRED、401 和缩放/键盘场景由其 browser tests 提供明确证据；本任务引用并重新运行它们，但不故意破坏真实 SQLite 来伪造 recovery。
9. BUG-003 的开发 proxy rejection/sentinel 测试由其 `npm test` 提供明确证据；本任务不请求任何远程 host 来“试探”泄漏。

## 4. 必跑命令与结果记录

在符合版本和受控 Windows sandbox 能力的主机，以各项目的 lockfile 运行：

1. 根目录：Java 11 `mvnw.cmd -q -Dmaven.compiler.fork=true test package`，再检查本次 `target/cp.txt`。
2. `frontend/`：`npm ci`、`npm test`（包括 BUG-002/BUG-003 的配置、build、browser tests）。
3. `electron/`：`npm ci`、`npm run test:unit`、`npm run test:e2e`、`npm run test:integration`（含默认 source launch 回归）。

默认 Electron 验收必须保持 `sandbox:true`。受控主机权限不足以创建 Windows restricted token 时，标记为环境阻塞；诊断性的 `--no-sandbox` 不能计入任何通过数量。

## 5. 可逐项判定的验收标准

| 项 | 通过条件 |
| --- | --- |
| 默认数据根 | 无测试数据变量的真实源码 launch 首启/重开均为 READY，并只在隔离 `%LOCALAPPDATA%\LedgerX` 创建/重读 SQLite。 |
| 启动状态 | READY 之前不出现“已连接”；STARTING 有界，恢复状态无伪成功，401/坏 response 不被当作成功。 |
| token 与网络 | renderer 无 token；首导航前开始的请求记录完整，所有请求为本次 IPv4 loopback origin；错误 token/origin 仍被 Java 拒绝。 |
| 生命周期 | 单实例/刷新不重生 Java；kill 后人工 retry 恰建一个新会话；退出和父进程异常后无本任务 Java orphan。 |
| 构建与版本 | 记录实际 Node 版本（本次为用户批准的 v24.19.0）、JDK 11，所有指定命令真实通过，Maven package 自生成 classpath；Node 22 基线另行复测。 |
| 文档 | 仅全部项目通过后，中心 README/requirements/architecture/changelog 与 S0 task/verification 可恢复为“基础基座已验收”；内容仍明确非正式安装包/未迁移财务业务。 |

## 6. 失败、禁止与升级

- 任一项目失败、跳过、环境不匹配、未观察首导航、使用 mock 代替真实链路、未清理隔离数据或无法证明目录范围，均是 FAIL/BLOCKED，不得写 PASS。
- 不得为让测试通过而临时传 `LEDGERX_TEST_DATA_DIR`、隐藏网络事件、放宽 loopback/state/token 断言、修改生产代码或删除失败日志。
- 若所需首导航观察无法使用现有 Playwright/Electron public API 稳定实现，停止并形成一个最小调查记录：说明观察器注册时点、缺失的事件和已尝试的无副作用方案；再升级高级模型决定是否增加受控观测边界。不得猜测“没有看到即没有请求”。
- 若发现真实已有数据的迁移/兼容问题、API/DDL变化、或者 Node 22 与固定依赖不兼容，停止并升级相应高级设计；不在验收任务中修代码。

## 7. 完成报告

固定报告：PASS/FAIL/BLOCKED；环境版本；每层命令/通过数量；真实 Electron PID/端口的脱敏证据；默认数据根首启/重开结果；首导航网络观察结果；状态/安全/生命周期矩阵；隔离目录创建与清理；发布包/快捷方式“不适用”；未测试项、残余风险与所有升级点。失败报告必须保留精确失败验收项，不以“构建通过”总结。

## 8. 实际验收报告（2026-09-13）

- 结果：**PASS（用户授权 Node 24；Node 22 基线未认证）**。
- 环境：Node `v24.19.0`、Java `11.0.15.1`、Maven Wrapper `Apache Maven 3.9.11`、Windows 11 x64；Electron 仍使用 `sandbox:true`，未使用 `--no-sandbox`。
- Java：`MAVEN_OPTS=-Duser.home=C:\Users\liang .\mvnw.cmd -q '-Dmaven.compiler.fork=true' test package`，12/12 通过；本次 `target/cp.txt` 存在。
- 前端：`npm ci` 在 Node 24 下完成（仅 engine warning；默认缓存首次有权限错误，使用同一机器缓存的受控提升权限重试成功）；`npm test` 中配置测试 7/7、Vite production build 成功、Playwright 11/11 通过。浏览器插件不可用，使用项目 Playwright 流程。
- Electron：`npm ci` 完成，审计结果 18 high / 1 critical，与 ADR-006 已知风险一致；`npm run test:unit` 10/10、`npm run test:e2e` 2/2、`npm run test:integration` 2/2 通过。E2E 首次因 Electron 缓存目录 EPERM 失败，提升权限重跑通过；集成首次因嵌套权限出现 Target crashed，未计入通过；受控提升权限重跑后通过，仍未关闭 sandbox。
- 真实链路证据：集成测试使用临时 `LOCALAPPDATA`、`--user-data-dir` 和隔离 SQLite；首导航监听器在 `firstWindow()` 前注册，观察到首个 Java-origin document，并在 READY/刷新后断言 renderer 请求均为本次 `127.0.0.1` origin。输出记录首会话 `firstPid=44736`、`retryPid=38952`、`firstPort=49246`、`restartedPort=57699`、renderer 请求 46 次；默认启动端口 64003、请求 9 次、profile 重开保持一致。测试 finally 清理临时根。
- 默认数据：无 `LEDGERX_TEST_*`/普通 `LEDGERX_DATA_DIR` 的启动在隔离 `LOCALAPPDATA\LedgerX` 创建 `profiles.db` 和 profile `ledger.db`，首启/重开 READY、schema 1、UUID 不变、revision 0；继承的普通数据目录未被使用。
- 安全与生命周期：renderer 不可访问 token/Node/Electron 内部对象；错误 token/origin 分别返回 401/403；第二实例不重复启动 Java；kill/retry 建立新会话；旧 token 对新端口失效；异常父进程清理后无 Java orphan。
- 未验证/残余风险：Node `22.18.0` 基线尚未复测，不能宣称两个 Node 版本等价；验收后机器仍有测试前已存在且未归属的 Java 进程，未擅自终止，集成测试只证明本次记录的 Java 子进程退出；Forge 6.4.2 的 18 high / 1 critical 仍是正式发布门禁；正式安装包、签名、快捷方式和财务领域迁移不属于本任务。
