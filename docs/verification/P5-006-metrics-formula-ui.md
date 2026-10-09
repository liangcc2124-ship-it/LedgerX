# P5-006 Vue 指标与公式管理验证记录

状态：PARTIAL。

已实现并验证：指标管理入口按 `metrics.read` 显示；真实相对 API 请求读取指标；中文函数/运算提示（含最小值、最大值、平均值、四舍五入、限制范围和安全除法）；结构化常数/指标引用/运算构建器；创建请求带 custom UUID v4、闭合 formula AST 和 Idempotency-Key。自定义指标可读取详情、完整替换、归档；系统指标只可更新 hidden/dashboardEnabled；自定义指标可读取不可变公式版本列表。

浏览器合同测试：`frontend/tests/dashboard-metrics.spec.js` 的指标场景通过，含中文提示可见性和 POST body/header 断言。

未完成：复杂嵌套 AST 目前会保留并可不改动地保存，但尚无可视化逐节点编辑器；请求级防抖/取消与完整异常状态测试仍待补齐。
