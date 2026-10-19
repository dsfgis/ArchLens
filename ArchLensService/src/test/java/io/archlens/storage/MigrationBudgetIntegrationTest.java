package io.archlens.storage;

import io.archlens.contract.*;
import io.archlens.migration.MigrationRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="ARCHLENS_MIGRATION_SCHEMA_IT",matches="true")
class MigrationBudgetIntegrationTest {
    StorageConfig config; PgMigrationRunStore runs; PgMigrationToolStore tools; PgMigrationRunStore.Ticket ticket;
    @BeforeEach void setup()throws Exception {
        assertTrue(System.getenv("ARCHLENS_PG_URL").endsWith("/archlens_migration_it"));
        config=new StorageConfig(System.getenv());new PgInvestigationStore(config).initialize();
        runs=new PgMigrationRunStore(config);tools=new PgMigrationToolStore(config);
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)Json.MAPPER.readTree(Path.of("examples/migration/request-code-only.json").toFile());
        ((com.fasterxml.jackson.databind.node.ObjectNode)json.get("policyRefs")).putArray("allowedTools").add("list_rules");
        ((com.fasterxml.jackson.databind.node.ObjectNode)json.get("budget")).put("maxToolCalls",1).put("maxModelCalls",1);
        var request=Json.MAPPER.treeToValue(json,MigrationRequest.class);
        ticket=runs.claim(runs.enqueue("budget."+UUID.randomUUID(),request).runId(),"budget-worker");
    }
    @FunctionalInterface interface Work {void apply(Connection c)throws Exception;}
    void transaction(Work work)throws Exception {try(var c=config.connect()){c.setAutoCommit(false);try{PgMigrationRuntimeStore.active(c,ticket);work.apply(c);c.commit();}catch(Exception e){c.rollback();throw e;}}}
    @Test void sharedReservationsAreAtomicIdempotentAndSurviveTakeover()throws Exception {
        CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Future<String>> results=new ArrayList<>();
            for(int i=0;i<2;i++){String key="model-"+i;results.add(pool.submit(()->{ready.countDown();go.await();try{transaction(c->PgMigrationRuntimeStore.reserve(c,ticket.runId(),key,"MODEL"));return "OK";}catch(ContractException e){return e.code();}}));}
            ready.await();go.countDown();List<String> values=new ArrayList<>();for(var f:results)values.add(f.get());
            assertEquals(1,Collections.frequency(values,"OK"));assertEquals(1,Collections.frequency(values,"MIG_MODEL_BUDGET"));
        }
        String saved;
        try(var c=config.connect();var q=c.prepareStatement("SELECT action_key FROM archlens.migration_budget_reservation WHERE run_id=?")){q.setObject(1,ticket.runId());try(var r=q.executeQuery()){assertTrue(r.next());saved=r.getString(1);}}
        transaction(c->{PgMigrationRuntimeStore.reserve(c,ticket.runId(),saved,"MODEL");PgMigrationRuntimeStore.settle(c,ticket.runId(),saved,"MODEL");PgMigrationRuntimeStore.settle(c,ticket.runId(),saved,"MODEL");});
        try(var c=config.connect();var q=c.prepareStatement("UPDATE archlens.migration_run SET lease_until=clock_timestamp()-interval '1 second' WHERE run_id=?")){q.setObject(1,ticket.runId());q.executeUpdate();}
        ticket=runs.claim(ticket.runId(),"new-budget-worker");
        var budget=new PgMigrationRuntimeStore(config).context(ticket).budget();assertEquals(1,budget.modelsUsed());assertEquals(0,budget.modelsReserved());
        var ex=assertThrows(ContractException.class,()->transaction(c->PgMigrationRuntimeStore.reserve(c,ticket.runId(),"next-model","MODEL")));assertEquals("MIG_MODEL_BUDGET",ex.code());
    }
    @Test void logicalToolReservationIsReusedButDispatchRetriesAreBounded()throws Exception {
        var inv=tools.registerRuleCatalog(ticket,UUID.randomUUID(),"bounded");
        tools.dispatchRuleCatalog(ticket,inv.invocationId(),inv.snapshotHash());
        tools.dispatchRuleCatalog(ticket,inv.invocationId(),inv.snapshotHash());
        tools.dispatchRuleCatalog(ticket,inv.invocationId(),inv.snapshotHash());
        var ex=assertThrows(ContractException.class,()->tools.dispatchRuleCatalog(ticket,inv.invocationId(),inv.snapshotHash()));assertEquals("MIG_TOOL_RETRY_BUDGET",ex.code());
        var budget=new PgMigrationRuntimeStore(config).context(ticket).budget();assertEquals(1,budget.toolsUsed());assertEquals(0,budget.toolsReserved());
        var extra=assertThrows(ContractException.class,()->tools.registerRuleCatalog(ticket,UUID.randomUUID(),"another"));assertEquals("MIG_TOOL_BUDGET",extra.code());
        // A stored success remains reusable without another dispatch charge.
        var result=tools.completeRuleCatalog(ticket,inv.invocationId(),inv.snapshotHash());
        assertEquals(result,tools.executeRuleCatalog(ticket,inv.invocationId(),inv.snapshotHash()));
    }
    @Test void uncertainReservationIsNotRefundedAndDeadlineDoesNotReset()throws Exception {
        tools.registerRuleCatalog(ticket,UUID.randomUUID(),"reserved-before-crash");
        try(var c=config.connect();var q=c.prepareStatement("UPDATE archlens.migration_run SET lease_until=clock_timestamp()-interval '1 second',started_at=clock_timestamp()-interval '1 hour' WHERE run_id=?")){q.setObject(1,ticket.runId());q.executeUpdate();}
        ticket=runs.claim(ticket.runId(),"deadline-worker");
        var b=new PgMigrationRuntimeStore(config).context(ticket).budget();assertEquals(1,b.toolsReserved());assertTrue(b.elapsedMillis()>b.maxElapsedMillis());
        var ex=assertThrows(ContractException.class,()->transaction(c->PgMigrationRuntimeStore.reserve(c,ticket.runId(),"new","MODEL")));assertEquals("MIG_ELAPSED_BUDGET",ex.code());
        runs.cancel(ticket.runId());assertEquals("STALE_RUN",assertThrows(ContractException.class,()->transaction(c->PgMigrationRuntimeStore.settle(c,ticket.runId(),"new","MODEL"))).code());
    }
}
