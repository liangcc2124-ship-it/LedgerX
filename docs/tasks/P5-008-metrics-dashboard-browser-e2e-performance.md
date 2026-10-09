# P5-008：指标与总览真实浏览器验收、性能基线

- 状态：可选的容量与性能调查规格；当前个人使用不要求执行
- 单一结果：当真实数据规模或卡顿表明有必要时，在隔离数据目录中记录容量和性能事实；跨层功能变化仍按对应功能任务做真实浏览器验收。

## 1. 背景、范围与非目标

这是 P5 唯一跨层验收任务，默认不改产品行为。它验证前序任务协作而不是以 mock 或浏览器 fixture 冒充真实 Java/SQLite。范围：可复现隔离测试数据、正式本机浏览器场景、网络/API 观察、性能采样、验证报告与必要测试 harness 修复。非目标：新增功能、扩 scope、调整公式/口径阈值或发布运行包。

## 2. 前置与允许文件

- 启动条件：只有实际数据增长或卡顿需要定位时才执行；若涉及公式/布局跨层变化，再先验证对应功能路径。使用真实 Java 11 和同源浏览器会话。
- 必读：P5 全部 Task、ADR-011/012/014、P4-005 验证记录、P7-004 会话约束。
- 允许：现有浏览器/Java integration harness 和仅为可观察性必需的测试 helper、P5 verification report、本 Task。若发现产品 bug，停止验收并升级明确 bug task。
- 禁止：真实用户数据目录、migration/API/UI/依赖的功能变更、用 Vite 代理或 mock 替代正式链路、把 synthetic benchmark 写入用户账本。

## 3. 真实链路契约

1. 每次场景创建新的隔离数据根和匿名合成 seed；记录 Java pid、HTTP base URL、profile id、schema history 和启动/退出状态。结束核对服务端口已释放。
2. 通过 UI 建立/选择 P4 分类账户和三类记录，进入财务总览；分别断言日、周、月、年 period label/period range、服务端数据和卡片状态。编辑、删除、恢复记录后刷新或切换期间，指标只按指定 occurred/settlement 口径变化。
3. 通过指标管理创建至少一个含中文 token 的 custom formula，validate/preview/save 后在 dashboard 启用；完整停止 Java 并重开，检查 metric、formula version、value/status 和 ETag 重新读取一致。
4. 编辑 dashboard，真实拖动/缩放至少两卡并以键盘改动一项；保存、退出、完整重开后检查共享 12 列 layout 坐标/尺寸和卡片内容。另验证取消不写、reset 复原、hidden 不显示数值。
5. 观察实际 `/api/v1/dashboard`、metrics/formulas/layout 请求：会话/CSRF/If-Match/idempotency、响应 dataRevision/ETag 与 SQLite 重读一致。不得从页面或 test mock 注入结果。
6. 性能样本：在独立数据库生成 20,000 ACTIVE record、最多 50 enabled metrics、100 layout items；预热后固定 profile/period 请求至少 30 次，记录每次端到端 dashboard duration、P50/P95/max、机器/Node/Java/DB 条件、query count 或日志指标。P95 ≤750ms 才能标 PASS；否则报告 FAIL/证据并升级，不靠删卡或缩小数据集掩盖。

## 4. 验收与测试

| 维度 | 必须证据 |
| --- | --- |
| 生命周期 | 首开、迁移、停止服务、完整重开、无遗留写者 |
| 数据闭环 | record mutation → REST/dataRevision → dashboard → SQLite reread |
| 自定义公式 | 中文 token、validate/preview、version、重开、错误/循环负例 |
| 布局 | mouse + keyboard、save/cancel/reset、四粒度共享、长内容不裁切 |
| 隔离/安全 | profile 隔离、hidden no-value、真实浏览器会话、CSRF/Host/Origin |
| 性能 | 完整样本、原始分布/条件、P95 判断 |

- 必跑：前序 Java/前端已有通过证据可复用；本任务新增执行的是真实浏览器 E2E 和 performance harness，报告实际次数/通过数。若 harness 源码改变，只重跑受影响 Java/前端层。
- 不适用：发布运行包；只有用户要求分享版本时才进入发布 SOP。

## 5. 禁止事项、升级与完成报告

禁止把 browser mocked API、unit test 或单次快照称为真实 E2E/性能证据；禁止写真实用户数据、运行破坏性清理或关闭安全隔离。真实链路失败、P95 超阈、SQLite/REST/UI 数据不一致、布局/公式重开丢失时立即停止，附最小复现与日志脱敏摘要升级。

完成报告必须分开写明：环境、隔离目录策略、每个 E2E 场景、网络/SQLite 证据、性能原始样本摘要/P50/P95/max、通过或失败、未测项；不得声明发布包或移动端已验收。
