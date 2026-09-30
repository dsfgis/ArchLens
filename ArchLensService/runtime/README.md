# 运行依赖清单

本目录放置打包烟测需要读取的依赖清单，与设计、规格和历史文档分开。

- [runtime-dependencies.json](runtime-dependencies.json) 于 2026-09-29 从旧目录迁入，2026-09-30 根据本次实际构建刷新，包含 MySQL Connector/J。
- [原始归档](../../docs/archive/2026-09-29/ArchLensService/docs/runtime-dependencies.json) 保留迁入时的历史版本；字节 SHA-256 为 `b9bb7ffb5aad61ee91a360e855cf027a646a25dcef9a0fdd9109db67790e6487`。
- `scripts/record-runtime.ps1` 从实际 shaded JAR 与 `target/runtime-classpath.txt` 重建当前清单并更新本目录；历史归档不改写。
- `scripts/smoke.ps1` 读取本目录清单，对照实际运行类路径、产物内 Maven 元数据和原始 JAR 哈希核对完整性；缺项、重复、错版本或旧哈希会失败。新烟测报告和摘要写入 `target/verification/` 中带时间戳及唯一标识的文件，不再写历史文档。

本次清单从 shaded JAR 与真实 runtime classpath 等效生成，包含 26 个运行依赖和 1 个仅内嵌 Maven 元数据项，共 27 个坐标；已核对 15 处原始第三方声明在打包产物中保留。执行方式为 Linux Python/Java 等效检查，未执行 PowerShell 脚本；证据见[文档整理验证](../../docs/verification/document-rebuild-2026-09-30.md)。清单对应本次构建，不表示今后变更自动通过；发布前应重新构建、生成清单并核对产物。

在 `ArchLensService` 目录、Windows PowerShell 7 环境中执行（使用现代 .NET 的 `Path.GetRelativePath`；不保证旧 Windows PowerShell 5.1 兼容）：

```powershell
# 构建使用项目 .local/m2 仓库，供脚本核对依赖原始 JAR。
mvn --batch-mode --no-transfer-progress '-Dmaven.repo.local=.local/m2' verify
.\scripts\record-runtime.ps1
.\scripts\smoke.ps1 -JdkHome $env:ARCHLENS_JAVA_HOME
```

这两个脚本沿用 Windows/JDK 启动约定，`smoke.ps1` 使用 `bin/java.exe`；无 PowerShell 的环境不得将静态审阅或等效检查标记为脚本实际执行通过。清单只记录可获得的直接 POM 声明与二进制来源，不是完整的许可证合规审计。

MySQL Connector/J 9.7.0 的原始 JAR 带有 `LICENSE`，包含 GPLv2 与 Universal FOSS Exception。分发包含该驱动的产物前必须核对适用许可和完整第三方声明，保留原始声明文本；烟测检查原始 JAR 中已识别的 LICENSE/NOTICE/THIRD-PARTY 是否仍包含在 shaded JAR 中，不能替代分发条件审查。项目不使用 X DevAPI，POM 排除其 protobuf 依赖；依赖调整后仍需重新生成清单和核对产物。
