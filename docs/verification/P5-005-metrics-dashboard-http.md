# P5-005 Metrics/Dashboard HTTP 验证记录

状态：PARTIAL。

已验证真实 `HttpServer → application → SQLite` 路径：

- `GET /api/v1/metrics`、`GET /api/v1/dashboard`、`GET /api/v1/dashboard/layout`；
- `POST /api/v1/formulas/validate`、`POST /api/v1/formulas/preview`、`GET /api/v1/formulas/{id}/versions`；
- custom metric `POST` 的闭合 body、`Location`/ETag、当前公式版本和详情投影；
- metrics collection 的 ACTIVE/ARCHIVED、dashboardEnabled、limit/opaque cursor 过滤；
- system metric 的 visibility-only `PUT`，以及公式 validate/preview 的有标签依赖组件；
- Dashboard layout 的 `If-Match`、`Idempotency-Key` 和重复请求回放；
- 认证/loopback 基座继续复用既有 HTTP 测试。

执行命令：

```text
./mvnw.cmd -q -Dmaven.compiler.fork=true -Dtest=MetricsApplicationTest,MetricsRepositoryTest,MetricsDomainTest,MetricsHttpServerTest,MetricsSchemaV005Test test
```

结果：5 个目标测试类通过，HTTP 测试覆盖集合筛选/分页与系统指标可见性更新。未完成项是所有错误分支的契约 fixture 和逐项 HTTP 回归矩阵，因此不能标记为完整 PASS。
