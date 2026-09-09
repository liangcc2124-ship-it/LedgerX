# LedgerX Desktop Host

LedgerX 使用 WPF 提供 Windows 窗口、生命周期、文件选择和本地存储，完整可见界面由 React + TypeScript 运行在系统 WebView2 中。它不是 Electron：不会随应用再打包一整套 Chromium。

## 本地开发

先构建 Web 界面，再构建原生宿主：

~~~powershell
cd ..\web
npm install
npm run build
cd ..\native
dotnet build LedgerX.Native.csproj
dotnet run --project LedgerX.Native.csproj
~~~

正式数据默认保存在 %LocalAppData%\LedgerX\ledger.json。自定义皮肤只接受 --lx-* CSS 变量，并拒绝远程资源和可执行表达式。
