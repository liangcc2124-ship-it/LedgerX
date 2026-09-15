# S0-001：修正 Java 11 后端构建基线

## 1. 背景、目标、范围、非目标

旧 M0-001 已建立 Maven wrapper，但仍含 JavaFX 依赖和 `desktop/bridge` 包。单一结果：形成可重复构建的 Java 11 **无 UI 后端**基线，生产依赖只有已批准的 Jackson 2.x 和 sqlite-jdbc。

范围：根 POM、Java 包骨架、基线测试和依赖说明。非目标：HTTP 路由、SQLite schema、Vue/Electron、业务实体、发布包、升级依赖版本。

## 2. 前置与引用

- 前置：无；先于其他 S0 任务。
- 必读：[requirements §3](../requirements.md#3-已确认决定与安全假设)、[architecture §3/§4/§7](../architecture.md)、[ADR-001](../decisions/ADR-001-java11-modular-monolith.md)、[ADR-004](../decisions/ADR-004-vue-electron-local-rest.md)。
- 沿用 POM 中已验证的 Jackson/sqlite-jdbc/JUnit 版本；若与 Java 11 不兼容则停止升级，不自行换版本。

## 3. 允许修改

- `pom.xml`、Maven wrapper（只有现有 wrapper 真正不能工作时）；
- `src/main/java/com/ledgerx/{http,application,domain,persistence,backup,observability}/**` 的空骨架；
- `src/test/java/com/ledgerx/build/**`；
- 本 Task Spec 的完成证据。

不得修改 `frontend/**`、`web/**`、`native/**`、领域/API/数据库规范或用户业务代码。

## 4. 实现契约

1. 编译必须固定 `maven.compiler.release=11`；默认 test 不启动 UI、端口、数据库或写用户目录。
2. 移除 `org.openjfx:*` 与 JavaFX Maven plugin/version；生产 dependency tree 只保留 Jackson databind 及其 core/annotations、sqlite-jdbc。
3. 删除仅本任务此前创建且确定为空的 `com.ledgerx.desktop`、`com.ledgerx.bridge` 占位；不得删除用户实现。
4. 新建 `com.ledgerx.http` 空边界；application/domain 不依赖 http/persistence。
5. 保留 Maven wrapper 和 JUnit 基线；不引入 Spring、ORM、DI、日志框架、Lombok、模块化多 POM 或 Java 17 API。
6. 依赖版本不能为 range/SNAPSHOT/latest；不改现有锁定版本。

## 5. 用户/跨层/业务/持久化/边界

| 用户操作 | 前端反馈 | 跨层字段 | 业务结果 | 持久化结果 | 边界 |
| --- | --- | --- | --- | --- | --- |
| 无，本任务仅构建 | 不适用 | 无 | Java 11 后端可编译测试 | 不创建 DB/用户文件 | 在 JDK 17+ 构建仍不能引用 Java 12+ API |

## 6. 验收

- `mvnw.cmd -q test` 通过，基线测试至少 1 项且不依赖图形环境。
- `mvnw.cmd -q package` 通过。
- dependency tree 不含 JavaFX/Spring/ORM/SNAPSHOT/range；Jackson/sqlite-jdbc/JUnit 均为 POM 固定版本。
- 编译产物目标为 Java 11；POM 和源码不含 JavaFX 引用。
- 运行测试前后工作区无 SQLite、用户配置、端口监听或 Maven临时配置残留。

## 7. 测试与真实链路

运行 wrapper test/package/dependency tree。真实 Electron/HTTP/SQLite/发布包均不适用，必须在报告说明“不适用，因为本任务只有构建基线”。不得用系统全局 Maven替代 wrapper 后仍称 wrapper 已验证。

## 8. 禁止、升级、文档与报告

禁止顺手重构旧 C#/React、实现 HttpServer、改版本或创建桌面入口。若移除 JavaFX 会破坏非占位 Java 源、已批准依赖无法在 Java 11 编译、需要新增生产依赖，立即升级。

仅同步实际影响的构建说明和本文件证据。完成报告：文件、依赖差异、命令/测试数量、Java 11 证据、未测试层、残余风险。

## 9. 实际执行证据（2026-09-12）

- 修改：`pom.xml` 移除 JavaFX 依赖、属性和 Maven plugin；项目描述改为 Java 11 backend；删除空的 `com.ledgerx.desktop`/`com.ledgerx.bridge` 占位；新增空的 `com.ledgerx.http` 边界。
- `MAVEN_OPTS=-Duser.home=C:\Users\liang .\mvnw.cmd test`：1 个测试通过，0 失败/错误/跳过；测试在 Java `11.0.15.1` 运行。
- `MAVEN_OPTS=-Duser.home=C:\Users\liang .\mvnw.cmd package`：通过，生成 `target/ledgerx-desktop-0.1.0-SNAPSHOT.jar`。
- `MAVEN_OPTS=-Duser.home=C:\Users\liang .\mvnw.cmd dependency:tree -Dscope=runtime`：仅包含 Jackson databind/core/annotations 与 sqlite-jdbc；无 JavaFX、Spring、ORM 或快照依赖。
- `javap -verbose target/test-classes/com/ledgerx/build/Java11BaselineTest.class`：class major version `55`。
- 真实 HTTP、Electron、SQLite schema、迁移、发布包和快捷方式不适用；本任务只建立构建基线。仓库已有的旧版 QA 数据库文件未被本任务创建或修改。
