# ArchLens 调查工作台

文档整理完成：2026-09-30。当前开发依据见 [文档导航](../docs/README.md) 和 [新实施规格](../ArchLensService/specs/implementation/requirements.md)。下文指向 `docs/archive/` 的说明与验证是保留原日期的历史资料；当前实现边界以 [项目总结](../docs/design/ArchLens-现有项目总结-2026-09-29.md) 及源码为准。

2026-09-27 增量：联合报告增加 C#/Java 方法及 MyBatis 语句的静态访问证据路径，区分直接 SQL 实参、同方法候选和未知；详见 [业务数据库与访问路径](../docs/archive/2026-09-29/ArchLensService/docs/business-database.md)。
2026-09-26 更新：[业务数据库与目标环境](../docs/archive/2026-09-29/ArchLensService/docs/business-database.md)。已支持 MySQL 8.x 与 Oracle、人大金仓 KingbaseES、达梦 DM 的连接及只读结构采集，并输出代码 SQL 对象名的静态候选关联；后三种数据库仍需厂商 JDBC 驱动及实库验证。目标环境仅记录声明，运行时依赖确认和迁移评估仍待实现。
原生 HTML/CSS/JavaScript 与 Node.js 本机适配器，要求 Node.js 18+、JDK 21 和已构建的 Java 后端。首页已接入 Agent 调查提交、澄清回答、运行状态、证据报告、下载及历史修订；旧请求草稿入口保留在 `/draft.html`。

## 启动

在项目根目录运行，JDK 路径按本机实际位置设置：

```powershell
$env:ARCHLENS_JAVA_HOME = '你的 JDK 21 目录'
.\ArchLensService\scripts\build.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME
.\ArchLensClient\start.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME
```

访问 `http://127.0.0.1:4173`。可用 `ARCHLENS_CLIENT_PORT` 指定其他端口。服务仅监听本机，不能作为生产鉴权服务公开部署。

`start.ps1` 沿用 `ArchLensService/.local/storage.json` 与当前 Windows 用户 DPAPI 加密的 `storage.credentials.xml`，仅注入 Node/Java 子进程环境；已有环境变量优先。需要先按 [存储说明](../docs/archive/2026-09-29/ArchLensService/docs/investigation-storage.md) 配好 ArchLens 自身 PG/Neo4j 并初始化，启动页面不会自行初始化数据库。模型配置优先进程、其次当前用户配置；已配置远程端点但缺少密钥时隐藏输入，不再要求 DeepSeek。

当前正式调查的配置校验仍要求 PG/Neo4j 七项后端配置完整；无图调查可不连接 Neo4j，但不能省略其配置。`storage-check` / `storage-init` 会连接双库，具体见 [后端配置说明](../ArchLensService/README.md#当前配置与资源位置)。

用 `start.ps1 -JdkHome ... -Offline` 可禁用模型，验证确定性降级和澄清；**Offline 不代表无需 PostgreSQL**。直接 `node server.mjs` 要求环境已配置，否则页面显示存储/后端不可用，旧草稿页面仍可使用。

## 通用分析入口（2026-10-13）

首页只要求代码库路径与自然语言目标，可附加数据库连接、约束与业务不变量。不提供固定场景、源/目标产品版本、C#/.NET 专用入口或固定模型选择。比如迁移到 Java、升级 Java/Spring Boot、替换数据库，均通过文字目标表达。

代码库路径必须是服务所在机器可读取的项目文件夹，不能直接填写单个源码、`.sln` 或 `.csproj` 文件。开始分析时，按钮旁显示提交进度、必填项校验或后端失败原因；存储未配置时正式分析不可用，本地现状预览仍可使用。

输入来源可选代码、数据库、联合。代码文件清单可留空：Node 在授权目录中有界发现源码和项目声明，排除构建目录、依赖缓存和符号链接，再将清单传给 Java 并随正式请求封存；恢复时沿用原清单。默认上限 1000 个文件、50 MB、10000 个目录项、20 层、10 秒，超限拒绝，不静默截断。可先查看文件清单或缩小到模块目录。

后台 `inspect_repository` 从本地 Maven/.NET/package.json/Go 等声明提取技术产品和明确版本；文件扩展名只证明语言候选，版本未知、条件声明、混合版本和不支持的解析器保留缺口。模型结合文字目标选择工具；复合目标可委派最多两个独立子 Agent，顺序执行且共用总预算。缺少关键目标时以自然语言澄清。规则分析仍受现有支持矩阵约束，发现更多语言不代表完整语义支持。

本地“预览代码库现状”和数据库/联合预览无需模型及自身存储，只做现状盘点。正式“开始分析”仍需自身 PostgreSQL 和现有存储配置；缺模型只能降级，不能生成已完成的模型分析。运行可取消、澄清恢复、查看历史修订和下载封存报告；子报告、调用审计及覆盖缺口保留在正式报告中。

模型在后端配置，页面仅显示供应商标签与模型标识，见[通用模型配置](../ArchLensService/README.md#通用模型配置2026-10-13)。密钥不进入浏览器。业务数据库密码通过本次独立连接通道传输，不进入报告、模型或浏览器存储。

## 接口与持久化

| 接口 | 行为 |
| --- | --- |
| `GET /api/investigations/model-config` | 已验证的后端模型标签与标识，不返回端点和凭据 |
| `POST /api/investigations/discover-repository` | `{sourceRoot}` 通用有界文件发现 |
| `POST /api/investigations/preview-repository` | `{sourceRoot, files:[]}` 自动发现并生成本地现状预览，无存储/模型 |
| `GET /api/investigations/example?scenario=csharp-java` | C# 项目样例；默认无参数仍为 MySQL |
| `POST /api/investigations/discover-csharp` | 授权目录内有界候选发现，返回显式文件/排除项；不执行项目 |
| `POST /api/investigations/test-database` | `{sourceRoot, request, connection}`；连接测试，不保存调查、不等于完成结构采集 |
| `POST /api/investigations/preview-joint` | 相同封套；仅数据库或联合现状预览，无模型、无自身存储 |
| `POST /api/investigations` | `{sourceRoot, request, connection}`；无业务上下文为 Agent v1，有业务上下文为 v2；分配 Run 后返回 202 |
| `GET /api/investigations?caseId=...&offset=0` | 最近 Agent 运行或指定 Case 修订；每页 25 条 |
| `GET /api/investigations/:runId` | PG 状态、不可变报告、检查点和本机来源根目录 |
| `GET /api/investigations/:runId/report.md` | 从封存报告生成面向阅读的 Markdown；原始符号、哈希和审计仍保留在 JSON |
| `POST /api/investigations/:runId/resume` | `{sourceRoot, answers, connection}`；原始 request 从 PG 父报告恢复，业务连接需要重新提供 |
| `POST /api/investigations/:runId/cancel` | `{}`；PG 取消并隔离迟到结果 |
| `GET /api/investigations/:runId/report` | 封存报告或明确标注的不完整检查点附件 |

工作台默认呈现范围、缺口、迁移步骤、风险、难点与成功率评估条件；逐条语义符号、工程配置和技术声明清单折叠在“供技术核查”区域。报告可下载易读 Markdown 或完整原始 JSON。风险评估由封存证据确定性生成；在缺少运行基线和迁移后回归结果时，成功率显示为不可可信量化，不伪造百分比。Markdown 是封存 JSON 的可读投影，不改变原报告状态，也不将 `PARTIAL` 草案升级为验证结论。

Java 入口为 `io.archlens.cli.WebAgentCli`，stdin/JSON 行输出均 UTF-8。报告和修订由 PG 管理；`.local/web-contexts/<runId>.json` 只保存本机授权根目录以便重启后恢复，不含凭据、不进入模型、不会作为静态文件发布。不要把该目录提交到仓库。没有另造浏览器历史数据库。

POST 校验同源 Origin/Host 和 JSON 类型；拒绝额外字段、路径越界、超大请求及任意命令。最多并行两次调查、六次查询；子进程参数不用 shell 拼接。业务库密码仅保留在当前表单内存和本次 Node→Java 的独立连接通道，不写 localStorage、报告、持久化请求或模型上下文。ArchLens 自身存储密码仍由后端配置；旧草稿页不传密码。模型只接收白名单摘要，当前边界见 [项目规则](../project_rules.md#6-模型调用与凭据)。

当前限制：本机单用户、无生产身份鉴权/后台持久队列；初次提交没有跨进程幂等键，网络超时应先刷新历史再重试。Java 进程被中断后依赖租约过期显示中断，不自动重启调查。未支持全项目语义分析、运行时依赖证明或旧列适配提交；目录自动发现只扩展采集入口。

## 验证

```powershell
# ArchLensClient 目录
npm run check
npm test
# 沙箱禁止测试子进程时可用等价的同进程运行
node tests/http.test.mjs
```

真实双库与网页 Java 协议回归，从项目根目录运行 `ArchLensService/scripts/web-verify.ps1 -JdkHome ...`；脚本要求本机存储配置，不输出凭据，使用合成文件和 ArchLens 自身 schema。

`node tests/ui-fixture.mjs` 在 4184 启动明确标注“UI 回归夹具”的页面，读取 [独立测试资源](tests/fixtures/agent-20260918/README.md)，无需历史文档目录。它使用历史报告测试交互，**不证明真实数据库、当前模型或采集链路可用**。只用于开发回归，不是生产入口。

当前真实闭环、重启回读及结果证据见 [2026-09-20 网页验证](../docs/archive/2026-09-29/ArchLensService/docs/verification-web-2026-09-20.md)。无图 SQL 场景可用 `web-verify.ps1 -JdkHome ... -PgOnly` 验证 PG 子集；该模式排除原双库测试，不代表 Neo4j 通过。此前网络阻塞记录保留在 [2026-09-18 验证](../docs/archive/2026-09-29/ArchLensService/docs/verification-web-2026-09-18.md)。

## 旧专用 API 与历史界面（不再作为首页入口）

以下描述记录旧界面的验证背景；`discover-csharp`、`discover-dotnet`、`preview-dotnet` 等 API 为兼容保留，首页使用通用入口。

### C# 项目到 Java

新增“填入 C# 项目示例”和“发现 C# 项目文件”。检查清单后提交，示例澄清时填 C# 语言版本 `12`，目标 Java `21`。项目/依赖声明与源码特征一起进入真实 Agent/PG 报告；未知和覆盖缺口保留。具体范围、限额及复现见 [C# 使用说明](../docs/archive/2026-09-29/ArchLensService/docs/csharp-java.md)。

### 混合 .NET 平台与本地预览（2026-09-24）

新增“填入混合 .NET 示例”“发现 .NET 解决方案”和“预览 .NET 现状”。支持 Framework/Core/现代 .NET 的逐项目声明识别及 C#/VB.NET/F# 项目盘点；源技术 `.NET` 不要求填写统一源版本。详细使用和边界见 [.NET 平台说明](../docs/archive/2026-09-29/ArchLensService/docs/dotnet-platform.md)。

`POST /api/investigations/discover-dotnet` 接收 `{sourceRoot}`；`POST /api/investigations/preview-dotnet` 接收 `{sourceRoot, files}`，返回真实本地分析的 `{mode:"LOCAL_PREVIEW", persisted:false, report, reportHash}`，没有 Run ID。预览无需模型和 PostgreSQL，不保存历史，不解释迁移目的；下载独立预览 JSON。正式“提交调查”仍需要配置自身存储。

WPF 工程中的 `UseWPF`、目标框架和项目引用可由 `.csproj` 声明盘点；UTF-8 BOM 工程文件也可解析。发现步骤会提示 XAML 文件数量，但当前不采集或解析 XAML 界面结构，`.xaml.cs` 也没有语义绑定。真实 WPF 样本的本地预览与覆盖边界见[验证记录](../docs/verification/dotnet-wpf-preview-2026-10-03.md)。

现状预览现在先显示带工程来源的“现状分析（工程声明）”：目标框架分层、WPF/WinForms、SQLite/SQLCipher 依赖和项目引用，并在下载 JSON 的 `findings` 中保留每条结论的证据引用、待核实范围和建议。它是确定性声明归纳，不是源码语义分析或任何目标技术的迁移方案；见[分析内容验证](../docs/verification/dotnet-current-state-assessment-2026-10-03.md)。

2026-10-14 修正了现状盘点和通用语言迁移建议中的固定 Java 字样；新报告用与目标技术无关的措辞。已封存的旧报告保持原文，页面会标出不匹配的旧模板文案。`DETERMINISTIC_FALLBACK` 表示模型调查因诊断错误或预算等原因未完成；页面保留静态事实和澄清结果，不代表迁移方案已形成。当前不提供 .NET → C++/Electron 的完整语义迁移评估。

回归夹具可用 `ARCHLENS_UI_FIXTURE_REPORT` 指定本地 CLI 已生成的 Agent 报告；它仍模拟存储，只用于展示回归。本地预览完整链路应使用 `node server.mjs` 验证。

## 业务数据库配置补充

MySQL 8.x 采集器读取系统元数据；已有历史实库证据为 MySQL 8.4.11。Oracle、KingbaseES、DM 使用 JDBC 元数据接口，尚未完成各产品实库验收。启动前用后端环境变量 `ARCHLENS_BUSINESS_JDBC_JARS` 提供兼容的厂商 JAR 绝对路径；多个路径按本机类路径分隔符连接（Windows `;`，Linux `:`），缺驱动返回 `DB_DRIVER_UNAVAILABLE`。

Oracle 填服务名与模式，KingbaseES 填数据库名与模式，DM 填模式；不接受任意 JDBC URL。MySQL 默认 `VERIFY_IDENTITY`；其他产品当前使用 `DRIVER_DEFAULT` 并保留 TLS/服务端只读状态未验证的缺口。采集只覆盖账号可见对象，未出现的对象不等于不存在。源产品及目标版本分别记录；目标环境仍是声明，不是迁移兼容性结论。
