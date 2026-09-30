package io.archlens;
import io.archlens.agent.*;
import io.archlens.agent.AgentContracts.*;
import io.archlens.cli.WebAgentCli;
import io.archlens.contract.*;
import io.archlens.investigation.database.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.archlens.investigation.InvestigationRequest.*;
/** 仅连接 fixture.sql 建立的本机隔离合成库。 */
@EnabledIfEnvironmentVariable(named="ARCHLENS_MYSQL_IT",matches="true")
class MysqlCollectionIntegrationTest {
    @TempDir Path root;
    BusinessConnection connection(String user){return new BusinessConnection("127.0.0.1",Integer.parseInt(System.getenv("ARCHLENS_MYSQL_TEST_PORT")),"archlens_fixture",user,"synthetic-readonly-password","REQUIRED");}
    BusinessContext.Source source(){return new BusinessContext.Source("MySQL","archlens_fixture","8.4",null);}
    DatabaseInventory collect(String user,boolean test){return new MysqlCollector().collect(source(),connection(user),()->false,System.nanoTime()+15_000_000_000L,test);}
    @Test void realConnectionCollectsStructureWithoutBusinessRows() {
        var test=collect("archlens_reader",true);assertEquals("CONNECTED",test.status(),test.coverageGaps().toString());assertTrue(test.tables().isEmpty());
        var db=collect("archlens_reader",false);assertEquals(3,db.tables().size(),db.coverageGaps().toString());
        var orders=db.tables().stream().filter(t->t.name().equals("orders")).findFirst().orElseThrow();
        assertEquals(4,orders.columns().size());assertTrue(orders.columns().stream().anyMatch(c->c.columnType().contains("unsigned")));
        assertTrue(orders.keys().stream().anyMatch(k->"customers".equals(k.referencedTable())));assertTrue(orders.indexes().stream().anyMatch(i->i.name().equals("uq_customer_created")));
        assertTrue(db.tables().stream().anyMatch(t->t.kind().equals("VIEW")));
        String json=Json.canonical(db);for(String forbidden:List.of("SYNTHETIC_BUSINESS_ROW_NOT_FOR_REPORT","SYNTHETIC_DEFAULT_NOT_FOR_REPORT","out_of_scope","synthetic-readonly-password","127.0.0.1"))assertFalse(json.contains(forbidden));
        assertEquals(db.metadataHash(),collect("archlens_reader",false).metadataHash());
    }
    @Test void extendedMetadataExposesStructureWithoutDefinitionText() {
        var admin=new BusinessConnection("127.0.0.1",Integer.parseInt(System.getenv("ARCHLENS_MYSQL_TEST_PORT")),
                "archlens_fixture","root","archlens-synthetic-admin-only","REQUIRED");
        var db=new MysqlCollector().collect(source(),admin,()->false,System.nanoTime()+15_000_000_000L,false);
        assertEquals("PARTIAL",db.status(),db.coverageGaps().toString());
        assertEquals(DatabaseInventory.VERSION,db.schemaVersion());
        var details=db.extendedMetadata();assertNotNull(details);
        assertTrue(details.columns().stream().anyMatch(c->c.table().equals("customers")&&c.column().equals("internal_note")&&c.defaultPresent()&&c.commentPresent()));
        assertTrue(details.tables().stream().anyMatch(t->t.name().equals("customers")&&t.commentPresent()));
        assertTrue(details.views().stream().anyMatch(v->v.name().equals("order_totals")&&v.definitionVisible()&&v.definitionLength()>0));
        assertTrue(details.checks().stream().anyMatch(c->c.table().equals("customers")&&c.name().equals("ck_customer_name")&&c.enforced()&&c.clauseVisible()));
        assertTrue(details.programs().stream().anyMatch(o->o.kind().equals("PROCEDURE")&&o.name().equals("fixture_probe")));
        assertTrue(details.programs().stream().anyMatch(o->o.kind().equals("TRIGGER")&&o.name().equals("customer_before_insert")));
        assertTrue(details.parameters().stream().anyMatch(o->o.program().equals("fixture_probe")&&o.name().equals("p")&&o.dataType().equals("int")));
        String json=Json.canonical(db);
        for(String text:List.of("SYNTHETIC_DEFAULT_NOT_FOR_REPORT","SYNTHETIC_COLUMN_COMMENT_NOT_FOR_REPORT", "SYNTHETIC_TABLE_COMMENT_NOT_FOR_REPORT","SELECT p","SET NEW.name"))assertFalse(json.contains(text));
        assertEquals(db.metadataHash(),new MysqlCollector().collect(source(),admin,()->false,System.nanoTime()+15_000_000_000L,false).metadataHash());
    }
    @Test void restrictedAccountShowsVisibilityGapAndHasNoWritePrivilege() throws Exception {
        var db=collect("archlens_limited",false);assertEquals(List.of("customers"),db.tables().stream().map(DatabaseInventory.Table::name).toList());assertTrue(db.coverageGaps().contains("DB_ACCOUNT_VISIBLE_OBJECTS_ONLY"));
        try(var c=connection("archlens_reader").connect();var s=c.createStatement()) {
            assertThrows(java.sql.SQLException.class,()->s.executeUpdate("UPDATE archlens_fixture.customers SET name='should-not-write' WHERE id=1"));
            try(var r=s.executeQuery("SELECT name FROM archlens_fixture.customers WHERE id=1")){assertTrue(r.next());assertEquals("SYNTHETIC_BUSINESS_ROW_NOT_FOR_REPORT",r.getString(1));}
        }
    }
    @Test void jointWebReportContainsCodeDatabaseAndEnvironment() throws Exception {
        Files.writeString(root.resolve("App.csproj"),"<Project><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");
        var context=new BusinessContext(BusinessContext.VERSION,source(),new BusinessContext.Environment(new Profile("KingbaseES","V8R6"),"Oracle",new Profile("Linux",null),"ARM64",new Profile("Java","21")));
        var request=new Request(AgentContracts.VERSION_2,"代码和业务数据库结构联合调查",new Target(Scenario.CURRENT_STATE,new Profile(".NET",null),null),List.of(),List.of(),List.of("App.csproj"),null,Budget.defaults(),new Limits(1,1,30000),context);
        var bytes=new ByteArrayOutputStream();WebAgentCli.execute(new WebAgentCli.Input("preview-joint",root.toString(),request,null,null,null,null,connection("archlens_reader")),null,null,new PrintStream(bytes));
        String output=bytes.toString(java.nio.charset.StandardCharsets.UTF_8);var result=Json.MAPPER.readTree(output).path("value");
        assertEquals(1,result.path("report").path("dotnetInventory").path("projects").size());assertEquals(3,result.path("report").path("databaseInventory").path("tables").size());assertEquals("ARM64",result.path("report").path("targetEnvironment").path("architecture").asText());
        assertEquals(Json.hash(result.path("report")),result.path("reportHash").asText());assertFalse(output.contains("synthetic-readonly-password"));assertFalse(output.contains("archlens_reader"));
    }
    @Test void databaseOnlyProtocolReportsItsOwnMode() throws Exception {
        var request=new Request(AgentContracts.VERSION_2,"仅数据库结构调查",new Target(Scenario.CURRENT_STATE,new Profile("MySQL","8.4"),null),
                List.of(),List.of(),List.of(),null,Budget.defaults(),new Limits(1,1,30000),new BusinessContext(BusinessContext.VERSION,source(),null));
        var bytes=new ByteArrayOutputStream();WebAgentCli.execute(new WebAgentCli.Input("preview-joint",null,request,null,null,null,null,connection("archlens_reader")),null,null,new PrintStream(bytes));
        var result=Json.MAPPER.readTree(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)).path("value");
        assertEquals("LOCAL_DATABASE_PREVIEW",result.path("mode").asText());
        assertEquals(0,result.path("report").path("sources").size());
        assertEquals(3,result.path("report").path("databaseInventory").path("tables").size());
    }
    @Test void authenticationAndVersionConflictsStayExplicit() {
        var c=connection("archlens_reader");var bad=new BusinessConnection(c.host(),c.port(),c.database(),c.username(),"wrong-synthetic-password",c.tlsMode());
        var db=new MysqlCollector().collect(source(),bad,()->false,System.nanoTime()+10_000_000_000L,false);assertEquals("UNAVAILABLE",db.status());assertTrue(db.coverageGaps().contains("DB_AUTH_FAILED"));
        var conflict=new MysqlCollector().collect(new BusinessContext.Source("MySQL","archlens_fixture","8.0",null),c,()->false,System.nanoTime()+15_000_000_000L,false);assertTrue(conflict.coverageGaps().contains("DB_DECLARED_VERSION_CONFLICT"));
    }
}
