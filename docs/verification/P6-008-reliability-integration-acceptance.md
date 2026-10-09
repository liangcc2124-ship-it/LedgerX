# P6-008 交互可靠性与公式闭环集成验收记录

- 日期：2026-09-22
- 状态：BLOCKED

## 已完成的可复用证据

- `frontend/npm test`：配置 7/7、composable 3/3、Playwright 44/44 通过，覆盖 P6-002 至 P6-007 的浏览器 fixture 场景。
- P6-001 已有 Java/SQLite formula create→停止/重启→detail/dashboard round-trip 记录；本轮未重复同一原生证据。

## 阻塞证据

- `electron/npm test`：unit 10/10 通过；两个 Electron Playwright 场景均失败，renderer target 在 `domcontentloaded` 前崩溃，第二场景进一步报 `Invalid URL`。因此不能证明真实 Electron→Java→SQLite 闭环。
- `mvn -q test`：项目 POM 无法解析 `com.fasterxml.jackson:jackson-bom:2.21.0`，环境指向不存在/不可写的 `D:\dev\apache-maven-3.6.1\mvn_repo` 锁定缓存，未进入测试执行。

## 结论与下一步

保持 P6-008 BLOCKED，不更新 P5-008 为可执行，不声明 Electron 发布包、快捷方式或真实 records/profile/layout 重开通过。解除条件是修复/提供可用 Maven 依赖缓存并定位 Electron renderer 崩溃后，重新执行隔离数据目录下的跨层验收矩阵。
