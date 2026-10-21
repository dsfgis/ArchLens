# .NET WPF → C++/Electron 真实草案验证（2026-10-19）

## 范围

真实项目：`D:\study\code\aitraining\wechatDashboard`，只读调查，不执行项目构建、restore 或运行被分析代码。目标是 `.NET 10.0 + WPF + SQLite/SQLCipher` 迁移到 `C++20 + Electron`，目标平台为 Windows、macOS、Linux。

本次只验证现有通用 Agent 的静态调查与 `UNVERIFIED_PROPOSAL` 草案，不验证正式 `MigrationPlan`、A1 完成门槛或行为等价。

## 环境与入口

- JDK 21.0.11、Maven 3.9.x、.NET SDK 10.0.401；本机网页 `127.0.0.1:4173`。
- 先构建 `ArchLensService/analyzers/dotnet/ArchLens.Semantics.csproj`。
- 先调用 `POST /api/investigations/discover-dotnet`，审阅显式文件清单后提交；未执行项目本身的构建命令。

## 实际结果

- 发现清单：127 个文件，其中 120 个 `.cs`、6 个 `.csproj`；WPF、SQLite、SQLCipher、.NET 10.0 为项目声明。
- 成功 Run：`72e12f2d-ceb5-44ca-a063-06f737433da8`，Case `872ce45d-28f7-46f4-bb98-b5b36f63039c`。
- 报告状态：`PARTIAL`；编排：`MODEL_TOOL_LOOP`；模型调用 5 次；无运行诊断；迁移草案 9 个阶段；草案状态 `UNVERIFIED_PROPOSAL`。
- 语义事实：5,259 条，覆盖 119 个 C# 文件。
  - `SOURCE_BOUND`：1,676 条（调用/对象创建等）。
  - `HOST_REFERENCE_BOUND`：1,805 条；只表示绑定到取证宿主引用，不能当作 WPF/Windows API 兼容证明。
  - `UNRESOLVED`：850 条；未解析不等于代码错误。
- 解析缺口：实际编译项未求值、生成源码未纳入、C# 语言版本未求值、宿主引用不是目标框架引用、存在编译错误/缺失引用、运行行为未验证。
- Java `mvn verify`：213 项，0 失败/错误，33 跳过；`DotnetSemanticsTest` 5 项全部通过。
- `dotnet build ArchLens.Semantics.csproj -c Release`：0 警告、0 错误。

## 失败与修复

1. 初次运行返回 `ROSLYN_TOOL_NOT_BUILT`：Java 进程从 `ArchLensClient` 启动，分析器查找路径只覆盖 `ArchLensService/...`，未查找 `../ArchLensService/...`。已在 [DotnetSemantics.java](../../ArchLensService/src/main/java/io/archlens/investigation/dotnet/DotnetSemantics.java) 增加相对项目根目录的回退。
2. 第二次运行返回 `ROSLYN_PROCESS_FAILED`：Roslyn 分析器使用 `Console.In.ReadToEnd()`，Windows 控制台按本地代码页读取 Java 写入的 UTF-8 JSON，触发 `JsonException`。已改为显式 UTF-8 stdin/stdout 字节流，并保留不含源码的类型级诊断。修复后同一清单成功产出 5,259 条事实。

## 报告草案边界

草案提出 C++20 核心 + Electron 渲染层 + IPC/本地模块的三层结构，并把现状基线、依赖核实、宿主引用归属、桥接契约、未解析项、数据层、业务迁移、UI 适配和三平台验证拆成 9 个阶段。草案中的职责划分、接口边界、WPF 交互映射和 SQLCipher 数据兼容仍需人工核验。

当前可下载的完整证据：

- JSON：`ArchLensService/.local/reports/wechat-cpp-electron-72e12f2d-ceb5-44ca-a063-06f737433da8.json`
- Markdown：`ArchLensService/.local/reports/wechat-cpp-electron-72e12f2d-ceb5-44ca-a063-06f737433da8.md`

这些文件位于忽略版本控制的本地目录；报告约有 3.2 MB 原始 JSON。网页地址：`http://127.0.0.1:4173/#run=72e12f2d-ceb5-44ca-a063-06f737433da8`（服务状态以实际运行进程为准）。

## 未完成事项

- XAML 界面文件未进入发现清单；当前发现明确报告 6 个 XAML 未采集，因此 Electron 界面重建缺少确定性结构证据。
- 未求值实际 MSBuild 编译项、条件编译、目标框架引用和生成源码；未执行被分析项目的构建或运行。
- 850 条未解析事实、1,805 条宿主引用绑定尚未逐组归属；不能直接换算为迁移工作量或 WPF 调用数量。
- 尚未建立旧系统输入输出基线、C++/Electron 端到端样例和迁移后回归，因此不能声明功能等价或 A1/A2 完成。
- 正式 MigrationPlan v1、Java 完成门槛、计划封存和人工接受记录仍待 T13/T14。
