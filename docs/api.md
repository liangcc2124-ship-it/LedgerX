# LedgerX 全局 REST API 规范

- 状态：已接受，替代 Desktop Bridge 协议
- 日期：2026-09-14
- 调用方：Vue 3 renderer；提供方：Java 11 本地后端
- 传输：仅本机 loopback HTTP/1.1；生产由同一 Java 进程提供前端静态资源和 API

## 1. 边界

REST API 是 Vue 与 Java 之间唯一业务契约。前端不得访问 SQLite、Java 类或 Electron IPC 来完成业务；Electron 不转发业务 DTO。

按 [ADR-010](./decisions/ADR-010-basic-ledger-scope.md)，基础版公开资源只有 system、profiles、settings、categories、accounts、records 和 operation status。本文保留的文件大小、公式、备份等通用约定只是将来启用相应 endpoint 时的约束；当前不得出现在 capabilities、路由或 Vue 导航中。

生产后端只绑定 `127.0.0.1` 随机端口，不对局域网或公网提供服务。开发时 Vue 只请求相对 `/api`，Vite 仅可将 `/api/v1` 代理到经校验的 `http://127.0.0.1:<port>`，并仅在该 Node 代理层注入会话 token；生产时 Vue 和 API 同源。默认不开启 CORS。

开发代理的 origin 必须是带显式端口的 `http://127.0.0.1:<1..65535>`，最多允许一个末尾 `/`；`https`、`localhost`、IPv6、其他地址、凭据、非根路径、query、fragment、空白和非法端口均在 Vite 启动前拒绝。代理键固定为 `/api/v1`。`LEDGERX_DEV_API_TOKEN` 只能存在于 Node 配置环境，严格匹配 43 位 `[A-Za-z0-9_-]`；缺少 origin 时不能启用 token。生产构建不创建开发 proxy，也不把这些变量读取到客户端或写入构建产物。

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
- Vue、Electron、Java 随同一安装包发布，但启动必须核对 API major、应用版本、schema 和 capability，错误时显示兼容提示而不是白屏。

## 3. 认证、授权与数据范围

### 3.1 认证

除 `/health/live` 和生产静态资源外，所有请求必须包含：

```http
Authorization: Bearer <desktop-session-token>
```

- token 至少 256 bit、每次 Electron 会话重新生成，只通过子进程环境交给 Java。
- Electron 只为精确匹配本会话端口的 `/api/v1/*` renderer 请求注入 header。
- Vue JavaScript、DOM、URL、local/session storage、配置文件和日志均不能获得 token。
- 缺失或错误 token 返回 `401 AUTHENTICATION_REQUIRED`，并使用固定时间比较；不要区分“缺失”和“错误”。
- API 不使用 Cookie、OAuth、JWT、长期 API key 或刷新令牌。

### 3.2 授权和 profile

- 当前 Windows 用户启动的桌面会话是唯一主体，首期没有角色。
- 普通业务请求作用于 Java application context 中的 active profile；请求不得携带任意 `profileId`。
- 只有 `/profiles` 管理接口显式使用 profile ID。切换成功后旧 `dataRevision`、游标和页面草稿失效。
- 恢复模式只允许 system/diagnostics/backup/recovery 明确列出的读写；其他写入返回 `423 RECOVERY_REQUIRED`。
- 文件接口只接受上传内容、下载流或 Electron 返回的一次性能力，不接受 renderer 提交任意绝对路径。

### 3.3 来源限制

- Java 校验 `Host` 为实际 loopback 地址与端口；mutation 的 `Origin` 必须精确为同一 origin。GET/HEAD 可缺失 Origin，但若存在必须同源；任何跨源值都拒绝。
- 预检或跨源请求默认拒绝，不返回通配 CORS 头。
- 来源检查不能替代 Bearer token。

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
| `Authorization` | `/api/v1/*` | 是 | 由桌面壳/开发代理注入 |
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
| 400 | `INVALID_REQUEST_ID`, `INVALID_JSON`, `VALIDATION_FAILED` | 输入错误；不自动重试 |
| 401 | `AUTHENTICATION_REQUIRED` | 会话无效；由 Electron 处理，不循环重试 |
| 403 | `REQUEST_ORIGIN_FORBIDDEN` | Host/Origin 不属于本次 loopback origin |
| 404 | `ROUTE_NOT_FOUND`, `NOT_FOUND` | API 路由不存在；或资源在当前范围不可见 |
| 405 | `METHOD_NOT_ALLOWED` | 路径存在但方法不支持；响应含 `Allow` |
| 409 | `REFERENCE_CONFLICT`, `REVISION_CONFLICT`, `IDEMPOTENCY_CONFLICT`；后续保留 `FORMULA_INVALID`, `MIGRATION_BLOCKED` | 状态冲突；按用户选择处理 |
| 413 | `PAYLOAD_TOO_LARGE` | body/文件超限 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Content-Type 不支持 |
| 422 | 后续保留 `DATA_NOT_COMPUTABLE`, `BACKUP_INVALID` | 基础版不使用；相应模块启用后表示语法可读但业务不能处理 |
| 423 | `RECOVERY_REQUIRED` | 数据库只读，需先恢复 |
| 428 | `PRECONDITION_REQUIRED` | 修改已有实体但缺少 `If-Match` |
| 429 | `RATE_LIMITED` | 后端有界队列满；按 `Retry-After` 重试读请求 |
| 503 | `DATABASE_BUSY`, `SERVICE_UNAVAILABLE` | 短时不可用；仅安全重试 |
| 504 | `TIMEOUT` | 服务端未完成或结果未知；写请求用同一幂等键查询/重试 |
| 500 | `INTERNAL_ERROR` | 未分类错误；默认不自动重试写入 |

同一 `code` 不得被多个不兼容 HTTP 状态复用。`message` 可本地化，`code` 和字段路径不可随文案变化。

## 8. 日期、时间、金额和数值

### 日期时间

- 业务自然日：`YYYY-MM-DD`，映射 `LocalDate`；例 `occurredOn`、`serviceStart`。
- 期间统一半开 `[start, endExclusive)`；字段必须命名 `endExclusive`。
- 时间点：UTC RFC 3339，带 `Z`，映射 `Instant`；例 `2026-09-12T08:30:00Z`。
- 不用无时区午夜 timestamp 表达业务日期。
- “今天”由后端当前 Windows 时区和可替换 `Clock` 决定；需要复现的查询显式传 `asOf`。

### 金额

```json
{"amount":"1234.56","currency":"CNY"}
```

- 金额是规范十进制字符串；禁止 JSON number、指数、千分位和货币符号。
- 首期 CNY 最多两位小数，后端精确转 `amount_minor=123456`；不隐式舍入。
- 领域用 `BigDecimal`；前端格式化显示但不以 IEEE-754 number 计算或提交金额。
- 数量/比例同样用十进制字符串；百分比 `12.5` 表示 12.5%。
- 分母为零返回 `value:null` 与稳定 `dataStatus`，不返回 NaN/Infinity/伪造 0。

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
| profiles | `GET/POST /profiles`, `POST /profiles/{id}/activate`, `DELETE /profiles/{id}` |
| records | `GET/POST /records`, `GET/PUT/DELETE /records/{id}`, `POST /records/{id}/restore` |
| categories | `GET/POST /categories`, `PUT/DELETE /categories/{id}`, `POST /categories/{targetId}/merge` |
| accounts | `GET/POST /accounts`, `PUT/DELETE /accounts/{id}` |
| settings | `GET/PATCH /settings`；未实现的偏好不等于对应执行能力 |
| operation status | `GET /operations/{idempotencyKey}` |

具体 API 文档必须声明每个路径的方法、字段、授权、事务、幂等、并发、错误、示例和数据库/领域映射。dashboard、metrics/formulas、assets/allocations、reports/warnings、themes、backup/recovery 和 PDF 导出均不属于基础版；只有用户重新确认范围并补齐具体 API 后才能增加。

## 12. 响应投影与缓存

- 基础首页通过现有 accounts 与 records 读取需要的数据，不增加聚合 dashboard endpoint。
- 列表由各资源 endpoint 分页返回；禁止用一个全量 `Snapshot` 作为隐式契约。
- DTO 可有展示辅助字段，但必须同时返回结构化原值和数据状态。
- API 不泄漏表名、rowid、内部类名或完整绝对路径。
- 业务 JSON 默认 `Cache-Control: no-store`；hash 命名静态资源可长期 immutable，入口 HTML 不长期缓存。

## 13. 契约验证

- `docs/contracts/api-v1/` 是跨前后端共享的请求/响应 fixtures；Java 和 Vue 测试读取同一份文件。
- Java 对每个 endpoint 覆盖认证、合法请求、缺失/非法/未知字段、领域拒绝、幂等重试、并发冲突和内部错误映射。
- Vue mock 必须模拟 HTTP status、headers、错误、分页、revision 和幂等，不能固定成功。
- fixtures 必须覆盖金额字符串、日期、null/缺失、枚举大小写、重复 query、过期 cursor 和字段错误路径。
- 最终真实 Electron 验证至少覆盖并发读取、超时后同 key 写重试、profile 切换使旧游标失效、renderer 刷新后继续通信、后端退出和重启。
- 浏览器 mock 只算前端验证；不能宣称真实桌面/持久化链路通过。
