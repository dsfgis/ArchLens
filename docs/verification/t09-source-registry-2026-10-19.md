# T09 来源登记与迁移入口验证（2026-10-19）

基线：`5e27f7e` 之上的未提交工作区。范围仅为 T09 的来源登记、授权/凭据引用、迁移运行创建/查询/取消 API；不包含 T10 取证工具或正式 `MigrationPlan` 封存。

## 实际执行

- Java `mvn verify`：213 项，0 失败/错误，38 跳过。跳过项包括外部 PostgreSQL/Neo4j/MySQL 集成、Roslyn host 未构建相关用例和 Windows 符号链接权限用例。
- Node `npm run check` 通过；`npm test`：22 项，20 通过、2 项因 Windows 符号链接权限跳过，0 失败。
- 打包 CLI 烟测：登记 `Examples/scenarios/dotnet-platform` 的 `LegacyWeb.csproj` 与 `Payment.cs`，返回 2 个文件、`.NET Framework 4.8` 和 C# 7.3 观测；输出未包含绝对根目录。
- Node HTTP 契约：来源登记、创建、查询、取消、Origin/JSON 类型/字段白名单/UUID 校验通过；该段测试使用注入 transport，真实 PG 链路见下方“同日补测”。

## 未完成与阻塞

- 来源登记目前是本机 `.local/migration-sources` 上下文，不是生产级多用户授权服务；业务库凭据只做引用存在性解析，未连接业务库。
- T10 的工具账本泛化、来源快照/覆盖分母、Roslyn 产物绑定和崩溃复用仍未实现。

本记录不把 T09 标记为全部完成，也不把 API 骨架当作迁移调查或 A1 方案完成。

## 同日补测：切换到专用数据库后完成真实生命周期

后续只读检查发现同一 PostgreSQL 实例已有 `archlens` 数据库，owner 为 `appuser`，且已有 `archlens_checkpoint` schema。当前 `.local/storage.json` 原先指向 `postgres`，因此 `appuser` 缺少数据库级 `CREATE`。将本机配置切换到该专用数据库后，不再需要系统管理员密码。

该数据库已有旧版本 V002–V004 校验和，但迁移表为空，且列和关键约束与当前 DDL 一致。确认后仅重基线 V002–V004 的 `schema_version` 校验和并补装 V005，保留既有 10 条 investigation case / 14 条 run；未删除调查数据。随后 `storage-init` 成功。

真实网页链路：

| 操作 | 结果 |
| --- | --- |
| `POST /api/migrations` | 202；case `2551cd5a-6183-403a-b37b-95c0f80e8f57`，run `3b1f9d95-60fa-43e9-b3ef-6ca5f1e9654d`，revision 1 |
| `GET /api/migrations/:runId` | `QUEUED`，`latestRevision=true` |
| 同一提交键重复提交 | 返回同一 run，未创建重复运行 |
| 同一提交键修改请求 | `MIG_SUBMISSION_CONFLICT` |
| `POST /api/migrations/:runId/cancel` | 返回 epoch 1 取消回执 |
| 再次查询 | `CANCELLED`，`finishedAt` 非空 |
| `storage-check` | PostgreSQL 16.15、Neo4j 2026.08.1 可达 |

本次仍未启动 T10 worker，运行保持 `QUEUED`/`CANCELLED`，没有生成迁移证据或正式方案。

## 42501 的诊断与解除方法

只读诊断（未输出密码）确认：当前用户 `appuser` 对 `postgres` 数据库没有 `CREATE`，但对 `archlens` schema 有 `CREATE`；`archlens_checkpoint` schema 不存在。V002 的第一条 DDL 需要创建该 schema，因此授权不足。

推荐由数据库 owner 或管理员执行以下最小授权，而不是给应用账号授予 `CREATEDB` 或 superuser：

```sql
-- 以数据库 owner/管理员身份执行；appuser 为 ArchLens 应用账号
CREATE SCHEMA IF NOT EXISTS archlens_checkpoint AUTHORIZATION appuser;
ALTER SCHEMA archlens_checkpoint OWNER TO appuser;
GRANT USAGE, CREATE ON SCHEMA archlens_checkpoint TO appuser;
```

随后以 `appuser` 重新运行：

```powershell
.\scripts\storage.ps1 -Command storage-init -JdkHome $env:ARCHLENS_JAVA_HOME
```

初始化器现在会跳过已存在的 `archlens_checkpoint` schema，避免从 42501 变成 42P06。P0 worker 的 checkpoint 角色仍应使用独立账号，并在初始化后只授予该 schema 的 `USAGE` 与表的 `SELECT/INSERT/UPDATE`；不要把业务表 DDL 权限授予 worker。

代码侧修改位于 [PgMigrationSchema.java](../../ArchLensService/src/main/java/io/archlens/storage/PgMigrationSchema.java)：当 V002 检测到该 schema 已存在时跳过 `CREATE SCHEMA`，其余 V002–V005 DDL 仍按原校验和安装。该修改后 Java 全量 `mvn verify` 仍为 213 项、0 失败/错误、38 跳过；真实预建 schema 场景仍需管理员授权后复测。
