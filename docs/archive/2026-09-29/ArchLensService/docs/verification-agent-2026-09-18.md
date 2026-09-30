# Agent 编排验证记录 — 2026-09-18

本记录验证 Java/CLI 编排层、真实 DeepSeek 工具调用与 ArchLens 自身 PG/Neo4j 存储。输入均为仓库合成样例，不执行样例 SQL、不编译被调查源码、不访问业务表。网页未接入新 Agent，本记录不声称网页闭环已完成。

## 自动化与打包

JDK 21.0.11、Maven 3.9.14。普通离线 `mvn verify`：108 项，97 通过、0 失败/错误、11 跳过（10 项数据库集成未启用，加 1 项 Windows 符号链接权限）。[离线摘要](verification/agent-20260918/offline-tests.json)

最终启用真实存储，`mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=.local/m2 -DforkCount=0 verify` 于 09:18:44 +08:00 成功：**108 项，107 通过、0 失败、0 错误、1 跳过**。包括 15 项 Agent 编排、4 项新增原生工具网关测试、9 项 PG 存储集成测试和 1 项 Neo4j 投影测试。[最终摘要](verification/agent-20260918/final-tests.json)

验证内容包括：真实解析工具与规则选择；遗漏规则覆盖缺口；版本澄清/恢复/来源重采集；旧回答拒绝；金仓目标不借用 PG 规则；越权工具与路径扩展拒绝；伪造版本、证据、outcome 拒绝；错误反馈后模型纠正；模型/工具/时间预算；取消迟到响应；来源漂移撤销；无密钥降级；JSON 严格解析、远端错误脱敏和固定端点。测试通过不能证明自然语言解释的语义全部正确。

七场景打包 CLI 烟测覆盖 22 条注册规则，旧列分析 CLI 保持 3 节点/2 边/3 诊断，14 项第三方声明检查通过。[场景摘要](verification/agent-20260918/scenarios/summary.json)、[列分析烟测](verification/agent-20260918/column-smoke.json)

初始沙箱存储测试发生超时，放宽执行环境后的测试子 JVM 又出现连接重置。真实 CLI 的 PG 封存/导出可用；切换为同 JVM 测试后，先通过 [六项 PG 测试](verification/agent-20260918/pg-tests.json)，随后完成上述全部双库回归。初始失败保留在 [首次摘要](verification/agent-20260918/storage-attempt.json) 与 [PG 重试摘要](verification/agent-20260918/pg-attempt.json)。同 JVM 模式仅用于本次运行，没有修改项目默认 fork 配置；根本网络原因未独立证实。

最终健康检查返回 PostgreSQL 16.15、Neo4j/2026.08.1。早先一次 7687 TCP 检查失败属于该时刻的观察，不代表最终服务仍不可用。

## 真实模型闭环

生产网关使用后端配置的 DeepSeek，调用合成 MySQL8.0.36 → PG16 目标。初次试跑在 finish 超过六条解释时被拒绝并降级；随后补充工具 Schema 的 maxItems 和具体纠正提示，并加入纠正循环测试。

最终真实调用五轮完成：propose_target → list_rules → run_analysis → read_evidence → finish；`orchestration=MODEL_TOOL_LOOP`，无诊断，六条未核实解释。目标从自然语言解析，未在请求预填 target。[实际报告](verification/agent-20260918/live-model.json)

另一次真实模型澄清与 PG 恢复：

| 项目 | 证据 |
| --- | --- |
| Case | `d3fec7be-da56-4c4a-b043-1b62f978bed1` |
| 修订 1 Run | `b2266db5-5c0a-4c60-9ca1-38a96c9f497a` |
| 修订 1 结果 | NEEDS_CLARIFICATION，询问 sourceProfile.version，PG 封存并回读 |
| 父报告 canonical 哈希 | `8a41ca549013777aa49b374b65676c58b18bd855c4841c410ad42f6c003cdbfc` |
| 用户回答（合成样例） | `8.0.36` |
| 修订 2 Run | `67b54b29-7696-462e-8b8c-782b1a0500c0` |
| 修订 2 结果 | PARTIAL，MODEL_TOOL_LOOP，五轮工具调用、五条选中规则、六条解释、无诊断 |
| 修订 2 canonical 哈希 | `38e6b4957a6a054a769c51956ecbaf3700322acbdefd0aa5b7f2de70079b79e6` |
| 回读状态 | 修订 1 latestRevision=false；修订 2 latestRevision=true；无新场景事实图，NOT_APPLICABLE |

原始导出：[澄清报告](verification/agent-20260918/pg-clarification.json)、[恢复报告](verification/agent-20260918/pg-resumed.json)。CLI 无密钥路径也实际完成文件澄清/恢复，第二修订明确为 DETERMINISTIC_FALLBACK：[降级报告](verification/agent-20260918/offline-resumed.json)。

## 交付边界

本批完成模型参与的受限调查循环及可恢复存储，不等于完整产品验收。尚无网页调查工作台、联网查阅官方文档工具、业务源数据库在线采集、完整跨语言语义绑定或 Oracle → 金仓兼容规则。模型读取匿名证据摘要，解释仍标记 MODEL_EXPLANATION_UNVERIFIED；确定性规则、覆盖缺口和 UNKNOWN 不受模型覆盖。

运行方式、请求字段及失败行为见 [Agent 使用说明](agent-orchestration.md)。新增核心、工具、CLI、脚本和测试使用中文注释；未新增依赖或修改存储迁移脚本。
