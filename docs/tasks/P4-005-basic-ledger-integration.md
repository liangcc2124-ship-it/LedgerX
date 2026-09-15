# P4-005：基础记账版真实桌面集成验收

- 状态：PARTIAL（基础记录新建/回收站/恢复/重开已验证；全量场景待补）
- 单一结果：在隔离数据根中通过真实 Electron→Vue→REST→Java→SQLite 完成分类/账户准备、三类记录、余额、编辑、回收站、恢复和完整重开，并确认界面只展示已实现能力。

## 1. 背景、目标、范围与非目标

P3/P4 各层测试通过后，本任务证明软件对真实用户已经形成基础记账闭环。默认只增加/调整验收和验证文档；发现产品缺陷按 BUG 流程处理。

范围：真实桌面 E2E、隔离数据、用户点击、网络契约观察、SQLite 只读核验、完整退出/重开、20,000 条后端性能抽查证据复用、基本备份说明可见性和 P4 总状态同步。

非目标：在验收任务中修产品、生成安装包/签名、真实复制用户目录、旧数据导入、资产/指标/报表、金融安全审计、Node 22 复测。

## 2. 前置依赖与引用规范

- 前置：S0/P1/P2、P3-001..005、P4-001..004 均 PASS，且无未处理公共契约冲突。
- 必读：全局 AGENTS；[需求 §6–§8](../requirements.md)；[架构 §5–§10](../architecture.md)；[全局 API](../api.md)；[具体 API](../api/ledger-records-api.md)；[模块规格](../modules/ledger-records.md)；ADR-010；P3-005；P4-001..004。
- 真实操作必须经可见 Vue UI；直接 HTTP/SQL 只可在操作后补充验证，不能构造业务结果。

## 3. 允许/预计修改的模块和文件

- 新增 `electron/tests/p4-005-basic-ledger.integration.spec.mjs` 及本 spec 专用 helper。
- `docs/verification/P4-005-basic-ledger.md`、本任务、`docs/tasks/README.md`、README/CHANGELOG 中基础版状态。

禁止修改 `src/main`、`frontend/src`、electron 产品代码、migration/API/database/module/ADR、package manifests、旧 WPF/React、真实 LocalAppData、发布目录或桌面快捷方式。发现缺陷必须停止对应场景并建立 BUG Task Spec。

## 4. 实施步骤与真实链路

1. 创建唯一隔离数据根，验证它不等于工作区、用户 home、真实 `%LocalAppData%\LedgerX` 或任何已有数据目录。使用当前本机 Node 环境与默认 Electron sandbox；不强求 Node 22。
2. 启动真实 Electron，等待 READY，从 UI 完成 P3 seed/分类/账户最小冒烟：看到 seed；创建 BANK“日常卡”openingOn=2026-01-01/openingBalance=1000.00；创建 CREDIT“信用卡”openingBalance=0.00；准备可用于三类记录的分类。
3. 在“收支记录”页仅通过 UI 新建：BANK 收入 100.25、BANK 固定支出 20.10、BANK 弹性支出 30.05；断言余额 1050.10。再在 CREDIT 新建弹性支出 40.00，断言负债余额 40.00。
4. 编辑 BANK 固定支出为 25.10，断言记录 revision/界面更新且 BANK 余额 1045.10。编辑请求必须包含完整 Draft 和原 ETag。
5. 取消一次删除确认，断言无 DELETE。确认删除 BANK 弹性支出，断言它只在回收站，BANK 余额变为 1075.15；从回收站恢复后余额回到 1045.10。
6. 验证错误路径：金额 0/三位小数前端阻止或服务端 fieldError；选择与类型不匹配的系统分类不可提交；通过测试注入 stale ETag 时页面显示冲突且已保存记录不被覆盖。不得放宽断言为“出现任意错误”。
7. 完全退出 Electron，确认本次 Java child PID 已结束。用同一隔离根重启，验证记录字段、账户余额、回收站最终状态和 revision 保持；切换到新 profile 时旧记录不可见，切回后恢复。
8. 只读检查隔离 SQLite：三类 record 行数/amount_minor/type/category/account/date/deleted_at/revision 正确；ledger_meta data_revision 与成功 mutation 次数一致；processed_operation 没有重复；所有高级关联表 0 行。SQL 只用于核验。
9. 打开设置说明，验证未显示通知/自动备份/安全缓冲/高级财务入口，手工备份四条关键说明可见；openDataFolder 只做按钮可达性，不执行复制或恢复。
10. 运行 Java、frontend、electron 既有全套适用测试与本 spec。断网运行期间不应出现公网业务请求；只报告已有技术边界回归，不新增金融安全验收。
11. 测试结束后先确认全部本次进程退出，再安全清理经过路径校验的隔离根。

## 5. 用户操作、前端反馈、跨层与持久化结果

| 用户操作 | 前端反馈 | 跨层数据 | 业务结果 | 持久化结果 | 边界 |
| --- | --- | --- | --- | --- | --- |
| 新增三类记录 | 成功提示、列表/余额刷新 | 闭合 Draft、key | 四种余额方向正确 | record/operation/dataRevision | 金额/分类非法 |
| 编辑 | 完整表单与新 revision | PUT/If-Match/key | 余额按新金额重算 | 同一 id/createdAt 保留 | stale ETag |
| 删除/恢复 | 回收站与余额变化 | DELETE/restore、ETag/key | 余额先取消后恢复 | deletedAt 写/清 | 取消确认 |
| 完整重开/切空间 | 原状态重读、无串数据 | 新会话/active profile | 状态一致 | 同一 SQLite 文件可重开 | child 退出、隔离 |
| 查看设置 | 仅基础范围/手工备份说明 | 既有可选 bridge | 不执行备份 | 无写入 | bridge 不可用 |

## 6. 验收标准

- 全部主流程由真实可见 UI 操作，经过 Electron、受认证 REST、Java 和 sqlite-jdbc；mock/直接 HTTP/SQL 不得替代。
- 100.25、20.10、30.05、40.00、编辑 25.10 的列表和余额精确达到步骤 3–5 数值，不能只断言非空。
- 删除取消不发请求；trash/restore 的 ACTIVE/TRASHED 列表、deletedAt 和余额一致，完整重启后仍正确。
- 金额非法、分类不匹配、stale ETag 有精确用户反馈和零错误覆盖；profile 切换无数据串线。
- 数据库核验的 record、operation、revision/dataRevision 与高级表 0 行成立。
- 设置页无未实现入口且备份说明准确；不实际操作真实用户数据目录。
- Java、Vue、Electron 和新增 E2E 均通过并报告实际命令/数量；任何缺证据项不得标记 PASS。

## 7. 测试层次、命令与真实原生要求

- Java：仓库根 `./mvnw.cmd -q '-Dmaven.compiler.fork=true' test package`。
- Vue：`frontend` 目录当前本机 Node 执行 `npm test`。
- Electron：`electron` 目录当前本机 Node 执行 `npm test`、`npm run test:integration`，再执行新增 P4 spec 的项目既有 Playwright 调用方式。
- 真实原生：强制 Electron→Vue→REST→Java→SQLite、隔离根、完整退出重开和 profile 切换。
- 发布包、真实用户数据副本和桌面快捷方式：不适用；分享包另行建立 P5 任务。

## 8. 禁止事项、升级、文档与完成报告

禁止修改产品代码、使用真实数据根、`--no-sandbox`、批量杀 Java、以 HTTP/SQL 构造结果、实现高级功能、做金融安全审计或把本验收称为安装包验收。

发现字段/余额/状态/幂等/重开不一致、P3/P4 产品缺陷、schema/API 变化、进程遗留、数据串 profile 或测试环境无法运行真实 Electron 时，停止相关验收并形成 BUG Task Spec；不要在测试中绕过。

完成报告写：PASS/FAIL；修改文件；每层命令与数量；真实 UI/HTTP/Java/SQLite 重开证据；数值和数据库核验；隔离根/进程清理；设置说明；发布包/快捷方式不适用；未测项、限制和残余风险。
