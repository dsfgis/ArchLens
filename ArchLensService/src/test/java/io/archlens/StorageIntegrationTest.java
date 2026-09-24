package io.archlens;

import io.archlens.contract.*;
import io.archlens.investigation.*;
import io.archlens.storage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in tests use only ArchLens-owned schema/labels and unique synthetic cases. Retained for audit. */
@EnabledIfEnvironmentVariable(named="ARCHLENS_STORAGE_IT",matches="true")
class StorageIntegrationTest {
    StorageConfig config;PgInvestigationStore store;InvestigationRequest request;InvestigationReport report;
    @BeforeEach void setup() throws Exception {
        config=new StorageConfig(System.getenv());store=new PgInvestigationStore(config);store.initialize();
        Path root=Path.of("examples/column-rename");request=Json.MAPPER.readValue(root.resolve("investigation.json").toFile(),InvestigationRequest.class);
        report=new InvestigationEngine().investigate(root,request,()->false);
    }
    @Test void realRoundTripAndProjectionAreIdempotent() throws Exception {
        var ticket=store.begin(null,request);store.checkpoint(ticket,report.sources());store.finish(ticket,report);
        var saved=store.load(ticket.runId());assertEquals(Json.hash(report),saved.reportHash());assertEquals("PENDING",saved.projectionState());
        try(var neo=new Neo4jProjection(config)) {
            neo.initialize();neo.project(saved);neo.project(saved);
            assertEquals(Map.of("nodes",3L,"edges",2L),neo.counts(ticket.runId()));
        }
        store.projected(ticket.runId(),saved.graphHash());assertEquals("READY",store.load(ticket.runId()).projectionState());
        ContractTest.assertCode("STALE_RUN",()->store.finish(ticket,report));assertEquals(saved.reportHash(),store.load(ticket.runId()).reportHash());
        System.out.println("STORAGE_EVIDENCE caseId="+ticket.caseId()+" runId="+ticket.runId()+" reportHash="+saved.reportHash());
    }
    @Test void revisionsAreSerializedAndOldReportIsNotCurrent() throws Exception {
        var first=store.begin(null,request);store.finish(first,report);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->store.begin(first.caseId(),request));var b=pool.submit(()->store.begin(first.caseId(),request));
            var ta=a.get();var tb=b.get();assertEquals(Set.of(2,3),Set.of(ta.revision(),tb.revision()));store.fail(ta);store.fail(tb);
        }
        var old=store.load(first.runId());assertFalse(old.latestRevision());assertEquals(Json.hash(report),old.reportHash());
    }
    @Test void cancellationFencesLateWritesAndRetainsCheckpoint() throws Exception {
        var ticket=store.begin(null,request);store.checkpoint(ticket,report.sources());assertTrue(store.cancel(ticket.runId()));
        assertFalse(store.active(ticket));assertFalse(store.cancel(ticket.runId()));
        ContractTest.assertCode("STALE_RUN",()->store.finish(ticket,report));
        ContractTest.assertCode("STALE_RUN",()->store.checkpoint(ticket,List.of()));
        var run=store.load(ticket.runId());assertEquals("CANCELLED",run.state());assertNotNull(run.checkpointJson());assertNull(run.reportJson());
    }
    @Test void expiredLeaseRejectsLateCompletionAndCanBeRecovered() throws Exception {
        var ticket=store.begin(null,request);store.checkpoint(ticket,report.sources());
        try(var c=config.connect();var p=c.prepareStatement("UPDATE archlens.investigation_run SET lease_until=clock_timestamp()-interval '1 second' WHERE run_id=?")) {p.setObject(1,ticket.runId());p.executeUpdate();}
        ContractTest.assertCode("STALE_RUN",()->store.finish(ticket,report));assertTrue(store.expire()>=1);
        assertEquals("FAILED",store.load(ticket.runId()).state());assertNotNull(store.load(ticket.runId()).checkpointJson());
    }
    @Test void projectionFailureDoesNotDiscardPgReportAndRetryUsesSameGraph() throws Exception {
        var ticket=store.begin(null,request);store.finish(ticket,report);var saved=store.load(ticket.runId());
        var bad=new HashMap<>(System.getenv());bad.put("ARCHLENS_NEO4J_URI","bolt://127.0.0.1:1");
        try(var neo=new Neo4jProjection(new StorageConfig(bad))) {assertThrows(Exception.class,()->neo.project(saved));}
        assertEquals(saved.reportHash(),store.load(ticket.runId()).reportHash());assertEquals("PENDING",store.load(ticket.runId()).projectionState());
        try(var neo=new Neo4jProjection(config)) {neo.initialize();neo.project(saved);}
        store.projected(ticket.runId(),saved.graphHash());assertEquals("READY",store.load(ticket.runId()).projectionState());
    }
    @Test void differentRunGraphsNeverMixEvenWithIdenticalOfflineScope() throws Exception {
        var a=store.begin(null,request);var b=store.begin(null,request);store.finish(a,report);store.finish(b,report);
        try(var neo=new Neo4jProjection(config)) {
            neo.initialize();neo.project(store.load(a.runId()));neo.project(store.load(b.runId()));
            assertEquals(Map.of("nodes",3L,"edges",2L),neo.counts(a.runId()));assertEquals(neo.counts(a.runId()),neo.counts(b.runId()));
        }
    }
    @Test void scenarioReportsRoundTripWithoutInventedGraphProjections() throws Exception {
        // 七类合成调查分别封存；只向 ArchLens 自身 schema 写入审计记录。
        for(String name:List.of("mysql-postgresql","oracle-postgresql","csharp-java","java-refactor","spring-boot","jdk-upgrade","httpclient-upgrade")) {
            Path base=Path.of("examples/scenarios",name);
            var req=Json.MAPPER.readValue(base.resolve("investigation.json").toFile(),InvestigationRequest.class);
            var result=new InvestigationEngine().investigate(base,req,()->false);
            assertTrue(result.findings().stream().anyMatch(f->f.rule()!=null));
            var ticket=store.begin(null,req);store.checkpoint(ticket,result.sources());store.finish(ticket,result);
            var stored=store.load(ticket.runId());var restored=Json.MAPPER.readValue(stored.reportJson(),InvestigationReport.class);
            assertEquals(Json.hash(result),stored.reportHash());assertEquals(result,restored);
            assertEquals("PARTIAL",stored.state());assertEquals("NOT_APPLICABLE",stored.projectionState());assertNull(stored.graphJson());
            System.out.println("SCENARIO_STORAGE_EVIDENCE scenario="+name+" runId="+ticket.runId()+" reportHash="+stored.reportHash());
        }
    }
    @Test void nativePostgres16ReadOnlyProbesSupportDialectRules() throws Exception {
        // 只查询原生类型/函数语义，不读写任何业务表，也不执行样例 DDL。
        try(var connection=config.connect();var statement=connection.createStatement()) {
            try(var version=statement.executeQuery("SHOW server_version_num")) {
                assertTrue(version.next());Assumptions.assumeTrue(version.getInt(1)/10000==16,"Dialect probe targets PostgreSQL 16");
            }
            try(var result=statement.executeQuery("SELECT '' IS NULL, 2147483647::integer, (-2147483648)::integer, extract(hour from timestamp '2026-01-01 12:34:56'), COALESCE(NULL::integer,7)")) {
                assertTrue(result.next());assertFalse(result.getBoolean(1));assertEquals(Integer.MAX_VALUE,result.getInt(2));
                assertEquals(Integer.MIN_VALUE,result.getInt(3));assertEquals(12,result.getInt(4));assertEquals(7,result.getInt(5));
            }
            for(String function:List.of("ifnull","nvl")) {
                var error=assertThrows(java.sql.SQLException.class,()->statement.executeQuery("SELECT pg_catalog."+function+"(NULL::integer, 1)"));
                assertEquals("42883",error.getSQLState());
            }
            for(String type:List.of("number","varchar2")) {
                var error=assertThrows(java.sql.SQLException.class,()->statement.executeQuery("SELECT CAST(NULL AS pg_catalog."+type+")"));
                assertEquals("42704",error.getSQLState());
            }
        }
    }
    @Test void agentClarificationPersistsAndOnlyOneResumeWins() throws Exception {
        // Agent 澄清链使用真实 PG 封存，模型响应是可控测试输入；不发送业务数据。
        var base=Path.of("examples/scenarios/mysql-postgresql");
        var req=new io.archlens.agent.AgentContracts.Request(io.archlens.agent.AgentContracts.VERSION,"调查 MySQL 到 PostgreSQL 迁移",
                new io.archlens.agent.AgentContracts.Target(InvestigationRequest.Scenario.DATABASE_MIGRATION,new InvestigationRequest.Profile("MySQL",null),new InvestigationRequest.Profile("PostgreSQL","16")),
                List.of(),List.of(),List.of("schema.sql"),null,InvestigationRequest.Budget.defaults(),new io.archlens.agent.AgentContracts.Limits(8,8,30000));
        var agent=new io.archlens.agent.AgentOrchestrator(null);
        var first=agent.investigate(base,req,()->false);assertEquals(io.archlens.agent.AgentContracts.Status.NEEDS_CLARIFICATION,first.status());
        var a=store.beginAgent(null,req,null);store.finishAgent(a,first);
        var loaded=store.load(a.runId());assertEquals(first,Json.MAPPER.readValue(loaded.reportJson(),io.archlens.agent.AgentContracts.Report.class));
        var answers=new io.archlens.agent.AgentContracts.Answers(io.archlens.agent.AgentContracts.VERSION,loaded.reportHash(),Map.of(first.questions().getFirst().questionId(),"8.0.36"));
        var second=agent.execute(base,req,first,answers,()->false,s->{});
        var b=store.beginAgent(a.caseId(),req,1);ContractTest.assertCode("STALE_ANSWERS",()->store.beginAgent(a.caseId(),req,1));
        store.checkpoint(b,second.investigation().sources());store.finishAgent(b,second);
        assertFalse(store.load(a.runId()).latestRevision());assertEquals(2,store.load(b.runId()).revision());
        assertEquals("NOT_APPLICABLE",store.load(b.runId()).projectionState());assertEquals(Json.hash(second),store.load(b.runId()).reportHash());
        ContractTest.assertCode("STALE_RUN",()->store.finishAgent(b,second));
        System.out.println("AGENT_STORAGE_EVIDENCE caseId="+a.caseId()+" firstRunId="+a.runId()+" resumedRunId="+b.runId()+" reportHash="+Json.hash(second));
    }
}
