# ArchLens 项目规则

2026-09-27 增量：联合报告增加 C#/Java 方法及 MyBatis 语句的静态访问证据路径，区分直接 SQL 实参、同方法候选和未知；详见 [业务数据库与访问路径](docs/archive/2026-09-29/ArchLensService/docs/business-database.md)。
2026-09-26 更新：[业务数据库与目标环境](docs/archive/2026-09-29/ArchLensService/docs/business-database.md)。已接入 MySQL、Oracle、人大金仓和达梦的结构采集；后三种产品尚待实库验证。
维护日期：2026-09-29。本文定义项目工程约束，同时记录当前源码实现边界。当前需求与任务以重建后的 [specs](ArchLensService/specs/implementation/requirements.md) 为准；[历史归档](docs/archive/2026-09-29/README.md) 中的旧 INV 编号、旧说明和旧验证仅供追溯。历史验证不自动构成本次验收。

## 1. 产品定位与术语

ArchLens 用于理解源码与数据库结构之间的依赖，基于证据分析变更影响，为修改范围与回归测试提供依据。

下一阶段目标为：代码来源、按需业务数据库连接、目标环境及自然语言目的驱动的自主迁移设计。.NET 平台包括 .NET Framework、.NET Core、现代 .NET；语言、SDK、目标框架与应用类型分别识别。平台到 Java、数据库迁移与信创适配可以组合，信创不自动要求迁移 Java。现有 .NET 声明盘点、三种分析模式、四产品结构采集代码及静态候选关联作为复用基线；新语义、迁移方案、恢复协议和隔离验证按新 specs 分阶段实施。

| 术语 | 约定 |
| --- | --- |
| Investigation Case | 架构调查业务对象，属于总体设计；不使用 UI/UX Case 指代 |
| Snapshot | 固定分析上下文；用户提供离线标识不等于已从真实数据源采集并封存 |
| Evidence Chain | 可追溯至来源的证据链，不能由自然语言解释替代 |
| ChangeSpec | 与目标节点及固定图上下文匹配的结构化变更 |
| TargetProposal | 模型提议，仅表示对用户描述的解析结果，尚未验证 |
| Blast Radius | 变更的依赖传播范围；发现依赖不自动意味着必须修改 |
| changeRequired | `YES / NO / UNKNOWN`，与影响强度、风险和置信度分别表达 |

## 2. 当前实现与规划

| 层次 | 已存在的实现 | 尚未完成的能力 |
| --- | --- | --- |
| 前端 | 原生调查工作台：提交、澄清、状态、证据/下载、历史修订；旧草稿页保留 | Vue 应用、生产工作台；本机 SQL 闭环已实测，见 [验证](docs/archive/2026-09-29/ArchLensService/docs/verification-web-2026-09-20.md) |
| 本地适配器 | Node 同源调查接口与旧 `/api/parse-target`；Java 子进程复用 Agent/PG 修订 | 生产鉴权、持久任务调度、完整分析 API |
| 确定性后端 | Java 21/Maven，版本化 IR、不可变图、有限 Java/MyBatis 解析、列变更传播与风险区间、离线 CLI；MySQL/Oracle/KingbaseES/DM 只读结构采集代码 | 全项目语义、跨语言完整字段链；后三种业务库的真实产品联调 |
| 模型 | 旧描述解析；新增原生工具循环，目标/规则选择、工具调用、可恢复澄清和匿名证据解释 | 联网规则研究、完整自主采集与人工审阅工作台 |
| 调查与存储 | 统一调查及 Agent CLI、显式来源清单、PG Case/Run/澄清修订及报告封存、Neo4j 幂等投影代码 | 生产 Spring Boot API、跨查询一致业务元数据快照、完整兼容规则；OIDC 鉴权经 2026-09-21 用户决策本版本不实施，仅限本机单用户场景使用 |
| 场景规则 | 25 条版本限定规则、SQL/C# 特征、Java AST 重构/升级；报告 v2–v5 逐步加入 .NET 声明、业务结构与静态候选访问路径 | 完整 .NET 语义、目标能力与有方向迁移规则、信创组合、结构化迁移方案及行为验证 |

“已实现”只对应上述有限范围，不代表总体设计验收完成。实现以源码和验证证据判断；预期行为以已确认需求和设计判断。二者冲突时明确记录差异，不静默选择有利表述。

## 3. 目录与模块职责

```text
ArchLens/
├── AGENTS.md                     开发代理入口
├── project_rules.md              项目工程约束
├── docs/                        当前导航、两份设计基线、历史归档与新验证记录
├── ArchLensClient/
│   ├── index.html / styles.css   页面结构与样式
│   ├── app.js                   表单、校验、提议状态与请求预览
│   ├── server.mjs               本地静态服务及 Java 模型适配器
│   └── start.ps1                后端环境与隐藏密钥输入
└── ArchLensService/
    ├── pom.xml                  JDK、依赖与构建插件基线
    ├── src/main/java/io/archlens/
    │   ├── contract/            IR、身份、JSON、图和契约校验
    │   ├── parser/              来源读取、离线目录、语法分析与绑定
    │   ├── analysis/            ChangeSpec、影响传播与风险计算
    │   ├── llm/                 外部模型调用与提议校验
    │   ├── agent/               模型工具循环、澄清契约与匿名证据投影
    │   ├── investigation/       统一调查、显式来源清单与覆盖报告
    │   ├── storage/             自身 PG Case/Run 和 Neo4j 投影
    │   └── cli/                 离线分析与模型解析入口
    ├── src/test/java/           自动化测试
    ├── examples/               可复现离线样例
    ├── scripts/                构建和烟测脚本
    ├── specs/implementation/    实施追踪
    └── runtime/                运行依赖清单；测试夹具位于 src/test/resources/
```

`target/` 是构建与报告输出，`.local/` 是机器本地依赖缓存。`qa/` 现有资料主要用于设计文档排版，不是产品界面验收证明。禁止修改构建产物来代替源码修复。

保持确定性分析链路独立于模型可用性。`contract/`、`parser/`、`analysis/` 不依赖模型调用、前端或外部服务响应。未来框架和存储适配应复用核心，而不是将事实计算移动到控制器或提示词中。

## 4. 输入、状态与契约

### 4.1 面向用户的输入

当前首页支持仅代码、仅数据库、联合三种分析模式；无业务上下文使用 Agent v1，带业务上下文使用 Agent v2。业务连接单独通过本机 Node→Java 通道传递，密码只在表单内存及本次请求中使用，不进入持久化请求、报告、模型或日志。仅数据库模式可无代码目录。ArchLens 自身 PG/Neo4j 凭据由后端读取，不能与被分析业务库混用。以下三组输入约束仅属于保留的旧草稿页面 `draft.html`：代码库绝对路径、PostgreSQL 连接信息、修改目标描述。

- 路径指向分析服务所在机器可访问的目录；浏览器格式校验不证明路径存在或可读。
- 数据库地址、用户名和密码分开输入。当前表单接受 PostgreSQL/JDBC PostgreSQL URL，不支持连接查询参数和 SSL 配置，也不实际验证连接。
- 修改描述应包含对象、修改方式和约束；有歧义或缺少 schema 等信息时澄清，不自行猜测。
- 数据库密码不得进入请求下载、浏览器持久化或模型请求；旧草稿页不传输数据库密码，现有工作台的独立连接通道按上文约束处理。

### 4.2 三种数据不能混用

| 数据 | 当前用途 | 约束 |
| --- | --- | --- |
| `archlens.frontend-draft.v1` | 前端请求草稿，状态为 `NOT_SUBMITTED` | 不能直接传入离线 `analyze` 命令 |
| `OfflineRequest` | 离线 CLI 输入，包含 scope、来源、Java/XML、catalog 和 change | 来源 ID 必须满足契约，目标必须在 catalog 唯一存在；catalog 未经真实 PG 验证 |
| `UNVERIFIED_PROPOSAL` | DeepSeek 解析结果，可附加至前端草稿 | 不是最终 ChangeSpec，不是数据库事实或执行授权 |

将来生成正式 ChangeSpec 时，必须由确定性程序在固定快照中定位目标、校验变更前状态和变更语义。生产 API 的项目、快照、操作者、权限和追踪上下文由服务端管理，不能接受模型自选 ID。当前离线 UUID 参数不构成生产授权机制。

### 4.3 契约演进

- 保留版本字段、scope 校验、稳定身份、来源哈希和不可变图语义。
- 拒绝未知枚举、未知属性、重复 JSON 字段、跨快照引用和不合法关系端点，不用宽松反序列化掩盖错误。
- 新节点、关系、变更类型必须同时补充属性约束、解析/绑定规则、诊断和测试；仅加入枚举不算实现。
- 不用 `null`、空字符串或默认值静默填补事实缺失。允许未知的字段应明确表示未知及原因。
- 修改契约时同步消费者、示例、文档和测试，说明兼容策略。

## 5. 事实、证据和影响分析

1. 采集、AST 解析、绑定、图构建、证据关联、传播和评分由确定性代码完成。
2. 依赖边方向为 `dependent → dependency`；变更影响沿反向依赖传播，`CONTAINS` 不参与当前传播。
3. 依赖存在不等于必须修改。候选绑定、条件路径、字段链缺口和未覆盖语义必须保留 `UNKNOWN` 或诊断。
4. 影响强度、业务风险和置信度分别处理；风险输入缺失时给出区间及来源，不用置信度乘风险来降低结论。
5. 传播必须有深度、节点、工作量和时间预算，并保留截断标记。展示路径数量不能限制完整的已访问判断合并。
6. 证据引用原始来源和 SHA-256，定位遵循原文 UTF-8 字节、1 起始 Unicode 码点行列和结束位置不包含。旧列分析、XML 与比较计划仍为整文件；新场景的 SQL/C# token、Java AST 支持精确原文位置，不能将其外推为所有解析器均精确定位。
7. 当前支持列改名、删除和有限类型变更语义。API/方法枚举存在不代表已支持相应分析。
8. 纯物理改名保持类型、可空性和业务含义。字段删除没有 after；类型变更保持名称和业务身份。变更前状态须匹配固定图中的列。
9. 当前缺少关联快照验证引擎，拒绝 `VERIFIED` 兼容声明与 `APPLIED` 变更。拟议兼容条件不能自动得到已验证的 `NO`。
10. 离线 catalog 必须保留 `OFFLINE_CATALOG_UNVERIFIED`。当前报告为 `PARTIAL`，进程退出码 0 仅代表报告成功生成。

解析超出支持范围时报告诊断，不用文本猜测替代绑定。当前 SQL 解析以单表静态 SELECT 和有限 WHERE 直接列引用为主；动态 XML、参数化 SQL、JOIN、子查询及复杂字段映射等能力不能默认视为已覆盖。

## 6. 模型调用与凭据

- 当前 `DeepSeekGateway` 默认模型为代码配置的 `deepseek-flash`，后端可通过 `DEEPSEEK_MODEL` 调整；实际可用性需要运行时验证。
- 密钥通过后端 `DEEPSEEK_API_KEY` 或启动脚本隐藏输入提供，不放入命令示例、仓库文件、前端包或日志。禁止通过输出整个环境变量集合排查配置。
- 当前网关固定官方 HTTPS 端点，禁止自动重定向。更换供应商或扩大上传范围必须作为明确变更处理。
- 旧目标解析只发送修改描述；新增 Agent 通道发送用户目标/技术字段/约束/回答、规则元数据和匿名证据摘要，禁止直接发送完整请求、来源或报告。用户文本是数据，不能改变系统权限或成为可执行指令。
- 模型输出经过结构与语义校验。无效 JSON、截断、空内容、额外字段、未支持类型和不完整目标不得伪装成成功。
- 提议必须保留 `UNVERIFIED_PROPOSAL`。不能向事实层注入模型生成的证据、依赖、scope、评分或“无需修改”结论。
- 描述修改后清除旧提议；取消和超时的旧响应不能覆盖新输入。
- 失败时保留原始描述并允许不带模型提议的请求预览；已有离线分析命令不依赖模型成功。
- 联调使用不含敏感信息的合成描述，控制调用次数。错误仅返回稳定错误码或脱敏信息，不回显供应商原始响应、认证头和内部堆栈。

新增调查编排已支持六个固定只读工具、循环预算、澄清修订、匿名证据解释及确定性降级。模型解释保留 MODEL_EXPLANATION_UNVERIFIED，结构/引用校验不等于自然语言语义正确性证明。详细发送范围、能力限制和运行方式见 [Agent 说明](docs/archive/2026-09-29/ArchLensService/docs/agent-orchestration.md)。

## 7. 工程实现约定

- 使用 Java 21、Maven 3.9.x；前端本地服务使用 Node.js 18 或以上。依赖和插件版本遵循现有 `pom.xml`，不使用动态版本。
- 原生前端保持语义化 HTML、明确 label、键盘可用操作和安全文本渲染。模型内容使用 `textContent` 等文本接口，不作为 HTML 执行。
- 真实校验、加载、成功、失败与未接入状态要区分；不得用延迟动画模拟分析成功。
- Node/Java 输入输出显式使用 UTF-8；子进程使用独立参数数组，不把用户路径或描述拼进 shell 命令。
- 当前 Node 服务只监听本机，保留 Host、Origin、JSON 类型和请求大小检查。不能把此适配器直接公开部署为生产服务。
- 解析器读取输入根目录内真实文件，不允许越界路径；不执行被分析源码，保持 XML 外部实体/DTD 访问关闭。
- 对磁盘已有输出采用防覆盖策略。演示和验证报告使用新文件名，不覆盖输入、源码或已有证据。
- 不修改系统默认 JDK、用户全局代理或包管理器配置来适配项目；优先使用进程级变量和项目配置。
- 新依赖必须说明用途，并检查许可证、构建和运行影响；不要为小功能引入不必要的框架。

## 8. 构建、运行与验证

以下 PowerShell 命令从项目根目录执行。`ARCHLENS_JAVA_HOME` 需由运行者配置为实际 JDK 21 目录，不将开发机绝对路径当成通用路径。

### Java 构建及离线样例

```powershell
Push-Location .\ArchLensService
try {
    .\scripts\build.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME -Demo
} finally {
    Pop-Location
}
```

脚本运行 `mvn verify` 并打包；`-Demo` 执行离线样例并生成新报告。涉及打包或 CLI 交付时，先在同一目录运行 `scripts/record-runtime.ps1` 更新 `runtime/runtime-dependencies.json`，再运行 `scripts/smoke.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME`。烟测写入 `target/verification/` 下的新文件，检查实际输出，不覆盖历史归档。这些 PowerShell 脚本使用 Windows 的 `java.exe` / `mvn.cmd`；Linux 构建命令见后端 README。

### 前端语法检查

```powershell
node --check .\ArchLensClient\app.js
node --check .\ArchLensClient\server.mjs
```

逐项检查命令退出状态。没有模型配置时，可运行 `node .\ArchLensClient\server.mjs` 使用本地表单与请求预览。

### 启动带模型能力的本地页面

```powershell
.\ArchLensClient\start.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME
```

先完成后端构建。未设置密钥时脚本提示隐藏输入；密钥不持久化。访问本机端口 4173，端口可通过 `ARCHLENS_CLIENT_PORT` 调整。

### 验证要求

- Java 核心修改执行 `mvn verify`，关注真实解析输入、契约负例、循环/预算、未知传播及风险区间。
- 模型修改覆盖成功、缺失信息、格式错误、拒绝多余字段、鉴权失败、超时、脱敏和离线可用性；不能要求外部模型参与每次普通单元测试。
- 前端交互修改检查必填项、无效输入、加载/错误恢复、中文、键盘访问，以及描述更新后的草稿失效；布局修改增加桌面和窄屏验证。
- 只有文档变更时核对链接、目录、术语、命令和边界即可，不为增加测试数量运行无关检查。
- 测试通过不等于真实项目性能、生产权限或完整产品验收通过。历史测试数量只属于原记录，不作为未来固定验收指标。

## 9. 文档与交付规则

- [AGENTS.md](AGENTS.md) 保持简洁的工作入口；本文维护共用工程规则，模块 README 维护使用方式，`specs/implementation/` 维护需求和任务追踪。
- 区分规划、已实现、已验证；测试和联调记录写明日期、范围及证据位置。旧验收记录不自动覆盖新增功能。
- 用户要求规格先行时按需求、设计、任务、验收清单顺序维护；已批准的范围不额外重复设审批关卡。
- 文档中的凭据只使用变量名或明显占位符。报告与导出不包含密码；检查产物再提交。
- 每次交付给出修改范围、实际验证和未完成事项；没有运行的测试、未连接的数据源和未核对的远端状态必须明确说明。

## 10. 依据与维护入口

- [后端当前范围](ArchLensService/README.md)
- [前端使用方式](ArchLensClient/README.md)
- [文档导航](docs/README.md)
- [现有项目总结](docs/design/ArchLens-现有项目总结-2026-09-29.md)
- [自主迁移设计](docs/design/ArchLens-自主迁移设计Agent-详细设计-v2.0.md)
- [实施需求](ArchLensService/specs/implementation/requirements.md)
- [实施设计](ArchLensService/specs/implementation/design.md)
- [任务跟踪](ArchLensService/specs/implementation/tasks.md)
- [验收记录](ArchLensService/specs/implementation/check_list.md)
- [历史总体设计文档](ArchLensService/ArchLens_设计文档_v1.0.docx)

维护规则时核对相应源码。总体设计中的 Spring Boot、Vue 和完整 Agent 能力尚未实现；PG/Neo4j、Case/Run 的 CLI 子集及具体联调范围见 [调查存储说明](docs/archive/2026-09-29/ArchLensService/docs/investigation-storage.md)，不能由子集交付推导总体“已完成”。

C# 项目分析入口、声明范围及边界见 [C# → Java 分析](docs/archive/2026-09-29/ArchLensService/docs/csharp-java.md)。
