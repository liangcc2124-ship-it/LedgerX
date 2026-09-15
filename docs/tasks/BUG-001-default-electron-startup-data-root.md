# BUG-001：默认 Electron 启动未把数据目录交给 Java

- 状态：已实施；BUG-001 验收通过，当前 Node 24 授权环境整体 S0 已由 BUG-004 重验通过
- 分类：桌面启动接线、数据根和源码启动前置产物
- 优先级：阻塞 S0 基座验收

## 1. 已确认诊断

### 稳定复现

在已构建 `target/classes`、`frontend/dist` 的源码工作区中，清除所有
`LEDGERX_TEST_*`、`LEDGERX_DATA_DIR` 后执行 `electron` 的正常 `npm start` 路径。主进程会记录
`backend.source_fallback` 和 `backend.spawned`，但最终记录 `renderer.loaded` 的状态为 `STARTING`；
用于本次受控复现的新用户数据根下没有 `profiles.db`。这不是网络、Java 路径或 renderer 沙箱故障。

### 症状、直接原因与根因

| 层次 | 已确认事实 |
| --- | --- |
| 用户可见症状 | 默认启动没有创建 SQLite catalog，且后续 UI 会收到 `STARTING`；BUG-002 还会把它错误显示为“已连接”。 |
| 直接原因 | `electron/main.js` 的 `startDesktop()` 计算了 `runtime.dataDir`，但 `spawnBackend(config, token)` 只在 `config.dataDir` 非空时设置 Java 的 `LEDGERX_DATA_DIR`。默认 `config.dataDir` 为 `null`，所以该环境变量没有传给 Java。 |
| Java/application | `ApplicationBootstrap.fromEnvironment()` 在环境变量缺失时返回 `InitialSystemStatusProvider`，其 status 固定为 `STARTING`；不会执行 `ProfileBootstrap`。 |
| 持久化 | 未调用 bootstrap，因而不创建 `%LocalAppData%\LedgerX\profiles.db`、默认 profile 或 `ledger.db`。 |
| 根因 | Electron 配置对象与运行时数据根有两个不一致的事实来源；同时错误地把 Electron `userData` 下的 `data` 当作默认数据根，与已接受的 `%LocalAppData%\LedgerX` 文件布局不一致。S0 集成测试总是传入 `LEDGERX_TEST_DATA_DIR`，掩盖了默认路径。 |

选型依据：Electron 官方文档说明 Windows 的 `appData` 默认是 `%APPDATA%`，`userData` 又以应用名附加在其下，且不建议将大文件写在该目录；因此本任务不把它作为 SQLite 的规范根，而是执行既有数据库规范定义的 `%LOCALAPPDATA%\LedgerX`。[Electron app paths](https://www.electronjs.org/docs/latest/api/app#appgetpathname)

### 链路追踪

`OS/source npm start` → Electron 生成 token、计算但未传递 `runtime.dataDir` → Java 子进程缺少
`LEDGERX_DATA_DIR` → `InitialSystemStatusProvider(STARTING)` → 已认证
`GET /api/v1/system/status` 返回 `200/STARTING` → 当前 main 加载 Vue → 当前 Vue 错误地显示成功。

本 bug 的持久化闭环在 Java bootstrap 前中断；没有历史数据写入或迁移发生。BUG-002 单独修复最后一段展示语义。

## 2. 目标、范围与非目标

目标：无测试专用配置的 Windows 源码启动和未来打包启动，均向 Java 传入同一个、确定的有效数据根；首次启动完成既有 V001 bootstrap 并以 `READY` 打开 UI。

范围：Electron 数据根解析/子进程环境接线、源码 classpath 生成、针对默认启动的真实回归测试，以及受影响的运行说明与历史验收状态。

非目标：修改 REST DTO、SQLite DDL/迁移、财务业务、迁移或扫描任意旧目录、改变 Java 直接启动时读取 `LEDGERX_DATA_DIR` 的能力、安装器/签名、Node 或依赖升级。

## 3. 已固定契约

1. Windows 正常桌面会话的唯一规范数据根为 `%LOCALAPPDATA%\LedgerX`，即 `path.resolve(LOCALAPPDATA, 'LedgerX')`；不得使用 `%APPDATA%`、`app.getPath('userData')` 或其下的 `data` 子目录代替。
2. `LEDGERX_TEST_DATA_DIR` 是唯一允许覆盖 Electron 数据根的测试变量。它必须解析为绝对路径，且测试自身必须证明其位于本次临时根内。正常 Electron 父进程不得把继承的通用 `LEDGERX_DATA_DIR` 当作用户设置或覆盖来源。
3. 每次 `spawn` 前，Electron 必须先解析一次 `effectiveDataDir`，将**同一绝对路径**写入：
   - `runtime.dataDir`（固定“打开数据目录”的目标）；
   - Java 子进程的 `LEDGERX_DATA_DIR`。
   复制父进程环境时必须先移除继承的 `LEDGERX_DATA_DIR`，再写入该值，不能因父进程环境而漂移。
4. 若在 Windows 无法取得有效的绝对 `LOCALAPPDATA`，且没有有效测试覆盖，必须在 spawn 前进入现有本地失败页；日志只记录稳定错误原因/代码，不记录绝对路径、token 或完整环境。不得回退到未定义位置。
5. Java 的既有语义不变：收到有效目录时 bootstrap 生成/重开 catalog 和默认 profile，status 为 `READY`；Java 被单独启动时仍可按既有方式读取 `LEDGERX_DATA_DIR`。
6. 源码 fallback 所需的 `target/cp.txt` 必须由普通 Maven `package` 生命周期生成。将现有 `maven-dependency-plugin` 的 classpath goal 绑定到 `package`，输出 `${project.build.directory}/cp.txt`，内容覆盖 `HttpServerMain` 所需的 compile/runtime JAR；不新增 Maven 依赖。手工另跑 dependency goal 不再是源码启动的隐含前置条件。
7. 补齐既有 desktop-shell 规定的启动阶段事件：`desktop.start`（解析完成、spawn 前）、`backend.listening`（受限 readiness 已验证）、`backend.status_checked`（已认证 status 已校验，记录枚举 state）和既有 `backend.start_failed`/`renderer.loaded`。事件只含阶段、脱敏 PID/port、耗时、枚举 state 或稳定错误码；绝不含 token、完整目录、环境或 response body。
8. Electron 的 session token、web root、loopback 端口、API 路径、安全 BrowserWindow 设置和 API/DDL 均不得改变。

## 4. 允许修改的文件与依赖

前置依赖：无；本任务必须在 BUG-002、BUG-004 之前完成。

允许/预计修改：

- `electron/main.js`、`electron/tests/unit.test.mjs`、`electron/tests/s0-006.integration.spec.mjs`；
- `pom.xml`；
- `electron/README.md`、根 `README.md`；
- `docs/modules/desktop-shell.md`、`docs/tasks/S0-004-electron-shell.md`、`docs/tasks/S0-006-electron-rest-integration.md`、`docs/verification/S0-006-electron-rest.md`，以及本任务的实际报告。

引用规范：[requirements §6.1/§7](../requirements.md)、[architecture §5/§8/§10](../architecture.md)、[database §2](../database.md)、[api §1/§3](../api.md)、[desktop-shell](../modules/desktop-shell.md)、[S0-004](./S0-004-electron-shell.md)、[S0-006](./S0-006-electron-rest-integration.md)。

不得修改 `src/main/java/**`、DDL、共享 API fixture、旧 `native/` 或 `web/`。

## 5. 实施步骤

1. 将 Electron 的后端配置与有效数据根分开：配置只保留测试覆盖，启动路径只使用一次解析出的 `effectiveDataDir`。为可单测的路径解析传入环境/平台，不要在测试中改写全局 `process.env` 或实际用户目录。
2. 在 `spawnBackend` 的 child env 中无条件写入有效目录；对不存在/无效目录的失败在 spawn 前处理，避免产生一个必然 `STARTING` 的 Java 子进程。
3. 保持 `openDataFolder()` 对该同一目录工作；它不得接受 renderer 路径或通过日志泄漏路径。
4. 在 Maven `package` 绑定 classpath 文件生成。输出文件为空、缺少 sqlite/Jackson runtime 依赖或不能被 Windows Java classpath 使用时，构建视为失败。
5. 把 Electron README 的源码路径改为一段可直接执行的顺序：根目录 Maven `test package`（其后已有 `target/cp.txt`）、前端 `npm ci && npm run build`、Electron `npm ci && npm start`。明确这是源码开发路径，不是正式包；构建产物缺失时仍显示失败页。
6. 为上述启动阶段事件增加顺序/脱敏断言；失败和成功路径都必须能关联本次启动，而不是只记录最终 `renderer.loaded`。
7. 在模块/历史验收文档中把数据根契约改为“正常会话始终传入规范根；仅测试可覆盖”，并保留旧隔离测试证据但不把它写成默认启动证据。

## 6. 验收标准

| 前置与操作 | 可观察结果 |
| --- | --- |
| Windows、没有 `LEDGERX_TEST_*` 和 `LEDGERX_DATA_DIR`，给临时 `LOCALAPPDATA`，从源码产物启动 Electron | Java 收到的目录恰为 `<临时 LOCALAPPDATA>\LedgerX`；已认证 status 为 `READY`、当前 ledger V004 的 `schemaVersion=4`、非空 UUID `activeProfileId`、`dataRevision=0`。 |
| 同一临时 `LOCALAPPDATA` 关闭并重开 | 仍为同一 profile UUID；catalog history 不重复，DB 可重读。 |
| 上述首次启动 | 存在 `<临时 LOCALAPPDATA>\LedgerX\profiles.db` 和对应 profile 的 `ledger.db`；不把 DB 写到工作区、用户 home 或实际 `%LocalAppData%`。 |
| 父环境包含任意 `LEDGERX_DATA_DIR`，未设置测试覆盖 | Electron 仍选择规范 `%LOCALAPPDATA%\LedgerX`；不能继承到 Java。 |
| `LEDGERX_TEST_DATA_DIR` 指向隔离绝对目录 | 只在测试路径使用该目录，且既有 S0-006 隔离测试继续通过。 |
| Windows 默认根不可解析 | 未 spawn Java、显示既有本地失败页、无 token/路径泄漏。 |
| 成功与失败启动日志 | 分别包含规定的阶段事件和合法顺序；不含 token、完整数据根、完整环境或 HTTP body。 |
| 新建干净构建目录只运行 Maven `package` | 生成非空 `target/cp.txt`；随后不另跑 dependency goal 即可满足源码 fallback。 |

## 7. 测试与真实链路

必须运行：

1. Maven Java 11 `test package`，并断言本次 `package` 生成非空 `target/cp.txt`；在隔离/干净构建环境验证，不能用工作区遗留文件冒充。
2. Electron 单元测试：测试覆盖优先级、绝对路径校验、Windows 规范根、拒绝缺失根，以及 child env 使用同一路径且不继承通用变量。
3. 真实 Electron 源码回归：使用真正 Electron、Java `HttpServerMain`、JDBC/sqlite-jdbc 和 Vue dist；不传 `LEDGERX_TEST_*`、不传通用 `LEDGERX_DATA_DIR`，以临时 `LOCALAPPDATA` 和 Electron CLI `--user-data-dir` 隔离。验证首启和重开。默认 `sandbox:true`，不得以 `--no-sandbox` 替代。
4. 既有 `npm run test:integration`，确认其显式隔离变量仍只服务测试。

测试结束只删除本次创建、已解析并确认位于临时根内的目录；报告真实链路与模拟/单元链路分开统计。

## 8. 风险、兼容性、禁止与升级

- 当前 S0 未发布数据根迁移；不得复制、扫描、删除或自动导入 `%APPDATA%`、继承变量指向目录或任何用户选择路径。
- 若发现已有受支持用户数据实际位于旧 `%APPDATA%`/继承目录，或产品需支持便携/非 Windows 数据根，停止并升级高级模型：这需要明确迁移、备份和回滚设计。
- 若打包资源布局使 `package` 生成 classpath 方案不可行，或需要引入 runtime 打包器/新依赖，停止并升级；不得临时下载 JAR 或改用 `shell:true`。
- 禁止改 API/DDL、token 传递方式、renderer 权限、Java bootstrap 语义、测试用 `--no-sandbox`、或用固定 `READY` mock 掩盖问题。

## 9. 完成报告与文档同步

报告必须列出：实际选定目录（仅可写测试相对/脱敏描述）、首启/重开 profile 结果、Maven classpath 生成证据、各层通过数量、真实 sandbox 链路、隔离目录清理、未测项和残余风险。完成后只更新本任务直接影响的运行说明；不得在 BUG-002 至 BUG-004 完成前把 S0-006 恢复标记为 PASS。
