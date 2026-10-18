package io.archlens;

import io.archlens.cli.MigrationToolBridgeCli;
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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    @Test void jsonLineBridgeRegistersExecutesAndRejectsStaleOrConflictingTickets() throws Exception {
        var run = runs.enqueue("bridge." + UUID.randomUUID(), request);
        var ticket = runs.claim(run.runId(), "bridgeWorker");
        UUID taskId = UUID.randomUUID();
        var register = new MigrationToolBridgeCli.Request(MigrationToolBridgeCli.VERSION, UUID.randomUUID(),
                "REGISTER", ticket.caseId(), ticket.runId(), ticket.revision(), ticket.requestHash(),
                ticket.workerId(), ticket.epoch(), taskId, "catalog-first", null, null,
                "list_rules", "archlens-rules-1.1", Map.of());
        var registered = bridge(register);
        assertEquals("REGISTERED", registered.status());
        assertNotNull(registered.invocationId());
        assertTrue(registered.artifactRefs().isEmpty());
        assertEquals(registered.invocationId(), bridge(register).invocationId());
        var inspect = new MigrationToolBridgeCli.Request(MigrationToolBridgeCli.VERSION, UUID.randomUUID(),
                "INSPECT", ticket.caseId(), ticket.runId(), ticket.revision(), ticket.requestHash(),
                ticket.workerId(), ticket.epoch(), null, null, registered.invocationId(), registered.snapshotHash(),
                "list_rules", "archlens-rules-1.1", Map.of());
        assertEquals("REGISTERED", bridge(inspect).status());

        var conflict = new MigrationToolBridgeCli.Request(MigrationToolBridgeCli.VERSION, UUID.randomUUID(),
                "EXECUTE", ticket.caseId(), ticket.runId(), ticket.revision(), ticket.requestHash(),
                ticket.workerId(), ticket.epoch(), null, null, registered.invocationId(), "0".repeat(64),
                "list_rules", "archlens-rules-1.1", Map.of());
        assertEquals("MIG_INVOCATION_CONFLICT", bridge(conflict).errorCode());
        var execute = new MigrationToolBridgeCli.Request(MigrationToolBridgeCli.VERSION, UUID.randomUUID(),
                "EXECUTE", ticket.caseId(), ticket.runId(), ticket.revision(), ticket.requestHash(),
                ticket.workerId(), ticket.epoch(), null, null, registered.invocationId(), registered.snapshotHash(),
                "list_rules", "archlens-rules-1.1", Map.of());
        var completed = bridge(execute);
        assertEquals("SUCCEEDED", completed.status());
        assertEquals(1, completed.artifactRefs().size());
        assertEquals(registered.snapshotHash(), completed.artifactRefs().getFirst().contentHash());
        assertEquals(completed.artifactRefs(), bridge(execute).artifactRefs());
        assertEquals(completed.artifactRefs(), bridge(inspect).artifactRefs());
        runs.cancel(run.runId());
        assertEquals("STALE_RUN", bridge(execute).errorCode());
        assertEquals("STALE_RUN", bridge(inspect).errorCode());
    }

    @Test void dispatchedLedgerIsRecoverableByNewEpochWithoutNewInvocation() throws Exception {
        var run = runs.enqueue("reconcile." + UUID.randomUUID(), request);
        var first = runs.claim(run.runId(), "firstWorker");
        var registered = tools.registerRuleCatalog(first, UUID.randomUUID(), "catalog-first");
        haltedWorker("dispatch", first, registered.invocationId(), registered.snapshotHash());
        // A new process would build a fresh store; the durable row is all it needs to inspect.
        var recoveredStore = new PgMigrationToolStore(config);
        assertEquals("DISPATCHED", recoveredStore.inspectRuleCatalog(first, registered.invocationId(),
                registered.snapshotHash()).status());
        try (var connection = config.connect();
             var update = connection.prepareStatement("UPDATE archlens.migration_run SET lease_until=clock_timestamp()-interval '1 second' WHERE run_id=?")) {
            update.setObject(1, run.runId()); assertEquals(1, update.executeUpdate());
        }
        var replacement = runs.claim(run.runId(), "replacementWorker");
        ContractTest.assertCode("STALE_RUN", () -> recoveredStore.inspectRuleCatalog(first,
                registered.invocationId(), registered.snapshotHash()));
        assertEquals("DISPATCHED", recoveredStore.inspectRuleCatalog(replacement,
                registered.invocationId(), registered.snapshotHash()).status());
        var result = recoveredStore.executeRuleCatalog(replacement, registered.invocationId(), registered.snapshotHash());
        assertEquals(result.artifactId(), recoveredStore.inspectRuleCatalog(replacement,
                registered.invocationId(), registered.snapshotHash()).artifactId());
        assertEquals(result, recoveredStore.loadResult(registered.invocationId()));
        try (var connection = config.connect();
             var query = connection.prepareStatement("SELECT count(*) FROM archlens.tool_invocation WHERE run_id=?")) {
            query.setObject(1, run.runId());
            try (var rows = query.executeQuery()) { rows.next(); assertEquals(1, rows.getLong(1)); }
        }
    }

    @Test void killedProcessAfterRegistrationReusesTheSameInvocation() throws Exception {
        var run = runs.enqueue("register-crash." + UUID.randomUUID(), request);
        var first = runs.claim(run.runId(), "registerWorker");
        UUID taskId = UUID.randomUUID();
        haltedWorker("register", first, taskId, "catalog-first");
        var original = tools.registerRuleCatalog(first, taskId, "catalog-first");
        try (var connection = config.connect();
             var update = connection.prepareStatement("UPDATE archlens.migration_run SET lease_until=clock_timestamp()-interval '1 second' WHERE run_id=?")) {
            update.setObject(1, run.runId()); assertEquals(1, update.executeUpdate());
        }
        var replacement = runs.claim(run.runId(), "replacementWorker");
        assertEquals(original, new PgMigrationToolStore(config).registerRuleCatalog(replacement, taskId, "catalog-first"));
        var result = tools.executeRuleCatalog(replacement, original.invocationId(), original.snapshotHash());
        assertEquals(result.artifactId(), tools.inspectRuleCatalog(replacement, original.invocationId(),
                original.snapshotHash()).artifactId());
        try (var connection = config.connect();
             var query = connection.prepareStatement("SELECT count(*) FROM archlens.tool_invocation WHERE run_id=?")) {
            query.setObject(1, run.runId());
            try (var rows = query.executeQuery()) { rows.next(); assertEquals(1, rows.getLong(1)); }
        }
    }

    private static void haltedWorker(String phase, PgMigrationRunStore.Ticket ticket, UUID identity, String detail)
            throws Exception {
        // The child commits its ledger transition, prints a marker, then blocks until forcibly killed.
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                CrashWindow.class.getName(), phase, ticket.caseId().toString(), ticket.runId().toString(),
                Integer.toString(ticket.revision()), ticket.requestHash(), ticket.workerId(),
                Long.toString(ticket.epoch()), identity.toString(), detail).start();
        var reader = Executors.newSingleThreadExecutor();
        try {
            var marker = reader.submit(() -> process.inputReader(StandardCharsets.UTF_8).readLine());
            assertEquals(phase.equals("register") ? "REGISTERED" : "DISPATCHED", marker.get(10, TimeUnit.SECONDS));
        } finally {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            reader.shutdownNow();
        }
    }

    public static final class CrashWindow {
        public static void main(String[] args) throws Exception {
            var ticket = new PgMigrationRunStore.Ticket(UUID.fromString(args[1]), UUID.fromString(args[2]),
                    Integer.parseInt(args[3]), args[4], args[5], Long.parseLong(args[6]), null);
            var store = new PgMigrationToolStore(new StorageConfig(System.getenv()));
            if ("register".equals(args[0])) {
                store.registerRuleCatalog(ticket, UUID.fromString(args[7]), args[8]);
                System.out.println("REGISTERED");
            } else if ("dispatch".equals(args[0])) {
                store.dispatchRuleCatalog(ticket, UUID.fromString(args[7]), args[8]);
                System.out.println("DISPATCHED");
            } else throw new IllegalArgumentException("Unknown crash phase");
            System.out.flush();
            Thread.sleep(60_000);
        }
    }

    private static MigrationToolBridgeCli.Response bridge(MigrationToolBridgeCli.Request request) throws Exception {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exit = MigrationToolBridgeCli.run(new String[]{"migration-tool-bridge"},
                new ByteArrayInputStream((Json.canonical(request) + "\n").getBytes(StandardCharsets.UTF_8)),
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), System.getenv());
        var response = Json.MAPPER.readValue(stdout.toString(StandardCharsets.UTF_8), MigrationToolBridgeCli.Response.class);
        assertEquals(request.requestId(), response.requestId());
        assertEquals("ERROR".equals(response.status()) ? 2 : 0, exit);
        assertFalse(stdout.toString(StandardCharsets.UTF_8).contains("MYSQL_UNSIGNED")); // Body stays in PG.
        return response;
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
