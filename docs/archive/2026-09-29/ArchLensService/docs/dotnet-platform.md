# .NET 平台现状分析

更新：2026-09-24。当前交付范围是本地代码目录的候选发现与静态声明盘点，覆盖 .NET Framework、.NET Core、现代 .NET 和 .NET Standard；识别 C#、VB.NET、F# 项目，不把语言版本当作运行平台版本。

## 使用

网页点击“填入混合 .NET 示例”，或填写本地授权目录并点击“发现 .NET 解决方案”。检查候选文件清单后：

- **预览 .NET 现状**：调用真实本地 Java 引擎，显示项目、目标框架、依赖声明、引用与覆盖缺口；不需要 PostgreSQL 或模型，不保存历史，可下载预览 JSON。这里只盘点现状，不解释表单中的迁移目的。输入目录或文件清单改变后，旧预览标记失效。
- **提交调查**：使用已有 Agent/PG 工作流，结合目的、约束及目标技术进行调查。源技术填 `.NET`，源版本可留空；每个项目单独识别声明。目标 Java 版本未知时仍需要澄清。全平台迁移兼容规则和语义尚未完成，兼容性结论保留 UNKNOWN。

Linux 从仓库根目录启动（需本机已安装 JDK 21、Maven 3.9、Node 18+）：

```bash
# 使用本项目已安装的本地工具链时：
source .local/toolchains/activate.sh
mvn -f ArchLensService/pom.xml --batch-mode --no-transfer-progress verify
node ArchLensClient/server.mjs
```

默认访问 `http://127.0.0.1:4173`。没有配置存储时，历史列表/正式提交会提示存储不可用，本地预览仍可使用。Windows 构建和正式调查启动见 [前端说明](../../ArchLensClient/README.md)。

无需 PG 的文件模式，从仓库根目录运行（输出必须使用新文件名）：

```bash
java -jar ArchLensService/target/archlens-0.1.0-SNAPSHOT-cli.jar agent-investigate \
  ArchLensService/examples/scenarios/dotnet-platform/agent-clarify.json \
  ArchLensService/target/my-dotnet-report.json
```

未配置模型时保留确定性降级。要确保离线运行，可在该进程清空 `DEEPSEEK_API_KEY`；不要把真实凭据写入命令或示例。

## 已实现的声明范围

| 输入 | 产物与边界 |
| --- | --- |
| `.sln`、`.slnx` | 项目引用声明；不求值解决方案构建配置，不自动跟随外部引用 |
| `.csproj`、`.vbproj`、`.fsproj` | 旧式/SDK 风格，目标框架、语言版本、输出类型、WPF/WinForms 声明 |
| `TargetFrameworkVersion` / `TargetFramework(s)` | 区分 Framework/Core/现代 .NET/Standard，多目标逐项保留，未知/条件/重复声明明确标注 |
| PackageReference、程序集/框架/COM 引用、ProjectReference | 仅直接声明；目标已采集不等于调用关系已绑定，缺失/动态/越界引用单列 |
| `global.json`、Directory.Build/Packages、`packages.config` | SDK 版本或共享/包声明；共享属性不应用到各工程，不恢复包或解析传递依赖 |
| web/app.config、appsettings JSON | 只输出文件结构/已知元素线索，不导出连接串或配置值；严格 JSON，注释等非严格格式记为缺口 |
| `.cs`、`.vb`、`.fs`、`.fsx` | 采集与哈希，平台清单语义绑定数为 0；现有 C# 专项规则保留独立范围 |

应用类型只作为候选线索。原文证据为整个声明文件，附 `sourceId`、SHA-256 和 `WHOLE_FILE` 粒度，不伪造精确 XML 行号、调用边或兼容结论。F# 项目可识别，但其语义分析器未实现。

发现限额：1000 文件、50 MB、10000 目录项、20 层、10 秒，超过即失败，不返回静默截断清单。排除构建/包缓存、生成代码和符号链接。Java 重新校验根目录、文件范围与哈希；单元数据文件另限 500000 字符，解析工作量有界。预览采集预算最多 25 秒；失败显示错误码或 PARTIAL/覆盖缺口。

## 契约与边界

- 新接口：`POST /api/investigations/discover-dotnet` 接收 `{sourceRoot}`；`POST /api/investigations/preview-dotnet` 接收 `{sourceRoot, files}`。沿用同源检查、请求大小限制，预览与正式调查共享两次并行分析上限。
- 平台清单为 `archlens.dotnet-inventory.v1`；包含清单的调查报告为 `archlens.investigation-report.v3`。无清单时仍输出 v2；旧报告读取后不新增空字段，历史 canonical 哈希回归通过。Agent 外层契约仍为 v1。
- 模型只接收平台项目数量摘要，不接收平台文件路径、项目/包名、配置正文或完整清单。自然语言目的和手填技术字段仍沿用既有白名单。
- 不执行被分析项目、MSBuild、restore 或业务 SQL。条件、Imports、SDK 默认值、实际部署及行为需要后续验证；代码来源不是原子快照，漂移或取消时撤销清单。
- Git 固定提交采集、业务数据库连接器、Roslyn 语义、KingbaseES 专用迁移规则、信创组合及自动代码转换尚未实现。

验证结果见 [2026-09-24 验证](verification-dotnet-2026-09-24.md)。
