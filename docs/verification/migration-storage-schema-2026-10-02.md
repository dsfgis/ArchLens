# P0 V002 迁移运行表结构验证

日期：2026-10-02。范围：MIG-T03 的追加表结构、版本校验和与旧存储兼容子集；关联 MD-AC25。V002 不表示 MIG-T03、P0 或 MD-AC15/18/25 整体完成。

## 实现边界

- [V002](../../ArchLensService/src/main/resources/db/V002__migration_runtime.sql) 在 `archlens` 下追加 migration_case/run、artifact、task、tool_invocation、event、question/answer、validation_job 和 checkpoint_link；`archlens_checkpoint` 是供后续框架持久化的独立 schema。运行/修订、调用去重、哈希格式、状态、跨表引用和认可 checkpoint 的请求哈希均有数据库约束。
- [PgMigrationSchema](../../ArchLensService/src/main/java/io/archlens/storage/PgMigrationSchema.java) 在原有 `PgInvestigationStore.initialize()` 的事务与 advisory lock 中安装 V002；已安装时核对资源 SHA-256，不重复执行 DDL，校验和漂移返回 `MIGRATION_DRIFT`。旧 V001 字节 SHA-256 固定为 `f96e5873694434a2a2eb71dcc319019837618f0cbbb9add2d7f6e5a17c8150ba`，原文件未修改。
- `archlens_checkpoint` 未给 PUBLIC 使用权限；迁移表没有向 PUBLIC 授予写入权限。具体 worker/验证服务角色、LangGraph 适配表及授权策略尚未创建，不应把 schema 存在解释为恢复能力已就绪。

## 实际验证

- 环境：Temurin JDK 21.0.12.1、Maven 3.9.16、Node 24.15.0、PostgreSQL 16.15。测试通过 Python `TemporaryDirectory` 启动独立本机 PostgreSQL 实例，创建唯一用途的 `archlens_migration_it` 数据库；执行后关闭进程并自动删除该临时实例，没有修改系统已有 PostgreSQL 数据库。
- 对临时实例运行 `MigrationSchemaIntegrationTest`：1/1 通过。验证 V001→V002 安装与重复初始化、两条 schema_version 校验和、十张新增业务表与独立 schema、PUBLIC 权限边界、旧 investigation 运行可创建/回读、独立新 Case/Run、重复修订、RUNNING 缺租约、checkpoint 错误请求哈希被拒绝，以及伪造 V002 校验和导致 `MIGRATION_DRIFT`。
- `npm test`：Node 6/6 项通过；未修改 Node 契约运行时。
- Java 全量 `mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=../.local/m2 verify`：构建成功，169 个测试、失败 0、错误 0、跳过 19。跳过项含默认不运行的外部集成测试及临时 PG 专项测试；后者已用显式环境变量另行执行并通过。

## 尚未实现或验证

- 尚无创建/领取 MigrationRun 的 Java 业务服务、租约与 epoch 围栏、checkpoint_link CAS、工单/调用/结果事务、封存回执、权限登记、导入真实性核验及 LangGraph checkpoint 表。V002 只是这些功能的持久结构和部分约束，不能让 worker 直接写业务表。
- 未连接真实业务数据库、Neo4j 或模型；未执行生产 PG 升级或历史报告 v1–v5 全量回读。真实部署前需备份并按部署流程安装，由拥有 schema 创建权限的管理员执行初始化。
