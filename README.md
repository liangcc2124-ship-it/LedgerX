<div align="center">
  <img src="assets/ledgerx-custom-v2.png" width="112" alt="LedgerX icon" />
  <h1>LedgerX</h1>
  <p>把财务指标放到桌面上。钱没有变多，但至少终于知道它去哪了。</p>
</div>

当前版本：v2.1.0

发布说明：[LedgerX v2.1 正式迭代需求文档](docs/LedgerX-v2.1-正式迭代需求文档.md)

LedgerX 是一款面向个人使用的 Windows 桌面财务管理软件，专注于把手动记录变成现金、成本、资产、负债和资金链指标。
## v2.1.0 已完成

这一版把 LedgerX 从“能记账”推进到“能复盘、能解释、能预警”：

- 指标卡支持进入详情，查看添加过的记录、趋势、计算公式和贡献明细。
- 报告工作区提供日报、周报、月报、年报，配套现金流、成本结构、收入来源和数据表。
- 预警中心支持阈值、同比/环比变化、连续周期、AND 条件、冷却时间和自定义生效期。
- 记录支持编辑、软删除、撤销、恢复、永久删除和收入来源批量补充。
- 数据管理支持版本化备份、SHA-256 校验、恢复预览、保护快照、清空账本和恢复出厂。
- 金额隐私会覆盖指标、图表、表格、报告、预警和导出视图。

LedgerX 仍然是单机本地应用：不登录、不联网同步、不读取银行卡，也不会在你月底余额不足时假装这是一次“成长体验”。

![LedgerX v2.1 财务总览](docs/design/ledgerx-webview-v2.png)

## 为什么做它

很多记账软件以“记了多少笔”为中心，LedgerX 更关心“这些记录说明了什么”。收入、固定成本、可变成本、固定资产、应付账款和 Runway 会被放到同一张仪表盘上——因为账户余额只告诉你今天，而资金链通常已经在偷偷讨论下个月。

## 功能

- **指标优先**：现金储备、净现金流、总资产、资金链、固定成本、可变成本、固定资产、应付账款和 Runway。
- **自定义指标**：创建金额、数值或百分比指标，并按日期记录增加与减少。
- **自动联动**：一笔现金流入会同步更新现金储备、净现金流和总资产，不必拿计算器进行二次创作。
- **隐私模式**：全部金额或单个指标均可隐藏。适合公共场合，也适合暂时不想面对现实的下午。
- **财务分析**：日报、周报、月报、年报与可视化报表，支持周期比较和下钻。
- **指标详情**：点击指标即可查看构成数据、趋势、公式和来源记录。
- **记录纠错**：记录可编辑，删除后进入回收站，可恢复或永久删除。
- **设置中心**：数据、备份、皮肤、通知和诊断日志统一收纳，首页保持清爽。
- **本地优先**：数据只保存在当前电脑，没有账号、订阅费和“云端惊喜”。
- **安全备份**：备份包含版本、内容摘要和 SHA-256 完整性校验；恢复前先预览，恢复与清空前自动留一份保护快照。
- **可换肤**：自带暖铜、石墨、深海蓝皮肤，也支持安全的 CSS 变量主题。

## 技术栈

- C# / .NET 10（账本、指标计算、本地文件与系统窗口）
- WPF + Microsoft WebView2（轻量原生宿主）
- React + TypeScript + Vite（完整可见界面）
- 本地 JSON 持久化
- 无 Electron、无云端、无账号系统

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
- 不提供云同步和多账户登录。
- 自定义指标目前为手动累计型；复杂公式编辑器留给后续版本。

## 参与贡献

欢迎提交 Issue 和 Pull Request。修 Bug、补测试、改善无障碍体验都很有价值；如果只是想把按钮改成荧光绿，请先深呼吸十秒。

## License

[MIT](LICENSE)