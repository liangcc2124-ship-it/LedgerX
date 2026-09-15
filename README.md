<div align="center">
  <img src="assets/ledgerx-custom-v2.png" width="112" alt="LedgerX icon" />
  <h1>LedgerX</h1>
  <p>把财务指标放到桌面上。钱没有变多，但至少终于知道它去哪了。</p>
</div>

当前版本：v3.1.0

> **重构状态（2026-09-14）**：当前可下载版本仍是下文的 WPF + React/TypeScript v3.1。Vue 3 + Electron + Java 11 + SQLite 基座已完成 V004 seed、基础分类/账户/三类收支记录的 Java/HTTP 链路，以及记录页、目录管理页和基础备份说明页；Java 全量 41/41、前端配置 7/7、Playwright 35/35 已通过，真实 Electron 基础冒烟 2/2、目录新建/重开 1/1、扩展基础记账场景 1/1 已通过，现行目录设置验收 1/1 已通过。P3/P4 仍有完整异常矩阵、Electron 层 child PID/SQLite 只读核验和默认 sandbox 环境证据待补，暂不称为已发布基础记账版。按 [ADR-010](docs/decisions/ADR-010-basic-ledger-scope.md)，资产/分摊、指标/公式、报表/预警、旧数据导入和应用内备份恢复不在本轮范围；基础版退出应用后复制整个 `%LocalAppData%\LedgerX` 目录进行手工备份。详见[需求](docs/requirements.md)、[架构](docs/architecture.md)、[API](docs/api.md)和[实施顺序](docs/tasks/README.md)。

发布说明：[LedgerX v3.1 基础功能迭代构建文档](docs/LedgerX-v3.1-基础功能迭代构建文档.md)

LedgerX 是一款面向个人使用的 Windows 桌面财务管理软件，专注于把手动记录变成现金、成本、资产、负债和资金链指标。
## v3.1.0 已完成

本次迭代把基础数据链路接通，并针对实际使用中的表单和总览交互做了收敛：

- 新空间提供主流生活、数字服务、资产和负债分类；历史自定义分类保留，新增分类在当前用户空间全局可用。
- 周期成本按日、周、月、年自动计算服务结束日；弹窗滚动锁定背景，日期和按钮在窄屏可用。
- 总览卡片支持鼠标移动/缩放，金额与标题会随卡片宽度换行；固定资产显示原值、累计折旧和当前账面价值。
- 自定义指标可选择归集记录；没有指标时数值记录仍可独立保存。公式数据源支持自定义指标和期间时间变量。
- 新增指标保存后立即进入当前指标列表和总览布局，并在重启后保留；公式会按指标依赖顺序计算并拒绝坏引用或循环。
- “本期摊销成本”按周期记录的服务期间自动计算，不再要求手工归集；固定资产处置支持取消确认和撤销。

## v3.0.0 已完成

这一版把 LedgerX 从“能记账”推进到“能按规则理解财务事实”：

- 指标采用模板库与可视化公式构建器；分类、资金账户和自定义指标可被记录直接选择。
- 固定成本可按服务期按日、周、月、年归属；历史补录可明确设为期初余额、应付款或不影响现金。
- 固定资产支持直线法、工作量法、双倍余额递减法、年数总和法与自定义公式入口。
- 记录、分类、账户和自定义指标支持批量管理；总览卡片支持调整位置、大小并恢复默认。
- 本地用户空间拥有独立账本、分类、账户、指标和布局；第二次启动会激活已打开窗口。
- 金额隐私会覆盖指标、图表、表格、报告、预警和导出视图。

LedgerX 仍是本地优先应用：用户空间不等于云账号，不联网同步、不读取银行卡。

![LedgerX 财务总览](docs/design/ledgerx-webview-v2.png)

## 为什么做它

很多记账软件以“记了多少笔”为中心，LedgerX 更关心“这些记录说明了什么”。收入、固定成本、可变成本、固定资产、应付账款和 Runway 会被放到同一张仪表盘上——因为账户余额只告诉你今天，而资金链通常已经在偷偷讨论下个月。

## 功能

- **指标优先**：现金储备、净现金流、总资产、资金链、固定成本、可变成本、固定资产、应付账款和 Runway。
- **可配置指标**：模板、结构化公式和直接归集并存；模板只提供参考，不锁死计算口径。
- **自动联动**：一笔现金流入会同步更新现金储备、净现金流和总资产，不必拿计算器进行二次创作。
- **隐私模式**：全部金额或单个指标均可隐藏。适合公共场合，也适合暂时不想面对现实的下午。
- **财务分析**：日报、周报、月报、年报与可视化报表，支持周期比较和下钻。
- **指标详情**：点击指标即可查看构成数据、趋势、公式和来源记录。
- **记录纠错**：记录可编辑，删除后进入回收站，可恢复或永久删除。
- **设置中心**：数据、备份、皮肤、通知和诊断日志统一收纳，首页保持清爽。
- **本地用户空间**：每个空间的数据独立保存；没有在线账号、订阅费和“云端惊喜”。
- **安全备份**：备份包含版本、内容摘要和 SHA-256 完整性校验；恢复前先预览，恢复与清空前自动留一份保护快照。
- **可换肤**：自带暖铜、石墨、深海蓝皮肤，也支持安全的 CSS 变量主题。

## 当前已发布技术栈

- C# / .NET 10（账本、指标计算、本地文件与系统窗口）
- WPF + Microsoft WebView2（轻量原生宿主）
- React + TypeScript + Vite（完整可见界面）
- 本地 JSON 持久化
- 无 Electron、无云端、无在线账号系统

## 目标重构技术栈（当前使用本机 Node 24 环境）

- Vue 3 + Vite + HTML/CSS/JavaScript：新代码位于计划中的 `frontend/`，可独立开发和测试。
- Electron：仅负责 Windows 窗口、安全边界和 Java 子进程生命周期。
- Java 11：本机模块化单体后端，提供只绑定 `127.0.0.1` 的版本化 REST API。
- SQLite：每个用户空间一个账本数据库；Java 是唯一写者。
- 一个安装包、一个用户入口；不引入云端、微服务或消息系统；首期不考虑 PDF 导出。
- 基础版只提供分类、账户、收入、固定支出、弹性支出、余额、编辑和回收站；不加入金融合规、角色、审批、加密或审计体系。

## 下载与运行

在仓库的 [Releases](../../releases) 页面下载 `LedgerX.exe`。它是 Windows x64 自包含单文件版本，无需单独安装 .NET。

首次启动后，数据默认保存在：

```text
%LocalAppData%\LedgerX\ledger.json
```

删除程序不会自动删除账本；毕竟软件可以重装，账单不能假装没发生。

## 从源码构建

以下命令只适用于当前 v3.1 WPF/React 发布版本；Vue/Electron/Java 基座的验证命令见下方独立小节，不能把两套构建链混用为同一个发布产物。

需要 Windows 10/11、Node.js 20+ 与 .NET 10 SDK。系统需安装 Microsoft Edge WebView2 Runtime（Windows 11 默认已包含）。

~~~powershell
cd web
npm install
npm run build
cd ..
dotnet build native\LedgerX.Native.csproj
dotnet run --project native\LedgerX.Native.csproj
dotnet test native.tests\LedgerX.Tests.csproj -c Release
dotnet publish native\LedgerX.Native.csproj -c Release -o release-native-v3.1
~~~

发布结果位于 `release-native-v3.1\LedgerX.exe`。如需更新桌面快捷方式，可运行 `powershell -ExecutionPolicy Bypass -File native\Create-DesktopShortcut.ps1`。前端 Playwright 回归测试可用 `cd web; npm test` 运行，原生指标、预警、报告和备份单测可用上面的 `dotnet test` 运行。

### Vue/Electron/Java 基座验证

目标基座的独立验证命令如下；这些命令验证源码和隔离测试链路，不生成正式安装包：

```powershell
cd frontend
npm ci --registry=https://registry.npmjs.org
npm test
cd ..
$env:MAVEN_OPTS='-Duser.home=C:\Users\liang'
.\mvnw.cmd -q '-Dmaven.compiler.fork=true' test package
cd electron
npm ci --registry=https://registry.npmjs.org
npm test
npm run test:integration
```

Electron 测试需在能创建 Windows restricted token 的受控主机权限下执行；不得用 `--no-sandbox` 代替验收。完整范围和证据见 [S0-006 验证记录](docs/verification/S0-006-electron-rest.md)。

普通 Maven `package` 生命周期会自动生成源码 Electron fallback 所需的 `target/cp.txt`；无需另行执行 `dependency:build-classpath`。默认桌面数据根为 `%LOCALAPPDATA%\LedgerX`，仅隔离测试使用 `LEDGERX_TEST_DATA_DIR` 覆盖。

## 自定义皮肤

LedgerX 只读取 CSS 中的 `--lx-*` 主题变量，不执行选择器、脚本或远程资源。可配置背景、表面、侧栏、主色、文字、边框、收入、成本、警告、选中状态、圆角和字体。

示例：

```css
:root {
  --lx-background: #F7F6F3;
  --lx-surface: #FFFDFC;
  --lx-primary: #9A6A3A;
  --lx-text: #1D1D1F;
  --lx-card-radius: 12px;
}
```

## 当前边界

- 仅支持 Windows x64。
- 采用手动记账，不自动读取银行、微信或支付宝数据。
- 不提供云同步或跨设备在线登录；本地用户空间用于逻辑隔离。
- 自定义公式为受限的结构化表达式，不支持任意脚本、网络或文件访问。

## 参与贡献

欢迎提交 Issue 和 Pull Request。修 Bug、补测试、改善无障碍体验都很有价值；如果只是想把按钮改成荧光绿，请先深呼吸十秒。

## License

[MIT](LICENSE)
