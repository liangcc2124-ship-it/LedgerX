# P10-001：指标模板与引导式公式构建

- 状态：BLOCKED（等待 P7/P8；P5-004..007 剩余合同关闭）
- 类型：分析产品体验、领域元数据与 Vue 交互
- 依赖：基础记账、检索和真实浏览器门禁稳定
- 影响：metrics/formulas 规格与 API、模板元数据、Vue MetricsPage/FormulaNodeEditor；不改变 AST 为唯一写入事实的 ADR

## 用户结果

普通用户无需看到 `AST`、`formula.ast.root` 或节点路径，即可通过模板创建“储蓄率、某分类支出占比、月均支出、现金可支撑月数”等常用指标；高级用户仍可进入结构化编辑器。

## 长期设计

1. Java 维护版本化模板目录，模板包含稳定 templateId、中文名称、用途、输入槽位、输出格式、公式 AST 工厂和适用范围。模板是构造器，不是第二套公式运行时；保存后仍生成并验证同一规范 AST。
2. `GET /metric-templates` 返回槽位定义和说明，不返回可执行代码。`POST /metric-templates/{id}/preview` 与普通公式 preview 走同一校验器、PeriodResolver 和 evaluator。
3. 模板引用只保存稳定 metric/category/account ID；显示名变化不破坏结果。模板版本只影响新创建草稿，不静默重写用户已有公式。
4. UI 默认进入模板选择→填写业务参数→预览→保存；“高级编辑”才显示递归节点，并把技术路径映射为“公式、分子、分母、第 N 项”等业务标签。
5. 服务端 fieldErrors 仍使用稳定 AST path，前端 presentation map 负责翻译和聚焦；不得改变错误契约来隐藏问题。
6. 系统指标显示设置、自定义指标定义、版本历史和归档继续使用现有 API/事务/幂等规则。

## 首批模板

- 储蓄率：`SAFE_DIVIDE(income - total-expense, income)`；收入为零时 NOT_COMPUTABLE。
- 分类支出占比：用户选择活动支出分类，除以 total-expense。
- 月均支出：选择近 N 个完整自然月，N 为有界整数；期间规则由 Java 定义。
- 现金可支撑月数：available-cash 除以近 N 月平均支出，零支出时 NOT_COMPUTABLE。

每个模板必须在模块规格中给出精确期间、空数据、负值和零分母语义后才能实现。

## 验收

- 四个模板分别完成创建→预览→保存→重开→编辑→版本浏览→Dashboard 计算，AST 和结果一致。
- 分类/账户改名后指标仍计算；归档引用给出可解释状态，不静默换引用。
- 默认流程不出现 AST/path/英文枚举；高级模式保留完整无损 round-trip。
- 键盘、焦点、长中文、错误定位和弹窗滚动通过；前端不执行财务公式。
- 模板目录版本升级不会修改现有指标；黄金测试覆盖旧 templateVersion。

## 禁止事项

禁止引入自由文本 `eval`、SQL 片段或浏览器计算；禁止为每个模板增加独立数据库表/endpoint；禁止用大模型生成未经服务端验证的公式事实。
