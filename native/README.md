# LedgerX Native

LedgerX 的 Windows 原生版本，基于 WPF 与 .NET 10，不包含 Electron、Chromium 或 WebView。

## 本地开发

```powershell
dotnet build LedgerX.Native.csproj
dotnet run --project LedgerX.Native.csproj
```

正式数据默认保存在 `%LocalAppData%\LedgerX\ledger.json`。皮肤支持内置主题与安全 CSS 变量导入。

## 皮肤变量

可导入 `:root` 中的 `--lx-background`、`--lx-surface`、`--lx-sidebar`、`--lx-primary`、`--lx-primary-hover`、`--lx-text`、`--lx-muted`、`--lx-border`、`--lx-income`、`--lx-cost`、`--lx-warning`、`--lx-selected`、`--lx-card-radius` 与 `--lx-font-family`。其他 CSS 内容不会执行。
