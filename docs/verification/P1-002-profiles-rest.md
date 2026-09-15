# P1-002 Profiles catalog REST 验证记录

- 日期：2026-09-14
- 结果：PASS
- 环境：Windows 11 x64、Java `11.0.15.1`、Maven Wrapper `3.9.11`、Node `24.19.0`（本任务未调用 Node）

## 交付范围

- Java application 层以单一 `ActiveProfileContext` 和排他写门持有唯一活动空间；`GET /api/v1/system/status` 与 profiles mutation 返回同一个 active profile/data revision。
- Java loopback HTTP 提供 `/api/v1/profiles` 的 list/create、`/profiles/{id}/activate`、`/profiles/{id}` archive，以及既有 `/operations/{key}` 的 catalog-specific 查询投影；HTTP 合同测试实际读取 `docs/contracts/api-v1/profiles/` 的 create/operation fixture。
- catalog operation 在同一 SQLite transaction 写入结果、ETag、Location、profile/catalog revision 和活动指针；同 key 真实回放，跨当前 ledger operation key 冲突。
- 所有测试使用 JUnit `@TempDir` 下的 catalog/ledger；未接触 `%LocalAppData%` 的真实用户数据。

## 已完成验证

| 层次 | 命令/数量 | 结果 |
| --- | --- | --- |
| 领域 + SQLite 重开 | `ProfileApplicationServiceTest`，3 项 | PASS：创建/trim、切换/no-op、归档/replay、重开、catalog/ledger key 冲突、缺账本、目录逃逸和 recovery read-only。 |
| 真实 Java loopback HTTP | `ProfilesHttpServerTest`，1 项 | PASS：认证/Origin 在 service 前拒绝；create/replay/operation、cursor 过期、缺 If-Match、归档 active、status 同步、非法 name。 |
| 受影响既有 HTTP/persistence | `LedgerHttpServerTest` 6 项 + `PersistenceBootstrapTest` 7 项 | PASS：S0 status/来源/请求体/静态资源和 bootstrap/migration 回归。 |
| 完整 Java 回归 + package | `mvnw.cmd -q -Dmaven.compiler.fork=true test package`，23 项 | PASS：profile service 3、baseline 1、既有 HTTP 6、profiles HTTP 1、migration 5、persistence 7；0 failure/error/skipped。JAR 已重新生成。 |

## 不适用与残余风险

- 本期没有 Vue/Electron 页面、真实桌面壳、发布 EXE、签名或快捷方式交付；因此未声称这些链路已验证。
- catalog 与文件系统不能组成一个 ACID transaction。实现先验证/创建目标 ledger、最后提交 catalog pointer；catalog 提交失败时旧 active 不变，非空新 ledger 不递归删除，留给恢复检查。
- Java 11 在当前 Windows/JDK 组合有时会在 Maven 进程退出 0 后输出 ZipFS/JAR close `AccessDeniedException` 诊断。最终 `test package` 的最近一次运行出现该诊断，但 Maven exit code 为 0，JAR 已更新，且 Surefire XML 明确为 23/23；这是当前工具链残余风险，不应被误报为干净的编译输出。
