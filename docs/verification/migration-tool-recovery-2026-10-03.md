# P0 规则目录调用恢复对账验证

日期：2026-10-03。范围：MIG-T05 固定 `list_rules` 工具的账本状态读取、真实子进程中断与新 epoch 恢复；MD-AC16 仅此子范围部分通过。

## 实现

- [PgMigrationToolStore](../../ArchLensService/src/main/java/io/archlens/storage/PgMigrationToolStore.java) 增加 `inspectRuleCatalog`，在当前租约/修订围栏下读取持久化调用状态。`dispatchRuleCatalog` 独立提交 `DISPATCHED`，`completeRuleCatalog` 只在有效 epoch 下写结果；重复执行已完成调用回读并核验原字节哈希。同一 invocation 的去重身份不含 epoch，新 worker 可接管。
- [Java 桥接](../../ArchLensService/src/main/java/io/archlens/cli/MigrationToolBridgeCli.java) 增加 `INSPECT`，仅返回登记状态、哈希或 artifact 引用。[Node 适配器](../../ArchLensService/agent-runtime/src/tool-bridge.ts) 的 `resumeToolInvocation` 先查询 PG；已完成时直接返回引用，未完成时按原 invocationId 执行固定只读工具。过期 worker 无法查询或发布。

## 实际执行

1. 在独立临时 PostgreSQL 16 的 `archlens_migration_it` 数据库运行 `MigrationSchemaIntegrationTest,MigrationRunStoreIntegrationTest,MigrationToolStoreIntegrationTest`：11 项通过，失败 0、跳过 0。其中两个测试分别在 `REGISTERED` 与 `DISPATCHED` 事务提交后强制结束 Java 子进程；令旧租约过期，再由新 epoch 查询并完成同一调用。每个 run 仍只有一条 invocation，结果可按原 artifactId 回读。
2. 打包后，Node 实际通过多个 Java CLI 进程连接临时 PG，完成 `REGISTER → INSPECT(REGISTERED) → resumeToolInvocation → INSPECT(SUCCEEDED)`；再次恢复返回相同 artifactId。数据库在验证后关闭并清理。
3. Maven `verify`：180 项，失败 0、错误 0、默认跳过 29；Node `npm test`：10/10 通过。默认跳过项包括需显式开启的 PG 专项测试。

## 仍未完成

- 尚无 LangGraph checkpoint、认可指针、通用任务调度、结果持久后至 checkpoint 前的中断验证、验证作业或封存事务；MD-AC16 的完整故障矩阵仍未通过。
- `INSPECT` 仅支持本机受信任宿主和固定规则目录；worker 身份尚无独立签名，`authorizationRef` 未接外部授权登记。目录哈希不是业务来源快照，不能据此宣称迁移分析完成。
