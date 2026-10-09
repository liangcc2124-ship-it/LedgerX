# P4-005 基础记账真实桌面验证记录

状态：PASS。已验证三类基础记录、负债账户、精确余额变化、编辑、取消删除、回收/恢复、重开、profile 切换、Java 子进程退出和数据库只读核验。

## 验证命令与结果

- `electron`：在受控主机、未设置 `ELECTRON_DISABLE_SANDBOX` 的默认沙箱下执行 `npm exec --yes -- playwright test tests/p4-005-basic-ledger.integration.spec.mjs --reporter=line --timeout=60000`：1/1 通过，22.6 秒。
- 测试通过真实 Electron 可见 UI 在临时隔离目录中创建分类、BANK `1000.00`、CREDIT `0.00`，创建收入 `100.25`、固定支出 `20.10`、弹性支出 `30.05` 和信用卡支出 `40.00`，验证 `1050.10`/`40.00`，编辑为 `25.10` 后验证 `1045.10`，取消一次删除，再完成回收/恢复；关闭后用同一目录重启，切换到新空间确认无记录，再切回原空间确认记录和余额仍在；确认隔离目录存在 `profiles.db`。
- 新建记录的真实链路曾发现 Vue 未生成 `id`，修正为 `createRequestId()` 后由 Java 真实 HTTP/SQLite 路径重验通过。自定义分类的 `canUseForRecords` 和 merge target 判定也同步修正为允许 active 自定义分类。
- 扩展验收记录 Java 子进程 PID `51040`、`43404` 均在退出后消失；两个隔离 profile ledger 只读快照分别为：空 profile `total/active/trashed=0/0/0, operations=0, dataRevision=0`；原 profile `4/4/0, operations=10, dataRevision=10`。`processed_operation` 总数与 distinct key 相同。

## 未覆盖与限制

- 未覆盖 stale ETag 的真实桌面注入；Java/HTTP 层已有并发/ETag 测试，后续 P5 集成继续覆盖真实页面冲突反馈。
- 当前嵌套沙箱仍会触发 Electron renderer crash；受控主机默认 sandbox 运行已通过，因此不以嵌套沙箱结果替代真实验收。
- 本次追加生成并验证 Windows x64 Electron 发布包：解包版启动冒烟 1/1；安装器已生成但未执行系统级安装/卸载回归。现有桌面 `LedgerX.lnk` 已更新到最新解包版，未创建重复快捷方式。
