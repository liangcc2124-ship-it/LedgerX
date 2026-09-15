# P3-005：分类与账户真实桌面集成验收

- 状态：PARTIAL（基础分类/账户新建与重开已验证；完整 P3 门禁仍未满足）
- 单一结果：在隔离数据根中，用真实 Electron、Vue、Java HttpServer 和 SQLite 验证系统 seed、分类管理、账户管理、重开持久化与本机会话安全；默认不修复产品代码。

## 1. 背景、目标、范围与非目标

P3-001 到 P3-004 分别验证 schema、后端和前端。本任务只证明用户实际点击能穿过 Electron→Vue→受认证 REST→Java→SQLite，并在完整关闭重开后得到相同的分类/账户状态。

范围：新增/调整真实 Electron E2E、只读 SQLite/health 补充核验、验证记录、P3 总表状态同步。覆盖 seed、分类创建/编辑/归档/合并、账户创建/编辑/归档、屏幕反馈、隔离和重开。

非目标：修复 Java/Vue/Electron 产品代码、记录/资产/指标、备份/导入、安装包、签名、快捷方式、性能压测。发现产品缺陷必须按 BUG 流程形成规格，不能在此任务顺手修。

## 2. 前置依赖及引用规范

- 前置：S0-006、BUG-004、P2-005、P3-001、P3-002、P3-003、P3-004 都是 PASS，且没有未升级的公共契约变更。
- 必读：AGENTS.md；docs/requirements.md 第 3、6、7 节；docs/architecture.md 第 5–10 节；docs/api.md；docs/api/ledger-records-api.md 第 2–4 节；docs/contracts/core-catalog-v1.md；P2-005；P3-001 至 P3-004；docs/tasks/README.md。
- 若实现与上述契约冲突，停止、保留证据、创建或升级 BUG Task Spec；不得把测试改为直接 HTTP/SQL 以避开 UI。

## 3. 允许/预计修改的模块和文件

- 新增 electron/tests/p3-005-catalog-ui.integration.spec.mjs；仅为该 spec 新增专属 helper。
- docs/verification/P3-005-catalog-ui.md、本 Task Spec、docs/tasks/README.md；若实际命令经验证可最小更新 README.md。

禁止修改 frontend/src、src/main、migration、API/database/module 文档、package manifests、旧 WPF/React、发布目录、桌面快捷方式。任何产品代码异常均按 BUG 流程处理。

## 4. 实施步骤与必须遵守的真实链路

1. 为本 test 创建唯一隔离数据根。它不得等于工作区、真实 LocalAppData LedgerX 目录、用户 home 或已有数据根；启动 Electron 时维持默认 sandbox:true，绝不传 --no-sandbox。
2. 在 READY 后用实际导航进入“分类与账户”。读取系统 seed：UI 至少展示“收入”“工资薪酬”“固定资产”“电脑及电子设备”“应付款”“未分类”和系统账户“现金储备”；“收入”等无类型父节点的不可记录说明可见，系统分类不能出现编辑/归档/合并来源操作，现金储备可以编辑但不可归档。
3. 仅通过 UI 创建自定义顶级分类“测试父类”和子分类“测试子类”。尝试归档父类，断言 UI 显示拒绝且父/子仍为活动；归档子类，确认后父仍可见；再归档父类。先后操作都需验证确认前没有 DELETE 请求。
4. 仅通过 UI 创建两个同级自定义分类“合并源”和“合并目标”。在合并 UI 选定 source/target，取消一次确认并断言无 POST merge；第二次确认后断言 source 归档、target 活动，显示已归档后可看见 source。不得以 SQL/直接 HTTP 构造结果。
5. 仅通过 UI 创建 BANK 账户“测试银行卡”，openingOn=2026-01-01，openingBalance=100.25，includeInAvailableCash=true；创建 CREDIT 账户“测试信用账户”，openingBalance=500.00，断言 UI 显示 LIABILITY 且现金标记为关闭。编辑现金储备名称为“我的现金储备”；尝试/检查该行无归档动作。编辑银行卡名称为“测试银行卡已改名”，随后通过两次确认归档它。
6. 完全关闭 Electron 与 Java child，确认本测试启动的 child PID 已退出。用相同隔离根重启后，等待 READY 并在 UI 验证：71 个 seed 仍不重复；已归档父/子/合并源/银行卡只在“显示已归档”可见；合并目标、信用账户和改名后的系统现金储备仍正确；银行卡 openingBalance=100.25、信用账户余额=500.00 以精确两位字符串显示。
7. 只读补充检查允许打开隔离 SQLite：schema_history=4、category=75（71 seed加测试父/子/源/目标）、financial_account=3（系统加银行卡加信用）、seed UUID 未变、必要 archived_at 非空。此检查只补强持久化证据，不能替代第2–6步 UI 操作。
8. 采集安全证据：renderer bundle/DevTools 不含 token；错 token/Origin 仍被 Java 拒绝；无非 loopback 请求；日志和验证输出不出现 token、备注、真实用户数据或绝对隔离路径；测试结束后安全清理已核验隔离目录。

## 5. 用户操作、前端反馈、跨层数据、持久化结果和边界

| 用户操作 | 前端反馈 | 跨层数据 | 持久化结果 | 边界 |
| --- | --- | --- | --- | --- |
| 新建/归档父子分类 | 成功提示或 activeChildCount 错误 | POST/DELETE、ETag、key | 只有确认后写 archivedAt | 归档父先失败、取消无 DELETE |
| 合并分类 | 二次确认、源归档 | target If-Match、source expectedRevision | 原子 merge | 取消不 POST、归档筛选重读 |
| 新建/编辑/归档账户 | side/金额/成功状态明确 | 金额 string、kind、If-Match、key | 账户重开仍相同 | liability 不计现金、系统不可归档 |
| 完整重开 | UI 重读已保存状态 | 新 token/端口/旧 cursor 无效 | SQLite 无重复 seed | child 退出、空间隔离、安全拒绝 |

## 6. 验收标准

- 测试必须通过真实可见 UI 完成全部分类与账户操作；没有一步以 HTTP、SQL、mock 或静态 DOM 代替用户点击。
- V004 seed 在 UI 和只读数据库核验中精确为 71 分类、65 类型关联、1系统账户；重开后不重复，固定 ID 不变。
- 子分类阻止父归档、取消确认不发 mutation、合并成功后 source archived/target active、系统分类无编辑归档合并入口，均有请求观察和最终 UI 断言。
- BANK/CREDIT 的 100.25/500.00、ASSET/LIABILITY、现金标记约束、系统账户可编辑不可归档、银行卡归档和完整重开均通过；所有金额保持两位字符串。
- 真正 Electron renderer 无 token/Node、sandbox 启用；错误 token/origin 被拒绝；无公网请求；Java child 退出且隔离目录清理。
- Java、Vue、Electron 既有必跑测试和本新增 E2E 全部通过。任何无证据项使 P3-005 不得标记 PASS。

## 7. 测试层次、命令与真实链路

- 必跑：仓库根 .\mvnw.cmd -q '-Dmaven.compiler.fork=true' test package；frontend 目录 npm test；electron 目录 npm test、npm run test:integration；再直接执行 node node_modules/@playwright/test/cli.js test tests/p3-005-catalog-ui.integration.spec.mjs --reporter=line。
- 真实原生链路：强制 Electron→Vue renderer→认证 REST→Java HttpServer→sqlite-jdbc 文件，强制隔离根和完整重开。使用并报告当前本机 Node 版本；不要求 Node 22 复测。
- 发布包与桌面快捷方式：不适用；不得为了通过集成验收而打包、签名或创建快捷方式。

## 8. 禁止事项、升级、文档与完成报告

禁止 --no-sandbox、真实用户数据、杀所有 java 进程、修改产品代码、替换 token/origin 防线、跳过确认、以直接 HTTP/SQL 代替 UI，或把 Forge 审计描述为已通过。

发现 API/database/architecture 改动、seed 不一致、数据串 profile、重开丢失/重复、token 泄露、sandbox 不可用、Java child 遗留或任何产品缺陷时，停止并按 BUG 流程升级。

完成报告固定写：PASS/FAIL；测试与验证文件；每层实际命令和数量；真实 UI/进程/SQLite 重开脱敏证据；隔离根复核清理；发布包/快捷方式不适用；未测项、Node/Forge 风险和 Review 关注点。不得声称 records、备份或分享包已经完成。
