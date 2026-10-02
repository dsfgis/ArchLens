# 迁移产物 v1 结构契约验证

日期：2026-10-02。范围：MIG-T01 的 Plan/Evidence/Validation/Event 契约子集，MD-AC11/12 的离线结构与引用子范围。接续同日[请求契约验证](migration-request-contract-2026-10-02.md)。本文不表示 P0 或任一完整 AC 已通过。

## 实现边界

- `MigrationEvidence` 绑定 runId、revision、snapshotId/hash、sourceRef/sourceHash、subjectId、定位粒度和生产者。`USER_IMPORT` 只能标为 `EXTERNAL_UNVERIFIED`；候选证据仍为 `CANDIDATE`。
- `MigrationPlan` 包含固定请求 hash、现状与目标摘要、备选、对象处置、改造工作项、验证计划、切换/回退、假设缺口和估算依据。内容哈希由整个方案的 canonical JSON 计算，方案内部没有自引用 `planHash`。构造时检查重复 ID、工作项悬空依赖/环、验证/假设引用及方案证据清单；与请求和证据目录联查时检查请求目标/验收范围、同一运行/修订/快照、授权来源及缺失证据。
- `MigrationValidationRecord` 用独立 `validationId` 记录一次结果，以 `validationItemRef` 指向计划中的验证项；重跑可保留多条不可变记录。记录绑定 plan/snapshot/environment/testSpec 哈希与验证级别。`NOT_RUN` 不可带结果；外部导入不能声称系统 `PASSED`；执行的 V3 必须有环境 hash。`matches` 仅做结构匹配，持久来源认证和完成门槛尚未实现。
- `MigrationEvent` 有序号、UTC 时间、固定事件类型和受控 artifactHash/errorCode，不保存原始模型输出或源码。
- 五个 v1 JSON Schema 与合成样例位于 `ArchLensService/src/main/resources/schema/` 和 `ArchLensService/examples/migration/`。只读 `migration-plan-check` 对三份输入做 Java 结构/引用校验，返回 `STRUCTURE_VALID`，不创建 run、不执行调查、不封存。

## 本次实际执行

工作目录：`ArchLensService/`；JDK 21.0.12.1、Maven 3.9.16；本次代码仍为未提交工作区状态。样例 runId、来源哈希和计划内容为合成测试数据，不代表实际代码文件或业务数据库快照。

1. Python `jsonschema` 4.10.3 的 `Draft202012Validator.check_schema` 检查 Request/Evidence/Plan/Validation/Event 五份 Schema，并分别验证五份合成样例：全部通过。
2. `mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=../.local/m2 verify`：构建成功；163 个测试，失败 0、错误 0、跳过 18。跳过的外部集成测试没有在本次被视为通过。
3. `MigrationArtifactsTest` 的 6 个测试：合法请求/证据/方案 hash 与引用匹配；错误请求 hash、跨快照、缺失/未声明证据；工作项缺前置条件、悬空验证/依赖、有环；外部结果升级和旧方案结果拒绝；事件错误码/UTC；导入证据状态，均通过。原有请求契约的 5 个测试也通过。
4. `java -jar target/archlens-0.1.0-SNAPSHOT-cli.jar migration-plan-check examples/migration/request-code-only.json examples/migration/evidence-code-only.json examples/migration/plan-code-only.json`：退出码 0；输出 `STRUCTURE_VALID planHash=ab8b6474dd923e23df1b148c963c2a435de54149dbe10e24502dd41b69202026 workItems=1 evidence=1`。`validation-not-run.json` 和 `event-plan-drafted.json` 与该 hash 在 Java 测试中一致。

## 尚未证明

- 证据由真实工具生成、快照和来源 hash 的真实性、外部导入核验、凭据和授权登记、跨运行显式导入均未实现。结构通过不代表候选证据已语义绑定，也不代表计划可封存。
- 完整的目标架构/数据库映射语义、决定记录、A1/A2 完成检查、持久封存、事件序列事务、TS 同构校验及错误目录仍待实现。Schema 本身不能检查工作项 DAG 和跨文件哈希，这些检查当前只在 Java 中实现。
- 没有运行真实模型、业务库、PG 状态机、隔离验证或浏览器链路；`NOT_RUN` 样例不表示任何验证实际通过。
