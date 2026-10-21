# ArchLens 文档入口

更新：2026-09-30（归档基线日期 2026-09-29）。本次根据两份设计基线重建实施规格，并归档旧资料；产品能力以源码和注明日期的验证为准。

## 当前依据

| 目的 | 入口 | 使用方式 |
| --- | --- | --- |
| 下一阶段目标与架构 | [自主迁移设计 Agent 详细设计 v2.0](design/ArchLens-自主迁移设计Agent-详细设计-v2.0.md) | A1 迁移设计优先，A2 隔离验证分阶段建设；不是已上线功能 |
| 既有能力与限制 | [现有项目总结](design/ArchLens-现有项目总结-2026-09-29.md) | 2026-09-29 源码核对基线；其中旧验证数字属于原日期 |
| 开发需求 | [requirements.md](../ArchLensService/specs/implementation/requirements.md) | 输入、范围、行为与非功能要求 |
| 实施设计 | [design.md](../ArchLensService/specs/implementation/design.md) | 组件、契约、状态和恢复协议 |
| 分阶段任务 | [tasks.md](../ArchLensService/specs/implementation/tasks.md) | 依赖、交付物及待开发事项 |
| 验收要求 | [check_list.md](../ArchLensService/specs/implementation/check_list.md) | MD-AC 条目、证据与完成判定 |
| 运行现有系统 | [后端说明](../ArchLensService/README.md)、[前端说明](../ArchLensClient/README.md) | 当前原生网页 / Node / Java CLI 的运行方式 |
| 工程约束 | [AGENTS.md](../AGENTS.md)、[project_rules.md](../project_rules.md) | 开发入口、事实与模型边界、验证约定 |
| 迁移可行性分析方案 | [ArchLens 迁移可行性分析方案](design/ArchLens-迁移可行性分析方案-2026-10-19.md) | 评级、工作量区间、Go/No-Go 门槛和比赛演示结构；不执行实际迁移 |

当前源码已有受限调查 Agent、有限 Roslyn C# 语义取证（含无条件直接工程引用）和未经验证的迁移草案。正式 MigrationPlan 封存、完整语义与隔离验证尚未完成；P0 固定只读工具持久编排已实现并通过恢复验证。重建 specs 不改变旧报告版本或已有运行记录。

## 历史与资源

- [历史归档](archive/2026-09-29/README.md)：原 `ArchLensService/docs/` 的 97 个文件及原四份 specs；包含旧结论、失败记录和验收证据，不作为当前实施指令。
- [归档清单](archive/2026-09-29/manifest.json)：原路径、新路径、字节数和 SHA-256，保留归档时的未提交内容。
- [Java 测试资源](../ArchLensService/src/test/resources/fixtures/agent-20260918/README.md)、[网页测试资源](../ArchLensClient/tests/fixtures/agent-20260918/README.md)：回归夹具独立于文档，历史报告不能证明当前外部服务可用。
- [运行依赖清单](../ArchLensService/runtime/README.md)：烟测输入放在运行资源目录，烟测输出放在 `ArchLensService/target/verification/`。
- [本轮整理验证](verification/document-rebuild-2026-09-30.md)：归档核对、文档映射、测试资源迁移、回归结果及未执行事项。

后续修改按“需求 → 设计 → 任务 → 验收”同步维护；已实现、仅有代码、真实环境已验证应分别说明。新验证写新日期记录，历史文件保持原字节。

- [2026-10-13 通用分析入口与模型配置](verification/generic-analysis-2026-10-13.md)

- [2026-10-19 P0 worker 交付与恢复验证](verification/p0-worker-2026-10-19.md)
- [2026-10-18 成果基线与 P0 推进](verification/p0-baseline-2026-10-18.md)
- [2026-10-19 .NET WPF → C++/Electron 真实草案验证](verification/dotnet-cpp-electron-2026-10-19.md)
