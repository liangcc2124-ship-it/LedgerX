# P5-004 应用层验证记录

状态：PARTIAL。

已验证范围：真实 active-profile SQLite 上的 17 个系统指标读取、Dashboard 8 卡片读取、custom metric 创建、归档、幂等回放，以及布局读取/替换/重置的事务与 dataRevision 协调。自定义公式现在从同一事实快照取得递归分类收入/支出（含当前后代）、按 asOf 投影的账户余额、时间变量及嵌套指标值；候选图会拒绝间接循环，归档分类/账户历史引用仍可读。

执行命令（Java 11/Maven）：

```text
./mvnw.cmd -q -Dmaven.compiler.fork=true -Dtest=MetricsApplicationTest,MetricsRepositoryTest,MetricsDomainTest,MetricsHttpServerTest,MetricsSchemaV005Test test
```

结果：5 个目标测试类通过；其中新增断言覆盖真实 SQLite 分类后代事实、账户余额映射、分类公式运行时 READY 和间接指标循环。

未完成：趋势/breakdown 的完整聚合投影规则尚未实现；当前 API 明确返回空数组，不能当作该功能已经交付。
