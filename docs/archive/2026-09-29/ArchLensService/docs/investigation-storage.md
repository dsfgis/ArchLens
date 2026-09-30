# 调查与双存储切片

初始实施日期：2026-09-17；当前文档同步：2026-09-18。PG 和 Neo4j 用于 ArchLens 自身存储。本文说明确定性调查与共享存储能力；新增模型调用、澄清及恢复入口见 [Agent 编排](agent-orchestration.md)，不代表全部 INV 任务完成。

## 可运行的链路

`InvestigationRequest → 受限文件采集 → 旧列分析或版本限定场景规则 → InvestigationReport → PostgreSQL → Neo4j 图投影（仅有已绑定图时）`。

- `CURRENT_STATE` 采集显式列出的 UTF-8 文件清单、SHA-256、字节范围、时间和覆盖缺口。它不是全项目语义扫描器。
- `COLUMN_CHANGE` 使用 `columnRequest` 引用原有 `OfflineRequest`，复用真实 Java/MyBatis 解析、事实图和影响分析。保留 `OFFLINE_CATALOG_UNVERIFIED` 和 `PARTIAL`。
- 其余场景现已接入 [22 条版本限定规则](scenario-rules.md)，输出带证据/依据/建议的 v2 报告。版本未知或矩阵外仍返回 `SCENARIO_RULES_UNAVAILABLE` 和 `UNKNOWN`；新场景没有已绑定图时，Neo4j 状态为 `NOT_APPLICABLE`。
- `investigate/investigate-store` 不调用模型；`agent-*` 通过受限工具循环调用模型及本地引擎。两者均不执行 shell、不修改业务文件、不采集业务行数据；两个数据库仅作自身存储。

## 支持范围与预算

源文件必须相对于调查请求所在目录；绝对路径、`..` 越界、解析到根目录外的符号链接均拒绝。显式来源上限 1000，单文件上限 5 MB，总逻辑来源字节上限 50 MB；默认 100 文件/10 MB/30 秒。统计及解析可能重复读取来源，总逻辑字节预算不是物理 IO 字节上限。

取消和时间预算在采集/校验边界检查；现有 Java/SQL 解析库的单次调用不能强制中断，时间预算是协作式，不承诺硬实时中断。来源在采集后复核；变化导致放弃相关列分析并记录 `SOURCE_DRIFT`。这不能保证跨文件原子快照，报告始终声明 `NON_ATOMIC_SOURCES`。

默认单次 CLI 的目录权限就是当前操作系统用户权限，没有生产用户鉴权和 HTTP API。OIDC 鉴权经 2026-09-21 用户决策本版本不实施，当前所有入口仅限本机单用户场景。

## 存储结构与一致性

PG 专用 schema 为 `archlens`，表为 `schema_version`、`investigation_case`、`investigation_run`。数据库不自动创建，使用运维指定的现有库。初始化在事务内加 advisory lock，校验已安装迁移的 SHA-256；重跑不清空数据。

Case/Run 的 UUID 由存储层生成；同 Case 的修订号通过行更新串行分配。每次运行生成新 revision，即使输入内容相同也不覆盖旧报告；目标、来源哈希及引擎版本进入 `inputFingerprint`。`run-status` 返回 `latestRevision`，最新运行失败时也不把旧结果伪装成最新。

Agent 新调查使用 `agent-investigate-store <请求>` 创建 Case，`agent-resume-store <原请求> <父运行 UUID> <回答文件>` 仅恢复最新澄清修订。回答绑定父报告 canonical JSON 哈希；事务内按期望 latest_revision 更新，拒绝并发重复消费。PG 的运行 state 为 PARTIAL 时，Agent 报告 status 可能为 NEEDS_CLARIFICATION；使用 `run-export` 查看问题及模型循环状态。沿用 V001 存储结构，未新增 schema 迁移。

运行的 epoch、180 秒租约、状态和请求哈希共同约束报告封存。已封存结果没有更新接口。取消/失败提升 epoch，迟到写入被拒绝。采集到的来源清单逐步写 checkpoint；取消或崩溃可以导出已有 checkpoint，但 checkpoint 明确为不完整，尚未完成的解析中间结果不会被伪装为报告。`run-expire` 显式将超期运行记为 FAILED，不启动自动后台恢复。

Neo4j 使用 `ArchLensProjection`、`ArchLensFact` 和 `ARCHLENS_DEPENDENCY`。关系 `kind` 保留原始语义，方向为 dependent → dependency；包含关系也保留其 kind，不能一律用于依赖传播。每个运行有独立键空间，图、证据和诊断的完整权威 JSON 留在 PG。投影只接收已通过 `FactGraph` 校验的封存图，用参数化 Cypher 在一个事务内写入并核对数量。

PG 与 Neo4j 不做分布式事务。PG 先封存并标记 `PENDING`；Neo4j 成功后 PG 改为 `READY`。故障保留 PG 报告，重试同 run 幂等补齐图；Neo4j 提交后 PG 标记前崩溃也可重试。Neo4j 的同 run digest 不一致时拒绝。当前是幂等重建缺失投影，不提供删除未知污染节点、无差别清库或自动灾难恢复工具。

## 配置与运行

以下命令均在 **ArchLensService** 目录运行，设置 `ARCHLENS_JAVA_HOME` 指向本机 JDK 21。

首次配置使用隐藏输入；此机器已按用户提供的信息配置，无需重复执行：

```powershell
.\scripts\configure-storage.ps1 `
  -PgUrl 'jdbc:postgresql://YOUR_HOST:5432/YOUR_DATABASE' -PgUser 'YOUR_USER' `
  -Neo4jUri 'bolt://YOUR_HOST:7687' -Neo4jUser 'YOUR_USER'
```

`.local/storage.json` 只含地址/账号；`.local/storage.credentials.xml` 的密码由 Windows DPAPI 绑定当前用户和机器。两者被忽略，不进入源码、示例或报告。脚本只在当前进程及 Java 子进程环境中解密，结束后恢复原变量。也可以直接设置以下后端变量覆盖本地配置：

`ARCHLENS_PG_URL`、`ARCHLENS_PG_USER`、`ARCHLENS_PG_PASSWORD`、`ARCHLENS_NEO4J_URI`、`ARCHLENS_NEO4J_USER`、`ARCHLENS_NEO4J_PASSWORD`、`ARCHLENS_NEO4J_DATABASE`。

地址禁止内嵌凭据及查询参数。Neo4j 支持显式 `bolt+s` / `neo4j+s`；当前 PG 连接未提供可配置 TLS 模式，应限定于已批准的内网部署。本地脚本不设置系统代理或系统默认 JDK。

```powershell
.\scripts\build.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME
.\scripts\storage.ps1 -Command storage-check
.\scripts\storage.ps1 -Command storage-init
.\scripts\storage.ps1 -Command investigate-store -CommandArgs @('examples/column-rename/investigation.json','new')
```

`RUNNING` 行返回 `caseId`、`runId`、`revision`；`SEALED` 行报告状态和投影状态。样例预期 `PARTIAL`、3 个图节点、2 条关系，Mapper 的列改名影响为 `YES`；兼容性仍 `UNKNOWN`。读取运行结果：

```powershell
# 用实际输出中的 UUID 替换变量值
$runId = 'YOUR_RUN_UUID'
$caseId = 'YOUR_CASE_UUID'
.\scripts\storage.ps1 -Command run-status -CommandArgs @($runId)
.\scripts\storage.ps1 -Command run-export -CommandArgs @($runId,('target/export-' + [guid]::NewGuid() + '.json'))
.\scripts\storage.ps1 -Command investigate-store -CommandArgs @('examples/column-rename/investigation.json',$caseId)
.\scripts\storage.ps1 -Command projection-retry -CommandArgs @($runId)
.\scripts\storage.ps1 -Command run-cancel -CommandArgs @($runId)
.\scripts\storage.ps1 -Command run-expire
```

导出使用 CREATE_NEW，拒绝覆盖已有文件。`run-cancel` 只修改 RUNNING；封存运行返回 `NOT_RUNNING`。错误仅输出稳定代码（SQL 错误含 SQLSTATE），不输出原始数据库异常。Java 退出码 0 表示命令成功，2 表示失败，3 表示报告已存 PG、图投影待重试；PowerShell 包装器将非零视为失败，保留具体输出。

离线新入口不依赖数据库：

```powershell
& "$env:ARCHLENS_JAVA_HOME\bin\java.exe" -jar target/archlens-0.1.0-SNAPSHOT-cli.jar `
  investigate examples/column-rename/investigation.json ('target/investigation-' + [guid]::NewGuid() + '.json')
```

旧 `analyze` 和模型描述解析入口继续保留。Agent 模型密钥使用 `scripts/agent.ps1` 加载；其存储命令内部复用 `storage.ps1`，不会使用页面填写的数据库密码。

## 初始化权限

连通、认证和 DDL 权限分别验证。应用账号需拥有专用 schema 的对象创建权限。如果没有数据库 CREATE 权限，可由管理员连接**目标数据库**预建：

```sql
CREATE SCHEMA IF NOT EXISTS archlens AUTHORIZATION appuser;
```

不要为应用账号授予 superuser。初始化器发现已有 schema 后不再尝试创建 schema。若已有同名 schema 属于其他应用，先由管理员检查归属，不自动接管。

## 验证

普通 `mvn verify` 不连接外部数据库；通过显式命令开启真实 PG/Neo4j 集成验证：

```powershell
.\scripts\storage.ps1 -Command storage-verify -JdkHome $env:ARCHLENS_JAVA_HOME
.\scripts\smoke.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME
```

真实测试只写 `archlens` schema 和专用 Neo4j 标签，创建随机 UUID 的合成调查并保留用于核查。覆盖报告回读、重复封存拒绝、并发修订、取消与 checkpoint、租约过期、图故障重试、跨运行隔离、七场景报告回读，以及 Agent 澄清封存和重复恢复拒绝。另用 PostgreSQL16 只读常量表达式/原生类型函数探针核对规则依据，不执行样例 DDL，不读写业务表。不会清理其他数据。2026-09-18 的实际执行方式、初始连接故障与最终双库结果见 [验证记录](verification-agent-2026-09-18.md)。

手工反例：复制调查样例到独立目录，使用 `DATABASE_MIGRATION`、`columnRequest: null`、空目标版本和合法本地文件；结果必须含澄清项和 `UNKNOWN`。把 `files` 改成 `../outside` 应拒绝；再次使用已存在输出路径应失败，原文件哈希不变。

## 依赖依据

新增 pgJDBC 42.7.13（BSD-2-Clause）和 Neo4j Java Driver 5.28.9（父 POM Apache-2.0），直接版本固定。官方资料：[pgJDBC 下载](https://jdbc.postgresql.org/download/)、[连接选项](https://jdbc.postgresql.org/documentation/use/)、[Neo4j 5.28.9 发布](https://github.com/neo4j/neo4j-java-driver/releases/tag/5.28.9)、[事务及超时](https://neo4j.com/docs/java-manual/current/transactions/)。这是本次选型和实现依据，不是完整传递依赖安全审计。
