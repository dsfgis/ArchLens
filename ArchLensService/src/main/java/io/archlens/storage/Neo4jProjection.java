package io.archlens.storage;

import io.archlens.contract.*;
import io.archlens.contract.Model.GraphDocument;
import org.neo4j.driver.*;
import java.time.Duration;
import java.util.*;
import static io.archlens.contract.ContractException.require;

/** Rebuildable per-run projection. All data values are parameters; labels are fixed. */
public final class Neo4jProjection implements AutoCloseable {
    private final Driver driver; private final String database;
    private static final TransactionConfig TX=TransactionConfig.builder().withTimeout(Duration.ofSeconds(15)).build();
    public Neo4jProjection(StorageConfig config) { driver=config.driver();database=config.neoDatabase(); }
    private Session session() {return driver.session(SessionConfig.builder().withDatabase(database).build());}
    public String check() {
        try(var session=session()) {return session.executeRead(tx->tx.run("RETURN 1 AS ok").consume().server().agent(),TX);}
    }
    public void initialize() {
        try(var session=session()) {
            session.executeWrite(tx->{tx.run("CREATE CONSTRAINT archlens_projection_run IF NOT EXISTS FOR (n:ArchLensProjection) REQUIRE n.runId IS UNIQUE").consume();return null;},TX);
            session.executeWrite(tx->{tx.run("CREATE CONSTRAINT archlens_fact_key IF NOT EXISTS FOR (n:ArchLensFact) REQUIRE n.key IS UNIQUE").consume();return null;},TX);
        }
    }
    public void project(PgInvestigationStore.StoredRun run) throws Exception {
        require(run.graphJson()!=null && Set.of("PARTIAL","COMPLETED").contains(run.state()),"NO_SEALED_GRAPH","Run has no sealed graph");
        GraphDocument graph=new FactGraph(Json.MAPPER.readValue(run.graphJson(),GraphDocument.class)).document();
        require(Json.hash(graph).equals(run.graphHash()),"STORAGE_CORRUPT","Graph hash does not match validated document");
        List<Map<String,Object>> nodes=graph.nodes().stream().map(n->Map.<String,Object>of(
                "key",run.runId()+":"+n.nodeId(),"nodeId",n.nodeId(),"type",n.type().name(),"name",n.name(),"payload",Json.canonical(n))).toList();
        List<Map<String,Object>> edges=graph.edges().stream().map(e->Map.<String,Object>of(
                "from",run.runId()+":"+e.fromId(),"to",run.runId()+":"+e.toId(),"relationId",e.relationId(),
                "kind",e.kind().name(),"evidenceIds",e.evidenceIds(),"payload",Json.canonical(e))).toList();
        Map<String,Object> parameters=new HashMap<>();parameters.put("run",run.runId().toString());parameters.put("digest",run.graphHash());
        parameters.put("case",run.caseId().toString());parameters.put("revision",run.revision());parameters.put("nodes",nodes);parameters.put("edges",edges);
        try(var session=session()) {
            session.executeWrite(tx->{
                // Updating this marker serializes concurrent retry/rebuild operations for the same run.
                var marker=tx.run("MERGE (s:ArchLensProjection {runId:$run}) ON CREATE SET s.digest=$digest SET s.lockVersion=coalesce(s.lockVersion,0)+1 RETURN s.digest AS digest",parameters).single();
                require(marker.get("digest").asString().equals(run.graphHash()),"PROJECTION_CONFLICT","Run graph digest differs");
                tx.run("UNWIND $nodes AS row MERGE (n:ArchLensFact {key:row.key}) SET n.runId=$run,n.nodeId=row.nodeId,n.type=row.type,n.name=row.name,n.payload=row.payload",parameters).consume();
                tx.run("UNWIND $edges AS row MATCH (a:ArchLensFact {key:row.from}), (b:ArchLensFact {key:row.to}) MERGE (a)-[r:ARCHLENS_DEPENDENCY {relationId:row.relationId}]->(b) SET r.runId=$run,r.kind=row.kind,r.evidenceIds=row.evidenceIds,r.payload=row.payload",parameters).consume();
                long nodeCount=tx.run("MATCH (n:ArchLensFact {runId:$run}) RETURN count(n) AS total",parameters).single().get("total").asLong();
                long edgeCount=tx.run("MATCH (:ArchLensFact {runId:$run})-[r:ARCHLENS_DEPENDENCY {runId:$run}]->(:ArchLensFact {runId:$run}) RETURN count(r) AS total",parameters).single().get("total").asLong();
                require(nodeCount==nodes.size() && edgeCount==edges.size(),"PROJECTION_MISMATCH","Projection record counts differ");
                tx.run("MATCH (s:ArchLensProjection {runId:$run}) SET s.state='READY',s.caseId=$case,s.revision=$revision",parameters).consume();return null;
            },TX);
        }
    }
    public Map<String,Long> counts(UUID runId) {
        try(var session=session()) {return session.executeRead(tx->{
            var params=Map.<String,Object>of("run",runId.toString());
            long n=tx.run("MATCH (n:ArchLensFact {runId:$run}) RETURN count(n) AS total",params).single().get("total").asLong();
            long e=tx.run("MATCH (:ArchLensFact {runId:$run})-[r:ARCHLENS_DEPENDENCY {runId:$run}]->(:ArchLensFact {runId:$run}) RETURN count(r) AS total",params).single().get("total").asLong();
            return Map.of("nodes",n,"edges",e);
        },TX);}
    }
    @Override public void close() {driver.close();}
}
