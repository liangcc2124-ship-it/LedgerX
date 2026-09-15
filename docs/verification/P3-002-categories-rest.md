# P3-002 验证

状态：PARTIAL。已实现分类 application/HTTP 路由，并由独立 `CatalogHttpServerTest` 真实验证 list/create/replace/archive/merge、ETag、If-Match 和隔离 SQLite；merge 类型兼容、活动子分类和完整 HTTP 异常 fixtures 仍待补齐。
