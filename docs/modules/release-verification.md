# release-verification 模块规格

- 状态：个人分享包后续规格；P4 基础记账版完成前不构建、不安装、不生成快捷方式
- 边界：Java 11、Vue、Electron 的可重复构建，Windows 便携包或 per-user 包、隔离数据验证、启动冒烟和说明文档
- 引用：[需求](../requirements.md)、[架构 §10](../architecture.md)、[ADR-001](../decisions/ADR-001-java11-modular-monolith.md)、[ADR-003](../decisions/ADR-003-sqlite-per-profile.md)、[ADR-004](../decisions/ADR-004-vue-electron-local-rest.md)、[ADR-006](../decisions/ADR-006-electron-forge-6-4-2-audit.md)及全局 `AGENTS.md`

## 1. 职责、术语、用例与非目标

职责：把已通过 P4 的 Vue 静态产物、Electron 壳、Java 后端和 Java 11 runtime 组装为一个离线桌面部署单元，证明基础记账、单实例和进程退出可用，并同步个人分享说明。

术语：`build Node`（满足固定 Electron/Vite 版本的构建 Node）、`Java runtime image`（随产品分发的 Java 11 runtime）、`isolated data dir`（不含真实活动数据的目录）、`release package`（Electron Forge 产生的 installer/app bundle）、`golden fixture`（冻结 v3.1 行为的输入/输出）。

非目标：商业发布、应用商店、单文件 EXE、管理员安装、要求用户预装 JRE/Node、自动更新、云发布、付费签名或把开发机 mock 当分享包验证。

## 2. 输入、输出和依赖

| 项 | 内容 |
| --- | --- |
| 入口 | 已批准实现、Maven wrapper/POM、npm lockfile、Forge 配置、Vue 产物、Java runtime、fixtures、隔离样本 |
| 输出 | 固定版本安装器/app bundle、SBOM/依赖清单、验证报告、升级/回滚说明、唯一桌面快捷方式 |
| 依赖方 | 最终用户、维护者、支持/发布流程 |
| 被依赖方 | 所有模块、desktop-shell、local-api、persistence/backup、Windows 目标机 |
| 方向 | 发布只消费已定义产物；不得修改领域/数据库/API 让包“通过” |

发布/安装/快捷方式只在用户明确要求生成版本时执行。测试数据必须是合成或经授权匿名副本并位于隔离目录。

## 3. 状态和门禁

```text
PLANNED → BUILDING → PACKAGE_CREATED → ISOLATED_VALIDATION → READY_TO_SHARE
                   └────失败或缺证据────> BLOCKED
READY_TO_SHARE → SHARED | DISCARDED
```

进入 BUILDING 前必须满足：

- P4-005 PASS；需求 O1、O4、O5 已关闭；
- Java、Electron、Forge、Node、Vue、Vite、sqlite-jdbc、Jackson 精确版本及许可证已固定，lockfile 无 `latest`；
- Forge 6.4.2 的已知审计结果按 ADR-006 披露；个人分享不以商业安全审计或到期复审为门禁。
- Java/API/Vue/Electron/P4 基础记录测试已通过，未把 mock 当真实链路；
- 构建输入不包含用户无关的 dirty 改动、密钥、token、真实账本或本机私有配置。

任何一项不满足都不能把产物称为候选正式包。

## 4. 构建与部署流程

1. 使用 Maven wrapper 和 `--release 11` 构建 Java，运行领域、SQLite 和 HTTP 合同测试。
2. 使用锁定 npm 依赖构建 Vue；生产产物中不得含 source token、开发代理地址、React/TSX 或外部 CDN。
3. 将 Vue `dist` 复制到 Java 静态资源 staging 并生成/校验 manifest；重新打包 Java 后运行静态资源与 API smoke。
4. 准备经许可批准的 Java 11 runtime 和后端启动器；Electron Forge 将 Electron、后端、runtime 和资源组装为 Windows x64 package。
5. 在干净目标机/环境以隔离数据目录、断网且无系统 JRE/Node 的条件安装/解压。
6. 执行真实启动、单实例、三类记录保存/重载/编辑/删除/恢复、余额、后端退出和 UI 关键路径。
7. 通过后同步 README、CHANGELOG、安装、手工备份/恢复、已知限制和许可说明；需要桌面交付时更新现有快捷方式，不创建重复项。

回退：基础版按新账本使用。升级/替换包前完全退出应用并复制整个 `%LocalAppData%\LedgerX` 目录；失败时恢复该完整目录副本。Java 数据不反写旧 JSON。

## 5. 验收矩阵

| 风险/流程 | 必须证据 | 层次 |
| --- | --- | --- |
| 构建可重复 | clean checkout 按文档产生同版本组件；lockfile/POM/SBOM 一致 | 构建 |
| 基础记账 | 三类记录、金额、余额方向、日期、状态和 revision 有 Java 结果断言 | 单元/SQLite |
| HTTP 契约 | 认证、字段、状态、幂等、ETag、分页、错误均读同一 fixtures | Java/Vue 合同 |
| Vue UI | P3/P4 关键场景；320px、200%缩放、日期、焦点、键盘和确认 | 浏览器/视觉 |
| 桌面真实链路 | renderer 无 Node/token；单实例；Java 启停；刷新；崩溃恢复 | Electron 集成 |
| 数据生命周期 | 新建→重启→编辑→重启→删除→恢复；真实 SQLite 重开一致 | E2E/持久化 |
| 手工备份说明 | 明确完全退出、复制整个数据目录、恢复前保留当前副本 | 文档/人工 |
| 技术边界 | 既有 loopback/session/sandbox 回归，不新增金融安全验收 | Electron 集成 |
| 性能 | 20k 样本下启动 ≤5s、常用 P95 ≤500ms、写 P95 ≤300ms | 参考机 |
| 最终包 | 无系统 JRE/Node 可启动；关闭后无 Java 孤儿进程；数据隔离 | 发布包冒烟 |

每项报告环境、产物 hash、数据来源、通过数量、失败和未测原因。“构建通过”只算第一行。

## 6. 可观测与文档

分享包诊断可包含应用/API/schema、Electron/Chromium/Node/Java 和依赖版本、OS 架构、最近错误码、阶段耗时、完整性状态；不得包含 token、账本、金额、备注、用户名或完整路径。

用户文档必须明确：数据位置、手工备份/恢复、支持的 Windows、未签名提示和未实现能力。维护文档包含构建、依赖版本、进程协议和故障定位；不要求商业支持流程。

## 7. 待决与升级

- 分享前关闭需求 O1、O4、O5；O2/O3 不阻塞基础包。
- 依赖告警按 ADR-006 如实披露；只有扩大到公开商业分发时重新建立安全发布门禁。
- 无签名证书可以生成个人分享包，但必须说明 Windows 可能显示发布者警告。
- 若 package 变化，之前发布包启动/进程/资源验证失效；只重测受影响路径和关键 smoke。
