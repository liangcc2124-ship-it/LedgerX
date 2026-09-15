# 模块规格：metrics-formulas

- 状态：按 ADR-010 延后；不是基础记账版卡点，用户重新确认需要后才恢复设计
- 边界：系统/自定义指标、模板、不可变公式版本、受限 AST、依赖图、指标试算、仪表盘布局和直接指标数值的语义。
- 引用：[需求](../requirements.md)、[数据库 §4.5–§4.6](../database.md)、[全局 REST API](../api.md)、[架构](../architecture.md)。

## 1. 职责、术语、用例与非目标

术语：`system metric`（固定 ID、只读定义）、`custom metric`（`custom-<uuid>`）、`formula version`（不可变 AST 快照）、`dependency`（从 AST 抽取的引用）、`period metric`（报告期计算）、`point-in-time metric`（截至 asOf 计算）、`dataStatus`（计算状态而非错误）。

用例：浏览/启停/归档自定义指标；从模板创建；创建、验证、预览和保存公式版本；识别循环和缺失引用；按期间解释指标证据；保存/重置 dashboard layout。非目标：任意 JS/SQL/反射表达式、持久化指标缓存、跨 profile 公式、预算/预测、将 widget 布局当成业务事实。

## 2. 入口、输出与依赖

| 项目 | 内容 |
| --- | --- |
| 入口 | `/metrics`、`/formulas`、`/dashboard` REST 资源；ledger-records 写后发起的指标刷新；reports-warnings 的只读求值。 |
| 输出 | metric list/detail、`value` 或 `null`+`dataStatus`、解释/证据 ID、公式校验/预览、layout revision。 |
| 依赖方 | reports-warnings、ui-integration、ledger-records（归集能力检查）、allocation-assets（折旧/分摊值）。 |
| 被依赖方 | ledger-records 的只读事实、allocation-assets 的值对象、persistence、Clock。 |
| 方向 | `metrics → ledger/assets public read models`；不写 finance_record/asset 表；dashboard 不依赖 Vue 组件实现。 |

## 3. 实体、权限与不变量

- `metric_definition`、`metric_visibility`、`formula_definition/version/dependency`、`dashboard_layout/item` 的字段/约束引用[数据库 §4.5–§4.6](../database.md#45-指标与公式)。系统指标 ID 只能由 seed migration 创建/修改，不能归档/删除；自定义名称 trim 后 1–100 字符，活动自定义指标名不区分大小写唯一，且不得与系统显示名同名。
- `enabled` 控制是否出现在可选/仪表盘列表，`hidden` 只控制金额展示；两者不改变公式计算、预警或历史证据。归档指标不参与新引用或计算，仍可作为历史迁移诊断对象。
- 公式版本不可改写；保存新 AST 总是新增 version、重新抽取 `formula_dependency`，再原子切换 `metric_definition.current_formula_version_id`。被 metric/asset/allocation 引用的旧版本不可物理删除。
- AST JSON、tokens JSON 只接收白名单节点：constant、variable、`+ - * / unary-`、`MIN/MAX/AVG/ROUND/ABS/CLAMP`；最大深度 32、总节点/令牌 256。没有 eval、函数调用、文件、网络或动态变量。
- scope 隔离：`METRIC` 只可引用 `metric:*`,`base:*`,`direct:*`,`time:*`；`ALLOCATION` 和 `DEPRECIATION` 变量集以 allocation-assets 为准。`formula_version.result_type` 必须匹配被引用用途。

## 4. 公式状态、验证与计算

```text
DRAFT_TOKENS → VALIDATED → SAVED_VERSION → ACTIVE_FOR_METRIC
       └──────→ INVALID
```

公式 validate/preview endpoint 对草稿只读；保存新版本必须先通过同一编译器，不能因“预览曾通过”跳过校验。验证顺序：token/AST 结构 → scope/变量 → 运算元个数 → 指标/公式存在且活动 → 结果类型 → 有向图环 → 除零风险（试算时实际除零）。失败返回 `FORMULA_INVALID` 和稳定 field path，不写 draft/definition。

指标计算顺序为：装载活跃事实与选择期间 → 计算系统基础指标 → 拓扑排序自定义指标 → 注入已完成依赖 → 求 AST → 生成解释/证据。任何依赖缺失、归档、循环或实际除零的单个指标输出 `value:null` + `dataStatus=NOT_COMPUTABLE`（或 `INSUFFICIENT`/`NO_BURN`），不得让整页失败或伪造 0。公式中 `ROUND(x[,scale])` 的 scale 必须为 0–8 整数、采用 `HALF_UP`；公式内部 BigDecimal 保留足够精度，结果投影依 display format 明确舍入，比例 `12.5` 表示 12.5%。

### 系统指标基线

实现必须至少提供并保持稳定 ID：`inflow,outflow,netflow,cash,liquidity,fixedcost,variablecost,totalcost,nonessential,nonessentialratio,upcomingpayables,fixedassets,fixedassetgross,payables,assets,netassets,burn,runway,chain,dependency,concentration,selfsufficiency,safetycoverage`。其财务口径以当前 C# 黄金样本冻结：现金只计已结算有现金影响记录；固定成本按 allocation 确认；资产为活动资产账面净值；应付款为截至 asOf 新增减支付且不小于零；burn 为最近三个完整月固定/可变成本平均，样本不足为 `INSUFFICIENT`；分母零返回 `NOT_COMPUTABLE` 或 `NO_BURN`，绝不返回 NaN/Infinity。

自定义模板是初始 AST/显示格式/期间行为的可复制蓝图，不是运行时“模板继承”；从模板创建后用户得到独立 metric 和 formula version。当前模板列表：`savings-rate`,`expense-ratio`,`fixed-cost-share`,`debt-ratio`,`liquid-net-worth`,`monthly-fixed-cost`,`direct-amortization`。

## 5. 直接数值、布局与边界

`record_metric_assignment` 让一条 finance record 影响多个接收直接归集的指标。`direct_metric_entry` 表示非现金的增加/减少数值，金额/数值必须正，方向由 `INCREASE|DECREASE` 决定；它不影响账户、现金或财务记录类型。0–200 个归集目标存于 `direct_metric_assignment`，零目标仍是合法独立事实。详情和列表投影必须以统一的 `records` DTO 显示，但其 source kind 要可辨识。

布局按 breakpoint 一行；item 的 widgetId 只能是已发布系统 widget 或 `metric:<active-metric-id>`。坐标/尺寸满足数据库约束、不得重叠到不可用布局；重置只删除当前 profile 的 layout 数据并由确定的默认布局重新生成。空账本仍能显示已启用系统指标及空数据状态。

所有保存需要 `expectedRevision`。归档指标若被 formula、warning 或 assignment 引用，返回 `REFERENCE_CONFLICT`，先修复引用；删除只适用于未引用自定义指标。直接数据、公式、指标和当前公式指针的跨表写由 application 保持事务原子。

## 6. API/表映射

| API | 表 | 特有输入/输出 |
| --- | --- | --- |
| `/metrics` collection/resource | metric_definition, metric_visibility | list 默认不返回完整 AST；mutation 返回 id/revision/currentFormulaVersionId。 |
| `/formulas/validate|preview` 与版本资源 | formula_definition/version/dependency | validate/preview 不写；save 返回公式 ID/version/依赖。 |
| `/dashboard` 与 `/dashboard/layout` | dashboard_layout/item | get 只返回已启用 metric 摘要和当前 breakpoint layout。 |

具体方法、路径和请求体必须由后续 `metrics-formulas-api.md` 固定后实施；本模块表不替代具体 API 规范。
| records API 的 metric 投影 | direct_metric_entry、direct_metric_assignment、record_metric_assignment | 由 ledger-records 协调，metrics 只提供校验/值。 |

公式和指标无跨 profile ID 可见性；所有查询/依赖图只在 active profile 建立。计算结果不写入数据库缓存。

## 7. 可观测、验收与建议测试

事件：`formula.validated`, `formula.rejected`, `formula.version-saved`, `metric.computed`, `metric.not-computable`, `layout.saved`；记录计数、节点数、耗时、状态，不记录 AST、数值、指标名或证据内容。

| 场景 | 结果 | 层次 |
| --- | --- | --- |
| 两个指标互相引用 | 保存拒绝，旧 current version 不变。 | 图算法单元 + SQLite 集成 |
| `MAX(x,1)` 防除零 | 对应公式可计算；直接 `/0` 返回 null/dataStatus。 | 公式单元 |
| 深度 33/节点 257 | 无持久化、稳定 `FORMULA_INVALID`。 | 安全/单元 |
| 固定成本跨月 | fixedcost 使用分摊而非付款日。 | 与 allocation 黄金测试 |
| 三月样本不足 | burn/runway 不伪造数值，报告/预警可识别状态。 | 领域单元 |
| 公式版本更新 | 历史版本不变、依赖索引更新、重启后相同结果。 | SQLite 生命周期 |
| 空布局/空账本 | 返回确定默认 layout/空指标，而不是异常。 | E2E |

## 8. 已裁决事项与待决

[ADR-008](../decisions/ADR-008-direct-metric-multi-assignment.md) 已解决多目标历史映射：
`direct_metric_entry` 保存事实，`direct_metric_assignment` 保存 0–200 个目标。导入不得复制记录、随机选一个
metric 或丢弃未归集记录。

当前 C# 对自定义公式显示精度没有统一持久化舍入策略；本规格已把公共公式 `ROUND` 及公共 CNY 输出定为 `HALF_UP`，这应加入黄金 fixture。若历史样本显示与该规则不一致，应升级高级模型调整规则，而不是在 UI 单独格式化修复。
