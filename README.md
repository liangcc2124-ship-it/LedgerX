<div align="center">
  <img src="assets/ledgerx-custom-v2.png" width="112" alt="LedgerX icon" />
  <h1>LedgerX</h1>
  <p>把财务指标放到桌面上。钱没有变多，但至少终于知道它去哪了。</p>
</div>

当前版本：v2.0.0

LedgerX 是一款面向个人使用的 Windows 桌面财务管理软件。它不连接银行卡、不上传云端、不要求注册，也不会在你买完奶茶后弹出一句“请反思消费观”。你只需手动记账，它负责把记录变成现金、成本、资产、负债和资金链指标。

![LedgerX v2.0 财务总览](docs/design/ledgerx-webview-v2.png)

## 为什么做它

很多记账软件以“记了多少笔”为中心，LedgerX 更关心“这些记录说明了什么”。收入、固定成本、可变成本、固定资产、应付账款和 Runway 会被放到同一张仪表盘上——因为账户余额只告诉你今天，而资金链通常已经在偷偷讨论下个月。

## 功能

- **指标优先**：现金储备、净现金流、总资产、资金链、固定成本、可变成本、固定资产、应付账款和 Runway。
- **自定义指标**：创建金额、数值或百分比指标，并按日期记录增加与减少。
- **自动联动**：一笔现金流入会同步更新现金储备、净现金流和总资产，不必拿计算器进行二次创作。
- **隐私模式**：全部金额或单个指标均可隐藏。适合公共场合，也适合暂时不想面对现实的下午。
- **财务分析**：根据已有记录计算成本结构、财务依赖度与资金状态。
- **本地优先**：数据只保存在当前电脑，没有账号、订阅费和“云端惊喜”。
- **备份恢复**：通过 JSON 文件导出和恢复账本。
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
dotnet publish native\LedgerX.Native.csproj -c Release -o release-native
~~~

发布结果位于 `release-native\LedgerX.exe`。

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
