# 业务数据库与目标环境（MySQL、Oracle、KingbaseES、DM）

更新：2026-09-27。当前交付业务数据源配置、目标环境声明、真实连接测试、只读元数据采集，以及代码与数据库联合现状报告。尚未实现任意数据库互转或自动迁移。业务库独立于 ArchLens 自身 PostgreSQL/Neo4j 存储。

## 调查范围

- **仅代码**：选择“仅代码”，填写授权目录和文件清单；.NET 项目可本地预览，其他代码类型通过正式调查入口分析。
- **仅数据库**：选择“仅数据库”，填写所选业务数据库连接；代码目录和文件清单被禁用，可直接预览或正式提交。
- **代码＋数据库**：选择“代码＋数据库”，同时填写授权目录、文件清单和所选业务数据库连接，报告记录两类事实。当前仍未建立两者之间的语义依赖。

目标环境可选；若提供任何代码文件，目录仍必须是有效的授权绝对路径。目标环境声明不能替代代码或数据库输入。

## 页面使用

1. 构建 Java 后端并启动 [本机工作台](../../ArchLensClient/README.md)。填写授权代码根目录，发现并确认相对文件清单；仅调查数据库时，授权目录和文件清单均可留空。
2. 展开“业务数据库”，勾选本次连接，选择数据库产品，填写主机、端口、对象范围、只读账号、密码与连接模式。源版本可留空，由服务器采集实际版本。
3. MySQL 默认 `VERIFY_IDENTITY` 验证服务器证书及主机名，使用 JVM 信任库。`REQUIRED` 要求加密但不验证服务器身份，适合已隔离的测试环境；`DISABLED` 仅用于受控本机测试。不要把 JDBC URL 填入主机字段；当前不支持 IPv6 地址或自定义 JDBC 参数。
4. 点击“测试数据库连接”。MySQL 会验证 8.x 版本、只读会话及所选库可见性；其他产品检查驱动、认证、产品和模式可见性，服务端只读与 TLS 状态目前无法验证。连接成功不表示拥有完整对象权限。
5. 目标环境可声明目标数据库/版本/兼容模式、操作系统/版本、CPU 架构、运行时/版本。此处填 KingbaseES 不会触发数据库迁移，也不构成兼容性结论。
6. 点击“预览数据库 / 联合现状”：无需模型、无需 ArchLens 自身存储。输出同一份 JSON 报告，页面展示 .NET 声明、数据库结构、代码 SQL 对象候选关联、目标环境、来源哈希及覆盖缺口。预览不保存历史，也不分析自然语言迁移目标的全部语义。
7. 正式“提交调查”复用上述采集能力，仍需自身 PostgreSQL 配置；模型不可用时保留确定性降级。恢复关联业务库的历史调查需重新填写原连接参数；数据源指纹拒绝更换端点/库/账号/TLS 模式，密码轮换不改变指纹。源/目标数据库字段冲突会被拒绝。

## 实际采集范围

| 范围 | 本批行为 |
| --- | --- |
| 源产品 | MySQL 8.x 已实库验证 8.4.11；Oracle、KingbaseES、DM 已接入 JDBC 元数据采集，但尚未用各产品实例实库验证；MariaDB 不支持 |
| 服务配置 | 实际版本、sql_mode、大小写表名设置、服务器及库字符集/排序规则 |
| 表与视图 | 名称、类型、引擎、排序规则、表注释存在标记；视图定义是否可见、长度、可更新性、安全模式 |
| 字段 | 顺序、类型、完整类型声明、可空、extra、字符集/排序规则；默认值、注释、生成列表达式存在标记 |
| 索引/约束 | 索引列及顺序、唯一性、类型；主键/唯一键/外键及引用对象；CHECK 名称、是否强制、表达式可见性与长度 |
| 程序对象 | 当前账号可见的过程、函数、触发器名称与类别；返回类型、参数类型、数据访问/安全模式、触发事件与时机，以及正文可见性和长度 |
| 未保存 | 业务数据行、默认值和注释原文、视图/存储程序/触发器正文、CHECK 表达式、函数索引表达式 |
| 未建立 | 完整方法调用图、运行时 SQL/ORM 依赖证明、数据规模/质量、目标兼容性、迁移计划与执行 |

账号应由数据库管理员配置为所选库的最小只读权限；合成测试使用所选库的 SELECT/SHOW VIEW。过程与函数的可见性还取决于额外对象权限，缺少权限时不能把未出现的对象解释为不存在。不同权限会改变 INFORMATION_SCHEMA 可见范围，采集器无法证明隐藏对象不存在，因此始终记录 `DB_ACCOUNT_VISIBLE_OBJECTS_ONLY`。元数据为多条查询，不能保证跨查询原子一致，记录 `DB_NON_ATOMIC_METADATA`。

扩展查询使用 MySQL 官方的 [TABLE_CONSTRAINTS](https://dev.mysql.com/doc/refman/8.4/en/information-schema-table-constraints-table.html)、[ROUTINES](https://dev.mysql.com/doc/refman/8.4/en/information-schema-routines-table.html) 和 [PARAMETERS](https://dev.mysql.com/doc/refman/8.4/en/information-schema-parameters-table.html) 字段。仅读取定义长度与可见性，正文、默认值及注释内容不进入报告；长度不能用于判断表达式兼容性。

所有查询为固定、带库名绑定参数的系统元数据 SELECT。JDBC 连接显式设置只读并核验服务器会话，结束回滚；禁止多语句、LOCAL INFILE、URL 本地文件读取及反序列化。无任意 SQL 接口，不执行被分析项目。

## 预算、失败及隐私

- 上限：200 个表/视图；字段、索引列、约束列和 CHECK 各 5000 条；视图、过程/函数各 200 条，触发器 500 条，程序参数 1000 条；元数据文本总计 500000 字符，单值 4096 字符。超限显式返回缺口或不可用，不能据此声称全库盘点完成。
- 数据库采集最多 15 秒且受调查总预算约束；连接测试最多 10 秒；连接/网络超时 5 秒，单查询最多 5 秒。取消停止等待并丢弃迟到结果，底层 JDBC 结束仍受驱动超时控制。
- 认证失败、权限问题、不支持版本、取消、超时与声明版本冲突使用稳定错误码；不回显驱动异常中的连接信息。数据库失败时联合报告保留可用的代码盘点；代码来源变化或调查取消会撤销不能确认有效的结构结果。
- 密码只在当前表单内存和本次 Node→Java 请求中传递，不写浏览器存储、源码上下文文件、调查请求、报告、模型上下文或应用日志。页面关闭后需重填。主机及账号不进入报告；报告保留库名、对象名与不可逆数据源指纹，属于用户本地调查材料。
- 模型仅获得是否有关联业务库这一摘要标记，不自动获得库名、对象名或连接参数。目标环境目前供报告记录，不冒充已完成的规则评估。

## 契约与实现

业务上下文使用 `archlens.business-context.v1`；含此上下文的 Agent 请求/报告使用 `archlens.agent.v2`，含代码候选关联的联合调查结果使用 `archlens.investigation-report.v5`；其余业务调查继续使用 v4。增强结构清单使用 `archlens.database-inventory.v2`，旧 v1 清单仍可读取。旧 Agent v1 与历史调查报告继续读取，新增空字段不参与旧哈希。数据库清单包含结构哈希、对象证据 ID 和采集时间，哈希不含采集时间。

实现：`BusinessContext` / `BusinessConnection` / `MysqlCollector` / `DatabaseInventory`；`WebAgentCli` 增加 `test-database` 与 `preview-joint`，本机 HTTP 对应 `/api/investigations/test-database`、`/api/investigations/preview-joint`。连接对象单独传递，不能塞进持久化 Request。正式 start/resume 同样接受独立连接对象。

驱动固定为 `com.mysql:mysql-connector-j:9.7.0`，未使用 X DevAPI，排除其 protobuf 依赖。参见 [官方 Maven 安装说明](https://dev.mysql.com/doc/connector-j/en/connector-j-installing-maven.html)、[连接安全属性](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-security.html)。分发包含驱动的产物时需保留并核对驱动 LICENSE（GPLv2 与 Universal FOSS Exception 1.0）；本说明不改变第三方许可条件。

验证及证据：[2026-09-25 验证记录](verification-business-database-2026-09-25.md)。

## Oracle、人大金仓和达梦采集与关联（2026-09-26）

三种产品通过 JDBC `DatabaseMetaData` 接口并设置客户端只读标志，采集**指定模式**中当前账号可见的表/视图、字段、索引、主键、外键、过程和函数的名称及结构。Oracle 需填写服务名和模式；KingbaseES 需填写数据库名和模式；DM 需填写模式。连接地址由程序按固定产品模板生成，不接受用户 JDBC URL 或任意 SQL。单次上限 200 个表、字段/索引/键各最多 5000 条、过程和函数各 200 条，超过时报告覆盖缺口。驱动返回的元数据可能不完整；对象可见性以账号权限为限，服务端只读状态、TLS 状态及跨查询一致性尚未实测确认。

启动工作台前，把相应厂商提供的 JDBC JAR 绝对路径放入 `ARCHLENS_BUSINESS_JDBC_JARS`，多个 JAR 按操作系统类路径分隔符连接（Linux/macOS 为 `:`，Windows 为 `;`）。Oracle 使用与 JDK 21、目标数据库版本匹配的 `ojdbc11.jar`；KingbaseES 使用安装包提供的 JDBC JAR；DM 使用厂商提供的 `DmJdbcDriver11.jar` 或经该厂商确认兼容当前 JDK 的驱动。缺少驱动返回 `DB_DRIVER_UNAVAILABLE`。JAR 不会复制到项目、报告或浏览器。本版非 MySQL 连接只提供 `DRIVER_DEFAULT`，传输安全性必须由部署环境和数据库管理员核对；报告明确给出 `DB_TLS_POLICY_UNVERIFIED`。

代码关联只检查本次明确列出的 `.cs`、`.java`、`.kt`、`.xml` 中的字符串及 `.sql` 文件中的 `FROM`、`JOIN`、`UPDATE`、`INTO`、`CALL`、`EXEC` 语法线索。通过所选模式的对象名匹配得到 `CANDIDATE`，歧义为 `AMBIGUOUS`，未找到为 `UNMATCHED`。报告保留文件路径、来源哈希、行号、操作、对象证据 ID；不保留 SQL 片段。动态 SQL、ORM 映射、执行分支、同义词指向和跨模式对象不能据此确认。采集失败时不会发布对象关联。最多报告 500 条线索，超限显式记录缺口。

连接格式依据 [Oracle JDBC Thin 文档](https://docs.oracle.com/en/database/oracle/oracle-database/21/jjdbc/data-sources-and-URLs.html)、[KingbaseES JDBC 指南](https://help.kingbase.com.cn/v8.6.8.20/PDF/KingbaseES%E5%AE%A2%E6%88%B7%E7%AB%AF%E7%BC%96%E7%A8%8B%E6%8E%A5%E5%8F%A3%E6%8C%87%E5%8D%97-JDBC.pdf) 与 [达梦 JDBC 编程指南](https://eco.dameng.com/document/dm/zh-cn/pm/jdbc-rogramming-guide.html)。这三个产品尚无本次任务可用的实库凭据，当前验证覆盖编译、契约、静态关联、前端和缺驱动失败路径；连接成功与真实元数据覆盖仍需在各产品测试实例上验证。

## 静态访问证据路径（2026-09-27）

联合报告中的 `archlens.code-database-association.v2` 在原有对象名候选关联上增加 `accessPaths`。每条路径记录源码文件及哈希，并以单独的证据 ID 表示入口注解、方法或 MyBatis 语句、数据访问 API 语法、SQL 对象引用和已采集的数据库对象。页面同时展示状态及未确认项，不保存 SQL 原文。

首批支持 C#、Java、Kotlin 中可定位的方法声明和 `new SqlCommand(...)`、`prepareStatement(...)` 等有限调用形式，以及 MyBatis XML 的 `select`、`insert`、`update`、`delete` 语句体。SQL 字面量**直接作为**已识别 API 实参时标记 `DIRECT_ARGUMENT_CANDIDATE`；SQL 与调用只出现在同一方法内时标记 `SAME_METHOD_CANDIDATE`，并明确 `SQL_TO_INVOCATION_NOT_PROVEN`。MyBatis 语句标记 `MAPPER_STATEMENT_CANDIDATE`；当前没有把 XML namespace/id 与 Java 接口方法可靠绑定。未能定位方法的 SQL 仍作为 `UNRESOLVED_CONTAINER` 展示，绝不补造入口或调用边。C# 路由注解仅在紧邻方法声明时列为入口声明证据，不代表请求一定能到达该方法。

这些状态都不是运行时依赖证明：变量传递、重赋值、条件分支、反射、跨方法调用、继承、动态 SQL、复杂 ORM 映射及 VB.NET/F# 仍需后续分析。`DB_METHOD_CALL_GRAPH_UNAVAILABLE`、`DB_MAPPER_INTERFACE_UNBOUND`、`DB_CODE_LANGUAGE_UNSUPPORTED` 等缺口会随报告保留。XML 仅按文本遮蔽标签和注释，不解析外部 DTD/实体，不执行被分析代码。静态关联最多使用本次调查剩余预算中的 5 秒，超时撤销未完成的关联并保留数据库清单；取消则撤销本次调查的事实结果。首批验证使用合成 C#/Java/MyBatis 样例和负例；完整项目及各厂商实库验收仍未完成。

本批实际验证见 [2026-09-27 静态访问路径验证](verification-access-path-2026-09-27.md)。
