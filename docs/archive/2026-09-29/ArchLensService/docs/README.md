# ArchLens 文档导航与当前基线

2026-09-27 增量：联合报告增加 C#/Java 方法及 MyBatis 语句的静态访问证据路径，区分直接 SQL 实参、同方法候选和未知；详见 [业务数据库与访问路径](business-database.md)。
2026-09-26 更新：[业务数据库与目标环境](business-database.md)。已支持 MySQL 8.x 与 Oracle、人大金仓 KingbaseES、达梦 DM 的连接及只读结构采集，并输出代码 SQL 对象名的静态候选关联；后三种数据库仍需厂商 JDBC 驱动及实库验证。目标环境仅记录声明，运行时依赖确认和迁移评估仍待实现。
更新日期：2026-09-24。当前产品提供模型参与的只读调查 CLI：目标解析 → 适用规则选择 → 本地分析 → 证据解释；信息不足时澄清并恢复为新修订。事实与规则结论由确定性程序产生，模型解释保持待核实。本机网页已加入提交、澄清恢复、状态、证据报告及历史修订；真实网页 SQL 调查闭环及 PG 重启回读已验证，见 [网页验证](verification-web-2026-09-20.md)。

2026-09-24 已确认后续目标：代码来源 + 按需业务数据库连接 + 自然语言目的，覆盖整个 .NET 平台（.NET Framework/.NET Core/现代 .NET）、Oracle/MySQL → KingbaseES 和信创组合改造。首批已实现 [.NET 平台现状](dotnet-platform.md)：解决方案发现、混合项目声明清单与本地预览；完整迁移能力仍按下表及任务状态判断。

## 按用途查阅

| 需求 | 当前文档 |
| --- | --- |
| 构建及后端入口 | [后端 README](../README.md) |
| Agent 工具、目标解析、澄清、恢复、数据边界与命令 | [Agent 编排](agent-orchestration.md) |
| .NET 解决方案发现、平台清单及无存储预览 | [.NET 平台说明](dotnet-platform.md)、[2026-09-24 验证](verification-dotnet-2026-09-24.md) |
| 支持的产品版本及 25 条规则 | [场景规则](scenario-rules.md) |
| 自身 PG/Neo4j 配置、修订、封存、导出与故障恢复 | [调查存储](investigation-storage.md) |
| 本机调查工作台及旧模型目标提议 | [前端 README](../../ArchLensClient/README.md)、[旧描述解析](deepseek.md) |
| 产品范围、平台级目标、设计、任务及验收（INV-REQ-10–16 分阶段实施） | [需求](../specs/implementation/requirements.md) → [设计](../specs/implementation/design.md) → [任务](../specs/implementation/tasks.md) → [验收](../specs/implementation/check_list.md) |
| 依赖及工程约束 | [技术基线](technical-baseline.md)、[项目规则](../../project_rules.md)、[开发代理指南](../../AGENTS.md) |

## 能力与入口

| 入口 | 实际能力 | 边界 |
| --- | --- | --- |
| `analyze` | 离线 Java/MyBatis 列影响分析 | 显式 catalog，非在线数据库快照 |
| `investigate` / `investigate-store` | 显式文件调查、版本限定规则、文件/PG 报告 | 确定性流程，不调用模型 |
| `agent-investigate` / `agent-resume` | 模型工具循环及文件澄清修订 | 文件模式允许从同一父报告分支 |
| `agent-investigate-store` / `agent-resume-store` | 模型调查及 PG 修订封存恢复 | 最新修订校验；有已绑定图才投影 Neo4j |
| 网页 `/api/investigations` | 提交、澄清、状态、证据下载、历史修订 | 本机显式文件；有限 SQL 场景已真实联调 |
| 网页 `/api/parse-target` | 修改描述转为 UNVERIFIED_PROPOSAL | 未接入 Agent 调查、业务采集或报告展示 |

未实现：全项目自主采集、其他产品实库覆盖、联网规则研究、完整 .NET 有效配置/语义及到 Java 的行为验证、Oracle/MySQL→金仓专用规则、信创环境组合分析、生产 HTTP 和完整生产调查工作台。OIDC 鉴权经 2026-09-21 用户决策本版本不实施；对外多用户部署前必须补充身份鉴权方案。不支持的版本/对象保持 UNKNOWN；模型可以解释缺口，不能补造事实或宣布兼容。

新增 [2026-09-27 静态访问路径验证](verification-access-path-2026-09-27.md)，区分单元测试、网页夹具与尚未完成的实库联调。

## 验证与历史资料

[2026-09-18 Agent 验证](verification-agent-2026-09-18.md) 记录真实模型闭环、PG 澄清恢复及双库测试：108 项、107 通过、1 项符号链接权限跳过。这是该次运行结果，不是服务持续可用保证。2026-09-20 另完成网页真实联调与 PG-only 103 项回归（102 通过、1 跳过），范围见上述网页验证。

历史记录保留原日期和范围：[2026-09-12 基础切片](verification.md)、[2026-09-17 存储](verification-2026-09-17.md)、[2026-09-17 场景规则](verification-scenario-rules-2026-09-17.md)。四份规格中的日期追加段也属于相应阶段记录，当前正文和状态表说明现状。

[原始 DOCX 设计](../ArchLens_设计文档_v1.0.docx) 保留为历史架构参考，未重制为当前版本。当前实施以 INV Markdown 规格、对应源码和有日期的验证记录为准；不要从旧 DOCX 的规划推导当前已有 Spring Boot、Vue 或完整自主采集能力。

当前 C# 子集入口、声明范围及边界见 [C# → Java 分析](csharp-java.md)。.NET 平台扩展以 INV-REQ-10–16 为后续目标，不能由当前 C# 子集推导整个平台已支持。
