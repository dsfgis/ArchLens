package io.archlens;

import io.archlens.contract.ContractException;
import io.archlens.contract.Json;
import io.archlens.migration.MigrationRequest;
import io.archlens.migration.MigrationSourceRegistry;
import io.archlens.migration.MigrationRequest.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationSourceRegistryTest {
    @TempDir Path temp;

    @Test void registersCodeManifestResolvesWithinAuthorizationAndDetectsDrift() throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Files.writeString(root.resolve("App.csproj"), """
                <Project Sdk="Microsoft.NET.Sdk">
                  <PropertyGroup>
                    <TargetFramework>net48</TargetFramework>
                    <LangVersion>12</LangVersion>
                  </PropertyGroup>
                </Project>
                """);
        Files.writeString(root.resolve("Program.cs"), "class Program { static void Main() {} }");
        Path registry = temp.resolve("registry");

        var binding = MigrationSourceRegistry.register(registry, "orders-code", SourceKind.LOCAL_CODE,
                root, null, List.of("Program.cs", "App.csproj"), ".NET Framework", "4.8",
                "auth-orders", null);
        assertEquals(2, binding.fileCount());
        assertTrue(binding.manifestHash().matches("[0-9a-f]{64}"));
        assertFalse(Json.canonical(binding).contains(root.toRealPath().toString()));
        assertTrue(binding.observed().stream().anyMatch(item ->
                item.product().equals(".NET Framework") && "4.8".equals(item.version())));
        assertTrue(binding.observed().stream().anyMatch(item ->
                item.product().equals("C#") && "12".equals(item.version())));

        var request = request("orders-code", SourceKind.LOCAL_CODE, ".NET Framework", "4.8",
                "auth-orders", null, AnalysisMode.CODE_ONLY);
        var resolved = MigrationSourceRegistry.resolve(registry, "orders-code", request, Map.of());
        assertEquals(root.toRealPath(), resolved.sourceRoot());
        assertFalse(resolved.gaps().contains("MIG_SOURCE_PROFILE_UNOBSERVED"));

        var repeated = MigrationSourceRegistry.register(registry, "orders-code", SourceKind.LOCAL_CODE,
                root, null, List.of("App.csproj", "Program.cs"), ".NET Framework", "4.8",
                "auth-orders", null);
        assertEquals(binding.manifestHash(), repeated.manifestHash());

        Files.writeString(root.resolve("Program.cs"), "class Program { static void Main() { } }");
        assertCode("MIG_SOURCE_DRIFT", () ->
                MigrationSourceRegistry.resolve(registry, "orders-code", request, Map.of()));
    }

    @Test void rejectsAuthorizationMismatchAndSourceReplacement() throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Files.writeString(root.resolve("A.cs"), "class A {}");
        Path registry = temp.resolve("registry");
        MigrationSourceRegistry.register(registry, "orders-code", SourceKind.LOCAL_CODE,
                root, null, List.of("A.cs"), ".NET Framework", "4.8", "auth-orders", null);

        var wrongAuth = request("orders-code", SourceKind.LOCAL_CODE, ".NET Framework", "4.8",
                "auth-other", null, AnalysisMode.CODE_ONLY);
        assertCode("MIG_AUTHORIZATION_MISMATCH", () ->
                MigrationSourceRegistry.resolve(registry, "orders-code", wrongAuth, Map.of()));

        Files.writeString(root.resolve("A.cs"), "class A { int changed; }");
        assertCode("MIG_SOURCE_CONFLICT", () ->
                MigrationSourceRegistry.register(registry, "orders-code", SourceKind.LOCAL_CODE,
                        root, null, List.of("A.cs"), ".NET Framework", "4.8", "auth-orders", null));
    }

    @Test void databaseCredentialIsAReferenceAndNeverStoredInManifest() throws Exception {
        Path registry = temp.resolve("registry");
        MigrationSourceRegistry.register(registry, "orders-db", SourceKind.BUSINESS_DATABASE,
                null, "orders-database", null, "MySQL", "8.0", "auth-orders", "credential-orders");
        String stored = Files.readString(registry.resolve("orders-db.json"));
        assertTrue(stored.contains("credential-orders"));
        assertFalse(stored.contains("synthetic-secret"));

        var request = request("orders-db", SourceKind.BUSINESS_DATABASE, "MySQL", "8.0",
                "auth-orders", "credential-orders", AnalysisMode.DATABASE_ONLY);
        assertCode("MIG_CREDENTIAL_UNAVAILABLE", () ->
                MigrationSourceRegistry.resolve(registry, "orders-db", request, Map.of()));
        var resolved = MigrationSourceRegistry.resolve(registry, "orders-db", request,
                Map.of("ARCHLENS_MIGRATION_CREDENTIAL_CREDENTIAL_ORDERS", "synthetic-secret"));
        assertEquals(List.of("MIG_SOURCE_NOT_CONNECTED_IN_T09"), resolved.gaps());
    }

    @Test void rejectsTraversalAndProfileConflict() throws Exception {
        Path root = Files.createDirectory(temp.resolve("project"));
        Files.writeString(root.resolve("App.csproj"), "<Project><PropertyGroup><TargetFramework>net48</TargetFramework></PropertyGroup></Project>");
        Path registry = temp.resolve("registry");
        assertCode("PATH_OUTSIDE_ROOT", () -> MigrationSourceRegistry.register(registry, "orders-code",
                SourceKind.LOCAL_CODE, root, null, List.of("../outside"), ".NET Framework", "4.8",
                "auth-orders", null));

        MigrationSourceRegistry.register(registry, "orders-code", SourceKind.LOCAL_CODE,
                root, null, List.of("App.csproj"), ".NET Framework", "4.8", "auth-orders", null);
        var incorrect = request("orders-code", SourceKind.LOCAL_CODE, ".NET Framework", "4.7",
                "auth-orders", null, AnalysisMode.CODE_ONLY);
        assertCode("MIG_SOURCE_PROFILE_CONFLICT", () ->
                MigrationSourceRegistry.resolve(registry, "orders-code", incorrect, Map.of()));
    }

    @Test void observedFrameworkVersionConflictsWithDeclaration() throws Exception {
        Path root = Files.createDirectory(temp.resolve("observed-project"));
        Files.writeString(root.resolve("App.csproj"), "<Project><PropertyGroup><TargetFramework>net48</TargetFramework></PropertyGroup></Project>");
        Path registry = temp.resolve("observed-registry");
        MigrationSourceRegistry.register(registry, "orders-code", SourceKind.LOCAL_CODE,
                root, null, List.of("App.csproj"), ".NET Framework", "4.7", "auth-orders", null);
        var request = request("orders-code", SourceKind.LOCAL_CODE, ".NET Framework", "4.7",
                "auth-orders", null, AnalysisMode.CODE_ONLY);
        assertCode("MIG_SOURCE_PROFILE_CONFLICT", () ->
                MigrationSourceRegistry.resolve(registry, "orders-code", request, Map.of()));
    }

    private static MigrationRequest request(String sourceRef, SourceKind kind, String product, String version,
                                            String auth, String credential, AnalysisMode mode) {
        return new MigrationRequest(MigrationRequest.VERSION, mode, "分析迁移设计",
                List.of(new SourceRef(sourceRef, kind, "locator-" + sourceRef, credential, product, version, false)),
                new TargetEnvironment(new Technology("Java", "21", null),
                        mode == AnalysisMode.DATABASE_ONLY ? new Technology("PostgreSQL", "16", null) : null,
                        null, null),
                List.of(new Condition("api", "保持接口兼容")),
                List.of(new Condition("rounding", "保持金额舍入")),
                new AcceptanceScope(Level.A1, List.of(sourceRef), List.of("module"), List.of("path"),
                        List.of("api"), List.of("rounding"), List.of(ValidationLevel.V1), List.of()),
                new PolicyRefs(auth, null, DataPolicy.SUMMARY_ONLY, ExecutionPolicy.READ_ONLY,
                        List.of("list_rules")),
                new Budget(4, 0, 10_000), List.of());
    }

    private static void assertCode(String expected, org.junit.jupiter.api.function.Executable executable) {
        ContractException error = assertThrows(ContractException.class, executable);
        assertEquals(expected, error.code());
    }
}
