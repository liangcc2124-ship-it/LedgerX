# 模块规格：metrics-formulas-dashboard

- 状态：高级设计已接受；中级 API、技术设计与 Task Spec 已完成，尚未实现
- 日期：2026-09-15
- 边界：系统指标、自定义计算指标、公式版本与依赖、四级财务总览、桌面卡片布局
- 引用：[需求](../requirements.md)、[架构](../architecture.md)、[数据库 §4.5–§4.6](../database.md#45-指标与公式)、[全局 API](../api.md)、[ADR-011](../decisions/ADR-011-enable-metrics-dashboard.md)、[ADR-012](../decisions/ADR-012-gridstack-dashboard-layout.md)、[中级技术设计](../design/P5-metrics-formulas-dashboard-technical-design.md)、[Metrics/Formula API](../api/metrics-formulas-api.md)、[Dashboard API](../api/dashboard-api.md)

## 1. 目标、用户结果与非目标

用户可以在财务总览中按日、周、月、年查看可信指标，比较上一自然期间，并把卡片拖到适合自己的位置和尺寸。用户可以创建由已有账本事实计算出的自定义指标，使用中文优先的函数、变量选择和错误提示，无需编写代码。

本模块必须保证：同一期间在任何页面只有一套后端财务结果；坏公式只影响自身及依赖项；布局变化不改变业务事实；重启或切换 profile 后不会串数据。

非目标：

- 手工录入非现金指标值、普通记录到指标的多目标归集。
- 固定资产、分摊、应付款、预算、预测、报表、预警和通知。
- 任意 JavaScript、SQL、脚本、插件函数、远程数据源或跨 profile 引用。
- 手机/平板布局、触摸拖拽、四种时间粒度各自独立布局。
- 持久化指标结果、趋势缓存或历史公式“按当时版本”回放。

## 2. 模块边界与依赖方向

| 子模块 | 拥有的职责 | 可以依赖 | 禁止承担 |
| --- | --- | --- | --- |
| period | 粒度、range、asOf、上一期间、趋势桶 | `Clock` | SQL、UI 文案、指标公式 |
| metric catalog | 系统/自定义定义、显示格式、归档、可见性 | persistence ports | 财务记录写入 |
| metric facts | 按发生日/结算日装载聚合事实和账户余额 | records/accounts 只读端口 | 修改记录/账户、公式求值 |
| formula | AST schema、验证、依赖图、拓扑求值、版本 | metric/category/account 查询端口 | HTTP、Vue token 展示 |
| dashboard | 编排期间、基础指标、公式、比较、趋势和布局 DTO | 上述模块 | 保存派生指标值 |
| layout | 12 列坐标、碰撞/尺寸验证、默认布局、revision | metric catalog | 财务计算、DOM 测量 |

方向固定为：`HTTP → dashboard/formula application use case → domain → persistence ports`。Persistence 不决定指标口径；Vue 不计算金额、比例、期间或依赖图；Electron 不参与业务 DTO。

## 3. 时间模型

### 3.1 请求与规范期间

Dashboard 请求只接收 `granularity` 和 `anchor`：

| granularity | 当前期间 `[start,endExclusive)` | 比较基础期间 | 趋势桶 |
| --- | --- | --- | --- |
| DAY | anchor 当日到下一日 | 前一日 | 不做小时趋势；返回分类构成/当日摘要 |
| WEEK | anchor 所在周的周一到下周一 | 前一自然周 | 连续 7 个自然日 |
| MONTH | anchor 所在自然月 | 前一自然月 | 当月每个自然日 |
| YEAR | anchor 所在自然年 | 前一自然年 | 连续 12 个自然月 |

周一为一周起点。业务日期使用 Java `LocalDate`；“今天”来自后端本地 Windows 时区和可替换 `Clock`。HTTP/前端不能传自定义 start/end 来绕过粒度规则。

`asOf` 规则：历史期间为 `endExclusive-1天`；包含今天或未来的期间为 `min(today,endExclusive-1天)`；完全未来期间没有有效 asOf，指标状态为 FUTURE。下一期间导航可由 UI 禁用到当前期间之后，但后端仍必须安全处理未来 anchor。

比较窗口规则：历史完整期间使用完整上一自然期间；当前未结束的 WEEK/MONTH/YEAR 从上一自然期间起点开始，截取与 current start→asOf 相同的已过自然日数，若上一期更短则止于其 endExclusive。DAY 因没有日内时间事实而使用完整前一日。Dashboard 必须同时返回 previous period 边界与实际 comparison window，不能都含混地叫“上期”。

### 3.2 事实日期口径

- 损益/分类/记录数：按 `finance_record.occurred_on`。
- 现金流：按 `finance_record.settlement_on`。
- 账户余额：从 opening balance 投影到 `settlement_on<=asOf`。
- 只使用 `deleted_at IS NULL` 的三类记录；回收站记录立即退出全部指标。
- current period 的“日均支出”分母为从 start 到 asOf 的自然日数；历史期间为完整自然日数；未来天数不进入分母。

## 4. 系统指标和数据状态

系统稳定 ID、定义、默认可见性以 [数据库 §4.5](../database.md#45-指标与公式) 为唯一清单。中级规格可以为查询优化拆内部基础事实，但不能更改公开 ID、发生/结算口径或公式。

计算顺序：

1. 规范化 current/previous range 和趋势桶。
2. 一次性取得损益、现金流、分类和账户所需事实。
3. 计算系统原子指标，再计算系统派生指标。
4. 对自定义指标当前版本建立依赖图并拓扑求值。
5. 分别计算 current、previous 和趋势/构成；组装状态、解释和布局。

数值状态：

| 状态 | value | 含义 |
| --- | --- | --- |
| READY | decimal string | 计算成功；真实无记录的历史桶可以为 0 |
| EMPTY | null | 当前有效期间没有足够事实，且 0 会误导 |
| NOT_COMPUTABLE | null | 例如零分母、非法数值条件 |
| DEPENDENCY_UNAVAILABLE | null | 依赖指标不是 READY |
| FUTURE | null | 期间或桶完全位于今天之后 |

禁止返回 NaN、Infinity、前端默认 0 或让单个指标错误变成整页 500。隐藏金额只影响最终 DTO 的展示值/掩码策略，不改变内部依赖计算；具体隐私投影由 dashboard API 细化，但不得把明文藏在另一个响应字段。

有效的历史或当前期间内，收入、支出、现金流和记录数没有匹配记录时是 READY 的 0，而不是 EMPTY；账户存在但余额为零也是 READY。EMPTY 只用于确实要求样本而样本不足的后续统计或无分类构成等投影，不能把普通“本期没有消费”显示成故障。

## 5. 自定义指标

### 5.1 生命周期

```text
DRAFT（仅前端）
  ├─ validate/preview 失败 → INVALID（零写入）
  └─ validate/preview 成功 → save → ACTIVE revision 0
ACTIVE → update with If-Match → 新公式版本 + metric revision+1
ACTIVE → archive（无活动依赖）→ ARCHIVED
```

首版不提供恢复或物理删除。名称 trim 后 1–100 字符，活动名称不区分大小写唯一，且不得与系统显示名相同；description 0–500。显示格式为 CURRENCY/PERCENT/NUMBER/INTEGER，precision 0–8，但 CURRENCY 固定 2、INTEGER 固定 0。

`periodBehavior`：

- PERIOD：只引用期间/时间类事实。
- AS_OF：只引用账户/时点指标。
- MIXED：同时引用两类；UI 必须显示“期间值与期末值组合”的解释。

Java 从依赖推导并校验 behavior，不能只信任前端字段。历史 dashboard 始终使用查询时的 current formula version 重算，因此修改公式会有意改变历史期间的展示；旧 version 只用于审查和诊断。

### 5.2 可引用数据

| kind | 示例中文展示 | 稳定 key | 规则 |
| --- | --- | --- | --- |
| METRIC | `收入`、`我的储蓄率` | metric ID | 目标必须属于 active profile 且未归档；禁止自身/循环 |
| CATEGORY_INCOME | `分类收入("工资")` | category UUID | 目标活动；计算含所选分类及当前后代，只取 INCOME |
| CATEGORY_EXPENSE | `分类支出("餐饮")` | category UUID | 目标活动；计算含所选分类及当前后代，只取两类支出 |
| ACCOUNT_BALANCE | `账户余额("现金储备")` | account UUID | 新引用目标活动；已保存引用在归档后继续按 asOf 计算 |
| TIME | `期间天数`、`已过天数`、`完整月份数` | 固定英文 key | 由 PeriodResolver 注入，不能由请求覆盖 |

分类/账户/指标改名只更新展示 label，tokens/AST/dependency 中的 ID 不变。分类重新归属后，分类函数按当前层级重算历史。活动公式引用的自定义指标不得归档；分类和账户归档不删除历史引用。

## 6. 公式语言与中文体验

### 6.1 交互与传输

Vue 提供接近文本公式的结构化编辑器：用户可以键入中文函数名，或从函数、指标、分类、账户列表插入 token。中文名称和说明是首要界面；英文别名只用于兼容和熟悉公式的用户。

前端提交规范 token/AST：函数使用稳定英文枚举，reference 携带 kind/key，显示 label 只作提示。Java 必须重新验证并以规范 AST 为权威，不能执行原始显示文本，也不能信任客户端生成的 dependency。

### 6.2 首版语法

- 常量：规范十进制，不接受指数、NaN、Infinity；绝对值不超过 `10^15`，最多 8 位小数。
- 运算：ADD、SUBTRACT、MULTIPLY、DIVIDE、NEGATE。
- 函数及中文名：MIN/最小值、MAX/最大值、AVG/平均值各接收 2–32 个参数；ROUND/四舍五入接收数值和可选 scale；ABS/绝对值接收 1 个参数；CLAMP/限制范围接收值、下限、上限 3 个参数；SAFE_DIVIDE/安全除法接收分子、分母 2 个参数。二元算术节点严格接收 2 个参数，NEGATE 接收 1 个。
- ROUND 的 scale 为 0–8 整数，采用 Java `RoundingMode.HALF_UP`；内部 BigDecimal 统一使用 `MathContext.DECIMAL128`，只有显式 ROUND 或最终 display projection 才按目标 precision 做 HALF_UP，不得回退 double。
- DIVIDE 遇零返回 NOT_COMPUTABLE；SAFE_DIVIDE 遇零同样返回 NOT_COMPUTABLE，而不是 0。它的价值是提供明确函数语义和中文提示，不是掩盖错误。
- 任一参数非 READY 时函数传播状态；多个非 READY 状态同时出现时优先级固定为 FUTURE > DEPENDENCY_UNAVAILABLE > NOT_COMPUTABLE > EMPTY，不能由 AST 遍历顺序或 UI 决定。

首版不加入比较运算、布尔值、IF、字符串拼接、日期函数或用户自定义函数。出现真实公式需求后再以 AST schema 新版本扩展。

### 6.3 验证顺序和原子性

1. JSON/token schema、body 大小、节点类型与参数个数。
2. 深度不超过 32、节点不超过 256、AST/tokens 各不超过 64 KiB。
3. 常量范围、ROUND scale、result type/display format。
4. scope、active profile、指标/分类/账户引用存在性及是否允许新引用。
5. 从 AST 重新抽取 dependency，并与 token 引用对应。
6. 把候选版本加入活动图后检测自身引用和环。
7. 用请求指定 view/anchor 预览；运行时零分母是有效的 NOT_COMPUTABLE 结果，不等于结构无效。
8. save 时在同一事务新增 definition/version/dependency、切换 current pointer、递增 revisions/dataRevision、保存 operation。

Validate/preview 永远只读。保存失败或并发冲突时旧 current version、dependencies 和 dataRevision 全部不变。

## 7. Dashboard 卡片与布局

### 7.1 页面和内容层级

页面顶部固定包含标题、粒度选择、上一期间/下一期间、回到今天、当前规范日期范围、编辑布局和新增指标。默认进入当前月。

卡片按实际网格尺寸选择完整内容层级：

- compact：名称、完整格式化值/单位、dataStatus。
- medium：compact + 上一期原值和变化。
- expanded：medium + 当前粒度的趋势、分类构成或解释。

层级切换不是 CSS 裁切。每一层的字段必须完整换行和可读，金额不缩写为 1.2K/1.2M；长名称可换行。卡片不得使用固定高度裁掉文字、仅 hover 才能读取关键值或内部滚动条掩盖最小尺寸错误。

DAY 没有小时事实，因此 expanded 展示当日分类构成/摘要，不生成虚假的小时折线；WEEK 为 7 日、MONTH 为自然日、YEAR 为 12 月连续桶。

### 7.2 布局编辑

- 普通模式点击卡片进入指标详情，不可拖动；只有显式编辑模式显示移动区域和缩放手柄。
- 鼠标拖动卡片标题区域移动，拖动右下角/边缘缩放；拖动中显示占位和目标位置，不允许重叠落盘。
- 使用数据库规定的 12 列和 min/max。Vue 可实时做相同约束以提供反馈，但 Java 是最终校验者。
- 保存使用 `If-Match`、幂等键并提交完整布局；取消只丢弃前端 draft；恢复默认是明确 mutation。
- 日/周/月/年共享 `financial-overview/desktop` 布局。新增启用指标若无 item，后端以确定性首次可用位置补入；归档/禁用指标不返回卡片，其孤立 item 在下一次成功布局保存/重置时清理。
- 布局算法或第三方库不得成为数据库格式；持久化只保存规范坐标和尺寸，以便以后替换 UI 实现。

## 8. API 总体契约

中级模型必须据此新建具体 API 文档，不得直接编码。最低资源：

| 用例 | 方法/资源 | 关键约束 |
| --- | --- | --- |
| 指标列表/详情 | GET `/metrics`, GET `/metrics/{id}` | 默认不返回全部历史 AST；返回 current version 摘要与引用解释 |
| 新建指标 | POST `/metrics` | 客户端 UUID、metric+formula 单事务、幂等 |
| 替换指标/公式 | PUT `/metrics/{id}` | 完整草稿、If-Match、新不可变 version |
| 归档指标 | DELETE `/metrics/{id}` | If-Match；活动依赖冲突拒绝 |
| 验证/预览 | POST `/formulas/validate`, POST `/formulas/preview` | 只读；preview 带 granularity/anchor |
| 版本列表 | GET `/formulas/{id}/versions` | 有界分页，不返回其他 profile |
| 总览读取 | GET `/dashboard?granularity=&anchor=` | 返回规范期间、current/previous、连续趋势桶、layout 和 dataRevision |
| 布局读取/保存 | GET/PUT `/dashboard/layout` | 固定 viewKey/breakpoint；PUT 完整替换、If-Match、幂等 |
| 恢复默认 | POST `/dashboard/layout/reset` | revision/幂等；确定性默认布局 |

Dashboard 读取一次返回全部启用卡片，不能按卡片产生客户端 N 个请求。响应必须区分原始 decimal string、display format、precision、dataStatus、previous status 与 change status；隐藏项不得在旁路字段泄漏明文。

## 9. 一致性、并发、删除和故障

- 所有查询隐含 active profile；请求不接收 profileId。profile 切换使旧 dashboard、metric revision、layout revision 和 pending draft 失效。
- 记录 create/update/trash/restore 不同步写指标表；其已有 dataRevision 变化让下一次 dashboard 重算。
- 自定义指标的 metric、formula version、dependency、current pointer 与 operation 必须原子；layout 与 items/operation 必须原子。
- 同幂等键重放返回首次成功结果；同键不同请求冲突。If-Match 过期不覆盖他处修改。
- 系统指标只能调整 visibility，不能改名、换公式或归档。自定义指标被活动公式引用时不能归档。
- 数据库重开时必须重新验证当前 AST/dependency 基本一致性；发现损坏进入既有恢复/诊断路径，不能静默跳过后返回错误金额。

## 10. 性能、安全与可观测性

- 单 profile 20,000 条记录、最多 50 个启用指标、100 个布局 item；常用 dashboard P95 暂定 750 ms。
- 数据访问应先聚合再组装；禁止按指标、分类引用或趋势桶形成无界 N+1 查询。具体 SQL 与索引计划由中级 persistence 规格固定。
- 首版不持久化结果缓存。允许单请求内 memoization；若以后增加跨请求内存缓存，key 必须包含 profile、dataRevision、formulaVersion、granularity、range/asOf。
- AST 是不可信输入：严格 schema、大小、深度、节点、常量和引用上限；不做动态类加载、反射或表达式 eval。
- 日志可记录 route、requestId、granularity、指标/节点/桶数量、duration、status、errorCode 和脱敏 profile hash；禁止记录金额、名称、AST、tokens、备注或完整引用 ID。

## 11. 高级验收矩阵

| 用户操作 | 前端结果 | 跨层数据 | 业务结果 | 持久化结果 | 关键边界 |
| --- | --- | --- | --- | --- | --- |
| 打开当前月总览 | 八张默认卡和规范日期 | MONTH+anchor → range/asOf | 发生/结算口径正确 | 只读 | 空账本、今天为月初 |
| 切换四种粒度/上一期 | 值、比较、趋势同步 | current+previous+buckets | 周一/月末/跨年正确 | 只读 | 闰年、未来期间 |
| 发生日与结算日不同 | 收支与现金流落在不同桶 | occurredOn/settlementOn | 两套口径互不混淆 | 只读 | 月末跨期结算 |
| 创建分类支出占比 | 中文公式可预览并保存 | category UUID AST | 含当前后代，零分母不可计算 | 四表+operation 原子 | 改名、重新归类、归档 |
| A/B 相互引用 | 明确循环提示 | 候选依赖图 | 不计算候选版本 | 零写入、旧版本有效 | 长依赖链、节点 257 |
| 修改已用指标公式 | 历史期间随当前版本重算 | If-Match+新 AST | 依赖顺序正确 | 旧 version 不变、新 version 生效 | stale revision、重复 key |
| 拖动/缩放并重启 | 布局保持且内容完整 | 完整 12 列 layout | 不影响指标值 | layout/items 原子 | 重叠、越界、未知 widget |
| 删除/恢复一笔记录 | 刷新后指标相应变化 | 新 dataRevision | 回收站不计、恢复重计 | 不写指标缓存 | 旧响应、profile 切换 |

## 12. 中级模型交付要求与升级条件

中级模型已按依赖顺序完成下列产物；实施者必须先阅读，而不是跳过规格直接编码：

1. `docs/api/metrics-formulas-api.md`：所有 DTO、AST JSON schema、错误 field path、示例、幂等与并发。
2. `docs/api/dashboard-api.md`：四级期间、卡片/比较/趋势/layout DTO、隐藏值投影和缓存头。
3. [P5 技术设计](../design/P5-metrics-formulas-dashboard-technical-design.md)：period、metric facts、formula graph/evaluator、dashboard projection、layout validator、Vue interaction 及精确 V005/seed。
4. [P5-001 至 P5-008 Task Spec](../tasks/README.md#36-p5-指标公式与财务总览)：迁移→领域→persistence→application→HTTP→Vue 指标/公式→Vue 总览/layout→跨层 E2E；每项注明允许文件、契约 fixture 和真实链路。

必须升级给高级模型的情况：

- 需要新增记录字段、record type、direct metric/assignment、资产/分摊/应付款或改变基础余额规则。
- 需要改变发生日/结算日、周起点、历史按当前公式重算、四种粒度共享布局或系统稳定 ID。
- 需要加入 IF/布尔/字符串/日期函数、任意脚本、跨 profile 引用或持久化计算缓存。
- 数据库设计无法用前向 migration 实现，或必须修改 V001–V004。
- 成熟拖拽库需要不兼容许可证、Node integration、远程资源、不可控 DOM 格式或改变持久化坐标契约。
- 性能验证证明 20,000/50 目标无法在现有架构内达到，且优化 SQL/序列化后仍超标。
