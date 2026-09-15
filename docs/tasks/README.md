# LedgerX 前后端分离实施任务顺序

- 状态：当前有效
- 日期：2026-09-14
- 架构：Vue 3/JavaScript + Electron + Java 11 loopback REST + SQLite

## 1. 执行顺序

| 顺序 | Task | 依赖 | 单一结果 |
| --- | --- | --- | --- |
| 1 | [S0-001](./S0-001-java-backend-baseline.md) | 无 | 去除 JavaFX，形成纯 Java 11 后端基线 |
| 2A | [S0-002](./S0-002-local-rest-contract.md) | S0-001 | 真实 loopback live/status、认证和 fixtures |
| 2B | [S0-003](./S0-003-vue-frontend-foundation.md) | 无；真实 HTTP 验证等 S0-002 | 独立 Vue/JS/Vite status 基座 |
| 2C | [S0-005](./S0-005-sqlite-profile-bootstrap.md) | S0-001；status 接线等 S0-002 | catalog/default profile/ledger V001 可重开 |
| 3 | [S0-004](./S0-004-electron-shell.md) | S0-002、S0-003 | Electron 安全壳和 Java 生命周期（ADR-006 临时豁免开发；正式发布仍受审计门禁） |
| 4 | [S0-006](./S0-006-electron-rest-integration.md) | S0-001..005 | 隔离 Electron→REST→Java→SQLite 证据（当前本机 Node 24 用户授权环境已通过） |

`S0-002`、`S0-003`、`S0-005` 在各自前置满足后可并行，但它们不得同时修改同一文件：S0-005 只有在 S0-002 完成后才能修改 system status provider/fixture。S0-004 和 S0-006 顺序执行。

当前执行备注：S0-004/S0-006 的受控主机隔离证据仍可复用；BUG-001 已覆盖默认启动/重开数据根，BUG-002 已覆盖启动状态语义，BUG-003 已覆盖 Vite loopback 代理安全语义，BUG-004 已在用户授权的 Node 24.19.0 环境完成默认启动和真实链路重验。当前交付以本机 Node 24 环境为准。嵌套工具沙箱不能创建 Windows restricted token；受控主机仍必须保持默认 `sandbox:true`，不得用 `--no-sandbox` 替代真实验收，详见 [ADR-007](../decisions/ADR-007-electron-sandbox-runtime.md)。

## 1.1 已确认缺陷与修复顺序

| 顺序 | Task | 依赖 | 单一结果 | 可并行性 |
| --- | --- | --- | --- | --- |
| B1 | [BUG-001](./BUG-001-default-electron-startup-data-root.md) | 无 | 默认 Electron 将规范 `%LocalAppData%\LedgerX` 数据根传给 Java，并由 Maven package 生成源码 fallback classpath | 先执行 |
| B2 | [BUG-002](./BUG-002-startup-status-lifecycle.md) | BUG-001 合入 | Vue 正确处理 STARTING/READY/RECOVERY_REQUIRED 与有限轮询 | 可与 B3 并行；不共改文件 |
| B3 | [BUG-003](./BUG-003-vite-loopback-proxy.md) | 无 | Vite 只代理精确 loopback `/api/v1`，不会向外部转发 token | 可与 B2 并行；不共改文件 |
| B4 | [BUG-004](./BUG-004-s0-startup-reacceptance.md) | BUG-001..003 PASS | 用户授权 Node 24.19.0 下的默认启动、安全和真实链路重新验收 | 最后执行 |

`BUG-001`、`BUG-002`、`BUG-003` 的实现报告通过不等于 S0 完成；BUG-004 已通过后，S0-006 与中心文档可标记为当前 Node 24 用户授权环境 PASS；这不等于正式发布认证。

## 2. 执行规则

1. 严格从 S0 Task Spec 读取允许文件、字段、状态和测试，不再执行 M0 旧任务。
2. 开始每项前复述：用户操作/反馈、关键跨层字段、业务/持久化结果和边界。
3. 发现需改变 architecture/database/api、增加依赖、改变已固定版本或触碰未授权 dirty 文件时停止相关部分并升级。
4. 只修改当前任务文件；不得借迁移删除旧 `native/` WPF 或 `web/` React。Vue 目标代码放 `frontend/`。
5. mock、真实 Java HTTP、真实 Electron 和发布包证据分别报告；前者不能冒充后者。
6. 每项结束同步实际影响的 Task Spec/命令说明，但不得把计划状态改写成已发布。

## 3. 基础里程碑完成条件

S0-006 的原始验收和 BUG-004 的重新验收已在用户授权 Node 24 环境通过，可称“前后端分离基座完成”。P2 是已完成的数据/界面底座；P3 分类/账户与 P4 基础记录均已有可执行 Task Spec。高级领域已按 ADR-010 延后，不再是基础版门禁。

## 3.1 P1 当前任务

| 顺序 | Task | 依赖 | 状态 | 单一结果 |
| --- | --- | --- | --- | --- |
| P1-001 | [Catalog V002 与有序迁移基座](./P1-001-catalog-migration-foundation.md) | S0 PASS | PASS | V001→V002 无损升级并提供 catalog revision/幂等表 |
| P1-002 | [Profiles catalog REST](./P1-002-profiles-rest-api.md) | P1-001 | PASS | profiles 列出、创建/激活、切换、归档的真实 Java/SQLite API |
| P1-003 | [Ledger settings V002 与 REST](./P1-003-ledger-settings-foundation.md) | P1-002 | PASS | active profile settings 的 V002、REST、并发和持久幂等 |

P1-001、P1-002、P1-003 已通过。P1-003 的最终 Java `test package` 数量与工具链诊断见[验证记录](../verification/P1-003-ledger-settings-rest.md)。该项只交付 settings REST 与 SQLite；Vue 设置页、自动备份执行、通知投递、主题文件和发布包仍未交付。指标数据多目标规则已由 [ADR-008](../decisions/ADR-008-direct-metric-multi-assignment.md) 裁决，不再阻塞后续 migration/metrics 规格。

## 3.2 P2 当前可下发任务

| 顺序 | Task | 依赖 | 状态 | 单一结果 |
| --- | --- | --- | --- | --- |
| P2-001 | [Vue 应用壳、导航与通用 REST 请求能力](./P2-001-vue-application-shell.md) | S0/P1 PASS | PASS（当前本机 Node 24） | READY 后的可访问导航及后续页面共享请求接口 |
| P2-002 | [Vue 用户空间管理页](./P2-002-vue-profiles-page.md) | P2-001、P1-002 | PASS（当前本机 Node 24） | profiles REST 的创建、切换、归档 UI 与真实 HTTP 证据 |
| P2-003 | [Vue 设置页](./P2-003-vue-settings-page.md) | P2-001、P1-003 | PASS（当前本机 Node 24） | settings REST 的并发安全 UI 与真实 HTTP 重读证据 |
| P2-004 | [Ledger V003 核心事实数据表](./P2-004-ledger-core-schema-v003.md) | P1-003 | PASS（本机 Java 11 隔离 11/11；Maven test package 31/31） | categories/accounts/finance records 的无业务 API SQLite 物理底座 |
| P2-005 | [用户空间与设置的真实桌面集成验收](./P2-005-catalog-settings-ui-integration.md) | P2-002、P2-003、P2-004、S0/P1 PASS | PASS（本机 Node 24 受控权限真实链路 1/1；Java/Vue/Electron 回归通过） | Electron→Vue→Java→SQLite 的 profile/settings 隔离和重开证据 |

### 执行顺序与可并行分组

1. **并行组 A**：P2-001 与 P2-004。两项分别修改 `frontend/**` 和 Java migration/persistence tests，不共享目标文件。
2. **并行组 B**：P2-002 与 P2-003（均在 P2-001 PASS 后）。两项均已完成；P2-002 因成功事件生命周期冲突获准对 `App.vue` 做最小后台刷新修改，P2-003 未修改 `App.vue`、`apiClient.js` 或共享 CSS。
3. **组 C**：P2-005（P2-002、P2-003、P2-004 PASS 后）。这是本批唯一跨层集成验收，已完成；默认只改测试/验证文档，发现产品问题按 BUG 流程升级。

P2-004 不必等待 P2 前端线；P2-005 不以它的表为 UI 功能前置，却要求其 PASS，以便本批状态与 Java migration 回归在一次集成报告中同步。它的后续 REST 实现仍必须等下方设计门禁。任何任务开始前均须以本 README 和对应 Task Spec 为准复述用户操作、跨层字段、业务/持久化结果与边界。

## 3.3 P3 核心分类与账户里程碑

P3 的目标是让用户准备分类和资金账户；它不录入财务记录，因此不能称为“可记账版”。G-001 已由系统 seed 契约、分类归档/合并规则和账户余额/种类映射收敛，以下任务可以交给低级模型实施。

| 顺序 | Task | 依赖 | 状态 | 单一结果 |
| --- | --- | --- | --- | --- |
| P3-001 | [核心分类与账户 seed V004](./P3-001-core-catalog-seed-v004.md) | P2-004 | PASS（真实 SQLite 2/2） | V003→V004 以固定契约 seed 71 分类、65 类型关联和1个系统账户 |
| P3-002 | [分类 REST API](./P3-002-categories-rest-api.md) | P3-001 | PARTIAL（实现与独立 HTTP CRUD/merge 1/1；完整异常矩阵仍需补证据） | 分类 list/create/replace/archive/merge 的 Java/SQLite/HTTP 契约 |
| P3-003 | [资金账户 REST API 与余额投影](./P3-003-accounts-rest-api.md) | P3-001、P3-002 | PARTIAL（实现与独立 HTTP CRUD 1/1、余额方向 1/1；asOf/异常矩阵仍需补证据） | 账户 list/create/replace/archive 和精确余额投影 |
| P3-004 | [Vue 分类与账户管理页](./P3-004-catalog-management-ui.md) | P3-002、P3-003 | PARTIAL（浏览器 2/2；完整异常/分页矩阵仍需补证据） | 分类/账户管理的 Vue 交互和浏览器合同测试 |
| P3-005 | [分类与账户真实桌面集成验收](./P3-005-catalog-ui-integration.md) | P3-001..004 | PARTIAL（真实目录新建/重开 1/1；全量场景仍需补证据） | Electron→Vue→Java→SQLite 的分类/账户重开证据 |

P3-001 必须先行。P3-002 与 P3-003 均修改 ProfileApplicationService、LedgerCatalogRepository 和 LedgerHttpServer，故严格顺序执行。P3-004 只在两项后修改 frontend；P3-005 仅在全部实现任务 PASS 后运行且默认不改产品代码。P3 没有可并行写入组。

## 3.4 P4 基础记账版

[ADR-010](../decisions/ADR-010-basic-ledger-scope.md) 已把记录范围收敛为三类基础收支，因此不再依赖 G-002/G-003。P4 完成后即可称为“可自用基础记账版”；分享包另行拆 P5。

| 顺序 | Task | 依赖 | 状态 | 单一结果 |
| --- | --- | --- | --- | --- |
| P4-001 | [基础记录领域与持久化](./P4-001-basic-records-domain-persistence.md) | P3-005 | PARTIAL（真实 application/SQLite 3/3） | 三类记录及余额、回收站在 Java/SQLite 重开一致 |
| P4-002 | [基础记录 REST API](./P4-002-basic-records-rest-api.md) | P4-001 | PARTIAL（真实 HTTP 1/1；全量错误矩阵仍需补证据） | records 六个 endpoint 的真实 HTTP/SQLite 契约 |
| P4-003 | [Vue 基础收支记录页](./P4-003-basic-records-vue-ui.md) | P4-002 | PARTIAL（浏览器 2/2） | 新增/编辑/删除/恢复/余额的浏览器交互 |
| P4-004 | [基础版功能入口与说明收敛](./P4-004-basic-scope-ui-cleanup.md) | P4-003 | PARTIAL（浏览器 3/3） | UI 不再展示未执行能力，并给出手工备份说明 |
| P4-005 | [基础记账版真实桌面验收](./P4-005-basic-ledger-integration.md) | P4-001..004 | PARTIAL（扩展余额/编辑/回收站/重开/profile 1/1；child PID/只读核验仍需补证据） | 真实 Electron→Vue→Java→SQLite 完整记账闭环 |

执行顺序严格为 P3-001→002→003→004→005→P4-001→002→003→004→005。P4-001/P4-002 会依次修改 Java application/HTTP；P4-003/P4-004 会依次修改 Vue；P4-005 默认只改测试/验证文档。为减少同文件冲突，本批不设置并行写入组。

## 3.5 已解除的卡点与后续可选能力

| 原门禁 | 当前处理 | 何时重新启动 |
| --- | --- | --- |
| G-002 资产/分摊 | 从基础 RecordDraft 和事务中移除；保留表为空 | 用户明确需要固定资产或跨期成本 |
| G-003 指标/公式 | 从基础 DTO、capability、UI 和副作用中移除 | 用户明确需要自定义指标/公式/仪表盘 |
| G-004 报表/预警 | 不实现 endpoint 或入口，不阻塞记录/回收站 | 用户明确需要报表或提醒 |
| G-005 旧数据迁移/应用内备份 | 基础版按新账本使用；退出应用后手工复制完整数据目录 | 用户需要迁移旧数据或一键备份恢复 |

这些内容不是 READY Task，也不是基础版未完成项。未来重启时必须先更新需求/模块/具体 API，再新建任务；不得直接按保留的高级草案编码。

### 规格完整性检查

P2-001 至 P2-005、P3-001 至 P3-005 与 P4-001 至 P4-005 均已明确：单一可观察结果、允许文件、用户操作、字段/header、事务或无持久化边界、异常、测试命令/真实链路要求、禁止事项和升级条件。基础记录公共行为已固定；实现者无需决定资产、指标、备份或金融安全规则，因为这些明确不在范围。

## 4. 一次集成验收

[S0-006](./S0-006-electron-rest-integration.md) 与 [BUG-004](./BUG-004-s0-startup-reacceptance.md) 共同构成基础里程碑的当前集成验收门禁。验收必须使用隔离数据目录、真实 Electron、真实 Java HttpServer 和真实 SQLite 文件，并覆盖无测试变量的首次启动/重开、第二实例、刷新、错误认证、后端崩溃/重试、断网、首导航网络观察和退出无孤儿进程。

P2 业务页的下一次集成验收固定为 [P2-005](./P2-005-catalog-settings-ui-integration.md)。它在 S0 门禁之上验证 profile/settings 的 UI 操作、跨 profile 隔离和完整重开，不生成正式安装包，也不替代未来全量领域/release-verification 验收。

P3 的一次集成验收固定为 [P3-005](./P3-005-catalog-ui-integration.md)。它在 P2-005 证据之上验证 V004 seed、分类/账户管理和隔离重开；不录入财务记录。

P4 的一次集成验收固定为 [P4-005](./P4-005-basic-ledger-integration.md)。它验证新增→重开→编辑→删除→恢复、精确余额、profile 隔离和只展示已实现功能。P4-005 PASS 后可称“可自用基础记账版”，但不等于已生成可分享安装包。

## 5. 旧任务

`M0-001` 至 `M0-006` 保留为历史索引，均不得继续执行。M0-001 已产生的 JavaFX 依赖由 S0-001 明确清理，其余旧任务未授权实施。
