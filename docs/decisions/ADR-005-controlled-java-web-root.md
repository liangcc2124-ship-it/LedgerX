# ADR-005：Java 受控静态 Web 根目录

- 状态：已接受（S0-004 基座）
- 日期：2026-09-13

## 背景

S0-003 生成的 Vue `frontend/dist` 必须在 Electron 成功链路中与 Java REST 使用同一 loopback origin。原 Java HTTP 适配器只读取 classpath 中固定的旧 Web manifest，Electron 无法安全地把 Vue 资源作为同源页面加载。

## 决策

1. Java 启动时可读取父进程提供的 `LEDGERX_WEB_ROOT`，仅用于受控静态资源根目录；未设置时继续使用 classpath manifest，保持现有开发/测试兼容。
2. 目录必须是现存的非符号链接目录；manifest 只接受根 `index.html` 和 `assets/` 下的普通文件，路径不得包含 `..`、反斜杠或链接逃逸，MIME 类型使用既有白名单。
3. Electron 只把打包资源目录传入该变量；测试可传入构建后的 `frontend/dist`。renderer 不可设置或修改该值。
4. `LEDGERX_PARENT_PID` 用于 Java 的父进程存活监测；Electron 通过 stdin 发送固定 `LEDGERX_STOP` 请求优雅停止，超时后只终止本次 ChildProcess 句柄。

## 取舍

- 受控目录避免把构建产物复制/耦合到 Java classpath，同时保持 Java 同源 REST 和现有静态路径防护。
- 不接受任意 URL、renderer 参数或动态文件路径；扩展静态资源类型需单独安全评估。
- 父进程监测和 stdin 控制不替代 Electron 的句柄清理；两者都失效时必须在集成验收中升级评估 Job Object/受控控制管道。
