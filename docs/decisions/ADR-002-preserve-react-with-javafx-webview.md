# ADR-002：保留 React UI，候选 JavaFX WebView 桌面壳

- 状态：已废止，由 [ADR-004](./ADR-004-vue-electron-local-rest.md) 替代
- 日期：2026-09-12

## 背景

> 历史说明：本 ADR 记录 2026-09-12 较早阶段的 JavaFX/React 方案。用户随后明确要求 Vue、HTML/CSS/JavaScript、前后端分离并允许 Electron，因此以下方案不再允许下发实现。

当前可见界面已完整迁移到 React + TypeScript，并在 WebView2 中运行。全量重写为 JavaFX 控件不会直接改善财务正确性，却会显著扩大工期和视觉/交互回归。

当前仓库使用的是 .NET WebView2 包；迁移到纯 Java 桌面壳后需要重新选择嵌入式 Web 组件。OpenJFX 是 OpenJDK 旗下项目，JavaFX 17 官方说明可运行于 JDK 11+；其 `WebEngine` API 支持通过 `JSObject.setMember` 把 Java 对象暴露给 JavaScript。

JavaFX WebView 使用的 WebKit 与当前 Edge WebView2 并不相同，当前 Vite 产物、CSS、Pointer Events、日期控件和无障碍不能只凭 API 存在推定兼容。用户已确认本次不迁移 PDF 报表导出。

## 决策

1. 保留现有 React + TypeScript + Vite 作为唯一可见 UI。
2. 以 JavaFX 17 WebView 作为严格 Java 11 运行时下的首选桌面壳候选。
3. JavaScript 只获得一个 `postMessage(String)` bridge；Java 回调固定页面函数。所有业务调用使用 `api.md` 的版本化 JSON envelope。
4. 页面只从安装资源加载，禁用任意外部导航和调试入口；bridge 对象由 Java 持有强引用。
5. 在移植业务前完成可丢弃的兼容性 spike。P0 包括：启动/资源、日期输入、弹窗焦点、拖拽/缩放、响应式/200% 缩放、文件选择、错误回调和无障碍基本路径；不验证打印/PDF。
6. spike 失败时暂停全面实现并记录新的桌面壳 ADR；不通过浏览器 mock 宣称成功。

## 备选方案

- **全量 JavaFX 原生控件**：最终只用 Java 技术，但重写全部页面、图表和设计系统，当前没有收益证据。
- **保留 WPF/WebView2 壳，Java 只做业务进程**：可降低 UI 风险，但形成双运行时、IPC 和两个发布单元；仅可作为短期迁移工具，不是目标架构。
- **本地 HTTP + 系统浏览器**：最兼容现代 Web，但失去一体化桌面体验并扩大本机攻击面。
- **嵌入 Chromium/JCEF 或商业浏览器组件**：兼容性更强，但包体、更新、安全和许可成本明显更高；只有 JavaFX spike 失败且 UI 必须保留时评估。

## 后果

- 好处：复用现有 UI 和 Playwright 资产，重构重点留给业务正确性和存储。
- 成本：必须维护 JavaFX/WebKit 兼容构建目标和真实宿主测试。
- 风险：JavaFX 17 的安全维护和浏览器能力可能早于 Java 11 运行时成为瓶颈。
- 回退/变更路径：bridge、application 和 domain 不依赖 JavaFX；可替换桌面壳而不迁移数据库或业务规则。

## 废止处理

- 不再执行 JavaFX P0、classpath WebView 资源或进程内 bridge 任务。
- 已建立的 Java 11 Maven 基线中 JavaFX 依赖属于待清理技术债，由新 Task Spec 单独移除。
- 本文保留为决策历史，不代表当前架构。

## 依据

- [OpenJFX 17 release notes](https://github.com/openjdk/jfx/blob/master/doc-files/release-notes-17.md)
- [JavaFX 17 WebEngine API](https://openjfx.io/javadoc/17/javafx.web/javafx/scene/web/WebEngine.html)
