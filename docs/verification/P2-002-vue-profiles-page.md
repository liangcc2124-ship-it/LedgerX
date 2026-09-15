# P2-002 Vue 用户空间页面验证记录

- 日期：2026-09-14
- 结果：PASS（当前本机 Node 24；Node 22 不作为交付门槛）
- 环境：Windows、Java `11.0.15.1`、Node `24.19.0`、npm `11.17.0`、Vue/Vite/Playwright 现有锁定版本

## 交付范围

- Vue 用户空间页实现 profiles 列表、归档筛选与游标分页、创建、切换、二次确认归档、字段错误、认证/恢复/版本冲突和待确认操作反馈。
- `App.vue` 仅将 profile 成功事件触发的 system status 刷新改为后台刷新，保持发起操作的页面挂载；启动、手动重试和恢复状态语义不变。
- 共享 profiles 列表 fixture、浏览器合同测试和隔离 Java + Vite 真实链路脚本。
- 未修改 Java API、数据库结构、全局 API 字段或 Electron 打包配置。

## 验收矩阵

| 层次 | 命令/数量 | 结果 |
| --- | --- | --- |
| Vite 配置合同 | `npm test` 内置 Node 测试，7 项 | PASS：代理 origin/token 安全边界。 |
| API client | `npm test` 内置 Node 测试，3 项 | PASS：相对路径、结构化错误、取消/非法响应。 |
| Vue/浏览器合同 | `npm test` 内置 Playwright，28 项 | PASS：列表/创建/切换/归档、分页去重与筛选重置、401/423/428、字段错误、Unicode 长度、网络/读超时、操作 404/完成、键盘、320px 和 200% 缩放；0 failure。 |
| 前端构建 | `npm run build`（由 `npm test` 执行） | PASS：Vite production build。 |
| 真实 Java HTTP + Vite 代理 | `node tests/profiles-real-http.mjs`，1 条真实流程 | PASS：隔离临时数据根中真实 Java 11 → Vite `/api/v1` proxy → Vue 完成创建 profile → 页面重载 → 切换回旧 profile；确认 `profiles.db` 已生成且页面 active profile 一致。 |

## 真实链路证据

- 测试目录为临时 `C:\Users\liang\AppData\Local\Temp\ledgerx-p2-002-real-*`，运行结束已清理；未使用 `%LOCALAPPDATA%\\LedgerX` 或真实账本。
- Java 使用现有 `target/classes` 与 `target/cp.txt` 启动，Vite 开发代理只向 Java loopback origin 转发并注入会话认证；renderer 未设置 Authorization。最终 dist 扫描未发现开发 token、Bearer、loopback origin 或 source map 引用。
- 真实操作顺序：读取默认 ACTIVE profile → 创建唯一隔离 profile →刷新页面重新读取并确认新 profile ACTIVE → 切换回默认 profile →确认默认行显示“当前空间”。

## 未验证项与残余风险

- 为重建 Java target 尝试执行 `mvnw.cmd -q -DskipTests package` 时，Maven Wrapper 因尝试写入 `C:\.m2` 被拒绝；随后系统 Maven 因本机镜像缺少 `jackson-bom:2.21.0` 解析失败。因此本任务没有重新编译 Java；真实链路使用既有、已由 P1-002 验证的 Java 构建产物。Java 源码未被本任务修改。
- 未执行 Electron、发布包、签名或桌面快捷方式验证；按 P2-002 规格由 P2-005 负责。
- 交付以当前本机 Node 24 环境为准，不再安排 Node 22 复测。
