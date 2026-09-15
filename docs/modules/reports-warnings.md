# 模块规格：reports-warnings

- 状态：按 ADR-010 延后；不是基础记账版卡点，用户重新确认需要后才恢复设计
- 边界：应用内报表、期间对比、可追溯来源、预警规则/条件/事件的评估和生命周期。引用：[需求](../requirements.md)、[架构](../architecture.md)、[数据库 §4.6、§6](../database.md)、[全局 REST API](../api.md)。

## 1. 职责、术语、用例与非目标

| 术语 | 定义 |
| --- | --- |
| 报告期间 | `day`、周一开始的 `week`、`month`、`year`；内部为半开区间。 |
| 进行中期间 | 截止 `asOf`/今天，不能把未来日期当事实。 |
| 证据 | 参与指标或预警计算的 record ID 集合；可下钻，但不改变计算。 |
| 规则 | 一个指标条件或多个 AND 条件、比较方式、连续期数、有效期与冷却策略。 |
| 事件 | 某规则一次满足触发条件的持久化观察；不是财务事实。 |

用例：查看日报/周报/月报/年报和上期可比值；点击图表/分类下钻来源记录；创建/预览/启停/复制/删除预警规则；读、暂停、忽略事件；写入事实后重新评估。非目标：PDF/打印/邮件导出、外网通知、预测、自动修改记录、后台常驻调度器、任意逻辑表达式（条件只支持 AND）。

## 2. 入口、输出与依赖方向

| 项目 | 内容 |
| --- | --- |
| 入口 | `/reports`、`/warnings`、`/warning-events/{id}` REST 资源；启动和成功领域写后的内部 `evaluateAffectedRules`。具体方法/字段等待具体 API 文档。 |
| 输出 | 报告 DTO、drill-down record IDs、规则/事件 DTO、`dataStatus`/解释/证据。 |
| 依赖方 | ui-integration、desktop 的应用内提示区域、ledger-records 的永久清理前置检查。 |
| 被依赖方 | metrics-formulas 计算器、ledger-records/allocations 的只读事实、persistence transaction/Clock。 |
| 方向 | `reports-warnings → metrics/ledger/assets read model`；它不写 finance_record、metric 或 asset。ledger-records 只能查询它的 evidence 引用状态。 |

全部操作限定 active profile；规则和事件写需要 `expectedRevision`。报告是只读计算，不能因空数据写入默认财务事实。

## 3. 报告口径与边界

`GET /reports` 仅接受 `kind=DAY|WEEK|MONTH|YEAR`（wire 枚举使用全局大写）和可选 `asOf`；前端显示文案可为中文。当前期间为从相应自然边界到 `min(asOf, today)`；此前期间从上一同类型周期起，以**相同已过去天数**截取，避免把本月第 3 天和完整上月作误导性比较。

| 输出项 | 规则 |
| --- | --- |
| `dataStatus` | 本期没有记录为 `EMPTY`，有记录为 `READY`；“记录存在但某指标不能算”在 `metricStatus` 单独表达。 |
| 流入/流出/净流 | 从 metrics 的已结算现金口径取值，绝不以所有记录金额相加替代。 |
| 系列 | `DAY` 显示截至 asOf 的最近 14 个自然日；`WEEK/MONTH` 按日；`YEAR` 按月。每点含可下钻 record IDs。 |
| 成本/收入结构 | 本期 active records；成本只含固定/可变成本，收入按收入来源；降序 Top 5，其余合为“其他”。 |
| 期初/期末现金 | `asOf` 前一天的 point-in-time cash 与当前期间末 cash；没有事实也可为合法 0。 |
| 摘要 | 依据 `netflow` 和 `dataStatus` 的确定本地化文案；不得声称建议、预测或外部比较。 |

报告返回结构化数值/状态和 record IDs，不返回全历史、内部公式 AST 或数据库字段。金额隐藏是 UI 显示偏好，报告 API 仍返回正确结构化值；它不是加密/授权特性。

## 4. 规则、实体与不变量

`warning_rule`、`warning_condition`、`warning_event`、`warning_event_evidence` 的列/索引按[数据库 §4.6](../database.md#46-仪表盘预警和事件)。模块特有约束：

1. 规则名 trim 后 1–100 字符；说明最多 1,000 字符；每规则 1–8 条条件，按 position 排序，逻辑恒为 AND。
2. 每个 condition 必须引用活动可计算 metric；operator 为 `GT,GE,EQ,LE,LT`，threshold 为规范 decimal 字符串，单位必须与 metric display format 相容。
3. period 为 `DAY|WEEK|MONTH|YEAR`；`consecutivePeriods`、`comparisonWindow` 均为 1–24；`cooldownHours` 为 0–8,760；有效日期均为业务日期且 `effectiveFrom ≤ effectiveTo`。
4. `comparisonMode`：`THRESHOLD`、`PREVIOUS_CHANGE_PERCENT`、`AVERAGE_CHANGE_PERCENT`、`CONSECUTIVE_POSITIVE`、`CONSECUTIVE_NEGATIVE`。后两种不读取 threshold/operator；保存时把无意义的 threshold 比较字段规范化为默认值。
5. severity 为 `NOTICE|IMPORTANT|CRITICAL`，显示层映射为“关注/重要/严重”；notification method 本期只允许 `IN_APP`。现有“Windows 通知”设置不得伪装成已投递能力。
6. 保存/复制规则在一事务写 rule+全部 conditions 并更新 revision；复制不复制 event，名称追加“（副本）”，新规则默认沿用 enabled 值。
7. 删除规则在一个事务删除 rule、conditions、events 与 evidence，保持当前 v3.1 行为；永久清理被 event evidence 引用的记录必须先删除相关规则/事件，见 ledger-records。

## 5. 事件状态、评估和补偿

```text
（评估触发）→ ACTIVE → READ → SNOOZED ──到期且仍触发──> ACTIVE
                   └────────────→ IGNORED
ACTIVE/READ/SNOOZED ──条件不再满足/规则禁用/过期/不可算──> RESOLVED
```

warning-event 更新只允许：`ACTIVE→READ`；`ACTIVE|READ→SNOOZED`（必须传未来 `snoozedUntil` UTC instant，UI 的“暂停 24h”由 UI 明确计算）；`ACTIVE|READ|SNOOZED→IGNORED`。用户不能手工将事件设为 `RESOLVED`，不能修改 measured value、阈值、证据或触发时间。`IGNORED` 保留历史，不被视为当前 active event；后续仍满足且冷却已过可产生新事件。

每次评估以注入 Clock 的本地业务日期运行：

1. 禁用、尚未生效、已过期或任一指标 `dataStatus` 不可计算：把最新 `ACTIVE/READ/SNOOZED` 事件设为 `RESOLVED`（无 event 则不写）。
2. 对连续 `n` 个期点，逐期评估全部 conditions；全部期、全部条件为真才触发。previous/average change percent 用 `(current-baseline)/abs(baseline)*100`；样本不足或 baseline=0 为不可算。
3. 触发且存在 active/read/snoozed event：更新 measured、threshold、period、lastEvaluated、explanation、evidence；已到期 snooze 变回 `ACTIVE`。未触发则 resolve。
4. 触发且无当前事件：检查该 rule 最近触发 event 的 `cooldownHours`。冷却未过则不建 event；否则插入 `ACTIVE` event 及证据。

规则 preview 只返回计算结果，不写 event。事实写入后由 application 在事实成功提交后触发评估；事件写入失败不能回滚已经提交的财务事实，必须记录诊断，下一次相关写/启动重新评估。因相同 `Idempotency-Key` 重试不得重复创建事件：评估在同一 Clock/asOf 下对相同 rule/current period 先查当前 event。

## 6. API 与数据库映射

| API | 表 | 输出/错误 |
| --- | --- | --- |
| `GET /reports` | 只读 finance/allocation/asset/metric 数据 | report DTO；未知 kind/日期为 `VALIDATION_FAILED`。 |
| warning preview 子资源 | 只读 rule draft + metric 计算 | `canEvaluate, triggered, value, explanation, evidenceRecordIds`。 |
| `/warnings` collection/resource/actions | warning_rule, warning_condition, event/evidence | list 或 revision；引用归档 metric 时 `REFERENCE_CONFLICT`。 |
| `PATCH /warning-events/{id}` | warning_event | status/revision；非法转换 `VALIDATION_FAILED`。 |

除 `GET /reports` 和全局已列资源外，具体方法/路径/字段必须在后续 API 文档确定后实施。

规则字段中的 numeric threshold 使用 API decimal 字符串、表中 text；event 的 measured/threshold 同样由 BigDecimal 规范化，不用浮点。日期显示 end 为报告 UI 文案时可转闭区间，但 wire 内部仍遵循全局 `endExclusive`；历史 C# 的 `PeriodEnd` 导入时转为 `endExclusive=oldEnd+1 day`。

## 7. 可观测、验收与测试

事件：`report.generated`, `warning.previewed`, `warning.evaluated`, `warning.triggered`, `warning.resolved`, `warning.snoozed`, `warning.evaluation-failed`。仅记录 rule/event 脱敏 ID、状态、condition 数、耗时和错误码；不记录说明、阈值、数值或证据。

| 场景 | 结果 | 层次 |
| --- | --- | --- |
| 本月第 3 天报告 | 上期仅比较上月前 3 个可比日。 | Clock 固定的领域单元 |
| 本期无记录/有零金额结果 | `EMPTY` 与可计算 0 区分；UI 不把空当 0。 | 单元 + UI 合同 |
| 两个 AND 条件、连续两月 | 四项评估都满足才触发一次。 | 警告引擎单元 |
| 冷却、暂停 24h、到期 | 不重复建 event；到期且仍满足恢复 active。 | Clock 状态机单元 |
| 分母为 0/样本不足 | 规则可评估为 false/不可算，旧 active event resolve。 | 单元 |
| 删除规则 | rule/conditions/events/evidence 都消失；之后记录可永久清理。 | SQLite 集成 |
| 真实报告下钻 | 返回 ID 与 `GET /records` 同 profile 同事实匹配。 | REST + Electron E2E |

## 8. 待决与升级

当前 C# 的 `PeriodEnd` 是闭区间，而全局 API 已定半开区间；本规格给出导入转换，属必要兼容。若要增加推送、Windows toast、邮件/短信、OR/NOT 条件、未读计数同步或跨设备提醒，需高级模型先设计权限、调度、隐私与可靠投递；本模块不得扩展。
