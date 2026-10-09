# LedgerX 全局 REST API 规范

- 状态：已接受，替代 Desktop Bridge 协议
- 日期：2026-09-23
- 调用方：系统浏览器内的 Vue 3；提供方：Java 11 本地后端
- 传输：仅本机 loopback HTTP/1.1；生产由同一 Java 进程提供前端静态资源和 API

## 1. 边界

REST API 是 Vue 与 Java 之间唯一业务契约。前端不得访问 SQLite、Java 类或任何桌面桥接来完成业务。浏览器运行时决策见 [ADR-014](./decisions/ADR-014-local-browser-only.md)；[P7-004](./tasks/P7-004-local-browser-runtime.md) 已实现正式网页所需的同源会话契约。Bearer 认证不属于活动 Java 服务或网页版开发代理；相关旧设计和验证只保留为迁移历史。

按 [ADR-010](./decisions/ADR-010-basic-ledger-scope.md)、[ADR-011](./decisions/ADR-011-enable-metrics-dashboard.md) 与 [ADR-016](./decisions/ADR-016-local-backup-snapshot.md)，目标资源包括 system、profiles、settings、categories、accounts、records、metrics、formulas、dashboard、backups 和 operation status。P9-001 只启用手动本机备份的创建、列表、校验和下载；恢复、上传/导入、自动备份与保留删除仍不得提前出现。

生产后端只绑定 `127.0.0.1` 随机端口，不对局域网或公网提供服务。开发时 Vue 只请求相对 `/api`；Vite 代理仅是前端本地开发工具，不参与正式运行或认证。生产时 Vue 和 API 同源。默认不开启 CORS。

如配置 Vite 开发代理，其 origin 必须是带显式端口的 `http://127.0.0.1:<1..65535>`，最多允许一个末尾 `/`；其他地址、凭据、非根路径和非法端口均拒绝。代理键固定为 `/api/v1`。`LEDGERX_DEV_API_TOKEN` 不再是受支持的配置，设置时构建配置会明确失败；本机浏览器路径始终使用上文的 Cookie/CSRF 会话。

业务 API 根路径是 `/api/v1`。公开但不含敏感信息的存活探针只有：

```http
GET /health/live
```

其响应固定为 `200 {"status":"UP"}`，不得包含应用版本、路径、端口、profile 或数据库状态。stdout 的 `LEDGERX_READY` 仅用于发现已监听端口；应用初始化、就绪和恢复状态以已认证的 `GET /api/v1/system/status` 为唯一依据。

## 2. 版本与兼容

- major 版本放在 URL；首版 `/api/v1`。
- 响应头 `LedgerX-Api-Version: 1.0`，minor 版本只增加可选字段或新 endpoint。
- 新增必填请求字段、删除/重命名字段、改变金额/日期/枚举含义或状态码语义是 major 变化。
- 客户端必须忽略未知响应字段；服务端默认忽略未知普通业务字段。具体 endpoint 可为避免把未交付字段误当成功而明确声明请求对象为闭合结构并拒绝未知字段。
- 已发布字段不得复用为新含义；弃用 endpoint 至少保留一个稳定版本，并在响应中使用标准 `Deprecation`/`Sunset` 头提示。
- Vue 静态资源与 Java 后端随同一交付单元发布；启动时核对 API major、应用版本、schema 和 capability，错误时显示兼容提示而不是白屏。

## 3. 认证、授权与数据范围

### 3.1 认证

当前浏览器契约：Java 在 `GET /api/v1/system/session` 建立或复用当前进程内短期会话，返回 `200 {"data":{"authMode":"browser","csrfToken":"<base64url>"}}` 和 `Cache-Control: no-store`；会话标识仅放入 host-only、`HttpOnly; SameSite=Strict; Path=/` 的 `ledgerx_session` Cookie，不放入 Vue JavaScript、DOM、URL、local/session storage 或日志。浏览器请求 `/api/v1/*` 自动携带 Cookie，刷新时重新 bootstrap 取得与会话绑定的 CSRF 值；服务重启后旧会话失效。缺失或错误会话返回 `401 AUTHENTICATION_REQUIRED`，不区分原因。POST/PUT/PATCH/DELETE 必须携带 `X-LedgerX-CSRF`，缺失或错误返回 `403 INVALID_CSRF_TOKEN`；错 Host/Origin 返回 `403 REQUEST_ORIGIN_FORBIDDEN`，均不得改变账本。CSRF 值只保存在 Vue 内存，不放入 URL、持久化存储或日志。P7-004 与 P7-002 已通过 Java HTTP 测试和真实浏览器隔离数据闭环验证；本机使用主流程见 P7-003。

活动浏览器服务拒绝 `Authorization: Bearer <desktop-session-token>`；该机制只见于归档的 Electron 代码与历史测试，不是兼容路径。OAuth、JWT、长期 API key 和刷新令牌均不引入。

### 3.2 授权和 profile

- 当前 Windows 用户启动的本机服务会话是唯一主体，首期没有角色。
- 普通业务请求作用于 Java application context 中的 active profile；请求不得携带任意 `profileId`。
- 只有 `/profiles` 管理接口显式使用 profile ID。切换成功后旧 `dataRevision`、游标和页面草稿失效。
- 恢复模式只允许 system/diagnostics/backup/recovery 明确列出的读写；其他写入返回 `423 RECOVERY_REQUIRED`。
- 文件接口只接受上传内容或下载流，不接受浏览器提交任意绝对路径。

### 3.3 账本初始化授权

`system/status.state=READY` 表示 Java 服务和 active profile 已打开，不代表账本已允许记账。状态 DTO 另含 `setupState: PENDING|REVIEW_REQUIRED|COMPLETED` 与 `ledgerStartOn: Date|null`。只有 `setupState=COMPLETED` 时开放普通分类、账户、记录、设置、指标、公式和总览能力。

PENDING/REVIEW_REQUIRED 仍允许 `system.status`、`profiles.read`、`profiles.write`、`ledger.initialization.read`、`ledger.initialization.write` 和 `operations.read`。Java HTTP 路由和 application service 都拒绝普通业务请求，返回 `409 LEDGER_SETUP_REQUIRED`；不可只依赖 Vue 隐藏导航。损坏/恢复状态仍由 `RECOVERY_REQUIRED` 表示，不与 setup 状态混用。

### 3.4 来源限制

- Java 校验 `Host` 为实际 loopback 地址与端口；mutation 的 `Origin` 必须精确为同一 origin。GET/HEAD 可缺失 Origin，但若存在必须同源；任何跨源值都拒绝。
- 预检或跨源请求默认拒绝，不返回通配 CORS 头。
- 来源检查不能替代浏览器会话和 CSRF 校验。

## 4. 命名、媒体类型与大小

- URL 使用小写复数名词和短横线；动作只用于无法自然表达为 CRUD 的子资源，例如 `/records/{id}/restore`。
- JSON 字段 `camelCase`；数据库 `snake_case` 只存在于 persistence。
- 请求/响应 JSON 使用 `application/json; charset=utf-8`；非 JSON 返回 `415 UNSUPPORTED_MEDIA_TYPE`。
- 普通 JSON body 最大 1 MiB；超限返回 `413 PAYLOAD_TOO_LARGE`。
- 备份导入使用 `multipart/form-data` 或二进制流，默认最大 512 MiB，具体接口可更小但不能更大而不改全局规范。
- 文本 UTF-8；用户正文保留原字符。名称按具体规则 trim/不区分大小写，备注不做全局改写。
- ID 是小写、带连字符 UUID；系统指标可使用文档声明的稳定 slug。
- 枚举为稳定大写英文值；中文只用于 label。
- 缺失表示未提供；只有具体字段明确允许时 `null` 才表示清空。集合响应永远返回 `[]`，不返回 `null`。
- JSON boolean 只能是 `true/false`；不得使用 `0/1` 或字符串。

## 5. 通用请求头

| Header | 适用范围 | 必填 | 规则 |
| --- | --- | --- | --- |
| `Cookie` | `/api/v1/*` | 是 | 浏览器自动携带 Java 设置的短期同源会话 |
| `X-LedgerX-CSRF` | 变更请求 | 是 | 同源 bootstrap 得到的独立 CSRF token |
| `X-Request-Id` | 所有 API | 是 | UUID v4；关联日志与响应，不产生幂等语义 |
| `Idempotency-Key` | POST/PUT/PATCH/DELETE | 是 | UUID；同一业务尝试在超时重试时复用 |
| `If-Match` | 修改/删除已有可编辑实体 | 是 | 使用读取响应的强 ETag，如 `"7"` |
| `Accept` | API | 建议 | 仅接受 `application/json`，下载接口除外 |

服务端响应始终回显 `X-Request-Id`；若 header 非法则服务端生成安全关联 ID，并返回 `400 INVALID_REQUEST_ID`，响应头和 error 中使用新 ID。

## 6. 响应格式

### 6.1 成功

```http
HTTP/1.1 201 Created
Content-Type: application/json; charset=utf-8
Location: /api/v1/records/9fc1df09-f1d3-4c9b-97be-f592f65d0c29
ETag: "1"
X-Request-Id: 0d7700d1-ef22-49fd-83b3-7b45f51c9c82
LedgerX-Api-Version: 1.0
```

```json
{
  "data": {
    "id": "9fc1df09-f1d3-4c9b-97be-f592f65d0c29",
    "revision": 1
  },
  "meta": {
    "dataRevision": 42
  }
}
```

- 单资源放在 `data`；列表也使用 `data.items`。
- `meta.dataRevision` 是 active profile 的整体数据版本；无 active profile 时为 `null`。
- 删除/动作成功也返回 JSON 结果，不使用空 body，便于返回 revision/dataRevision。
- 单资源响应用 `ETag: "<revision>"`；ETag 与 body `revision` 必须一致。

### 6.2 失败

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "请检查记录内容。",
    "requestId": "0d7700d1-ef22-49fd-83b3-7b45f51c9c82",
    "fieldErrors": {
      "amount": "金额必须大于 0 且最多两位小数。"
    },
    "retryable": false,
    "recoverySuggestion": "补全使用寿命后重试",
    "details": {}
  }
}
```

- `message` 可展示但不含堆栈、SQL、token 或完整私人路径。
- `fieldErrors` 的键使用请求 JSON 路径；没有字段错误时为 `{}`。
- `details` 只含具体 API 已定义且脱敏的机器可读信息。
- 内部异常映射为 `INTERNAL_ERROR`；详细原因只在脱敏本地日志中通过 request ID 关联。

## 7. HTTP 状态与错误码

| HTTP | code | 含义/重试 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST_ID`, `INVALID_JSON`, `VALIDATION_FAILED`, `ACCOUNT_NOT_OPEN_ON_SETTLEMENT_DATE` | 输入/日期领域错误；不自动重试，字段位置见具体 API |
| 401 | `AUTHENTICATION_REQUIRED` | 会话无效；由浏览器 UI 引导重新建立本机会话，不循环重试 |
| 403 | `REQUEST_ORIGIN_FORBIDDEN` | Host/Origin 不属于本次 loopback origin |
| 404 | `ROUTE_NOT_FOUND`, `NOT_FOUND` | API 路由不存在；或资源在当前范围不可见 |
| 405 | `METHOD_NOT_ALLOWED` | 路径存在但方法不支持；响应含 `Allow` |
| 409 | `REFERENCE_CONFLICT`, `REVISION_CONFLICT`, `IDEMPOTENCY_CONFLICT`, `LEDGER_SETUP_REQUIRED`, `ALREADY_INITIALIZED`, `REVIEW_CONFIRMATION_REQUIRED`；后续保留 `FORMULA_INVALID`, `MIGRATION_BLOCKED` | 状态冲突；按用户选择处理 |
| 413 | `PAYLOAD_TOO_LARGE` | body/文件超限 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content-Type 不支持 |
| 422 | `BACKUP_INVALID`；后续保留 `DATA_NOT_COMPUTABLE` | 备份包可定位但格式、hash、metadata 或 SQLite 完整性校验失败；不自动重试 |
| 423 | `RECOVERY_REQUIRED` | 数据库只读，需先恢复 |
| 428 | `PRECONDITION_REQUIRED` | 修改已有实体但缺少 `If-Match` |
| 429 | `RATE_LIMITED` | 后端有界队列满；按 `Retry-After` 重试读请求 |
| 503 | `DATABASE_BUSY`, `SERVICE_UNAVAILABLE` | 短时不可用；仅安全重试 |
| 504 | `TIMEOUT` | 服务端未完成或结果未知；写请求用同一幂等键查询/重试 |
| 500 | `INTERNAL_ERROR` | 未分类错误；默认不自动重试写入 |

同一 `code` 不得被多个不兼容 HTTP 状态复用。`message` 可本地化，`code` 和字段路径不可随文案变化。

指标迭代增加以下稳定业务错误；具体 endpoint 文档必须固定 field path：

| HTTP | code | 使用条件 |
| --- | --- | --- |
| 400 | `FORMULA_INVALID` | AST/token/schema、参数个数、类型、复杂度或引用格式非法 |
| 409 | `FORMULA_CYCLE` | 新版本会令活动指标依赖图成环；旧版本保持不变 |
| 409 | `REFERENCE_CONFLICT` | 引用不存在/不可新引用，或归档指标仍被活动公式使用 |
| 409 | `LAYOUT_INVALID` | 越界、重叠、未知/禁用 widget、尺寸低于内容最小值 |

单个指标因零分母、空样本或依赖不可计算不是 HTTP 错误；dashboard/preview 仍返回 200，并在该指标使用 `dataStatus`。只有整个请求的公式草稿无法验证时才返回 400。

## 8. 日期、时间、金额和数值

### 日期时间

- 业务自然日：`YYYY-MM-DD`，映射 `LocalDate`；例 `occurredOn`、`serviceStart`。
- 期间统一半开 `[start, endExclusive)`；字段必须命名 `endExclusive`。
- 时间点：UTC RFC 3339，带 `Z`，映射 `Instant`；例 `2026-09-12T08:30:00Z`。
- 不用无时区午夜 timestamp 表达业务日期。
- “今天”由后端显式业务时区 `Asia/Shanghai` 与可替换 `Clock` 决定；需要复现的查询显式传 `asOf`。
- 总览调用方提交 `granularity=DAY|WEEK|MONTH|YEAR` 与一个 `anchor=YYYY-MM-DD`；后端返回权威 `start/endExclusive/asOf`。DAY 为一个自然日，WEEK 为周一开始的 7 天，MONTH/YEAR 为自然月/年。
- 当前或跨未来期间使用 `asOf=min(today,endExclusive-1天)`；完全未来期间返回 `FUTURE`/空序列状态。调用方不得自行把未来天数当作零加入平均值。
- 历史完整期间使用完整 previous period；当前未结束的 WEEK/MONTH/YEAR 返回截断后的 `comparisonStart/comparisonEndExclusive`，其自然日数与 current start→asOf 的已过天数相同并受上一期长度限制。DAY 返回完整前一日。比较窗口由后端返回，前端不得自行推导。

### 金额

```json
{"amount":"1234.56","currency":"CNY"}
```

- 金额是规范十进制字符串；禁止 JSON number、指数、千分位和货币符号。
- 首期 CNY 最多两位小数，后端精确转 `amount_minor=123456`；不隐式舍入。
- 领域用 `BigDecimal`；前端格式化显示但不以 IEEE-754 number 计算或提交金额。
- 数量/比例同样用十进制字符串；百分比 `12.5` 表示 12.5%。
- 分母为零返回 `value:null` 与稳定 `dataStatus`，不返回 NaN/Infinity/伪造 0。

指标数值统一投影：

```json
{
  "value":"1234.56",
  "displayFormat":"CURRENCY",
  "precision":2,
  "dataStatus":"READY"
}
```

`dataStatus` 首版固定为 `READY|EMPTY|NOT_COMPUTABLE|DEPENDENCY_UNAVAILABLE|FUTURE`。`value` 仅在 READY 时为规范 decimal string，其余状态必须为 null；Vue 根据 format/precision 本地化展示，但不能重新计算或舍入后提交。

## 9. 分页、过滤与排序

集合响应：

```json
{
  "data": {
    "items": [],
    "page": {"nextCursor": null, "hasMore": false, "limit": 50}
  },
  "meta": {"dataRevision": 42}
}
```

- 查询参数：`limit` 默认 50、范围 1–200；`cursor` 为不透明 base64url token。
- 不使用深 offset；游标包含 API major、查询指纹、profile/dataRevision 和排序键，严格解析且不拼接 SQL。
- 筛选使用明确字段，如 `recordType=INCOME&occurredFrom=2026-01-01&occurredToExclusive=2027-01-01`；不接受未定义任意 SQL 风格参数。
- 多值使用重复 query key，如 `recordType=INCOME&recordType=FIXED_COST`；空字符串无效，不等于未提供。
- 排序格式 `sort=-occurredOn,-id`；每个 endpoint 必须列 allowlist 和稳定 tie-breaker。
- filter/sort/profile/dataRevision 改变后客户端必须丢弃旧 cursor；过期 cursor 返回 `400 VALIDATION_FAILED`。

## 10. 幂等、并发、超时和重试

### 10.1 幂等

- 每个 mutation 必须有 `Idempotency-Key`。Java 在业务写同一事务中保存 method、规范路径、canonical body hash、成功状态和成功响应；提交前的校验/冲突/数据库失败不建立 operation 记录。
- 已提交的同 key + 同请求返回首次成功响应；同 key + 不同请求返回 `409 IDEMPOTENCY_CONFLICT`。
- 创建实体 ID 由前端生成。相同 ID/相同内容重试等价成功；相同 ID/不同内容冲突。
- 幂等记录默认保留 7 天；危险恢复操作至少保留到下一次成功备份。
- 可用 `GET /api/v1/operations/{idempotencyKey}` 查询已提交结果；不存在返回 404，表示“未找到已提交结果”而不是证明原请求从未执行到任何阶段。普通业务 operation 只在当前会话和 active profile 范围内可见；profiles catalog operation 存在 `profiles.db`，切换 active profile 后仍可查询。应用写门在执行 mutation 前同时检查 catalog 与当前 ledger，禁止同 key 跨作用域复用；查询先查 catalog、再查当前 ledger，同时命中视为完整性错误。

### 10.2 乐观并发

- 可编辑实体有递增整数 `revision` 和强 ETag。
- PUT/PATCH/DELETE 使用 `If-Match`；缺失返回 `428 PRECONDITION_REQUIRED`，不匹配返回 `409 REVISION_CONFLICT` 并在 `details.currentRevision` 给出当前版本。
- 创建不使用 `If-Match`；恢复/切换等具体 action 是否需要额外 expected revision 由接口文档明确。

### 10.3 超时和重试

- 普通读建议客户端超时 15 秒；普通写 30 秒。基础版没有长任务 endpoint。
- 客户端只可自动重试 GET/HEAD；mutation 仅在用户仍处于同一操作且复用同一幂等键时重试。
- `429/503` 尊重 `Retry-After`，退避并加入抖动；不无限重试。
- 客户端断开不等于服务端回滚；服务端必须继续完成已进入提交阶段的事务并记录结果。

## 11. 资源分组

| 资源 | 代表 endpoint |
| --- | --- |
| system | `GET /system/status`, `GET /system/diagnostics`, `POST /system/actions/open-data-folder` |
| ledger initialization | `GET/POST /ledger-initialization`；GET 读取 setup/review summary，POST 原子完成初始化 |
| profiles | `GET/POST /profiles`, `POST /profiles/{id}/activate`, `DELETE /profiles/{id}` |
| records | `GET/POST /records`, `GET/PUT/DELETE /records/{id}`, `POST /records/{id}/restore` |
| categories | `GET/POST /categories`, `PUT/DELETE /categories/{id}`, `POST /categories/{targetId}/merge` |
| accounts | `GET/POST /accounts`, `PUT/DELETE /accounts/{id}` |
| settings | `GET/PATCH /settings`；未实现的偏好不等于对应执行能力 |
| metrics | `GET/POST /metrics`, `GET/PUT/DELETE /metrics/{id}`；DELETE 表示归档，不物理删除 |
| formulas | `POST /formulas/validate`, `POST /formulas/preview`, `GET /formulas/{id}/versions`；保存公式随 metric create/update 原子完成 |
| dashboard | `GET /dashboard?granularity=&anchor=`, `GET/PUT /dashboard/layout`, `POST /dashboard/layout/reset` |
| backups | `POST/GET /backups`, `GET /backups/{id}/verify`, `GET /backups/{id}/download`；详见 [backups-api](./api/backups-api.md) |
| operation status | `GET /operations/{idempotencyKey}` |

具体 API 文档必须声明每个路径的方法、字段、授权、事务、幂等、并发、错误、示例和数据库/领域映射。账本初始化、指标/总览和备份分别见其具体 API 文档；不得把本表当成足够的实现规格。assets/allocations/direct-metric/reports/warnings/themes/recovery 和 PDF 导出仍不属于当前范围。

## 12. 响应投影与缓存

- 财务总览只通过 dashboard 聚合 endpoint 读取；禁止 Vue 组合 accounts/records 全量响应计算指标。
- 列表由各资源 endpoint 分页返回；禁止用一个全量 `Snapshot` 作为隐式契约。
- DTO 可有展示辅助字段，但必须同时返回结构化原值和数据状态。
- Dashboard 响应按当前布局顺序返回已启用指标，但布局坐标仍是独立结构；每项包含 metric ID/name/description、value、previousValue、change、trend/breakdown 和 dataStatus。不得返回公式 AST、原始全量记录或持久化表结构。
- `change.absolute` 与 `change.percent` 均为 decimal string/null；上一期为零时 percent=null 且 status=`NOT_COMPUTABLE`，不能用 100% 或 0% 猜测。
- DAY 不伪造小时趋势，因为记录只有自然日；DAY 大卡返回分类构成或当日摘要。WEEK 按 7 个自然日、MONTH 按月内自然日、YEAR 按 12 个自然月返回连续桶，过去的无记录桶可为 READY 零，完全未来桶为 FUTURE/null。
- API 不泄漏表名、rowid、内部类名或完整绝对路径。
- 业务 JSON 默认 `Cache-Control: no-store`；hash 命名静态资源可长期 immutable，入口 HTML 不长期缓存。

## 13. 契约验证

- `docs/contracts/api-v1/` 是跨前后端共享的请求/响应 fixtures；Java 和 Vue 测试读取同一份文件。
- Java 对每个 endpoint 覆盖认证、合法请求、缺失/非法/未知字段、领域拒绝、幂等重试、并发冲突和内部错误映射。
- Vue mock 必须模拟 HTTP status、headers、错误、分页、revision 和幂等，不能固定成功。
- fixtures 必须覆盖金额字符串、日期、null/缺失、枚举大小写、重复 query、过期 cursor 和字段错误路径。
- 指标 fixtures 还必须覆盖四种粒度、周一边界、月末/跨年/闰年、发生日与结算日不同、零分母、空账本、未来期间、中文函数 token、重命名后稳定引用、循环依赖和布局重叠。
- 最终真实浏览器验证至少覆盖并发读取、超时后同 key 写重试、profile 切换使旧游标失效、页面刷新后继续通信、Java 服务退出和重启。
- 备份契约测试必须覆盖同 key 耐久重试、profile 隔离、坏 ZIP/hash/未知格式、深校验和二进制下载；恢复不在 P9-001 验收内。
- 浏览器 mock 只算前端验证；不能宣称真实浏览器/持久化链路通过。
