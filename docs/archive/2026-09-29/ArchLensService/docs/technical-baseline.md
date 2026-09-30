# 当前技术基线

文档同步日期：2026-09-18。固定版本来自当前 pom.xml 与源码，环境值来自有日期的验证记录。其余设计技术栈尚未集成，不把“计划采用”记为“兼容通过”。

| 组件 | 固定版本 / 本机验证环境 | 用途 |
| --- | --- | --- |
| Java | release 21；本机 JDK 21.0.11 | 核心和 CLI |
| Maven | 3.9.x；本机 3.9.14 | 构建 |
| Jackson databind | 2.18.3 | 严格 JSON 与 canonical 编码入口 |
| JavaParser core | 3.27.1 | Java 接口声明及有限重构/升级 AST |
| JSqlParser | 5.3 | SQL AST |
| JUnit Jupiter | 5.12.2 | 自动化验证 |
| pgJDBC | 42.7.13 | ArchLens 自身 PG 存储；BSD-2-Clause |
| Neo4j Java Driver | 5.28.9 | ArchLens 图投影；Apache-2.0（父 POM） |
| JDK HttpClient | 随 JDK 21 | DeepSeek 描述网关与原生 tool_calls；未新增模型 SDK |
| 调查引擎 / 规则包 | archlens-investigation-0.3 / archlens-rules-1.0 | 22 条版本限定规则 |
| Agent 引擎 / 契约 | archlens-agent-0.1 / archlens.agent.v1 | 六工具编排及澄清/报告 |

JavaParser 的固定坐标来自 [Maven Central 3.27.1](https://central.sonatype.com/artifact/com.github.javaparser/javaparser-core/3.27.1)。JSqlParser 的固定发行包为 [Maven Central 5.3](https://repo1.maven.org/maven2/com/github/jsqlparser/jsqlparser/5.3/)；[官方迁移说明](https://jsqlparser.github.io/JSqlParser/migration50.html) 说明 5.x 的 Java 基线和 Visitor API 变化。测试采用 [JUnit 5.12.2](https://docs.junit.org/5.12.2/user-guide/junit-user-guide-5.12.2.pdf)。这里不宣称这些是最新版本，也未将本次功能测试等同于漏洞审计。

直接依赖及构建插件固定在 `pom.xml`，不使用浮动版本。运行时依赖的实际坐标、SHA256 和 POM 许可证声明见 `runtime-dependencies.json`；这是构建追踪信息，不代替正式发布前的许可证审查。2026-09-17 增加数据库驱动以支持 CLI 自身存储，尚无 Web 运行时；依据和运行边界见 [调查存储说明](investigation-storage.md)。

2026-09-18 健康检查记录 PostgreSQL 16.15、Neo4j/2026.08.1；这两项是当次服务环境，不是锁定部署镜像或持续健康承诺。Agent 默认模型由源码配置为 deepseek-flash，可用后端 DEEPSEEK_MODEL 修改；具体可用性看 [当次验证](verification-agent-2026-09-18.md)。

待完成：Spring Boot/Vue 生产运行栈、数据库部署 digest、Node 生产版本、依赖审计及用户项目有效 classpath/运行配置矩阵。已有规则版本范围见 [场景矩阵](scenario-rules.md)，不等于用户项目版本已被采集验证。
