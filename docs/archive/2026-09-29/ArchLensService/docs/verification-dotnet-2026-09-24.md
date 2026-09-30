# 2026-09-24 .NET 平台现状第一阶段验证

本次对应 INV-T12/13/19 的明确子集：本地目录候选发现、混合平台静态项目声明清单、Agent 集成和无存储现状预览。不作为完整 .NET 迁移、业务数据库或信创验收。

## 实际执行

环境：Linux、Temurin JDK 21、本地 Maven 3.9.16、Node 24.15。执行目录为仓库根目录（包含 ArchLensService 与 ArchLensClient）。

| 验证 | 结果 |
| --- | --- |
| `mvn -f ArchLensService/pom.xml --batch-mode --no-transfer-progress verify` | BUILD SUCCESS；130 项，118 通过，12 跳过，0 失败/错误 |
| `npm run check`（ArchLensClient） | 通过 |
| `npm test`（ArchLensClient） | 13 项全部通过 |
| 打包 CLI `agent-investigate` 混合样例 | 真实读取 14 文件，4 工程/1 解决方案；PARTIAL，0 模型调用；无源统一版本澄清 |
| 真实 Node/Java `POST /api/investigations/preview-dotnet` | HTTP 200、LOCAL_PREVIEW、persisted=false；4/4 工程、1/1 解决方案、语义绑定 0 |
| 浏览器生产入口（测试端口 4185） | 填示例、真实预览、展示 Framework/Core/.NET/Standard；编辑文件后失效并隐藏旧下载；缺失文件显示 SOURCE_UNAVAILABLE |
| 浏览器 UI 回归夹具（4184） | 使用真实 CLI 报告，验证发现 4 工程/14 文件、保留目标、正式报告渲染；存储为夹具，非 PG 验收 |
| 桌面与 390×844 窄屏 | 示例按钮、表单及预览面板可阅读，无页面水平溢出；测试后恢复视口 |
| `git diff --check` | 通过 |

12 项跳过为 StorageIntegrationTest 9、Neo4jProjectionTest 1、WebAgentCliTest 2，未配置本次集成测试所需存储条件。本次不宣称真实 PG/Neo4j 封存回读或外部模型联调通过；无存储预览测试传入 null store 并断言不调用模型，生产路径也在初始化存储/模型之前执行。

## 回归重点

- 真实旧式/SDK 工程、多语言、多目标、SDK 与语言版本区分。
- 条件/重复/动态声明、共享属性不求值，引用未采集及目录外引用不跟随。
- XML 外部实体拒绝，应用配置连接串/值不进入清单或模型投影。
- 来源哈希与报告证据一致，取消、超时、来源漂移时撤销平台清单。
- Agent 不要求混合平台统一源版本；缺少目标版本时保留独立现状盘点。
- 归档 Agent 报告反序列化/再序列化 canonical 哈希不变；含清单报告为 v3，无清单旧形状仍为 v2。
- 本地预览不产生 Run ID 或历史记录，接口拒绝跨域、额外凭据字段和非法清单，重任务并发上限共用且失败释放槽位。

## 可复核产物

- [离线 Agent 报告](verification/dotnet-20260924/agent-offline.json)，canonical 哈希 `38ff068be9790993af5abf1243175212a8c23ab5503cf38ce763cb9f19e4fd21`。
- [真实 HTTP 本地预览](verification/dotnet-20260924/local-preview.json)，其中 report canonical 哈希 `a7d06db62025ee74dbe97b84fde3a2e1374e5d39ab92229e8de851a9d98f3184`。
- [混合输入](../examples/scenarios/dotnet-platform/agent-clarify.json)、[使用和边界](dotnet-platform.md)。

浏览器点击了“下载预览 JSON”，未观察到 JS 错误；当前内嵌浏览器未回传 download 事件，因此本次不宣称下载文件已落盘验证。HTTP JSON 导出内容及哈希已验证并归档如上。

仍待实施：Git 来源、业务库连接器、Roslyn 绑定、KingbaseES 迁移专用规则、信创组合、依赖影响路径及详细可审阅改造计划。配置只解析已实现的结构白名单，项目可盘点不等于项目可直接迁移。
