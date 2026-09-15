# LedgerX Electron 安全壳

本目录只负责 Electron main/preload、Java 子进程生命周期、loopback session 认证注入和最小桌面错误页；业务和 SQLite 仍由 Java 后端负责。

当前 S0-004 依据 ADR-006 获得临时开发豁免：精确 Forge 6.4.2 的官方 `npm audit` 结果为 18 high / 1 critical；隔离评估的维护版 Forge 7.11.2 仍为 19 high / 1 critical。可在隔离环境执行 `npm ci` 和 Electron 集成，但正式发布前必须完成豁免复审或依赖修复，详见 [ADR-006](../docs/decisions/ADR-006-electron-forge-6-4-2-audit.md)。

BrowserWindow 固定使用 `nodeIntegration:false`、`contextIsolation:true`、`sandbox:true`。启动前会禁用硬件加速并启用进程内 GPU 回退，以兼容无可用 GPU 的主机；这不会关闭 renderer 沙箱。嵌套工具沙箱可能无法创建 Windows restricted token，需在受控主机权限下执行默认 `npm run test:e2e`；诊断用 `--no-sandbox` 不属于验收证据，详见 [ADR-007](../docs/decisions/ADR-007-electron-sandbox-runtime.md)。

```powershell
npm ci
npm test
npm run test:integration
npm start
```

Windows x64 封装使用当前 Maven JAR、运行时依赖和最新 Vue 构建资源：

```powershell
Set-Location ..
.\mvnw.cmd -q '-Dmaven.compiler.fork=true' package
Set-Location frontend
npm run build
Set-Location ..\electron
npm run make:win
```

产物位于 `electron/out/make`；正式包要求目标 Windows 已安装 Java 11 或兼容的 Java 运行时，数据仍写入 `%LOCALAPPDATA%\LedgerX`。

默认 Java 入口为随应用资源提供的 `java/ledgerx-desktop.jar`，运行时依赖位于 `java/lib`，Vue 资源根目录为 `resources/web`。集成测试通过明确的 `LEDGERX_TEST_*` 环境变量提供固定 Java 可执行文件、参数、隔离数据目录和 `frontend/dist`，这些变量不会进入 renderer。

在源码工作区执行 `npm start` 时，如果尚未生成打包资源，Electron 会在检测到 `target/classes`、`target/cp.txt` 和 `frontend/dist` 后自动使用它们启动本地 Java 服务；普通 Maven `package` 生命周期会生成 `target/cp.txt`，不需要额外运行 dependency goal。正式打包由 `prepare:package` 将应用 JAR、运行时依赖和 `frontend/dist` 放入 `java/ledgerx-desktop.jar`、`java/lib` 与 `resources/web`，再由 Forge 生成产物。正常 Windows 桌面会话的数据根固定为 `%LOCALAPPDATA%\LedgerX`；只有隔离测试可以用 `LEDGERX_TEST_DATA_DIR` 覆盖，继承的通用 `LEDGERX_DATA_DIR` 不会改变桌面会话。BUG-001 已完成默认根接线和真实回归，但完整基座仍须由 BUG-002、BUG-003 后的 BUG-004 重验。首次源码启动的计划前置仍是：

```powershell
.\mvnw.cmd -q '-Dmaven.compiler.fork=true' test package
Set-Location frontend; npm ci; npm run build; Set-Location ..
Set-Location electron; npm ci; npm start
```

如果 Java 或前端构建产物缺失，窗口会显示启动失败页；完成上述构建后点击“重试连接”或重新启动即可。

`npm run test:integration` 是 S0-006 真实集成验收，覆盖隔离 SQLite 首启/重开、第二实例、刷新、认证边界、Java kill/retry、旧 token 失效和父进程异常清理；需在支持 Windows restricted token 的受控主机权限下运行。
