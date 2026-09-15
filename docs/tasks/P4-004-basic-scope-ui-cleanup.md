# P4-004：基础版功能入口与说明收敛

- 状态：PARTIAL（基础说明页已实现；真实 Electron 可达性由 P4-005 验收）
- 单一结果：Vue 只向用户展示已实际可用的基础能力；未执行的通知、自动备份、安全缓冲、主题文件及高级财务功能不再以可操作控件出现，并提供准确的手工备份说明。

## 1. 背景、目标、范围与非目标

P2 设置页保存了若干“未来策略”，但通知、自动备份、主题文件和安全缓冲计算没有执行器。ADR-010 要求基础版不宣称这些能力已可用。本任务只修正用户界面与说明，不删除数据库字段或 API。

范围：SettingsPage 的基础版展示、手工备份说明、可用的打开数据目录动作、首页/导航文案、对应浏览器测试和文档。

非目标：删除 settings API/schema、实现通知/备份/主题、恢复文件、改变 preload、改记录/分类/账户功能、UI 全面重设计、金融安全功能。

## 2. 前置依赖与引用规范

- 前置：P4-003 PASS；P2/P3 行为仍通过。
- 必读：全局 AGENTS；[ADR-010](../decisions/ADR-010-basic-ledger-scope.md)；[需求 §3、§5–§7](../requirements.md)；[架构 §6.3、§8](../architecture.md)；[settings 模块](../modules/settings-preferences.md)；P2-003；P4-003。
- 现有 settings REST/SQLite 保留为兼容数据，不因隐藏控件物理删除或更改默认值。

## 3. 允许/预计修改的模块和文件

- `frontend/src/components/SettingsPage.vue`。
- `frontend/src/App.vue`：仅基础版说明/首页文案需要的最小调整。
- `frontend/tests/settings-page.spec.js`、`frontend/tests/application-shell.spec.js`。
- `docs/modules/settings-preferences.md`、`docs/modules/ui-integration.md`、README 中实际可见功能说明、本任务和 `docs/verification/P4-004-basic-scope-ui.md`。

禁止修改 Java、electron/preload、apiClient、数据库/migration、package manifests、RecordsPage/CatalogPage、旧 WPF/React。

## 4. 实现步骤与必须遵守的行为

1. SettingsPage 基础版不再提供通知、自动备份开关/间隔/保留数、安全缓冲、lastSettingsSection、自定义主题文件或“上次自动备份”的可编辑/状态控件。不得把“仅保存策略”继续包装成用户功能。
2. 设置页改为简洁说明页：当前版本为本地单用户基础记账；数据默认位于 `%LocalAppData%\LedgerX`；备份必须完全退出应用、确认 Java 进程结束后复制整个 LedgerX 目录；恢复前先保留当前目录，再整体替换；不要在运行中只复制 ledger.db。
3. 若现有 `window.desktop.openDataFolder` 可用，可提供“查看数据目录”按钮；按钮旁明确“仅用于定位，退出应用后再复制”。bridge 缺失、返回 `{ok:false}` 或抛错时显示普通错误，不虚构成功，不新增 IPC。
4. SettingsPage 不再需要 GET/PATCH settings 时，应移除该页面的请求、表单和 operation 重试代码；这不改变 server capability，也不删除 P1/P2 测试。若保留任何读取，只能用于真实展示且失败有明确反馈，不能只是保持旧代码。
5. 首页和导航不显示资产、指标、报表、预警、自动备份或迁移入口；基础引导只指向“收支记录”“分类与账户”“用户空间”和“设置/备份说明”。
6. 页面保持标题焦点、键盘操作、aria-live 反馈、常规和 320px 可读布局。

## 5. 用户操作、前端反馈、跨层与结果

| 用户操作 | 前端反馈 | 跨层数据 | 业务/持久化结果 | 边界 |
| --- | --- | --- | --- | --- |
| 打开设置 | 看到准确范围与备份步骤 | 无业务请求 | 无写入 | 浏览器无 desktop bridge |
| 查看数据目录 | 成功无夸大提示；失败给出重试建议 | 既有 `openDataFolder()` | 不修改账本 | bridge 缺失/false/异常 |
| 浏览首页导航 | 只出现已交付入口 | 无高级 API | 无写入 | 320px、键盘焦点 |

## 6. 验收标准

- SettingsPage 不存在通知、自动备份、保留份数、安全缓冲、自定义主题文件、上次自动备份等误导性可操作元素。
- 页面逐条写明“完全退出、复制整个目录、恢复前保留当前副本、不要运行中单拷 ledger.db”；不宣称加密、自动或可在线恢复。
- openDataFolder 只在 bridge 能力存在时显示/启用；成功、false、抛错和普通浏览器场景均有可判断测试。
- 首页/导航没有高级功能入口；P3/P4 已实现入口仍可达且焦点正确。
- frontend npm test 通过；没有任何 settings DB/API 删除或行为变化。

## 7. 测试层次、命令与真实链路

- Vue 浏览器：设置页文案、隐藏控件、bridge 可用/不可用/失败、导航、320px。
- 回归：profiles、catalog、records 和启动状态相关前端测试随 `npm test` 一并运行。
- 必跑：`frontend` 目录当前本机 Node 执行 `npm test`，报告版本和数量。
- 真实 Electron 的 openDataFolder 在 P4-005 只做可达性验证；数据复制/恢复不得自动执行。Java/SQLite mutation、发布包和快捷方式不适用。

## 8. 禁止事项、升级、文档与完成报告

禁止删除 settings API/schema、实现文件复制/恢复、添加任意路径 IPC、声明数据加密/自动备份、修改记录功能或借机重写全局 UI。

若现有 bridge 不包含 openDataFolder，不新增它，只隐藏按钮并报告；若必须改变 preload/API/database/architecture，停止升级。

完成报告写：修改文件；移除的误导入口；保留的基础入口；设置页浏览器测试数量；bridge 模拟与真实可达性区分；无持久化改动；发布包/快捷方式不适用；未测项、风险和文档同步。
