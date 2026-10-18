package io.archlens.investigation.dotnet;

import io.archlens.contract.Json;
import io.archlens.investigation.InvestigationReport.Evidence;
import io.archlens.investigation.InvestigationReport.Finding;
import io.archlens.investigation.InvestigationReport.Source;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import static io.archlens.investigation.dotnet.DotnetInventory.*;

/** 仅从已采集的工程声明归纳现状；不把技术线索解释成源码行为或迁移兼容性。 */
public final class DotnetCurrentStateAssessment {
    private DotnetCurrentStateAssessment() {}

    public static List<Finding> assess(DotnetInventory inventory, List<Source> sources) {
        if (inventory == null) return List.of();
        // 控制单条发现的证据引用规模；超出范围必须在结论中标出。
        List<Project> projects = inventory.projects().stream().sorted(Comparator.comparing(p -> p.origin().path())).limit(40).toList();
        if (projects.isEmpty()) return List.of();
        Map<String, Source> byPath = sources.stream().collect(Collectors.toMap(Source::path, s -> s));
        String scope = inventory.projects().size() > projects.size() ? "（仅归纳前 40 个已解析工程）" : "";
        List<Finding> findings = new ArrayList<>();

        List<Project> windows = projects.stream().filter(p -> p.targetFrameworks().stream()
                .anyMatch(f -> "DECLARED".equals(f.state()) && f.platform() != null && f.platform().startsWith("windows"))).toList();
        List<Project> unqualified = projects.stream().filter(p -> p.targetFrameworks().stream()
                .anyMatch(f -> "DECLARED".equals(f.state()) && f.platform() == null && ".NET".equals(f.family()))).toList();
        if (!windows.isEmpty() || !unqualified.isEmpty()) findings.add(finding("target-frameworks",
                "目标框架分层：" + windows.size() + " 个工程声明 Windows 目标框架，" + unqualified.size()
                        + " 个工程声明不带平台后缀的现代 .NET 目标框架" + scope
                        + "。这反映项目配置边界，不能证明后者的源码可跨平台运行。",
                projects, byPath, List.of("逐工程核对有效构建配置及 Windows API 使用，再划分可复用业务逻辑与需替换的平台能力。")));

        List<Project> wpf = projects.stream().filter(p -> declaredTrue(p, "UseWPF")).toList();
        List<Project> winforms = projects.stream().filter(p -> declaredTrue(p, "UseWindowsForms")).toList();
        if (!wpf.isEmpty() || !winforms.isEmpty()) findings.add(finding("desktop-ui",
                "桌面界面：" + wpf.size() + " 个工程直接声明 WPF，" + winforms.size()
                        + " 个工程直接声明 Windows Forms" + scope
                        + "。当前未解析 XAML、数据绑定、资源或窗口交互，无法从这些声明推出目标界面的实现范围。",
                projects.stream().filter(p -> wpf.contains(p) || winforms.contains(p)).toList(), byPath,
                List.of("盘点窗口、控件、命令、数据绑定与资源，并明确目标界面框架和交互验收样例。")));

        List<Project> sqlite = projects.stream().filter(p -> dependency(p, "Microsoft.Data.Sqlite") || dependency(p, "SQLitePCLRaw")).toList();
        List<Project> cipher = projects.stream().filter(p -> dependency(p, "sqlcipher")).toList();
        if (!sqlite.isEmpty()) findings.add(finding("local-storage",
                "本地存储：" + sqlite.size() + " 个工程声明 SQLite 相关包，" + cipher.size()
                        + " 个工程还声明 SQLCipher 相关包" + scope
                        + "。包声明不能证明数据库文件格式、密钥管理或实际调用路径。",
                sqlite, byPath, List.of("核对数据库初始化、加密与迁移策略，在目标实现中验证相同测试数据的读写行为。")));

        List<Project> referenced = projects.stream().filter(p -> !p.references().isEmpty()).toList();
        long referenceCount = referenced.stream().mapToLong(p -> p.references().size()).sum();
        if (!referenced.isEmpty()) findings.add(finding("project-references",
                "工程依赖：" + referenced.size() + " 个工程共声明 " + referenceCount + " 条项目引用" + scope
                        + "。这能作为模块拆分线索，但没有绑定类、方法或运行时调用。",
                referenced, byPath, List.of("按项目引用建立初步模块清单，再用语义分析核对跨模块调用和接口边界。")));
        return List.copyOf(findings);
    }

    private static boolean declaredTrue(Project project, String name) {
        return project.declarations().stream().anyMatch(d -> name.equals(d.name())
                && "DECLARED".equals(d.state()) && "true".equalsIgnoreCase(d.value()));
    }

    private static boolean dependency(Project project, String token) {
        return project.dependencies().stream().anyMatch(d -> "DECLARED".equals(d.state())
                && d.name().toLowerCase(java.util.Locale.ROOT).contains(token.toLowerCase(java.util.Locale.ROOT)));
    }

    private static Finding finding(String category, String summary, List<Project> projects,
                                   Map<String, Source> byPath, List<String> recommendations) {
        List<Source> support = projects.stream().map(p -> byPath.get(p.origin().path())).filter(java.util.Objects::nonNull)
                .distinct().sorted(Comparator.comparing(Source::path)).toList();
        List<String> ids = support.stream().map(Source::sourceId).toList();
        List<Evidence> evidence = support.stream().map(s -> new Evidence(
                Json.hashParts("dotnet-current-state.v1", category, s.sourceId()), s.sourceId(), s.sha256(),
                s.location(), "DOTNET_PROJECT_XML_DECLARATION")).toList();
        return new Finding(Json.hashParts("dotnet-current-state.v1", category, ids), "UNKNOWN",
                "dotnet-current-state." + category + ".v1", ids, List.of(),
                List.of("仅依据项目声明；未评估有效构建配置、源码语义或目标技术兼容性"),
                null, summary, evidence, null, recommendations, List.of());
    }
}
