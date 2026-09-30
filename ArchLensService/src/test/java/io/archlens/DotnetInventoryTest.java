package io.archlens;

import io.archlens.contract.*;
import io.archlens.investigation.*;
import io.archlens.investigation.dotnet.*;
import io.archlens.parser.SourceText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.archlens.investigation.InvestigationRequest.*;

class DotnetInventoryTest {
    @TempDir Path temp;
    void put(String path,String text) throws Exception {Path p=temp.resolve(path);Files.createDirectories(p.getParent());Files.writeString(p,text);}
    InvestigationRequest request(List<String> files) {
        return new InvestigationRequest(VERSION,Scenario.LANGUAGE_MIGRATION,new Profile(".NET",null),new Profile("Java","21"),
                "混合 .NET 平台迁移到 Java",List.of(),List.of(),files,null,Budget.defaults());
    }
    InvestigationReport analyze(String... files) throws Exception {return new InvestigationEngine().investigate(temp,request(List.of(files)),()->false);}
    @Test void mixedPlatformExamplePreservesFrameworkLanguageAndEvidence() throws Exception {
        Path root=Path.of("examples/scenarios/dotnet-platform");
        var input=Json.MAPPER.readTree(root.resolve("agent-clarify.json").toFile());
        var files=new ArrayList<String>();input.path("files").forEach(n->files.add(n.asText()));
        var report=new InvestigationEngine().investigate(root,request(files),()->false);
        var inventory=report.dotnetInventory();assertNotNull(inventory);assertEquals(4,inventory.projects().size());
        assertEquals("archlens.investigation-report.v3",report.schemaVersion());assertTrue(report.clarificationItems().isEmpty());
        var families=inventory.projects().stream().flatMap(p->p.targetFrameworks().stream()).map(DotnetInventory.Framework::family).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(".NET Framework",".NET Core",".NET",".NET Standard"),families);
        assertEquals(Set.of("C#","VB.NET","F#"),inventory.projects().stream().map(DotnetInventory.Project::language).collect(java.util.stream.Collectors.toSet()));
        assertEquals(0,inventory.coverage().semanticallyBoundFiles());
        for(var project:inventory.projects())assertTrue(report.sources().stream().anyMatch(s->s.sourceId().equals(project.origin().sourceId())&&s.sha256().equals(project.origin().sourceHash())));
        assertEquals(Json.hash(report),Json.hash(Json.MAPPER.readValue(Json.canonical(report),InvestigationReport.class)));
    }
    @Test void conditionsImportsAndDynamicPropertiesAreNeverEvaluated() throws Exception {
        put("App.csproj","<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup Condition=\"'$(OS)' == 'Windows_NT'\"><TargetFramework>net8.0-windows</TargetFramework><LangVersion>12.0</LangVersion></PropertyGroup><PropertyGroup><TargetFramework>$(InheritedFramework)</TargetFramework></PropertyGroup><Import Project=\"other.props\"/></Project>");
        put("Directory.Build.props","<Project><PropertyGroup><TargetFramework>net9.0</TargetFramework></PropertyGroup></Project>");
        var inv=analyze("App.csproj","Directory.Build.props").dotnetInventory();var p=inv.projects().getFirst();
        assertEquals("CONDITIONAL",p.targetFrameworks().getFirst().state());assertEquals("windows",p.targetFrameworks().getFirst().platform());
        assertEquals("UNKNOWN",p.targetFrameworks().getLast().family());assertEquals(2,p.targetFrameworks().size());
        assertTrue(p.gaps().contains("DOTNET_MSBUILD_UNEVALUATED"));assertTrue(p.gaps().contains("DOTNET_REPEATED_PROPERTY_DECLARATION"));
        assertTrue(inv.gaps().stream().anyMatch(g->g.code().equals("DOTNET_SHARED_PROPERTIES_NOT_APPLIED")));
    }
    @Test void projectAndSolutionReferencesAreDeclarationsOnlyAndNeverFollowed() throws Exception {
        put("src/App.vbproj","<Project><ItemGroup><ProjectReference Include=\"..\\Shared.fsproj\"/><ProjectReference Include=\"../../outside.csproj\"/><ProjectReference Include=\"missing.csproj\"/></ItemGroup></Project>");
        put("Shared.fsproj","<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");
        put("Mixed.slnx","<Solution><Folder Name=\"/src/\"><Project Path=\"src/App.vbproj\"/></Folder></Solution>");
        var inv=analyze("src/App.vbproj","Shared.fsproj","Mixed.slnx").dotnetInventory();
        var p=inv.projects().stream().filter(v->v.language().equals("VB.NET")).findFirst().orElseThrow();
        assertEquals(List.of("COLLECTED_NOT_BOUND","UNRESOLVED","UNCOLLECTED"),p.references().stream().map(DotnetInventory.Reference::state).toList());
        assertEquals("COLLECTED_NOT_BOUND",inv.solutions().getFirst().projects().getFirst().state());
        assertTrue(p.gaps().contains("DOTNET_TARGET_FRAMEWORK_UNKNOWN"));
    }
    @Test void applicationConfigurationValuesAndExternalEntitiesNeverEnterReport() throws Exception {
        put("App.csproj","<Project><PropertyGroup><TargetFrameworkVersion>v4.8</TargetFrameworkVersion><LangVersion>7.3</LangVersion></PropertyGroup></Project>");
        put("web.config","<configuration><connectionStrings><add connectionString=\"Password=synthetic-secret\"/></connectionStrings><system.web/></configuration>");
        put("appsettings.json","{\"ConnectionStrings\":{\"Main\":\"synthetic-secret\"}}");
        put("bad.fsproj","<!DOCTYPE Project [<!ENTITY e SYSTEM 'file:///etc/passwd'>]><Project>&e;</Project>");
        var report=analyze("App.csproj","web.config","appsettings.json","bad.fsproj");var inv=report.dotnetInventory();
        assertEquals(1,inv.projects().size());assertEquals("4.8",inv.projects().getFirst().targetFrameworks().getFirst().version());
        assertFalse(Json.canonical(report).contains("synthetic-secret"));assertFalse(Json.canonical(report).contains("root:x:"));
        assertTrue(inv.gaps().stream().anyMatch(g->g.code().equals("DOTNET_XML_REJECTED")));
        assertTrue(inv.supportingFiles().stream().anyMatch(f->f.applicationHints().contains("ASP_NET_FRAMEWORK_CONFIGURATION")));
    }
    @Test void cancellationDeadlineAndSourceDriftWithholdInventory() throws Exception {
        put("App.csproj","<Project/>");var source=SourceText.read(temp,"App.csproj");
        ContractTest.assertCode("DOTNET_CANCELLED",()->DotnetInventoryAnalyzer.analyze(Map.of(source.path(),source),1,()->true,Long.MAX_VALUE));
        ContractTest.assertCode("DOTNET_TIME_BUDGET",()->DotnetInventoryAnalyzer.analyze(Map.of(source.path(),source),1,()->false,0));
        var report=new InvestigationEngine().investigate(temp,request(List.of("App.csproj")),()->false,sources->{try{put("App.csproj","<Project Sdk=\"Microsoft.NET.Sdk\"/>");}catch(Exception e){throw new RuntimeException(e);}});
        assertNull(report.dotnetInventory());assertTrue(report.coverageGaps().stream().anyMatch(g->g.code().equals("SOURCE_DRIFT")));
    }
    @Test void archivedAgentReportRetainsCanonicalHash() throws Exception {
        // 历史契约夹具走 classpath，文档归档或清理不应改变回归测试输入。
        try(var input=getClass().getResourceAsStream("/fixtures/agent-20260918/pg-resumed.json")) {
            assertNotNull(input,"Missing historical Agent report fixture");
            byte[] bytes=input.readAllBytes();
            var raw=Json.MAPPER.readTree(bytes);
            var typed=Json.MAPPER.readValue(bytes,io.archlens.agent.AgentContracts.Report.class);
            assertEquals(Json.hash(raw),Json.hash(typed));
        }
    }
    @Test void reportsWithoutInventoryKeepOriginalCanonicalShape() throws Exception {
        put("plain.txt","ordinary source");var report=analyze("plain.txt");
        String json=Json.canonical(report);assertFalse(json.contains("dotnetInventory"));assertEquals("archlens.investigation-report.v2",report.schemaVersion());
        assertEquals(Json.hash(Json.MAPPER.readTree(json)),Json.hash(Json.MAPPER.readValue(json,InvestigationReport.class)));
    }
}
