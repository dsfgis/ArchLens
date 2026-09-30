package io.archlens.investigation.dotnet;

import java.util.List;
import io.archlens.investigation.InvestigationReport.Gap;

/** 平台声明清单；不表示有效编译配置、语义绑定或迁移兼容性。 */
public record DotnetInventory(String schemaVersion, List<Project> projects, List<Solution> solutions,
        List<SupportFile> supportingFiles, Coverage coverage, List<Gap> gaps) {
    public static final String VERSION = "archlens.dotnet-inventory.v1";
    public DotnetInventory {
        projects=List.copyOf(projects); solutions=List.copyOf(solutions);
        supportingFiles=List.copyOf(supportingFiles); gaps=List.copyOf(gaps);
    }
    public record Origin(String path, String sourceId, String sourceHash, String granularity) {}
    public record Declaration(String name, String value, String state) {}
    public record Framework(String declaredValue, String family, String version, String platform, String state) {}
    public record Dependency(String kind, String name, String version, String state) {}
    public record Reference(String declaredPath, String resolvedPath, String state) {}
    public record Project(Origin origin, String language, String projectStyle, List<Framework> targetFrameworks,
            List<Declaration> declarations, List<String> applicationHints, List<Dependency> dependencies,
            List<Reference> references, List<String> gaps) {
        public Project {
            targetFrameworks=List.copyOf(targetFrameworks); declarations=List.copyOf(declarations);
            applicationHints=List.copyOf(applicationHints); dependencies=List.copyOf(dependencies);
            references=List.copyOf(references); gaps=List.copyOf(gaps);
        }
    }
    public record Solution(Origin origin, List<Reference> projects) {
        public Solution {projects=List.copyOf(projects);}
    }
    public record SupportFile(Origin origin, String kind, List<Declaration> declarations,
            List<Dependency> dependencies, List<String> applicationHints) {
        public SupportFile {
            declarations=List.copyOf(declarations); dependencies=List.copyOf(dependencies); applicationHints=List.copyOf(applicationHints);
        }
    }
    /** 分母为当前提交/采集清单；从未声称全仓库覆盖，语义绑定数明确为零。 */
    public record Coverage(int submittedFiles, int collectedFiles, int projectFiles, int parsedProjects,
            int solutionFiles, int parsedSolutions, int sourceCodeFiles, int semanticallyBoundFiles) {}
    public static boolean platformProduct(String product) {
        return product != null && List.of(".net", "dotnet", ".net framework", ".net core").contains(product.toLowerCase(java.util.Locale.ROOT));
    }
}
