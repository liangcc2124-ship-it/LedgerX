# LedgerX Vue 前端基座

本目录是新的 Vue 3/JavaScript/Vite 前端基座，独立于旧的 `web/` React 实现。

```powershell
npm ci
npm run dev
npm run build
npm test
```

## 启动正式本机网页版

在 Windows PowerShell 7 中安装 Java 11+ 与满足 Vite 版本要求的 Node.js。首次准备或修改代码时安装锁定依赖、运行回归并构建：

```powershell
cd frontend
npm ci
npm test
cd ..
.\scripts\build-web.ps1
.\scripts\start-local.ps1
```

日常使用时只运行 `scripts/start-local.ps1`。Java 会在同一 loopback origin 上提供 Vue 页面与 `/api/v1`，并尝试打开系统浏览器。首次打开新账本时，先确认账本起始日和默认账户期初余额；历史账本若需校正，会先展示待确认账户日期。关闭标签页不会停止 Java 服务，使用启动终端中的 `Ctrl+C` 退出。当前只面向个人本机运行，不制作安装器或分享包；简单构建与验收约定见 [P7-003](../docs/tasks/P7-003-reproducible-release-gate.md)。

网页版沿用旧版的工作台布局：左侧为 LedgerX 导航栏，右侧为宽内容区；READY 后可从导航进入财务总览、指标与公式、用户空间、分类与账户、收支记录和设置。财务总览只使用 `/api/v1/dashboard` 返回的日/周/月/年数据；编辑布局时才生成本地草稿，使用精确锁定的 `gridstack@13.3.0` 官方 Vue 包装层完成鼠标拖动与缩放，保存使用 layout ETag 和幂等键。指标页通过结构化 AST 发送中文函数提示，支持创建/编辑/归档、系统指标显示设置和版本历史，不在浏览器执行财务计算。收支记录的新增和编辑使用弹窗表单，取消或关闭不会写入数据。

设置页可为当前用户空间创建、查看、校验和下载 SQLite 一致性备份；Java 会先完成数据库与文件完整性检查，再显示为可用。备份同时保存在本机数据目录的 `Backups/<用户空间 ID>/`，也可以下载到浏览器选择的位置。备份文件**没有加密**，请将下载副本保存在可信的独立位置。当前版本不提供恢复、自动备份或自动清理；不要直接复制运行中的 `ledger.db`。

`npm run dev` 的 Vite Bearer 代理只留作迁移期开发/兼容测试，不是正式产品入口或验收路径；正式运行必须使用 Java 同源服务及浏览器 Cookie/CSRF 会话，详见 [P7-004](../docs/tasks/P7-004-local-browser-runtime.md)。

生产构建不创建开发代理，也不会把凭据写入 bundle；使用 self-only CSP，默认不生成 source map。Profiles、Catalog 与 Records 的读取/写入通过 `src/composables/useAsyncResource.js` 和 `useApiMutation.js` 统一处理竞态、cursor 分页、15 秒超时、同幂等键重试和 operation 查询；创建、编辑与确认操作使用 `src/components/common/AppDialog.vue`，统一焦点圈、Escape、背景滚动锁和焦点恢复。指标编辑器使用 `FormulaNodeEditor.vue` 递归维护 external AST，`ReferencePicker.vue` 提供指标/分类/账户/时间引用，`FormulaVersionHistory.vue` 按游标读取历史；validate/preview 的字段错误会定位到 AST 节点且不执行浏览器端求值。Dashboard 只有具备 `dashboard.layout.write` 时才显示布局写入口，非编辑网格使用 GridStack 公共 `sizeToContent`/`resizeToContent` 自动容纳卡片内容，期间导航锚点使用服务端返回值。应用壳 hash 白名单为 `home|records|catalog|metrics|profiles|settings`，页面枚举显示统一由 `src/presentationMaps.js` 转为中文，未知值保留原值诊断。`npm test` 会先运行 Node 配置测试，再构建并运行浏览器回归；SQLite 集成 E2E 由 `node scripts/local-browser-e2e.mjs` 从项目根目录运行。
