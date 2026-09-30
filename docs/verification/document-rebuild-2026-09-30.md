# 文档重建与历史资料整理验证

执行日期：2026-09-29 至 2026-09-30；完成日期：2026-09-30。范围为文档、测试资源及烟测输入/输出路径整理，不实现新的迁移设计 Agent 功能。

## 1. 交付与保留

| 工作 | 结果 |
| --- | --- |
| 重建 specs | [需求](../../ArchLensService/specs/implementation/requirements.md)、[设计](../../ArchLensService/specs/implementation/design.md)、[任务](../../ArchLensService/specs/implementation/tasks.md)、[验收](../../ArchLensService/specs/implementation/check_list.md)；20 项 MIG-R、20 项 MIG-D、36 项 MIG-T、28 项 MD-AC；新能力均待开发/验收 |
| 归档旧资料 | [101 个原文件](../archive/2026-09-29/README.md)：旧 docs 97 个文件和旧 specs 4 个文件，包括未提交内容；[manifest](../archive/2026-09-29/manifest.json)记录原路径、新路径、字节数及 SHA-256 |
| 清理旧目录 | 核对原件与归档哈希、测试依赖迁移后删除原 `ArchLensService/docs/`；当前 specs 留在原入口重写，旧内容保存在归档 |
| 导航与检索 | 更新根文档入口、模块 README、AGENTS、project_rules 及两份设计的历史引用；`.rgignore` 排除归档的常规搜索，不从 Git 忽略归档 |
| 历史链接 | 保留原证据字节；[链接映射](../archive/2026-09-29/link-map.md)按原位置解析，指向归档副本或当前源码，不以当前源码替代历史证据 |
| 测试输入 | Java 使用 classpath 下独立历史报告；前端使用自身 tests/fixtures；三个复制文件与归档原字节一致 |
| 烟测资源 | 当前依赖清单迁至 `ArchLensService/runtime/`；新报告写 `target/verification/` 唯一文件名，保留旧历史记录 |

两份设计资料仍位于 `docs/design/`，设计主体和原日期不变，补记后续整理和历史链接位置。当前 README 中过时的“业务采集尚未实现”“浏览器不采集业务密码”等表述已纠正；正式调查仍要求完整 PG/Neo4j 配置、双库检查/初始化命令的限制已按源码说明。

## 2. 本轮实际执行

| 验证 | 命令 / 方法 | 实际结果 |
| --- | --- | --- |
| 后端回归与打包 | 激活项目 JDK/Maven，在 ArchLensService 运行 `mvn --batch-mode --no-transfer-progress -Dmaven.repo.local=.local/m2 verify` | BUILD SUCCESS；152 项，134 通过，0 失败/错误，18 跳过；[测试摘要](document-rebuild-20260930/java-regression.json) |
| 受影响 Java 夹具 | 上述回归内 DotnetInventoryTest、BusinessContextTest | 各 7 项通过；旧 Agent 报告 canonical hash 仍可回读，测试不读取 docs |
| 前端语法 | ArchLensClient 中 `npm run check` | 通过；未修改产品页面布局 |
| Node HTTP 回归 | ArchLensClient 中 `npm test` | 16 项通过，0 失败/跳过 |
| UI 夹具输入迁移 | 实际请求夹具服务：页面标识、提交、澄清、新修订、历史、报告下载 | 协议链路通过，服务已停止；使用模拟存储，不是浏览器视觉或真实模型/PG 验收 |
| 依赖记录与打包检查 | Linux Python 根据本次 shaded JAR、实际 runtime-classpath 与项目 Maven 缓存执行等效检查 | 26 个运行依赖 + 1 个仅内嵌元数据坐标；包含 MySQL；15 项第三方声明原文保留检查通过 |
| 清单失败用例 | 旧归档清单、删除 MySQL 项、错误 MySQL SHA-256 | 三种不一致均被等效校验拒绝；没有用旧清单漏过新增驱动 |
| CLI 烟测 | 当前 JAR 的 `analyze examples/column-rename/request.json` 输出到新文件 | PARTIAL；3 节点、2 边、3 诊断；Mapper 必改 YES，风险区间 32–92 |
| 文档完整性 | 归档 SHA-256、当前本地文件链接、编号映射、旧目录依赖及相对初始工作区的改动范围核对 | 详细结果见[静态检查摘要](document-rebuild-20260930/document-check.json) |

烟测实际结果：[等效检查原始摘要](document-rebuild-20260930/runtime-migration-20260930-011904-2bfcc204.json)、[原始 CLI 报告](document-rebuild-20260930/final-report-20260930-011904-2bfcc204.json)。复制证据保持原字节，摘要内的 `target/` 路径表示执行时位置；长期读取使用这两个文档链接。构建日志与 surefire 原文件仍在本机 `ArchLensService/target/`，摘要保存其哈希。

## 3. 未执行与结论边界

- 当前 Linux 环境无 `pwsh`，没有实际执行 `record-runtime.ps1` 或 `smoke.ps1`。已静态审阅并用 Python/Java 验证相同输入及检查目标；这不等于 PowerShell 运行兼容性已验证。脚本使用 Windows 工具名及现代 .NET API，运行说明要求 Windows PowerShell 7。
- 18 个跳过项分别为 MySQL 实库 6 项、自身存储 9 项、Neo4j 1 项、网页 Java 存储集成 2 项。没有把跳过记作通过，也没有连接业务库或真实模型执行新验收。
- 未新增 LangGraph、Roslyn 绑定、MigrationPlan 生成或隔离执行。新 MD-AC01–28 均为待实现/未执行；本轮测试验证资源迁移对现有功能的影响。
- 本轮没有提交或推送。原有未提交业务代码保留；只改必要的文档、测试资源加载、烟测维护与 POM 注释。

## 4. 后续维护

从 [当前文档入口](../README.md) 和新 specs 开始工作；旧 INV/TASK/CHECK 仅作历史索引。新验证在新的日期文件记录，归档文件和已封存报告保持不可变。需要引用历史说明时用归档链接或 link-map；产品代码、测试和打包脚本不得依赖历史文档目录。

新增能力按 P0 任务开始：先实现契约、工具桥接与恢复原型，再推进设计草案。每次实现同步必要源码注释、需求/设计变化、任务状态、失败行为和真实验收证据。
