# bridge-contract（已废止）

- 状态：历史占位文件
- 日期：2026-09-12
- 替代：[local-api-contract.md](./local-api-contract.md)

原 JavaFX WebView `postMessage`/JavaScript 回调方案已被 Vue/Electron + loopback REST 替代。该协议不得继续实现、扩展或用于验收。

迁移规则：

- 旧 `command` 名称映射为 REST resource/path，但字段业务语义仍以模块与具体 API 文档为准。
- 旧 `protocolVersion/requestId/ok` envelope 不再作为 wire 格式；版本进入 URL/响应头，请求关联进入 `X-Request-Id`，幂等进入 `Idempotency-Key`。
- 已写的 bridge fixture/task 仅是历史设计证据，不得作为新实现契约。
- 所有新引用必须指向 [全局 API](../api.md) 和 [local-api-contract](./local-api-contract.md)。

