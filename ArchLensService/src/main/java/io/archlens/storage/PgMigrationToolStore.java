package io.archlens.storage;

import io.archlens.contract.Json;
import io.archlens.investigation.rules.RuleCatalog;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static io.archlens.contract.ContractException.require;

/** A first fenced, replayable read-only tool. IDs and action keys are supplied by the trusted host. */
public final class PgMigrationToolStore {
    public static final String TOOL_ID = "list_rules";
    private final StorageConfig config;

    public PgMigrationToolStore(StorageConfig config) { this.config = config; }

    public record Registered(UUID invocationId, UUID taskId, String inputHash, String snapshotHash) {}
    public record Result(UUID invocationId, UUID artifactId, String contentHash, String contentUtf8) {}
    public record InvocationView(UUID invocationId, UUID taskId, String status,
                                 String inputHash, String snapshotHash, UUID artifactId, String contentHash) {}

    /** This manifest is intentionally narrow: no model-selected path, SQL, or arbitrary arguments. */
    public Map<String, Object> manifest() {
        return Map.of("toolId", TOOL_ID, "toolVersion", RuleCatalog.VERSION,
                "readOnly", true, "argumentsSchema", Map.of("type", "object", "additionalProperties", false),
                "maxResultBytes", 65_536, "replay", "same action returns persisted result");
    }

    public Registered registerRuleCatalog(PgMigrationRunStore.Ticket ticket, UUID taskId, String actionKey) throws SQLException {
        require(taskId != null, "MIG_TASK_INVALID", "Task ID is required");
        require(actionKey != null && actionKey.matches("[A-Za-z][A-Za-z0-9._:-]{0,199}"),
                "MIG_ACTION_INVALID", "Invalid host action key");
        String inputHash = Json.hash(Map.of());
        String snapshotHash = catalogHash();
        return transaction(connection -> {
            Context context = activeContext(connection, ticket);
            require(context.allowed && context.maxToolCalls > 0, "TOOL_NOT_ALLOWED", "Rule catalog tool is not authorized");
            // A single catalog action is the first bounded task shape until a planner owns task creation.
            try (var insert = connection.prepareStatement("INSERT INTO archlens.migration_task(task_id,run_id,revision,plan_version,task_version,status) VALUES(?,?,?,1,1,'READY') ON CONFLICT (task_id) DO NOTHING")) {
                insert.setObject(1, taskId);
                insert.setObject(2, ticket.runId());
                insert.setInt(3, ticket.revision());
                insert.executeUpdate();
            }
            String taskStatus;
            try (var query = connection.prepareStatement("SELECT run_id,revision,task_version,status FROM archlens.migration_task WHERE task_id=?")) {
                query.setObject(1, taskId);
                try (var rows = query.executeQuery()) {
                    require(rows.next() && ticket.runId().equals(rows.getObject(1, UUID.class))
                                    && ticket.revision() == rows.getInt(2) && rows.getInt(3) == 1,
                            "MIG_TASK_CONFLICT", "Task ID belongs to a different run or version");
                    taskStatus = rows.getString(4);
                }
            }
            try (var query = connection.prepareStatement("SELECT invocation_id FROM archlens.tool_invocation WHERE run_id=? AND task_id=? AND task_version=1 AND action_key=? AND tool_id=? AND tool_version=? AND input_hash=? AND snapshot_hash=?")) {
                query.setObject(1, ticket.runId()); query.setObject(2, taskId); query.setString(3, actionKey);
                query.setString(4, TOOL_ID); query.setString(5, RuleCatalog.VERSION);
                query.setString(6, inputHash); query.setString(7, snapshotHash);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) return new Registered(rows.getObject(1, UUID.class), taskId, inputHash, snapshotHash);
                }
            }
            require("READY".equals(taskStatus), "MIG_TASK_NOT_READY", "Task already has an action or is terminal");
            try (var query = connection.prepareStatement("SELECT count(*) FROM archlens.tool_invocation WHERE task_id=?")) {
                query.setObject(1, taskId);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    require(rows.getLong(1) == 0, "MIG_TASK_CONFLICT", "This bounded task already owns an invocation");
                }
            }
            try (var query = connection.prepareStatement("SELECT count(*) FROM archlens.tool_invocation WHERE run_id=?")) {
                query.setObject(1, ticket.runId());
                try (var rows = query.executeQuery()) {
                    rows.next();
                    require(rows.getLong(1) < context.maxToolCalls, "MIG_TOOL_BUDGET", "Tool call budget exhausted");
                }
            }
            UUID invocationId = UUID.randomUUID();
            PgMigrationRuntimeStore.reserve(connection, ticket.runId(), invocationId + ":1", "TOOL");
            try (var insert = connection.prepareStatement("INSERT INTO archlens.tool_invocation(invocation_id,run_id,revision,task_id,task_version,action_key,tool_id,tool_version,input_hash,snapshot_hash,status) VALUES(?,?,?,?,1,?,?,?,?,?,'REGISTERED')")) {
                insert.setObject(1, invocationId); insert.setObject(2, ticket.runId());
                insert.setInt(3, ticket.revision()); insert.setObject(4, taskId);
                insert.setString(5, actionKey); insert.setString(6, TOOL_ID);
                insert.setString(7, RuleCatalog.VERSION); insert.setString(8, inputHash);
                insert.setString(9, snapshotHash); insert.executeUpdate();
            }
            return new Registered(invocationId, taskId, inputHash, snapshotHash);
        });
    }

    /** Read the durable ledger before recovery; never infer completion from a process exit code. */
    public InvocationView inspectRuleCatalog(PgMigrationRunStore.Ticket ticket, UUID invocationId,
                                             String expectedSnapshotHash) throws SQLException {
        require(invocationId != null, "MIG_INVOCATION_INVALID", "Invocation ID is required");
        return transaction(connection -> {
            activeContext(connection, ticket);
            Invocation invocation = invocation(connection, ticket, invocationId);
            require(expectedSnapshotHash == null || expectedSnapshotHash.equals(invocation.snapshotHash),
                    "MIG_INVOCATION_CONFLICT", "Bridge snapshot differs from registered invocation");
            require(Set.of("REGISTERED", "DISPATCHED", "SUCCEEDED").contains(invocation.status),
                    "MIG_INVOCATION_TERMINAL", "Invocation is terminal or needs separate recovery handling");
            if ("SUCCEEDED".equals(invocation.status)) {
                Result stored = result(connection, invocationId); // Hash is checked before returning a reference.
                return new InvocationView(invocationId, invocation.taskId, invocation.status,
                        invocation.inputHash, invocation.snapshotHash, stored.artifactId, stored.contentHash);
            }
            return new InvocationView(invocationId, invocation.taskId, invocation.status,
                    invocation.inputHash, invocation.snapshotHash, null, null);
        });
    }

    /** Dispatch is committed before computation; a crash can replay the same pure catalog lookup. */
    public Result executeRuleCatalog(PgMigrationRunStore.Ticket ticket, UUID invocationId) throws SQLException {
        return executeRuleCatalog(ticket, invocationId, null);
    }

    /** A bridge caller supplies the registered catalog snapshot identity; the ledger remains authoritative. */
    public Result executeRuleCatalog(PgMigrationRunStore.Ticket ticket, UUID invocationId, String expectedSnapshotHash) throws SQLException {
        InvocationView dispatched = dispatchRuleCatalog(ticket, invocationId, expectedSnapshotHash);
        if ("SUCCEEDED".equals(dispatched.status)) return loadResult(invocationId);
        return completeRuleCatalog(ticket, invocationId, expectedSnapshotHash);
    }

    /** Separate dispatch commit exposes a real crash window without changing the result transaction. */
    public InvocationView dispatchRuleCatalog(PgMigrationRunStore.Ticket ticket, UUID invocationId,
                                              String expectedSnapshotHash) throws SQLException {
        require(invocationId != null, "MIG_INVOCATION_INVALID", "Invocation ID is required");
        return transaction(connection -> {
            activeContext(connection, ticket);
            Invocation invocation = invocation(connection, ticket, invocationId);
            require(expectedSnapshotHash == null || expectedSnapshotHash.equals(invocation.snapshotHash),
                    "MIG_INVOCATION_CONFLICT", "Bridge snapshot differs from registered invocation");
            if ("SUCCEEDED".equals(invocation.status)) {
                Result stored = result(connection, invocationId);
                return new InvocationView(invocationId, invocation.taskId, invocation.status,
                        invocation.inputHash, invocation.snapshotHash, stored.artifactId, stored.contentHash);
            }
            require("REGISTERED".equals(invocation.status) || "DISPATCHED".equals(invocation.status),
                    "MIG_INVOCATION_TERMINAL", "Invocation cannot be dispatched");
            PgMigrationRuntimeStore.ensureTime(connection, ticket.runId());
            int attempt;
            try (var query = connection.prepareStatement("SELECT attempts FROM archlens.migration_task WHERE task_id=?")) {
                query.setObject(1, invocation.taskId);
                try (var rows = query.executeQuery()) { rows.next(); attempt = rows.getInt(1) + 1; }
            }
            require(attempt <= 3, "MIG_TOOL_RETRY_BUDGET", "At most three dispatch attempts per invocation");
            String reservation = invocationId + ":1";
            PgMigrationRuntimeStore.reserve(connection, ticket.runId(), reservation, "TOOL");
            PgMigrationRuntimeStore.settle(connection, ticket.runId(), reservation, "TOOL");
            try (var update = connection.prepareStatement("UPDATE archlens.tool_invocation SET status='DISPATCHED' WHERE invocation_id=?")) {
                update.setObject(1, invocationId); update.executeUpdate();
            }
            try (var update = connection.prepareStatement("UPDATE archlens.migration_task SET status='RUNNING',attempts=attempts+1,updated_at=clock_timestamp() WHERE task_id=?")) {
                update.setObject(1, invocation.taskId); update.executeUpdate();
            }
            return new InvocationView(invocationId, invocation.taskId, "DISPATCHED",
                    invocation.inputHash, invocation.snapshotHash, null, null);
        });
    }

    /** Publish only after a live epoch checks the immutable catalog snapshot and result hash. */
    public Result completeRuleCatalog(PgMigrationRunStore.Ticket ticket, UUID invocationId,
                                      String expectedSnapshotHash) throws SQLException {
        String content = catalogContent();
        require(content.getBytes(StandardCharsets.UTF_8).length <= 65_536,
                "MIG_TOOL_RESULT_LIMIT", "Rule catalog exceeds the bounded result limit");
        return transaction(connection -> {
            activeContext(connection, ticket); // A cancelled, expired or replaced epoch cannot publish a result.
            Invocation invocation = invocation(connection, ticket, invocationId);
            require(expectedSnapshotHash == null || expectedSnapshotHash.equals(invocation.snapshotHash),
                    "MIG_INVOCATION_CONFLICT", "Bridge snapshot differs from registered invocation");
            if ("SUCCEEDED".equals(invocation.status)) return result(connection, invocationId);
            require("DISPATCHED".equals(invocation.status), "MIG_INVOCATION_TERMINAL", "Invocation is not dispatched");
            require(invocation.snapshotHash.equals(Json.sha256(content.getBytes(StandardCharsets.UTF_8))),
                    "MIG_CATALOG_CHANGED", "Catalog changed after invocation registration");
            UUID artifactId = UUID.randomUUID();
            try (var insert = connection.prepareStatement("INSERT INTO archlens.migration_artifact(artifact_id,run_id,revision,artifact_kind,schema_version,content_hash,storage_ref,producer_ref) VALUES(?,?,?,'TOOL_RESULT',?,?,?,?)")) {
                insert.setObject(1, artifactId); insert.setObject(2, ticket.runId());
                insert.setInt(3, ticket.revision()); insert.setString(4, "archlens.rule-catalog.v1");
                insert.setString(5, invocation.snapshotHash); insert.setString(6, "pg:tool-result:" + artifactId);
                insert.setString(7, invocationId.toString()); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO archlens.tool_result_payload(artifact_id,content_utf8) VALUES(?,?)")) {
                insert.setObject(1, artifactId); insert.setString(2, content); insert.executeUpdate();
            }
            try (var update = connection.prepareStatement("UPDATE archlens.tool_invocation SET status='SUCCEEDED',result_artifact_id=?,finished_at=clock_timestamp() WHERE invocation_id=?")) {
                update.setObject(1, artifactId); update.setObject(2, invocationId); update.executeUpdate();
            }
            try (var update = connection.prepareStatement("UPDATE archlens.migration_task SET status='SUCCEEDED',result_artifact_id=?,updated_at=clock_timestamp() WHERE task_id=?")) {
                update.setObject(1, artifactId); update.setObject(2, invocation.taskId); update.executeUpdate();
            }
            return new Result(invocationId, artifactId, invocation.snapshotHash, content);
        });
    }

    /** Historical results remain readable after cancellation, but dispatch always requires a live ticket. */
    public Result loadResult(UUID invocationId) throws SQLException {
        require(invocationId != null, "MIG_INVOCATION_INVALID", "Invocation ID is required");
        try (var connection = config.connect()) { return result(connection, invocationId); }
    }

    private static String catalogContent() {
        return Json.canonical(Map.of("ruleCatalogVersion", RuleCatalog.VERSION, "rules", RuleCatalog.all()));
    }
    public static String catalogHash() { return Json.sha256(catalogContent().getBytes(StandardCharsets.UTF_8)); }

    private record Context(boolean allowed, int maxToolCalls) {}
    private Context activeContext(Connection connection, PgMigrationRunStore.Ticket ticket) throws SQLException {
        PgMigrationRuntimeStore.active(connection, ticket);
        try (var query = connection.prepareStatement("SELECT (request_json #> '{policyRefs,allowedTools}') @> '[\"list_rules\"]'::jsonb,(request_json #>> '{budget,maxToolCalls}')::integer FROM archlens.migration_run WHERE run_id=?")) {
            query.setObject(1, ticket.runId());
            try (var rows = query.executeQuery()) { rows.next(); return new Context(rows.getBoolean(1), rows.getInt(2)); }
        }
    }

    private record Invocation(String status, String inputHash, String snapshotHash, UUID taskId) {}
    private Invocation invocation(Connection connection, PgMigrationRunStore.Ticket ticket, UUID invocationId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT status,input_hash,snapshot_hash,task_id FROM archlens.tool_invocation WHERE invocation_id=? AND run_id=? AND revision=? AND tool_id=? AND tool_version=?")) {
            query.setObject(1, invocationId); query.setObject(2, ticket.runId());
            query.setInt(3, ticket.revision()); query.setString(4, TOOL_ID); query.setString(5, RuleCatalog.VERSION);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "MIG_INVOCATION_NOT_FOUND", "Invocation is outside the run or tool version");
                return new Invocation(rows.getString(1), rows.getString(2), rows.getString(3), rows.getObject(4, UUID.class));
            }
        }
    }

    private Result result(Connection connection, UUID invocationId) throws SQLException {
        try (var query = connection.prepareStatement("SELECT a.artifact_id,a.content_hash,p.content_utf8 FROM archlens.tool_invocation i JOIN archlens.migration_artifact a ON a.artifact_id=i.result_artifact_id JOIN archlens.tool_result_payload p ON p.artifact_id=a.artifact_id WHERE i.invocation_id=? AND i.status='SUCCEEDED'")) {
            query.setObject(1, invocationId);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "MIG_RESULT_NOT_READY", "Invocation has no persisted result");
                String content = rows.getString(3), hash = rows.getString(2);
                require(hash.equals(Json.sha256(content.getBytes(StandardCharsets.UTF_8))),
                        "MIG_RESULT_DRIFT", "Persisted tool result hash differs");
                return new Result(invocationId, rows.getObject(1, UUID.class), hash, content);
            }
        }
    }

    @FunctionalInterface private interface Work<T> { T apply(Connection connection) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        try (var connection = config.connect()) {
            connection.setAutoCommit(false);
            try { T value = work.apply(connection); connection.commit(); return value; }
            catch (SQLException | RuntimeException failure) { connection.rollback(); throw failure; }
        }
    }
}
