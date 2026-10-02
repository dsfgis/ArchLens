# MigrationRequest v1 请求契约验证

日期：2026-10-02。范围：MIG-T01 的请求契约子集；MD-AC01 的离线输入校验子范围。本文不表示 P0、MIG-T01 或 MD-AC01 整体完成。

## 实现与边界

- Java `MigrationRequest` 明确三种分析模式、来源引用、目标环境、约束/不变量、A0/A1/A2 acceptanceScope、数据/执行策略、预算和未决输入字段。请求只接收登记 ID，不接收连接密码或源文件内容。
- `archlens.migration-request.v1.schema.json` 为同版 JSON Schema；Java 的构造校验额外检查跨字段引用、重复 ID、三模式必需来源、离线标记、A2/V3 与执行策略。严格 Jackson 解析拒绝未知属性、未知枚举、重复 JSON 键和标量强制转换。
- `migration-request-check` 只读验证并输出 canonical 请求哈希、模式和等级。它不登记 locator/credential，不入队运行，不查询数据库，也不生成方案。
- 缺目标版本、操作系统或验证环境可作为明确 `unresolvedFields` 输入；契约通过不代表这些缺口已解决。`ISOLATED_VALIDATION` 是授权策略声明，不代表环境已就绪或 V3 已执行。

## 本次实际执行

工作目录：`ArchLensService/`；JDK 21.0.12.1、Maven 3.9.16；本地工具链由 `source ../.local/toolchains/activate.sh` 启用。代码为本次未提交工作区状态，非远端 commit。

1. `python3 -m json.tool` 检查 Schema 和示例 JSON 语法；Python `jsonschema` 4.10.3 的 `Draft202012Validator.check_schema` 检查 Schema，并验证代码模式示例：通过。
2. `mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=../.local/m2 verify`：构建成功；157 个测试，失败 0、错误 0、跳过 18。跳过项包括需外部 MySQL、PG、Neo4j 或正式网页环境的集成测试。
3. `MigrationRequestTest` 的 5 个测试：CODE_ONLY/DATABASE_ONLY/JOINT 合法输入与稳定哈希，缺来源/范围引用、A2 无 V3、V3 仅只读、未知 `password` 属性、缺离线标记和未知版本负例均通过。
4. `java -jar target/archlens-0.1.0-SNAPSHOT-cli.jar migration-request-check examples/migration/request-code-only.json`：退出码 0，输出 `VALID requestHash=1853855b825bccc6b3a539c66e6ed513514f1ff62ddb6b3eba1299098bb16062 mode=CODE_ONLY level=A1`。该哈希是本例的实际产物，尚未作为 TS/Java 跨语言黄金样例冻结。

## 未完成

- MIG-T01 其余 Plan/Evidence/Validation/Event Schema 和错误目录；MIG-T02 TS/Java 同构校验及黄金哈希样例。
- MD-AC01 的 API 提交、持久化修订、页面状态与三模式端到端验收。MD-AC11/12/23/24 的方案、引用、完成门槛与实际验证均未执行。
- 未使用真实模型、业务数据库或隔离验证环境；本次测试不证明迁移设计或 A2 能力。
