# LedgerX WPF 视觉系统

## 设计基准

- 参考图：`docs/design/ledgerx-warm-copper-reference.png`
- 平台：Windows 原生 WPF，不使用 WebView 或浏览器内核
- 布局：230px 左侧导航、开放式内容区、两级指标卡、最近记录表格
- 风格：克制、安静、温和但不偏黄；铜色只承担交互与重点状态

## 默认暖铜主题

| 令牌 | 色值 | 用途 |
| --- | --- | --- |
| `--lx-background` | `#F7F6F3` | 页面背景 |
| `--lx-surface` | `#FFFDFC` | 卡片和表格 |
| `--lx-sidebar` | `#F1F0ED` | 侧栏 |
| `--lx-primary` | `#9A6A3A` | 主按钮、选中态、重点指标 |
| `--lx-text` | `#1D1D1F` | 主文字 |
| `--lx-muted` | `#76736F` | 次要文字 |
| `--lx-border` | `#DEDBD5` | 边框和分隔线 |
| `--lx-income` | `#A06F3D` | 流入和正向变化 |
| `--lx-cost` | `#B84F45` | 成本和现金流出 |
| `--lx-warning` | `#7C572E` | 风险提示 |

## 组件规则

- 默认字体使用 Segoe UI Variable 与 Microsoft YaHei UI 回退。
- 卡片圆角 12px，按钮圆角 9px，边框 1px。
- 阴影只用于卡片层级，不做发光、渐变或金属质感。
- 金额使用等宽数字特性，主要金额 28px，次级指标 22px。
- 所有金额均支持单项隐藏和全局隐藏。
- 图标采用 Segoe Fluent Icons，统一线性图标风格。

## CSS 主题边界

CSS 文件仅支持 `:root` 中的 `--lx-*` 变量。选择器、布局规则、脚本、远程字体和远程图片不会执行。变量由主题服务解析后映射到 WPF `DynamicResource`。
