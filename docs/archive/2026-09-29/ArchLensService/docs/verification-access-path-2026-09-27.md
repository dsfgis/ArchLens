# 静态访问路径子集验证（2026-09-27）

本次实现 `CodeDatabaseAssociation` v2 与 `CodeAccessPathAnalyzer`。分析仅使用已授权的显式文件及已采集的数据库对象，不执行被分析代码、XML 外部实体或业务 SQL。报告按步骤记录入口注解、方法或 MyBatis 语句、数据访问语法、SQL 引用和数据库对象的来源哈希、行号与证据 ID；直接字面量实参、同方法共现及未定位容器分别标注候选状态。

## 本次实际检查

- `mvn -q -f ArchLensService/pom.xml verify`：152 项测试，134 通过、0 失败、0 错误、18 跳过。新增正例覆盖 C# 路由和方法、Java 直接 SQL 实参、MyBatis XML 语句及过程对象；负例覆盖代码注释、跨方法误关联、取消与时间预算。跳过项包含依赖外部环境的集成测试，不能解释为实库通过。
- `npm --prefix ArchLensClient test`：16 项通过。`npm --prefix ArchLensClient run check` 与 `git diff --check` 通过。
- 本机浏览器使用 `ArchLensClient/tests/ui-fixture.mjs` 和临时合成 Agent 报告检查报告页：可见“静态访问路径”、步骤顺序、`SAME_METHOD_CANDIDATE` 与 `SQL_TO_INVOCATION_NOT_PROVEN`/`RUNTIME_EXECUTION_UNVERIFIED`。该夹具只模拟存储返回，不代表 PostgreSQL、模型或业务数据库联调。临时服务已停止。

## 未验证范围

Oracle、人大金仓、达梦实库仍未接入本次环境；尚无真实项目上的跨方法调用、变量值流、Mapper 接口绑定、复杂 ORM 或动态 SQL 验证。静态路径不能据此声称运行时依赖成立，也不能用于宣布迁移兼容或已完成改造。
