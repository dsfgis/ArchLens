# 迁移契约 v1 的 Java/Node 一致性验证

日期：2026-10-02。范围：MIG-T02 的五类 JSON Schema 形状校验、canonical JSON 与 SHA-256 内容身份子集，关联 MD-AC11。接续[请求契约](migration-request-contract-2026-10-02.md)和[迁移产物](migration-artifacts-2026-10-02.md)验证。本记录不代表 MIG-T02、P0 或 MD-AC11 全部完成。

## 实现与样例

- `ArchLensService/agent-runtime/` 新增独立 TypeScript/Node 运行时。读取 Java 资源目录中的 Request、Plan、Evidence、Validation、Event v1 Schema，用 Ajv draft 2020-12 校验版本和结构；不修改既有客户端的 Node 基线。
- Java/Node 共用 `ArchLensService/examples/migration/golden-hashes.json` 的六组身份：五类合成产物和含补充平面 Unicode 字符、控制字符、键排序、数组、最大安全整数及小数写法的边界样例。对象键按 Unicode 码点排序，数组保序；可空字段在 JSON 中须显式给出 `null`。Java canonical 对孤立 UTF-16 代理字符拒绝，Node 同样拒绝。
- Java 继续检查请求/证据/计划间的 hash、运行范围、授权来源和工作项依赖；Node 当前只校验单个产物的 Schema 并计算 hash。新增的 `agent-runtime/README.md` 记录命令、依赖和限制。

## 实际执行

工作区仍为未提交状态。环境：Temurin JDK 21.0.12.1、Maven 3.9.16、Node 24.15.0、npm 12.0.1；锁定 Ajv 8.20.0、ajv-formats 3.0.1、TypeScript 7.0.2 和 `@types/node` 24.19.1。直接依赖的许可证为 MIT 或 Apache-2.0；锁文件中另有 BSD-3-Clause 的 fast-uri 3.1.8。

1. 在 `ArchLensService/agent-runtime/` 运行 `npm ci --ignore-scripts --no-audit --no-fund && npm test`：TypeScript 编译成功；Node 测试 4/4 通过，涵盖五份合成产物、六组黄金哈希、码点/数组/安全整数/孤立代理字符，以及未知字段、未来版本、缺失可空字段、超出安全整数的事件序号拒绝。
2. 在 `ArchLensService/` 激活本地工具链并运行 `mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=../.local/m2 verify`：构建成功；166 个测试，失败 0、错误 0、跳过 18。Java `MigrationGoldenTest` 的 2 个测试核对六组哈希和孤立代理字符拒绝；外部集成测试仍跳过。
3. Python `jsonschema` 4.10.3 对五份 draft 2020-12 Schema 执行 `check_schema` 并验证五份合成样例：均通过。
4. Node CLI 对代码单项请求输出 `SCHEMA_VALID kind=request hash=1853855b825bccc6b3a539c66e6ed513514f1ff62ddb6b3eba1299098bb16062`；Java `migration-plan-check` 输出 `STRUCTURE_VALID planHash=ab8b6474dd923e23df1b148c963c2a435de54149dbe10e24502dd41b69202026 workItems=1 evidence=1`。两条命令退出码均为 0。
5. `git diff --check` 通过。

## 尚未证明

- `JSON.parse` 不检测原始 JSON 的重复对象键；Node CLI 不能作为最终受理边界。未来桥接/API 必须在接受请求前拒绝或保留这类原始输入，并核验真实来源 hash、授权和工具出处。
- Schema 不执行跨文件引用、工作项 DAG、真实来源、凭据/权限登记、封存门槛。错误码目录、旧报告回读黄金基线、跨运行导入票据和 checkpoint 版本适配仍未实现。
- 没有运行真实模型、业务数据库、PG 状态机、LangGraph 调度或隔离验证；六组黄金哈希只证明当前合成样例的跨语言内容身份一致。
