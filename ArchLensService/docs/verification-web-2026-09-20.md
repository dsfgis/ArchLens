# 网页真实闭环验证 — 2026-09-20

交付功能：MySQL 8.0 → PostgreSQL 16 的有限 SQL 兼容性调查。真实浏览器 → Node 同源接口 → Java Agent → DeepSeek 工具调用 → 确定性分析 → PostgreSQL 报告/修订封存 → 浏览器回读与下载，全程未使用 UI 夹具。

## 本次修改

- 完成现有网页链路的真实联调；`GET /api/investigations/example` 提供仓库自带的合成样例路径与请求。“填入 MySQL 示例”自动填入可读取文件，用户无需手工拼接路径。
- 增加 `web-verify.ps1 -PgOnly`，为无图的网页调查单独验证 PG 与相关回归，完整双库模式仍保留。
- 增加真实结果核验脚本 `ArchLensClient/tests/verify-live-run.mjs` 和取消烟测 `verify-live-cancel.mjs`，验证附件内容、来源哈希、父子修订、重复回答拒绝和取消状态保持。
- 补齐使用说明、规格追踪和上次遗留的验证记录链接。

## 真实浏览器运行

| 项目 | 本次证据 |
| --- | --- |
| 页面 | `http://127.0.0.1:4173/`，实际 Node/Java 服务 |
| Case ID | `cd176b26-583d-4dc2-984a-949968c47c77` |
| 修订 1 | `694ab3e4-b28a-4917-af5b-99c047aefdc3`；NEEDS_CLARIFICATION |
| 澄清 | 模型询问 MySQL 源版本；浏览器回答 `8.0.36` |
| 修订 2 | `6353f253-2974-49f1-aed9-b4db72b91a96`；PARTIAL |
| 模型编排 | MODEL_TOOL_LOOP；5 轮，propose_target → list_rules → run_analysis → read_evidence → finish |
| 结果 | 1 个实际来源、7 条发现（含未评估范围）、7 项覆盖缺口、6 条待核实模型解释；无编排诊断 |
| 来源 | 仓库合成 `examples/scenarios/mysql-postgresql/schema.sql`，原文件 SHA-256 与报告一致 |
| 修订关系 | 第二报告 parentReportHash 等于第一报告 canonical 哈希；第一修订非最新，第二修订最新 |

重启前证据：[摘要](verification/web-20260920-before-restart/evidence.json)、[第一修订](verification/web-20260920-before-restart/first.json)、[第二修订](verification/web-20260920-before-restart/second.json)。

停止本次 Node 实例并重新启动真实服务后，两个修订均从 PG 回读，哈希未变；并非依赖内存列表或浏览器 localStorage。[重启后证据](verification/web-20260920-after-restart/evidence.json)、[下载报告](verification/web-20260920-after-restart/downloaded.json)。

浏览器实测：运行中状态自动刷新为澄清/报告；回答输入不被刷新清空；旧修订隐藏回答入口；点击报告下载触发实际附件下载；服务重启后地址中的 Run ID 恢复报告。桌面 1366×900 和窄屏 390×844 已观察布局，DOM 宽度检查无横向溢出，测试后恢复默认窗口。

## 测试与负例

- `web-verify.ps1 -PgOnly`：**103 项，102 通过、0 失败、0 错误、1 项 Windows 符号链接权限跳过**。包含 2 项实际 PG 网页协议集成、3 项网页输入契约测试和其余相关确定性/模型回归。此模式明确排除原有 `Neo4jProjectionTest` 和含双库测试的 `StorageIntegrationTest` 共 10 项，不声称全双库回归通过。
- Node HTTP 契约测试 **7 项通过**：跨域拒绝、错误参数、并发限制、异步票据/目录保存、根目录变更拒绝、错误/旧回答状态、报告附件、真实样例入口等行为按测试用例分组验证。
- 对实际第一修订重新提交回答，返回 **409 / STALE_ANSWERS**；Case 仍只有两个修订，旧报告哈希不变。
- 实际报告下载返回 attachment，解析内容与 PG 报告完全一致；来源 SHA-256 与实际样例文件一致。
- [真实取消证据](verification/web-20260920-after-restart/cancel.json)：新合成运行在收到票据后取消，10 秒后回读仍为 CANCELLED，未生成封存报告；不据此推断所有在途模型调用已结束。PG 协议测试另覆盖取消检查点保留。
- JavaScript 语法检查通过。测试使用的源文件均为合成样例；未执行样例 SQL、未构建被调查项目、未读写业务数据库。

本次 Maven 日志位于 `target/web-verify-pg-20260920.log`；可复核的测试摘要见 [tests.json](verification/web-20260920-after-restart/tests.json)。

## 启动与复现

从项目根目录，当前机器可直接运行：

```powershell
.\ArchLensClient\start.ps1 -JdkHome 'D:\Program Files\Java\jdk-21.0.11'
```

打开本机 4173 → 点击“填入 MySQL 示例” → “提交调查” → 对源版本问题回答 `8.0.36` → 查看第二修订的证据报告、下载和历史。后台凭据仍来自现有当前用户/DPAPI 配置，不进入页面或导出。

可用以下命令独立验证已完成的真实网页 Case，输出目录须不存在：

```powershell
node .\ArchLensClient\tests\verify-live-run.mjs http://127.0.0.1:4173 694ab3e4-b28a-4917-af5b-99c047aefdc3 6353f253-2974-49f1-aed9-b4db72b91a96 ('ArchLensService/target/web-recheck-' + [guid]::NewGuid())
```

## 边界

本次 PostgreSQL 可连接；Neo4j 7687 不可用，未修改其配置。该调查不产生完整事实图，图投影明确为 NOT_APPLICABLE，因此不阻断当前闭环。此结果不证明旧列场景的 Neo4j 投影当前可用。

目前是本机单用户、显式文件、有限 SQL 规则的调查闭环，不是整库迁移验收。PARTIAL、UNKNOWN、覆盖缺口和 MODEL_EXPLANATION_UNVERIFIED 均保留；生产鉴权、自动全项目采集、业务元数据在线采集及完整迁移语义不在本次交付范围。
