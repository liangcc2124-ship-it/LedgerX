# ADR-007：Electron renderer 沙箱保持开启与本机启动限制

- 状态：已接受（实现约束；工具沙箱限制已通过受控主机执行规避）
- 日期：2026-09-13
- 范围：S0-004 Electron 桌面壳

## 背景

S0-004 要求 BrowserWindow 使用 `nodeIntegration:false`、`contextIsolation:true`、`sandbox:true`。在 Codex 工具沙箱内，Electron 44.3.0 的默认沙箱 renderer 无法创建 Windows restricted token，`render-process-gone` 报告 `reason=launch-failed`、`exitCode=49`；这不是 Java readiness 或 REST 认证失败。在受控主机权限下使用同一默认启动参数，真实 Electron→Java→Vue 链路已通过。使用命令行 `--no-sandbox` 后也可观察到链路，但会改变安全前提，不能作为生产或本任务安全验收证据。

## 决策

1. 保持生产代码 `sandbox:true`，不增加 `--no-sandbox`、`sandbox:false` 或其他关闭 renderer 沙箱的路径。
2. 在 app ready 前禁用硬件加速并启用进程内 GPU 回退，降低无 GPU/缺少 GPU 组件主机的启动失败概率；这不改变 renderer 沙箱配置。
3. 默认 `npm run test:e2e` 必须在支持 Electron 沙箱的目标 Windows/CI 环境运行；在嵌套工具沙箱中运行时，应使用受控主机执行权限，而不是修改应用参数。不得放宽断言或把 `--no-sandbox` 结果标成通过。
4. 诊断时可以在隔离临时数据目录使用 `--no-sandbox` 确认业务链路是否独立于 Java/前端问题；该结果仅作诊断记录，不计入 S0-004 验收。

## 后果

- 当前工作区的 Node 单元/静态安全测试及受控主机真实 Electron renderer/Java/REST/刷新链路均可验证；嵌套工具沙箱中的失败仅作为环境限制记录。
- 发布前仍必须在目标环境重新构建/启动并执行真实原生链路；若目标环境仍失败，应升级评估 Electron 版本、Windows 安全策略或签名/打包环境，不得通过关闭沙箱绕过。
- 仅禁用硬件加速可能降低图形性能；若后续测得 UI 性能不足，应单独评估硬件加速策略并复核安全与兼容性。

## 参考

- [Electron Process Sandboxing](https://www.electronjs.org/docs/latest/tutorial/sandbox)
- [Electron app.disableHardwareAcceleration](https://www.electronjs.org/docs/latest/api/app#appdisablehardwareacceleration)
- [Electron app.commandLine](https://www.electronjs.org/docs/latest/api/app#appcommandline-readonly)
- [Electron RenderProcessGoneDetails](https://www.electronjs.org/docs/latest/api/structures/render-process-gone-details)
