# 模块规格：allocation-assets

- 状态：按 ADR-010 延后；不是基础记账版卡点，用户重新确认需要后才恢复设计
- 边界：保留的未来设计草案；固定成本服务期分摊、固定资产档案、折旧、购置记录关联、处置与恢复。引用：[ADR-010](../decisions/ADR-010-basic-ledger-scope.md)。基础版不得据此实现 endpoint。

## 1. 职责、术语、用例与非目标

术语：`serviceEndExclusive`（服务期不含结束日）、`recognition`（成本确认而非现金结算）、`allocation slice`（一个日期区间的已确认金额）、`depreciable base=cost-salvage`、`book value=cost-accumulated depreciation`。

用例：给固定成本配置服务期并试算分摊；新增固定资产购置时同步建立资产；预览折旧；更新资产参数；处置/恢复资产；报告期计算已确认成本、累计折旧和账面价值。非目标：库存、资产盘点、转让会计分录、处置收益的现金记录、自动周期扣款、任意脚本公式。

## 2. 入口、输出与依赖方向

| 项目 | 内容 |
| --- | --- |
| 入口 | `POST/PUT /api/v1/records` 中的固定成本/资产草稿；`POST /allocations/preview`、`PATCH /assets/{id}`、`POST /assets/{id}/dispose|restore` 等待具体 API 文档固定。 |
| 输出 | allocation/depreciation 试算切片、资产 DTO/status/revision、供 metrics 的确认金额/账面价值值对象。 |
| 依赖方 | ledger-records、metrics-formulas、reports-warnings、ui-integration。 |
| 被依赖方 | ledger-records 的公开 record 值对象、metrics-formulas 的已验证 `ALLOCATION`/`DEPRECIATION` 公式、persistence/application 事务。 |
| 方向 | `assets → ledger public values`，`metrics → assets read model`；任何模块都不得绕过本模块直接更新 `allocation_plan`/`fixed_asset`。 |

## 3. 实体、字段、权限与不变量

### 3.1 `allocation_plan`

- 仅 `FIXED_COST` 可有一个 plan；`recordId` 唯一，`amountMinor` 必须等于母记录金额，`serviceEndExclusive > serviceStart`。
- `recognitionMethod`：`IMMEDIATE`、`STRAIGHT_LINE_DAILY`、`NATURAL_MONTH`、`PREVIOUS_PERIOD`、`CUSTOM_FORMULA`。custom 必有活动、scope 为 `ALLOCATION` 的不可变 `formula_version`。
- 对 `DAY/WEEK/MONTH/YEAR` cadence，服务结束日由 `serviceStart.plusDays/plusWeeks/plusMonths/plusYears` 推导并覆盖前端值；`CUSTOM` 必须显式传有效结束日。一次性立即确认用 `allocation=null`，不发明额外 cadence。
- `IMMEDIATE` 在 `serviceStart` 所在期间确认全额；`PREVIOUS_PERIOD` 在 `serviceStart - 1 day` 所在期间确认全额；二者仍不改变结算现金日。

### 3.2 `fixed_asset`

- 一项已保存资产必须有唯一 `acquisitionRecordId`，且记录类型为 `FIXED_ASSET_PURCHASE`；成本必须与购置记录金额相等。新建资产只允许作为该记录的同一事务组成部分，独立资产 endpoint 只能更新既有资产。
- `name` trim 后非空；`0 ≤ salvage ≤ cost`；`inServiceOn` 为自然日；`usedUnits ≥ 0`；工作量法 `totalUnits > 0`。金额字段为 CNY 分，数量用规范十进制字符串。
- 方法：`STRAIGHT_LINE`、`UNITS_OF_PRODUCTION`、`DOUBLE_DECLINING_BALANCE`、`SUM_OF_YEARS_DIGITS`、`CUSTOM_FORMULA`。custom 只接受 scope `DEPRECIATION` 的有效公式版本。
- `ACTIVE` 是可折旧资产；`DISPOSED` 必须有 `disposedOn` 和非负 `disposalProceedsMinor`；`ARCHIVED` 表示关联购置记录在回收站，不能参与指标；`DRAFT` 仅允许旧数据导入诊断/修复，不能由公开 API 创建，也不参与指标。

无应用锁的当前安全模型下，只有 active profile 的已验证页面可操作；更新/处置/恢复均需 `expectedRevision`。资产和分摊字段不允许独立绕过其母记录。

## 4. 状态、转换与失败补偿

```text
DRAFT ──修复并关联购置记录──> ACTIVE ──dispose──> DISPOSED
                              │                      │
                         record trash             restore
                              ▼                      ▼
                           ARCHIVED <── record trash ACTIVE
```

| 操作 | 前置/原子结果 | 失败行为 |
| --- | --- | --- |
| 新购置记录 | record、asset、可选分摊与 metric assignment 在一个 transaction 保存；资产即 `ACTIVE`。 | 任一验证失败，全部回滚。 |
| 更新资产 | 既有非 `ARCHIVED` 资产、ETag 匹配；不能改 acquisition record/cost 与母记录不一致。 | `REFERENCE_CONFLICT`/`REVISION_CONFLICT`，不部分更新。 |
| 处置资产 | 仅 `ACTIVE`；`disposedOn ≥ inServiceOn`；设置处置日期/收益，状态为 `DISPOSED`。 | 处置不创建现金记录；I/O/DB 失败回滚。 |
| 恢复资产 | 仅 `DISPOSED`；清除处置日与收益，返回 `ACTIVE`。 | 不恢复已被 trash 的购置记录；该状态先走 record restore。 |
| record trash/restore | 由 ledger-records 调用转换 `ACTIVE ↔ ARCHIVED`；`DISPOSED` 不被改写。 | 与记录同事务。 |
| permanent purge | 由 ledger-records 仅在 `ARCHIVED` 时删除资产，再删记录。 | 有其他引用时 `REFERENCE_CONFLICT`。 |

## 5. 计算、异常与边界行为

报告期间使用全局半开区间 `[start,endExclusive)`。分摊先取服务期与报告期交集；无交集为 `0`。直线按重叠天数/总服务天数，natural month 按服务期触及的自然月等额，custom 使用仅允许的变量 `totalAmount,totalDays,overlapDays,elapsedDays,remainingDays`。切片金额在成为 CNY 输出时按 `HALF_UP` 到分，最后一个非 custom 切片取“原总额−前面已舍入切片”，保证全部切片和严格等于原金额；自定义公式结果若为负、超过原额累计或不能精确表达为两位 CNY，返回 `FORMULA_INVALID`/`VALIDATION_FAILED`，不静默截断。

折旧从 `inServiceOn` 起按月生成，截止 `min(asOf+1 day, inServiceOn+usefulLifeMonths, disposedOn+1 day)`；因此处置日当期仍计入该日之前已形成的月度切片，之后为零。每期金额为：

| 方法 | 未舍入公式 |
| --- | --- |
| 直线法 | `(cost-salvage)/usefulLifeMonths` |
| 工作量法 | `(cost-salvage)*unitsUsed/totalUnits` |
| 双倍余额递减 | `(cost-accumulated)*2/usefulLifeMonths` |
| 年数总和 | `(cost-salvage)*(usefulLifeMonths-periodIndex+1)/(n*(n+1)/2)` |
| 自定义 | 受限 AST，变量为 `cost,salvageValue,depreciableBase,usefulLifeMonths,elapsedMonths,periodIndex,bookValue,totalUnits,unitsUsed` |

每期先按 CNY `HALF_UP`，再夹在 `[0, depreciableBase-accumulated]`；最后一期吸收尾差，不让累计折旧超过可折旧金额，账面价值永不低于残值。工作量不足/自定义公式除零使该次计算 `DATA_NOT_COMPUTABLE`，不篡改资产；预览返回状态和原因而非保存。闰年、1/31 加月、0 工作量、已折完、空资产列表均须有黄金测试。

## 6. 数据与 API 映射

| API | 表/协作 | 输出 |
| --- | --- | --- |
| `POST /allocations/preview` | 不写库；`allocation_plan` 草稿 + formula version | 切片 `{start,endExclusive,amount}`、total、method。 |
| `PATCH /assets/{id}` | `fixed_asset` | 资产 DTO + revision；不会单独建立购置记录。 |
| `POST /assets/depreciation-previews` | 不写库 | 当前期/累计/账面价值、method、`dataStatus`。 |
| `POST /assets/{id}/dispose|restore` | `fixed_asset` | status、日期/收益、revision。 |
| `POST/PUT /records` | `finance_record` + `allocation_plan` + `fixed_asset` | 由 ledger-records 返回 record，资产摘要可选嵌入。 |

以上资产专用路径是模块候选，必须在具体 `assets-api.md` 固定全部字段后才能实施；不得仅凭本表猜请求体。

折旧、分摊结果是可重算投影，不能持久化为权威累计列或缓存；公式版本/依赖详情由 metrics-formulas 所有。

## 7. 可观测、验收与测试

事件：`allocation.preview`, `asset.saved`, `asset.disposed`, `asset.restored`, `depreciation.not-computable`；不记录资产名称、金额或 AST。

| 场景 | 结果 | 层次 |
| --- | --- | --- |
| ¥100 服务期 10 天、逐日分摊 | 所有日切片合计严格 ¥100.00，最后一日吸收分差。 | 领域单元 |
| 月末/闰年服务期 | `LocalDate` 推导结束日且各期间不重叠不遗漏。 | 单元/黄金 |
| 购置固定资产 | 记录和资产要么同时出现要么都不出现；成本一致。 | SQLite 事务集成 |
| 每种折旧法 | 账面价值不低于残值、累计不超过可折旧额。 | 单元/黄金 |
| 处置后查询 | 后续期间零折旧、资产不再进入活动资产指标。 | 领域+集成 |
| 垃圾箱恢复购置记录 | `ARCHIVED` 资产恢复为 `ACTIVE`；已处置资产不被错误复活。 | 生命周期 E2E |

## 8. 冲突与待决

数据库把 `fixed_asset.acquisition_record_id` 定为 `NOT NULL UNIQUE`，故当前 C# 可独立保存无购置记录资产的路径不能保留；本规格按已接受数据库不变量收敛为“只允许随购置记录创建”。导入遇到该类旧资产必须记 `migration_issue` 并阻止切换，而不是杜撰购置记录。若产品需独立资产台账，须高级模型改动数据库/需求。
