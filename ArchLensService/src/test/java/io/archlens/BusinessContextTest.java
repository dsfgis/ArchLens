package io.archlens;
import io.archlens.agent.*;
import io.archlens.agent.AgentContracts.*;
import io.archlens.contract.*;
import io.archlens.investigation.database.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static io.archlens.investigation.InvestigationRequest.*;
class BusinessContextTest {
    @TempDir Path root;
    BusinessContext context(){return new BusinessContext(BusinessContext.VERSION,new BusinessContext.Source("MySQL","private_ledger","8.4",null),new BusinessContext.Environment(new Profile("KingbaseES","V8R6"),"Oracle",new Profile("Linux",null),"ARM64",new Profile("Java","21")));}
    BusinessConnection connection(){return new BusinessConnection("db.internal",3306,"private_ledger","private_account","synthetic-password","VERIFY_IDENTITY");}
    Request request(){return new Request(AgentContracts.VERSION_2,"代码和业务结构联合调查",new Target(Scenario.CURRENT_STATE,new Profile(".NET",null),null),List.of(),List.of(),List.of(),null,Budget.defaults(),new Limits(1,1,30000),context());}
    @Test void earlierDatabaseInventoryVersionKeepsCanonicalHash() throws Exception {
        var old=new DatabaseInventory("archlens.database-inventory.v1","MySQL","8.4.11","private_ledger",null,
                "2026-09-25T00:00:00Z","2026-09-25T00:00:01Z","PARTIAL","abc",List.of(),Map.of(),List.of());
        String json=Json.canonical(old);assertFalse(json.contains("extendedMetadata"));
        assertEquals(Json.hash(old),Json.hash(Json.MAPPER.readValue(json,DatabaseInventory.class)));
    }
    @Test void databaseOnlyNeedsNoRootButCodeStillDoes() throws Exception {
        var req=new io.archlens.investigation.InvestigationRequest(io.archlens.investigation.InvestigationRequest.VERSION,Scenario.CURRENT_STATE,new Profile("MySQL","8.4"),null,"仅数据库",List.of(),List.of(),List.of(),null,Budget.defaults());
        var engine=new io.archlens.investigation.InvestigationEngine();
        var report=engine.investigate(null,req,()->false,x->{},null,context());
        assertTrue(report.sources().isEmpty());assertNotNull(report.databaseInventory());assertNull(report.dotnetInventory());
        ContractTest.assertCode("SOURCE_ROOT_INVALID",()->engine.investigate(null,req,()->false));
        var code=new io.archlens.investigation.InvestigationRequest(req.schemaVersion(),req.scenario(),req.sourceProfile(),null,req.objective(),List.of(),List.of(),List.of("App.csproj"),null,Budget.defaults());
        ContractTest.assertCode("SOURCE_ROOT_INVALID",()->engine.investigate(null,code,()->false,x->{},null,context()));
    }
    @Test void versionedContextAndOldReportsRoundTripWithoutCredentials() throws Exception {
        var r=request();assertEquals(r,Json.MAPPER.readValue(Json.canonical(r),Request.class));assertFalse(Json.canonical(r).contains("password"));
        // 与 .NET 回归共用原字节测试资源，不再把历史文档目录当作运行依赖。
        try(var input=getClass().getResourceAsStream("/fixtures/agent-20260918/pg-resumed.json")) {
            assertNotNull(input,"Missing historical Agent report fixture");
            byte[] bytes=input.readAllBytes();
            assertEquals(Json.hash(Json.MAPPER.readTree(bytes)),Json.hash(Json.MAPPER.readValue(bytes,Report.class)));
        }
        ContractTest.assertCode("UNSUPPORTED_SCHEMA",()->new Request(AgentContracts.VERSION,r.objective(),r.target(),r.constraints(),r.invariants(),r.files(),null,r.collectionBudget(),r.agentBudget(),context()));
    }
    @Test void unsafeOptionsAndChangedEndpointAreRejected() {
        ContractTest.assertCode("DATASOURCE_CONFIG_INVALID",()->new BusinessConnection("host/?allowMultiQueries=true",3306,"private_ledger","user","synthetic","VERIFY_IDENTITY"));
        ContractTest.assertCode("DATASOURCE_SCOPE_INVALID",()->new BusinessConnection("localhost",3306,"db?options=bad","user","synthetic","VERIFY_IDENTITY"));
        var c=connection();assertEquals("BusinessConnection[REDACTED]",c.toString());
        assertEquals(c.fingerprint(),new BusinessConnection(c.host(),c.port(),c.database(),c.username(),"rotated",c.tlsMode()).fingerprint());
        ContractTest.assertCode("DATASOURCE_IDENTITY_CHANGED",()->c.verify(new BusinessContext.Source("MySQL","private_ledger",null,"a".repeat(64))));
    }
    @Test void connectionErrorsAreRedactedAndCancellationPreventsConnection() {
        var n=new AtomicInteger();var collector=new MysqlCollector(c->{n.incrementAndGet();throw new java.sql.SQLException("sensitive endpoint and password","28000");});
        var failed=collector.collect(context().source(),connection(),()->false,Long.MAX_VALUE,false);
        assertTrue(failed.coverageGaps().contains("DB_AUTH_FAILED"));assertFalse(Json.canonical(failed).contains("sensitive"));
        collector.collect(context().source(),connection(),()->true,Long.MAX_VALUE,false);
        collector.collect(context().source(),connection(),()->false,0,false);assertEquals(1,n.get());
    }
    @Test void missingCredentialsRetainCodeAndEnvironmentAndDoNotUploadPrivateContext() throws Exception {
        Files.writeString(root.resolve("App.csproj"),"<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");
        var r=request();r=new Request(r.schemaVersion(),r.objective(),r.target(),r.constraints(),r.invariants(),List.of("App.csproj"),null,r.collectionBudget(),r.agentBudget(),r.businessContext());
        var sent=new ArrayList<String>();var report=new AgentOrchestrator((m,t,b)->{sent.add(Json.canonical(m));throw new ContractException("MODEL_NOT_CONFIGURED","offline");}).investigate(root,r,()->false);
        assertNotNull(report.investigation().dotnetInventory());assertEquals("UNAVAILABLE",report.investigation().databaseInventory().status());
        assertEquals("ARM64",report.investigation().targetEnvironment().architecture());assertEquals("archlens.investigation-report.v4",report.investigation().schemaVersion());
        for(String name:List.of("private_ledger","private_account"))assertFalse(String.join("",sent).contains(name));
        assertEquals(Json.hash(report),Json.hash(Json.MAPPER.readValue(Json.canonical(report),Report.class)));
    }
    @Test void targetEnvironmentCannotContradictMigrationProfile() {
        ContractTest.assertCode("TARGET_CONTEXT_CONFLICT",()->AgentContracts.validateBusinessTarget(new Target(Scenario.DATABASE_MIGRATION,new Profile("MySQL","8.4"),new Profile("PostgreSQL","16")),context()));
    }
}
