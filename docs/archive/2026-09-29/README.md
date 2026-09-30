# 2026-09-29 历史归档

归档创建于 2026-09-29，整理完成日期记录于当前验证文件。这里保存 97 个旧技术/验证文件和四份旧 specs，共 101 个文件，包括归档时未提交的内容。所有原文件保持字节不变；原始日期、失败记录和当时的能力限制均保留。

**本目录不是当前实施规范。** 后续开发从[当前文档入口](../../README.md)进入；当前 specs 以 MIG 编号跟踪，旧 INV 编号仅供历史追溯。

## 完整性与阅读方式

- [manifest.json](manifest.json) 记录每个文件的原路径、归档路径、字节数与 SHA-256。
- 归档文件正文及其中的链接按原位置保留，部分相对链接不能在新位置直接打开；请使用下方文件入口或[历史链接映射](link-map.md)，不要为修复链接改写原证据。
- 历史链接映射中指向当前源码的链接只便于定位，不表示源码与原验收日期相同；未入库的旧 target 产物不会被补造。
- 测试使用模块自己的测试资源，烟测使用 runtime 清单；运行代码不读取本目录。
- 全仓常规 rg 搜索默认排除归档；需要追溯时显式使用 `rg --no-ignore 关键词 docs/archive/`。归档仍正常纳入 Git。

## 旧 specs

- [ArchLensService/specs/implementation/check_list.md](<ArchLensService/specs/implementation/check_list.md>)
- [ArchLensService/specs/implementation/design.md](<ArchLensService/specs/implementation/design.md>)
- [ArchLensService/specs/implementation/requirements.md](<ArchLensService/specs/implementation/requirements.md>)
- [ArchLensService/specs/implementation/tasks.md](<ArchLensService/specs/implementation/tasks.md>)

## 技术说明与有日期验证

- [README.md](<ArchLensService/docs/README.md>)
- [agent-orchestration.md](<ArchLensService/docs/agent-orchestration.md>)
- [business-database.md](<ArchLensService/docs/business-database.md>)
- [csharp-java.md](<ArchLensService/docs/csharp-java.md>)
- [deepseek.md](<ArchLensService/docs/deepseek.md>)
- [dotnet-platform.md](<ArchLensService/docs/dotnet-platform.md>)
- [investigation-storage.md](<ArchLensService/docs/investigation-storage.md>)
- [scenario-rules.md](<ArchLensService/docs/scenario-rules.md>)
- [technical-baseline.md](<ArchLensService/docs/technical-baseline.md>)
- [verification-2026-09-17.md](<ArchLensService/docs/verification-2026-09-17.md>)
- [verification-access-path-2026-09-27.md](<ArchLensService/docs/verification-access-path-2026-09-27.md>)
- [verification-agent-2026-09-18.md](<ArchLensService/docs/verification-agent-2026-09-18.md>)
- [verification-business-database-2026-09-25.md](<ArchLensService/docs/verification-business-database-2026-09-25.md>)
- [verification-csharp-2026-09-22.md](<ArchLensService/docs/verification-csharp-2026-09-22.md>)
- [verification-dotnet-2026-09-24.md](<ArchLensService/docs/verification-dotnet-2026-09-24.md>)
- [verification-scenario-rules-2026-09-17.md](<ArchLensService/docs/verification-scenario-rules-2026-09-17.md>)
- [verification-web-2026-09-18.md](<ArchLensService/docs/verification-web-2026-09-18.md>)
- [verification-web-2026-09-20.md](<ArchLensService/docs/verification-web-2026-09-20.md>)
- [verification.md](<ArchLensService/docs/verification.md>)

## 机器可读历史证据

JSON 报告按原结构保存在 [旧 docs 目录](ArchLensService/docs/) 中，全部位置及校验值见 manifest。不要把历史报告内容当成当前数据库、模型或产品验收结果。
