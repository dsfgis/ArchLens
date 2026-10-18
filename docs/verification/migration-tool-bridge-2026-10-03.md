# P0 固定规则工具跨进程桥接验证

日期：2026-10-03。范围：MIG-T05 的 `list_rules` 固定工具 UTF-8 JSON Lines 子协议；关联 MD-AC09 的固定工具输入边界、MD-AC16 的跨进程账本重放和 MD-AC18 的现有租约围栏。三项验收及 P0 总阶段均未整体完成。

后续同日增加 `INSPECT`、新 epoch 对账和登记/派发后子进程强制终止实验，见[恢复验证](migration-tool-recovery-2026-10-03.md)。以下结果保留首次桥接验证时的范围与数字。

## 实现与边界

- [Java CLI](../../ArchLensService/src/main/java/io/archlens/cli/MigrationToolBridgeCli.java) 新增 `migration-tool-bridge` 子命令，每个进程接受一条以换行结束的 UTF-8 JSON 消息，协议版本 `archlens.tool-bridge.v1`，仅有 `REGISTER`、`EXECUTE`。先登记持久调用，再由下一次请求按 invocationId 执行。只接受 `list_rules`、固定规则版本和空业务参数；任务/动作身份来自受信任宿主。执行所给 snapshotHash 必须与 PG 已登记值一致。
- [Node 适配器](../../ArchLensService/agent-runtime/src/tool-bridge.ts) 用参数数组启动子进程，不使用 shell；请求/响应上限各 16 KiB，超时上限 30 秒，严格核对单行、UTF-8、JSON 重复键、协议版本、requestId、退出码和响应字段。stdout 只承载协议，stderr 不返回给调用者。超时或暂时存储错误标记可按稳定动作重放，但适配器不自动重试。
- Java 返回 `REGISTERED` 的 invocationId、inputHash、目录 snapshotHash；`SUCCEEDED` 只返回 PG artifactId 与 contentHash，不把规则正文、路径、数据库连接或凭据送回 Node。账本仍核验当前 Case 修订、worker、epoch 与数据库时钟租约。CLI 是本机受信任宿主接口，传输中的 worker 字段尚无独立签名或外部授权注册表，不应开放给不受信任客户端。

## 实际验证

1. Java 全量 `mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=../.local/m2 verify`：178 项，失败 0、错误 0、默认跳过 27；构建成功。新增 Java 边界测试覆盖多行、无结束换行、无效 UTF-8、重复 JSON 键、未知工具与超长消息的拒绝，不回显正文。
2. Node `npm test`：9/9 通过。新增进程测试覆盖有效单行响应、重复键、多行/超大响应、超时、错误分类及模型提供额外 SQL 参数的拒绝。
3. 在独立临时 PostgreSQL 16 的 `archlens_migration_it` 数据库运行专项集成测试：9/9 通过，失败 0、跳过 0。新增桥接测试覆盖登记、重复登记、错误快照拒绝、结果引用、重复执行和取消后旧票据拒绝。
4. 另用 Node 进程实际启动打包 Java CLI 三次，连接临时 PostgreSQL 完成 `REGISTER → EXECUTE → 同 invocationId 重放`；最后两次返回相同 artifactId。临时数据库在测试后关闭并清理，没有使用业务数据库或系统现有实例。

## 尚未完成

- 没有 LangGraph worker、运行调度器、Web API、通用工具目录/能力票据或真实代码/数据库来源工具；目录哈希只表示规则目录，不是业务来源快照。
- 尚未在登记后/结果持久后注入真实进程崩溃，也没有 checkpoint 认可、验证作业、封存或完整预算对账；上述重放不能替代 MD-AC16 的完整故障验收。
- 当前仅本机信任边界；`authorizationRef` 尚未映射独立授权注册表，宿主 worker 字段也未签名。外部用户、模型和浏览器不能直接调用此 CLI。
