# .NET 现状预览分析内容验证

日期：2026-10-03。针对用户反馈“调查报告里没有分析内容”，在原有 .NET 工程清单基础上增加确定性的声明归纳与页面展示。用户本机源码没有复制到本记录；源项目未构建或运行。

## 实现与实际结果

- `DotnetCurrentStateAssessment` 从已解析的工程声明生成目标框架、桌面 UI、本地存储和项目引用四类 `Finding`。每条保留来源工程、原字节 SHA-256 对应的 sourceId、整文件证据及待核实建议，结果仍为 `UNKNOWN`，不推断 Java 兼容性。
- `InvestigationEngine` 只在 `CURRENT_STATE` 调查且来源稳定时加入这些发现；`CONTENT_INVENTORY_ONLY` 继续说明缺少语义发现。网页把“现状分析（工程声明）”放在逐工程清单之前，预览 JSON 的 `report.findings` 同步包含结论。
- 用户提供的本机 WPF 项目实际预览：6/6 工程声明已解析，新增 4 条结论。分别指出 4 个 Windows 目标框架工程与 2 个无平台后缀工程、3 个 WPF 与 1 个 Windows Forms 声明工程、SQLite/SQLCipher 包声明及 11 条项目引用。页面已核对这四条结论及来源工程。
- 本机结果保存在未纳入 Git 的 `ArchLensService/target/verification/wechatdashboard-dotnet-analysis-20261003-120340.json`。本次另写人工静态复核补充报告于同目录，明确区分自动结论和人工阅读所得。Maven `verify`：182 项、失败 0、错误 0、默认跳过 29；`npm run check` 通过，`npm test` 16/16 通过。新增回归测试验证结论、证据引用、BOM 项目和报告 canonical 回读。

## 边界

这四类结论只来自 `.csproj` 声明。当前没有解析 11 个 XAML 文件，没有将 148 个源码文件做符号/调用语义绑定，也没有评估 Windows 原生接口、SQLite 文件/密钥或交互行为。目标 Java 框架、改造工作量、迁移步骤和行为等价结论均待后续能力及真实验证。
