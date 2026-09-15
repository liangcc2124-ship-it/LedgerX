# P3-002：分类 REST API

- 状态：PARTIAL（基础实现已接入；完整 fixtures/边界验收待补）
- 单一结果：active profile 的分类可经受认证的 REST API 列出、创建、完整替换、归档和合并，并以 SQLite 事务、ETag、幂等和稳定错误语义保护数据。

## 1. 背景、目标、范围与非目标

系统分类及一个默认账户由 P3-001 写入。此任务只把分类管理能力接入 Java application、JDBC 和 HTTP；它不创建财务记录，也不实现账户 API 或 Vue 页面。

范围：GET/POST /api/v1/categories，PUT/DELETE /api/v1/categories/{id}，POST /api/v1/categories/{targetId}/merge；分类 DTO、cursor、校验、事务/幂等、HTTP 路由、共享 fixtures、Java 单元/SQLite/HTTP 测试和实际影响文档。

非目标：账户、records、assets、allocation、metrics、reports、backup、分类恢复或物理删除、拖拽排序、批量归档、重写系统 seed、前端、Electron、任何数据库 migration 或依赖变更。

## 2. 前置依赖及引用规范

- 前置：P3-001 PASS；S0、P1、P2 仍为 PASS。
- 必读：AGENTS.md；docs/api.md 第 1–10、12–13 节；docs/api/ledger-records-api.md 第 1–3 节；docs/database.md 第 1、4.2、4.2.1、6、7 节；docs/modules/ledger-records.md 第 2–7 节；docs/contracts/core-catalog-v1.md；docs/tasks/README.md。
- 现有 profiles/settings 的 application service、事务、processed_operation、HTTP error/envelope 和 fixture 模式是复用基线。不得复制一套不兼容的认证、请求 ID、ETag、幂等或 cursor 机制。

## 3. 允许/预计修改的模块和文件

- 新增 application/ledger 下仅分类 API 所需的 CategoryApi、CategoryApiResult、CategoryMutation、CategoryException、CategoryPatch/值对象；名称可与现有 profile/settings 模式保持一致，但不得创建通用 CRUD 框架。
- src/main/java/com/ledgerx/application/profile/ProfileApplicationService.java，仅为实现 CategoryApi、active ledger 分类用例、dataRevision 快照刷新及 capabilities 增加 categories.read/categories.write。
- 新增或扩展 src/main/java/com/ledgerx/persistence/LedgerCatalogRepository.java，仅分类 SQL、分类 projection 与 processed_operation 的现有复用；不得修改 schema。
- src/main/java/com/ledgerx/http/LedgerHttpServer.java，仅注入/路由/DTO 解析和既有错误映射所需的分类代码；保留既有构造器兼容测试。
- src/test/java/com/ledgerx/application、persistence、http 下与分类直接相关的测试；新增 docs/contracts/api-v1/categories 下精确 fixture；docs/api/ledger-records-api.md、docs/modules/ledger-records.md、本任务、docs/verification/P3-002-categories-rest.md。

禁止修改 migration、accounts API、Vue/Electron、pom.xml、全局 API 规范、系统 seed 契约、旧 WPF/React。P3-003 才拥有账户实现；本任务不得提前创建账户 endpoint 或 UI。

## 4. 实现步骤与必须遵守的接口、业务和持久化契约

### 4.1 统一接线

1. ProfileApplicationService 是 active profile 的唯一所有者。所有分类读写只打开 activeContext 的 ledger 文件；请求体不得接受 profileId。恢复上下文和无 active context 均沿既有模式拒绝写入。
2. API 能力集在分类 API 真正可用后加入 categories.read 和 categories.write；不得在 P3-001 时提前宣称 capability。
3. 每次 category mutation 使用现有 process-local write gate、catalog-operation key 冲突检查、ledger processed_operation 重放、TransactionRunner 和参数化 SQL。成功事务内依次完成业务写、实体 revision、ledger_meta.updated_at/data_revision、成功 response envelope 和 processed_operation；提交后才刷新 activeContext 的 dataRevision。校验、冲突、SQL 失败和 rollback 都不得产生半写、operation 或内存 revision 更新。
4. 用户创建分类 ID 接受小写、带连字符的 36 位 UUID 文本；不要要求 v4，以便系统 seed 的固定 UUID 能被读取。POST 重试仍由 Idempotency-Key 保护；同一已存在 ID 不同请求为 409 IDEMPOTENCY_CONFLICT 或 REFERENCE_CONFLICT，按全局幂等先后规则处理。

### 4.2 DTO 与读取

1. CategorySummary 必须与 ledger-records API 2.1 完全一致，包含 canUseForRecords。status 从 archived_at 投影；recordTypes 按稳定枚举顺序输出；所有 system 或 custom 字段均由服务端读取。
2. GET categories 只允许 includeArchived、limit、cursor。includeArchived 默认 false；limit 默认 50、范围 1–200。false 只返回 ACTIVE；true 返回 ACTIVE 后 ARCHIVED。每个状态内按顶级在前、sortOrder 升序、name 不区分大小写升序、id 升序。空结果仍返回 data.items=[] 和标准 page。
3. Cursor 是不透明 base64url，绑定 API major、includeArchived、active profile dataRevision 和完整排序 key。改变 includeArchived 或 dataRevision，非法编码、空 cursor、未知 query、重复 query 或过期 cursor 均返回 400 VALIDATION_FAILED，fieldErrors.cursor 或 fieldErrors.query，不泄漏 SQL。
4. GET 单资源不是本任务 API；编辑前端必须从已加载 CategorySummary 获取 revision。不得私自增加 GET /categories/{id}。

### 4.3 创建和完整替换

1. POST body 只接受 id、name、parentId；三个字段都必需，parentId 必须显式为 UUID 或 null。name trim 后长度 1–100；保留合法 Unicode 原字符。空白、非字符串、超过长度、非法 UUID、缺失 parentId 或 JSON null 替代整个 body 返回 400 VALIDATION_FAILED，fieldErrors 使用精确请求路径。
2. parentId=null 创建顶级。非 null 父必须存在且 ACTIVE，并且父本身必须顶级；指向已归档/不存在父返回 404 NOT_FOUND，指向第二层父返回 400 VALIDATION_FAILED。数据库 UNIQUE 冲突要稳定映射为 409 REFERENCE_CONFLICT，不暴露 SQLite 错误。
3. 创建 custom 分类时 isSystem=false、isLegacyCustom=false、recordTypes=[]、defaultRecognitionMethod=IMMEDIATE、recommendedDepreciationMethod=null、revision=0、archivedAt=null。sortOrder 使用同 parentId 下活动与归档同级最大值加一，无同级为 0；不得由客户端提供。
4. PUT path id 必须是可见 ACTIVE 非系统分类；If-Match 必须为当前强 ETag。body 仍只接受完整 name、parentId，缺一不可。系统或已归档目标为 409 REFERENCE_CONFLICT；不存在为 404；版本不符为 409 REVISION_CONFLICT，details.currentRevision 为当前整数。名称-only 保留 sortOrder；parent 变化按目标 parent 重新追加 sortOrder。成功 PUT 即使字段值未变也按既有完整 replace 语义递增该分类 revision 和 dataRevision 一次。
5. POST 成功为 201，Location=/api/v1/categories/{id}，ETag="0"；PUT 成功为 200 和新 ETag。响应 data.category 与 ETag/body revision 一致。

### 4.4 归档与合并

1. DELETE 仅可归档 ACTIVE 非系统分类，须 If-Match 和 Idempotency-Key，无 body。若有活动直接子分类，返回 409 REFERENCE_CONFLICT，details.activeChildCount；只存在归档子分类不阻止。历史或回收站 finance_record 引用不阻止归档，也不修改记录。成功时写 archived_at、updated_at、revision+1、dataRevision+1，返回 ARCHIVED CategorySummary 和 ETag。
2. POST /categories/{targetId}/merge 须 target ACTIVE、canUseForRecords=true、If-Match 为 target revision。body 只有 sources，数组去重且长度 1–200；每项只有 id、expectedRevision，均必需。target 不能出现在 source 中，source 必须 ACTIVE 非系统且无活动直接子分类，source/target 不能形成父子环。
3. 合并前查询所有 source 的 ACTIVE 和 TRASHED finance_record。target 为 custom 时可承接所有类型；target 为 system 时其 recordTypes 必须覆盖每种被迁移 recordType。失败分别使用 details.reason=ACTIVE_CHILDREN、TARGET_NOT_RECORD_USABLE、TARGET_RECORD_TYPE_MISMATCH，HTTP 409，事务零写入。
4. 合并成功在一个事务内把所有 source finance_record.category_id 改为 target、每条受影响记录 revision+1/updated_at 更新、每个 source archived_at/updated_at/revision+1、ledger dataRevision+1，并保存 operation。target revision 不因引用变化递增；其 If-Match 只保护目标在命令发出后的身份/状态。响应 data.targetId、mergedIds 按请求顺序、updatedRecordCount 为活动加回收站总数。

## 5. 用户操作、前端反馈、跨层数据和边界

| 用户操作 | 前端预期反馈 | 关键字段 | Java/SQLite 结果 | 边界 |
| --- | --- | --- | --- | --- |
| 读取分类 | 返回树状所需 DTO 和分页 | includeArchived、cursor、canUseForRecords | 只读，无 dataRevision 改变 | 空表、过期 cursor、归档筛选 |
| 新增自定义二级分类 | 201 后显示 revision 0 | id、name、parentId、Idempotency-Key | 一行 category，零 type 行，operation 同事务 | 重名、二级父、归档父 |
| 编辑自定义分类 | 200、ETag 更新 | If-Match、完整 body | revision/dataRevision 各加一 | stale ETag、系统/归档 |
| 归档父分类 | 409 且无假成功 | If-Match、activeChildCount | 零写入 | 仍有活动孩子 |
| 合并两个自定义分类 | 成功后源归档、记录指向目标 | target ETag、sources expectedRevision | 全有或全无更新 | 任一 source stale/无效/类型不匹配 |

## 6. 验收标准

- 在 P3-001 真实 seed 库中，GET 默认只列 71 ACTIVE 分类，并按 API 排序返回 stable DTO；无类型系统父 canUseForRecords=false，系统叶子按类型为 true。
- POST 自定义顶级/二级分类、PUT 改名/移动、DELETE 归档、merge 两个 source 都可重开后正确读取；每个成功 mutation 的 dataRevision 恰加一、operation 可按同 key 同 body 重放。
- 归档有活动直接孩子、移动到二级父、系统编辑/归档/合并、同父活动重名、归档/不存在 parent、stale ETag、混合有效无效 merge、同 key 不同 body 都返回指定错误并保持所有表不变。
- merge 有 finance_record fixture 时，活动和回收站记录全被迁移且各自 revision 增加；目标类型不覆盖时一个记录也不变。
- 缺失/错 token、错 Host/Origin、错误 Content-Type、非法 request ID、缺少 Idempotency-Key、缺少 If-Match、未知/重复 query 均沿全局规范拒绝；没有 token 时 application/repository 零调用。
- Java test package 通过；分类 fixtures 被 Java HTTP 测试读取。不得存在账户 route 或前端代码。

## 7. 测试层次、命令与真实链路

- 单元：名称、UUID、层级、可用类型、ETag/operation/merge 规则。
- SQLite 集成：seed 后 list、transaction、重开、partial unique、archive/merge rollback、processed_operation 和 dataRevision。
- HTTP：认证、headers、状态码、Location/ETag、fixtures、JSON/unknown fields、cursor、错误 envelope。
- 必跑：仓库根 .\mvnw.cmd -q '-Dmaven.compiler.fork=true' test package。报告实际 Surefire 数量。
- Vue/Electron/发布包/快捷方式：不适用。本任务只验证真实 Java HttpServer→SQLite，不得把 mock 或手写 SQL 当作 UI 链路。

## 8. 禁止事项、升级、文档与完成报告

禁止改全局 REST 语义、迁移/seed、账户/records endpoint、分类恢复/物理删除/拖拽排序、绕过 ProfileApplicationService、在 renderer 保存 token、用 SQL 字符串拼 query，或将业务规则复制到 HTTP/Vue。

出现以下任一情况停止并升级：API 文档、seed 契约与数据库不一致；需要改变 dataRevision/幂等通用规则；P3-001 未 PASS；合并需修改资产/预警/指标表；无法保持 ledger 与 catalog idempotency key 防冲突；发现 records API 已实现并与本任务规则冲突。

完成报告写：修改文件；新增/总测试数量；单元、SQLite、HTTP 证据；是否真实 Java→SQLite；Vue/Electron/发布包/快捷方式不适用原因；未验证项、残余风险；实际修改的文档。不得标记 records 或 accounts 已完成。
