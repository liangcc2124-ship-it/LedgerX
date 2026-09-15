# P3-003：资金账户 REST API 与余额投影

- 状态：PARTIAL（基础实现已接入；完整 fixtures/边界验收待补）
- 单一结果：active profile 的账户可经受认证 REST API 列出、创建、完整替换和归档；余额完全由期初余额和已结算记录在 Java/SQLite 中推导，不持久化累计值。

## 1. 背景、目标、范围与非目标

P3-001 提供固定的“现金储备”系统账户，P3-002 已占用并稳定了 HTTP 分类路由。本任务在同一已接受架构下实现账户 API 和金额精确的余额投影；后续 records API 才会提供用户写入现金流的入口。

范围：GET/POST /api/v1/accounts，PUT/DELETE /api/v1/accounts/{id}；账户 DTO、asOf/cursor、AccountKind 映射、余额查询、事务/幂等、HTTP 路由、fixture、Java 测试和文档。

非目标：创建财务记录、可用现金汇总 endpoint、转账、账户恢复/物理删除/合并、账户排序、资产/负债报表、Vue/Electron、migration 或依赖变更。

## 2. 前置依赖及引用规范

- 前置：P3-001 PASS，P3-002 PASS。P3-003 在 P3-002 之后执行，因为两项都需小范围改 LedgerHttpServer、ProfileApplicationService 和 LedgerCatalogRepository。
- 必读：AGENTS.md；docs/api.md 第 1–10、12–13 节；docs/api/ledger-records-api.md 第 1、2.2、4 节；docs/database.md 第 1、4.2、4.3、6、7 节；docs/modules/ledger-records.md 第 2–7 节；docs/contracts/core-catalog-v1.md；P3-001、P3-002 与任务总表。
- 复用 P3-002 建立的 application gate、active profile 上下文、LedgerCatalogRepository、processed_operation、HTTP 认证/error/JSON 机制。不得创建第二个 ledger writer 或重复幂等实现。

## 3. 允许/预计修改的模块和文件

- 新增 application/ledger 下仅账户所需的 AccountApi、AccountApiResult、AccountMutation、AccountException、AccountPatch/值对象和确定性余额计算值对象；不得增加泛型框架。
- ProfileApplicationService，仅为实现 AccountApi、active ledger 账户用例、dataRevision 更新、capabilities 增加 accounts.read/accounts.write；如 P3-002 已提取共用 private helper，只在不改变分类可观察行为的前提下复用。
- P3-002 创建的 LedgerCatalogRepository，仅追加 financial_account SQL、余额 projection 和必要的 future finance_record 只读查询。
- LedgerHttpServer，仅追加账户依赖注入、路由、query/body/header 解析、既有 error mapping；不得重排或重写 P3-002 分类代码。
- ApplicationBootstrap.java，仅将生产传入的可替换 Clock 改为 Clock.systemDefaultZone，以符合全局 API 的“今天由后端 Windows 时区决定”。测试仍可传固定 Clock；不得改 Instant 存储为本地时间。
- 分类相关测试可在必要时最小更新以覆盖新增 capability；新增账户 application/persistence/http 测试、docs/contracts/api-v1/accounts fixtures、docs/api/ledger-records-api.md、docs/modules/ledger-records.md、本任务、docs/verification/P3-003-accounts-rest.md。

禁止修改 seed/migration、全局 API、records/assets、Vue/Electron、pom.xml、旧 WPF/React。不得新增账户恢复、转账、现金汇总或报表 endpoint。

## 4. 实现步骤与必须遵守的接口、金额和持久化契约

### 4.1 DTO、日期与余额

1. AccountSummary 字段和语义精确等于 ledger-records API 2.2。openingBalance 与 balance 都是两位 CNY 规范十进制字符串；不得暴露 amount_minor 或让 Vue/SQLite 浮点计算。ETag 必须等于 revision。
2. GET accounts 只接受 asOf、includeArchived、limit、cursor。asOf 缺失时为 LocalDate.now(clock)，其中生产 clock 的 zone 是 Windows system default；显式 asOf 是严格 YYYY-MM-DD。includeArchived 默认 false、limit 默认 50且为1–200。
3. list 排序为 ACTIVE 后 ARCHIVED，再 name 的不区分大小写升序、id 升序。cursor 绑定 API major、asOf、includeArchived、active profile dataRevision 与全部排序 key；任何 filter/revision 不同、空/重复/未知 query 或坏 cursor 都返回 400 VALIDATION_FAILED。
4. balance 计算只能读取 financial_account 与 finance_record：asOf 早于 openingOn 返回 0.00；否则从 openingBalanceMinor 开始，只计 deleted_at is null、settlement_mode=PAID_FROM_ACCOUNT、account_id 匹配、settlement_on 不晚于 asOf 的基础记录。INCOME 对 ASSET 加金额、对 LIABILITY 减金额；FIXED_COST、VARIABLE_COST 对 ASSET 减金额、对 LIABILITY 加金额；其他保留 record_type 不属于基础版，余额投影忽略且不得修改。全部计算使用 long 分并检查溢出，最终转固定两位字符串。
5. 余额查询不写表、不修改 dataRevision；没有 finance_record 时，openingOn 当日及以后 balance=openingBalance，之前=0.00。归档账户在 includeArchived=true 时照算并返回，但不能计入未来可用现金汇总。

### 4.2 创建和完整替换

1. POST body 只接受 id、name、kind、openingOn、openingBalance、currency、includeInAvailableCash。每项都必需，且不得为 null。id 是小写连字符 UUID；name trim 后1–100；kind 必须是七项 AccountKind；openingOn 严格 LocalDate；currency 只能 CNY。
2. openingBalance 必须是规范带符号 CNY 字符串：0，正数，或非零负数，最多两位小数；拒绝 JSON number、指数、千分位、加号、空格、-0、超过 long 分范围和第三位小数。响应始终格式化为两位小数。
3. 服务端按 kind 推导 balanceSide：CASH/BANK/WALLET/OTHER_ASSET 为 ASSET；CREDIT/LOAN/OTHER_LIABILITY 为 LIABILITY。LIABILITY + includeInAvailableCash=true 为 400 VALIDATION_FAILED，fieldErrors.includeInAvailableCash；任何客户端 balance、balanceSide、revision、status、isSystem 或 archived 字段都按全局未知字段规则忽略，绝不写入。
4. POST 新建 isSystem=false、archivedAt=null、revision=0。活动名称不区分大小写唯一；冲突映射为 409 REFERENCE_CONFLICT。成功 201，Location=/api/v1/accounts/{id}，ETag="0"，data.account。
5. PUT 只接受完整 name、kind、openingOn、openingBalance、currency、includeInAvailableCash；path id + If-Match 必需。目标必须是 ACTIVE，系统账户也允许 replace。成功完整 replace（即使值相同）让 account revision 和 dataRevision 各加一。
6. PUT openingOn 不得晚于该账户最早一条 ACTIVE、PAID_FROM_ACCOUNT finance_record 的 settlement_on；否则 409 REFERENCE_CONFLICT，details.earliestSettlementOn 是 YYYY-MM-DD。TRASHED、NON_CASH、INCLUDED_IN_OPENING_BALANCE、CREATE_PAYABLE 与其他账户记录不阻止。此查询使用参数化 SQL，返回前不得暴露记录内容。

### 4.3 归档、事务和错误

1. DELETE 仅归档 ACTIVE 非系统账户；须 If-Match、Idempotency-Key、无 body。历史 finance_record 引用不阻止；成功写 archived_at、updated_at、revision+1、ledger dataRevision+1，返回 ARCHIVED AccountSummary 和 ETag。
2. 系统账户、已归档账户为 409 REFERENCE_CONFLICT；不存在为404；stale If-Match 为409 REVISION_CONFLICT和 details.currentRevision；缺 If-Match 为428；缺/错 mutation key、认证、origin、content type、request ID 都沿全局规范。
3. 所有 mutation 按 P3-002 的同一 gate 和同一事务策略完成账户写、ledger_meta、response 和 processed_operation，并在 commit 后刷新 activeContext dataRevision。相同 key+method+canonical path+body hash 重放首次 response；相同 key不同请求409 IDEMPOTENCY_CONFLICT；提交前失败不建 operation。

## 5. 用户操作、前端反馈、跨层数据和边界

| 用户操作 | 前端预期反馈 | 关键字段 | Java/SQLite 结果 | 边界 |
| --- | --- | --- | --- | --- |
| 读取账户余额 | 精确两位金额与 ASSET/LIABILITY | asOf、cursor | 只读投影，无累计余额列 | 空记录、openingOn 前、归档筛选 |
| 新建银行卡 | 201、ASSET、可用现金标记 | id、kind=BANK、openingBalance | account 一行、revision=0 | 重名、格式错误、负数 |
| 新建信用账户 | 201、LIABILITY、不可计可用现金 | kind=CREDIT | 服务器推导 side | include true 必须拒绝 |
| 编辑系统现金储备 | 成功 ETag 更新 | If-Match、完整 body | 保留 isSystem，更新可编辑字段 | stale ETag、openingOn 受记录限制 |
| 归档自定义账户 | ARCHIVED 且后续不可新选 | If-Match、key | 历史引用仍保留 | system/已归档不可归档 |

## 6. 验收标准

- seed 后 GET accounts 默认仅含“现金储备”，其 ID/字段符合 core-catalog-v1；asOf 在其 openingOn 前返回 0.00，当日及后返回 0.00。
- 创建 BANK openingBalance=100.25、CREDIT openingBalance=500.00、OTHER_ASSET openingBalance=-2.50 后，响应/重开均精确显示相同两位金额和规定 BalanceSide；负债带 includeInAvailableCash=true 被拒绝。
- 用真实 finance_record fixture 验证资产账户收入+100、基础成本-30、负债账户收入-100、基础成本+30；TRASHED、非匹配账户、未来结算日、非现金模式和其他保留 record_type 不影响余额；溢出被拒绝或安全映射，绝不静默绕回。
- replace 的 openingOn 晚于最早 ACTIVE 结算日时返回指定 409 并零写入；TRASHED 记录不阻断。系统账户可 edit 不可 archive；自定义账户可 archive、重开仍为 ARCHIVED。
- 所有 mutation 的 idempotency replay、冲突、ETag、dataRevision、origin/token、错误 envelope 和 cursor 都符合全局与账户 API 文档；P3-002 分类行为不回归。
- 全量 Maven test package 通过；账户 fixtures 同时由 Java HTTP 测试读取。未添加 records/Vue/Electron。

## 7. 测试层次、命令与真实链路

- 单元：金额解析/格式、kind→side、余额方向、asOf、溢出与 openingOn 规则。
- SQLite 集成：account CRUD/archive、future finance_record fixture、dataRevision/operation、reopen、cursor。
- HTTP：headers/auth/origin、query/body、ETag/Location、fixture 和错误码。
- 必跑：仓库根 .\mvnw.cmd -q '-Dmaven.compiler.fork=true' test package；报告实际 Surefire 数量和 clock 时区测试。
- 真实原生链路：本任务要求真实 Java HttpServer→sqlite-jdbc；Vue/Electron/发布包/快捷方式不适用。

## 8. 禁止事项、升级、文档与完成报告

禁止累计/缓存余额列、SQLite 浮点金额计算、修改全局时区语义以外的启动流程、账户恢复/物理删除/合并、可用现金 endpoint、转账、records UI/API、seed/migration 变更、用客户端传入 BalanceSide 或绕过 active profile。

发现以下任一情况停止并升级：P3-002 未 PASS；账户 API 与全局金额/日期规范冲突；需要新 schema/索引；真实 records 已实现而结算语义不同；Clock.systemDefaultZone 改动导致已验证启动/状态行为变化；无法让 catalog 和 ledger 幂等 key 防冲突。

完成报告写明：修改文件；新增/总测试数量；金额/方向/asOf/ETag/幂等/SQLite 重开/HTTP 证据；是否真实 Java→SQLite；Vue/Electron/发布包/快捷方式不适用原因；未测试项、残余 records 风险和文档同步。不得标记收支记录、可用现金汇总或桌面 UI 已完成。
