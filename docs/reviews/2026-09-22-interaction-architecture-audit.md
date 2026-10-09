# LedgerX 交互、可用性与轻量架构审计

- 日期：2026-09-22
- 审计对象：`frontend/` Vue 3 网页、Java 11 本地 REST、SQLite V005 指标与总览链路
- 结论：当前基础记账可继续使用；指标公式和部分桌面交互仍处于“可演示但未形成可靠闭环”的阶段，不应标记为完整可用
- 原则：保留 Vue 3 + Java 11 + SQLite 模块化单体，不引入 Pinia、Vue Router、Headless UI、微服务、消息队列或新的后端框架
- 后续规格：[P6 技术设计](../design/P6-interaction-reliability-technical-design.md)、[ADR-013](../decisions/ADR-013-formula-ast-single-source.md)、[P6 任务清单](../tasks/README.md#37-p6-交互可靠性与业务闭环收敛)

## 1. 规模判断与审计范围

LedgerX 是小型、本地单用户产品，但财务计算、幂等写入、公式版本和跨进程契约使跨层风险达到中等。适合继续采用一个 Vue 前端、一个 Java 进程和每空间一个 SQLite 数据库的模块化单体。当前问题主要不是框架能力不足，而是同一种交互和契约在不同页面被重复实现后出现语义漂移。

本次检查包括：

- 首页日/周/月/年切换、卡片布局编辑和恢复默认；
- 用户空间、分类、账户、记录和指标表单；
- 键盘焦点、Escape、背景滚动和桌面宽度下的内容完整性；
- API capability、分页、并发、幂等和错误反馈；
- 自定义公式从创建、持久化、读取、计算到再次编辑的完整链路。

未执行 Electron 壳、正式安装包和真实用户数据测试。本次浏览器功能检查使用独立临时数据目录，不接触正式账本。当前环境未提供 Browser 插件，因此使用项目已有 Playwright 作为浏览器检查工具。

## 2. 结论摘要

### 必须先修复

1. 运算公式的 API 序列化和持久化读取不闭环。界面可以得到 201 成功，但总览无法计算，再次编辑也无法正确回显。
2. 公式界面只支持常数、指标引用和一层函数，未提供已经写入需求与 API 的分类收入、分类支出、账户余额、时间变量和嵌套公式。

### 下一批应修复

1. 弹窗缺少统一焦点管理，键盘用户会落到背景页面，Escape 也因此不稳定。
2. 1024px 桌面宽度下有 5 张默认指标卡内容越出卡片边界。
3. 布局“恢复默认”会立即写数据库，但界面仍保留“取消”，造成取消成功的错觉。
4. 收支记录只取前 50 条，没有“加载更多”，旧记录会永久不可见。
5. 页面请求和 mutation 处理方式不一致，存在列表竞态、超时后生成新幂等键、结果不确定却无法查询的问题。
6. `dashboard.layout.write` capability 没有真正控制布局编辑按钮。

## 3. 缺陷清单

| 编号 | 优先级 | 缺陷 | 证据与影响 | 最小修复 |
| --- | --- | --- | --- | --- |
| F-001 | P0 | 运算公式保存后不能可靠读取、计算和编辑 | 通过真实网页创建减法指标得到 HTTP 201；响应把子节点返回成 `{schemaVersion, root}` 包裹而不是直接 FormulaNode，外部 `SUBTRACT` 又被返回成内部 `SUB`。总览结果为 `DEPENDENCY_UNAVAILABLE`，重开编辑器时函数选择为空。 | 先固定唯一外部 AST 枚举与节点结构；Java 在 HTTP 边界做显式映射，递归序列化子节点时不再嵌套 schema wrapper；增加真实 SQLite 重开回归。 |
| F-002 | P0 | 公式功能与已接受需求不一致 | UI 只有“常数 / 指标引用 / 函数运算”；没有 CATEGORY_INCOME、CATEGORY_EXPENSE、ACCOUNT_BALANCE、TIME，也不能编辑任意嵌套 AST。用户无法完成分类支出占比等核心场景。 | 用递归 FormulaNode 编辑器完成引用选择和 1–32 参数节点；先覆盖最小、最大、平均、四舍五入、限制范围、安全除法及四类引用。 |
| F-003 | P0 | AST 与 tokens 存在两个事实来源但服务端未按文档校验一致性 | 当前前端对运算公式只提交一个 function token，子引用和常数未进入 token 序列；Java 只验证 token 是非空数组，随后原样持久化。接口文档却声称服务端会规范化并比较语义。 | mutation 请求只接收 AST；tokens 和 dependencies 全部由 Java 从 AST 派生并在响应中返回。若暂时保留 tokens，请在同一提交内实现规范生成和一致性校验。 |
| F-004 | P1 | 弹窗焦点、Escape 和背景滚动失效 | 用户空间、账户、指标弹窗打开后焦点仍停在背景触发按钮；`Shift+Tab` 可进入侧栏；`body` overflow 仍为 visible。用户空间和账户把 Escape 绑在遮罩层，焦点不在其 DOM 子树时 Escape 不会关闭；指标弹窗根本没有 Escape 处理。 | 新增一个 `AppDialog.vue`：Teleport 到 body、打开时聚焦首个字段、Tab 循环、Escape 关闭、关闭后恢复触发按钮、打开时锁定背景滚动；所有表单复用。 |
| F-005 | P1 | 1024px 下指标卡内容越界 | 浏览器检查在 1440px 无溢出，但 1024px 下 `savings-rate`、`fixed-expense`、`variable-expense`、`total-expense`、`income` 的 `scrollHeight > clientHeight`。CSS 同时设置容器和卡片 `overflow: visible`，内容可能压到相邻卡片。 | 重新标定 GridStack `cellHeight/minH` 和卡片文字层级；按真实内容计算最小高度，1024px 下不得发生视觉重叠。不要靠裁切关键内容解决。 |
| F-006 | P1 | “恢复默认”与“取消编辑”语义冲突 | 隔离数据实测：点击恢复默认后布局 revision 从 2 增至 3，再点取消，数据库仍是 revision 3。用户看到“取消”，但写入已经发生。 | 保留现有 reset API 时，将按钮改为“恢复默认并保存”，成功后立即退出编辑模式；取消只针对尚未 PUT 的草稿。 |
| F-007 | P1 | 收支记录超过 50 条后不可继续浏览 | `GET /records?...&limit=50` 的 page/nextCursor 没有被读取，页面也没有加载更多。 | 复用用户空间页已有的 cursor 模式；切换当前/回收站时清空 cursor 并取消旧请求。 |
| F-008 | P1 | 列表请求存在竞态 | Records 的当前记录/回收站共用同一个结果引用，没有 generation 或 AbortController；Metrics 的归档筛选也相同。慢的旧请求可以覆盖新的筛选结果。 | 新增小型 `useAsyncResource` composable，统一 generation、AbortController、loading/error/empty/retry；不增加状态管理库。 |
| F-009 | P1 | mutation 的幂等和“结果待确认”处理不一致 | 用户空间和分类页会保存原请求并查询 operation；记录、指标和布局页每次重试都会生成新 Idempotency-Key，也没有 operation 查询。超时后可能重复提交或只得到 revision conflict。 | 新增 `useApiMutation` composable，首次提交时冻结 method/path/body/If-Match/idempotencyKey；网络结果不确定时只能同 key 重试或查询 operation。 |
| F-010 | P1 | capability gating 不完整 | App 只用 `dashboard.read` 决定是否展示总览，但 DashboardPage 无 `canWrite` 输入；缺少 `dashboard.layout.write` 时仍显示编辑按钮，最终只能由 API 报错。 | App 将写 capability 明确传给页面；入口级 capability 决定按钮是否出现或禁用，不用失败请求代替权限提示。 |
| F-011 | P1 | 公式版本读取失败会阻止整个编辑器打开 | `editMetric` 把详情和历史请求放在同一个 try 中；历史失败时已有的详情也被丢弃。`historyError` 已声明但未真正赋值。 | 先打开详情编辑器，再独立异步读取历史；历史失败只影响历史区域。 |
| F-012 | P2 | 指标版本历史只显示前 50 条 | API 支持 cursor，但界面既不读取 page 也不加载下一页。每次完整替换都会新建版本，长期使用必然截断。 | 在历史区域增加“加载更早版本”，保存并使用 server cursor。 |
| F-013 | P2 | 页面位置不能刷新恢复，也不支持浏览器前进/后退 | `activePage` 只存在内存中，刷新总回首页。网页版尤为明显。 | 用原生 `location.hash` 同步 `home/records/catalog/metrics/profiles/settings`，监听 `hashchange`；当前无需引入 Vue Router。 |
| F-014 | P2 | 术语与状态显示未完成中文化 | 账户页直接显示 CASH/BANK、ASSET/LIABILITY、ACTIVE；指标预览直接显示 dataStatus；显示格式仍显示 CURRENCY 等。 | 建立单一 `presentationMaps.js`，只负责稳定枚举到中文标签的映射，传输值保持英文。 |
| F-015 | P2 | 时间视图只有粒度和日期输入，缺少高频导航 | 四种粒度能正确请求并显示，但切换上一期、下一期或回到本期必须手工改日期。 | 增加“上一期 / 本期 / 下一期”；日期计算可在前端只调整 anchor，真正期间边界仍由 Java 返回。 |
| F-016 | P2 | tab 语义不完整 | 日/周/月/年使用 `role=tab`，但没有 roving tabindex、方向键切换和关联 tabpanel。 | 要么补齐 WAI-ARIA tab 键盘行为，要么改成普通分段按钮组，避免声明未实现的控件语义。 |
| F-017 | P2 | 设置页说明已经过时 | 页面仍写“提供收支记录、分类和账户管理”，没有提到已出现的指标和财务总览。 | 与当前 capability 和发布状态同步文案，明确哪些功能仍为预览/部分完成。 |

## 4. 轻量架构改进方案

### 4.1 前端：保留页面局部状态，只抽取三种重复机制

不引入全局 store。App 继续持有启动状态、activeProfileId、dataRevision 和 activePage；页面继续持有自身表单数据。只增加三个薄层：

1. `AppDialog.vue`
   - 统一 Teleport、焦点圈、Escape、滚动锁、busy 状态和焦点恢复。
   - 页面只传 title、open、busy，并通过 slot 放表单。
2. `useAsyncResource.js`
   - 统一读取请求的取消、竞态保护、loading/error/retry 和 append cursor。
   - Dashboard、Records、Metrics、Catalog、Profiles 都使用相同状态语义。
3. `useApiMutation.js`
   - 冻结一次 mutation 的请求体、revision 和幂等键。
   - 统一 success、validation、conflict、pending-confirmation、retry-same-key 和 query-operation。

这三项解决的是已出现的重复缺陷，不是为了抽象而抽象。组件仍按业务页面组织，不需要新增大型目录体系。

### 4.2 前端：增加一个很小的应用上下文

App 通过 `provide/inject` 提供：

```js
{
  activeProfileId,
  dataRevision,
  capabilities,
  commitMutation(meta),
  switchProfile(profileId)
}
```

mutation 成功后用响应 `meta.dataRevision` 更新上下文；切换空间时增加 context epoch，使仍挂载的读取请求作废。它不是业务缓存，也不保存金额或公式，只负责当前数据域和失效信号。

### 4.3 Java：继续单体，但拆开超大的应用服务和 HTTP 路由

`ProfileApplicationService` 当前同时承载 profile、catalog、record、metric、formula、dashboard 和序列化辅助逻辑，改动容易相互影响。建议在同一 JVM、同一事务边界和同一数据库连接策略内拆成：

- `ProfileService`
- `CatalogService`
- `RecordService`
- `MetricsService`
- `DashboardService`

`LedgerHttpServer` 仍使用 JDK HttpServer，但把路由处理分到对应的 `*HttpHandler`。共享认证、requestId、错误 envelope、If-Match 和 Idempotency-Key 解析留在一个小型 HTTP 支撑类中。不要引入 Spring、事件总线或依赖注入框架。

## 5. 接口收敛方案

### 5.1 公式请求只保留一个权威来源

推荐的写入 DTO：

```json
{
  "name": "餐饮支出占比",
  "displayFormat": "PERCENT",
  "precision": 2,
  "visibility": { "hidden": false, "dashboardEnabled": true },
  "formula": {
    "ast": {
      "schemaVersion": 1,
      "root": {
        "kind": "SAFE_DIVIDE",
        "children": [
          { "kind": "REF", "referenceKind": "CATEGORY_EXPENSE", "key": "<category-uuid>" },
          { "kind": "REF", "referenceKind": "METRIC", "key": "total-expense" }
        ]
      }
    }
  }
}
```

规则：

- 只有 AST 是写入事实；tokens 和 dependencies 由 Java 生成并返回。
- `schemaVersion` 只出现在 AST 根，不出现在每个 child。
- 外部枚举固定使用 `ADD/SUBTRACT/MULTIPLY/DIVIDE/NEGATE/...`；内部 Java enum 如需 `SUB/MUL/DIV`，只能在 mapper 内转换。
- validate、preview、create、update 使用同一个 `FormulaDraft` 校验器和同一个响应 mapper。
- 保存后必须以 SQLite 重新读取的对象构造响应，不能直接回显请求对象来掩盖持久化不一致。

### 5.2 mutation 统一约定

所有有副作用的请求继续使用现有：

- `If-Match`：实体或布局并发控制；
- `Idempotency-Key`：同一用户意图的稳定键；
- `X-Request-Id`：每次网络尝试的关联 ID；
- 成功响应：`data + meta.dataRevision + ETag`；
- 结果不确定：前端保留原请求，可用同 key 重试或 `GET /operations/{key}` 查询。

关键区别是：网络重试可以换 requestId，但不能换 idempotencyKey、body 或 If-Match。

### 5.3 布局接口保持现状，修正界面语义

`POST /dashboard/layout/reset` 是立即持久化操作，不应伪装成编辑草稿。暂不新增 default-preview endpoint：

- “取消”只撤销本地 draft；
- “保存布局”才 PUT；
- “恢复默认并保存”单独确认并 POST reset，成功后退出编辑；
- 将来确有“先预览默认、再决定保存”的需求，再增加纯读 `GET /dashboard/layout/default`。

### 5.4 分页响应必须被页面消费

Records、Profiles、Categories、Accounts、Metrics 和 Formula Versions 统一读取：

```json
{
  "data": {
    "items": [],
    "page": { "nextCursor": null, "hasMore": false }
  },
  "meta": { "dataRevision": 42 }
}
```

cursor 仍由服务端生成并绑定 profile、筛选和 dataRevision；前端只保存和回传，不解析。

## 6. 建议实施顺序

| 顺序 | 任务 | 依赖 | 完成门槛 |
| --- | --- | --- | --- |
| 1 | 修复 Formula AST 外部映射、递归序列化、持久化读取和服务端派生 tokens | 无 | 四类运算经创建、重开、预览、总览、再次编辑后值和 AST 一致 |
| 2 | 补齐分类/账户/时间引用和递归公式编辑器 | 1 | “分类支出 / 总支出”可用中文提示完成并通过真实后端保存 |
| 3 | 建立 AppDialog 并替换全部表单弹窗 | 无 | 首焦点、Tab 圈、Escape、滚动锁、焦点恢复在所有弹窗一致 |
| 4 | 建立 useAsyncResource/useApiMutation，修复分页和竞态 | 无 | 快速切筛选不串数据；超时后只允许同 key 重试/查询；第 51 条记录可达 |
| 5 | 修正布局 reset 语义、1024px 卡片最小尺寸和 capability gating | 1 可并行 | 1024px 无重叠；reset 后不再出现误导性取消；只读 capability 不显示编辑 |
| 6 | 原生 hash 导航、中文映射和时间快捷导航 | 3、4 | 刷新/前进/后退保持页面；稳定枚举不直接暴露给用户 |

## 7. 必须增加的验收场景

1. 通过真实 HTTP 和 SQLite 创建 `收入 - 固定支出`，关闭后端并重开，再次编辑和总览计算仍一致。
2. 创建 `分类支出("餐饮") / 总支出`，验证改名不破坏 UUID 引用、零分母为 NOT_COMPUTABLE。
3. 创建含 3 层嵌套、MIN/AVG/ROUND/CLAMP 的公式，验证 AST、tokens、dependencies 由服务端规范生成。
4. 每个弹窗仅用键盘完成打开、填写、取消、保存；焦点不能进入背景，关闭后回到触发按钮。
5. 快速切换当前记录/回收站和显示/隐藏归档，旧慢响应不得覆盖新筛选。
6. 构造 51 条记录和 51 个公式版本，最后一条可通过 cursor 浏览。
7. 布局编辑后取消零 mutation；reset 明确立即写入；超时后同幂等键可查询。
8. 1024×768 和 1440×900 检查长中文名称、长说明、不可计算状态和编辑控制；卡片不重叠、不裁掉关键内容。
9. 320/375px 不作为产品布局验收，但弹窗仍需可关闭、可滚动，不能把用户困在不可操作状态。
10. capability 组合测试至少覆盖 read-only dashboard、无 formulas.write、后端 423 和 revision conflict。

## 8. 本次验证证据

- 隔离 Java/SQLite 启动成功：schemaVersion 5，指标、公式、Dashboard capability 均存在。
- 日、周、月、年四个视图都发起真实请求并返回当前期与上一期范围。
- 布局 reset 后 revision 从 2 变 3，随后点击取消仍保持 3，证实其已经持久化。
- 真实网页创建减法指标返回 201；响应 AST 为内部 `SUB` 且 child 结构错误；重开函数值为空；Dashboard 为 `DEPENDENCY_UNAVAILABLE`。
- 弹窗检查证实焦点留在背景按钮、Shift+Tab 可进入侧栏、背景滚动未锁定、Escape 不稳定或未实现。
- 1440px 默认卡片无内容越界；1024px 有 5 张默认卡越界；320/375px 全部默认卡越界。后两者不是移动端产品门槛，但可作为弹窗可达性检查。
- 页面运行期间未观察到浏览器 console error；这说明这些问题属于状态和契约正确性，不会靠消除运行时报错自动解决。

## 9. 当前不建议做的事

- 不引入 Pinia 来解决局部表单状态；问题是请求生命周期没有统一，不是 store 不够大。
- 不引入 Vue Router；原生 hash 已足够覆盖六个本地页面。
- 不引入 Spring、微服务或消息队列；当前吞吐、部署和协作规模都不需要。
- 不先做更多卡片类型或图表库；公式闭环、弹窗可达性和分页比新增展示更重要。
- 不把复杂 AST 退回自由文本 `eval`；继续使用受限 AST，修正其边界映射即可。
