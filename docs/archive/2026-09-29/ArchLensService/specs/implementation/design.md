# ArchLens 分析调查 Agent 设计

当前修订日期：2026-09-24；目标设计基线：2026-09-14。本文同时标明 INV-REQ-01–09 的目标设计与已实现子集，按 [需求](requirements.md) 追踪。2026-09-24 新增 INV-REQ-10–16 / INV-D08–12 平台分析目标设计，现已实现 INV-D08/09 的本地发现与声明盘点子集，其他范围按任务表推进。旧 DOCX 是未重制的历史设计，不作为当前接口或完成状态依据；当前文档入口见 [文档导航](../../docs/README.md)。

## 当前实现结构

`AgentCli → AgentOrchestrator ↔ DeepSeekAgentModel` 构成模型循环；六个受限工具调用 `RuleCatalog / InvestigationEngine / ScenarioRules`，事实与证据仍由本地确定性程序产生。`AgentContracts.Report` 分开保存调查结果和未核实模型解释；文件模式写新报告，存储模式通过 `PgInvestigationStore` 封存修订，存在已绑定列分析图时再投影 Neo4j。

旧 `analyze/investigate` 不依赖模型，旧网页 `/api/parse-target` 仅解析提议。本机网页 Agent 适配已加入，设计及验证范围见文末；生产 HTTP、完整递归扫描、其他数据库在线采集和联网规则研究未实现；MySQL 首批采集见文末。OIDC 鉴权经 2026-09-21 用户决策本版本不实施，本设计不包含生产身份鉴权组件。工具参数、状态及具体限额见 [编排说明](../../docs/agent-orchestration.md)。

## 现有基础切片

| 包 | 职责 | 需求 |
| --- | --- | --- |
| contract | 严格 JSON、canonical JSON 哈希、IR、不可变图校验 | REQ01/02 |
| analysis | 有界遍历、语义判断、风险区间及路径证据 | REQ03 |
| parser | 只读 UTF-8 Java/MyBatis XML 与显式 catalog，静态 AST 绑定 | REQ01/02 |
| cli | 离线分析、调查、Agent 及存储命令；各输入契约独立 | SLICE-AC05/06、INV-REQ-01/07 |
| investigation / rules | 显式采集、版本匹配、25 条规则、证据及覆盖报告 | INV-REQ-02/03/05 |
| agent | 目标解析、工具循环、匿名证据投影、澄清修订与降级 | INV-REQ-01/06/08/09 |
| storage | 自身 PG Case/Run 封存与 Neo4j 条件式投影 | INV-REQ-07 |

依赖关系使用 dependent → dependency；从变更目标反向遍历，CONTAINS 不参与传播。有限规则只在足够证据下给 YES；对象链、候选链和未支持语义一律 UNKNOWN。兼容性验证引擎尚未实现，因此本批禁止传入 VERIFIED，PROPOSED 仅附条件；不提供能把用户布尔值当作已验证事实的后门。

入图时验证节点 ID、来源、关系类型端点、证据 hash/位置及 scope，构造只读 incoming 索引。报告保存完整已访问判断的合并值与最多三条展示路径。遍历顺序固定，工作预算可复算；墙钟超时独立标记。风险计算使用 BigDecimal / HALF_UP，置信度不乘风险。

离线 catalog 是用户显式提供的分析范围，报告标记为离线输入且整体 PARTIAL；不会冒充生产数据库采集或完整元数据快照。Java 使用 JavaParser，SQL 使用 JSqlParser AST；未覆盖表达式通过诊断保留空缺。文件定位按原始 UTF-8 字节，结束位置不包含；首批证据粒度为原始整文件，禁止把规范化 SQL 字符串位置当原文件位置。首批 XML 禁止 DOCTYPE/实体；带 DTD 的常规 MyBatis 文件也会被明确拒绝，后续在隔离解析协议中扩充支持。

JDK21 为运行基线，Maven 固定直接依赖及构建插件。先验证独立核心，后续 Spring Boot 应用适配这个核心；当前不引入无业务用途的空 controller、假数据库或无鉴权服务器。

## INV-D01：统一调查上下文（INV-REQ-01、07）

调查业务对象统一使用 `Investigation Case`，不把一切迁移强行编码成列变更 `ChangeSpec`。拟议 `InvestigationSpec` 包含：

| 字段 | 语义及校验 |
| --- | --- |
| schemaVersion、caseId、revision | 版本化契约；持久化服务管理身份，离线标识不构成权限 |
| scenario | CURRENT_STATE、COLUMN_CHANGE、DATABASE_MIGRATION、LANGUAGE_MIGRATION、REFACTORING、DEPENDENCY_UPGRADE |
| sourceProfile、targetProfile | 产品/语言/框架名称、版本、相关配置；未知值显式记录，不默认猜测 |
| scope、sourceRefs | 模块/对象范围及来源引用；凭据仅引用受控配置，不进入调查 JSON |
| objective、constraints、invariants | 调查问题、限制和必须保持的行为；模型提议待程序校验 |
| clarificationItems | 缺失信息及其阻塞的结论范围 |

目标版本未知时允许采集现状；兼容结论保持 UNKNOWN。列分析通过专用适配器定位固定图中目标并验证前状态后创建 ChangeSpec，不能将 InvestigationSpec 或 TargetProposal 直接传入旧 analyze 命令。当前前端草稿、模型提议和 OfflineRequest 格式保持原有边界，未来使用独立版本适配并保留旧 CLI 回归。

## INV-D02：受控采集与事实层（INV-REQ-02、08、09）

目标设计按代码 AST、依赖/配置、数据库元数据注册采集器。当前实现读取明确文件清单，支持有限 Java/MyBatis、SQL/C# 词法特征、Java AST、直接 Maven 属性及 C# 项目声明分析；现已新增 MySQL 8.x 在线结构采集器；Oracle 连接器和完整 C# 符号绑定尚未实现。语法和产品版本范围见 [规则矩阵](../../docs/scenario-rules.md)。

读取限制在授权根目录和元数据接口；数据库连接账号只读，查询来自审核过的固定模板并参数化，不接受模型 SQL。默认不读取业务行，不执行存储过程；解析源码不加载或运行被分析项目。文件变化、权限不足、编码错误、语法不支持分别记录诊断及覆盖范围。

事实和证据保存 sourceId、内容哈希、采集时间、解析器版本、位置与确定性。不能精确定位时标注粒度，不伪造 token 位置。采集批次结束检查来源变化；来源漂移使相关结论失效或 PARTIAL。跨来源时间一致性未经验证时不得宣称原子快照。

## INV-D03：兼容性规则与场景分析器（INV-REQ-03）

规则包声明 ruleId、版本、适用源/目标版本范围、所需事实、判断条件、官方依据及复核日期、解释模板和建议模板。实施时核对指定版本的官方文档，并用正反例验证；本文的检查维度不是数据库差异已经证实的规则。

`CompatibilityFinding` 包含 findingId、subjectId、ruleRef、evidenceIds、outcome、conditions、unknownReasons、sourceLocation、recommendations。outcome 为 COMPATIBLE / INCOMPATIBLE / CONDITIONAL / UNKNOWN。无规则不等于兼容；COMPATIBLE 仅对应明确检查过的规则和对象，不代表整个项目可迁移。条件未验证保持 CONDITIONAL，不能映射为已验证无需修改。规则冲突或来源不足记录 UNKNOWN 和冲突依据。

场景分析器复用事实层和规则机制，但分别实现语义：数据库分析处理类型/方言/过程/驱动/事务等；语言迁移处理类型/运行时/框架及业务不变量；重构处理公共契约/调用/状态与副作用；升级处理 API/配置和传递依赖。不得仅扩展枚举便宣称支持场景。

## INV-D04：影响传播（INV-REQ-04、09）

以兼容发现涉及的对象或已验证变更目标为传播起点。边仍为 dependent → dependency，使用带语义的反向传播；代码中存在边类型不等于采集器已能提取它。结果分别记录 dependencyPresent、changeRequired、certainty、impactScore、risk、coverage 和 evidenceIds，禁止用风险分数推导必改或安全。

列、SQL、方法、API、配置、库与数据库对象需各自定义语义转换规则。对象级调用只能说明潜在影响，不能代替字段链或业务等价性证明。复用预算、循环控制和路径展示机制；新增场景的权重须单独验证，不能直接声称旧列评分适用所有迁移。

## INV-D05：调查报告（INV-REQ-05）

报告按以下内容组织：调查目标与技术版本、现状及支持范围、兼容性发现、直接/间接影响和路径、风险与未知、改造建议及适用条件、验证清单、来源及规则版本。每条建议关联发现，每个验证项关联行为不变量或兼容假设，并注明由用户在其环境执行；外部验证结果标记为外部提供，保留来源与未独立核实状态。

确定事实、规则结论、模型解释和待验证假设分开呈现。引用不到证据的模型判断不得进入确认发现列表。模型解释不能改写机器结果。成功生成报告与覆盖完成分别表达；当前离线报告继续 PARTIAL。后续只有预先声明的支持范围全部检查通过才可表示该范围完成，不能输出无条件的全系统安全结论。

## INV-D06：只读调查编排（INV-REQ-06、08）

Agent 流程为：解析目标 → 校验/澄清 → 选择已注册分析能力 → 请求受控采集 → 规则检查 → 影响分析 → 证据检查 → 生成报告。必要时循环补充证据，每次受预算限制。工具返回事实或结果引用；事实构造和写入必须经程序校验，模型不直接写图。

拟议工具能力限于读取授权源码/元数据、查询事实图、运行静态规则、查询证据和保存自身报告。参数包含范围和资源预算，服务端校验调用者权限及来源引用。不存在任意 shell、文件修改、SQL 执行、补丁应用、迁移或部署工具；说明“请执行迁移”只能产生超范围提示及分析建议。提示注入防护由工具授权和参数约束实施，不能只依靠提示词。

当前六工具循环已实现；上段中的任意图查询、其他数据库在线元数据采集和生产调用者权限仍是目标能力，不在现有工具注册表。旧 DeepSeek 描述接口不变；新增通道只发送用户声明、通用规则及匿名证据摘要，不自动发送路径、源码、业务名称或连接信息。缺少外部模型时，确定性模块继续运行并标记 DETERMINISTIC_FALLBACK；模型解释保持 MODEL_EXPLANATION_UNVERIFIED。

## INV-D07：生命周期、失败与运行边界（INV-REQ-07、09）

拟议运行状态为 DRAFT、NEEDS_CLARIFICATION、RUNNING、COMPLETED、PARTIAL、CANCELLED、FAILED。COMPLETED 只表示请求范围内调查结束；兼容性结论独立表达。旧报告和修订保持不可变，目标/来源/规则变化创建新修订；晚到的响应必须核对 revision，不得覆盖新结果。

| 失败类型 | 行为 |
| --- | --- |
| 缺少目标版本或约束 | 返回澄清项；独立现状调查可继续 |
| 无权限/来源缺失/解析失败 | 标记来源及相关覆盖缺口；其他来源可继续 |
| 规则不支持或证据冲突 | 对应发现 UNKNOWN，保留原因 |
| 模型不可用 | 回退确定性已支持流程，声明编排限制 |
| 超时、节点或工具预算耗尽 | 停止扩展，保留证据和截断原因，PARTIAL |
| 用户取消 | 停止新采集，隔离迟到结果，CANCELLED |
| 契约损坏、越权或存储失败 | 拒绝相关操作；不能生成可信报告时 FAILED |

运行记录包含来源摘要、规则/解析器版本、工具耗时、预算和错误码。当前已实现 PG Case/Run、180 秒租约、epoch、checkpoint、不可变报告与修订，及有事实图时的 Neo4j 幂等投影。Agent 报告状态为 NEEDS_CLARIFICATION/PARTIAL/CANCELLED；澄清报告在 PG 运行层记为 PARTIAL。回答绑定父报告哈希，PG 通过最新修订条件更新拒绝重复恢复；文件模式允许分支。生产权限、自动后台恢复、保留期限和规模目标仍待实施；不以业务库充当自身存储。

版本升级需保留历史报告解释所需版本信息，不静默重算旧结论；不兼容输入明确拒绝或经显式适配生成新修订。这里的恢复指自身调查记录恢复，不是执行业务数据库回滚。

## 方案取舍与追踪

- 采用统一调查契约加场景分析器：复用证据、图和报告，同时允许数据库与语言迁移具有不同语义。
- 不采用扩大 ChangeSpec 枚举来承载全部调查：现状调查没有变更目标，整库/跨语言调查也不对应单一节点。
- 不采用让模型直接判断迁移安全：无法提供稳定规则、完整覆盖及行为等价性证明。
- 不在本次引入框架迁移、执行沙箱或业务迁移引擎。未来的只读验证工具也需单独界定，当前仅给验证建议和审阅外部证据。

| 需求 | 设计 |
| --- | --- |
| INV-REQ-01 | INV-D01 |
| INV-REQ-02 | INV-D02 |
| INV-REQ-03 | INV-D03 |
| INV-REQ-04 | INV-D04 |
| INV-REQ-05 | INV-D05 |
| INV-REQ-06 | INV-D06 |
| INV-REQ-07 | INV-D01、INV-D07 |
| INV-REQ-08 | INV-D02、INV-D06 |
| INV-REQ-09 | INV-D02、INV-D04、INV-D07 |

## 2026-09-24 平台级目标设计（INV-D08–12，待实施）

目标流程：代码来源/业务数据源/自然语言目的 → 技术栈探测与必要澄清 → 受控采集和来源快照 → 语义与依赖关联 → 分场景规则及影响分析 → Agent 追查缺口 → 有证据的改造方案与验证清单。复用现有 Java 核心、报告封存和 Agent 循环；新增能力按模块适配，不以更换前端框架作为前置条件。

### INV-D08：统一来源、项目档案及组合目标（INV-REQ-10、11、14）

未来契约应独立版本化，保留当前 `archlens.agent.v1` 和既有报告的读取边界。现有单一 sourceProfile/targetProfile 及 files 字段无法表达混合解决方案，不将新字段静默塞入旧契约，也不改变历史 C# version 字段的语言版本含义。

| 拟议模型 | 内容及约束 |
| --- | --- |
| CodeSourceRef | 本地授权根目录，或受控获取的 Git 来源及固定提交；排除清单、采集范围、文件哈希和来源变化记录；不执行 Git 钩子或项目脚本 |
| BusinessDataSourceRef | 产品、连接配置引用、库/schema 与对象范围；密钥由后端受控存储读取；与 ArchLens 自身存储配置分离 |
| ProjectProfile | 每个项目的语言、平台家族、目标框架、SDK、应用类型、依赖、数据访问和宿主；字段附证据、声明/已解析/未知状态 |
| TargetEnvironment | Java 或保留/升级 .NET 的目标平台，KingbaseES 版本/模式/配置，以及 CPU、OS、中间件和组件约束；不假定所有目标都必填 |
| InvestigationGoal | 从自然语言抽取的多个关联目标、用户约束、不变量和阻塞问题；用户明确声明优先，不确定目标先澄清 |

数据库连接输入经本机受控接口交给后端管理，凭据不进入浏览器持久化、报告、普通运行上下文或模型。先自动探测现状，再仅询问影响当前判断的必要条件；没有目标连接时允许使用用户声明的版本/模式，但保留未探测状态。

### INV-D09：.NET 解决方案发现及语义适配（INV-REQ-11、12）

将当前 discover-csharp 后续扩展为平台发现能力。识别解决方案及 `.csproj`、`.vbproj`、`.fsproj` 等项目声明，兼容旧式与 SDK 风格；读取 `TargetFrameworkVersion`、`TargetFramework`、`TargetFrameworks`、`global.json`、`packages.config`、NuGet 声明、Directory.Build/Packages、应用与部署配置等已授权来源。发现项目类型不代表其语言或应用框架分析器已实现。

按项目及目标框架分别记录配置，支持同一解决方案混合 .NET Framework、.NET Core、现代 .NET 与 .NET Standard 类库；SDK、语言版本、运行平台和 API 规范分别表达。静态声明只证明声明内容；Import、条件、动态属性、外部引用及生成文件不能据此推导有效构建结果。

拟增加独立 .NET/Roslyn 分析进程，通过版本化事实协议与 Java 核心交互；C# 与 VB.NET 逐项声明语法/符号/框架覆盖，F# 等需要独立解析器。只对受控源码、引用程序集及明确编译条件进行解析和绑定，不加载项目提供的分析器、源生成器或运行目标程序集。缺少引用时输出诊断并降低绑定覆盖，不自动执行 MSBuild、restore 或被分析项目来补齐上下文；后续若需构建求值应单独定义执行边界。

应用框架适配器逐步覆盖 Web Forms、ASP.NET MVC/Web API、ASP.NET Core、WCF、Windows 服务、WinForms/WPF 和类库。数据访问适配器关联 ADO.NET、EF6、EF Core、Dapper、驱动、SQL 与程序对象。程序集缺源码、反射、动态 SQL、预处理条件和本机组件分别形成缺口；候选引用不能直接写为确定依赖。

### INV-D10：业务数据库采集与版本规则（INV-REQ-13、14）

按 Oracle、MySQL、KingbaseES 注册独立只读连接器，声明支持的版本、对象类型及最小元数据权限。采集表/列/约束/索引/视图/序列、可见的过程/函数/包/触发器/同义词定义，以及判断兼容性所需的版本和配置。查询来自固定审核模板并参数化，不允许模型提交 SQL，不调用业务过程或读取业务行。

元数据采集记录产品版本、对象标识、原始定义哈希、采集时间、权限/对象覆盖和一致性声明。业务源发生变化或不能获得一致视图时保留相应缺口，不把多个来源的采集时间相近等同于原子快照；取消、超时及单对象失败应可追溯。

KingbaseES 目标规则以源产品/版本、目标具体版本、兼容模式和必要配置匹配，独立维护官方依据、条件及正反例。现有 PostgreSQL 规则不可直接当作金仓结论；Oracle 与 MySQL 来源分别验收。信创规则按 CPU/OS/运行时/数据库/中间件/组件组合检查平台依赖；现状识别不能证明产品认证或运行性能。

### INV-D11：跨来源依赖与改造报告（INV-REQ-15）

新增框架和 SQL 绑定器将接口、服务方法、数据访问、SQL、表/列、程序对象与配置关联为可追溯路径。依赖方向沿用 dependent → dependency，问题影响沿反向传播；每条事实边包含来源、位置、哈希、解析器版本和绑定依据。候选、动态及不支持路径单列，路径不存在但覆盖不足时不能判断无影响。

报告按项目/模块和组合目标组织现状、发现、直接/间接影响、证据路径、替代方案、前置条件、实施顺序及验证清单。共享发现合并展示但保留各目标的规则与条件，目标间冲突进入待决项。工作量只在有依据时给区间并列假设；不按代码行数自动生成精确人天。外部测试结果保存来源、环境和是否独立核实，不能升级为引擎已验证事实。

### INV-D12：调查循环、覆盖与增量生命周期（INV-REQ-16）

后续工具可受控发现项目、读取授权元数据、查询符号/依赖、读取证据、运行注册规则和检索版本材料。工具只接受服务端登记的来源引用及有界查询参数；模型不得自选新路径、数据库连接或任意命令。新增源码语义分析在本地执行；模型继续使用当前白名单投影，若需要原文片段或本地模型策略，应另行明确数据范围。

Agent 维护待调查问题及其证据状态，按缺口继续查询；遇到无进展、重复查询、取消或预算耗尽终止并保留原因。检索到的文档用于引用或规则草案，未经版本核对和样例验证不自动成为正式规则。

分别统计已发现、纳入、采集、解析、绑定、规则检查与未覆盖的文件/对象，说明分母和排除项；覆盖、风险与置信度继续分别表达。缓存依赖来源哈希、目标条件、解析器和规则版本；变化使受影响结论失效并创建新修订。持久任务、检查点和恢复按阶段推进，保留取消与迟到写入隔离；真实项目规模及性能阈值在后续选定样本后确定。

### 新增需求—设计追踪及依据

| 需求 | 设计 |
| --- | --- |
| INV-REQ-10 | INV-D08 |
| INV-REQ-11 | INV-D08、INV-D09 |
| INV-REQ-12 | INV-D09 |
| INV-REQ-13 | INV-D10 |
| INV-REQ-14 | INV-D08、INV-D10 |
| INV-REQ-15 | INV-D11 |
| INV-REQ-16 | INV-D12 |

本次设计参考（2026-09-24 查阅）：[Microsoft 平台区分](https://learn.microsoft.com/en-us/dotnet/standard/choosing-core-framework-server)、[目标框架](https://learn.microsoft.com/en-us/dotnet/standard/frameworks)、[.NET Standard](https://learn.microsoft.com/en-us/dotnet/standard/net-standard)、[Roslyn 编译器模型](https://learn.microsoft.com/en-us/dotnet/csharp/roslyn-sdk/compiler-api-model)、[KingbaseES MySQL 兼容性](https://help.kingbase.com.cn/v8/development/develop-transfer/kes-vs-mysql/overview.html)、[Oracle 至 KingbaseES 迁移](https://help.kingbase.com.cn/v9/PDF/Oracle%E8%87%B3KingbaseES%E8%BF%81%E7%A7%BB%E6%9C%80%E4%BD%B3%E5%AE%9E%E8%B7%B5.pdf)。这些资料用于确定分析维度；实际发布规则时仍须核对选定版本，不把文档概述当作用户项目兼容证明。

## 2026-09-17 实施补充：统一调查及自身存储切片

- `InvestigationRequest` 独立于旧 OfflineRequest，使用 `archlens.investigation-request.v1`。调查问题、源/目标 profile、约束、不变量、显式文件和预算均严格反序列化；Case/Run/revision 由存储层分配，前端草稿没有自动转换入口。
- `InvestigationEngine` 仅采集明确文件清单、原始 SHA-256/时间/字节范围，检查越界、缺失、UTF-8、预算及漂移。列场景引用旧请求并复用已验证分析核心；其他场景输出未知与澄清，未注册兼容规则。模型上传范围未扩大。
- `InvestigationReport` 包含来源、覆盖缺口、澄清、UNKNOWN 兼容发现、建议及可选列分析。引擎版本/请求/来源哈希/覆盖缺口形成输入指纹；默认结果仍为 PARTIAL。
- PG 的 `archlens` 专用 schema 是权威存储，保存不可变报告、校验哈希及图 JSON，Case 行更新串行分配修订。独立 checkpoint 留存已采集清单；最终封存校验 epoch、租约、状态、请求哈希。取消/失败隔离迟到写入；不提供修改已封存报告的接口。
- Neo4j 是按 runId 隔离的可重建投影，使用固定标签、参数化语句、唯一约束和单事务写入。PG 中 PENDING/READY 记录投影进度，失败不删除报告；重试同 run 幂等，不保证跨库原子提交。
- 迁移仅作用于 ArchLens 自身存储。管理员可预建 `archlens` schema，使应用账号无需数据库 CREATE 权限。配置位于被忽略的 `.local`，Windows 密码通过当前用户 DPAPI 加密；不出现在调查请求或模型请求中。
- CLI 增加调查、存储初始化/检查、封存、状态、导出、取消、超期恢复及投影重试。没有新增生产 HTTP 服务、OIDC、递归扫描、在线业务库采集、语言迁移规则或完整模型编排。时间预算为协作式，单个解析调用暂不能强制中断。

## 2026-09-17 追加：INV-D03/04/05 场景规则设计落地

上节是存储切片当时状态。其后新增 `investigation/rules/`，由 `ScenarioRules` 按明确产品及版本分派，`RuleCatalog` 固定 22 条规则元数据。SQL 使用有界特征词法器和有限列声明解析，C# 使用保守词法特征调查，Java 复用 JavaParser AST；不新增第三方依赖。官方依据、复核日期、源码事实与条件进入同一发现；矩阵外请求和来源版本冲突不能套用规则。

`InvestigationEngine` 缓存采集时的 `SourceText`，规则只分析该缓存；规则后仍做来源重查。漂移/取消/超时使发现撤销，来源和缺口保留。报告升级到 `archlens.investigation-report.v2`，Finding 增加 subjectId、summary、内嵌 RuleBasis/Evidence、建议和声明/候选影响项；历史 v1 新字段缺失时为空，PG 不改写旧封存 JSON。规则元数据加入输入指纹。

SQL/C#/Java AST 发现具有 token/AST 级 UTF-8 原文定位，XML/计划仍使用整文件位置。Evidence 引用来源清单 ID/hash，不能由描述文本生成。重构只接受显式 `archlens.refactor-plan.v1` 前后文件对，路径必须已在 files 中采集；平坦类的原始类型方法描述符做局部兼容判断，方法体/状态不同单列 UNKNOWN，同名调用仅为 CANDIDATE，绝不据此创建事实图边。

`rules` CLI 导出本地注册规则。新场景报告沿用既有 PG 封存/导出流程，因无新绑定图，Neo4j 为 NOT_APPLICABLE；旧列场景的投影机制不变。模型仍只发送描述；全部调查仍 PARTIAL。范围、限额和失败行为详见 [多场景说明](../../docs/scenario-rules.md)。

具体契约、状态、命令、失败行为及限额见 [调查与双存储切片](../../docs/investigation-storage.md)。这是一份可运行子集设计，不将目标设计中的全部状态或 INV 任务宣称为已实现。

## 2026-09-18 追加：INV-D06 模型循环实现

`AgentOrchestrator` 使用 `AgentModel` 接口；生产 `DeepSeekAgentModel` 每轮接收一个原生工具调用，宿主验证后执行并回传结果，继续下一轮。注册工具为 propose_target、list_rules、run_analysis、read_evidence、ask_clarification、finish。模型不接触路径选择、任意命令、SQL、存储票据或事实构造接口。

契约 `archlens.agent.v1` 将原始请求、解析目标、机器调查、未核实解释和哈希审计分开。目标字段必须与用户显式声明一致，未声明产品/版本需出现在目标原文或先澄清。选择规则只能来自本地适用目录，遗漏规则显式报告覆盖缺口。模型解释仅校验结构、引用与 outcome，不宣称语义已被证明。

NEEDS_CLARIFICATION 报告保存稳定问题 ID；Answers 绑定父报告 canonical 哈希和完整问题集合。恢复时重新读取授权文件，最多八修订。PG 复用现有 JSONB 报告封存、租约和 epoch；最新修订条件更新与创建 Run 同事务，拒绝重复恢复。数据库 PARTIAL 与报告 NEEDS_CLARIFICATION 分层表达，无 schema 迁移。

每轮模型调用受取消轮询与总时间预算约束；工具、模型轮数、分析次数及上下文大小均有上限。模型失败保留确定性降级结果；最终来源漂移撤销当前发现与解释。对外投影通过白名单构造，不直接序列化 Request 或完整报告；只包含用户输入、通用规则及匿名引用。详细限制见 [Agent 说明](../../docs/agent-orchestration.md)。

## 2026-09-20 网页适配设计落地

原生工作台通过同源 Node 接口调用 WebAgentCli，再复用 AgentOrchestrator 与 PG；JSON 行协议显式 UTF-8。PG 保存权威状态和不可变修订；恢复请求从父报告读取，回答绑定父报告哈希，旧回答返回冲突。授权根目录只保存在本机上下文，不进入模型。页面轮询真实状态，历史修订只读，报告下载复用封存内容。

GET /api/investigations/example 提供仓库合成样例的实际路径，支持直接复现。服务最多并发两次调查、六次查询，仅监听本机；原草稿页和解析接口保留。INV-D01、05、06、07 的该子集已完成真实浏览器、PG 和模型验证，见 [验收证据](../../docs/verification-web-2026-09-20.md)。

## 2026-09-22 C# 项目分析设计

INV-D02/03/05/06：Node discover-csharp 仅递归发现授权根目录的候选 .cs/.csproj 和 Directory.Build/Packages 文件，不求值工程。它返回可编辑的显式文件清单、排除项与范围提示；Java 重新采集并绑定 SHA-256。CSharpProjectRules 使用禁用 DTD、外部实体和外部 schema 的 XML 解析器，只读取直接 PropertyGroup/ItemGroup 声明，不跟随 ProjectReference/Import 读取额外文件。

规则目录升级为 1.1（25 条），增加 CS_PROJECT_PROFILE、CS_PROJECT_DEPENDENCY、CS_PROJECT_REFERENCE；与已有四条 C# 特征规则共同参与 Agent 选择。项目 XML 证据绑定整个声明文件，页面明确其粒度。声明引用仅核对目标是否在已采集来源中，不创造调用图。语言版本冲突撤销本次规则发现；条件声明不冒充有效版本。模型投影继续排除摘要、包名、路径及源码。C# sourceProfile.version 仅代表语言版本；网页和模型提示明确与 TFM 区别。

## 2026-09-24 实施追加：.NET 声明清单与本地预览

`DotnetInventoryAnalyzer` 仅读已采集的 SourceText 缓存；解析工程/解决方案、共享构建声明和应用配置结构，发布独立版本化 `DotnetInventory`。平台与语言版本分开，所有引用是声明，语义绑定分母明确；条件/缺失引用/未求值共享配置保留缺口。引擎重查来源后才能发布；Agent 后续漂移/取消也撤销清单。

含清单的报告使用 v3，无清单时保留 v2 字段形状；旧封存报告不改写。Agent 仅增加匿名项目数量，平台源版本可为空，目标版本澄清期间可保留独立现状清单。`WebAgentCli` 的 `preview-dotnet` 在存储与模型初始化之前执行确定性现状分析，预览不上报虚假 Run，不写历史。生产 Node 接口固定 CURRENT_STATE、限制预算与并发，页面独立展示可下载预览及输入变化后的失效状态。

该交付对应 INV-D08/09 子集，不代表数据库元数据、语义解析或完整组合目标完成；范围与限额见 [.NET 平台说明](../../docs/dotnet-platform.md)。


## 2026-09-25：业务数据库首批交付

按用户选择先实现 MySQL，暂不实施任意数据库互转。已完成业务数据库配置、目标环境声明、MySQL 8.x 连接测试、固定模板只读元数据采集，以及 .NET 清单与数据库结构合并报告。无模型/自身存储也可本地预览；正式调查复用采集器。业务凭据独立于持久化请求，恢复需重填并核验数据源指纹。

INV-REQ-10/13 和对应数据源任务仅交付此子集：200 表/视图、字段/索引列/约束列各 5000 条、总文本 500000 字符，超时与权限可见性显式报告。目标环境尚未评估，业务程序对象正文、跨源依赖绑定、Oracle/KingbaseES 连接器及迁移计划继续待办。不要将此前日期的“在线采集未实现”作为本批当前状态。

实现及使用见 [业务数据库说明](../../docs/business-database.md)，实际测试结果见 [本批验收](../../docs/verification-business-database-2026-09-25.md)。旧验收记录保留原日期。


### 2026-09-25 同日增量：三种范围与 MySQL 扩展结构

工作台显式选择仅代码、仅数据库或联合调查，并按范围校验目录、文件和连接。数据库清单 v2 增加默认值/注释/生成表达式存在标记，CHECK、视图、程序对象及参数的可见结构；定义原文不保存。真实 MySQL 8.4 合成库与页面联调见 [业务数据库验证记录](../../docs/verification-business-database-2026-09-25.md)。Oracle/KingbaseES 连接器、代码与数据库依赖绑定、定义语义分析及迁移建议仍待实施。
