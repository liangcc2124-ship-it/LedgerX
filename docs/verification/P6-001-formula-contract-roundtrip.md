# P6-001 公式契约往返验证

## 范围

验证自定义指标公式在 HTTP 输入、服务层、SQLite 版本记录、重开读取和 Dashboard 派生计算之间使用同一 AST 语义。验证同时覆盖服务端派生 tokens/dependencies、外部 `SUBTRACT` 映射、旧版子节点 wrapper 读取以及请求携带 `formula.tokens` 的拒绝。

## 实现摘要

- `FormulaHttpMapper` 负责外部 AST 与 `FormulaNode` 的映射，公开 `SUBTRACT/MULTIPLY/DIVIDE`，不泄露内部 `SUB/MUL/DIV`。
- `FormulaStorageMapper` 负责 SQLite canonical JSON；写入时子节点为直接节点，读取时兼容历史 `{schemaVersion,root}` 子节点 wrapper 和旧别名。
- `CanonicalFormulaTokens` 从已验证 AST 确定性生成 tokens；前端只提交 `formula.ast`。
- 校验器的函数边界固定为 MIN/MAX/AVG 2–32、SAFE_DIVIDE 2、ROUND 1–2。

## 实际验证

1. Java 11 全量源码编译：`javac --release 11`，通过。
2. 公式 domain、指标 application、metrics repository/schema 测试：13/13 通过。
3. HTTP metrics 测试：1/1 通过，覆盖 201 创建、canonical response、派生 token、携带 tokens 返回 400 `formula.tokens`。
4. 前端 `npm run test:config`：7/7 通过；`npm run build` 通过；Playwright 针对公式请求契约的用例 `metrics page shows Chinese formula hints and sends a closed custom metric body`：1/1 通过。
5. 真实 loopback HTTP + 隔离临时 SQLite：创建 `SUBTRACT(12.5,2.5)` 返回 201，Dashboard 返回 `10.0 / READY`；停止并重新启动 Java 后，详情仍为 `SUBTRACT`、2 个直接子节点，Dashboard 仍为 `10.0 / READY`。

## 未覆盖与后续

本任务未实现递归公式编辑器、统一弹窗状态机、记录分页或 GridStack 布局；这些分别由 P6-002 至 P6-006 处理。Maven wrapper 因当前主机的全局镜像指向不可写缓存，使用项目缓存依赖完成 Java 11 编译和定向 JUnit 执行；未宣称 Maven package 发布包验证通过。
