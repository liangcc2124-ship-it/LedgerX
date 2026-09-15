# P3-005 分类与账户真实桌面集成验证记录

状态：PARTIAL。基础新建与重开路径通过；完整归档/merge、分页和冲突恢复仍未完成证据，因此不能将本任务或 P3 里程碑标记为 PASS。

## 验证命令与结果

- `electron`：`ELECTRON_DISABLE_GPU=1 ELECTRON_DISABLE_SANDBOX=1 node node_modules/@playwright/test/cli.js test tests/p3-005-catalog.integration.spec.mjs --reporter=line --timeout=60000`：1/1 通过。
- 测试使用临时隔离数据根和临时 Electron userData；通过真实 Electron 可见 UI 创建自定义分类“桌面验收分类”和账户“桌面验收账户”，关闭后使用同一隔离根重启，重新读取两行，并确认 `profiles.db` 存在。
- 这是真实 Electron→Vue→受认证 REST→Java→SQLite 路径；没有用 mock 或直接 HTTP 构造业务结果。

## 未覆盖与限制

- 没有覆盖系统分类编辑限制以外的完整 merge、归档冲突、分页、ETag/operation 待确认、profile 切换和全量数据库只读核验。
- 运行器默认环境 renderer 会 crash；本次使用 GPU/沙箱禁用环境完成可重复冒烟，未改 Electron 产品安全配置。正式受控主机仍需按任务要求使用默认 sandbox 重验。
- 未生成发布包、未验证发布包启动、未更新桌面快捷方式。
