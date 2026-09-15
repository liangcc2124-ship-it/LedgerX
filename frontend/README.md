# LedgerX Vue 前端基座

本目录是新的 Vue 3/JavaScript/Vite 前端基座，独立于旧的 `web/` React 实现。

```powershell
npm ci
npm run dev
npm run build
npm test
```

## 在浏览器中运行真实网页版

网页版复用 Java/SQLite 本地服务。先在项目根目录构建 Java，并在一个 PowerShell 窗口启动后端：

```powershell
$env:LEDGERX_SESSION_TOKEN = node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
$env:LEDGERX_DATA_DIR = "$env:TEMP\LedgerX-Web"
java -cp ".\target\classes;$(Get-Content -Raw .\target\cp.txt)" com.ledgerx.http.HttpServerMain
```

记录后端输出的 `LEDGERX_READY` 端口和启动时生成的 token，在第二个 PowerShell 窗口启动前端代理：

```powershell
$env:LEDGERX_DEV_API_ORIGIN = 'http://127.0.0.1:<后端端口>'
$env:LEDGERX_DEV_API_TOKEN = '<上一个窗口的 token>'
Set-Location frontend
npm run dev -- --host 127.0.0.1 --port 5173
```

然后打开 `http://127.0.0.1:5173/`。两项服务都只绑定本机回环地址，数据目录可替换为独立的验收目录；退出后端和前端窗口即可停止网页版。

网页版沿用旧版的工作台布局：左侧为 LedgerX 导航栏，右侧为宽内容区；READY 后可从导航进入首页、用户空间、分类与账户、收支记录和设置。页面只展示本地服务实际返回的状态，不在首页填充虚构的财务指标。收支记录的新增和编辑使用弹窗表单，取消或关闭不会写入数据。

开发代理只从 Node 环境读取 `LEDGERX_DEV_API_ORIGIN` 和 `LEDGERX_DEV_API_TOKEN`，并在代理层注入认证。origin 必须是带显式端口的 `http://127.0.0.1:<1..65535>`（可有一个末尾 `/`），token 必须是 43 位 `[A-Za-z0-9_-]`；两者都为空时不创建代理，不能使用 `localhost`、远程 URL、`.env` 提交内容或 `VITE_*` 变量。代理键固定为 `/api/v1`，Vue bundle 不读取 token、端口或 Electron IPC。

生产构建不创建开发代理，也不会把这些变量写入 bundle；使用 self-only CSP，默认不生成 source map。`npm test` 会先运行不发起网络请求的 Node 配置测试，再构建并运行浏览器回归。
