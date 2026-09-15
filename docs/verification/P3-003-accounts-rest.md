# P3-003 验证

状态：PARTIAL。已实现账户 list/create/replace/archive、精确金额与余额投影；独立 `CatalogHttpServerTest` 真实验证账户 CRUD、ETag、系统账户不可归档和隔离 SQLite，`RecordApplicationServiceTest` 验证资产/负债方向。asOf、最早结算日和完整 HTTP 异常 fixtures 仍待补齐。
