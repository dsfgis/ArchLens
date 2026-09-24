# C# 项目 → Java 只读分析

当前支持矩阵：C# 12.x → Java 21.x。`.NET 8`/`net8.0` 是框架版本，不应填入 C# 语言版本字段。其他语言版本保留 UNKNOWN，不借用最近版本规则。

## 从网页运行

从项目根目录启动 `ArchLensClient/start.ps1 -JdkHome <JDK21目录>`，访问本机 4173。需要现有 ArchLens PG 存储配置；模型使用后端已有配置，源码和路径不进入模型。

1. 点击“填入 C# 项目示例”，或输入自己的 C# 根目录并点击“发现 C# 项目文件”。
2. 检查候选文件清单、排除项及调查目标；按项目实际情况填写 C# 语言版本和 Java 版本。发现入口默认目标 Java 21，可在提交前调整。
3. 提交；示例源语言版本留空，澄清时回答 `12`。每次回答生成新修订。
4. 查看项目配置、依赖声明、项目引用和源码特征；展开原文位置/来源哈希，下载 JSON，切换历史。报告还包含金额、JSON、异步等迁移验证建议。

示例在 [csharp-java](../examples/scenarios/csharp-java/)，包含两个 .csproj 和两个 .cs，不执行这些项目。真实业务项目尚未指定，本次验证不代表任何业务项目已完成迁移分析。

## 发现与分析范围

`POST /api/investigations/discover-csharp` 接收 `{sourceRoot}`。最多 1000 个候选文件、50 MB、10000 个目录项、20 层与 10 秒；超限整体拒绝，不输出截断清单。排除 bin、obj、.git、.vs、.idea、node_modules、packages、符号链接及常见 .g/.generated/.designer.cs 文件。发现 .cs/.csproj、Directory.Build.props/targets、Directory.Packages.props。目录外或被排除的引用不会自动读取。

目录清单不是 MSBuild 有效编译清单。Compile Include/Remove、Import、条件、属性表达式、SDK 默认项、源生成器、中央/传递包版本和外部引用不求值，必须审阅覆盖缺口。提交后的报告绑定显式文件和哈希，采集时会重新检查路径与漂移。

| 规则 | 实际结果范围 |
| --- | --- |
| CS_PROJECT_PROFILE | SDK、TFM、LangVersion、Nullable、宿主/UI 等直接 XML 声明；UNKNOWN |
| CS_PROJECT_DEPENDENCY | PackageReference/PackageVersion/Reference/FrameworkReference 声明；不保证已解析或存在 Java 等价包 |
| CS_PROJECT_REFERENCE | ProjectReference 声明及目标是否已采集；不等于有效调用图 |
| CS_DECIMAL / CS_UNSIGNED | 确认的语言关键字迁移差异，数值与业务行为另验 |
| CS_AWAIT / CS_SERIALIZATION | 未绑定的异步/属性候选，保留 UNKNOWN |

XML 证据绑定整个声明文件，不声称已定位元素行。DTD/外部实体拒绝；源文件不进入模型上下文。项目语言版本与请求明显冲突时撤销全部规则发现，不选择有利版本。

声明处理依据 [Microsoft .NET SDK 属性说明](https://learn.microsoft.com/en-us/dotnet/core/project-sdk/msbuild-props) 和 [MSBuild 项目项说明](https://learn.microsoft.com/en-us/visualstudio/msbuild/common-msbuild-project-items)。本次实现未运行其构建求值过程。

## 验证

从项目根目录执行：

```powershell
npm --prefix ArchLensClient run check
node ArchLensClient/tests/http.test.mjs
.\ArchLensService\scripts\web-verify.ps1 -PgOnly -JdkHome '你的 JDK 21 目录'
```

PgOnly 排除原 Neo4j/双库测试；该场景不生成完整事实图，投影 NOT_APPLICABLE。实际记录见 [2026-09-22 验收](verification-csharp-2026-09-22.md)。

完整流程不等于完整语义支持。Roslyn 绑定、跨项目调用图、LINQ/泛型/异常/事务等全面行为验证、自动转换 Java 和运行迁移代码均未实施。
