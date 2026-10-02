package io.archlens;

import io.archlens.contract.Json;
import io.archlens.migration.MigrationRequest;
import io.archlens.storage.PgInvestigationStore;
import io.archlens.storage.PgMigrationRunStore;
import io.archlens.storage.PgMigrationToolStore;
import io.archlens.storage.StorageConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real PG verification of registration-before-dispatch, replay and epoch fencing. */
@EnabledIfEnvironmentVariable(named = "ARCHLENS_MIGRATION_SCHEMA_IT", matches = "true")
class MigrationToolStoreIntegrationTest {
    private StorageConfig config;
    private PgMigrationRunStore runs;
    private PgMigrationToolStore tools;
    private MigrationRequest request;

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("ARCHLENS_PG_URL");
        assertNotNull(url);
        assertTrue(url.endsWith("/archlens_migration_it"), "Use only a disposable archlens_migration_it database");
        config = new StorageConfig(System.getenv());
        new PgInvestigationStore(config).initialize();
        runs = new PgMigrationRunStore(config);
        tools = new PgMigrationToolStore(config);
        var sample = Json.MAPPER.readValue(Path.of("examples/migration/request-code-only.json").toFile(), MigrationRequest.class);
        request = withPolicy(sample, List.of(PgMigrationToolStore.TOOL_ID), 1);
    }

    @Test void persistedResultReplaysWithoutAnotherInvocationOrArtifact() throws Exception {
        var run = runs.enqueue("tool." + UUID.randomUUID(), request);
        var ticket = runs.claim(run.runId(), "catalogWorker");
        UUID taskId = UUID.randomUUID();
        var registered = tools.registerRuleCatalog(ticket, taskId, "catalog-first");
        assertEquals(registered, tools.registerRuleCatalog(ticket, taskId, "catalog-first"));
        ContractTest.assertCode("MIG_RESULT_NOT_READY", () -> tools.loadResult(registered.invocationId()));
        var first = tools.executeRuleCatalog(ticket, registered.invocationId());
        assertEquals(first, tools.executeRuleCatalog(ticket, registered.invocationId()));
        assertEquals(first, tools.loadResult(registered.invocationId()));
        assertTrue(first.contentUtf8().contains("MYSQL_UNSIGNED"));
        assertEquals(first.contentHash(), registered.snapshotHash());
        try (var connection = config.connect();
             var query = connection.prepareStatement("SELECT status,attempts,result_artifact_id FROM archlens.migration_task WHERE task_id=?")) {
            query.setObject(1, taskId);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next()); assertEquals("SUCCEEDED", rows.getString(1));
                assertEquals(1, rows.getInt(2)); assertEquals(first.artifactId(), rows.getObject(3, UUID.class));
            }
        }
        ContractTest.assertCode("MIG_TOOL_BUDGET", () -> tools.registerRuleCatalog(ticket, UUID.randomUUID(), "catalog-again"));
        try (var connection = config.connect();
             var query = connection.prepareStatement("SELECT count(*) FROM archlens.tool_invocation WHERE run_id=?")) {
            query.setObject(1, run.runId());
            try (var rows = query.executeQuery()) { rows.next(); assertEquals(1, rows.getLong(1)); }
        }
        runs.cancel(run.runId());
        assertEquals(first, tools.loadResult(registered.invocationId()));
        ContractTest.assertCode("STALE_RUN", () -> tools.executeRuleCatalog(ticket, registered.invocationId()));
    }

    @Test void expiredWorkerCannotDispatchOrPublishAndReplacementReusesInvocation() throws Exception {
        var run = runs.enqueue("takeover." + UUID.randomUUID(), request);
        var old = runs.claim(run.runId(), "oldWorker");
        var registered = tools.registerRuleCatalog(old, UUID.randomUUID(), "catalog-first");
        // Only the disposable test instance changes lease time; production predicates use PG clock.
        try (var connection = config.connect();
             var update = connection.prepareStatement("UPDATE archlens.migration_run SET lease_until=clock_timestamp()-interval '1 second' WHERE run_id=?")) {
            update.setObject(1, run.runId()); assertEquals(1, update.executeUpdate());
        }
        ContractTest.assertCode("STALE_RUN", () -> tools.executeRuleCatalog(old, registered.invocationId()));
        var replacement = runs.claim(run.runId(), "newWorker");
        assertEquals(registered, tools.registerRuleCatalog(replacement, registered.taskId(), "catalog-first"));
        assertEquals(registered.invocationId(), tools.executeRuleCatalog(replacement, registered.invocationId()).invocationId());
        ContractTest.assertCode("STALE_RUN", () -> tools.registerRuleCatalog(old, UUID.randomUUID(), "late-action"));
    }

    @Test void policyMustAuthorizeTheToolBeforeAnyLedgerWrite() throws Exception {
        var denied = withPolicy(request, List.of("discover_projects"), 1);
        var run = runs.enqueue("denied." + UUID.randomUUID(), denied);
        var ticket = runs.claim(run.runId(), "catalogWorker");
        ContractTest.assertCode("TOOL_NOT_ALLOWED", () -> tools.registerRuleCatalog(ticket, UUID.randomUUID(), "catalog-first"));
        try (var connection = config.connect();
             var query = connection.prepareStatement("SELECT count(*) FROM archlens.tool_invocation WHERE run_id=?")) {
            query.setObject(1, run.runId());
            try (var rows = query.executeQuery()) { rows.next(); assertEquals(0, rows.getLong(1)); }
        }
    }

    private static MigrationRequest withPolicy(MigrationRequest source, List<String> allowedTools, int maxToolCalls) {
        var policy = new MigrationRequest.PolicyRefs(source.policyRefs().authorizationRef(),
                source.policyRefs().modelConfigRef(), source.policyRefs().dataPolicy(),
                source.policyRefs().executionPolicy(), allowedTools);
        var budget = new MigrationRequest.Budget(maxToolCalls, source.budget().maxModelCalls(),
                source.budget().maxElapsedMillis());
        return new MigrationRequest(source.schemaVersion(), source.analysisMode(), source.objective(),
                source.sourceRefs(), source.targetEnvironment(), source.constraints(), source.invariants(),
                source.acceptanceScope(), policy, budget, source.unresolvedFields());
    }
}
