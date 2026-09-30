# 首批代码验证记录

日期：2026-09-12。设计基线 SHA256：`122f845fc9848ac72e6136ee3984d720c37d9c66f2084ed41cbb1a023a80371b`。原 DOCX 未在本次代码实施中改变。

## 构建和测试

实际执行：

```powershell
.\scripts\build.ps1 -JdkHome 'D:\Program Files\Java\jdk-21.0.11' -Demo
```

结果：Maven `BUILD SUCCESS`，35 项测试通过，失败/错误/跳过均为 0。

| 测试类 | 数量 | 重点 |
| --- | ---: | --- |
| ContractTest | 11 | scope/身份冲突、缺证据、非法端点/置信度、未知 JSON、位置/哈希、属性类型与不可变性、同关系证据合并 |
| BlastRadiusTest | 12 | 列改名/删除/类型变化、候选链、拟议别名、对象 UNKNOWN、隐藏第四条路径、环、预算、26.20/91、未知风险和 nullable 反例 |
| MapperAnalyzerTest | 12 | 真实文件 AST/原始字节、别名、动态和复杂 SQL、大小写/schema、namespace/注解/重复映射、XML 实体、目录越界、CLI 输出保护 |

原始机器可读结果在 `target/surefire-reports/TEST-io.archlens.*.xml`；该目录属于构建输出，不应作为源码提交。测试 fixture 是本批单元/集成样例，不是原文要求的独立人工标注 40 例黄金集。

## 离线端到端结果

实际 Java 进程运行打包 jar，样例报告：`target/rename-report-20260912-082426-643.json`。

- 输入：`examples/column-rename/DeviceMapper.java`、`DeviceMapper.xml`、`request.json`。
- 输出：3 个节点、2 条 READS 关系、3 条诊断，质量 `PARTIAL`。
- 旧列 `event_id` 和真实 Mapper 的改动结论为 `YES`。
- 图事实来源是固定的离线 catalog 和真实文件；未连接数据库，未调用模型，未执行被分析项目。
- 3 条诊断分别说明离线 catalog 未核验、方法返回类型缺 classpath 解析、下游字段链未覆盖。

最终打包时合并重复 LICENSE/NOTICE/THIRD-PARTY 文本，保留第三方声明。重新打包后的最终 jar 烟测结果与 SHA256 记录在 `final-smoke.json`。运行库坐标和 SHA256 在 `runtime-dependencies.json`，由 `scripts/record-runtime.ps1` 从实际打包 jar 中枚举。

## 尚未验证或实现

完整 JSON Schema/数据库 migration、PG 租约和快照、Neo4j 投影、带 DTD 和动态分支的 MyBatis、Java classpath/调用绑定、真实 PG 元数据事务、Vue/JSON 字段链、已验证兼容 NO、API/方法签名变更的完整语义、Case/Run/OIDC/页面、模型/导出/恢复和性能基准。

因此当前只验收 `specs/implementation/` 中的基础子任务，原文 TASK01–TASK10 与 AC01–AC14 未整体通过。未发现 Docker 命令不等于证明机器没有数据库服务；本批没有尝试安装或修改系统服务。
