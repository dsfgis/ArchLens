# 迁移契约输入边界与旧报告基线验证

日期：2026-10-02。范围：MIG-T02 的 Node 原始 JSON 重复键拒绝，以及一份历史 Agent 报告的固定字节/canonical 回读基线。接续同日[跨语言契约验证](migration-contract-parity-2026-10-02.md)；本文记录后续增量，不改写前一轮的测试快照。关联 MD-AC11 与 MD-AC25 的有限子范围，两个 AC 均未整体通过。

## 修改

- `ArchLensService/agent-runtime/src/contracts.ts` 在 `JSON.parse` 完成语法检查后扫描原始文本。每个对象单独收集解码后的键名；同层重复键（包括 `"scope"` 与 `"\\u0073cope"`）返回 `MIG_JSON_DUPLICATE_KEY`。兄弟对象可使用相同键名。输入仍受 5 MB、UTF-8 与深度限制；CLI 拒绝时只输出受控错误码，不输出原始内容。
- `ArchLensService/examples/migration/legacy-agent-golden.json` 固定原有 `pg-resumed.json` 的字节 SHA-256 `4849681315b66cb3411168e782f004bb25097fcd12ff7278908038dd1d814681` 与 canonical SHA-256 `38e6b4957a6a054a769c51956ecbaf3700322acbdefd0aa5b7f2de70079b79e6`。Java 同时按旧 `Report` 类型回读，Node 使用相同 canonical 函数核对；历史夹具本身未修改。

## 实际执行

工作区仍为未提交状态；环境为 Temurin JDK 21.0.12.1、Maven 3.9.16、Node 24.15.0、npm 12.0.1。

1. 在 `ArchLensService/agent-runtime/` 运行 `npm ci --ignore-scripts --no-audit --no-fund && npm test`：编译成功，6/6 项测试通过。新增测试覆盖直接重复、转义后重复、含引号的转义键、数组内嵌套重复、超深输入拒绝，以及兄弟对象同名键合法和字符串值中分隔符不误判；添加深度负例后再次执行 `npm test` 仍为 6/6 通过。
2. 在 `ArchLensService/` 激活本地工具链并运行 `mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=../.local/m2 verify`：构建成功；167 个测试，失败 0、错误 0、跳过 18。新增 Java 黄金测试核对历史报告原字节、原始 JSON canonical 哈希与旧 `Report` 回读哈希。
3. 使用临时合成文件调用 Node CLI：重复 `schemaVersion` 时退出码为 2，错误输出为 `MIG_JSON_DUPLICATE_KEY: artifact rejected`，没有回显输入字段值。

## 范围限制

- 重复键检查只覆盖经过 `parseJsonFile` 的 Node 文件入口。未来 API/桥接接收原始 JSON 时必须复用同一检查；直接把已解析的 JavaScript 对象传给 `validateArtifact` 无法追溯原始重复键。
- 只固定一份旧 Agent 报告。旧 Agent v1/v2、报告 v1–v5 的完整样本、旧 CLI/预览、V001 迁移脚本与新表部署前后回读仍待验收。跨运行导入票据和 checkpoint 版本适配也未实现。
- 本轮未连接业务数据库、PG、Neo4j 或模型；没有执行 LangGraph 调度、封存与浏览器链路。
