# ArchLens

2026-09-27 增量：联合报告增加 C#/Java 方法及 MyBatis 语句的静态访问证据路径，区分直接 SQL 实参、同方法候选和未知；详见 [业务数据库与访问路径](../docs/archive/2026-09-29/ArchLensService/docs/business-database.md)。
2026-09-26 更新：[业务数据库与目标环境](../docs/archive/2026-09-29/ArchLensService/docs/business-database.md)。已支持 MySQL 8.x 与 Oracle、人大金仓 KingbaseES、达梦 DM 的连接及只读结构采集，并输出代码 SQL 对象名的静态候选关联；后三种数据库仍需厂商 JDBC 驱动及实库验证。目标环境仅记录声明，运行时依赖确认和迁移评估仍待实现。
当前文档整理完成：2026-09-30；源码总结基线：2026-09-29。[文档导航](../docs/README.md) 汇总两份设计基线、新 specs、运行入口与历史归档。下文指向 `docs/archive/` 的文件为保留原日期的历史说明；历史测试结果不代表本次验证。

下一阶段按 [重建后的迁移设计规格](specs/implementation/requirements.md) 实施。现有基线包含 **0.1 确定性列分析**，以及 **统一调查与自身存储切片**：显式文件清单/列分析 → 证据报告 → PG Case/Run/修订 → Neo4j 图投影。已加入有限的本地 Roslyn 符号取证与 Agent 迁移草案；完整项目绑定、正式 MigrationPlan v1 封存和隔离验证尚待开发；LangGraph 已交付固定只读工具 P0 原型，当前不能作为生产系统完整扫描或上线审批工具。

新入口、凭据配置、数据库权限、失败恢复和验收命令见 [调查与双存储切片](../docs/archive/2026-09-29/ArchLensService/docs/investigation-storage.md)。本机网页已加入 Agent 提交、澄清、状态、证据报告和历史修订适配，2026-09-20 已实测 MySQL SQL 调查网页闭环、澄清及重启回读，见 [验证记录](../docs/archive/2026-09-29/ArchLensService/docs/verification-web-2026-09-20.md)；运行方法见 [前端说明](../ArchLensClient/README.md)。

现已新增 [多场景兼容规则与调查](../docs/archive/2026-09-29/ArchLensService/docs/scenario-rules.md)：25 条固定版本规则覆盖 MySQL/Oracle → PostgreSQL、C# → Java、Java 重构，以及 Spring Boot/JDK/HttpClient 升级的明确子集。提供七组可运行样例、带原文位置及官方规则依据的 v2 报告，并可在 PG 封存；关键代码含中文注释。

后续目标为“代码来源 + 按需业务数据库连接 + 目标环境 + 自然语言目的”的自主迁移设计，覆盖 .NET Framework/.NET Core/现代 .NET 到 Java、数据库迁移和信创组合改造。现有 .NET 声明盘点、三种分析模式、业务库采集与候选关联纳入复用基线；源码、规则覆盖与真实验证差异见 [项目总结](../docs/design/ArchLens-现有项目总结-2026-09-29.md)。

## 通用模型配置（2026-10-13）

### 本地 C# 语义取证（初版）

先安装 .NET 10 SDK，再从 `ArchLensService/` 运行 `dotnet build analyzers/dotnet/ArchLens.Semantics.csproj -c Release`。Agent 的 `inspect_dotnet_semantics` 只向此本地进程传入本次显式授权的 `.cs` 与 `.csproj` 原文及哈希；工具读取无条件的直接工程引用和 SDK 隐式 using，并按工程使用 Roslyn 绑定类型、方法、调用和对象创建，输出符号、绑定状态及 UTF-8 字节/Unicode 行列位置。不加载解决方案、不还原或构建被分析项目，也不运行其 analyzer、source generator 或脚本。工具本身引用 SDK 自带的 Roslyn 程序集，没有额外 NuGet 包。

默认查找 `analyzers/dotnet/bin/Release/net10.0/ArchLens.Semantics.dll`。部署到其他位置可设 `ARCHLENS_DOTNET_SEMANTIC_TOOL` 为 DLL 绝对路径，`ARCHLENS_DOTNET_HOST` 为 dotnet 可执行文件路径。未安装或未构建时报告 `ROSLYN_TOOL_NOT_BUILT` / `ROSLYN_TOOL_UNAVAILABLE`，不生成虚假的语义事实。条件工程配置、实际编译项、未明确声明的 C# 语言版本和生成代码仍未求值；运行时程序集只提供有限的宿主框架绑定，不能当作项目目标框架或第三方依赖验证。Agent 基于匿名编号组织 `UNVERIFIED_PROPOSAL` 迁移步骤，完整事实仅留在本地报告；该草案不是正式 MigrationPlan v1，也不声称目标兼容。

离线烟测可准备 UTF-8 JSON 数组文件，列出相对项目根目录的 `.csproj` 与 `.cs` 路径，然后在 `ArchLensService/` 运行 `java -cp target/archlens-0.1.0-SNAPSHOT-cli.jar io.archlens.cli.DotnetSemanticsCli <项目根目录> <files.json>`。输出为只读 JSON；它不初始化 PG/Neo4j，也不调用模型。

首页以代码库路径、可选业务数据库连接、文字目的与通用约束提交，`target=null`。Node 自动发现清单；Java 的 `RepositoryInspector` 在本地提取技术声明，`AgentOrchestrator` 根据目标调用工具或委派子 Agent。`repositoryInventory`、`subtasks`、`dotnetSemantics` 和 `migrationPlan` 是报告可选字段，旧报告缺省时不序列化，保持原 canonical 哈希。旧入口继续使用 Java Agent；独立 P0 LangGraph worker 通过 CLI 显式入队，尚未接入网页。

设置后端进程环境（密钥用环境注入或启动脚本隐藏输入）：

| 变量 | 含义 |
| --- | --- |
| `ARCHLENS_LLM_PROVIDER` | 供应商标签，可省略为 `compatible` |
| `ARCHLENS_LLM_ENDPOINT` | 完整 Chat Completions URL，例如服务商的 `/v1/chat/completions`；不自动追加路径 |
| `ARCHLENS_LLM_MODEL` | 该端点提供的模型 ID，支持命名空间与版本标签 |
| `ARCHLENS_LLM_API_KEY` | 服务端 API 密钥；回环地址的本地模型允许无密钥 |
| `ARCHLENS_LLM_DISABLED` | `true` 禁用所有模型，便于离线回归 |

要求服务兼容 Chat Completions `messages/tools/tool_calls`；仅支持每轮一个 function 工具调用，不支持任意供应商原生协议。远程地址要求 HTTPS，本机 `localhost/127.0.0.1/::1` 允许 HTTP；拒绝重定向、URL 内凭据、查询参数和 fragment。超时、鉴权、格式错误返回脱敏错误码，不静默切回其他模型。通用配置优先于旧 `DEEPSEEK_API_KEY/DEEPSEEK_MODEL`，旧配置仍通过兼容适配器使用原默认端点及模型。

主循环预算仍最多 16 次模型调用、24 次工具调用、120 秒。模型可以直接分析一个目标，或 `delegate_analysis` 顺序委派最多两项子调查；每项最多 6 次模型调用，不递归委派，合计计入父预算。子报告不代表新增语义分析能力；未知规则、源版本缺口、取消、漂移和澄清状态均保留。前端不配置这些内部流程。

只有白名单技术名称、字面版本、匿名规则摘要及用户目标/约束/回答进入模型。来源路径、源码、项目名称、业务库对象名和连接凭据保留在本地。Maven 仅解析直接声明和一层本地属性；不执行构建、下载父 POM、解析动态 Gradle、推断 C# 默认语言版本，或把混合版本归为统一事实。

## 运行

2026-09-18 新增 [模型 Agent 编排层](../docs/archive/2026-09-29/ArchLensService/docs/agent-orchestration.md)：原生工具调用循环、目标解析、规则选择、确定性分析、可恢复澄清、证据解释和 PG 封存。入口为 `scripts/agent.ps1`，网页通过 `WebAgentCli` 复用编排与 PG 存储；[本次验证](../docs/archive/2026-09-29/ArchLensService/docs/verification-agent-2026-09-18.md) 区分离线测试、真实模型调用及数据库联调状态。

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
- `investigation/rules`：明确来源清单、版本限定规则、覆盖缺口和 v2 证据发现报告；`investigation/dotnet` 提供带来源哈希的 v3 平台声明清单。
- `storage`：ArchLens 自身 PG Case/Run/修订、租约和不可变封存；存在已绑定事实图时投影 Neo4j。
- `migration` / `migration-web`：本地来源登记（相对清单、SHA-256、授权/凭据引用、基础画像观测）及迁移运行创建/查询/取消 API；当前运行保持 `QUEUED`，尚未接入取证 worker 或模型。

示例的 `event_id → global_id` 是物理列改名，真实 XML 仍读取 `event_id`，因此 Mapper 为 `YES`。没有源码字段映射的对象上游只能是 `UNKNOWN`。拟议兼容条件只可形成 `UNKNOWN + conditionedOutcome=NO`；当前尚无验证快照引擎，`VERIFIED` 和 `APPLIED` 输入会明确拒绝。

## 支持边界

catalog 中的标识符必须使用数据库实际大小写，`defaultSchema` 显式指定。catalog 是用户提供的离线断言，未与真实 PostgreSQL 校验，因此报告包含 `OFFLINE_CATALOG_UNVERIFIED`；它不是已发布数据库快照。

动态 XML、`${...}`、参数化 SQL、带参/重载/继承方法、CTE、JOIN、子查询、复杂表达式、结果映射和 Java/JSON/Vue 字段链不在首批支持范围。遇到这些输入产生诊断，可能保留明确的局部引用，但不声称覆盖完整。首批 XML 拒绝 DOCTYPE/实体声明（包含常见 MyBatis 外部 DTD 声明）；不会访问外部 DTD。不要把这一限制当作 MyBatis XML 语法错误。

源码必须为 UTF-8；采集单文件最多 5 MB；路径只能指向输入根目录内的真实文件。旧列分析证据是整文件范围；新规则的 SQL/C# token 和 Java AST 证据有原文 UTF-8 字节及 Unicode 码点行列，XML/比较计划仍为整文件。行列从 1 开始、end 不包含。新规则解析另有更小的文件/token/发现数量限额，详见多场景说明。

风险 B/K/D 都保留来源；业务关键性和恢复信息缺失时使用区间。报告没有生产权限系统，不应通过 HTTP 对外暴露这个 CLI。PG/Neo4j 和 Case/Run 用于 ArchLens 自身存储；业务库采集独立于自身存储，已包含四产品的采集代码，但后三种产品尚待实库验证。尚未提供 Spring Boot API、Vue 界面或生产身份鉴权；当前服务仅限本机单用户场景。可配置模型分为旧提议接口与 Agent 工具编排；前者生成 UNVERIFIED_PROPOSAL，后者保存确定性调查和单列的 MODEL_EXPLANATION_UNVERIFIED 解释。

## 后续实施与验证

[实施任务](specs/implementation/tasks.md) 和 [验收记录](specs/implementation/check_list.md) 跟踪已完成的子集与后续工作。单元/集成测试验证本批能力，不等于新迁移设计的全部验收。历史总体设计中的黄金样例数量与旧验收保留在归档，新阶段按当前 check_list 明确范围及证据。当前已有报告/图 JSON 持久化、运行租约、报告封存及有限场景规则；完整项目采集、业务元数据快照与更广泛迁移语义仍需逐项实施。

2026-10-02 P0 新增只读请求契约探针：`java -jar target/archlens-0.1.0-SNAPSHOT-cli.jar migration-request-check examples/migration/request-code-only.json`。它对 [MigrationRequest v1 Schema](src/main/resources/schema/archlens.migration-request.v1.schema.json) 对应的 Java 对象执行严格 JSON/语义校验，输出 canonical 请求哈希、模式和 A0/A1/A2 等级；不会启动调查或保存运行。`sourceRef`、`locatorRef`、`credentialRef`、`authorizationRef` 是未来登记服务解析的标识，不是路径、连接串或明文秘密。离线结构必须标记 `offlineUnverified=true`；在线业务库必须提供凭据引用。V3 必需项要求 `ISOLATED_VALIDATION` 策略，但策略声明本身不代表测试环境已经就绪或已执行验证。未决目标字段留在 `unresolvedFields`，后续完成检查仍需处理。

同日继续新增 [方案 v1 Schema](src/main/resources/schema/archlens.migration-plan.v1.schema.json)、证据、验证记录与事件 Schema，以及只读命令：`java -jar target/archlens-0.1.0-SNAPSHOT-cli.jar migration-plan-check examples/migration/request-code-only.json examples/migration/evidence-code-only.json examples/migration/plan-code-only.json`。命令检查请求 hash、同一 run/revision/snapshot 的证据、来源范围、计划引用、工作项依赖和必填结构，输出 `STRUCTURE_VALID` 与内容哈希。`planHash` 对方案 JSON 内容计算，不是方案内部可伪造字段。示例证据/计划是合成输入，工具尚未核实其来源真实性；结构通过不等于方案完整、V1/V2/V3 已通过或真实迁移可行。验证记录的 `NOT_RUN` 与外部导入状态保持单独表示。完整错误目录、来源/凭据登记、迁移 API、封存门槛仍待开发。

P0 的 [TypeScript 契约运行时](agent-runtime/README.md) 已补齐五份 Schema 的 Node 校验和与 Java 共用的六组黄金哈希。它单独要求 Node 24+，从 `ArchLensService/agent-runtime` 执行 `npm ci --ignore-scripts && npm test`；同时提供固定 list_rules 的持久化 worker；跨文件真实性及权威引用校验仍由 Java 负责。完整错误目录、来源/凭据登记、迁移 API、正式方案封存门槛仍待开发；P0 调度及 PARTIAL 回执已实现。

同日继续为 Node 文件输入加入重复 JSON 键拒绝（包含转义后同名），并固定一份历史 Agent 报告的原字节与 canonical 哈希回读基线；见[输入边界与旧报告验证](../docs/verification/migration-input-legacy-2026-10-02.md)。该基线只覆盖一个历史样例，尚不代表所有旧版本报告/API/CLI 的部署回归。

P0 存储继续追加 [V002 迁移任务表](src/main/resources/db/V002__migration_runtime.sql)：独立 migration Case/Run、任务/调用/产物/事件/澄清/验证作业/认可 checkpoint 表和独立 `archlens_checkpoint` schema。`PgInvestigationStore.initialize()` 在同一个事务及 advisory lock 下核对 V001，并依次安装或核对 V002、[V003 提交键表](src/main/resources/db/V003__migration_submission.sql)、[V004 小型工具结果表](src/main/resources/db/V004__migration_tool_result.sql)和[V005 worker/预算表](src/main/resources/db/V005__migration_worker.sql)；运行 `storage-init` 需要有创建这些对象的数据库权限。V001–V004 原文件与旧 investigation 表未修改。存储结构验证见[临时 PostgreSQL 记录](../docs/verification/migration-storage-schema-2026-10-02.md)。

[PgMigrationRunStore](src/main/java/io/archlens/storage/PgMigrationRunStore.java) 已实现新 Case 的幂等入队、单 worker 领取、PG 时钟续租/过期接管和重复取消回执。提交键由宿主生成，同键不同请求拒绝；取消在同一事务写受控事件并使旧 epoch 失效。它已通过本机运行桥接接入 P0 worker，尚未接入新网页/API，不能单独完成迁移调查。P0 checkpoint 认可和 PARTIAL 封存已实现；暂停、澄清修订和正式方案封存仍待实现；见[运行状态验证](../docs/verification/migration-run-lifecycle-2026-10-02.md)。

[PgMigrationToolStore](src/main/java/io/archlens/storage/PgMigrationToolStore.java) 新增 MIG-T05 的首个真实只读工具 `list_rules`：宿主传入任务和动作身份，先在 PG 登记调用并按请求中的 `allowedTools` 与 `maxToolCalls` 校验，再派发既有 `RuleCatalog`。成功结果原字节及 SHA-256 持久化；重试复用 invocationId 和结果。登记、派发、发布都要求当前 Case 修订及有效 worker 租约，取消/接管后的旧票据不能发布。当前只支持无参规则目录与 64 KiB 以内的 PG 结果，目录 hash 仅代表规则目录快照，不代表代码或业务数据库快照；见[工具调用验证](../docs/verification/migration-tool-ledger-2026-10-02.md)。

2026-10-03 新增 [`migration-tool-bridge`](src/main/java/io/archlens/cli/MigrationToolBridgeCli.java) 与 [Node 进程适配器](agent-runtime/src/tool-bridge.ts)：受信任宿主通过 UTF-8 JSON Lines 的 `REGISTER`、`EXECUTE` 两步调用上述固定工具。Java 每次核对请求版本、工具版本、当前租约和账本；成功响应只含 invocationId、哈希和 PG artifact 引用，不向 Node 传规则正文。单条请求和响应各限 16 KiB，Node 子进程超时上限 30 秒、参数数组启动且不使用 shell，错误码和重放分类不附带原始输入。该入口仍需本机 PG 配置和已领取的 run，尚无用户 API、通用能力票据、外部授权登记或调度器；见[跨进程验证](../docs/verification/migration-tool-bridge-2026-10-03.md)。

同日继续增加 `INSPECT` 与 `resumeToolInvocation`：接管 worker 先读取 PG 中的 `REGISTERED`、`DISPATCHED` 或 `SUCCEEDED` 状态；已完成时回读同一 artifact，未完成时才执行固定只读工具。专项测试在登记事务和派发事务分别提交后强制结束子进程，再以新 epoch 恢复；完整图 checkpoint、通用工具与调度仍未实现。见[故障恢复子集验证](../docs/verification/migration-tool-recovery-2026-10-03.md)。

固定依赖及来源见 [技术基线](../docs/archive/2026-09-29/ArchLensService/docs/technical-baseline.md)。现有 `qa/` 是设计文档制作和排版验证资料，不是产品代码。

C# 项目分析入口、声明范围及边界见 [C# → Java 分析](../docs/archive/2026-09-29/ArchLensService/docs/csharp-java.md)。

## 当前配置与资源位置

- 构建和直接运行 CLI 均从 `ArchLensService` 目录执行；Linux 使用已安装的 JDK 21 / Maven 3.9.x：`mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=.local/m2 verify`。项目已装本地工具链时，可在项目根目录先执行 `source .local/toolchains/activate.sh`。PowerShell 脚本仍按 Windows 工具名运行。
- 正式网页调查使用后端 `ARCHLENS_PG_URL`、`ARCHLENS_PG_USER`、`ARCHLENS_PG_PASSWORD`，当前配置校验还要求提供 `ARCHLENS_NEO4J_URI`、`ARCHLENS_NEO4J_USER`、`ARCHLENS_NEO4J_PASSWORD`、`ARCHLENS_NEO4J_DATABASE`。无图网页调查可以不连接 Neo4j，但七项配置仍须完整且地址格式合法，否则返回 `STORAGE_CONFIG`。`java -jar target/archlens-0.1.0-SNAPSHOT-cli.jar storage-check` 与 `storage-init` 都是双库命令，会分别检查或初始化 PG 与 Neo4j；不能作为仅 PG 的启动命令。部署者在专用存储准备就绪后执行，启动网页不会自动初始化。
- Windows 可用 `scripts/configure-storage.ps1` 的隐藏输入保存本机配置；`start.ps1` / `storage.ps1` 读取 `.local/storage.json` 与当前用户 DPAPI 加密的 `.local/storage.credentials.xml`。Linux 使用后端进程环境，不复制 Windows 密文。地址禁止内嵌凭据或查询参数。
- 模型由 `ARCHLENS_LLM_*` 后端配置，兼容旧 `DEEPSEEK_*`。网页本地预览无需模型及自身 PG，正式调查需要自身 PG；模型缺失可确定性降级。业务库与厂商驱动配置见 [前端说明](../ArchLensClient/README.md#业务数据库配置补充)。
- Java 回归报告现位于 [src/test/resources](src/test/resources/fixtures/agent-20260918/README.md)，不再读取文档归档。
- 打包后在 Windows 运行 `scripts/record-runtime.ps1` 刷新 [runtime/runtime-dependencies.json](runtime/runtime-dependencies.json)，再运行 `scripts/smoke.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME`。清单维护与第三方通知检查见 [runtime/README.md](runtime/README.md)；烟测写 `target/verification/` 新文件，旧记录留在历史归档。

测试命令 `mvn verify` 默认跳过需要显式开启的外部集成测试；不能据此宣称 PG、Neo4j、业务库或模型实测通过。当前依赖版本以 `pom.xml` 与本次构建清单为准，历史技术基线仅供追溯。

### 2026-10-19：P0 持久 worker

[运行说明](agent-runtime/README.md)包含版本、独立 checkpoint 角色、显式初始化/入队、调度与恢复步骤。[本轮验证](../docs/verification/p0-worker-2026-10-19.md)记录真实 PG、四个进程崩溃窗口、认可指针 CAS、预算、取消和不可变回执。成功仅表示 `PARTIAL / P0_SCOPE_ONLY`，不代表生成迁移方案或访问了请求中的来源引用。
