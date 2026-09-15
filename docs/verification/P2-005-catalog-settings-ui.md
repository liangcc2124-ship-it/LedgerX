# P2-005 用户空间与设置真实桌面集成验证记录

- 日期：2026-09-14
- 任务：[P2-005](../tasks/P2-005-catalog-settings-ui-integration.md)
- 结果：PASS（本机 Node 24.19.0 受控权限；真实 Electron sandbox 开启）

## 验证范围

新增 `electron/tests/p2-005-catalog-settings.integration.spec.mjs`，使用唯一隔离临时数据根和真实 Java child process，通过 Electron renderer 的 UI 完成完整流程：

1. READY 后创建“家庭账本”与“工作账本”，确认后者 ACTIVE、前者 INACTIVE。
2. 在工作空间保存安全缓冲 `1234.50 CNY` 与自动备份间隔 `14` 天。
3. 切回家庭空间确认默认 `3000.00` 与 `7`，再切回工作空间确认修改值仍在。
4. 在家庭空间对工作空间执行二次确认归档；确认前无 DELETE，归档后默认列表隐藏，筛选后显示 ARCHIVED，UI 无恢复/物理删除入口。
5. 关闭并重开 Electron/Java，确认家庭空间仍 ACTIVE、工作空间仍 ARCHIVED，家庭设置未混入工作空间。

## 结果证据

| 验收项 | 结果 | 证据 |
| --- | --- | --- |
| P2-005 真实 E2E | PASS | 1/1；输出 `profileIsolation=true archived=true reopened=true rendererRequests=41` |
| Java 全量测试与打包 | PASS | Maven Surefire 31/31；退出码 0 |
| Vue 全量测试 | PASS | `frontend/npm test`：36/36 浏览器测试通过，配置 7/7 |
| Electron 单元与基础 E2E | PASS | `electron/npm test`：单元 10/10、真实 E2E 2/2 |
| S0-006 真实集成回归 | PASS | `electron/npm run test:integration`：2/2；已同步 ledger V004 `schemaVersion=4` 断言 |
| 认证与 renderer 安全 | PASS | 无 token 401、错误 Origin 被拒绝；`require/process/ipcRenderer/token` 均不可见；旧 token 在重开端口返回 401 |
| 网络与日志 | PASS | renderer 请求仅当前 127.0.0.1 loopback；日志不含 profile 名、金额、绝对路径或 token |
| 持久化与进程生命周期 | PASS | UI 重开读取成功；隔离目录中 `profiles.db` 保留；两次 Java child process 均正常退出 |

## 命令与环境

```text
Node v24.19.0
npm 11.17.0
Java 11.0.15.1

MAVEN_OPTS=-Duser.home=C:\Users\liang .\mvnw.cmd -q '-Dmaven.compiler.fork=true' test package
frontend: npm test
electron: npm test
electron: node node_modules/@playwright/test/cli.js test tests/p2-005-catalog-settings.integration.spec.mjs --reporter=line
```

Electron 测试在本机受控权限执行，默认 `sandbox:true`，未使用 `--no-sandbox`。Node 22 未复测；Playwright 仅出现 `NO_COLOR` 环境提示，没有 engine warning。测试数据目录位于 `%TEMP%` 且与工作区、用户 home、正式 `%LocalAppData%\LedgerX` 不同，测试结束后已清理。

## 未包含与后续风险

- 发布包、签名、安装器和桌面快捷方式不在 P2-005 范围内，未执行。
- Forge 6.4.2 仍受 ADR-006 临时安全豁免约束，正式发布前必须复审。
- Node 22.18.0 基线尚未认证；本记录只代表当前 Node 24.19.0 本机环境。
