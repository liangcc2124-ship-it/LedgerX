<div align="center">
  <img src="assets/ledgerx-custom-v2.png" width="112" alt="LedgerX icon" />
  <h1>LedgerX</h1>
  <p>把财务指标放到桌面上。钱没有变多，但至少终于知道它去哪了。</p>
</div>

当前版本：v3.0.0

发布说明：[LedgerX v3.0 构建文档](docs/LedgerX-v3.0-构建文档.md)

LedgerX 是一款面向个人使用的 Windows 桌面财务管理软件，专注于把手动记录变成现金、成本、资产、负债和资金链指标。
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

## 技术栈

- C# / .NET 10（账本、指标计算、本地文件与系统窗口）
- WPF + Microsoft WebView2（轻量原生宿主）
- React + TypeScript + Vite（完整可见界面）
- 本地 JSON 持久化
- 无 Electron、无云端、无在线账号系统

## 下载与运行

在仓库的 [Releases](../../releases) 页面下载 `LedgerX.exe`。它是 Windows x64 自包含单文件版本，无需单独安装 .NET。

首次启动后，数据默认保存在：

```text
%LocalAppData%\LedgerX\ledger.json
```

删除程序不会自动删除账本；毕竟软件可以重装，账单不能假装没发生。

## 从源码构建

需要 Windows 10/11、Node.js 20+ 与 .NET 10 SDK。系统需安装 Microsoft Edge WebView2 Runtime（Windows 11 默认已包含）。

~~~powershell
cd web
npm install
npm run build
cd ..
dotnet build native\LedgerX.Native.csproj
dotnet run --project native\LedgerX.Native.csproj
dotnet test native.tests\LedgerX.Tests.csproj -c Release
dotnet publish native\LedgerX.Native.csproj -c Release -o release-native
~~~

发布结果位于 `release-native\LedgerX.exe`。前端 Playwright 回归测试可用 `cd web; npm test` 运行，原生指标、预警、报告和备份单测可用上面的 `dotnet test` 运行。

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