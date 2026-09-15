# P2-003 Vue 设置页验证记录

- 日期：2026-09-14
- 任务：[P2-003](../tasks/P2-003-vue-settings-page.md)
- 结果：PASS（当前本机 Node 24.19.0 / npm 11.17.0；Node 22 不作为交付门槛）

## 交付范围

已交付 Vue 设置页及其跨层回归验证：读取 active profile 设置、只提交可写字段的差异 PATCH、使用响应 ETag 更新基准、处理校验/认证/恢复/并发冲突/超时与操作查询，并在成功后重新 GET 验证服务端值。金额始终按字符串处理；只读字段、CUSTOM 主题和自动备份/通知/CSS 文件副作用均未提交或执行。

未修改 Java、SQLite DDL、全局 API 规范、具体 settings API、共享请求客户端、依赖清单或 Electron。

## 验证结果

| 层次 | 命令/路径 | 结果 | 覆盖 |
| --- | --- | --- | --- |
| Vite 配置 | `npm run test:config`（`frontend/`） | 7/7 PASS | 精确 loopback `/api/v1` 代理、token 不进入 renderer |
| API client | `node --test tests/api-client.test.mjs`（`frontend/`） | 3/3 PASS | ETag、幂等键、错误/超时映射 |
| 浏览器 mock | `npm test`（`frontend/`） | Playwright 36/36 PASS | 字段映射、差异 PATCH、no-op、CUSTOM、400/401/423/428/409、503、操作 404/COMPLETED、窄屏/键盘 |
| 构建 | `npm run build`（由 `npm test` 执行） | PASS | 生产资源生成成功 |
| 真实原生 HTTP | `node tests/settings-real-http.mjs`（`frontend/`） | 1/1 PASS | 隔离数据根；Java 11 + Vite proxy：GET 默认值 → PATCH 两字段 → 页面重载 GET；保存值与重读值一致，SQLite 文件生成 |

真实 HTTP 运行使用临时隔离目录（运行结束已清理），Java 使用本任务开始前已验证的 `target/classes` 与 `target/cp.txt`。请求证据确认 PATCH body 仅含 `notificationsEnabled`、`hideAllAmounts`，`If-Match: "0"`、新的 UUID `Idempotency-Key` 均存在，renderer 请求没有 `Authorization`。

## 产物与限制

- 构建产物扫描未发现 `LEDGERX_DEV_API_TOKEN`、Bearer token、loopback API 地址或 `sourceMappingURL`。
- Maven 重建未作为本任务门槛：本机 Maven wrapper 受 `C:\.m2` 权限限制，系统 Maven 受现有镜像无法解析 `jackson-bom:2.21.0` 影响；本任务未修改 Java，故未用失败重建结果替代真实 HTTP 验证。
- Electron、正式发布包、签名和桌面快捷方式由 P2-005 负责，本任务不适用。
- 未运行 Node 22 复测；按当前交付决定，Node 24 本机结果为有效证据。
