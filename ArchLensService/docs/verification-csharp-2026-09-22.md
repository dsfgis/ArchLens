# C# 项目 → Java 网页闭环验收 — 2026-09-22

本次交付：C# 12 → Java 21 的只读项目调查。真实浏览器发现候选文件 → Node/Java → DeepSeek 版本澄清 → 确定性规则 → PG 封存 → 报告下载与历史回读。使用合成多项目目录，不运行被分析项目、不执行 MSBuild/NuGet、不访问业务数据库。

## 实现

- `ArchLensClient/csharp-project.mjs` 与 discover-csharp 接口：有界目录候选发现、排除项、显式清单；超限整体拒绝。
- `CSharpProjectRules.java`：安全 XML 解析及项目配置、直接依赖声明、项目引用采集状态。规则目录 1.1 共 25 条，C# 包含原四条与新增三条。
- 工作台新增 C# 示例及发现按钮、语言版本说明、整文件 XML 证据标记。联调中发现模型混淆语言/框架版本，修正后真实问题明确仅询问 C# 语言版本。
- 调查报告包含模块、依赖、金额、JSON、异步等独立迁移验证建议；模型只见匿名证据摘要，项目/包名及源码不进入上下文。

## 最终真实结果

| 项目 | 证据 |
| --- | --- |
| Case | `100a7e45-0efd-47bc-8ea7-91071291b1bd` |
| 第一修订 | `6d65ca3e-dca0-4419-aa23-cc168cef38d4`；NEEDS_CLARIFICATION；2 次模型调用 |
| 回答 | 浏览器填入 C# 语言版本 `12` |
| 第二修订 | `6c6d48ec-fcaa-41ca-bd68-eb60e6d0cdc8`；PARTIAL；MODEL_TOOL_LOOP；5 次模型调用 |
| 结果 | 4 来源、10 条发现（9 条规则发现 + 1 未评估范围）、9 项覆盖缺口、6 条模型解释 |
| 规则 | 七个 C# 规则 ID 均有结果；项目声明全部 UNKNOWN，源码数值关键字为局部 INCOMPATIBLE |
| 持久化 | 实际 Node 重启前后，两份封存报告哈希一致；PG 回读历史修订 2、1 |
| 下载 | 浏览器触发真实附件下载；API 附件内容与 PG 报告逐字段相等 |
| 负例 | 旧回答再次提交返回 409 / STALE_ANSWERS，仍只有两个修订；旧修订网页隐藏回答入口 |

[重启前摘要](verification/csharp-20260922-before-restart/evidence.json)、[重启后摘要](verification/csharp-20260922-after-restart/evidence.json)、[第一修订](verification/csharp-20260922-after-restart/first.json)、[第二修订](verification/csharp-20260922-after-restart/second.json)、[下载报告](verification/csharp-20260922-after-restart/downloaded.json)。四个来源 SHA-256 已与磁盘逐个比对。

## 本次执行的检查

- 首轮 `build.ps1` 全部 `mvn verify`：117 项，104 通过、0 失败/错误、13 跳过（存储开关未启用及符号链接权限）；BUILD SUCCESS。
- 最终 `web-verify.ps1 -PgOnly`：107 项，106 通过、0 失败/错误、1 项 Windows 符号链接权限跳过；含实际 PG WebAgentCli 两项回归。该模式排除原 Neo4jProjectionTest/StorageIntegrationTest 共 10 项，未宣称当前 Neo4j 联调通过。[机器摘要](verification/csharp-20260922-after-restart/tests.json)
- 新增四项 Java 规则测试：XML 实体拒绝、引用不读取、声明与来源哈希、条件/冲突/版本门控。
- HTTP 9 项通过，包括实际目录发现、构建输出排除、无项目/非法目录/超量拒绝及跨域拦截；JavaScript 语法通过。
- 打包 CLI 七场景烟测通过，目录 `target/scenario-smoke-20260922-054800-020`，25 条规则目录检查通过；旧入口保持。
- 浏览器桌面 1366×900、窄屏 390×844 检查，DOM 无横向溢出；已恢复默认视口。观察最终版本提示、报告、历史、下载。

日志位于 `target/csharp-build-20260922.log`、`target/csharp-final-pg-20260922.log`、`target/csharp-scenarios-20260922.log`。网页持久化核验脚本为 `ArchLensClient/tests/verify-live-csharp.mjs`，参数依次为本机 URL、第一/第二 Run ID、新输出目录。脚本只接受仓库 C# 合成样例，不作为任意业务目录扫描工具。

## 复现与边界

[使用说明](csharp-java.md)：启动本机 4173 → 填入 C# 示例 → 发现并检查清单 → 提交 → 回答 12 → 报告/下载/历史。已有后端配置继续使用，重启命令从项目根目录执行：

```powershell
.\ArchLensClient\start.ps1 -JdkHome 'D:\Program Files\Java\jdk-21.0.11'
```

当前功能提供完整输入到报告流程，保留 PARTIAL/UNKNOWN。尚未证明完整 MSBuild 有效工程、Roslyn 符号绑定、跨项目调用图、行为等价或自动 Java 转换。本次未指定/分析真实业务项目。此无图场景投影 NOT_APPLICABLE，无需 Neo4j；不推导其他图场景当前可用。
