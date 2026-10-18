package io.archlens;

import io.archlens.agent.*;
import io.archlens.agent.AgentContracts.*;
import io.archlens.contract.*;
import io.archlens.investigation.InvestigationRequest.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GenericAgentTest {
    @TempDir Path root;
    Request request(String objective,List<String> files){return new Request(AgentContracts.VERSION,objective,null,List.of("只读"),List.of(),files,null,new Budget(100,1000000,10000),new Limits(16,24,60000));}
    Request spring()throws Exception {
        Files.writeString(root.resolve("pom.xml"),"<project><parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>2.7.18</version></parent><properties><java.version>8</java.version><private.password>secret-token</private.password></properties></project>");
        Files.writeString(root.resolve("PrivateAccount.java"),"import javax.xml.bind.JAXBContext; import javax.servlet.Filter; class PrivateAccount {}");
        return request("将 springboot 升级到 3.0.13，同时 Java 升级到 21，保持现有行为。",List.of("pom.xml","PrivateAccount.java"));
    }
    AgentModel.Call analysisStep(int step,List<Map<String,Object>> messages,Target target)throws Exception {
        return switch(step) {
            case 0->AgentTest.call(step,"propose_target",target);
            case 1->AgentTest.call(step,"list_rules",Map.of());
            case 2->{var ids=new ArrayList<String>();AgentTest.last(messages).path("rules").forEach(r->ids.add(r.path("ruleId").asText()));yield AgentTest.call(step,"run_analysis",Map.of("ruleIds",ids));}
            case 3->AgentTest.call(step,"read_evidence",Map.of("findingIds",List.of(AgentTest.last(messages).path("findings").get(0).path("findingId").asText())));
            default->{var f=AgentTest.last(messages).path("findings").get(0);var ids=new ArrayList<String>();f.path("evidenceIds").forEach(n->ids.add(n.asText()));yield AgentTest.call(step,"finish",Map.of("explanations",List.of(new Explanation(f.path("findingId").asText(),ids,f.path("outcome").asText(),"需根据证据验证迁移行为。",List.of("验证边界行为")))));}
        };
    }
    @Test void sourceVersionsComeFromRepositoryAndNoPrivateContentIsSent()throws Exception {
        var req=spring();var captured=new ArrayList<String>();var step=new AtomicInteger();
        var target=new Target(Scenario.DEPENDENCY_UPGRADE,new Profile("Spring Boot","2.7.18"),new Profile("Spring Boot","3.0.13"));
        var report=new AgentOrchestrator((m,t,time)->{captured.add(Json.canonical(m));int i=step.getAndIncrement();return i==0?AgentTest.call(100,"inspect_repository",Map.of()):analysisStep(i-1,m,target);}).investigate(root,req,()->false);
        assertEquals("MODEL_TOOL_LOOP",report.orchestration());assertTrue(report.questions().isEmpty());
        assertEquals("2.7.18",report.interpretedTarget().sourceProfile().version());assertNotNull(report.repositoryInventory());
        assertTrue(report.investigation().findings().stream().anyMatch(f->f.ruleRef().contains("BOOT")));
        String sent=String.join("",captured);for(String value:List.of(root.toString(),"PrivateAccount", "secret-token","pom.xml"))assertFalse(sent.contains(value),value);
        assertEquals(report,Json.MAPPER.readValue(Json.canonical(report),Report.class));
    }
    @Test void dotnetSemanticFactsCanGroundUnverifiedCppMigrationPlan()throws Exception {
        Files.writeString(root.resolve("App.csproj"),"<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");
        Files.writeString(root.resolve("PrivateWidget.cs"),"class PrivateWidget { void Run() { Helper(); } void Helper() {} }\n");
        var readiness=io.archlens.investigation.dotnet.DotnetSemantics.inspect(root,List.of("PrivateWidget.cs"),()->false,10000);
        Assumptions.assumeTrue(!readiness.status().equals("UNAVAILABLE"),"Build the local Roslyn host for Agent integration validation");
        var req=request("将现有 .NET 系统迁移到 C++ 核心和 Electron 界面，保持现有功能",List.of("App.csproj","PrivateWidget.cs"));
        var target=new Target(Scenario.LANGUAGE_MIGRATION,new Profile(".NET",null),new Profile("C++",null));
        var captured=new ArrayList<String>();var n=new AtomicInteger();
        var report=new AgentOrchestrator((messages,tools,time)->{
            captured.add(Json.canonical(messages));int step=n.getAndIncrement();
            return switch(step){
                case 0->AgentTest.call(step,"inspect_repository",Map.of());
                case 1->AgentTest.call(step,"propose_target",target);
                case 2->AgentTest.call(step,"inspect_dotnet_semantics",Map.of());
                case 3->{assertEquals(2,AgentTest.last(messages).path("authorizedFileCount").asInt());
                    assertEquals(1,AgentTest.last(messages).path("authorizedCsharpFileCount").asInt());
                    String id=AgentTest.last(messages).path("facts").get(0).path("id").asText();
                    yield AgentTest.call(step,"draft_migration_plan",Map.of("status","UNVERIFIED_PROPOSAL","targetArchitecture","C++ core + Electron UI",
                        "steps",List.of(Map.of("phase","Core","action","梳理调用边界并重写核心逻辑","factIds",List.of(id),"validation","对照现有行为执行回归用例")),
                        "openQuestions",List.of("确认 C++ 标准及平台接口")));}
                default->AgentTest.call(step,"finish",Map.of("explanations",List.of()));
            };
        }).investigate(root,req,()->false);
        assertEquals("MODEL_TOOL_LOOP",report.orchestration());
        assertEquals("UNVERIFIED_PROPOSAL",report.migrationPlan().status());
        assertNotNull(report.dotnetSemantics());
        assertEquals(Status.PARTIAL,report.status());
        assertNull(report.investigation());
        String sent=String.join("",captured);
        assertFalse(sent.contains("PrivateWidget"));assertFalse(sent.contains(root.toString()));
        assertEquals(report,Json.MAPPER.readValue(Json.canonical(report),Report.class));
    }
    @Test void compoundGoalCreatesIndependentBackendSubagentsWithinSharedBudget()throws Exception {
        var req=spring();var captured=new ArrayList<String>();
        var boot=new Target(Scenario.DEPENDENCY_UPGRADE,new Profile("Spring Boot","2.7.18"),new Profile("Spring Boot","3.0.13"));
        var java=new Target(Scenario.DEPENDENCY_UPGRADE,new Profile("Java","8"),new Profile("Java","21"));
        var report=new AgentOrchestrator((messages,tools,time)->{
            captured.add(Json.canonical(messages));Target assigned=null;
            for(var message:messages)if("user".equals(message.get("role"))){var content=Json.MAPPER.readTree((String)message.get("content"));if(content.has("assignedTarget"))assigned=Json.MAPPER.treeToValue(content.get("assignedTarget"),Target.class);}
            int step=(int)messages.stream().filter(m->"assistant".equals(m.get("role"))).count();
            if(assigned!=null){assertFalse(Json.canonical(tools).contains("\"name\":\"delegate_analysis\""));return analysisStep(step,messages,assigned);}
            return switch(step){case 0->AgentTest.call(100,"inspect_repository",Map.of());case 1->AgentTest.call(101,"delegate_analysis",boot);case 2->AgentTest.call(102,"delegate_analysis",java);default->AgentTest.call(103,"finish",Map.of("explanations",List.of()));};
        }).investigate(root,req,()->false);
        assertEquals(2,report.subtasks().size());assertEquals(14,report.modelCalls());assertTrue(report.modelCalls()<=req.agentBudget().maxModelCalls());
        assertEquals(Status.PARTIAL,report.status());assertEquals("MODEL_TOOL_LOOP",report.orchestration());
        for(var sub:report.subtasks()){assertTrue(sub.subtasks().isEmpty());assertNotNull(sub.investigation());assertFalse(sub.explanations().isEmpty());}
        assertEquals(report,Json.MAPPER.readValue(Json.canonical(report),Report.class));
        assertFalse(String.join("",captured).contains("secret-token"));
    }
    @Test void dotnetToJavaAndDatabaseMigrationUseNaturalLanguageWithoutDeclaredTarget()throws Exception {
        Files.writeString(root.resolve("App.csproj"),"<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");
        Files.writeString(root.resolve("schema.sql"),"CREATE TABLE sample (id INT UNSIGNED);");
        var targets=List.of(new Target(Scenario.LANGUAGE_MIGRATION,new Profile(".NET",null),new Profile("Java","21")),
                new Target(Scenario.DATABASE_MIGRATION,new Profile("MySQL","8.0.36"),new Profile("PostgreSQL","16")));
        for(int x=0;x<2;x++){
            var req=x==0?request("将现有系统迁移到 Java21",List.of("App.csproj")):request("将 MySQL 8.0.36 更换为 PostgreSQL 16",List.of("schema.sql"));
            var target=targets.get(x);var step=new AtomicInteger();
            var report=new AgentOrchestrator((m,t,time)->{int i=step.getAndIncrement();return i==0?AgentTest.call(100,"inspect_repository",Map.of()):analysisStep(i-1,m,target);}).investigate(root,req,()->false);
            assertEquals("MODEL_TOOL_LOOP",report.orchestration());assertTrue(report.questions().isEmpty());assertEquals(target,report.interpretedTarget());
            if(x==0)assertNotNull(report.investigation().dotnetInventory());
            else assertTrue(report.investigation().findings().stream().anyMatch(f->f.outcome().equals("INCOMPATIBLE")));
        }
    }
    @Test void declaredTargetEnvironmentGroundsVersionWithoutFixedFrontendScenario()throws Exception {
        Files.writeString(root.resolve("App.csproj"),"<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");
        var environment=new io.archlens.investigation.database.BusinessContext.Environment(null,null,new Profile("Linux",null),"ARM64",new Profile("Java","21"));
        var context=new io.archlens.investigation.database.BusinessContext("archlens.business-context.v1",null,environment);
        var req=new Request(AgentContracts.VERSION_2,"将当前系统迁移到目标运行时，保持现有接口行为",null,List.of(),List.of(),List.of("App.csproj"),null,new Budget(10,100000,10000),new Limits(12,16,30000),context);
        var target=new Target(Scenario.LANGUAGE_MIGRATION,new Profile(".NET",null),new Profile("Java","21"));
        var captured=new ArrayList<String>();var n=new AtomicInteger();
        var report=new AgentOrchestrator((m,t,time)->{captured.add(Json.canonical(m));int i=n.getAndIncrement();return i==0?AgentTest.call(100,"inspect_repository",Map.of()):analysisStep(i-1,m,target);}).investigate(root,req,()->false);
        assertEquals(target,report.interpretedTarget());assertNotNull(report.investigation());
        assertTrue(String.join("",captured).contains("targetEnvironment"));
        assertFalse(String.join("",captured).contains("App.csproj"));
    }
    @Test void languageTargetCannotOverrideDeclaredRuntime()throws Exception {
        Files.writeString(root.resolve("App.csproj"),"<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");
        var environment=new io.archlens.investigation.database.BusinessContext.Environment(null,null,null,null,new Profile("Java","21"));
        var context=new io.archlens.investigation.database.BusinessContext("archlens.business-context.v1",null,environment);
        var req=new Request(AgentContracts.VERSION_2,"将当前系统迁移到目标运行时",null,List.of(),List.of(),List.of("App.csproj"),null,new Budget(10,100000,10000),new Limits(6,8,30000),context);
        var wrong=new Target(Scenario.LANGUAGE_MIGRATION,new Profile(".NET",null),new Profile("Java","17"));var n=new AtomicInteger();
        var report=new AgentOrchestrator((m,t,time)->n.getAndIncrement()==0?AgentTest.call(0,"inspect_repository",Map.of()):AgentTest.call(n.get(),"propose_target",wrong)).investigate(root,req,()->false);
        assertTrue(report.diagnostics().contains("TARGET_CONTEXT_CONFLICT"));assertNull(report.interpretedTarget().scenario());
    }
    @Test void naturalLanguageClarificationsRetainEarlierAnswersAcrossRevisions()throws Exception {
        var req=spring();AgentModel clarify=(m,t,time)->AgentTest.call(0,"ask_clarification",Map.of("questions",List.of(Map.of("field","context","prompt","请补充目标约束"))));
        var agent=new AgentOrchestrator(clarify);var first=agent.investigate(root,req,()->false);
        var second=agent.execute(root,req,first,new Answers(AgentContracts.VERSION,Json.hash(first),Map.of(first.questions().getFirst().questionId(),"目标是 Java21")),()->false,sources->{});
        var answers=AgentOrchestrator.validateResume(req,second,new Answers(AgentContracts.VERSION,Json.hash(second),Map.of(second.questions().getFirst().questionId(),"保持接口兼容")));
        assertEquals("目标是 Java21\n保持接口兼容",answers.get("context"));
    }
    @Test void databaseOnlyRequestNeedsNoCodeRoot()throws Exception {
        var context=new io.archlens.investigation.database.BusinessContext("archlens.business-context.v1",new io.archlens.investigation.database.BusinessContext.Source("MySQL","synthetic",null,null),null);
        var req=new Request(AgentContracts.VERSION_2,"分析数据库更换的影响",null,List.of(),List.of(),List.of(),null,new Budget(10,10000,10000),new Limits(8,10,30000),context);
        var report=new AgentOrchestrator(null).investigate(null,req,()->false);
        assertEquals("DETERMINISTIC_FALLBACK",report.orchestration());assertNotNull(report.repositoryInventory());
        assertTrue(report.repositoryInventory().sourceHashes().isEmpty());
    }
    @Test void absentModelStillInventoriesGenericInputWithoutInventingScenario()throws Exception {
        var report=new AgentOrchestrator(null).investigate(root,spring(),()->false);
        assertEquals("DETERMINISTIC_FALLBACK",report.orchestration());assertNotNull(report.repositoryInventory());assertEquals(0,report.modelCalls());
        assertTrue(report.questions().isEmpty());assertTrue(report.diagnostics().contains("MODEL_NOT_CONFIGURED"));assertNull(report.interpretedTarget().scenario());
    }
    @Test void inspectionDoesNotInventCsharpVersionFromDotnetAndHandlesOtherLanguages()throws Exception {
        Files.writeString(root.resolve("App.csproj"),"<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework><LangVersion Condition=\"x\">12</LangVersion></PropertyGroup></Project>");
        Files.writeString(root.resolve("main.py"),"password = 'local-secret'\n");
        Files.writeString(root.resolve("package.json"),"{\"name\":\"private-project\",\"dependencies\":{\"react\":\"18.2.0\"},\"scripts\":{\"test\":\"private-command\"}}");
        var req=request("分析现状和依赖风险",List.of("App.csproj","main.py","package.json"));
        var result=RepositoryInspector.inspect(root,req,()->false,System.nanoTime()+5_000_000_000L);
        assertTrue(result.supports(new Profile(".NET","8.0")));assertFalse(result.supports(new Profile("C#","12")));
        assertTrue(result.supports(new Profile("Python",null)));assertTrue(result.supports(new Profile("React","18.2.0")));
        String sent=Json.canonical(result.projection());assertFalse(sent.contains("private"));assertFalse(sent.contains("local-secret"));
    }
    @Test void dotnetProjectTechnologyHintsRemainDeclarationsOnly()throws Exception {
        Files.writeString(root.resolve("App.csproj"),"<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net10.0-windows</TargetFramework><UseWPF>true</UseWPF><UseWindowsForms>true</UseWindowsForms></PropertyGroup><ItemGroup><PackageReference Include=\"Microsoft.Data.Sqlite.Core\" Version=\"9.0.10\"/></ItemGroup></Project>");
        var inventory=RepositoryInspector.inspect(root,request("梳理桌面界面和持久化边界",List.of("App.csproj")),()->false,System.nanoTime()+5_000_000_000L);
        String projection=Json.canonical(inventory.projection());
        assertTrue(projection.contains("WPF"));assertTrue(projection.contains("Windows Forms"));assertTrue(projection.contains("SQLite"));
        assertTrue(inventory.supports(new Profile(".NET","10.0")));
        assertFalse(projection.contains("App.csproj"));
        assertTrue(projection.contains("DECLARATIONS_ONLY"));
    }
    @Test void mixedVersionsAndExternalXmlAreNotGroundedAsOneSourceVersion()throws Exception {
        Files.createDirectory(root.resolve("module"));Files.writeString(root.resolve("pom.xml"),"<project><properties><java.version>8</java.version></properties></project>");
        Files.writeString(root.resolve("module/pom.xml"),"<project><properties><java.version>17</java.version></properties></project>");
        Files.writeString(root.resolve("App.csproj"),"<!DOCTYPE x [<!ENTITY p SYSTEM 'file:///etc/passwd'>]><Project>&p;</Project>");
        var req=request("升级到 Java 21",List.of("pom.xml","module/pom.xml","App.csproj"));
        var inv=RepositoryInspector.inspect(root,req,()->false,System.nanoTime()+5_000_000_000L);
        assertFalse(inv.supports(new Profile("Java","8")));assertTrue(inv.supports(new Profile("Java",null)));
        assertTrue(inv.gaps().contains("PROJECT_DECLARATION_UNRESOLVED"));
    }
    @Test void targetVersionStillCannotBeInventedAndInspectionDriftIsWithheld()throws Exception {
        var req=spring();var n=new AtomicInteger();
        var invented=new Target(Scenario.DEPENDENCY_UPGRADE,new Profile("Java","8"),new Profile("Java","99"));
        var report=new AgentOrchestrator((m,t,time)->{int i=n.getAndIncrement();return i==0?AgentTest.call(i,"inspect_repository",Map.of()):AgentTest.call(i,"propose_target",invented);}).investigate(root,req,()->false);
        assertTrue(report.diagnostics().contains("UNGROUNDED_TARGET"));
        n.set(0);
        var changed=new AgentOrchestrator((m,t,time)->{int i=n.getAndIncrement();if(i==0)return AgentTest.call(i,"inspect_repository",Map.of());Files.writeString(root.resolve("pom.xml"),"<project/>");return AgentTest.call(i,"ask_clarification",Map.of("questions",List.of(Map.of("field","context","prompt","请确认升级约束"))));}).investigate(root,req,()->false);
        assertNull(changed.repositoryInventory());assertTrue(changed.diagnostics().contains("AGENT_SOURCE_DRIFT"));
    }
}
