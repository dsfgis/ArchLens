package io.archlens.storage;

import io.archlens.contract.Json;
import io.archlens.migration.MigrationRequest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import static io.archlens.contract.ContractException.require;

/** PG-owned lifecycle slice for new migration runs. All transitions use database time and immutable request hashes. */
public final class PgMigrationRunStore {
    private final StorageConfig config;

    public PgMigrationRunStore(StorageConfig config) { this.config = config; }

    public record Enqueued(UUID caseId, UUID runId, int revision, String requestHash) {}
    public record Ticket(UUID caseId, UUID runId, int revision, String requestHash,
                         String workerId, long epoch, Instant leaseUntil) {}
    public record CancellationReceipt(UUID runId, long epoch, Instant cancelledAt) {}
    public record RunView(UUID caseId, UUID runId, int revision, String requestHash,
                          String state, long epoch, String workerId, Instant leaseUntil,
                          Instant finishedAt, boolean latestRevision) {}

    /** A submission key is host-generated and global to this local single-user installation. */
    public Enqueued enqueue(String submissionKey, MigrationRequest request) throws SQLException {
        require(submissionKey != null && submissionKey.matches("[A-Za-z0-9._:-]{1,200}"),
                "MIG_SUBMISSION_KEY_INVALID", "Invalid submission key");
        require(request != null, "MIG_REQUEST_REQUIRED", "Migration request is required");
        String requestHash = Json.hash(request), requestJson = Json.canonical(request);
        return transaction(connection -> {
            // A key collision only adds serialization; the table's exact key remains authoritative.
            try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 781734091))")) {
                lock.setString(1, submissionKey);
                lock.execute();
            }
            try (var query = connection.prepareStatement("SELECT r.case_id,r.run_id,r.revision,s.request_hash FROM archlens.migration_submission s JOIN archlens.migration_run r ON r.run_id=s.run_id WHERE s.submission_key=?")) {
                query.setString(1, submissionKey);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        require(requestHash.equals(rows.getString(4)), "MIG_SUBMISSION_CONFLICT",
                                "Submission key is bound to another request");
                        return new Enqueued(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class),
                                rows.getInt(3), requestHash);
                    }
                }
            }
            UUID caseId = UUID.randomUUID(), runId = UUID.randomUUID();
            try (var insert = connection.prepareStatement("INSERT INTO archlens.migration_case(case_id,latest_revision) VALUES(?,1)")) {
                insert.setObject(1, caseId);
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO archlens.migration_run(run_id,case_id,revision,request_hash,request_json,orchestration_type,execution_state) VALUES(?,?,?,?,?::jsonb,'LANGGRAPH','QUEUED')")) {
                insert.setObject(1, runId);
                insert.setObject(2, caseId);
                insert.setInt(3, 1);
                insert.setString(4, requestHash);
                insert.setString(5, requestJson);
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO archlens.migration_submission(submission_key,request_hash,run_id) VALUES(?,?,?)")) {
                insert.setString(1, submissionKey);
                insert.setString(2, requestHash);
                insert.setObject(3, runId);
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO archlens.migration_event(run_id,revision,event_sequence,event_type,summary) VALUES(?,1,1,'CREATED','Run queued')")) {
                insert.setObject(1, runId);
                insert.executeUpdate();
            }
            return new Enqueued(caseId, runId, 1, requestHash);
        });
    }

    /** Exactly one claimant wins; expired RUNNING leases are taken over with a new epoch. */
    public Ticket claim(UUID runId, String workerId) throws SQLException {
        require(runId != null, "RUN_NOT_FOUND", "Run ID is required");
        require(workerId != null && workerId.matches("[A-Za-z][A-Za-z0-9._:-]{0,99}"),
                "MIG_WORKER_INVALID", "Invalid worker ID");
        return transaction(connection -> {
            Current current = lockCase(connection, runId);
            require(current.revision == current.latestRevision, "STALE_RUN", "Run is not the latest revision");
            try (var update = connection.prepareStatement("UPDATE archlens.migration_run SET execution_state='RUNNING',worker_id=?,epoch=epoch+1,lease_until=clock_timestamp()+interval '180 seconds' WHERE run_id=? AND request_hash=? AND orchestration_type='LANGGRAPH' AND (execution_state='QUEUED' OR (execution_state='RUNNING' AND lease_until<=clock_timestamp())) RETURNING epoch,lease_until")) {
                update.setString(1, workerId);
                update.setObject(2, runId);
                update.setString(3, current.requestHash);
                try (var rows = update.executeQuery()) {
                    require(rows.next(), "RUN_NOT_CLAIMABLE", "Run already leased, paused or terminal");
                    return new Ticket(current.caseId, runId, current.revision, current.requestHash,
                            workerId, rows.getLong(1), rows.getTimestamp(2).toInstant());
                }
            }
        });
    }

    /** A late heartbeat cannot revive an expired lease or a cancelled/replaced epoch. */
    public Ticket renew(Ticket ticket) throws SQLException {
        require(ticket != null, "STALE_RUN", "Worker ticket is required");
        return transaction(connection -> {
            Current current = lockCase(connection, ticket.runId());
            require(current.revision == current.latestRevision && current.revision == ticket.revision()
                    && current.caseId.equals(ticket.caseId()), "STALE_RUN", "Run revision changed");
            try (var update = connection.prepareStatement("UPDATE archlens.migration_run SET lease_until=clock_timestamp()+interval '180 seconds' WHERE run_id=? AND revision=? AND request_hash=? AND worker_id=? AND epoch=? AND execution_state='RUNNING' AND lease_until>clock_timestamp() RETURNING lease_until")) {
                update.setObject(1, ticket.runId());
                update.setInt(2, ticket.revision());
                update.setString(3, ticket.requestHash());
                update.setString(4, ticket.workerId());
                update.setLong(5, ticket.epoch());
                try (var rows = update.executeQuery()) {
                    require(rows.next(), "STALE_RUN", "Worker lease expired or was fenced");
                    return new Ticket(ticket.caseId(), ticket.runId(), ticket.revision(), ticket.requestHash(),
                            ticket.workerId(), ticket.epoch(), rows.getTimestamp(1).toInstant());
                }
            }
        });
    }

    /** Cancel invalidates every worker ticket and returns the same receipt on repeated calls. */
    public CancellationReceipt cancel(UUID runId) throws SQLException {
        require(runId != null, "RUN_NOT_FOUND", "Run ID is required");
        return transaction(connection -> {
            Current current = lockCase(connection, runId);
            try (var update = connection.prepareStatement("UPDATE archlens.migration_run SET execution_state='CANCELLED',epoch=epoch+1,worker_id=NULL,lease_until=NULL,finished_at=clock_timestamp() WHERE run_id=? AND revision=(SELECT latest_revision FROM archlens.migration_case WHERE case_id=?) AND execution_state IN ('QUEUED','RUNNING','NEEDS_INPUT','WAITING_EXTERNAL') RETURNING epoch,finished_at")) {
                update.setObject(1, runId);
                update.setObject(2, current.caseId);
                try (var rows = update.executeQuery()) {
                    if (rows.next()) {
                        CancellationReceipt receipt = new CancellationReceipt(runId, rows.getLong(1), rows.getTimestamp(2).toInstant());
                        appendCancellationEvent(connection, runId, current.revision);
                        return receipt;
                    }
                }
            }
            try (var query = connection.prepareStatement("SELECT execution_state,epoch,finished_at FROM archlens.migration_run WHERE run_id=?")) {
                query.setObject(1, runId);
                try (var rows = query.executeQuery()) {
                    require(rows.next(), "RUN_NOT_FOUND", "Run does not exist");
                    if ("CANCELLED".equals(rows.getString(1)))
                        return new CancellationReceipt(runId, rows.getLong(2), rows.getTimestamp(3).toInstant());
                    require(current.revision == current.latestRevision, "STALE_RUN", "Run is not the latest revision");
                    throw new io.archlens.contract.ContractException("RUN_TERMINAL", "Only a cancelled run has a repeatable cancellation receipt");
                }
            }
        });
    }

    public boolean active(Ticket ticket) throws SQLException {
        if (ticket == null) return false;
        try (var connection = config.connect();
             var query = connection.prepareStatement("SELECT 1 FROM archlens.migration_run r JOIN archlens.migration_case c ON c.case_id=r.case_id WHERE r.run_id=? AND r.case_id=? AND r.revision=? AND r.revision=c.latest_revision AND r.request_hash=? AND r.worker_id=? AND r.epoch=? AND r.execution_state='RUNNING' AND r.lease_until>clock_timestamp()")) {
            query.setObject(1, ticket.runId());
            query.setObject(2, ticket.caseId());
            query.setInt(3, ticket.revision());
            query.setString(4, ticket.requestHash());
            query.setString(5, ticket.workerId());
            query.setLong(6, ticket.epoch());
            try (var rows = query.executeQuery()) { return rows.next(); }
        }
    }

    public RunView load(UUID runId) throws SQLException {
        require(runId != null, "RUN_NOT_FOUND", "Run ID is required");
        try (var connection = config.connect();
             var query = connection.prepareStatement("SELECT r.case_id,r.revision,r.request_hash,r.execution_state,r.epoch,r.worker_id,r.lease_until,r.finished_at,r.revision=c.latest_revision FROM archlens.migration_run r JOIN archlens.migration_case c ON c.case_id=r.case_id WHERE r.run_id=?")) {
            query.setObject(1, runId);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "RUN_NOT_FOUND", "Run does not exist");
                Timestamp lease = rows.getTimestamp(7), finished = rows.getTimestamp(8);
                return new RunView(rows.getObject(1, UUID.class), runId, rows.getInt(2), rows.getString(3),
                        rows.getString(4), rows.getLong(5), rows.getString(6),
                        lease == null ? null : lease.toInstant(), finished == null ? null : finished.toInstant(), rows.getBoolean(9));
            }
        }
    }

    private void appendCancellationEvent(Connection connection, UUID runId, int revision) throws SQLException {
        // The run row was updated in this transaction; future event writers must use the same row lock.
        try (var insert = connection.prepareStatement("INSERT INTO archlens.migration_event(run_id,revision,event_sequence,event_type,summary) SELECT ?,?,COALESCE(MAX(event_sequence),0)+1,'CANCELLED','Run cancelled' FROM archlens.migration_event WHERE run_id=?")) {
            insert.setObject(1, runId);
            insert.setInt(2, revision);
            insert.setObject(3, runId);
            insert.executeUpdate();
        }
    }

    private Current lockCase(Connection connection, UUID runId) throws SQLException {
        // Every lifecycle writer locks the case before updating its run, making future revisions serializable.
        try (var query = connection.prepareStatement("SELECT r.case_id,r.revision,c.latest_revision,r.request_hash FROM archlens.migration_run r JOIN archlens.migration_case c ON c.case_id=r.case_id WHERE r.run_id=? FOR UPDATE OF c")) {
            query.setObject(1, runId);
            try (var rows = query.executeQuery()) {
                require(rows.next(), "RUN_NOT_FOUND", "Run does not exist");
                return new Current(rows.getObject(1, UUID.class), rows.getInt(2), rows.getInt(3), rows.getString(4));
            }
        }
    }

    private record Current(UUID caseId, int revision, int latestRevision, String requestHash) {}
    @FunctionalInterface private interface Work<T> { T apply(Connection connection) throws SQLException; }

    private <T> T transaction(Work<T> work) throws SQLException {
        try (var connection = config.connect()) {
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }
}
