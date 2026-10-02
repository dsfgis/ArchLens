package io.archlens;

import io.archlens.contract.Json;
import io.archlens.investigation.InvestigationRequest;
import io.archlens.migration.MigrationRequest;
import io.archlens.storage.PgInvestigationStore;
import io.archlens.storage.StorageConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in additive-schema verification runs only against an explicitly named disposable PostgreSQL database. */
@EnabledIfEnvironmentVariable(named = "ARCHLENS_MIGRATION_SCHEMA_IT", matches = "true")
class MigrationSchemaIntegrationTest {
    @Test void additiveSchemaKeepsV001AndFencesMigrationIdentities() throws Exception {
        String url = System.getenv("ARCHLENS_PG_URL");
        assertNotNull(url);
        assertTrue(url.endsWith("/archlens_migration_it"), "Use only a disposable archlens_migration_it database");
        StorageConfig config = new StorageConfig(System.getenv());
        PgInvestigationStore legacy = new PgInvestigationStore(config);
        legacy.initialize();
        legacy.initialize(); // Repeated initialization checks all checksums without reapplying DDL.

        Path db = Path.of("src/main/resources/db");
        try (var connection = config.connect();
             var query = connection.createStatement();
             var versions = query.executeQuery("SELECT version,checksum FROM archlens.schema_version ORDER BY version")) {
            List<Integer> numbers = new ArrayList<>();
            while (versions.next()) {
                int version = versions.getInt(1);
                numbers.add(version);
                String name = switch (version) {
                    case 1 -> "V001__investigation_storage.sql";
                    case 2 -> "V002__migration_runtime.sql";
                    case 3 -> "V003__migration_submission.sql";
                    case 4 -> "V004__migration_tool_result.sql";
                    default -> throw new AssertionError("Unexpected schema version: " + version);
                };
                assertEquals(Json.sha256(Files.readAllBytes(db.resolve(name))), versions.getString(2));
            }
            assertEquals(List.of(1, 2, 3, 4), numbers);
        }
        try (var connection = config.connect();
             var query = connection.createStatement();
             var names = query.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='archlens' AND tablename IN ('migration_answer','migration_artifact','migration_case','migration_event','migration_question','migration_run','migration_submission','migration_task','tool_invocation','tool_result_payload','validation_job','checkpoint_link') ORDER BY tablename")) {
            List<String> tables = new ArrayList<>();
            while (names.next()) tables.add(names.getString(1));
            assertEquals(List.of("checkpoint_link", "migration_answer", "migration_artifact", "migration_case",
                    "migration_event", "migration_question", "migration_run", "migration_submission", "migration_task",
                    "tool_invocation", "tool_result_payload", "validation_job"), tables);
        }
        try (var connection = config.connect();
             var query = connection.createStatement();
             var result = query.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='archlens_checkpoint')")) {
            assertTrue(result.next());
            assertTrue(result.getBoolean(1));
        }
        try (var connection = config.connect(); var statement = connection.createStatement();
             var rights = statement.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_namespace n, aclexplode(n.nspacl) a WHERE n.nspname='archlens_checkpoint' AND a.grantee=0 AND a.privilege_type='USAGE'), EXISTS(SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace, aclexplode(c.relacl) a WHERE n.nspname='archlens' AND c.relname='migration_run' AND a.grantee=0 AND a.privilege_type='INSERT')")) {
            // V002 grants neither checkpoint-schema usage nor migration-run writes to PUBLIC.
            assertTrue(rights.next());
            assertFalse(rights.getBoolean(1));
            assertFalse(rights.getBoolean(2));
        }

        // Old and new cases allocate revisions in separate tables; the old store remains usable after V002.
        InvestigationRequest oldRequest = Json.MAPPER.readValue(Path.of("examples/column-rename/investigation.json").toFile(), InvestigationRequest.class);
        var oldTicket = legacy.begin(null, oldRequest);
        legacy.fail(oldTicket);
        assertEquals("FAILED", legacy.load(oldTicket.runId()).state());

        MigrationRequest request = Json.MAPPER.readValue(Path.of("examples/migration/request-code-only.json").toFile(), MigrationRequest.class);
        UUID caseId = UUID.randomUUID(), runId = UUID.randomUUID();
        try (var connection = config.connect();
             var insertCase = connection.prepareStatement("INSERT INTO archlens.migration_case(case_id,latest_revision) VALUES(?,1)");
             var insertRun = connection.prepareStatement("INSERT INTO archlens.migration_run(run_id,case_id,revision,request_hash,request_json,orchestration_type,execution_state) VALUES(?,?,?,?,?::jsonb,'LANGGRAPH','QUEUED')")) {
            insertCase.setObject(1, caseId);
            insertCase.executeUpdate();
            insertRun.setObject(1, runId);
            insertRun.setObject(2, caseId);
            insertRun.setInt(3, 1);
            insertRun.setString(4, Json.hash(request));
            insertRun.setString(5, Json.canonical(request));
            insertRun.executeUpdate();
        }
        assertEquals("23505", assertThrows(SQLException.class, () -> {
            try (var connection = config.connect(); var statement = connection.prepareStatement(
                    "INSERT INTO archlens.migration_run(run_id,case_id,revision,request_hash,request_json,orchestration_type,execution_state) VALUES(?,?,?,?,?::jsonb,'LANGGRAPH','QUEUED')")) {
                statement.setObject(1, UUID.randomUUID()); statement.setObject(2, caseId); statement.setInt(3, 1);
                statement.setString(4, Json.hash(request)); statement.setString(5, Json.canonical(request)); statement.executeUpdate();
            }
        }).getSQLState());
        assertEquals("23514", assertThrows(SQLException.class, () -> {
            try (var connection = config.connect(); var statement = connection.prepareStatement(
                    "UPDATE archlens.migration_run SET execution_state='RUNNING' WHERE run_id=?")) {
                statement.setObject(1, runId); statement.executeUpdate();
            }
        }).getSQLState());
        assertEquals("23503", assertThrows(SQLException.class, () -> {
            try (var connection = config.connect(); var statement = connection.prepareStatement(
                    "INSERT INTO archlens.checkpoint_link(run_id,revision,request_hash,snapshot_hash,epoch,graph_version,state_schema_version,checkpoint_namespace,checkpoint_id,checkpoint_sequence,event_cursor,state_hash) VALUES(?,?,?, ?,1,'graph-v1','state-v1','epoch-1','checkpoint-1',0,0,?)")) {
                statement.setObject(1, runId); statement.setInt(2, 1);
                statement.setString(3, "a".repeat(64)); statement.setString(4, "b".repeat(64));
                statement.setString(5, "c".repeat(64)); statement.executeUpdate();
            }
        }).getSQLState());

        // Every additive migration rejects checksum drift independently.
        for (int version : List.of(2, 3, 4)) {
            try (var connection = config.connect();
                 var statement = connection.prepareStatement("UPDATE archlens.schema_version SET checksum=? WHERE version=?")) {
                statement.setString(1, "0".repeat(64));
                statement.setInt(2, version);
                assertEquals(1, statement.executeUpdate());
            }
            try {
                ContractTest.assertCode("MIGRATION_DRIFT", legacy::initialize);
            } finally {
                // Restore the disposable database for other opt-in integration tests in the same run.
                String name = switch (version) {
                    case 2 -> "V002__migration_runtime.sql";
                    case 3 -> "V003__migration_submission.sql";
                    default -> "V004__migration_tool_result.sql";
                };
                try (var connection = config.connect();
                     var statement = connection.prepareStatement("UPDATE archlens.schema_version SET checksum=? WHERE version=?")) {
                    statement.setString(1, Json.sha256(Files.readAllBytes(db.resolve(name))));
                    statement.setInt(2, version);
                    statement.executeUpdate();
                }
            }
        }
    }
}
