# DeepSeek 修改目标解析

本文介绍旧描述解析接口。2026-09-18 新增独立的 [Agent 编排通道](agent-orchestration.md)，使用原生 tool_calls；旧页面不会自动切换到新编排层。两者的输入范围、契约与验证记录分别维护。

默认使用 `deepseek-flash`，通过官方 `https://api.deepseek.com/chat/completions` 调用 JSON Output。模型名可用后端环境变量 `DEEPSEEK_MODEL` 调整。当前网关采用非思考、非流式模式。

## 运行

先在 ArchLensService 中构建：

```powershell
.\scripts\build.ps1 -JdkHome 'D:\Program Files\Java\jdk-21.0.11'
```

在 ArchLensClient 中运行：

```powershell
.\start.ps1 -JdkHome 'D:\Program Files\Java\jdk-21.0.11'
```

脚本在后端环境变量 `DEEPSEEK_API_KEY` 未配置时以隐藏输入提示密钥，仅对当前进程及子进程生效，不持久化。不将真实密钥写入源码、命令样例、前端或日志。重启后需重新提供环境变量或通过脚本输入。

访问 http://127.0.0.1:4173，填写修改目标后点击“AI 梳理目标”。请求不要求先填写代码库和数据库。页面调用同源 `POST /api/parse-target`，Node 本地适配器以 stdin/stdout 调用 Java `ParseTargetCli`。Node 服务只监听本机并检查 Host、Origin 和 JSON 类型，不提供跨域访问；不是生产 HTTP 服务。

## 契约与边界

输入仅允许 `{"description":"至少 10 字符的修改描述"}`，最长 4000 字符。源码、仓库路径、数据库地址、数据库账号密码不会传给模型。

结果固定为 `status=UNVERIFIED_PROPOSAL`，包含 kind/schema/table/column/newName/newType/constraints/questions。输出只能是字段改名、删除、类型变更，或 UNSUPPORTED/UNCLEAR。缺失目标信息必须给出澄清问题，不默认数据库 schema。

这只是模型提议，不等于经过快照绑定的 ChangeSpec，也不生成依赖、证据、风险评分或变更结论。修改描述后前端会清除旧提议，防止附带失效的解析结果。生成请求时会附带最新提议，用户仍需审阅。

网关固定官方 HTTPS 主机、禁止重定向、连接超时 15 秒、请求超时 60 秒、最多 1800 输出 token；适配器最多运行一个解析请求并在 70 秒终止子进程。空结果、截断、额外字段和格式错误均拒绝。失败仅返回清理过的错误码，保留原始输入，允许继续使用不带模型的请求草稿。

现有离线 `analyze` 命令不依赖模型。当前尚无源码全量采集、数据库在线采集和完整影响分析 API。

## 验证

- `mvn verify` 覆盖有效提议、缺失信息、伪造快照字段、未知类型、空/截断输出、鉴权错误脱敏、缺失密钥、输入限额、固定端点及超时。
- 官方接口参考：[JSON Output](https://api-docs.deepseek.com/guides/json_mode/)。

2026-09-12 验证记录：JDK 21 构建成功，41 项测试通过（其中模型网关 6 项）。浏览器 → 本地适配器 → Java → DeepSeek 的真实调用通过；缺少 schema 的示例返回中文澄清问题。已修复 Windows 子进程 stdout 编码，验证中文输出、描述修改清除旧草稿、请求预览包含待核实提议；浏览器无错误日志。密钥只配置在本次运行的服务进程环境中，未写入项目文件。
