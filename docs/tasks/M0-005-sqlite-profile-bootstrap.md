# 历史 Task Spec：M0-005 SQLite profile bootstrap

- 状态：已替代
- 日期：2026-09-12

SQLite 总体模型仍有效，但任务必须使用 `processed_operation` 和新的 HTTP/application 边界；执行 [S0-005](./S0-005-sqlite-profile-bootstrap.md)。禁止按旧 `processed_command/requestId` 幂等语义实现。

