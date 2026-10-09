# P6 交互与业务闭环收敛：技术设计

- 状态：SPECIFIED
- 日期：2026-09-22
- 来源：[交互、可用性与轻量架构审计](../reviews/2026-09-22-interaction-architecture-audit.md)
- 关键决定：[ADR-013](../decisions/ADR-013-formula-ast-single-source.md)

## 1. 目标与约束

本阶段把当前“可展示”的网页收敛为可长期自用的业务闭环：公式可创建、计算、重开和再编辑；表单弹窗可用键盘完整操作；记录和历史数据不会因分页或竞态消失；总览卡片在 1024px 桌面宽度完整显示；所有 mutation 对超时、冲突和重试具有一致语义。

规模仍为小型单机产品，跨层风险中等。继续使用 Vue 3 + Java 11 + JDK HttpServer + SQLite + GridStack 13.3.0，不增加 Pinia、Vue Router、Headless UI、Spring、消息队列、表达式执行库或第二个数据库。

非目标：移动端适配、云同步、多人权限、资产/分摊/报表/预警、任意文本公式、主题系统、安装包发布和大规模视觉重做。

## 2. 业务边界

| 边界 | 拥有的业务规则 | 不应拥有 |
| --- | --- | --- |
| Formula Contract | AST schema、外部枚举、arity、引用、循环、规范 token/dependency、历史兼容 | 页面控件、GridStack、SQLite SQL |
| Formula Editor | 中文节点编辑、字段错误定位、validate/preview/save 状态、版本浏览 | 本地公式求值、内部 Java enum |
| Interaction Foundation | modal 焦点/关闭、读取竞态、mutation 重试状态机 | 财务口径、页面具体字段 |
| Ledger Records | 当前/回收站分页、记录 mutation、账户余额刷新 | Dashboard 指标计算 |
| Dashboard | 期间选择、卡片呈现、布局 draft/save/reset/capability | 公式解析、前端财务聚合 |
| App Shell | active profile、dataRevision、capabilities、hash 页面状态、中文展示映射 | 页面业务缓存、SQLite 数据 |
| Acceptance | 隔离环境真实链路、重开、性能和可访问性证据 | 顺手修改产品行为 |

## 3. 目标依赖方向

```text
Vue Page
  ├─ AppDialog / useAsyncResource / useApiMutation
  └─ apiClient（唯一 HTTP 入口）
          ↓
模块 HttpHandler / HTTP DTO Mapper
          ↓
模块 Application Service
          ↓
领域对象与规则
          ↓
Repository / Storage Mapper / SQLite
```

Vue 不读取 SQLite、不计算财务值、不执行公式。HTTP 层不把 JSON node 传入领域层。Repository 不决定中文提示或 HTTP error code。

## 4. 公式契约设计

### 4.1 写入 DTO

create/update/validate/preview 中的 formula 统一为：

```json
{
  "ast": {
    "schemaVersion": 1,
    "root": {
      "kind": "SAFE_DIVIDE",
      "children": [
        { "kind": "REF", "referenceKind": "CATEGORY_EXPENSE", "key": "<uuid>" },
        { "kind": "REF", "referenceKind": "METRIC", "key": "total-expense" }
      ]
    }
  }
}
```

请求不再接收 tokens 或 dependencies。closed-body 校验遇到这些字段返回 400 `FORMULA_INVALID`，field path 指向 `formula.tokens` 或 `formula.dependencies`。

### 4.2 节点与 arity

| kind | 字段 | arity |
| --- | --- | --- |
| CONSTANT | `value` 规范十进制字符串 | 0 |
| REF | `referenceKind`,`key` | 0 |
| ADD/SUBTRACT/MULTIPLY/DIVIDE | `children` | 2 |
| NEGATE/ABS | `children` | 1 |
| ROUND | `children` | 1 或 2；第二项是 0–8 整数常数 |
| CLAMP | `children` | 3：值、下限、上限 |
| SAFE_DIVIDE | `children` | 2 |
| MIN/MAX/AVG | `children` | 2–32 |

referenceKind 固定为 `METRIC`、`CATEGORY_INCOME`、`CATEGORY_EXPENSE`、`ACCOUNT_BALANCE`、`TIME`；TIME key 固定为 `period-days`、`elapsed-days`、`complete-months`。

### 4.3 响应与存储

- detail/history 返回规范 external AST，并附加 Java 派生的 canonical tokens 和 dependencies。
- tokens 是只读表达层，不参与写入判断；中文 label 由当前资源名称投影，不作为引用 key。
- 新 storage JSON 使用一个根 wrapper；children 为直接节点。storage kind 保持现有内部枚举以降低回滚风险。
- reader 同时支持新结构和错误历史结构，任何无法识别节点都返回诊断错误，不降级为 0。

### 4.4 公式主要状态流

```text
编辑 AST draft
  ├─ validate → 只读校验 → fieldErrors/dependencies
  ├─ preview  → 只读求值 → value/dataStatus/components
  └─ save     → 服务端再次校验 → 同事务写 version/dependency/revision/operation
                    ↓
               SQLite 重新读取
                    ↓
               规范 detail 响应
```

保存不依赖用户先点 validate；preview 成功也不代替保存校验。

## 5. 前端公共交互接口

### 5.1 `AppDialog.vue`

```js
defineProps({
  open: Boolean,
  titleId: String,
  busy: Boolean,
  initialFocus: String,
  closeOnBackdrop: { type: Boolean, default: true }
})
defineEmits(['close'])
```

行为：Teleport 到 body；保存打开前焦点；设置 `#app.inert`；锁定并恢复 body overflow；打开后聚焦 `initialFocus` 或第一个可操作元素；Tab/Shift+Tab 在 dialog 内循环；Escape 在非 busy 时关闭；关闭后恢复触发按钮。只允许一个顶层 AppDialog；确认内容放在同一 dialog 内切换步骤，不再嵌套 dialog。

### 5.2 `useAsyncResource.js`

```js
const { state, data, error, execute, cancel } = useAsyncResource(loader)
await execute({ mode: 'replace' | 'append', input })
```

- 每次 replace 增加 generation 并 abort 旧请求；只有最新 generation 可写 state。
- append 使用当前 query 对应的 cursor；query 改变先 replace，不允许把不同筛选结果拼接。
- state 固定为 `idle|loading|loading-more|success|empty|error`。
- unmount 必须 cancel；Abort 不显示为业务错误。

### 5.3 `useApiMutation.js`

```js
const { state, pendingRequest, submit, retrySame, queryOperation, clear } = useApiMutation()
await submit({ method, path, body, ifMatch, idempotencyKey })
```

状态固定为 `idle|submitting|success|validation-error|conflict|pending-confirmation|error`。第一次 submit 深复制并冻结 request。网络超时/连接中断进入 pending-confirmation；retrySame 只能复用原 path/body/If-Match/idempotencyKey，并生成新的 X-Request-Id。409/428 进入 conflict，不自动覆盖服务端。成功清除 pending 并返回 payload、ETag、meta。

pendingRequest 只保存在当前页面内存，不写 localStorage。

### 5.4 App Context

App 提供只读 refs 和两个动作：

```js
{
  activeProfileId,
  dataRevision,
  capabilities,
  contextEpoch,
  commitMutation(meta),
  applyProfileStatus(status)
}
```

它只负责数据域和失效信号，不缓存记录、账户或指标。profile 改变时 contextEpoch +1；仍在运行的页面请求必须失效或取消。

## 6. 页面业务设计

### 6.1 Formula Editor

- `FormulaBuilder` 管理 root，`FormulaNodeEditor` 递归编辑一个节点，`ReferencePicker` 只选择稳定 ID。
- 新增节点默认 CONSTANT `0`；切换 kind 时清除不属于新 kind 的字段，不能残留隐藏 child。
- METRIC 选项排除当前 candidate；分类和账户只显示 ACTIVE；TIME 使用固定中文选项。
- MIN/MAX/AVG 提供添加/删除参数，范围 2–32；递归深度达到 32 后禁用继续嵌套并说明原因。
- field path 映射到节点，例如 `formula.ast.root.children[1]`；首个错误自动滚动并聚焦。
- 详情先打开，history 独立加载；history 失败不阻止编辑，cursor 可继续加载更早版本。

### 6.2 Records

- list state 以 `status + cursor + contextEpoch` 为查询键。
- replace 固定 limit 50，消费 `page.hasMore/nextCursor`；加载更多只追加同一状态页面。
- current/trash 快速切换必须 abort 旧请求。
- create/update/trash/restore 使用统一 mutation 状态机；成功后刷新当前列表和账户余额。

### 6.3 Dashboard

- App 传入 `canEdit = capabilities.includes('dashboard.layout.write')`。
- 非编辑模式启用 GridStack 已有 `sizeToContent` 能力，使内容高度进入布局计算；编辑模式尊重草稿 h，并阻止保存小于内容可读最小值的卡片。
- Cancel 只丢弃未提交 draft，零 mutation。
- Reset 文案固定“恢复默认并保存”；确认后 POST reset，成功立即退出编辑。
- 上一期 anchor 使用服务端 `previousPeriod.start`；下一期使用 `period.endExclusive`；本期使用本地当天。前端不计算自然期间边界。
- 日/周/月/年采用普通 `role=group` 分段按钮，不声明未实现的 tab 语义。

### 6.4 App Shell 与呈现

- 原生 hash 只接受白名单页面；空/未知 hash 回到 home；hashchange 支持前进后退。
- 导航 capability 不满足时不渲染入口；页面写操作再检查对应 write capability。
- `presentationMaps.js` 统一账户类型、余额方向、状态、指标格式和 dataStatus 中文标签；API 值保持英文。
- 设置页说明来自当前已交付 capability，不再把指标和总览遗漏。

## 7. Java 模块整理边界

本阶段允许从 `ProfileApplicationService` 提取公式相关 mapper/validator orchestration 和 Dashboard service，但不要求一次拆完所有模块。每个业务修复只提取其直接需要的职责：

- `FormulaHttpMapper`：外部 JSON DTO ↔ domain；
- `FormulaStorageMapper`：storage JSON ↔ domain，含 legacy reader；
- `CanonicalFormulaTokens`：domain → tokens；
- `MetricsApplicationService`：指标/公式用例与事务编排；
- `DashboardApplicationService`：聚合读取与布局事务。

Profile 激活和 shared active-context gate 可暂时留在现有 coordinator，通过构造参数传给新 service。不得为了“纯架构”一次性搬动 Profiles/Catalog/Records。

## 8. 错误与恢复

| 情况 | 用户反馈 | 写入结果 |
| --- | --- | --- |
| AST schema/arity/reference 错误 | 节点旁中文 field error | 零写入 |
| 公式循环/引用冲突 | 保留草稿，指出引用对象 | 零写入 |
| revision 409/428 | 提示重新加载或放弃，保留草稿 | 零写入 |
| mutation 超时/断线 | 结果待确认；同 key 重试或查询 | 未知，不能创建新意图 |
| legacy AST 无法读取 | 指标不可用并给诊断 requestId | 不改历史数据 |
| 列表旧请求迟到 | 静默丢弃旧响应 | 无写入 |

## 9. 验证策略

1. Java 单元：每种 FormulaNode、arity、外部/内部枚举映射、token 派生、legacy child wrapper。
2. Java SQLite：新旧 operation formula 保存、完整重开、计算、history、dependency 一致。
3. HTTP contract：validate/preview/create/update/detail/history 的同一 AST round-trip；请求拒绝 tokens；错误 field path。
4. Vue component/Playwright：递归 builder、field error、history 独立失败、AppDialog 键盘、records cursor/race、mutation same-key。
5. 真实浏览器：1024×768、1440×900 和 200% zoom；320/375 只验证 dialog 可关闭和可滚动。
6. 真实浏览器/Java/SQLite：记录变化→公式→Dashboard→布局→服务重启后的完整重开；最后再执行 P5-008 性能门槛。

## 10. 发布、兼容与回退

- P6 不改变数据库 schema，不新增 migration。
- Vue 静态资源与 Java 必须作为同一本机网页版本发布；不支持旧前端连接新后端或反向组合。
- 实施 P6-001 前用匿名隔离副本覆盖 legacy operation AST；不得直接修真实用户数据库。
- 若 P6-001 失败，停止 P6-002 公式界面扩展。其他纯交互任务可以保留，但不得宣称指标闭环完成。
- P6-008 通过前，P5-008 保持 BLOCKED，产品文档继续把指标/总览标记为部分完成。

## 11. 方案取舍

- 不用 Pinia：当前共享状态只有 profile、revision、capabilities 和 epoch，provide/inject 足够。
- 不用 Vue Router：六个页面只需 hash 恢复和前进后退，没有嵌套路由或远端加载。
- 不用通用表达式解析库：公式由结构化 AST 编辑，Java 已有受限 evaluator；修正 mapper 比引入新语言更安全。
- 复用 GridStack `sizeToContent`：当前精确版本已包含该能力，避免另写卡片测量和碰撞系统。
