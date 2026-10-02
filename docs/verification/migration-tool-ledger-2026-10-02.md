# P0 规则目录调用账本子集验证

日期：2026-10-02。范围：MIG-T03 的 V004 小型结果表、MIG-T05 的 Java 内部 `list_rules` 只读工具账本，以及 MD-AC18 的此工具发布围栏子范围。MIG-T05、P0 和任何完整验收项仍未完成。

## 实现与边界

- [PgMigrationToolStore](../../ArchLensService/src/main/java/io/archlens/storage/PgMigrationToolStore.java) 固定使用既有 `RuleCatalog` 和无参、只读的 `list_rules` Manifest。宿主提供任务 ID 与稳定动作键；模型不能从此接口指定文件路径、SQL 或自定义工具。登记前按已保存请求的 `allowedTools`、`maxToolCalls` 和当前 Case/worker/epoch/PG 租约检查；同一动作重试返回原 invocationId。
- 登记 `REGISTERED` 已提交后才派发；`DISPATCHED` 后再校验当前票据才将结果和任务状态一并写入 PG。过期、取消或取代的 worker 不可发布。重复执行已完成调用直接回读相同 artifact，历史结果在取消后仍可读取。规则目录哈希只标识该目录版本内容，**不代表代码或业务数据库来源快照**。
- [V004](../../ArchLensService/src/main/resources/db/V004__migration_tool_result.sql) 仅保存 64 KiB 以内的 UTF-8 规则目录结果，应用回读时检查原字节 SHA-256。V001–V003 原文件不变。V004 字节 SHA-256：`fd586bbeb5b7abae25e6cf476dace95a2b9b755628053accf3652c358cb1cbbb`。

## 验证

使用 PostgreSQL 16 的独立临时实例和 `archlens_migration_it` 数据库；实例在测试后关闭并清理。专项运行 `MigrationSchemaIntegrationTest,MigrationRunStoreIntegrationTest,MigrationToolStoreIntegrationTest`：8 项通过，失败 0、跳过 0。覆盖 V004 安装/重复核对/校验和漂移拒绝、登记后未派发的读取拒绝、同一动作重放、任务状态与产物引用、预算上限、未授权工具拒绝、租约过期后的旧票据拒绝与新 epoch 复用、取消后旧票据拒绝及历史结果回读。

`mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=../.local/m2 verify`：176 项，失败 0、错误 0、默认跳过 26（包括上述需显式启用的 PG 测试），构建成功。`npm test`：Node 6/6 通过。`git diff --check` 无格式错误。

## 尚未完成

- 无跨进程 JSON Lines 桥接、通用工具 Manifest/能力票据、外部授权登记或实际代码/数据库范围核验；目前仅 Java 内部调用固定无参规则目录。请求中的 `authorizationRef` 尚未映射到独立授权注册表。
- 没有对进程中断做实际故障注入；只验证了数据库状态边界与调用重放。没有 LangGraph checkpoint、调度器、共享 token/时间预算或大型产物存储。MD-AC09、MD-AC16 和 MD-AC18 的完整条件仍未通过。
