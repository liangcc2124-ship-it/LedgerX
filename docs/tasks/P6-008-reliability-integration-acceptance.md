# P6-008：交互可靠性与公式闭环集成验收

- 状态：历史跨层验收清单；不是个人本机使用门槛
- 单一结果：在隔离数据目录中证明公式、记录、弹窗、布局和页面状态经过真实浏览器、Java、SQLite 和完整重开后满足 P6 契约，并把 P5-008 从已知正确性缺陷阻塞中释放。

## 1. 背景、范围与非目标

本任务只做跨层验收和必要测试 harness 修正，不新增产品行为。它保留 P6 的历史验收场景，后续只在相关功能改动或实际使用出现问题时重用。非目标：性能采样、安装包发布、真实用户数据迁移、移动端适配或顺手修复未建 Task 的缺陷。

## 2. 前置与允许文件

- 前置：P7-004/P7-002 本机链路可用；旧 Electron 证据只作历史参考。
- 必读：P6 技术设计、ADR-013、P5-008、P7-003 和当前验证记录。
- 允许：普通浏览器/Playwright 与 Java integration harness、匿名 seed/helper、P6 verification report、任务索引；发现产品缺陷时只记录并新建明确 bug task。
- 禁止：真实 `%LocalAppData%\LedgerX`、开发代理冒充正式会话、修改产品规则以让测试通过。

## 3. 验收矩阵

| 用户操作 | 前端结果 | 跨层数据 | 业务结果 | 持久化结果 | 边界 |
| --- | --- | --- | --- | --- | --- |
| 创建嵌套分类占比公式 | 中文节点、preview、save | 规范 AST、同 key mutation | 值/status 正确 | version/dependency 可重开 | 零分母、改名、归档 |
| 编辑旧错误运算公式 | 正确回显，不空选 | legacy reader→规范 DTO | Dashboard 恢复计算 | 旧 version 不改，新 version 规范 | 无法识别结构进入诊断 |
| 键盘操作全部 dialog | 焦点圈/Escape/恢复 | 零额外请求 | 不误提交 | 无无关写入 | busy、长表单、375px |
| 浏览 51 条记录/版本 | 加载更多且不重复 | cursor 原样回传 | 筛选不串数据 | 零额外 mutation | 慢响应、append 失败 |
| 保存/取消/reset 布局 | 文案与结果一致 | PUT/POST、ETag/key | 完整无重叠卡片 | 重开坐标一致 | 1024、200% zoom、冲突 |
| 切换 profile/hash | 正确页面和空间 | activeProfile/contextEpoch | 无跨空间展示 | 各 DB 隔离 | 迟到响应、无 capability |

## 4. 执行要求与退出标准

1. 每次创建独立匿名数据目录，记录 Java pid，结束确认端口关闭且无遗留写者。
2. 浏览器矩阵：1440×900、1024×768、200% zoom；375×667 只做 dialog 可达性。
3. 完整停止并重开 Java 后重读 metric detail/history/dashboard/layout/records，不能只刷新网页。
4. 观察实际 request method/path/body/If-Match/Idempotency-Key/X-Request-Id、response ETag/dataRevision 和 SQLite 重读。
5. P6 场景全部通过后，将 P5-008 更新为可执行；本任务不重复其 20,000 records/50 metrics/100 items 性能采样。
6. 必跑并记录实际数量：Maven test/package、frontend npm test/build、普通浏览器→Java→SQLite integration。已有未受影响测试可复用，但 harness 或源码改变后重跑受影响层。
7. 更新 requirements、architecture、API、module、task index、verification、README/CHANGELOG 中受影响状态；移除“PARTIAL”只能基于对应证据。

## 5. 禁止事项、升级与完成报告

禁止把 mock 当真实链路、把无 console error 当业务通过、修改用户数据、忽略失败样本或通过缩小数据集满足性能。任一 P0 round-trip、跨 profile、幂等、布局重开失败时整项 FAIL，并回到对应 Task。

完成报告分开列出：环境、数据隔离、功能场景数量、各测试层数量、真实浏览器链路、文档同步、未测项；明确性能仍由 P5-008 执行，不声明发布运行包已经生成。

## 6. 本轮执行状态

P6-001 至 P6-007 的前端任务已有历史验证记录。P7-004 已提供本机浏览器会话及同源启动路径；当前本机记账主流程见 P7-002/P7-003 的隔离验证。Vite fixture 或旧 Electron 结果不能充当真实跨层证据。
