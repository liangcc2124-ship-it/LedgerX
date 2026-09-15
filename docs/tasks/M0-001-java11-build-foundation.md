# 历史 Task Spec：M0-001 Java 11 构建基线

- 状态：已完成但部分失效
- 日期：2026-09-12
- 原因：[ADR-004](../decisions/ADR-004-vue-electron-local-rest.md) 已将目标改为 Vue/Electron + 本机 REST。

该任务曾建立 Java 11 Maven wrapper、`--release 11`、Jackson、sqlite-jdbc 和 JUnit 基线。JavaFX 依赖/包目录属于被废止部分，须由 [S0-001](./S0-001-java-backend-baseline.md) 清理。原测试证据不代表新架构已实现。

禁止继续执行本文件旧版本中的 JavaFX、WebView 或 bridge 指令。当前顺序见 [README](./README.md)。

