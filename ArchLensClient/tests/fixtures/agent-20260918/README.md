# 历史 Agent 报告测试夹具

迁入日期：2026-09-29。此目录保存从旧 `ArchLensService/docs/verification/agent-20260918/` 原字节复制的合成调查报告，用于历史契约与展示回归。正式归档位于 `docs/archive/2026-09-29/`；测试不再依赖文档目录。

| 文件 | 归档来源 | 文件字节 SHA-256 |
| --- | --- | --- |
| [pg-clarification.json](pg-clarification.json) | [2026-09-18 原报告](../../../../docs/archive/2026-09-29/ArchLensService/docs/verification/agent-20260918/pg-clarification.json) | `5a06a7c1f8865ec436823e792e5d105edffdfaaa74d7e9917b1f9c28c589eeed` |
| [pg-resumed.json](pg-resumed.json) | [2026-09-18 原报告](../../../../docs/archive/2026-09-29/ArchLensService/docs/verification/agent-20260918/pg-resumed.json) | `4849681315b66cb3411168e782f004bb25097fcd12ff7278908038dd1d814681` |

哈希为文件字节哈希，不能替代报告的 canonical JSON 哈希。更换测试样例应新增文件，保留当前历史兼容用例的原字节。这些报告不能证明当前模型、PostgreSQL、Neo4j 或业务数据库可用。

[ui-fixture.mjs](../../ui-fixture.mjs)只在本机模拟存储接口；该夹具的界面结果不是实库验收。
