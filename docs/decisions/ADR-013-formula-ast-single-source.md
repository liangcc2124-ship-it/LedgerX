# ADR-013：公式 AST 是唯一写入事实

- 状态：PROPOSED
- 日期：2026-09-22

## 背景

当前公式写入同时提交 AST 与 tokens。前端可以生成与 AST 不一致或不完整的 tokens，Java 只做浅层 token 校验后原样持久化；同时 HTTP DTO、领域枚举和 SQLite JSON 共用了同一套临时序列化逻辑，导致 `SUBTRACT`/`SUB` 等名称和递归 child 结构漂移。真实链路已经出现“创建返回 201，但总览不能计算、再次编辑不能回显”的结果。

LedgerX 是本地单体应用，没有多语言客户端、公开 SDK 或混合版本服务端，不需要维持两个可写公式表示。

## 决定

1. 公式写入只接受规范 AST。tokens 与 dependencies 由 Java 从已验证 AST 派生，作为只读展示数据返回。
2. HTTP 外部枚举固定为 `CONSTANT`、`REF`、`ADD`、`SUBTRACT`、`MULTIPLY`、`DIVIDE`、`NEGATE`、`MIN`、`MAX`、`AVG`、`ROUND`、`ABS`、`CLAMP`、`SAFE_DIVIDE`。
3. AST 的 `schemaVersion` 只位于 AST wrapper；每个 child 都是直接 FormulaNode，不再重复 wrapper。
4. Java 领域层可以继续使用 `SUB/MUL/DIV` 等内部枚举，但必须通过显式 HTTP mapper 转换，不能把 `Enum.name()` 当公共契约。
5. SQLite 继续保留现有 `ast_json`、`tokens_json` 列，不新增 migration。新版本使用独立 storage mapper 写入根 wrapper + 直接 child，并保留内部 kind，以兼容回滚读取。
6. 新 reader 兼容当前错误历史数据：接受被 `{schemaVersion,root}` 额外包裹的 child 和 `SUB/MUL/DIV`。读取后在内存中规范化；不就地改写历史 version。
7. validate、preview、create、update、detail、history 和 dashboard 使用同一个 AST validator、token generator 与 DTO mapper。

## 备选方案

### 保留 AST 与 tokens 双写并做一致性比较

可维持旧文档，但仍要求两端实现同一序列化算法，增加错误面。对于结构化编辑器没有实际收益，因此不采用。

### 只保存自由文本公式

会引入解析歧义、注入面和名称改动问题，也违背稳定 ID 引用决定，因此不采用。

### 数据库迁移全部历史 JSON

可以得到统一存量数据，但迁移风险高且没有必要。兼容 reader 足以恢复旧公式，后续更新会自然写入规范新版本，因此不采用。

## 后果

- 正面：只有一个权威写入表示；循环、引用、arity 和 token 展示均由服务端统一；前端不再需要猜测历史 tokens。
- 成本：必须新增 HTTP/storage 两个小 mapper 和历史兼容读取测试；现有 v1 请求 fixture 需要同步。
- 兼容：同一安装包内前后端必须同时更新。旧后端回滚仍可读取新 storage 结构，但不保证理解未来 AST schemaVersion。
- 回退：若实施中发现现有数据存在无法归一化的第三种结构，停止写入，保留原 JSON 并进入恢复诊断，不静默改成常数或 0。

