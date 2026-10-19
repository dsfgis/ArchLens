package io.archlens.storage;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.archlens.contract.ContractException;
import io.archlens.contract.Json;
import io.archlens.migration.MigrationRequest;
import io.archlens.investigation.rules.RuleCatalog;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static io.archlens.contract.ContractException.require;

/** P0 runtime authority. Checkpoint storage is separate; only a fenced Java transaction can approve it. */
public final class PgMigrationRuntimeStore {
    public static final String GRAPH = "archlens.p0-rules.v1", STATE = "archlens.p0-state.v1";
    public static final String PROFILE = "P0_RULES_V1";
    private final StorageConfig config;
    public PgMigrationRuntimeStore(StorageConfig config) { this.config = config; }

    public record State(String schemaVersion, String graphVersion, UUID runId, int revision,
                        String requestHash, String snapshotHash, long epoch, long sequence,
                        String phase, UUID taskId, @JsonProperty(required=true) UUID invocationId,
                        @JsonProperty(required=true) UUID artifactId, @JsonProperty(required=true) String resultHash) {}
    public record Checkpoint(String namespace, String checkpointId, long sequence, long eventCursor,
                             String stateHash, State state) {}
    public record Budget(long toolsReserved, long toolsUsed, long modelsReserved, long modelsUsed,
                         long elapsedMillis, long maxElapsedMillis) {}
    public record Context(UUID taskId, String toolVersion, String snapshotHash, Checkpoint checkpoint, Budget budget) {}
    public record Receipt(String schemaVersion, UUID runId, String requestHash, String executionState,
                          String reason, String checkpointHash, UUID artifactId, String artifactHash) {}
    public record Sealed(String receiptHash, Receipt receipt) {}

    public PgMigrationRunStore.Enqueued enqueue(String key, MigrationRequest request) throws SQLException {
        require(request.policyRefs().allowedTools().equals(List.of("list_rules"))
                        && request.policyRefs().executionPolicy() == MigrationRequest.ExecutionPolicy.READ_ONLY
                        && request.policyRefs().dataPolicy() == MigrationRequest.DataPolicy.SUMMARY_ONLY,
                "MIG_P0_SCOPE", "P0 only permits the argument-free rule catalog");
        var queued = new PgMigrationRunStore(config).enqueue(key, request);
        transaction(c -> {
            lock(c, queued.runId());
            try (var s = c.prepareStatement("UPDATE archlens.migration_run SET runtime_profile=? WHERE run_id=? AND (runtime_profile=? OR (runtime_profile IS NULL AND execution_state='QUEUED'))")) {
                s.setString(1, PROFILE); s.setObject(2, queued.runId()); s.setString(3, PROFILE);
                require(s.executeUpdate() == 1, "MIG_P0_SCOPE", "Run is not available to this prototype");
            }
            return null;
        });
        return queued;
    }

    /** Discover durable work; claim() remains the atomic authority under multiple local schedulers. */
    public PgMigrationRunStore.Ticket claimNext(String workerId) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (var c = config.connect(); var q = c.prepareStatement("SELECT r.run_id FROM archlens.migration_run r JOIN archlens.migration_case c ON c.case_id=r.case_id WHERE r.runtime_profile=? AND r.revision=c.latest_revision AND (r.execution_state='QUEUED' OR (r.execution_state='RUNNING' AND r.lease_until<=clock_timestamp())) ORDER BY r.created_at,r.run_id LIMIT 32")) {
            q.setString(1, PROFILE); try (var r = q.executeQuery()) { while (r.next()) ids.add(r.getObject(1, UUID.class)); }
        }
        for (UUID id : ids) {
            try { return new PgMigrationRunStore(config).claim(id, workerId); }
            catch (ContractException e) { if (!Set.of("RUN_NOT_CLAIMABLE", "STALE_RUN").contains(e.code())) throw e; }
        }
        return null;
    }

    public Context context(PgMigrationRunStore.Ticket ticket) throws SQLException {
        return transaction(c -> {
            active(c, ticket);
            Checkpoint cp = approved(c, ticket);
            return new Context(taskId(ticket.runId()), RuleCatalog.VERSION, PgMigrationToolStore.catalogHash(), cp, budget(c, ticket.runId()));
        });
    }

    public static UUID taskId(UUID runId) {
        return UUID.nameUUIDFromBytes((runId + ":p0-list-rules").getBytes(StandardCharsets.UTF_8));
    }
    public static String namespace(long epoch) { return GRAPH + "/epoch-" + epoch; }

    /** The caller supplies an expected pointer, not a new authoritative state. Read it from durable PG. */
    public Checkpoint acknowledge(PgMigrationRunStore.Ticket ticket, String checkpointId,
                                   long expectedSequence, String expectedStateHash, String stateHash) throws SQLException {
        require(checkpointId != null && checkpointId.matches("[a-f0-9-]{36}"), "MIG_CHECKPOINT_ID", "Invalid checkpoint ID");
        require(expectedSequence >= 0 && expectedSequence < 9_007_199_254_740_990L,
                "MIG_CHECKPOINT_SEQUENCE", "Invalid checkpoint sequence");
        return transaction(c -> {
            active(c, ticket);
            Checkpoint previous = approved(c, ticket);
            State state = readState(c, ticket.runId(), namespace(ticket.epoch()), checkpointId);
            validate(c, ticket, state);
            require(state.epoch() == ticket.epoch() && Json.hash(state).equals(stateHash), "MIG_CHECKPOINT_HASH", "Checkpoint identity differs");
            // A lost acknowledgement can be replayed, but cannot turn an old callback into a new pointer.
            if (previous != null && previous.checkpointId().equals(checkpointId)
                    && previous.stateHash().equals(stateHash) && previous.state().epoch() == ticket.epoch()) return previous;
            require((previous == null ? 0 : previous.sequence()) == expectedSequence
                            && Objects.equals(previous == null ? null : previous.stateHash(), expectedStateHash),
                    "MIG_CHECKPOINT_CAS", "Approved pointer advanced");
            require(state.sequence() == expectedSequence + 1, "MIG_CHECKPOINT_SEQUENCE", "Sequence must advance by one");
            require(previous == null || rank(state.phase()) >= rank(previous.state().phase()),
                    "MIG_CHECKPOINT_REGRESSION", "Checkpoint phase cannot regress");
            long cursor = event(c, ticket, "CHECKPOINT_APPROVED", stateHash);
            try (var q = c.prepareStatement("INSERT INTO archlens.checkpoint_link(run_id,revision,request_hash,snapshot_hash,epoch,graph_version,state_schema_version,checkpoint_namespace,checkpoint_id,checkpoint_sequence,event_cursor,state_hash) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(run_id) DO UPDATE SET snapshot_hash=EXCLUDED.snapshot_hash,epoch=EXCLUDED.epoch,graph_version=EXCLUDED.graph_version,state_schema_version=EXCLUDED.state_schema_version,checkpoint_namespace=EXCLUDED.checkpoint_namespace,checkpoint_id=EXCLUDED.checkpoint_id,checkpoint_sequence=EXCLUDED.checkpoint_sequence,event_cursor=EXCLUDED.event_cursor,state_hash=EXCLUDED.state_hash,updated_at=clock_timestamp()")) {
                q.setObject(1,ticket.runId()); q.setInt(2,ticket.revision()); q.setString(3,ticket.requestHash());
                q.setString(4,state.snapshotHash()); q.setLong(5,ticket.epoch()); q.setString(6,GRAPH); q.setString(7,STATE);
                q.setString(8,namespace(ticket.epoch())); q.setString(9,checkpointId); q.setLong(10,state.sequence());
                q.setLong(11,cursor); q.setString(12,stateHash); q.executeUpdate();
            }
            return new Checkpoint(namespace(ticket.epoch()), checkpointId, state.sequence(), cursor, stateHash, state);
        });
    }

    /** No completed migration claim exists in P0. Seal a fixed, immutable PARTIAL runtime receipt. */
    public Sealed seal(PgMigrationRunStore.Ticket ticket, String checkpointHash, String reason) throws SQLException {
        require(Set.of("P0_SCOPE_ONLY", "MIG_ELAPSED_BUDGET", "MIG_TOOL_BUDGET", "MIG_TOOL_RETRY_BUDGET", "MIG_MODEL_BUDGET",
                "MIG_CHECKPOINT_VERSION", "MIG_CHECKPOINT_HASH", "MIG_CATALOG_CHANGED", "MIG_RESULT_DRIFT",
                "MIG_CHECKPOINT_INVALID", "MIG_CHECKPOINT_MISSING", "MIG_WORKER_FAILURE").contains(reason),
                "MIG_SEAL_REASON", "Unsupported terminal reason");
        return transaction(c -> {
            lock(c, ticket.runId());
            try (var q = c.prepareStatement("SELECT request_hash,receipt_json::text,receipt_hash,epoch,worker_id,execution_state,case_id,revision FROM archlens.migration_run WHERE run_id=?")) {
                q.setObject(1,ticket.runId());
                try (var r = q.executeQuery()) {
                    require(r.next() && ticket.requestHash().equals(r.getString(1)) && ticket.caseId().equals(r.getObject(7,UUID.class))
                                    && ticket.revision()==r.getInt(8), "STALE_RUN", "Request changed");
                    if (r.getString(2) != null) {
                        Receipt saved = decode(r.getString(2), Receipt.class);
                        require(saved.reason().equals(reason) && Objects.equals(saved.checkpointHash(),checkpointHash)
                                        && Json.hash(saved).equals(r.getString(3)), "MIG_SEAL_CONFLICT", "Terminal receipt differs");
                        return new Sealed(r.getString(3),saved);
                    }
                }
            }
            active(c,ticket);
            Checkpoint cp = null;
            if (checkpointHash != null || "P0_SCOPE_ONLY".equals(reason)) {
                cp = approved(c,ticket);
                require(cp != null && cp.stateHash().equals(checkpointHash) && cp.state().epoch() == ticket.epoch(),
                        "MIG_SEAL_CHECKPOINT", "Seal requires current approved checkpoint");
            }
            if ("P0_SCOPE_ONLY".equals(reason)) {
                require("SUCCEEDED".equals(cp.state().phase()), "MIG_SEAL_INCOMPLETE", "Tool result must be durable");
                ensureTime(c,ticket.runId());
            }
            String terminal = reason.startsWith("MIG_CHECKPOINT") || reason.equals("MIG_RESULT_DRIFT")
                    || reason.equals("MIG_CATALOG_CHANGED") || reason.equals("MIG_WORKER_FAILURE") ? "FAILED" : "PARTIAL";
            Receipt receipt = new Receipt("archlens.p0-receipt.v1",ticket.runId(),ticket.requestHash(),terminal,
                    reason,checkpointHash,cp == null ? null : cp.state().artifactId(),cp == null ? null : cp.state().resultHash());
            String hash = Json.hash(receipt);
            try (var q = c.prepareStatement("UPDATE archlens.migration_run SET execution_state=?,receipt_json=?::jsonb,receipt_hash=?,finished_at=clock_timestamp(),epoch=epoch+1,worker_id=NULL,lease_until=NULL WHERE run_id=?")) {
                q.setString(1,terminal); q.setString(2,Json.canonical(receipt)); q.setString(3,hash); q.setObject(4,ticket.runId()); q.executeUpdate();
            }
            event(c,ticket,"RUN_SEALED",hash);
            return new Sealed(hash,receipt);
        });
    }

    public Sealed receipt(UUID runId) throws SQLException {
        try (var c=config.connect(); var q=c.prepareStatement("SELECT receipt_json::text,receipt_hash FROM archlens.migration_run WHERE run_id=?")) {
            q.setObject(1,runId); try (var r=q.executeQuery()) {
                require(r.next(),"RUN_NOT_FOUND","Run does not exist");
                if(r.getString(1)==null)return null;
                Receipt receipt=decode(r.getString(1),Receipt.class);
                require(Json.hash(receipt).equals(r.getString(2)),"MIG_RECEIPT_DRIFT","Receipt hash differs");
                return new Sealed(r.getString(2),receipt);
            }
        }
    }

    private Checkpoint approved(Connection c, PgMigrationRunStore.Ticket ticket) throws SQLException {
        try (var q=c.prepareStatement("SELECT checkpoint_namespace,checkpoint_id,checkpoint_sequence,event_cursor,state_hash,graph_version,state_schema_version,epoch,request_hash,snapshot_hash,revision FROM archlens.checkpoint_link WHERE run_id=?")) {
            q.setObject(1,ticket.runId()); try(var r=q.executeQuery()) {
                if(!r.next())return null;
                require(GRAPH.equals(r.getString(6)) && STATE.equals(r.getString(7)),"MIG_CHECKPOINT_VERSION","No implicit checkpoint version migration");
                require(r.getLong(8)<=ticket.epoch() && namespace(r.getLong(8)).equals(r.getString(1))
                                && ticket.requestHash().equals(r.getString(9)) && ticket.revision()==r.getInt(11),
                        "MIG_CHECKPOINT_INVALID","Approved checkpoint identity differs");
                State state=readState(c,ticket.runId(),r.getString(1),r.getString(2));
                require(Json.hash(state).equals(r.getString(5)) && state.sequence()==r.getLong(3)
                                && state.epoch()==r.getLong(8) && state.snapshotHash().equals(r.getString(10)),
                        "MIG_CHECKPOINT_HASH","Approved checkpoint changed");
                validate(c,ticket,state);
                return new Checkpoint(r.getString(1),r.getString(2),r.getLong(3),r.getLong(4),r.getString(5),state);
            }
        }
    }

    private State readState(Connection c, UUID runId, String ns, String id) throws SQLException {
        // Native PostgresSaver checkpoint and its export are committed in the SAME framework transaction.
        try(var q=c.prepareStatement("SELECT checkpoint->'archlens_export',checkpoint->>'id' FROM archlens_checkpoint.checkpoints WHERE thread_id=? AND checkpoint_ns=? AND checkpoint_id=?")) {
            q.setString(1,runId.toString());q.setString(2,ns);q.setString(3,id);
            try(var r=q.executeQuery()) {
                require(r.next() && r.getString(1)!=null,"MIG_CHECKPOINT_MISSING","Checkpoint export is absent");
                require(id.equals(r.getString(2)),"MIG_CHECKPOINT_INVALID","Native checkpoint ID differs");
                return decode(r.getString(1),State.class);
            }
        }
    }

    private void validate(Connection c,PgMigrationRunStore.Ticket ticket,State s) throws SQLException {
        require(STATE.equals(s.schemaVersion()) && GRAPH.equals(s.graphVersion()),"MIG_CHECKPOINT_VERSION","Unsupported state version");
        require(ticket.runId().equals(s.runId()) && ticket.revision()==s.revision() && ticket.requestHash().equals(s.requestHash())
                        && s.epoch()>0 && s.epoch()<=ticket.epoch() && s.sequence()>0 && taskId(ticket.runId()).equals(s.taskId()),
                "MIG_CHECKPOINT_INVALID","Checkpoint scope differs");
        require(PgMigrationToolStore.catalogHash().equals(s.snapshotHash()),"MIG_CATALOG_CHANGED","Rule catalog snapshot changed");
        int phase=rank(s.phase());
        require(phase>=0,"MIG_CHECKPOINT_INVALID","Invalid state phase");
        if(phase==0) {
            require(s.invocationId()==null && s.artifactId()==null && s.resultHash()==null,"MIG_CHECKPOINT_INVALID","Entry has no result");return;
        }
        require(s.invocationId()!=null,"MIG_CHECKPOINT_INVALID","Registered invocation required");
        try(var q=c.prepareStatement("SELECT i.status,i.result_artifact_id,a.content_hash,p.content_utf8 FROM archlens.tool_invocation i LEFT JOIN archlens.migration_artifact a ON a.artifact_id=i.result_artifact_id LEFT JOIN archlens.tool_result_payload p ON p.artifact_id=a.artifact_id WHERE i.invocation_id=? AND i.run_id=? AND i.revision=? AND i.task_id=? AND i.tool_id='list_rules' AND i.tool_version=? AND i.snapshot_hash=? AND i.action_key='p0.list-rules'")) {
            q.setObject(1,s.invocationId());q.setObject(2,ticket.runId());q.setInt(3,ticket.revision());q.setObject(4,s.taskId());q.setString(5,RuleCatalog.VERSION);q.setString(6,s.snapshotHash());
            try(var r=q.executeQuery()) {
                require(r.next(),"MIG_CHECKPOINT_INVALID","Invocation is outside checkpoint scope");
                if(phase==1)require(s.artifactId()==null && s.resultHash()==null,"MIG_CHECKPOINT_INVALID","Registered phase has no result");
                else {
                    require("SUCCEEDED".equals(r.getString(1)) && Objects.equals(s.artifactId(),r.getObject(2,UUID.class))
                                    && Objects.equals(s.resultHash(),r.getString(3)) && s.snapshotHash().equals(s.resultHash()) && r.getString(4)!=null,
                            "MIG_CHECKPOINT_INVALID","Result reference is not durable");
                    require(s.resultHash().equals(Json.sha256(r.getString(4).getBytes(StandardCharsets.UTF_8))),"MIG_RESULT_DRIFT","Result bytes changed");
                }
            }
        }
    }
    private static int rank(String phase) { return List.of("ENTRY","REGISTERED","SUCCEEDED").indexOf(phase == null ? "" : phase); }

    /** Lock order: Case, then fresh Run read. A joined pre-lock snapshot can miss a concurrent cancellation. */
    static void lock(Connection c,UUID runId) throws SQLException {
        require(runId!=null,"RUN_NOT_FOUND","Run ID required");
        try(var q=c.prepareStatement("SELECT c.case_id FROM archlens.migration_case c WHERE c.case_id=(SELECT case_id FROM archlens.migration_run WHERE run_id=?) FOR UPDATE")) {
            q.setObject(1,runId);try(var r=q.executeQuery()){require(r.next(),"RUN_NOT_FOUND","Run does not exist");}
        }
    }
    static void active(Connection c,PgMigrationRunStore.Ticket t) throws SQLException {
        require(t!=null && t.workerId()!=null,"STALE_RUN","Ticket required");lock(c,t.runId());
        try(var q=c.prepareStatement("SELECT 1 FROM archlens.migration_run r JOIN archlens.migration_case c ON c.case_id=r.case_id WHERE r.run_id=? AND r.case_id=? AND r.revision=? AND r.revision=c.latest_revision AND r.request_hash=? AND r.worker_id=? AND r.epoch=? AND r.execution_state='RUNNING' AND r.lease_until>clock_timestamp() FOR UPDATE OF r")) {
            q.setObject(1,t.runId());q.setObject(2,t.caseId());q.setInt(3,t.revision());q.setString(4,t.requestHash());q.setString(5,t.workerId());q.setLong(6,t.epoch());
            try(var r=q.executeQuery()){require(r.next(),"STALE_RUN","Worker lease expired or was fenced");}
        }
    }
    static void ensureTime(Connection c,UUID runId) throws SQLException {
        try(var q=c.prepareStatement("SELECT started_at IS NOT NULL AND clock_timestamp()<started_at+((request_json #>> '{budget,maxElapsedMillis}')::bigint * interval '1 millisecond') FROM archlens.migration_run WHERE run_id=?")) {
            q.setObject(1,runId);try(var r=q.executeQuery()){require(r.next() && r.getBoolean(1),"MIG_ELAPSED_BUDGET","Elapsed budget exhausted");}
        }
    }
    static void reserve(Connection c,UUID runId,String key,String resource) throws SQLException {
        require(Set.of("TOOL","MODEL").contains(resource) && key!=null && key.length()<=200,"MIG_BUDGET_INVALID","Invalid budget reservation");
        try(var q=c.prepareStatement("SELECT 1 FROM archlens.migration_budget_reservation WHERE run_id=? AND action_key=? AND resource=?")) {
            q.setObject(1,runId);q.setString(2,key);q.setString(3,resource);try(var r=q.executeQuery()){if(r.next())return;}
        }
        ensureTime(c,runId);
        try(var q=c.prepareStatement("SELECT (request_json #>> ?)::integer,(SELECT count(*) FROM archlens.migration_budget_reservation WHERE run_id=? AND resource=?) FROM archlens.migration_run WHERE run_id=?")) {
            q.setArray(1,c.createArrayOf("text",new String[]{"budget",resource.equals("TOOL")?"maxToolCalls":"maxModelCalls"}));q.setObject(2,runId);q.setString(3,resource);q.setObject(4,runId);
            try(var r=q.executeQuery()){require(r.next() && r.getLong(2)<r.getLong(1),resource.equals("TOOL")?"MIG_TOOL_BUDGET":"MIG_MODEL_BUDGET","Shared call budget exhausted");}
        }
        try(var q=c.prepareStatement("INSERT INTO archlens.migration_budget_reservation(run_id,action_key,resource,status) VALUES(?,?,?,'RESERVED')")) {
            q.setObject(1,runId);q.setString(2,key);q.setString(3,resource);q.executeUpdate();
        }
    }
    static void settle(Connection c,UUID runId,String key,String resource) throws SQLException {
        try(var q=c.prepareStatement("UPDATE archlens.migration_budget_reservation SET status='SETTLED',settled_at=COALESCE(settled_at,clock_timestamp()) WHERE run_id=? AND action_key=? AND resource=?")) {
            q.setObject(1,runId);q.setString(2,key);q.setString(3,resource);require(q.executeUpdate()==1,"MIG_BUDGET_INVALID","Reservation missing");
        }
    }
    private static Budget budget(Connection c,UUID runId) throws SQLException {
        try(var q=c.prepareStatement("SELECT count(*) FILTER(WHERE resource='TOOL' AND status='RESERVED'),count(*) FILTER(WHERE resource='TOOL' AND status='SETTLED'),count(*) FILTER(WHERE resource='MODEL' AND status='RESERVED'),count(*) FILTER(WHERE resource='MODEL' AND status='SETTLED'),GREATEST(0,(EXTRACT(EPOCH FROM clock_timestamp()-(SELECT started_at FROM archlens.migration_run WHERE run_id=?))*1000)::bigint),(SELECT (request_json #>> '{budget,maxElapsedMillis}')::bigint FROM archlens.migration_run WHERE run_id=?) FROM archlens.migration_budget_reservation WHERE run_id=?")) {
            q.setObject(1,runId);q.setObject(2,runId);q.setObject(3,runId);
            try(var r=q.executeQuery()){r.next();return new Budget(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5),r.getLong(6));}
        }
    }
    private static long event(Connection c,PgMigrationRunStore.Ticket t,String type,String hash) throws SQLException {
        try(var q=c.prepareStatement("INSERT INTO archlens.migration_event(run_id,revision,event_sequence,event_type,summary,artifact_hash) SELECT ?,?,COALESCE(MAX(event_sequence),0)+1,?,?,? FROM archlens.migration_event WHERE run_id=? RETURNING event_sequence")) {
            q.setObject(1,t.runId());q.setInt(2,t.revision());q.setString(3,type);q.setString(4,type);q.setString(5,hash);q.setObject(6,t.runId());
            try(var r=q.executeQuery()){r.next();return r.getLong(1);}
        }
    }
    private static <T>T decode(String json,Class<T> type) {
        try{return Json.MAPPER.readValue(json,type);}catch(Exception e){throw new ContractException("MIG_CHECKPOINT_INVALID","Invalid persisted runtime JSON");}
    }
    @FunctionalInterface private interface Work<T>{T apply(Connection c)throws SQLException;}
    private <T>T transaction(Work<T> work)throws SQLException {
        try(var c=config.connect()){c.setAutoCommit(false);try{T result=work.apply(c);c.commit();return result;}catch(SQLException|RuntimeException e){c.rollback();throw e;}}
    }
}
