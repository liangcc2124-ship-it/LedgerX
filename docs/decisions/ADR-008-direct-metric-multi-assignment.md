# ADR-008：指标数据使用多对多归集

- 状态：已接受
- 日期：2026-09-13

## 背景

v3.1 schema 4 的 `CustomIncrease`/`CustomDecrease` 是不影响现金的指标数据，并允许
`MetricTargetIds` 为 0 到多个。原数据库草案把 `direct_metric_entry.metric_id` 设计为单一外键，
无法无损表达“未归集”和“一条数据归集多个指标”。现有 C# 计算也会让同一条记录同时参与其
`MetricTargetIds` 中每个指标的求和。

## 决定

1. `direct_metric_entry` 只保存指标数据事实，不再包含 `metric_id`。
2. 新增 `direct_metric_assignment(entry_id, metric_id)` 多对多关联表，复合主键防止重复归集。
3. 一条指标数据允许 0–200 个归集目标；零目标仍是合法、可查看和可编辑的独立指标数据。
4. 目标指标必须属于当前 profile、未归档且允许直接归集。该跨表规则由 Java 领域层在同一事务内校验。
5. 导入 schema 4 时，每个 `CustomIncrease`/`CustomDecrease` 生成一条同 ID 的
   `direct_metric_entry`，并把去重后的 `MetricTargetIds` 写为 assignment；旧 `CustomMetricId`
   先合并进目标集合。不得复制事实、选择所谓主指标或丢弃零目标数据。
6. 普通财务记录仍通过 `record_metric_assignment` 归集；两种 assignment 不合并成通用多态表。

## 备选方案

- **每个目标复制一条 direct entry**：会重复列表事实并使编辑、删除和幂等语义复杂，拒绝。
- **限制恰好一个目标**：不能无损导入当前数据，也不符合“归集可选且可多选”的现有行为，拒绝。
- **继续把指标数据存为 finance record**：会保留无意义的结算、账户和分类字段，并混淆现金事实，拒绝。
- **通用多态 assignment 表**：需要类型列和应用级引用完整性，复杂度高于两个明确关联表，拒绝。

## 后果

- 旧 schema 4 的 0..n 目标可一对一保留，迁移不再因该项阻塞。
- 指标数据列表按 `direct_metric_entry(deleted_at, occurred_on, id)` 分页；按指标下钻通过
  `direct_metric_assignment(metric_id, entry_id)` 查询。
- 永久删除 direct entry 时可级联删除其 assignment；删除指标仍受引用保护，不自动丢弃历史归集。
- 本决定发生在业务表迁移尚未创建之前，不需要修改已执行的 V001 bootstrap migration。
- 若未来要求一条归集对不同指标使用不同权重，应新增 assignment 属性和版本化迁移，不在本期预留。
