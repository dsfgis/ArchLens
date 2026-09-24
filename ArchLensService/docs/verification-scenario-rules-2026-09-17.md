# 2026-09-17 多场景规则验证

本记录对应 `archlens-investigation-0.3` / `archlens-rules-1.0` 最终打包产物。此前存储切片记录保留原日期和测试数量；本记录不替代完整 INV 验收。

## 实际执行

在 `ArchLensService` 目录使用本机 JDK21.0.11：

```powershell
.\scripts\storage.ps1 -Command storage-verify -JdkHome $env:ARCHLENS_JAVA_HOME
.\scripts\scenario-smoke.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME
.\scripts\smoke.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME
```

最终 Maven verify 完成于 **2026-09-17 16:59:04 +08:00**，**87 tests，86 通过、0 failures、0 errors、1 skipped**。唯一跳过项为主机不允许创建符号链接；不是场景规则或数据库失败。新增 `ScenarioRulesTest` 25 项全部通过；`StorageIntegrationTest` 8 项和 `Neo4jProjectionTest` 1 项真实外部测试全部通过。

证据：[测试摘要](verification/scenario-rules-20260917-final/tests.json)。摘要只保留测试名称、计数及合成运行 ID/hash，没有复制可能含环境变量的原始 Surefire XML。

## 验证范围

- MySQL 原生类型/属性/标识符/空值函数；Oracle 类型、DATE、空字符串、NVL、序列；注释/字符串/限定自定义函数反例；过程体、可执行注释、未知 SQL 模式及错误语法保留缺口。
- C# decimal/无符号关键字、await/序列化候选；转义标识符、普通/逐字字符串及注释反例；预处理、原始/插值字符串明确未覆盖。
- Java 重构方法删除、返回类型/可见性/static 改变、实现和字段状态变化；注释排版不误报行为变化；包移动未知，继承/Object 方法/非原始类型签名保守处理；未列入 files 的计划路径不触发读取。
- Boot 仅匹配迁移的 EE 包，javax.xml 等 JDK 包不误报；Maven 直接声明、属性未解析、parent 版本冲突撤销；DTD/外部实体拒绝；JDK JAXB 和 HttpClient 并存条件。
- 不支持/缺失版本、原文 UTF-8/CRLF/中文/emoji/tab 定位、来源哈希与证据引用、指纹稳定及变化、漂移撤销、取消、发现数量限额、历史 v1 Finding 读取。
- PostgreSQL16 只读探针核实空字符串不为 NULL、integer 上下界、timestamp 时间部分及 COALESCE；`pg_catalog.ifnull/nvl` 返回函数不存在 SQLSTATE 42883，`pg_catalog.number/varchar2` 返回类型不存在 SQLSTATE 42704。仅使用常量表达式，无样例 DDL 或业务表操作。

## 打包 CLI 与封存

最终 JAR SHA-256：`2d96088ef9dc75d3e2373ad2ada566838dc5a5ea1eb7c1e22473d2c048955cde`。

[七场景 CLI 摘要及报告哈希](verification/scenario-rules-20260917-final/summary.json) 与 [22 条规则注册表](verification/scenario-rules-20260917-final/rules.json)均由打包 CLI 生成：

| 场景 | 规则发现数（不含总体 UNKNOWN） | 报告 |
| --- | ---: | --- |
| MySQL → PG | 6 | [报告](verification/scenario-rules-20260917-final/mysql-postgresql.json) |
| Oracle → PG | 7 | [报告](verification/scenario-rules-20260917-final/oracle-postgresql.json) |
| C# → Java | 4 | [报告](verification/scenario-rules-20260917-final/csharp-java.json) |
| Java 重构 | 4 | [报告](verification/scenario-rules-20260917-final/java-refactor.json) |
| Spring Boot | 3 | [报告](verification/scenario-rules-20260917-final/spring-boot.json) |
| JDK | 1 | [报告](verification/scenario-rules-20260917-final/jdk-upgrade.json) |
| HttpClient | 1 | [报告](verification/scenario-rules-20260917-final/httpclient-upgrade.json) |

七类报告分别写入 ArchLens 自身 PG schema，回读对象及 canonical hash 一致，状态 PARTIAL；没有生成新场景依赖图，投影为 NOT_APPLICABLE。运行 ID 和封存哈希保存在测试摘要。MySQL 报告另经打包 CLI `run-export` 导出：[封存报告](verification/scenario-rules-20260917-final/stored-mysql-report.json)，runId `14232a3b-aa58-488f-bb03-e802f35bdd30`；CLI 回报 latestRevision=true、state=PARTIAL。

旧列场景最终打包烟测同时通过：3 nodes / 2 edges / 3 diagnostics，14 项第三方声明检查通过；原始结果见 [旧 CLI 烟测记录](final-smoke.json)。没有修改依赖版本或新增依赖。

## 未验证和未覆盖

没有连接真实 MySQL/Oracle，没有编译/运行 C#/被分析 Java 项目；不能将官方版本依据加合成样例等同于用户生产项目迁移验收。新规则不覆盖完整业务数据/事务/调用/字段链或跨语言行为等价。前端仍未接入调查工作台，完整自主 Agent、在线元数据采集和新场景风险模型尚未完成。全部报告保留 PARTIAL 与覆盖缺口。
