package io.archlens;

import io.archlens.contract.ContractException;
import io.archlens.contract.Json;
import io.archlens.migration.MigrationRequest;
import io.archlens.storage.PgInvestigationStore;
import io.archlens.storage.PgMigrationRunStore;
import io.archlens.storage.StorageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real PG row locks and DB-time leases in a disposable local instance only. */
@EnabledIfEnvironmentVariable(named = "ARCHLENS_MIGRATION_SCHEMA_IT", matches = "true")
class MigrationRunStoreIntegrationTest {
    private StorageConfig config;
    private PgMigrationRunStore store;
    private MigrationRequest request;

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("ARCHLENS_PG_URL");
        assertNotNull(url);
        assertTrue(url.endsWith("/archlens_migration_it"), "Use only a disposable archlens_migration_it database");
        config = new StorageConfig(System.getenv());
        new PgInvestigationStore(config).initialize();
        store = new PgMigrationRunStore(config);
        request = Json.MAPPER.readValue(Path.of("examples/migration/request-code-only.json").toFile(), MigrationRequest.class);
    }

    @Test void concurrentSubmissionReturnsOneRunAndChangedContentConflicts() throws Exception {
        String key = "submit." + UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        Callable<PgMigrationRunStore.Enqueued> action = () -> {
            ready.countDown();
            go.await();
            return store.enqueue(key, request);
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(action);
            var second = pool.submit(action);
            ready.await();
            go.countDown();
            assertEquals(first.get(), second.get());
            var receipt = first.get();
            assertEquals("QUEUED", store.load(receipt.runId()).state());
            assertEquals(0, store.load(receipt.runId()).epoch());
            assertEquals(receipt, store.enqueue(key, request));
        }
        MigrationRequest changed = new MigrationRequest(request.schemaVersion(), request.analysisMode(),
                request.objective() + "（另一个目标）", request.sourceRefs(), request.targetEnvironment(),
                request.constraints(), request.invariants(), request.acceptanceScope(), request.policyRefs(),
                request.budget(), request.unresolvedFields());
        ContractTest.assertCode("MIG_SUBMISSION_CONFLICT", () -> store.enqueue(key, changed));
    }

    @Test void onlyOneWorkerClaimsThenExpiryCreatesAFencedEpoch() throws Exception {
        UUID runId = store.enqueue("lease." + UUID.randomUUID(), request).runId();
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        Callable<Object> actionA = claimAttempt(runId, "workerA", ready, go);
        Callable<Object> actionB = claimAttempt(runId, "workerB", ready, go);
        PgMigrationRunStore.Ticket winner;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(actionA);
            var second = pool.submit(actionB);
            ready.await();
            go.countDown();
            List<Object> outcomes = List.of(first.get(), second.get());
            assertEquals(1, outcomes.stream().filter(PgMigrationRunStore.Ticket.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter("RUN_NOT_CLAIMABLE"::equals).count());
            winner = (PgMigrationRunStore.Ticket) outcomes.stream().filter(PgMigrationRunStore.Ticket.class::isInstance).findFirst().orElseThrow();
        }
        assertEquals(1, winner.epoch());
        assertTrue(store.active(winner));
        var renewed = store.renew(winner);
        assertEquals(winner.epoch(), renewed.epoch());
        assertFalse(renewed.leaseUntil().isBefore(winner.leaseUntil()));

        // Force expiry only in the disposable test database; production code uses PG clock predicates.
        try (var connection = config.connect();
             var update = connection.prepareStatement("UPDATE archlens.migration_run SET lease_until=clock_timestamp()-interval '1 second' WHERE run_id=?")) {
            update.setObject(1, runId);
            assertEquals(1, update.executeUpdate());
        }
        assertFalse(store.active(winner));
        ContractTest.assertCode("STALE_RUN", () -> store.renew(renewed));
        var replacement = store.claim(runId, "replacement");
        assertEquals(2, replacement.epoch());
        assertTrue(store.active(replacement));
        assertFalse(store.active(winner));
        ContractTest.assertCode("STALE_RUN", () -> store.renew(renewed));
    }

    @Test void cancelFencesWorkerAndRepeatsTheSameReceipt() throws Exception {
        String key = "cancel." + UUID.randomUUID();
        var created = store.enqueue(key, request);
        UUID runId = created.runId();
        var ticket = store.claim(runId, "workerA");
        var receipt = store.cancel(runId);
        assertEquals(ticket.epoch() + 1, receipt.epoch());
        assertEquals(receipt, store.cancel(runId));
        assertEquals(created, store.enqueue(key, request)); // Idempotent submission never resurrects a cancelled run.
        assertFalse(store.active(ticket));
        assertEquals("CANCELLED", store.load(runId).state());
        ContractTest.assertCode("STALE_RUN", () -> store.renew(ticket));
        ContractTest.assertCode("RUN_NOT_CLAIMABLE", () -> store.claim(runId, "workerB"));
        try (var connection = config.connect();
             var query = connection.prepareStatement("SELECT event_sequence,event_type FROM archlens.migration_event WHERE run_id=? ORDER BY event_sequence")) {
            query.setObject(1, runId);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next()); assertEquals(1, rows.getLong(1)); assertEquals("CREATED", rows.getString(2));
                assertTrue(rows.next()); assertEquals(2, rows.getLong(1)); assertEquals("CANCELLED", rows.getString(2));
                assertFalse(rows.next());
            }
        }
        UUID queued = store.enqueue("queued-cancel." + UUID.randomUUID(), request).runId();
        assertEquals(store.cancel(queued), store.cancel(queued));
        ContractTest.assertCode("RUN_NOT_CLAIMABLE", () -> store.claim(queued, "workerC"));
    }

    @Test void oldRevisionCannotBeClaimedOrCancelledAfterCaseAdvances() throws Exception {
        var old = store.enqueue("revision." + UUID.randomUUID(), request);
        try (var connection = config.connect()) {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("INSERT INTO archlens.migration_run(run_id,case_id,revision,request_hash,request_json,orchestration_type,execution_state) VALUES(?,?,2,?,?::jsonb,'LANGGRAPH','QUEUED')");
                 var advance = connection.prepareStatement("UPDATE archlens.migration_case SET latest_revision=2 WHERE case_id=?")) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, old.caseId());
                insert.setString(3, old.requestHash());
                insert.setString(4, Json.canonical(request));
                insert.executeUpdate();
                advance.setObject(1, old.caseId());
                advance.executeUpdate();
                connection.commit();
            } catch (Exception failure) { connection.rollback(); throw failure; }
        }
        assertFalse(store.load(old.runId()).latestRevision());
        ContractTest.assertCode("STALE_RUN", () -> store.claim(old.runId(), "lateWorker"));
        ContractTest.assertCode("STALE_RUN", () -> store.cancel(old.runId()));
    }

    private Callable<Object> claimAttempt(UUID runId, String worker, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            go.await();
            try { return store.claim(runId, worker); }
            catch (ContractException error) { return error.code(); }
        };
    }
}
