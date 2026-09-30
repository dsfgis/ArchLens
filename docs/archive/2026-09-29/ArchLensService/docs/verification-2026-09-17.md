# 2026-09-17 调查与存储实施验证

范围：统一调查 CLI、显式来源清单、列分析适配、PG 自身 Case/Run 存储代码、Neo4j 投影、配置及运行脚本。前端未修改，没有浏览器/API 或完整 Agent 验收。

**最新状态：2026-09-17 权限问题已解除，完整双库测试和打包 CLI 端到端验证通过。** 下面保留首轮失败记录；本次成功证据见文末追加记录。

## 结果

| 检查 | 实际结果 |
| --- | --- |
| JDK21 / Maven verify | 60 项：52 通过、0 失败、0 错误、8 跳过；构建打包成功 |
| 跳过原因 | 7 项外部数据库测试默认关闭；1 项宿主 Windows 不允许创建符号链接 |
| Neo4j 独立集成 | 显式开启后 1 项通过；并发相同 run 投影幂等，3 节点/2 关系，节点 JSON 回读完全一致；测试随机 run 数据已清理 |
| PG 连接与认证 | 通过；服务端 PostgreSQL 16.15（Ubuntu 包 16.15-0ubuntu0.24.04.1） |
| Neo4j 连接与认证 | 通过；服务端 Neo4j/2026.08.1 |
| PG 初始化与双库集成 | 受阻：`permission denied for database postgres`；账号可连接但不能创建 schema。6 项测试在初始化阶段报错，不能声称业务断言已通过 |
| 旧 CLI 打包烟测 | PARTIAL，3 节点、2 关系、3 诊断，Mapper changeRequired=YES，风险区间 32–92 |
| 新调查 CLI 打包烟测 | PARTIAL，3 来源、列图 3 节点/2 关系；总体兼容性 UNKNOWN；覆盖声明保留 |
| Windows junction 目录越界 | 手工构造指向请求根目录外的 junction；返回 PATH_OUTSIDE_ROOT、退出码 2，无报告文件 |
| 第三方声明 | 26 个运行/嵌入坐标，14 项打包声明检查通过；见 `runtime-dependencies.json`、`final-smoke.json`。补充 LICENSE.txt/NOTICE.txt 合并；embedded metadata 单独标记，不伪造缺失依赖哈希 |
| 文档/脚本/凭据检查 | 59 个源码/文档/配置文件严格 UTF-8 解码通过；PowerShell 语法通过；24 条本地文档链接有效；真实 Neo4j 密码未出现在源码/文档/示例中 |

普通构建验证了既有 41 项回归和新增 11 项可运行本地用例。缺失版本、未知/执行场景、输入边界、源文件不变、来源漂移、预算、取消、新旧 CLI 及凭据配置负例均有覆盖。

最后打包烟测的报告路径和 SHA-256 分别保存在 [旧 CLI 烟测](final-smoke.json) 与 [统一调查烟测](investigation-smoke-2026-09-17.json)。

## PG 权限恢复后执行

管理员连接用户指定的 `postgres` 库，预建归应用账号所有的专用 schema：

```sql
CREATE SCHEMA IF NOT EXISTS archlens AUTHORIZATION appuser;
```

然后在 `ArchLensService` 目录执行（JDK 路径使用本机配置）：

```powershell
.\scripts\storage.ps1 -Command storage-init -JdkHome $env:ARCHLENS_JAVA_HOME
.\scripts\storage.ps1 -Command storage-verify -JdkHome $env:ARCHLENS_JAVA_HOME
.\scripts\storage.ps1 -Command investigate-store -CommandArgs @('examples/column-rename/investigation.json','new') -JdkHome $env:ARCHLENS_JAVA_HOME
```

后续应追加真实 runId、哈希、测试结果及日期，不把上面的权限失败记录改成当时已成功。

## 保留边界

- PostgreSQL 数据库名虽为 `postgres`，用途已由用户确认是 ArchLens 自身存储。只使用专用 schema；Neo4j 只使用 ArchLens 标签。
- 未运行任何被分析项目，没有业务库写入、迁移、代码转换或模型源码上传。
- 没有完整兼容规则、全项目语义扫描、在线业务数据库采集、Web 调查界面和生产认证。本次完成的是可运行调查入口与持久化代码切片。
- PG 实际存储验收仍待权限解决；当前不把这部分标记为已验证。代码内的取消/租约/并发/恢复断言需要真实 PG 测试通过后再确认。

## 2026-09-17 追加：schema 创建后的双库验收

用户确认已在服务器创建 schema 后，实时查询确认 `archlens` 存在、所有者为 `appuser`；该账号仍没有数据库 CREATE 权限，也不是超级用户。应用使用专用 schema 初始化成功，无需扩大账号权限或开放远程 postgres 登录。

13:17（Asia/Shanghai）完成 `scripts/storage.ps1 -Command storage-verify`，实际执行 Maven verify：**60 项，59 通过、0 失败、0 错误、1 跳过，BUILD SUCCESS**。唯一跳过项为 Windows 宿主不能创建符号链接，前一轮已用 junction 补充目录越界验证。

本次 6 项真实 PG/Neo4j 集成测试均通过：报告/图回读与幂等投影、重复封存拒绝、并发修订编号、取消阻止迟到写入且保留检查点、租约超期恢复、图连接失败后 PG 报告保留并重试成功，以及相同离线 scope 的不同 run 数据隔离。Neo4j 独立并发投影测试也通过。

16:13（Asia/Shanghai）完成打包 CLI 流程：创建调查 → PG 封存 → Neo4j READY → 导出 → 修改目标 → 同 Case 新修订 → 重新读取旧报告 → 重试投影。结果如下：

| 项目 | 实际结果 |
| --- | --- |
| Case ID | `0b6bff8a-6ff2-46f1-a13e-d521daa68be2` |
| 修订 1 Run ID | `1a47eca2-f7be-4ff6-9e47-0509a6efd19d` |
| 修订 2 Run ID | `baacd6d5-37da-4a59-be80-8da73b8f53f4` |
| 图投影 | 两个运行均 READY；3 节点、2 关系 |
| 新旧修订 | 旧运行 latestRevision=false，新运行为 true；修改目标后 inputFingerprint 不同 |
| 旧报告不可变 | 新修订完成后重新导出旧报告，文件 SHA-256 与原导出一致 |
| 重复投影 | 修订 2 再次 projection-retry 成功 |
| 调查质量 | PARTIAL；兼容性 UNKNOWN，未改变支持边界 |

保存的证据：[端到端记录](verification/storage-e2e-20260917-081301-146/evidence.json)、[测试汇总](verification/storage-e2e-20260917-081301-146/tests.json)、[修订 2 报告](verification/storage-e2e-20260917-081301-146/revision-2-report.json)、[旧 CLI 打包烟测](verification/storage-e2e-20260917-081301-146/legacy-cli-smoke.json)。合成调查记录保留在自身数据库中用于核查，没有清理其他数据。

本次补齐的是自身存储切片的真实验收；完整迁移规则、全项目采集和前端调查工作台仍按任务表保留未完成状态。
