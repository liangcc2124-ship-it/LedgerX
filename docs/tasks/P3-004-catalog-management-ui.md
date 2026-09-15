# P3-004：Vue 分类与账户管理页

- 状态：PARTIAL（基础管理页已实现；P3-002/P3-003 完整合同和本任务剩余边界待补）
- 单一结果：用户可在 Vue 桌面界面中查看、创建、编辑、归档和合并自定义分类，并查看、创建、编辑、归档自定义账户；前端严格使用已实现 REST 契约且清楚反馈成功、校验、冲突与待确认状态。

## 1. 背景、目标、范围与非目标

P3-002/P3-003 已把分类与账户的公共 REST 契约固定。本任务交付该契约的唯一 Vue 管理入口，使用户能准备自己的账户和分类；它不录入收支记录。

范围：新分类与账户管理页、App 导航接入、响应校验、表单和两次确认、分页/筛选/错误/并发体验、浏览器级 Vue 测试、必要 fixture/文档。

非目标：records 页面、余额汇总/图表、资产/指标、设置页重写、前端改变 API 语义、直接 SQLite/IPC、Node integration、账户恢复/删除/合并、系统分类编辑、系统账户归档、发布包或快捷方式。

## 2. 前置依赖及引用规范

- 前置：P3-001、P3-002、P3-003 PASS；当前 P2-005 基础真实集成证据仍有效。
- 必读：AGENTS.md；docs/requirements.md 第 3、5、6、7 节；docs/architecture.md 第 3–7 节；docs/api.md 第 1–10、12–13 节；docs/api/ledger-records-api.md 第 2.1、2.2、3、4 节；docs/modules/ledger-records.md；docs/modules/ui-integration.md；P2-001、P2-002、P2-003、P3-002、P3-003。
- 只能通过 frontend/src/apiClient.js 的 requestApiJson/createRequestId 调用相对 API 路径。它是 token/请求 ID/错误 envelope 的唯一前端入口；不得新增 token、profileId、绝对 origin、Electron IPC 或数据库访问。

## 3. 允许/预计修改的模块和文件

- 新增 frontend/src/components/CatalogPage.vue。
- frontend/src/App.vue，仅新增 catalog 页面导航、合法 activePage 值、焦点转移和组件挂载。
- frontend/src/styles.css，仅为容纳真实管理页和 320px 窄屏所需的最小布局调整；CatalogPage 自身优先使用 scoped 样式。
- frontend/tests/catalog-page.spec.js；仅必要时新增与该页面直接相关的 fixture/helper。
- docs/contracts/api-v1/categories 和 accounts 下已由后端提供的 fixture；docs/modules/ui-integration.md、docs/api/ledger-records-api.md、本任务、docs/verification/P3-004-catalog-management-ui.md。

禁止修改 electron、Java、migration、package.json、vite 配置、apiClient.js 的公共行为、ProfilesPage、SettingsPage、旧 WPF/React。若现有 apiClient.js 不能表达已接受 REST 请求，停止并升级，不要另写 fetch。

## 4. 实现步骤与必须遵守的前端/API 契约

### 4.1 入口、读取与可访问性

1. READY 后 App 导航新增“分类与账户”。只有 home、profiles、settings、catalog 四个值可选；选择 catalog 后把焦点移到 catalog-page-title。现有启动、profiles、settings 体验不得改变。
2. CatalogPage mount 时并发读取 GET /api/v1/categories?limit=200 与 GET /api/v1/accounts?limit=200。每一资源独立 loading/error/refresh；任一失败不遮蔽另一资源。读取 15 秒超时后显示可重试中文提示；卸载或重新读取消旧请求。
3. 首次列表 response 必须运行时校验：data.items 是数组、page 有 nextCursor/hasMore/limit、meta.dataRevision 为非负安全整数；分类逐项有 CategorySummary 的全部字段，包括 canUseForRecords；账户逐项有 AccountSummary 的全部字段。ETag 缺失或字段/枚举/金额格式不合格时显示“本地服务返回了无法读取的数据”，不渲染猜测值。
4. 每个列表有“显示已归档”复选框；切换后从 cursor=null 重读 includeArchived=true/false。hasMore=true 时显示“加载更多”，只用服务端 nextCursor 追加同一 filter 的下一页；切换筛选、刷新、profile 页面激活后不保留旧 cursor/行/ETag。
5. 所有状态、保存结果和服务端错误使用 role=status 或 role=alert；错误摘要可聚焦。键盘可完成导航、表单、编辑、二次确认、加载更多和重试；320px 宽度不出现水平裁剪或不可达按钮。

### 4.2 分类界面与请求

1. 页面展示系统分类与自定义分类的两级树。父分类显示不可用于记录的说明；叶子显示 recordTypes、系统/自定义/已归档状态和 canUseForRecords。只读 seed 值来自服务端，不在前端复制一份系统清单。
2. “新增分类”表单字段精确为 name 和 parentId。parent 下拉只能提供 ACTIVE 顶级分类和“不设父分类”；不让用户选择第二层或归档项。前端 trim 后检查 name 1–100、parentId 为空或已经在列表中的 top-level UUID；后端仍是唯一裁决者。
3. 新建请求为 POST /api/v1/categories，body={id,name,parentId}，id=createRequestId()，Idempotency-Key 为另一个 createRequestId()。成功后以响应 data.category 和 ETag 更新/插入行，显示“分类已创建”；不得由前端推算 sortOrder、recordTypes 或 canUseForRecords。
4. 只有 ACTIVE 且 isSystem=false 的分类显示“编辑”“归档”“合并来源”操作。编辑必须提交完整 name、parentId 到 PUT /api/v1/categories/{id}，If-Match 用该行最后 ETag；成功后替换该行。系统分类不提供编辑/归档/合并操作。
5. 归档点击后只进入本页面的明确确认状态，确认按钮文字为“确认归档”；取消不发 DELETE。确认后 DELETE 使用该行 ETag 和一个保存于 pending mutation 的 Idempotency-Key。409 ACTIVE_CHILDREN 或其他 field/details 显示服务器信息并保持列表/表单可恢复；不假装已归档。
6. 合并要求用户选择一个 ACTIVE 非系统 source 和一个 ACTIVE canUseForRecords=true target；target 不得等于 source，也不得为 source 的直接子分类。首击只进入确认，确认时 POST /api/v1/categories/{targetId}/merge，If-Match 用 target ETag，body={sources:[{id:sourceId,expectedRevision:sourceRevision}]}，使用新 Idempotency-Key。成功后按 response mergedIds 更新源为 ARCHIVED、刷新分类列表；失败不局部改 UI。

### 4.3 账户界面与请求

1. 账户列表显示 name、kind、balanceSide、openingOn、openingBalance、balance、currency、是否计入可用现金、系统/归档状态。金额只作为服务端字符串展示；不得转 Number、相加或重新格式化为提交值。
2. “新增账户”字段精确为 name、kind、openingOn、openingBalance、currency=CNY、includeInAvailableCash。kind 可选值和 BalanceSide 映射只作即时提示；LIABILITY 时前端自动取消并禁用 includeInAvailableCash，提交 body 仍显式为 false。openingBalance 输入按带符号且最多两位小数的规范字符串预校验；不使用 number input 的浮点值。
3. 创建 POST /api/v1/accounts 的 id 与 Idempotency-Key 各自新建；成功用 response data.account/ETag 更新列表并显示成功。系统账户仍可选择“编辑”，但不存在“归档”按钮。
4. 编辑任一 ACTIVE 账户必须提交完整 name、kind、openingOn、openingBalance、currency、includeInAvailableCash 到 PUT，If-Match 为该行 ETag；不得提交 balance、balanceSide、status 或 isSystem。更改 kind 到 LIABILITY 时按照上述 false 规则；409/428 时提示“账户已被其他操作更新，请重新加载”，不覆盖用户未保存草稿。
5. 自定义 ACTIVE 账户归档必须二次确认，确认后 DELETE 使用 ETag/key；成功刷新/更新为 ARCHIVED。归档账户仅在“显示已归档”选中时可见，不能编辑或再次归档。

### 4.4 超时、重试和 profile 切换

1. 每次 mutation 30 秒超时。请求中止/网络不确定时页面显示“结果待确认”，保留原 method/path/body/If-Match/Idempotency-Key，并提供“使用相同请求重试”和现有 GET /api/v1/operations/{key} 查询结果操作；不得换新 key 自动重试。
2. 409 REVISION_CONFLICT、428 或 profile 切换后返回错误时，清除该 pending mutation 的可重试状态，提供刷新；不能把旧 profile 行/ETag 提交到新 profile。
3. 所有 server fieldErrors 的 key 原样对应表单字段显示；通用错误不显示 SQL、token、路径或内部 details。423 时禁用所有 mutation，保留只读刷新。

## 5. 用户操作、前端反馈、跨层数据、业务和持久化结果

| 用户操作 | 前端反馈 | 跨层数据 | 后端/持久化结果 | 边界 |
| --- | --- | --- | --- | --- |
| 打开分类与账户 | 两个独立加载区 | GET categories/accounts、cursor | 只读投影 | 任一接口超时/无数据/归档筛选 |
| 新建“日常开销”并挂到“其他” | 新行和成功提示 | id、name、parentId、key | custom 分类、revision=0 | 空名、重名、二级父 |
| 归档仍有孩子的父分类 | 错误提示，不消失 | If-Match、key | 无写入 | activeChildCount |
| 合并自定义分类 | 源归档、目标保持 | target ETag、source revision、key | 原子 merge | 取消、stale、类型不匹配 |
| 新建银行卡/信用账户 | 显示服务端 side/余额 | openingBalance string、kind、CNY | account 行与精确余额 | LIABILITY+include true、格式错误 |
| 编辑/归档账户 | 成功或明确冲突提示 | If-Match、key | revision/dataRevision 改变 | 系统账户不可归档、超时待确认 |

## 6. 验收标准

- 用户在实际浏览器渲染的 Vue 页面可通过导航进入“分类与账户”，并在 320px 与常规宽度下完成读取、创建、编辑、归档和合并确认；没有横向裁剪或无法聚焦的主要操作。
- 页面只使用相对 REST 请求和 apiClient；源码、DOM、storage、页面日志中不含 Bearer token、profileId、SQLite 路径或 Node API。
- 分类父节点不可作为记录可用分类被错误标记；自定义分类创建/编辑/归档/合并请求字段、ETag、Idempotency-Key、response 字段与 categories API 逐项一致。
- 账户金额字符串在输入、请求、成功回显中保持精确；LIABILITY 提交 false；系统账户可 edit 不可 archive；自定义 archived 账户不再可 edit。
- 对合法响应、400 field error、401、409 conflict、423、超时待确认、operation 查询、取消确认、分页/归档筛选、profile 切换旧 cursor 失效分别有浏览器级断言。测试不能只断言元素存在。
- frontend npm test 通过；不修改 Java/Electron/发布包，也不称为真实原生持久化验证。

## 7. 测试层次、命令与真实链路

- 前端合同/浏览器：使用 API fixture 驱动 mock，必须模拟 status、ETag、Location、error fieldErrors、cursor、timeout 和 operation 查询，不能固定成功。
- 必跑：frontend 目录使用当前本机 Node 执行 npm test，并报告实际 Node 版本与结果；不要求 Node 22 复测。
- 真实 Java/Electron/SQLite、发布包、桌面快捷方式：本任务不适用；P3-005 才强制真实桌面闭环。

## 8. 禁止事项、升级、文档与完成报告

禁止改 API/public fields、复制后端余额或分类规则、直接 fetch/IPC/SQLite、前端保存 token/profileId、自动确认危险操作、自动换 key 重试、用 mock 冒充真实桌面、顺手重写 SettingsPage 或全局样式。

以下情况停止并升级：P3-002/P3-003 任何验收未通过；fixture 与真实 API 不一致；需要修改 apiClient 公共行为、API/database/architecture；UI 要求 records/asset 数据；无法在不扩大权限的情况下实现文件能力；发现系统 seed 或 ETag 语义冲突。

完成报告必须写：修改文件；用户操作与字段闭环；实际 frontend 命令/数量；mock 与真实链路的明确区分；发布包/快捷方式不适用；未测试项、残余风险、文档同步。不得报告为“可记账完成”。
