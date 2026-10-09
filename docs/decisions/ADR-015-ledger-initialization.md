# ADR-015：显式账本初始化与旧数据审查状态

- 状态：已接受
- 日期：2026-09-23
- 相关任务：[P7-002](../tasks/P7-002-ledger-initialization-account-opening.md)

## 背景

V004 给固定系统账户填入 migration 执行机器的本地日期。它是一次性兼容 seed，却会被误当作用户真实开户日；导致历史补录依赖迁移/测试机器的当前日期。已有用户数据不能通过重写日期或金额来“修复”，而只有 `PENDING|COMPLETED` 两态也无法表达需要人工确认的旧账户。

## 决定

1. 新 schema V006 在 `ledger_setting` 保存 `setup_state`、`ledger_start_on` 与完成 UTC instant。状态取值 `PENDING|REVIEW_REQUIRED|COMPLETED`。V004 保持不变，只作为旧 schema 的兼容 seed；新 profile 的 V006 状态是 PENDING。
2. DDL、旧数据分类、setup backfill 和 `schema_history` 写入在同一个 SQLite transaction；Java migration hook 用注入 `Clock`，SQLite 的 `date('now')` 不参与业务日期。quick/foreign-key/领域预检失败时整次迁移回滚。
3. 结构有效、引用完整且日期关系正确的旧账本，以全部可观察业务日期的最早值自动完成。候选包含 ACTIVE/TRASHED 记录的发生日和结算日，以及全部账户启用日；唯一排除项是完全未改动、未被引用的 V004 默认账户安装日。
4. 空但有用户编辑痕迹的账本，以及可由用户明确调整开户日修正的旧冲突进入 REVIEW_REQUIRED。只读预览展示需要确认的旧事实；单个初始化事务只应用用户明确提交的默认账户名称/开户日/期初余额和受影响账户开户日，不重写记录。Review 表单预填旧账户事实，避免无意改动。
5. 坏日期、孤儿引用、未知结算模式、SQLite/FK 损坏不属于用户初始化；migration 必须回滚并沿用 RECOVERY_REQUIRED，而不是猜测或抹平数据。
6. `ledger_start_on` 是完成后的历史记录下界：记录发生日、结算日及所有账户启用日不得早于它；完成后本任务不提供修改起始日的接口。需要调整时必须另行按历史数据不变量设计。
7. 当前业务日期使用 `Asia/Shanghai` 加注入 `Clock`；UTC instant 仍按 UTC 存储。这样同一个 Clock instant 不因 SQL 或操作系统本地时区漂移。
8. PENDING/REVIEW_REQUIRED 时 Vue 隐藏业务导航，Java application 和 HTTP 两层都拒绝普通业务读写；仅 status、profile 管理、初始化预览/提交、operation 查询保留。
9. 账户日期后移校验包含 ACTIVE 与 TRASHED 结算记录，确保回收站记录仍能恢复。

## 后果与取舍

- 多出一个可解释的 review 状态和迁移预检，但避免把“新账本”“用户已配置的空账本”和“坏账本”混在一个状态中，也避免静默重写账户或丢失回收站可恢复性。
- 用户能在初始化时明确修正开户日冲突；默认账户余额仍是分单位整数，期初事实和 setup 状态原子保存。
- V004 的历史执行日仍留在未修改的老行，但只有原始未引用 seed 会被忽略，不能污染新账本起始日。
- 账本起始日完成后不可编辑；这牺牲任意回溯扩展，换取所有指标、账户余额和记录查询口径一致。若产品未来支持扩展起始日，必须先重验整个日期下界及可用历史。
- 应用仍是小型单体，不需要新框架、ORM、服务或后台迁移进程。

## 被拒绝的方案

- 改写 V004 或用固定过去日期：破坏迁移 checksum，并把未经用户确认的日期当成业务事实。
- 自动把所有账户开户日调早、改动历史记录或金额：改变已保存财务事实。
- 只用 PENDING/COMPLETED 并把所有特殊旧库视作可普通初始化：无法安全表示明确确认和结构损坏的区别。
- 只由 Vue 隐藏页面：可由 HTTP 直接绕过 setup 流程。
- 只约束 ACTIVE 记录：用户可能删除记录后后移开户日，再无法恢复 TRASHED 记录。
