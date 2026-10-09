# P9-002：可回切恢复编排与安全 UI

- 状态：BLOCKED（等待 P9-001）
- 类型：恢复生命周期、Java 服务重启与高风险网页交互
- 依赖：备份 reader/verify 已稳定
- 影响：Java bootstrap/backup、持久化恢复作业、Vue 预览确认、恢复页和浏览器 E2E

## 用户结果

用户选择已管理的备份后先看到预览，再明确确认。系统在不直接覆盖活动数据库的前提下 staging、创建保护快照、切换、重启验证；任何失败都能回到原账本或进入可操作恢复页。

## 状态机

```text
SELECTED → VERIFIED → STAGED → PROTECTION_CREATED → RESTART_REQUIRED
→ SWAPPED → REOPEN_VERIFIED → COMPLETED
                   └─失败→ ROLLED_BACK | RECOVERY_REQUIRED
```

状态只能由 Java 持久化的 restore operation 推进；Vue 不根据窗口刷新猜测结果。

## 编排设计

1. `POST /backups/{id}/restore-previews` 完整验证备份并返回 profile、创建时间、记录/账户/指标数量、schema/format compatibility 和警告；preview 有短期 opaque ID。
2. `POST /recoveries` 接受 previewId、expectedProfileId 和固定确认短语，创建 protection snapshot 和 staging，完成所有可在运行时执行的校验后返回 `RESTART_REQUIRED`。
3. Java 持久化 `RESTART_REQUIRED` 和 recovery operation ID；网页明确提示用户停止当前本机服务并按固定启动命令重启。浏览器不得提交路径、凭据或任意系统命令；Java 重启时只按已持久化的 operation 继续恢复。
4. Java 在打开活动 ledger 前应用 pending recovery：同卷原子切换 → 打开/迁移 → integrity/foreign key/领域检查。失败立即用 protection snapshot 回切并复检。
5. 成功后保留保护快照到至少下一次成功备份或明确保留期；失败不得自动删除唯一可恢复副本。
6. 恢复期间禁止普通 mutation，system/status 返回明确恢复阶段；窗口刷新不能触发第二次 restore。

## UI 与可访问性

- 三步显示：选择备份、预览影响、确认恢复。破坏性按钮与取消清楚分离。
- 明确提示恢复会替换当前空间、会自动保存保护快照、必须重启本机 Java 服务；关闭标签页不等于服务退出。
- 错误提供验证失败原因、返回备份列表、打开脱敏日志和重试验证；不能提供“仍然强制恢复”。
- 全程键盘可操作，焦点圈、Escape 规则和重启后的状态反馈符合 AppDialog 规范。

## 验收

- 正常恢复后记录、余额、公式、布局和 revision 与 manifest/黄金数据一致。
- 当前数据在恢复前可通过 protection snapshot 恢复；模拟 swap 后重开失败会自动回切。
- 应用/Java 在 VERIFIED、STAGED、SWAPPED 各阶段被终止后，重开得到确定结果，不出现两份活动库。
- 坏包、跨 profile、未知高 schema、空间不足、只读目录、过期 preview、重复确认均不改变活动账本。
- 普通浏览器、真实 Java、真实 SQLite 下完成 E2E；不使用测试专用路径绕过生产编排。

## 禁止事项

禁止 renderer 自行解压或覆盖文件；禁止在数据库连接未关闭时替换活动库；禁止失败后创建空账本掩盖原数据；禁止删除 protection snapshot 来让测试通过。
