package io.archlens;

import io.archlens.contract.*;
import io.archlens.investigation.*;
import io.archlens.storage.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.neo4j.driver.SessionConfig;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ARCHLENS_STORAGE_IT",matches="true")
class Neo4jProjectionTest {
    @Test void standaloneNeo4jTransactionRoundTripAndConcurrentRetry() throws Exception {
        var config=new StorageConfig(System.getenv());Path root=Path.of("examples/column-rename");
        var request=Json.MAPPER.readValue(root.resolve("investigation.json").toFile(),InvestigationRequest.class);
        var report=new InvestigationEngine().investigate(root,request,()->false);
        var graph=new FactGraph(report.columnAnalysis().graph()).document();UUID runId=UUID.randomUUID();
        // Synthetic store envelope exercises Neo4j independently of PG availability, never reported as a PG round trip.
        var run=new PgInvestigationStore.StoredRun(UUID.randomUUID(),runId,1,"PARTIAL","PENDING",Json.hash(report),Json.canonical(report),
                Json.hash(graph),Json.canonical(graph),null,true);
        try(var neo=new Neo4jProjection(config)) {
            neo.initialize();
            try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var a=pool.submit(()->{neo.project(run);return true;});var b=pool.submit(()->{neo.project(run);return true;});
                assertTrue(a.get());assertTrue(b.get());
            }
            assertEquals(Map.of("nodes",3L,"edges",2L),neo.counts(runId));
            try(var driver=config.driver();var session=driver.session(SessionConfig.builder().withDatabase(config.neoDatabase()).build())) {
                var rows=session.run("MATCH (n:ArchLensFact {runId:$run}) RETURN n.payload AS payload",Map.of("run",runId.toString())).list();
                var actual=new HashSet<String>();for(var row:rows) actual.add(Json.canonical(Json.MAPPER.readTree(row.get("payload").asString())));
                assertEquals(new HashSet<>(graph.nodes().stream().map(Json::canonical).toList()),actual);
            }
        } finally {
            // Delete only the random, synthetic run created by this test, not any persisted Case/Run projection.
            try(var driver=config.driver();var session=driver.session(SessionConfig.builder().withDatabase(config.neoDatabase()).build())) {
                session.executeWrite(tx->{tx.run("MATCH (n:ArchLensFact {runId:$run}) DETACH DELETE n",Map.of("run",runId.toString())).consume();
                    tx.run("MATCH (s:ArchLensProjection {runId:$run}) DELETE s",Map.of("run",runId.toString())).consume();return null;});
            }
        }
    }
}
