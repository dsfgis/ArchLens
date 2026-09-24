# ArchLens

当前文档基线：2026-09-22。[文档导航](docs/README.md) 汇总运行入口、设计、任务进度、能力边界及有日期的验收证据。

按当前 INV 规格实施（总体背景见《ArchLens_设计文档_v1.0.docx》）。已保留 **0.1 确定性列分析**，并于 2026-09-17 新增 **统一调查与自身存储切片**：显式文件清单/列分析 → 证据报告 → PG Case/Run/修订 → Neo4j 图投影。完整产品仍在开发，不能作为生产系统完整扫描或上线审批工具。

新入口、凭据配置、数据库权限、失败恢复和验收命令见 [调查与双存储切片](docs/investigation-storage.md)。本机网页已加入 Agent 提交、澄清、状态、证据报告和历史修订适配，2026-09-20 已实测 MySQL SQL 调查网页闭环、澄清及重启回读，见 [验证记录](docs/verification-web-2026-09-20.md)；运行方法见 [前端说明](../ArchLensClient/README.md)。

现已新增 [多场景兼容规则与调查](docs/scenario-rules.md)：25 条固定版本规则覆盖 MySQL/Oracle → PostgreSQL、C# → Java、Java 重构，以及 Spring Boot/JDK/HttpClient 升级的明确子集。提供七组可运行样例、带原文位置及官方规则依据的 v2 报告，并可在 PG 封存；关键代码含中文注释。

## 运行

2026-09-18 新增 [模型 Agent 编排层](docs/agent-orchestration.md)：原生工具调用循环、目标解析、规则选择、确定性分析、可恢复澄清、证据解释和 PG 封存。入口为 `scripts/agent.ps1`，网页通过 `WebAgentCli` 复用编排与 PG 存储；[本次验证](docs/verification-agent-2026-09-18.md) 区分离线测试、真实模型调用及数据库联调状态。

需要 JDK **21**、Maven **3.9.x**。首次构建需要下载 Maven 依赖。项目不修改机器的默认 JDK；Windows 脚本结束后会恢复当前进程环境。依赖缓存位于项目 `.local/m2`。

```powershell
# 在 ArchLensService 目录运行；按本机位置修改 JDK 路径
.\scripts\build.ps1 -JdkHome 'D:\Program Files\Java\jdk-21.0.11' -Demo
```

脚本运行测试、打包，并把本次样例报告写入 `target/rename-report-时间戳.json`。

也可在已配置 JDK21 的终端运行：

```powershell
mvn --batch-mode --no-transfer-progress '-Dmaven.repo.local=.local/m2' verify
java -jar target/archlens-0.1.0-SNAPSHOT-cli.jar analyze examples/column-rename/request.json target/my-report.json
```

输出文件必须不存在，避免覆盖源码、输入或已有报告。CLI 成功输出报告的退出码为 0，报告分析质量仍是 `PARTIAL`；参数、契约、路径或写入失败返回 2。不要把进程退出码 0 解释为完整扫描或业务安全。

## 当前实现

- `contract`：版本化 IR、UUID scope、SHA-256 身份、canonical JSON、证据校验、不可变图、关系端点约束、同关系证据合并、COLUMN/METHOD 属性校验。其余节点的完整属性 schema 与跨语言 JSON Schema 尚未完成。
- `parser`：JavaParser 读取指定 Java21 接口的直接无参抽象方法；MyBatis XML 按 namespace/id 唯一绑定；JSqlParser 提取单表静态 SELECT 和有限 WHERE 的直接列引用。解析器读取源码，不编译或执行被分析项目。
- `analysis`：列改名/删除的有限语义、类型变更未知项、反向有界遍历、候选传播、环路处理、三条展示路径与全部已访问判断分离、独立风险区间计算。
- `cli`：旧 analyze/investigate 保持确定性离线分析；新增 agent-* 命令调用模型循环，可持久化澄清及调查报告。
- `agent`：目标解析、规则选择、受限工具执行、澄清恢复、证据解释；模型不能直接生成或修改事实结论。
- `investigation/rules`：明确来源清单、版本限定规则、覆盖缺口和 v2 证据发现报告。
- `storage`：ArchLens 自身 PG Case/Run/修订、租约和不可变封存；存在已绑定事实图时投影 Neo4j。

示例的 `event_id → global_id` 是物理列改名，真实 XML 仍读取 `event_id`，因此 Mapper 为 `YES`。没有源码字段映射的对象上游只能是 `UNKNOWN`。拟议兼容条件只可形成 `UNKNOWN + conditionedOutcome=NO`；当前尚无验证快照引擎，`VERIFIED` 和 `APPLIED` 输入会明确拒绝。

## 支持边界

catalog 中的标识符必须使用数据库实际大小写，`defaultSchema` 显式指定。catalog 是用户提供的离线断言，未与真实 PostgreSQL 校验，因此报告包含 `OFFLINE_CATALOG_UNVERIFIED`；它不是已发布数据库快照。

动态 XML、`${...}`、参数化 SQL、带参/重载/继承方法、CTE、JOIN、子查询、复杂表达式、结果映射和 Java/JSON/Vue 字段链不在首批支持范围。遇到这些输入产生诊断，可能保留明确的局部引用，但不声称覆盖完整。首批 XML 拒绝 DOCTYPE/实体声明（包含常见 MyBatis 外部 DTD 声明）；不会访问外部 DTD。不要把这一限制当作 MyBatis XML 语法错误。

源码必须为 UTF-8；采集单文件最多 5 MB；路径只能指向输入根目录内的真实文件。旧列分析证据是整文件范围；新规则的 SQL/C# token 和 Java AST 证据有原文 UTF-8 字节及 Unicode 码点行列，XML/比较计划仍为整文件。行列从 1 开始、end 不包含。新规则解析另有更小的文件/token/发现数量限额，详见多场景说明。

风险 B/K/D 都保留来源；业务关键性和恢复信息缺失时使用区间。报告没有生产权限系统，不应通过 HTTP 对外暴露这个 CLI。新增 PG/Neo4j 和 Case/Run 用于 ArchLens 自身存储，供 CLI 与本机网页使用；尚未提供 Spring Boot API、Vue 界面和在线业务库采集；OIDC 鉴权经 2026-09-21 用户决策本版本不实施，当前服务仅限本机单用户场景，不得对外部署。DeepSeek 分为 [旧提议接口](docs/deepseek.md) 与 [Agent 工具编排](docs/agent-orchestration.md)；前者生成 UNVERIFIED_PROPOSAL，后者保存确定性调查和单列的 MODEL_EXPLANATION_UNVERIFIED 解释。

## 后续实施与验证

[实施任务](specs/implementation/tasks.md) 和 [验收记录](specs/implementation/check_list.md) 跟踪已完成的子集与后续工作。单元/集成测试验证本批能力，不等于设计文档要求的 40 个独立黄金样例和真实项目性能验收。当前已有报告/图 JSON 持久化、运行租约、报告封存及有限场景规则；完整项目采集、业务元数据快照与更广泛迁移语义仍需逐项实施。

固定依赖及来源见 [技术基线](docs/technical-baseline.md)。现有 `qa/` 是设计文档制作和排版验证资料，不是产品代码。

C# 项目分析入口、声明范围及边界见 [C# → Java 分析](docs/csharp-java.md)。
