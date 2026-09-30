package io.archlens;

import io.archlens.investigation.database.*;
import io.archlens.parser.SourceText;
import io.archlens.contract.ContractException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DatabaseAssociationTest {
    @Test void vendorConnectionScopeAndIdentity() {
        var oracle=new BusinessConnection("db.example",1521,"APP","reader","secret","DRIVER_DEFAULT","Oracle","ORCLPDB1");
        var source=new BusinessContext.Source("Oracle","APP",null,oracle.fingerprint());
        oracle.verify(source);
        assertThrows(ContractException.class,()->new BusinessConnection("db.example",1521,"APP","reader","secret","DRIVER_DEFAULT","Oracle","PDB?x=1"));
        assertThrows(ContractException.class,()->new BusinessConnection("db.example",5236,"APP","reader","secret","REQUIRED","DM",null));
        assertThrows(ContractException.class,()->oracle.verify(new BusinessContext.Source("DM","APP",null,null)));
        assertFalse(oracle.toString().contains("secret"));
    }
    @Test void routineCallsLinkToVisibleProgramObjects() {
        var procedure=new DatabaseInventory.ProgramObject("PROCEDURE","SYNC_ORDERS",null,null,null,null,null,null,false,null);
        var details=new DatabaseInventory.ExtendedMetadata(List.of(),List.of(),List.of(),List.of(),List.of(procedure),List.of());
        var db=new DatabaseInventory(DatabaseInventory.VERSION,"DM","8","APP","fingerprint","start","finish","PARTIAL","hash",List.of(),Map.of(),List.of(),details);
        var src=new SourceText("jobs.sql","-- CALL FAKE;\nCALL SYNC_ORDERS;", "source-hash",null);
        var result=CodeDatabaseAssociation.analyze(Map.of(src.path(),src),db);
        assertEquals(1,result.links().size());
        assertEquals("SYNC_ORDERS",result.links().getFirst().databaseObject());
        assertNotNull(result.links().getFirst().objectEvidenceId());
    }
    @Test void csharpMethodProducesEvidenceStepsWithoutClaimingDataflow() {
        var db=oneTable("ORDERS");
        var code="""
                public class OrdersController {
                  [HttpGet]
                  public void Read() {
                    var sql = "SELECT * FROM ORDERS";
                    using var cmd = new SqlCommand(sql, connection);
                  }
                }
                """;
        var src=new SourceText("OrdersController.cs",code,"source-hash",null);
        var result=CodeDatabaseAssociation.analyze(Map.of(src.path(),src),db);
        assertEquals(1,result.accessPaths().size());
        var path=result.accessPaths().getFirst();
        assertEquals("SAME_METHOD_CANDIDATE",path.state());
        assertTrue(path.steps().stream().anyMatch(x->x.kind().equals("CODE_METHOD")&&x.name().equals("Read")));
        assertTrue(path.steps().stream().anyMatch(x->x.kind().equals("ENTRY_DECLARATION")));
        assertTrue(path.steps().stream().anyMatch(x->x.kind().equals("DATA_ACCESS_SYNTAX")));
        assertTrue(path.unknownReasons().contains("SQL_TO_INVOCATION_NOT_PROVEN"));
        assertTrue(path.unknownReasons().contains("RUNTIME_EXECUTION_UNVERIFIED"));
    }
    @Test void directSqlLiteralArgumentHasItsOwnCandidateState() {
        var src=new SourceText("Orders.java","class Orders {\n  void load() { connection.prepareStatement(\"SELECT * FROM orders\"); }\n}","source-hash",null);
        var result=CodeDatabaseAssociation.analyze(Map.of(src.path(),src),oneTable("orders"));
        assertEquals(1,result.accessPaths().size());
        assertEquals("DIRECT_ARGUMENT_CANDIDATE",result.accessPaths().getFirst().state());
        assertFalse(result.accessPaths().getFirst().unknownReasons().contains("SQL_TO_INVOCATION_NOT_PROVEN"));
        assertTrue(result.accessPaths().getFirst().unknownReasons().contains("RUNTIME_EXECUTION_UNVERIFIED"));
    }
    @Test void exhaustedAssociationBudgetCannotPublishPartialPath() {
        var src=new SourceText("Orders.sql","SELECT * FROM orders","source-hash",null);
        var timeout=assertThrows(ContractException.class,()->CodeDatabaseAssociation.analyze(
                Map.of(src.path(),src),oneTable("orders"),()->false,System.nanoTime()-1));
        assertEquals("DB_CODE_ASSOCIATION_TIME_BUDGET",timeout.code());
        var cancelled=assertThrows(ContractException.class,()->CodeDatabaseAssociation.analyze(
                Map.of(src.path(),src),oneTable("orders"),()->true,Long.MAX_VALUE));
        assertEquals("DB_CODE_ASSOCIATION_CANCELLED",cancelled.code());
    }
    @Test void accessCallInAnotherMethodDoesNotCreateFalsePathEdge() {
        var code="""
                class Orders {
                  void read() { var sql = "SELECT * FROM orders"; }
                  void run() { connection.prepareStatement("SELECT 1"); }
                }
                """;
        var src=new SourceText("Orders.java",code,"source-hash",null);
        var result=CodeDatabaseAssociation.analyze(Map.of(src.path(),src),oneTable("orders"));
        assertEquals(1,result.accessPaths().size());
        var path=result.accessPaths().getFirst();
        assertEquals("METHOD_SQL_CANDIDATE",path.state());
        assertTrue(path.unknownReasons().contains("DATA_ACCESS_CALL_UNRESOLVED"));
        assertFalse(path.steps().stream().anyMatch(x->x.kind().equals("DATA_ACCESS_SYNTAX")));
    }
    @Test void mybatisStatementBodyAssociatesWithoutReadingExternalResources() {
        var db=oneTable("orders");
        var xml="""
                <!DOCTYPE mapper SYSTEM "https://invalid.example/mapper.dtd">
                <mapper namespace="demo.OrderMapper">
                  <!-- SELECT * FROM fake -->
                  <select id="findOrders" resultType="map">SELECT * FROM orders</select>
                </mapper>
                """;
        var src=new SourceText("OrderMapper.xml",xml,"source-hash",null);
        var result=CodeDatabaseAssociation.analyze(Map.of(src.path(),src),db);
        assertEquals(1,result.links().size());
        var path=result.accessPaths().getFirst();
        assertEquals("MAPPER_STATEMENT_CANDIDATE",path.state());
        assertTrue(path.steps().stream().anyMatch(x->x.kind().equals("MYBATIS_STATEMENT")&&x.name().equals("demo.OrderMapper.findOrders")));
        assertTrue(path.steps().stream().anyMatch(x->x.kind().equals("DATABASE_OBJECT")));
    }
    @Test void sqlInCommentDoesNotCreatePath() {
        var db=oneTable("orders");
        var src=new SourceText("Orders.cs","// var sql = \"SELECT * FROM orders\";","source-hash",null);
        var result=CodeDatabaseAssociation.analyze(Map.of(src.path(),src),db);
        assertTrue(result.links().isEmpty());
        assertTrue(result.accessPaths().isEmpty());
    }
    private static DatabaseInventory oneTable(String name) {
        var table=new DatabaseInventory.Table("table-evidence",name,"TABLE",null,null,List.of(),List.of(),List.of());
        return new DatabaseInventory(DatabaseInventory.VERSION,"MySQL","8.4","app","fingerprint","start","finish","PARTIAL","hash",List.of(table),Map.of(),List.of());
    }
    @Test void sqlReferencesRemainCandidatesWithSourceEvidence() {
        var table=new DatabaseInventory.Table("table-evidence","ORDERS","TABLE",null,null,List.of(),List.of(),List.of());
        var db=new DatabaseInventory(DatabaseInventory.VERSION,"Oracle","19","APP","fingerprint","start","finish","PARTIAL","hash",List.of(table),Map.of(),List.of());
        var src=new SourceText("OrderRepository.cs","// FROM Fake\nvar sql = \"SELECT * FROM ORDERS JOIN MISSING ON 1=1\";", "source-hash",null);
        var result=CodeDatabaseAssociation.analyze(Map.of(src.path(),src),db);
        assertEquals(2,result.links().size());
        assertEquals("CANDIDATE",result.links().get(0).state());
        assertEquals("table-evidence",result.links().get(0).objectEvidenceId());
        assertEquals("UNMATCHED",result.links().get(1).state());
        assertEquals(2,result.links().get(0).line());
    }
}
