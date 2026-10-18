# .NET 本地语义取证与 Agent 方案验证（2026-10-14）

## 实施范围

- `ArchLensService/analyzers/dotnet/`：独立 .NET 10/Roslyn 静态进程，只接收显式授权的 C# 文本、来源 SHA-256 和最近已授权的项目身份；不加载、构建、还原或执行被分析项目。
- `DotnetSemantics` Java 桥接：路径/哈希/输出校验、进程超时和取消、每文件来源漂移复核；缺工具时返回明确覆盖缺口。
- Agent 增加 `inspect_dotnet_semantics` 和 `draft_migration_plan`。模型只看匿名事实编号、类别与绑定等级；报告保留完整本地事实，草案固定为 `UNVERIFIED_PROPOSAL`。旧报告新增字段为可选，缺省不改变序列化哈希。
- 网页显示语义事实、工程分布、覆盖缺口与草案步骤；大型 JSON 折叠内容按需渲染。

## 本次执行的检查

- 在本地隔离目录安装 .NET SDK 10.0.402；`dotnet build analyzers/dotnet/ArchLens.Semantics.csproj -c Release`：0 警告、0 错误。
- `mvn -q verify`：通过；`DotnetSemanticsTest` 3 项、`GenericAgentTest` 13 项均无跳过或失败。验证源内调用绑定、缺引用未知、同名类型按项目隔离、UTF-8 位置及 Agent 草案引用约束。
- `npm run check` 与 `npm test`：通过（19 项 HTTP 测试）。
- 对用户提供的 `WechatDashboard` 目录，本次共授权 168 个文件，其中 6 个 `.csproj`、148 个 `.cs`（147 个产生符号事实）；产生 8,582 条事实：1,386 条声明、1,011 条源内绑定、747 条宿主引用绑定、5,438 条未解析。报告 JSON 约 4.7 MB，无 `FACT_LIMIT_REACHED`。
- 真实 DeepSeek 模型工具链已调用项目检查、目标解析、本地语义取证、方案草拟与结束；最终 Run 与报告见下节。早期两次运行分别暴露了目标版本过早澄清和封存时重复复核同一来源造成的超时，修正后保留最终运行的审计轨迹。

## 当前结论边界

这是 `PARTIAL` 调查与未经验证的迁移草案。未执行项目构建或运行；项目引用、真实 TFM 编译条件、生成源码和目标运行行为仍未绑定或验证。`HOST_REFERENCE_BOUND` 只代表绑定到工具宿主引用，不能当作已确认的 Windows API 或目标框架兼容性。正式 MigrationPlan v1 封存、完整调用图、跨实现行为回归与生产可迁移性均未验收。

## 初版真实运行（保留历史）

最终 Run ID：`91ca18d7-3065-408e-9b72-ee1fdb59e1f3`。`PARTIAL`，无执行诊断；工具轨迹依次为 `inspect_repository`、`propose_target`、`inspect_dotnet_semantics`、`draft_migration_plan`、`finish`，均返回 `OK`。报告中的 `dotnetSemantics` 包含 8,582 条事实，`migrationPlan` 为 10 阶段 `UNVERIFIED_PROPOSAL`；目标 `.NET → C++` 版本保持未知，Electron 为 UI 目标。项目声明显示 6 个工程均声明 `.NET 10.0` 目标框架；这不代表实际编译或运行时已验证。

可在本机工作台打开 `http://127.0.0.1:4173/#run=91ca18d7-3065-408e-9b72-ee1fdb59e1f3`，或读取忽略版本控制的本地导出 `ArchLensService/.local/reports/wechat-cpp-electron-91ca18d7.json`。模型开放问题中“168 个授权 .NET/C# 文件”措辞不精确：168 是全部授权文件，C# 文件为 148；以请求清单与语义事实分母为准。此草案仍需人工复核具体步骤的源代码归属和业务等价性。

## 同日增量：直接工程引用与易读报告

Roslyn 取证进程现在接收已授权 `.csproj` 原文及哈希，按无条件 `ProjectReference` 建立工程间源码引用，并依据本机 .NET SDK 声明模拟已启用的隐式 using；仍不执行 MSBuild、还原、分析器、生成器或被分析项目。对同一份 168 文件清单重新分析，事实总数仍为 8,582；`SOURCE_BOUND` 从 1,011 增至 2,954，`UNRESOLVED` 从 5,438 降至 1,435。工程引用已在这份清单内解析；源码中没有条件编译分支，因此这两项缺口不再出现。实际编译项、C# 默认语言版本、目标框架引用、生成源码、其余编译诊断和运行行为仍未验证。

Agent 的匿名投影现在明确区分授权文件总数与 `.cs` 数，避免把 168 个文件误写为 168 个 C# 文件。最新真实 Run ID 为 `6cb0dd59-169e-480e-bbf7-1fd2fd72dbd2`：`PARTIAL`、无执行诊断、5 次模型调用，生成 8 阶段 `UNVERIFIED_PROPOSAL` 草案。原始 JSON 保存在忽略版本控制的 `ArchLensService/.local/reports/wechat-cpp-electron-6cb0dd59.json`，易读 Markdown 保存在同目录的 `wechat-cpp-electron-6cb0dd59.md`。网页 `GET /api/investigations/:runId/report.md` 可重新从封存 JSON 生成 Markdown；技术声明、逐条符号和工程配置默认折叠，原始证据仍由 JSON 下载提供。

此次执行 `dotnet build ... -c Release --no-restore`、`mvn -q verify`、`npm run check` 和 `npm test` 均通过；HTTP 测试 20 项。交叉工程绑定测试验证了有引用可绑定、无引用不越界，隐式 using 测试验证常见框架调用能绑定且不产生伪造来源事实。最新草案中的职责划分、UI 等价与数据库兼容仍是待人工验证的提案。

## 易读报告的风险与成功率评估

网页与 Markdown 现在共用同一份确定性风险评估：根据封存报告中的运行缺口、WPF/Windows Forms、SQLite/SQLCipher 声明、C++/Electron 目标和未解析语义事实，列出风险依据、可能影响、处理验证办法及主要难点。风险等级是处理优先级，不是发生概率。当前运行缺少旧系统行为基线、目标技术样例和迁移后回归结果，因此成功率不能可信地表示为百分比；报告列出待完成的验收条件，并区分未来的实测场景通过率与项目成功预测。原始封存 JSON 不变，Markdown 文件已重新生成。
