# P0 迁移运行入队与租约子集验证

日期：2026-10-02。范围：MIG-T03 的 V003 提交键表，以及 MIG-T04 的新 Case 入队、单 worker 领取、续租、过期接管和取消子集；关联 MD-AC18 的租约/epoch 子范围。接续[存储结构验证](migration-storage-schema-2026-10-02.md)。本文不表示 MIG-T04、P0 或任何完整 AC 已通过。

## 实现与边界

- [V003](../../ArchLensService/src/main/resources/db/V003__migration_submission.sql) 用唯一 `submission_key` 绑定一个固定请求哈希和 runId。相同键/相同请求返回原 run，同键/不同请求返回 `MIG_SUBMISSION_CONFLICT`。当前键在本机单用户范围内全局唯一，由宿主生成，不从模型输入取值。
- [PgMigrationRunStore](../../ArchLensService/src/main/java/io/archlens/storage/PgMigrationRunStore.java) 在 PG 事务内创建新 Case/QUEUED run 和 `CREATED` 事件。领取通过 Case 行锁与 RUNNING 租约条件更新竞争，一个 run 只有一个当前 worker；过期接管增加 epoch。续租检查最新修订、worker、请求哈希、epoch、RUNNING 状态和 PG 时钟。取消清除租约、增加 epoch、写 `CANCELLED` 事件并返回可重复读取的同一回执。旧票据不能续租，`active` 不再认作有效。
- [PgMigrationSchema](../../ArchLensService/src/main/java/io/archlens/storage/PgMigrationSchema.java) 在原 V001 初始化事务里依次核对 V002/V003 校验和；迁移文件只使用普通 DDL 与整行注释，执行前移除整行注释。V001/V002 原文件未改。V003 的字节 SHA-256 为 `03f3b1d80a6e9223e2f8af10b0b4207f04d7b34424409d16a03a5cedb6f4de26`。

## 实际执行

环境：Temurin JDK 21.0.12.1、Maven 3.9.16、Node 24.15.0、PostgreSQL 16.15。使用 Python `TemporaryDirectory` 建立独立 PostgreSQL 实例和 `archlens_migration_it` 数据库，运行后关闭并清理；没有修改系统已有数据库。代码仍为未提交工作区。

1. 在该实例运行 `MigrationSchemaIntegrationTest,MigrationRunStoreIntegrationTest`：5 项全部通过。覆盖 V001/V002/V003 重复安装与校验和、同键并发提交只创建一个 run、变更请求冲突、并发领取只有一个成功、活跃租约不能重领、续租、过期后新 epoch 接管、旧票据拒绝、取消与重复回执、取消后同键重试不复活、受控 CREATED/CANCELLED 事件，以及 Case 最新修订变化后的旧 run 拒绝。
2. `mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=../.local/m2 verify`：构建成功；173 个测试，失败 0、错误 0、跳过 23。默认跳过项包括需显式开启的外部集成测试；上述 5 项在临时 PG 上单独执行并通过。
3. `npm test`：Node 6/6 项通过；本轮未修改 Node 契约运行时。

## 尚未完成

- 只有新 Case 的首次提交，尚无澄清答案幂等消费、新修订/取代、暂停、外部等待、工具调用账本、checkpoint_link CAS、完整封存门槛或重复封存回执。`enqueue` 不启动 worker；当前没有迁移 API、队列调度或 LangGraph 执行。
- `active`/续租已验证迟到票据失效，但工具派发与结果发布尚未接入该票据检查。checkpoint namespace、同 epoch 回调乱序、崩溃恢复和外部验证任务仍未测试，MD-AC18 只计租约/epoch 子范围。
- 未连接真实业务数据库、Neo4j 或模型，也未在已有生产 PG 上部署 V003。
