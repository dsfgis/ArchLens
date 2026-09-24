# ArchLens 调查工作台

原生 HTML/CSS/JavaScript 与 Node.js 本机适配器，要求 Node.js 18+、JDK 21 和已构建的 Java 后端。首页已接入 Agent 调查提交、澄清回答、运行状态、证据报告、下载及历史修订；旧请求草稿入口保留在 `/draft.html`。

## 启动

在项目根目录运行，JDK 路径按本机实际位置设置：

```powershell
$env:ARCHLENS_JAVA_HOME = '你的 JDK 21 目录'
.\ArchLensService\scripts\build.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME
.\ArchLensClient\start.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME
```

访问 `http://127.0.0.1:4173`。可用 `ARCHLENS_CLIENT_PORT` 指定其他端口。服务仅监听本机，不能作为生产鉴权服务公开部署。

`start.ps1` 沿用 `ArchLensService/.local/storage.json` 与当前 Windows 用户 DPAPI 加密的 `storage.credentials.xml`，仅注入 Node/Java 子进程环境；已有环境变量优先。需要先按 [存储说明](../ArchLensService/docs/investigation-storage.md) 配好 ArchLens 自身 PG/Neo4j 并初始化，启动页面不会自行初始化数据库。DeepSeek 密钥优先进程、其次当前用户配置，均未配置时隐藏输入。

用 `start.ps1 -JdkHome ... -Offline` 可禁用模型，验证确定性降级和澄清；**Offline 不代表无需 PostgreSQL**。直接 `node server.mjs` 要求环境已配置，否则页面显示存储/后端不可用，旧草稿页面仍可使用。

## 完整操作

1. 输入授权根目录和相对文件清单，每行一个。只读明确文件；C# 可点击发现候选并检查清单，不运行源码，不连接业务数据库。
2. 填写目标和技术版本；未知版本留空，或选择由模型解析目标。重构场景仍须提供前后文件及 `*.refactor.json` 计划，参见 [支持矩阵](../ArchLensService/docs/scenario-rules.md)。旧 `COLUMN_CHANGE` 适配仍使用原 CLI，网页不将草稿冒充已验证变更。
3. 提交后拿到真实 PG Run ID，自动刷新状态；可取消。后台错误保留 FAILED，服务异常退出留下的 RUNNING 在租约到期后标记为中断。
4. 需要澄清时，逐项回答。回答绑定父报告 canonical 哈希，PG 在事务中拒绝旧修订/重复恢复；网页提交过的目录保持锁定。旧 CLI 报告未记录网页根目录，需要显式重新授权。
5. 查看规则发现、条件、未知项、文件位置/哈希、来源清单、工具审计和独立标注的模型解释，下载封存报告 JSON。取消前保留的来源检查点可下载，标为 INCOMPLETE_CHECKPOINT。
6. 查看同一 Case 的修订，或全部 Agent 运行（每页 25 条）。历史报告只读，旧澄清不再显示提交入口。地址中的 Run ID 支持刷新恢复；自动状态刷新不会清除正在编辑的回答。

点击“填入 MySQL 示例”会自动填写仓库内 `ArchLensService/examples/scenarios/mysql-postgresql` 的实际绝对路径及 `schema.sql`。源版本留空，应先澄清；回答 `8.0.36` 后产生第二修订。输出仍可能是 PARTIAL/UNKNOWN，不表示迁移已完成或整个项目安全。

## 接口与持久化

| 接口 | 行为 |
| --- | --- |
| `GET /api/investigations/example?scenario=csharp-java` | C# 项目样例；默认无参数仍为 MySQL |
| `POST /api/investigations/discover-csharp` | 授权目录内有界候选发现，返回显式文件/排除项；不执行项目 |
| `POST /api/investigations` | `{sourceRoot, request}`；Agent v1 请求，分配 Run 后返回 202 |
| `GET /api/investigations?caseId=...&offset=0` | 最近 Agent 运行或指定 Case 修订；每页 25 条 |
| `GET /api/investigations/:runId` | PG 状态、不可变报告、检查点和本机来源根目录 |
| `POST /api/investigations/:runId/resume` | `{sourceRoot, answers}`；原始 request 从 PG 父报告恢复 |
| `POST /api/investigations/:runId/cancel` | `{}`；PG 取消并隔离迟到结果 |
| `GET /api/investigations/:runId/report` | 封存报告或明确标注的不完整检查点附件 |

Java 入口为 `io.archlens.cli.WebAgentCli`，stdin/JSON 行输出均 UTF-8。报告和修订由 PG 管理；`.local/web-contexts/<runId>.json` 只保存本机授权根目录以便重启后恢复，不含凭据、不进入模型、不会作为静态文件发布。不要把该目录提交到仓库。没有另造浏览器历史数据库。

POST 校验同源 Origin/Host 和 JSON 类型；拒绝额外字段、路径越界、超大请求及任意命令。最多并行两次调查、六次查询；子进程参数不用 shell 拼接。浏览器不采集数据库密码，不使用 localStorage 保存报告或凭据。模型发送范围沿用 [Agent 白名单](../ArchLensService/docs/agent-orchestration.md)。

当前限制：本机单用户、无生产身份鉴权/后台持久队列；初次提交没有跨进程幂等键，网络超时应先刷新历史再重试。Java 进程被中断后依赖租约过期显示中断，不自动重启调查。未支持业务数据库在线采集、全项目扫描或旧列适配提交。

## 验证

```powershell
# ArchLensClient 目录
npm run check
npm test
# 沙箱禁止测试子进程时可用等价的同进程运行
node tests/http.test.mjs
```

真实双库与网页 Java 协议回归，从项目根目录运行 `ArchLensService/scripts/web-verify.ps1 -JdkHome ...`；脚本要求本机存储配置，不输出凭据，使用合成文件和 ArchLens 自身 schema。

`node tests/ui-fixture.mjs` 在 4184 启动明确标注“UI 回归夹具”的页面，使用归档报告测试交互，**不证明真实数据库、当前模型或采集链路可用**。只用于开发回归，不是生产入口。

当前真实闭环、重启回读及结果证据见 [2026-09-20 网页验证](../ArchLensService/docs/verification-web-2026-09-20.md)。无图 SQL 场景可用 `web-verify.ps1 -JdkHome ... -PgOnly` 验证 PG 子集；该模式排除原双库测试，不代表 Neo4j 通过。此前网络阻塞记录保留在 [2026-09-18 验证](../ArchLensService/docs/verification-web-2026-09-18.md)。

## C# 项目到 Java

新增“填入 C# 项目示例”和“发现 C# 项目文件”。检查清单后提交，示例澄清时填 C# 语言版本 `12`，目标 Java `21`。项目/依赖声明与源码特征一起进入真实 Agent/PG 报告；未知和覆盖缺口保留。具体范围、限额及复现见 [C# 使用说明](../ArchLensService/docs/csharp-java.md)。
