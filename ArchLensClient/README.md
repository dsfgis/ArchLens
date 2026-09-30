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

`start.ps1` 沿用 `ArchLensService/.local/storage.json` 与当前 Windows 用户 DPAPI 加密的 `storage.credentials.xml`，仅注入 Node/Java 子进程环境；已有环境变量优先。需要先按 [存储说明](../docs/archive/2026-09-29/ArchLensService/docs/investigation-storage.md) 配好 ArchLens 自身 PG/Neo4j 并初始化，启动页面不会自行初始化数据库。DeepSeek 密钥优先进程、其次当前用户配置，均未配置时隐藏输入。

当前正式调查的配置校验仍要求 PG/Neo4j 七项后端配置完整；无图调查可不连接 Neo4j，但不能省略其配置。`storage-check` / `storage-init` 会连接双库，具体见 [后端配置说明](../ArchLensService/README.md#当前配置与资源位置)。

用 `start.ps1 -JdkHome ... -Offline` 可禁用模型，验证确定性降级和澄清；**Offline 不代表无需 PostgreSQL**。直接 `node server.mjs` 要求环境已配置，否则页面显示存储/后端不可用，旧草稿页面仍可使用。

## 三种分析范围

首页“分析范围”可选“仅代码”“仅数据库”“代码＋数据库”。仅代码需要授权目录和文件清单；仅数据库只需所选业务数据库连接，目录和文件被禁用；联合模式同时要求两者。数据库和联合模式可用无模型、无调查存储的本地预览；正式提交仍需 ArchLens 自身 PostgreSQL。增强元数据与权限范围见 [业务数据库说明](../docs/archive/2026-09-29/ArchLensService/docs/business-database.md)。

## 完整操作

1. 输入授权根目录和相对文件清单，每行一个。只读明确文件；C# 可点击发现候选并检查清单，不运行源码；业务数据库仅在显式填写并启用后连接。
2. 填写目标和技术版本；未知版本留空，或选择由模型解析目标。重构场景仍须提供前后文件及 `*.refactor.json` 计划，参见 [支持矩阵](../docs/archive/2026-09-29/ArchLensService/docs/scenario-rules.md)。旧 `COLUMN_CHANGE` 适配仍使用原 CLI，网页不将草稿冒充已验证变更。
3. 提交后拿到真实 PG Run ID，自动刷新状态；可取消。后台错误保留 FAILED，服务异常退出留下的 RUNNING 在租约到期后标记为中断。
4. 需要澄清时，逐项回答。回答绑定父报告 canonical 哈希，PG 在事务中拒绝旧修订/重复恢复；网页提交过的目录保持锁定。旧 CLI 报告未记录网页根目录，需要显式重新授权。
5. 查看规则发现、条件、未知项、文件位置/哈希、来源清单、工具审计和独立标注的模型解释，下载封存报告 JSON。取消前保留的来源检查点可下载，标为 INCOMPLETE_CHECKPOINT。
6. 查看同一 Case 的修订，或全部 Agent 运行（每页 25 条）。历史报告只读，旧澄清不再显示提交入口。地址中的 Run ID 支持刷新恢复；自动状态刷新不会清除正在编辑的回答。

点击“填入 MySQL 示例”会自动填写仓库内 `ArchLensService/examples/scenarios/mysql-postgresql` 的实际绝对路径及 `schema.sql`。源版本留空，应先澄清；回答 `8.0.36` 后产生第二修订。输出仍可能是 PARTIAL/UNKNOWN，不表示迁移已完成或整个项目安全。

## 接口与持久化

| 接口 | 行为 |
| --- | --- |
| `GET /api/investigations/example?scenario=csharp-java` | C# 项目样例；默认无参数仍为 MySQL |
| `POST /api/investigations/discover-csharp` | 授权目录内有界候选发现，返回显式文件/排除项；不执行项目 |
| `POST /api/investigations/test-database` | `{sourceRoot, request, connection}`；连接测试，不保存调查、不等于完成结构采集 |
| `POST /api/investigations/preview-joint` | 相同封套；仅数据库或联合现状预览，无模型、无自身存储 |
| `POST /api/investigations` | `{sourceRoot, request, connection}`；无业务上下文为 Agent v1，有业务上下文为 v2；分配 Run 后返回 202 |
| `GET /api/investigations?caseId=...&offset=0` | 最近 Agent 运行或指定 Case 修订；每页 25 条 |
| `GET /api/investigations/:runId` | PG 状态、不可变报告、检查点和本机来源根目录 |
| `POST /api/investigations/:runId/resume` | `{sourceRoot, answers, connection}`；原始 request 从 PG 父报告恢复，业务连接需要重新提供 |
| `POST /api/investigations/:runId/cancel` | `{}`；PG 取消并隔离迟到结果 |
| `GET /api/investigations/:runId/report` | 封存报告或明确标注的不完整检查点附件 |

Java 入口为 `io.archlens.cli.WebAgentCli`，stdin/JSON 行输出均 UTF-8。报告和修订由 PG 管理；`.local/web-contexts/<runId>.json` 只保存本机授权根目录以便重启后恢复，不含凭据、不进入模型、不会作为静态文件发布。不要把该目录提交到仓库。没有另造浏览器历史数据库。

POST 校验同源 Origin/Host 和 JSON 类型；拒绝额外字段、路径越界、超大请求及任意命令。最多并行两次调查、六次查询；子进程参数不用 shell 拼接。业务库密码仅保留在当前表单内存和本次 Node→Java 的独立连接通道，不写 localStorage、报告、持久化请求或模型上下文。ArchLens 自身存储密码仍由后端配置；旧草稿页不传密码。模型只接收白名单摘要，当前边界见 [项目规则](../project_rules.md#6-模型调用与凭据)。

当前限制：本机单用户、无生产身份鉴权/后台持久队列；初次提交没有跨进程幂等键，网络超时应先刷新历史再重试。Java 进程被中断后依赖租约过期显示中断，不自动重启调查。未支持全项目扫描、运行时依赖证明或旧列适配提交。

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

## C# 项目到 Java

新增“填入 C# 项目示例”和“发现 C# 项目文件”。检查清单后提交，示例澄清时填 C# 语言版本 `12`，目标 Java `21`。项目/依赖声明与源码特征一起进入真实 Agent/PG 报告；未知和覆盖缺口保留。具体范围、限额及复现见 [C# 使用说明](../docs/archive/2026-09-29/ArchLensService/docs/csharp-java.md)。

## 混合 .NET 平台与本地预览（2026-09-24）

新增“填入混合 .NET 示例”“发现 .NET 解决方案”和“预览 .NET 现状”。支持 Framework/Core/现代 .NET 的逐项目声明识别及 C#/VB.NET/F# 项目盘点；源技术 `.NET` 不要求填写统一源版本。详细使用和边界见 [.NET 平台说明](../docs/archive/2026-09-29/ArchLensService/docs/dotnet-platform.md)。

`POST /api/investigations/discover-dotnet` 接收 `{sourceRoot}`；`POST /api/investigations/preview-dotnet` 接收 `{sourceRoot, files}`，返回真实本地分析的 `{mode:"LOCAL_PREVIEW", persisted:false, report, reportHash}`，没有 Run ID。预览无需模型和 PostgreSQL，不保存历史，不解释迁移目的；下载独立预览 JSON。正式“提交调查”仍需要配置自身存储。

回归夹具可用 `ARCHLENS_UI_FIXTURE_REPORT` 指定本地 CLI 已生成的 Agent 报告；它仍模拟存储，只用于展示回归。本地预览完整链路应使用 `node server.mjs` 验证。

## 业务数据库配置补充

MySQL 8.x 采集器读取系统元数据；已有历史实库证据为 MySQL 8.4.11。Oracle、KingbaseES、DM 使用 JDBC 元数据接口，尚未完成各产品实库验收。启动前用后端环境变量 `ARCHLENS_BUSINESS_JDBC_JARS` 提供兼容的厂商 JAR 绝对路径；多个路径按本机类路径分隔符连接（Windows `;`，Linux `:`），缺驱动返回 `DB_DRIVER_UNAVAILABLE`。

Oracle 填服务名与模式，KingbaseES 填数据库名与模式，DM 填模式；不接受任意 JDBC URL。MySQL 默认 `VERIFY_IDENTITY`；其他产品当前使用 `DRIVER_DEFAULT` 并保留 TLS/服务端只读状态未验证的缺口。采集只覆盖账号可见对象，未出现的对象不等于不存在。源产品及目标版本分别记录；目标环境仍是声明，不是迁移兼容性结论。
