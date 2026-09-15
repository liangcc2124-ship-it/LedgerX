# ADR-001：Java 11 模块化单体

- 状态：已接受；运行/传输部分由 ADR-004 修订
- 日期：2026-09-12

## 背景

LedgerX 当前是 Windows 单机、单用户、离线个人财务应用。用户要求用 Java 11 重构，随后指定 Vue/Electron 前端分离。系统没有独立扩缩容、跨团队所有权或网络隔离需求；业务规则和数据迁移仍应集中在一个 Java 后端单体中。

Java 11 是 2018 年发布的 LTS。Oracle 的 2026 支持路线图列出商业扩展支持至 2032 年 1 月，但 Oracle JDK 11 的生产/商业许可有条件；同时新一代框架逐步要求 Java 17 或更高，例如 Spring Boot 3.4 的最低版本是 Java 17。

## 决策

1. Java 源码和生产运行时以 Java 11 为基线，编译使用 `--release 11`。
2. Java 后端保持单进程、单写者、单 Maven 模块的模块化单体；整个产品仍是一个部署单元，但 Electron main/renderer 与 Java 分属受控本地进程。
3. Java 后端使用 JDK 自带 `HttpServer` 暴露受认证的 loopback REST API；仍不使用 Spring Boot、微服务、消息系统、ORM或依赖注入框架。
4. 代码按 `http`、`application`、`domain`、`persistence`、`backup`、`observability` 分包；领域层只依赖 Java 11 标准库。
5. 生产运行时发行版、补丁渠道和再分发许可证在实现开始前确认并固定，不能默认把 Oracle JDK 打进公开发布包。

## 备选方案

- **Java 17/21/25 LTS**：生态和安全维护窗口更好，但用户已确认不满足当前硬性 Java 11 约束；只有用户主动改变该约束时重新评估。
- **Spring Boot 本地服务**：可快速建立 HTTP API，但 Java 11 只能使用旧框架线，且有限本机路由不需要完整容器。
- **多 Maven 模块/微服务**：可以强化编译边界，但对 1–3 人、单进程项目增加构建和协调成本；出现独立发布或所有权边界时再拆。

## 后果

- 好处：Java 业务仍集中在一个进程和一个事务边界；领域规则容易测试；符合 Java 11 约束。
- 成本：增加本机端口、认证和子进程生命周期；必须自行维护少量 HTTP 适配、用例编排和 SQL 映射。
- 风险：Java 11 生态会继续收缩。Java 11 或关键后端依赖无法获得安全更新时，应升级 LTS，而不是长期停在无人维护的旧依赖。
- 回退/变更路径：领域和应用包保持 Java 标准库边界后，可提高 JDK 版本而不改变业务 API；这不是不可逆的数据决定。

## 依据

- [Oracle Java SE Support Roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html)
- [Spring Boot 3.4 System Requirements](https://docs.spring.io/spring-boot/3.4/system-requirements.html)
