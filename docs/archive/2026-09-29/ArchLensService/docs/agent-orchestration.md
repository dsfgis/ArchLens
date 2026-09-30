# 只读 Agent 编排层

2026-09-18 实现 `archlens-agent-0.1`。模型通过 DeepSeek 原生 `tool_calls` 逐轮选择动作，并使用真实工具结果决定下一步。入口包括 Java API、CLI 和本机网页工作台。网页通过 WebAgentCli 提交和恢复调查，PG 管理修订与封存；旧“AI 梳理目标”保留在 draft.html。运行方式与本次验证边界见 [前端说明](../../ArchLensClient/README.md)。

## 已实现的循环

1. `propose_target`：模型从用户目标解析场景、源/目标产品及版本；显式字段不可覆盖，未声明且原文不存在的版本不能猜测。
2. `list_rules`：查询本地版本限定规则目录。模型选中的规则必须属于已解析目标的适用集合。
3. `run_analysis`：读取请求明确授权的文件，执行确定性规则。分批选择会合并规则并重新分析；遗漏规则记录 `RULES_NOT_SELECTED`。
4. `read_evidence`：模型读取发现的匿名证据摘要。只能引用本轮确实存在的 finding/evidence ID。
5. `ask_clarification`：缺少信息时结束本轮并保存 `NEEDS_CLARIFICATION`；用户回答后启动下一修订，再次采集来源。
6. `finish`：提交最多六条证据解释及验证建议。程序校验引用、已读取状态和 outcome 一致性；解释标为 `MODEL_EXPLANATION_UNVERIFIED`，不会变成规则事实。

工具顺序有前置条件，但下一动作来自模型。错误返回可纠正的稳定提示；每修订累计三次无效工具动作终止模型循环，不要求连续发生。模型不能定义新工具、任意选择文件、运行命令、执行 SQL 或生成事实图边。规则仍是已实现的 25 条，范围见 [规则说明](scenario-rules.md)。Oracle → 金仓可解析、澄清和报告未知项，但没有金仓规则时不能宣称兼容。

## 输入及发送边界

输入示例：[自然语言目标](../examples/scenarios/mysql-postgresql/agent.json)、[缺少源版本](../examples/scenarios/mysql-postgresql/agent-clarify.json)。`files` 相对输入 JSON 所在目录；禁止绝对路径和越界，包括真实路径解析后的符号链接越界。模型不能修改 `files`、采集预算、列适配请求或存储 Case/Run。

新的 Agent 通道向模型发送：用户填写的目标、技术字段、约束、不变量、澄清回答、授权文件数量、本地规则元数据、匿名发现 ID/证据 ID/证据种类/规则结果/覆盖缺口代码。不会自动加入文件路径、源码、业务对象名、原文证据位置、数据库地址、凭据、完整报告或图。用户自由文本会作为输入发送，因此不要在目标或回答中粘贴密码。原有 `parse-target` 仍只发送描述。

匿名摘要可用于解释规则含义和适用限制，不足以证明业务语义。模型解释的自然语言正确性没有被程序证明。请求中的产品/版本也是用户声明，保留 `DECLARED_PROFILES_UNVERIFIED`。不存在外部网页检索或官方文档自动下载工具。

## 运行与澄清

从 `ArchLensService` 运行，`ARCHLENS_JAVA_HOME` 指向 JDK 21。先执行 `scripts/build.ps1`。密钥使用后端 `DEEPSEEK_API_KEY`；`agent.ps1` 优先当前进程，其次读取已配置的当前用户环境变量，退出时恢复进程变量。没有密钥时明确降级，不提示伪成功。

```powershell
$output = 'target/agent-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff') + '.json'
.\scripts\agent.ps1 -Command agent-investigate -CommandArgs @('examples/scenarios/mysql-postgresql/agent.json', $output) -JdkHome $env:ARCHLENS_JAVA_HOME
```

命令输出 receipt 包含 `status/revision/reportHash/output`，报告包含 `interpretedTarget/selectedRuleIds/trace/investigation/explanations/diagnostics`。退出码 0 表示成功生成报告，应继续检查 `orchestration` 和报告 status。`DETERMINISTIC_FALLBACK` 表示模型未完成闭环；`MODEL_TOOL_LOOP` 既可能成功解释，也可能停在澄清。报告状态为 PARTIAL、NEEDS_CLARIFICATION 或 CANCELLED，不表示迁移已完成。

下面演示版本澄清。必须读取问题后回答；若模型询问其他字段，应按实际问题填写，不批量猜答案。

```powershell
$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
$request = 'examples/scenarios/mysql-postgresql/agent-clarify.json'
$firstPath = "target/clarification-$stamp.json"
$receipt = .\scripts\agent.ps1 -Command agent-investigate -CommandArgs @($request, $firstPath) -JdkHome $env:ARCHLENS_JAVA_HOME | ConvertFrom-Json
$report = Get-Content -LiteralPath $firstPath -Raw | ConvertFrom-Json
$report.questions | Format-Table questionId, field, prompt
if ($report.status -ne 'NEEDS_CLARIFICATION' -or $report.questions.Count -ne 1 -or $report.questions[0].field -ne 'sourceProfile.version') { throw '请根据实际问题填写回答。' }
$answerPath = "target/answers-$stamp.json"
$answers = @{}
$answers[$report.questions[0].questionId] = '8.0.36'
$body = @{ schemaVersion='archlens.agent.v1'; parentReportHash=$receipt.reportHash; answers=$answers } | ConvertTo-Json -Depth 6
[IO.File]::WriteAllText((Join-Path (Get-Location) $answerPath), $body, [Text.UTF8Encoding]::new($false))
.\scripts\agent.ps1 -Command agent-resume -CommandArgs @($request, $firstPath, $answerPath, "target/resumed-$stamp.json") -JdkHome $env:ARCHLENS_JAVA_HOME
```

回答必须覆盖本轮全部 questionId，并绑定父报告 canonical JSON 哈希；不能用文件字节哈希替代。请求变化或父报告错误返回 `STALE_ANSWERS`。文件模式允许从同一父报告创建独立分支，不能提供跨进程唯一消费保证；需要唯一最新修订时使用存储入口。

## PostgreSQL 持久化

```powershell
.\scripts\agent.ps1 -Command agent-investigate-store -CommandArgs @('examples/scenarios/mysql-postgresql/agent-clarify.json') -JdkHome $env:ARCHLENS_JAVA_HOME
# 用 SEALED 回执中的 runId/reportHash 及实际问题构造答案文件后：
.\scripts\agent.ps1 -Command agent-resume-store -CommandArgs @('examples/scenarios/mysql-postgresql/agent-clarify.json', '<父运行 UUID>', '<答案 JSON 路径>') -JdkHome $env:ARCHLENS_JAVA_HOME
```

新调查创建 Case；恢复调查必须是该 Case 最新已封存的澄清报告。分配修订时在事务内条件更新 latest_revision，防止两次回答同时消费同一修订。请求哈希、租约和 epoch 保护封存；取消后拒绝迟到结果。沿用 `run-export/run-status/run-cancel`，配置见 [存储说明](investigation-storage.md)。数据库 state 为 PARTIAL 时，报告内可能为 NEEDS_CLARIFICATION，应查看导出报告。未新增数据库迁移；旧 V001 校验和不变。

仅列适配调查有已绑定的事实图时投影 Neo4j；新场景没有图时为 NOT_APPLICABLE。PG 是报告事实源，Neo4j 投影失败不会丢失报告，可用 `projection-retry` 重试。当前业务源数据库采集不属于工具范围。

## 预算、审计与限制

- 每修订最多 16 次模型调用、24 次工具调用、120 秒；最多三次分析工具执行、八个澄清修订。实际限额由请求收紧。
- 模型单次请求最多 60 秒，响应接收上限 256000 字节。超时/取消后丢弃迟到回答；解析器仍使用原有协作式时间预算，单次语法解析不能硬中断。
- 模型初始可见前 40 条发现，`findingsTruncated` 明确剩余范围；完整确定性发现留在本地报告。每份解释最多六条，不代表覆盖全部发现。
- `trace` 保存工具名、参数/结果哈希、错误码及工具执行耗时；不保存模型隐藏思考。哈希日志能核对引用，不提供完整模型原始对话重放。
- 解释完成前重新检查来源哈希。漂移或最终超时会撤销当前规则结论和模型解释、保留 UNKNOWN 与来源清单；这不是跨文件原子快照。
- 缺少密钥、鉴权失败、格式错误、循环预算耗尽时保留已完成的确定性分析，或在剩余预算内尝试降级。不得将降级报告称为模型闭环成功。

验证与限制记录见 [2026-09-18 验证](verification-agent-2026-09-18.md)。原生工具调用协议参考 [DeepSeek Function Calling](https://api-docs.deepseek.com/guides/tool_calls/)。

C# 项目分析入口、声明范围及边界见 [C# → Java 分析](csharp-java.md)。
