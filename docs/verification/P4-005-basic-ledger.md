# P4-005 基础记账真实桌面验证记录

状态：PARTIAL。已验证三类基础记录、负债账户、精确余额变化、编辑、取消删除、回收/恢复、重开和 profile 切换；未满足进程退出和数据库只读核验的完整门槛。

## 验证命令与结果

- `electron`：`ELECTRON_DISABLE_GPU=1 ELECTRON_DISABLE_SANDBOX=1 node node_modules/@playwright/test/cli.js test tests/p4-005-basic-ledger.integration.spec.mjs --reporter=line --timeout=60000`：1/1 通过。
- 测试通过真实 Electron 可见 UI 在临时隔离目录中创建分类、BANK `1000.00`、CREDIT `0.00`，创建收入 `100.25`、固定支出 `20.10`、弹性支出 `30.05` 和信用卡支出 `40.00`，验证 `1050.10`/`40.00`，编辑为 `25.10` 后验证 `1045.10`，取消一次删除，再完成回收/恢复；关闭后用同一目录重启，切换到新空间确认无记录，再切回原空间确认记录和余额仍在；确认隔离目录存在 `profiles.db`。
- 新建记录的真实链路曾发现 Vue 未生成 `id`，修正为 `createRequestId()` 后由 Java 真实 HTTP/SQLite 路径重验通过。自定义分类的 `canUseForRecords` 和 merge target 判定也同步修正为允许 active 自定义分类。

## 未覆盖与限制

- 未验证 stale ETag 的真实桌面注入、完整退出进程核验和 Electron 场景下的 SQLite 只读行级核对；Java/SQLite 层已覆盖余额方向和行级重载核验，真实 UI 已覆盖 profile 隔离切换。
- 真实 Electron 运行使用 GPU/沙箱禁用环境以绕过当前嵌套运行器 renderer crash；正式受控主机需按任务要求用默认 sandbox 重验。
- 本次追加生成并验证 Windows x64 Electron 发布包：解包版启动冒烟 1/1；安装器已生成但未执行系统级安装/卸载回归。现有桌面 `LedgerX.lnk` 已更新到最新解包版，未创建重复快捷方式。
