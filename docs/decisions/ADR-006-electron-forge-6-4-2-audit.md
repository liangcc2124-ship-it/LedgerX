# ADR-006：Electron Forge 6.4.2 个人使用风险接受

- 状态：已接受（个人自用和小范围非商业分享；不设基础功能开发到期门禁）
- 日期：2026-09-13
- 关联：[ADR-004](./ADR-004-vue-electron-local-rest.md)、[S0-004](../tasks/S0-004-electron-shell.md)

## 背景

S0-004 原先固定 `@electron-forge/cli@7.11.2`，并要求安装后 `npm audit` 不得存在未缓解的 high/critical 漏洞。官方 npm registry 审计对该版本报告 19 个漏洞（18 high、1 critical），并给出 Forge `6.4.2` 作为修复候选。用户随后明确批准改用 Forge `6.4.2`，因此已更新 `electron/package.json` 与精确 lockfile。

## 事实核查

在官方 npm registry 上对精确锁定的 Forge `6.4.2` 重新执行 `npm audit --json --registry=https://registry.npmjs.org`（2026-09-13，退出码 1），结果仍为 **18 high、1 critical**。问题主要来自 Forge 打包链的传递依赖（包括 `tar`、`extract-zip`、`electron-packager` 等）；审计元数据给出的可用修复回到 Forge `7.11.2`，属于 major 变更。当前没有证据表明仅更新 lockfile 或 `npm audit fix --force` 能在不改变固定版本约束的前提下消除风险。

按用户选择的“维护版本评估”路径，进一步在隔离目录重建 Forge `7.11.2` + Electron `44.3.0` + Playwright `1.63.0` lockfile 并执行同一官方审计：结果为 **3 low、19 high、1 critical（23 total）**，退出码 1，反而比 6.4.2 的审计总量更高。官方 registry 元数据显示 Forge `7.11.2` 为当前 stable/latest（MIT，Node `>=16.4.0`，2026-05-20 发布）；Forge `6.4.2` 最后发布于 2023-09-07（Node `>=14.17.5`）。Electron `44.3.0` 为当前 latest，要求 Node `>=22.12.0`；官方 release schedule 将 Electron 44 的 EOL 标为 2027-03-02。Node 构建基线 `22.18.0` 满足两者引擎要求。

## 决策与风险接受范围

1. 接受用户对 Forge `6.4.2` 的版本批准并将其记录为当前基线。
2. 用户明确接受以下已知构建链风险，允许继续开发、测试和个人分享，但不代表 `npm audit` 通过：
   - 风险接受仅覆盖 `electron/package.json` 的 `devDependencies` 及其安装/打包链传递依赖；当前 `dependencies` 为空，Forge 不会进入最终运行时依赖。
   - 受影响包包括 Forge 6.4.2 组件、`electron-packager`、`extract-zip`、`@electron/rebuild`、`cacache`、`make-fetch-happen`、`tar` 等；当前审计为 18 high、1 critical。
   - 关键公告包括 `tar` critical `GHSA-23hp-3jrh-7fpw`、`tar` high `GHSA-r292-9mhp-454m`，以及 `extract-zip` high `GHSA-jmr9-qjv8-65gv` / `GHSA-7pqw-9j4j-h8q3`；完整公告以每次 `npm audit --json` 输出为准。S0-004 不处理不受信任的归档输入，也不在产品运行时调用这些打包工具。
3. 风险接受前提：
   - 构建与测试使用隔离、非生产目录和最低权限账户，不接触真实账本、生产密钥或用户数据；测试数据必须为合成/授权匿名副本。
   - 依赖安装使用精确 `package-lock.json`、官方 HTTPS npm registry 和 `npm ci`；禁止 `npm audit fix --force`、未审查的 overrides、临时 registry 或动态版本。
   - 不执行来自网络或用户输入的任意 Forge maker/publisher；S0 不配置 maker/publisher/auto-updater。所有归档/资源输入在进入 Java 静态根前由受控 manifest 校验。
   - 构建机使用非管理员账户、主机防护/恶意软件扫描和出网控制；Forge 工具链不随最终运行时暴露给 renderer 或用户可写目录。
   - 每次依赖变更、发布候选和复审日期重新运行 `npm audit` 与许可证检查；漏洞数量上升、影响扩展到运行时或出现可利用的构建链事件时立即停止并升级。
4. 本决定仅适用于个人自用和小范围非商业分享。无需责任人、工单或到期复审；分享说明须如实写明未签名和构建工具存在已知审计告警。
5. 禁止执行 `npm audit fix --force`、擅自添加 overrides、降级其他依赖或改写审计结果。这些动作会改变公共构建输入，需单独安全评估和用户/高级模型授权。
6. 不采用 Forge `7.11.2` 作为自动修复：它虽是维护中的 stable，但本次隔离审计同样失败且漏洞总量更高。当前不改变产品锁定版本。
7. S0/P3/P4 和个人分享包可继续使用该精确版本，不把审计告警作为基础功能门禁。若产品范围变为公开商业发布、处理不受信任归档或构建链风险扩展到运行时，再重新评估版本。

## 候选路径与当前选择

| 方案 | 做法 | 主要取舍 |
| --- | --- | --- |
| A：个人范围风险接受（当前选择） | 保留 Forge 6.4.2；限制在本项目受控构建与个人分享，披露审计结果 | 可继续当前实现，但不能称为“审计通过” |
| B：维护版本评估（已完成） | 评估官方维护中的其他 Forge/Electron 组合；Forge 7.11.2 候选审计仍失败，未采用 | 不改变当前版本，后续仍可重新评估 |
| C：职责拆分（未选择） | S0-004 不引入 Forge，仅完成安全壳开发；把 Forge 作为独立 release task 的受控工具并在发布前解决审计 | 可降低开发链暴露，但需要调整任务边界 |

当前选择允许继续开发、测试和生成个人分享包；Java/SQLite 工作不受此决定影响。只有上述适用范围发生变化或出现直接影响运行时的证据时，才重新建立门禁。

## 官方资料

- [Electron Application Packaging](https://www.electronjs.org/docs/latest/tutorial/application-distribution)：Electron 官方推荐 Forge 作为打包工具。
- [Electron Release Schedule](https://releases.electronjs.org/schedule)：Electron 44 的稳定/EOL 与 Node 组合信息。
- [Electron Forge releases](https://github.com/electron/forge/releases)：Forge 7.11.2 stable 与 Forge 8 alpha 发布状态。
- [npm registry: @electron-forge/cli](https://www.npmjs.com/package/@electron-forge/cli)：精确版本、引擎和许可证元数据。

## 后果

- `electron/package.json` 和 `electron/package-lock.json` 已反映用户批准的 `6.4.2`；这不代表 `npm audit` 通过。
- 当前可以开发、测试并在用户要求时生成个人分享包；不要求为基础版购买签名证书或完成商业发布审计。
- 依赖实际变更后仍应运行项目既有安装/构建测试；未来扩大分发范围时再重新执行完整审计并更新本 ADR。
