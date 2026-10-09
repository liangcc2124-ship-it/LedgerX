# P7-002：账本初始化、账户启用日期与历史补录

- 状态：PASS（实现、文档和隔离真实浏览器验收完成；2026-09-23）
- 验证记录：[P7-002 验收证据](../verification/P7-002-ledger-initialization-account-opening.md)
- 类型：领域行为修复、schema migration、HTTP/Vue 跨层功能
- 依赖：P7-004 本机浏览器运行时；初始化规则需先于普通记账主流程
- 架构裁决：[ADR-015](../decisions/ADR-015-ledger-initialization.md)
- 规模：产品小型、功能中等；复用现有 Java 单体/JDBC/SQLite 与 Vue，不增加框架或服务

## 目标与非目标

消除 V004 执行机器的本地日期作为默认账户开户日的隐式业务语义。新账本必须经用户明确设定账本起始日和默认账户期初余额后才能记账；已有数据只在规则一致时自动迁移，其他可解释情况要求用户确认，结构或完整性损坏则迁移整体回滚。

不导入旧 JSON，不重算或改写历史记录，不改变余额方向、金额精度、分类规则或回收站语义；不允许 Vue 推导初始化状态。起始日完成后不可在本任务中修改，未来如需调整须另立带历史校验的任务。

## 持久化与迁移合同

新增 V006，只能追加迁移，不改写 V001–V005。`ledger_setting` 增加：

| 字段 | 约束/含义 |
| --- | --- |
| `setup_state` | `PENDING`、`REVIEW_REQUIRED` 或 `COMPLETED`，默认 `PENDING` |
| `ledger_start_on` | ISO `YYYY-MM-DD`；完成时必填，未完成时为空 |
| `setup_completed_at` | UTC instant；完成时必填，未完成时为空 |

状态含义：

- `PENDING`：全新空账本，或仅含完全未改动的 V004 默认系统账户；等待首次设定。
- `REVIEW_REQUIRED`：旧账本存在账户配置或可通过显式改正开户日而解决的历史日期冲突；只提供初始化预览及明确确认，不开放普通记账和编辑。
- `COMPLETED`：账本起始日已经确认，所有记录日期和账户启用日均满足下述不变量。
- SQLite `quick_check` / `foreign_key_check` 失败、日期不是严格 ISO、引用缺失、必需字段为空或结算模式未知时，V006 整体失败并回滚；服务进入既有 `RECOVERY_REQUIRED`，不得把坏数据伪装成可初始化状态。

V006 的 DDL、Java 分类/backfill 和 `schema_history` 行必须处在同一个 SQLite migration transaction。Java hook 使用注入 `Clock` 写 `setup_completed_at`；迁移不得使用 SQL `date('now')`、本机时区或执行日推断账本日期。迁移只写新增 setup 字段，不改账户、记录、现有实体 revision、settings revision 或 data revision。

旧账本分类算法（覆盖全部账户，包括归档账户；记录包括 ACTIVE 与 TRASHED）：

1. 新建数据库保持 `PENDING`，`ledger_start_on` 与完成时间为空。
2. 旧库无记录且恰好只有固定 ID 的 V004 默认账户，并且其名称、CASH/ASSET、0 分期初余额、计入可用现金、未归档、revision=0 等 seed 字段均未改变：`PENDING`。偏好设置、自定义分类或指标布局不影响判断。
3. 旧库无记录但有自定义账户，或默认账户已被修改/非零/归档：`REVIEW_REQUIRED`；保留全部账户原值，初始化预览说明将更改的默认账户字段。
4. 旧库有记录且日期/引用/模式结构有效、每条 PAID_FROM_ACCOUNT 记录的账户存在、结算日期非空：若所有结算日期不早于账户开户日则自动 `COMPLETED`；否则 `REVIEW_REQUIRED`，不改历史。未知或结构损坏则回滚迁移。
5. 自动完成的 `ledger_start_on` 是所有记录 `occurred_on`、非空 `settlement_on` 和所有账户 `opening_on` 的最早观测业务日期。只有完全未改动且未被任何记录引用的 V004 默认账户，其安装日 `opening_on` 才从候选集合排除。包含回收站记录，保证恢复行为不因迁移而失效。

## 领域不变量

- 账本起始日只能由用户输入；日期计算以显式业务时区 `Asia/Shanghai` 与注入 `Clock` 计算“今天”。持久化的瞬时仍为 UTC。
- 完成后，所有新建/编辑记录的 `occurredOn` 和 `settlementOn` 必须不早于 `ledger_start_on`。
- 活动及回收站 `PAID_FROM_ACCOUNT` 记录均保持 `settlementOn >= account.openingOn`；新建或编辑违反时返回 `400 ACCOUNT_NOT_OPEN_ON_SETTLEMENT_DATE`，并提供 `fieldErrors.settlement.settlementOn`。违反账本起始日时为 `400 VALIDATION_FAILED`，分别定位发生日/结算日。
- 所有账户的 `openingOn >= ledger_start_on`。账户编辑不得把开户日移到任一 ACTIVE 或 TRASHED 已结算记录的最早 `settlementOn` 之后；回收站记录必须仍可恢复。
- 日期值只在 Java 领域层校验；SQLite 存储 ISO 日期字符串。不放宽校验、不修补历史记录、不自动移动用户开户日。

## API 与前端状态

`GET /api/v1/system/status` 增加 `setupState` 和 `ledgerStartOn`。PENDING/REVIEW_REQUIRED 时服务端能力仅为 `system.status`、`profiles.read`、`profiles.write`、`ledger.initialization.read`、`ledger.initialization.write`、`operations.read`。Vue 只显示初始化/审查页面，不挂载普通业务导航；HTTP 和 application 层都必须拦截业务读写，以 `409 LEDGER_SETUP_REQUIRED` 返回稳定错误。

`GET /api/v1/ledger-initialization` 返回状态、建议起始日、默认系统账户当前/建议字段和需修正开户日的账户摘要，不返回 SQL、路径或不相关账本内容。`REVIEW_REQUIRED` 预览须清楚列出需要更早开户日的账户及其最早阻断结算日。普通业务 endpoint 在未完成时不可被绕过。

`POST /api/v1/ledger-initialization` 的闭合请求体：

```json
{
  "ledgerStartOn": "2026-08-01",
  "defaultAccountOpeningOn": "2026-08-01",
  "openingBalance": "2500.00",
  "accountName": "现金储备",
  "confirmExistingData": false,
  "accountOpeningDates": []
}
```

`accountName` 仅指 V004 固定默认账户，不是 profile 名；省略时保留现有名称。新账本 `confirmExistingData=false`、`defaultAccountOpeningOn=ledgerStartOn`、`accountOpeningDates=[]`。REVIEW_REQUIRED 必须 `confirmExistingData=true`，并且 `accountOpeningDates` 必须按 accountId 升序、恰好包含预览列出的所有非默认冲突账户（ID 不重复、无额外 ID）；默认账户的开户日由单独字段明确提交，其余账户不变。所有开户日必须 >= ledgerStartOn 且不得晚于该账户任一活动/回收站结算日。默认系统账户的 `openingOn` 和 `openingBalance` 按用户提交值原子更新。review 页面须展示迁移前默认账户日期/余额，并预填原值供用户明确确认，避免静默移动期初事实。

请求必须带 `Idempotency-Key` UUID。事务先查 operation，再判断 setupState：相同 key/body 重放原状态码与完整响应，不同 body 返回 `409 IDEMPOTENCY_CONFLICT`；不同 key 在 COMPLETED 后返回 `409 ALREADY_INITIALIZED`。首次成功在同一 SQLite transaction 更新 setup state/start/completion、默认账户与经用户确认的开户日、`ledger_setting.revision`、`ledger_meta.data_revision` 和 `processed_operation`；设置 revision 与 data revision 各加一。默认账户可见字段变化时其 entity revision 加一，否则不变。任一失败全部回滚；同 key 并发只提交一次。

## 六列验收矩阵

| 用户操作 | 前端结果 | 跨层数据 | 业务结果 | 持久化结果 | 边界情况 |
| --- | --- | --- | --- | --- | --- |
| 首次打开新空间 | 显示起始日、账户名称/期初余额表单；记账导航不可用 | setupState、ledgerStartOn、defaultAccountOpeningOn、openingBalance、幂等键 | Java 独占校验，今天由 Asia/Shanghai + Clock 决定 | V006 PENDING；初始化事务一次提交 | 刷新、浏览器重开、未来日期、非法金额 |
| 选择上月第一日并完成 | 显示成功后开放导航 | ledgerStartOn 与默认账户金额 | 允许该日起及之后的发生/结算日期 | setup COMPLETED；revision/operation 原子更新 | 月末、跨年、闰日、不同 host timezone |
| 输入早于起始日记录 | 字段内说明具体发生日/结算日冲突 | occurredOn/settlementOn | 不调用或不产生部分写入 | record、revision、operation 均不变 | 两日期分别早于起始日 |
| 结算早于账户开户日 | 结算日字段错误明确 | accountId、openingOn、settlementOn | `ACCOUNT_NOT_OPEN_ON_SETTLEMENT_DATE` | 零写入 | ACTIVE/TRASHED；账户先归档 |
| V005→V006 有效历史数据 | 不打断用户；起始日可解释 | 全记录/全部账户历史 | 自动使用最早观测日并完成 | 历史值/revision/dataRevision 不变；只新增 setup 字段/history | ACTIVE/TRASHED、归档/未归档账户 |
| V005→V006 模糊但可修旧数据 | 显示审查页和需要修正的账户日期 | review summary、confirmExistingData、开户日列表 | 用户确认后完成，不改记录 | 除明确账户日期和初始化字段外原样保留 | 空账本自定义账户、改过默认账户、被回收记录阻断 |
| 损坏库或迁移中断 | 进入恢复提示，不显示半初始化成功 | 不产生业务 API 调用 | migration transaction 全回滚 | 仍可按 V005 打开/备份；重试幂等 | bad date、FK、未知 mode、注入失败、进程重启 |
| 初始化请求丢失/重复/并发 | 展示原成功结果或可查操作 | 同 key 重试 | 同 body 重放、异 body 冲突、不同 key 仅一个成功 | 一条 operation、单次 revision 增长 | 响应丢失、不同 key 并发、重启后查 operation |

## 文件范围、测试和禁止事项

- 允许范围：`src/main/resources/db/ledger/migration/V006*`；persistence migration hook/repository/snapshot；system/status 与初始化 application/API/HTTP DTO；记录和账户领域验证；Vue 首次 setup view 与 API contract；受影响测试与需求/架构/数据库/API/使用文档。
- 持久化：fresh、V005 原始空账本、空但已编辑账户、多账户、正常历史、仅 TRASHED 历史、归档账户、回收站日期冲突、非 ISO 日期、孤儿引用/外键、未知模式；断言 DDL+setup+history 同事务回滚、迁移重复开库幂等、旧金额和所有 revision 不变。
- domain/HTTP：Clock 固定、金额分单位、date floor 两字段、账户开户日修改（ACTIVE/TRASHED）、状态能力与服务端 gate、完整字段错误、幂等重放/冲突/并发、初始化事务故障注入。
- 真实浏览器：隔离数据根新空间 PENDING → 初始化 → 录入起始日历史记录 → 刷新/重启 → 余额一致；不使用生产默认数据目录和 mock 代替真实 Java 路径。
- 禁止固定 `1970-01-01`、安装日减固定年数、改历史记录/金额、测试中改系统时间、由 Vue 保存或推断 setup state、只隐藏入口而不做服务端 gate、忽略 TRASHED 约束、把损坏数据降级成 PENDING、改变已成功迁移或扩大到旧 JSON 导入。
