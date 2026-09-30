# 多场景兼容规则与只读调查

2026-09-22 更新。引擎 `archlens-investigation-0.4`，规则包 `archlens-rules-1.1`，共 **25 条注册规则**。这是已经接入 CLI、来源证据和 PG 报告封存的有限静态调查能力；并非数据库迁移器、C# 转 Java 编译器或完整自主调查 Agent。

2026-09-18 编排接入：模型可通过 [Agent 工具循环](agent-orchestration.md) 解析目标、选择这里的适用规则、执行调查并解释匿名证据。规则目录由本地程序提供，模型不能新建规则或改写 outcome；分批规则合并后重新分析，未选规则记录 RULES_NOT_SELECTED。原 `investigate` 默认检查全部适用规则，不依赖模型。Oracle→金仓没有专用规则，不能套用 PG 规则。

## 支持矩阵

未收到项目具体版本时，先实现下列明确范围；这不是对用户项目版本的推测。请求必须提供实际版本。产品名不区分大小写，版本只接受相应数字前缀及数字补丁号；Oracle 另接受 `19c`。缺失版本、版本区间、预发行版本和矩阵外组合返回 `UNKNOWN`，不套用最近版本规则。

| 场景 | 源 → 目标 | 当前实际检查 | 明确未覆盖 |
| --- | --- | --- | --- |
| MySQL → PostgreSQL | MySQL 8.0.x → PostgreSQL 16.x | CREATE TABLE 数值属性、反引号、SELECT IFNULL、裸有符号 INT 值域 | 在线元数据、真实数据、排序规则、事务、驱动和访问调用链 |
| Oracle → PostgreSQL | Oracle 19c/19.x → PostgreSQL 16.x | NUMBER/VARCHAR2、DATE、空字符串、SELECT NVL、sequence.NEXTVAL | 包/过程/触发器体、完整 PL/SQL、同义词、事务及真实数据 |
| C# → Java | C# 12.x → Java 21.x | decimal、uint/ulong 关键字；await、JsonPropertyName 候选；项目配置、包及项目引用声明 | Roslyn/符号绑定、.NET 框架迁移、LINQ、完整异步/异常/序列化行为等价 |
| Java 重构 | Java 21.x → Java 21.x | 显式前后文件对：原始类型方法契约、方法体/字段/初始化块差异、同文件调用候选 | 继承/泛型/嵌套类、构造器、模块图、外部消费者、反射和行为等价 |
| Spring Boot 升级 | 2.7.x → 3.0.x | Java EE 导入、Maven Java 基线属性、直接 Servlet 依赖、声明版本冲突 | effective POM、profiles、传递依赖、配置属性全集及逐 API 调用 |
| JDK 升级 | Java 8.x → 17.x/21.x | JAXB 导入及目标 JDK 不再内置 JAXB 的条件风险 | 全部移除 API、类路径/模块图和运行行为 |
| HttpClient 升级 | `org.apache.httpcomponents:httpclient` 4.5.x → `org.apache.httpcomponents.client5:httpclient5` 5.2.x | 旧 org.apache.http 导入和替换/并存条件 | 逐 API 参数变化、运行时配置、传递依赖及网络行为 |

请求中的版本是用户声明，不是在线探测结果，报告保留 `DECLARED_PROFILES_UNVERIFIED`。Spring Boot 请求版本与直接 Maven parent 的已知版本冲突时，撤销本次全部规则发现并报告 `PROFILE_SOURCE_VERSION_CONFLICT`；属性版本无法解析时保留缺口。

## 规则及结论范围

| 规则 ID | 触发事实 | 结论与建议边界 |
| --- | --- | --- |
| MYSQL_UNSIGNED | CREATE TABLE 数值列的 UNSIGNED 属性 | INCOMPATIBLE：原生 PG 不接受该属性；评估扩大值域与非负约束 |
| MYSQL_AUTO_INCREMENT | 列的 AUTO_INCREMENT 属性 | INCOMPATIBLE：评估 identity，并验证显式 0/NULL、历史 ID 和生成键读取 |
| MYSQL_BACKTICK | 支持语句内的反引号标识符 | INCOMPATIBLE：评估双引号及标识符大小写 |
| MYSQL_IFNULL | SELECT 中非 schema 限定的 IFNULL 调用 | INCOMPATIBLE：限原生 PG 函数集合；COALESCE 方案仍需类型/求值验证 |
| MYSQL_SIGNED_INT | 恰为“列名 INT/INTEGER”的无修饰声明 | COMPATIBLE：**仅整数值域相同**，不推导整列或整表兼容 |
| ORACLE_TYPE | CREATE TABLE 的 NUMBER/VARCHAR2 类型 | INCOMPATIBLE：原生 PG 类型名不同，映射须保持精度、长度及空值约定 |
| ORACLE_DATE | CREATE TABLE 的 DATE 类型 | CONDITIONAL：是否必须保留时间部分尚未知，评估 timestamp |
| ORACLE_EMPTY_STRING | 支持 SQL 中的空字符串字面量 | CONDITIONAL：核实业务对空字符串与 NULL 的约定 |
| ORACLE_NVL | SELECT 中非 schema 限定的 NVL 调用 | INCOMPATIBLE：限原生 PG；类型转换和求值差异待验证 |
| ORACLE_NEXTVAL | SELECT 中的限定名 .NEXTVAL | INCOMPATIBLE：原生序列访问语法不同，不推断目标序列存在 |
| CS_DECIMAL | 注释/字符串之外的 decimal 关键字 | INCOMPATIBLE：不能直接作为 Java 基础类型；BigDecimal 映射须另验 |
| CS_UNSIGNED | uint/ulong 关键字 | INCOMPATIBLE：没有同名 Java 基础类型；值域及溢出待验证 |
| CS_AWAIT | await 词法候选 | UNKNOWN：上下文关键字未绑定，不能确认异步状态机或行为等价 |
| CS_SERIALIZATION | `[JsonPropertyName(...)]` 候选 | UNKNOWN：未绑定属性类型；建议核对 JSON 契约及往返样例 |
| JAVA_API_CHANGE | 明确前后类的方法声明或类型全名变化 | 原始类型方法删除/返回类型/static/可见性破坏为 INCOMPATIBLE；已检查契约不变为局部 COMPATIBLE；包移动及额外修饰符变化 UNKNOWN |
| JAVA_BODY_CHANGE | 前后方法体 AST 不同 | UNKNOWN：需要验证业务不变量、副作用和异常；忽略注释/排版差异 |
| JAVA_STATE_CHANGE | 字段/初始化块 AST 不同 | UNKNOWN：需要验证初值、共享状态、持久化和并发 |
| BOOT_JAKARTA | javax.servlet/persistence/validation 导入 | INCOMPATIBLE：限 Boot 3 Jakarta 集成；不会全局替换 javax.* |
| BOOT_JAVA17 | 直接 java.version/compiler.release 字面值低于 17 | CONDITIONAL：静态属性不是有效编译/部署 JDK，需核对实际配置 |
| BOOT_SERVLET_DEPENDENCY | 直接 javax.servlet:javax.servlet-api 依赖 | CONDITIONAL：依赖用途、scope、容器和并存情况未核实 |
| JDK_JAXB | javax.xml.bind 导入 | CONDITIONAL：目标 JDK 不内置 JAXB，但独立依赖可能已提供 |
| HTTPCLIENT_NAMESPACE | org.apache.http 导入 | CONDITIONAL：4/5 可并存，替换时才需要适配旧命名空间 |

每条规则携带版本、适用范围、所需事实、官方链接和复核日期。依据包括 [MySQL 数值属性](https://dev.mysql.com/doc/refman/8.0/en/numeric-type-attributes.html)、[PostgreSQL 16 类型](https://www.postgresql.org/docs/16/datatype.html)、[Oracle 19c 类型](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/Data-Types.html)、[Oracle 空值语义](https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/Nulls.html)、[C# 数值类型](https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/floating-point-numeric-types)、[Java 二进制兼容规范](https://docs.oracle.com/javase/specs/jls/se21/html/jls-13.html)、[Boot 3.0 迁移指南固定修订](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide/902835141ceffe40397423e5bd095505a2b0e40e)、[JDK 移除组件](https://docs.oracle.com/en/java/javase/21/migrate/removed-tools-and-components.html)、[HttpClient 5.x 迁移说明](https://hc.apache.org/httpcomponents-client-5.6.x/migration-guide/migration-to-classic.html)。最后一份现行指南只用于 4/5 命名空间及并存原则；目标坐标另由 [5.2.3 官方依赖说明](https://hc.apache.org/components/httpcomponents-client-5.2.x/5.2.3/httpclient5/dependency-info.html)核对，不外推 5.6 API 到 5.2。

## 输入及运行

在 `ArchLensService` 目录、JDK21 构建完成后运行：

```powershell
# 导出当前注册规则（只读取打包产物）
& "$env:ARCHLENS_JAVA_HOME\bin\java.exe" -Dfile.encoding=UTF-8 -jar target/archlens-0.1.0-SNAPSHOT-cli.jar rules

# 单个场景；输出路径必须不存在
& "$env:ARCHLENS_JAVA_HOME\bin\java.exe" -Dfile.encoding=UTF-8 -jar target/archlens-0.1.0-SNAPSHOT-cli.jar `
  investigate examples/scenarios/mysql-postgresql/investigation.json ('target/mysql-' + [guid]::NewGuid() + '.json')

# 七组打包 CLI 样例和 25 个规则 ID 检查
.\scripts\scenario-smoke.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME

# 保存同一场景到 ArchLens 自身存储；凭据仍由既有后端配置读取
.\scripts\storage.ps1 -Command investigate-store -CommandArgs @('examples/scenarios/mysql-postgresql/investigation.json','new') -JdkHome $env:ARCHLENS_JAVA_HOME
```

七组完整输入在 [examples/scenarios](../examples/scenarios/)，包括版本、源文件和行为不变量。核心调查仅读取 `files` 中列明的文件；网页 C# 入口可先有界发现候选并由用户审阅，不自动连接 MySQL/Oracle，不构建这些样例。

重构要求 `files` 中恰有一个 `*.refactor.json`，示例：

```json
{
  "schemaVersion": "archlens.refactor-plan.v1",
  "pairs": [{"before": "before/Counter.java", "after": "after/Counter.java"}]
}
```

两端路径相对调查请求目录，均须独立列入 `files`；计划不会触发额外文件读取。同一路径不能充当前后两个角色；最多 20 对。不把自然语言“重构一下”猜成具体方法移动计划。

## 报告与证据

输入契约保持 `archlens.investigation-request.v1`。新报告为 `archlens.investigation-report.v2`；历史 v1 JSON 仍可读取及按原始哈希导出，不改写已封存报告。新增发现包含 `subjectId`、中文 `summary`、内嵌 `rule`、`evidence`、`recommendations` 和 `impacts`。

规则发现的 `evidenceIds` 对应其内嵌 `evidence.evidenceId`；每项证据再通过 `sourceId` 和 `sourceHash` 指向报告顶层来源清单。SQL/C# token 和 Java AST 证据使用原文 UTF-8 字节范围、1 起始 Unicode 码点行列、结束不包含；Maven XML、比较计划以及旧列分析仍为整文件证据。不要把整文件证据当作 token 位置。

`impacts` 区分声明事实与 `CANDIDATE` 调用。同名、同实参数量不足以绑定接收者/重载，因此候选保持 `changeRequired=UNKNOWN`，不进入事实图。方法不变只说明已检查声明不变，方法体/状态变化另列 UNKNOWN。新场景没有复用旧列风险评分，不产生未经校准的迁移风险分数。

规则包元数据参与输入指纹；规则、目标或源字节变化均使指纹变化。最终来源重查若发现漂移、取消或超时，撤销规则发现，保留来源清单和缺口。所有报告仍为 `PARTIAL`，保留总体未检查范围的 UNKNOWN；退出码 0 仅表示成功生成报告。

所有新场景报告可以在 PG 封存；因尚无完整、已绑定的新场景依赖图，Neo4j 投影状态为 `NOT_APPLICABLE`。旧列分析仍使用真实图并可投影 `READY`。

## 失败、预算与边界

- SQL 仅支持有限 CREATE TABLE 列声明及 SELECT 词法检查。普通注释和字符串不会被当作关键字；过程/包/函数/触发器文件、可执行注释、Oracle q 引号、SQL 模板、未知 MySQL 引号/反斜杠模式明确报告缺口。SELECT 不声称完整语法校验或对象绑定。
- C# 源码部分仍为词法特征调查；项目 XML 声明另由项目规则处理。原始/插值字符串、预处理指令、转义标识符编码等不支持模式使该文件停止匹配。`@decimal` 等转义标识符不当作类型关键字。
- Java 使用已有 JavaParser，不新增依赖；只读解析。Unicode 转义预处理无法映射回原文时停止分析；Maven 禁用 DTD/外部实体，不执行插件或解析外部父 POM。
- 规则扫描每个 SQL/C# 文件最多 500,000 个 UTF-16 单元、50,000 个 token，Java 最多 200,000 个 UTF-16 单元；调查最多 500 条规则发现，每个方法展示至多 20 个调用候选。超限有独立诊断，不能输出“全兼容”。
- 总文件数、总字节数、超时和取消继续受调查预算控制。时间预算为协作式，单个词法/AST 解析调用不能被强制中断。解析失败撤销该文件的规则发现；发现数量超限撤销本次规则发现。

手工反例：把 MySQL 样例版本改成 `8.4` 或 null，必须得到 UNKNOWN/矩阵缺口；把 IFNULL 写入注释或字符串，不应触发函数规则；用继承类做重构对比，应显示不支持而不是宣布方法删除。详见 [本批验证记录](verification-scenario-rules-2026-09-17.md)。

2026-09-22 规则目录更新为 1.1（25 条）。新增 CS_PROJECT_PROFILE、CS_PROJECT_DEPENDENCY、CS_PROJECT_REFERENCE，详情见 [C# 项目线](csharp-java.md)。网页提供用户触发的候选发现，核心仍只分析最终显式清单。
