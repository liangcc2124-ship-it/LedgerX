# LedgerX P3/P4 实施验证记录（2026-09-14）

本轮按任务索引完成了 P3-001 的 V004 seed、P3-002/P3-003 的分类账户 Java/SQLite/HTTP 接线、P4-001/P4-002 的基础记录 application/HTTP 链路，以及 P3-004/P4-003/P4-004 的 Vue 页面，并补跑了真实 Electron 目录与记账场景。P3/P4 的部分极端错误矩阵、Electron 层 child PID 和数据库只读核验尚缺证据，因此本记录不把整批任务标记为全部 PASS。

## 实际变更

- Java：V004 seed、分类/账户/记录 DTO、application API、SQLite repository、loopback HTTP 路由及真实测试。
- Vue：收支记录页、分类/账户管理页、导航/首页入口、基础版设置与手工备份说明、对应浏览器测试。
- Electron：真实基础冒烟、S0-006 隔离重开回归、P3-005 基础目录新建/重开和 P4-005 基础记录新建/回收站/恢复/重开测试；补充 Windows x64 Forge 封装与安装器冒烟。
- 文档：数据库迁移状态、README 重构状态、任务状态和本验证记录。

## 验证证据

- IntelliJ bundled Maven 3.9：`test package` 41 个 Surefire 用例，41 通过，0 失败，0 错误；生成 `target/ledgerx-desktop-0.1.0-SNAPSHOT.jar`。规定的 wrapper 在本机因默认 `C:\.m2` 无权限失败，改用已有本地依赖和临时 Central mirror 配置完成同一 Maven 目标。
- `frontend` 目录 `npm test`：Vite 配置 7/7；Vite build 成功；Playwright 35/35。
- `electron`：单测 10/10；源码 Java 同源 Electron 冒烟 2/2；S0-006 隔离重开 2/2；P3-005 目录创建/重开 1/1；P4-005 三类基础记录、负债余额、编辑、取消删除、回收/恢复、重开 1/1。
- Electron 发布：`npm run make:win` 成功；生成 `electron/out/make/squirrel.windows/x64/LedgerXSetup.exe` 和 `ledgerx-0.1.0-full.nupkg`；最终解包版启动、Java 后端、Vue 路由和隔离 SQLite 冒烟 1/1 通过。
- 真实原生链路：Java application → sqlite-jdbc → 临时 SQLite 已验证；记录创建→编辑→软删除→恢复→重开、合并原子性、资产/负债余额方向、SQLite 行级记录/operation/revision 核验均有断言。
- 真实 HTTP 链路：loopback HTTP → Java application → SQLite 已验证；记录 GET/POST/DELETE/restore、ETag、If-Match、幂等重放和闭合 body 已覆盖。
- 上述 Electron 测试使用本机 Electron 的 GPU/沙箱禁用环境以绕过当前嵌套运行器的 renderer crash，未修改产品安全配置。
- 前端测试使用 fixture/mock HTTP；不等同于真实 Electron 或真实 SQLite 验收。

## 未完成与限制

- 未达到 P4-005 完整验收门槛：UI 已核对 profile 切换后的旧记录隔离，但尚未在 Electron 场景中核对退出后 Java child PID 和 SQLite 行级只读核验；默认 sandbox 环境重跑仍会触发当前 renderer crash。
- `electron/tests/p2-005-catalog-settings.integration.spec.mjs` 已按现行 P4-004 的手工备份说明更新，在 GPU/沙箱禁用环境下 1/1 通过；该结果不计入 Java/Vue 数量。
- 分类/账户 REST 已补独立 loopback HTTP CRUD、ETag/If-Match、合并和系统账户不可归档测试；活动子分类、类型兼容和更多异常矩阵仍需扩展 fixtures。
- 记录列表已支持 status、日期、类型、分类、账户、金额、query、limit/cursor；HTTP 测试已覆盖组合筛选、重复参数、游标绑定和 operation 查询，但完整错误矩阵仍未全部覆盖。
- 本次已重新构建并启动 Electron 解包发布包，已更新现有桌面 `LedgerX.lnk` 且核对只有 1 个快捷方式；安装包尚未执行系统级安装/卸载回归。发布包依赖目标 Windows 已安装 Java 11 或兼容运行时。

## Review 关注

- 如要将整批 P3/P4 任务标记为 PASS，仍应补齐完整异常矩阵、Electron 层 child PID/SQLite 只读核验，并解决或复现记录中的默认 sandbox renderer crash；当前已完成的功能与对应验证证据不受这些未覆盖项影响。
