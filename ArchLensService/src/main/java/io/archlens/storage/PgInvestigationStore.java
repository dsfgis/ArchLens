package io.archlens.storage;

import io.archlens.contract.*;
import io.archlens.contract.Model.GraphDocument;
import io.archlens.investigation.*;
import io.archlens.agent.AgentContracts;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import static io.archlens.contract.ContractException.require;

/** PG owns immutable report bodies. Every run mutation is fenced by epoch/state/lease. */
public final class PgInvestigationStore {
    private final StorageConfig config;
    public PgInvestigationStore(StorageConfig config) { this.config=config; }
    public record Ticket(UUID caseId,UUID runId,int revision,long epoch) {}
    public record StoredRun(UUID caseId,UUID runId,int revision,String state,String projectionState,String reportHash,
                            String reportJson,String graphHash,String graphJson,String checkpointJson,boolean latestRevision) {}
    /** 网页只枚举 Agent 运行；列表不加载源码、完整报告或凭据，分页保持稳定顺序。 */
    public record RunSummary(UUID caseId,UUID runId,int revision,boolean latestRevision,String state,String reportStatus,
                             String objective,String projectionState,String errorCode,String createdAt,String finishedAt,boolean leaseExpired) {}
    public List<RunSummary> listAgentRuns(UUID caseId,int offset) throws SQLException {
        require(offset>=0&&offset<=100000,"INVALID_PAGE","Invalid page offset");
        String sql="SELECT r.case_id,r.run_id,r.revision,r.revision=c.latest_revision,r.state,r.report_json->>'status',r.request_json->>'objective',r.projection_state,r.error_code,r.created_at,r.finished_at,(r.state='RUNNING' AND r.lease_until<=clock_timestamp()) FROM archlens.investigation_run r JOIN archlens.investigation_case c ON c.case_id=r.case_id WHERE r.request_json->>'schemaVersion' IN ('archlens.agent.v1','archlens.agent.v2')"+(caseId==null?"":" AND r.case_id=?")+" ORDER BY r.created_at DESC,r.run_id LIMIT 25 OFFSET ?";
        try(var c=config.connect();var p=c.prepareStatement(sql)) {
            int index=1;if(caseId!=null)p.setObject(index++,caseId);p.setInt(index,offset);
            try(var r=p.executeQuery()) {
                List<RunSummary> rows=new ArrayList<>();
                while(r.next())rows.add(new RunSummary(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3),r.getBoolean(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getBoolean(12)));
                return rows;
            }
        }
    }
    public String check() throws SQLException {
        try(var c=config.connect()) { c.setReadOnly(true);try(var s=c.createStatement();var r=s.executeQuery("SELECT current_setting('server_version')")) { r.next();return r.getString(1); } }
    }
    public void initialize() throws Exception {
        String sql;
        try(var stream=getClass().getResourceAsStream("/db/V001__investigation_storage.sql")) {
            require(stream!=null,"MIGRATION_MISSING","Storage migration missing");sql=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        String checksum=Json.sha256(sql.getBytes(StandardCharsets.UTF_8));
        try(var c=config.connect()) {
            c.setAutoCommit(false);
            try(var s=c.createStatement()) {
                s.execute("SELECT pg_advisory_xact_lock(681734091)");
                boolean schemaExists;
                try(var r=s.executeQuery("SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='archlens')")) {r.next();schemaExists=r.getBoolean(1);}
                for(String statement:sql.split(";")) if(!statement.isBlank()
                        && !(schemaExists && statement.stripLeading().startsWith("CREATE SCHEMA"))) s.execute(statement);
                try(var q=c.prepareStatement("SELECT checksum FROM archlens.schema_version WHERE version=1");var r=q.executeQuery()) {
                    if(r.next()) require(checksum.equals(r.getString(1)),"MIGRATION_DRIFT","Installed migration differs from this build");
                    else try(var p=c.prepareStatement("INSERT INTO archlens.schema_version(version,checksum) VALUES(1,?)")) {p.setString(1,checksum);p.executeUpdate();}
                }
                c.commit();
            } catch(Exception e) { c.rollback();throw e; }
        }
    }
    public Ticket begin(UUID existingCase,InvestigationRequest request) throws SQLException {
        return beginBody(existingCase,request,null);
    }
    /** 澄清只接续最新修订，条件更新与创建运行在同一事务中，阻止并发重复消费回答。 */
    public Ticket beginAgent(UUID existingCase,AgentContracts.Request request,Integer expectedRevision) throws SQLException {
        require((existingCase==null)==(expectedRevision==null),"AGENT_REVISION_REQUIRED","Agent resume requires an expected revision");
        return beginBody(existingCase,request,expectedRevision);
    }
    private Ticket beginBody(UUID existingCase,Object request,Integer expectedRevision) throws SQLException {
        UUID caseId=existingCase==null?UUID.randomUUID():existingCase,runId=UUID.randomUUID();
        try(var c=config.connect()) {
            c.setAutoCommit(false);
            try {
                if(existingCase==null) try(var p=c.prepareStatement("INSERT INTO archlens.investigation_case(case_id) VALUES(?)")) {p.setObject(1,caseId);p.executeUpdate();}
                int revision;
                try(var p=c.prepareStatement("UPDATE archlens.investigation_case SET latest_revision=latest_revision+1 WHERE case_id=?"+(expectedRevision==null?"":" AND latest_revision=?")+" RETURNING latest_revision")) {
                    p.setObject(1,caseId);if(expectedRevision!=null)p.setInt(2,expectedRevision);
                    try(var r=p.executeQuery()) {require(r.next(),expectedRevision==null?"CASE_NOT_FOUND":"STALE_ANSWERS","Case absent or revision changed");revision=r.getInt(1);}
                }
                try(var p=c.prepareStatement("INSERT INTO archlens.investigation_run(run_id,case_id,revision,state,lease_until,request_hash,request_json) VALUES(?,?,?,'RUNNING',clock_timestamp()+interval '180 seconds',?,?::jsonb)")) {
                    p.setObject(1,runId);p.setObject(2,caseId);p.setInt(3,revision);p.setString(4,Json.hash(request));p.setString(5,Json.canonical(request));p.executeUpdate();
                }
                c.commit();return new Ticket(caseId,runId,revision,1);
            } catch(SQLException|RuntimeException e) {c.rollback();throw e;}
        }
    }
    public boolean active(Ticket ticket) throws SQLException {
        try(var c=config.connect();var p=c.prepareStatement("SELECT 1 FROM archlens.investigation_run WHERE run_id=? AND epoch=? AND state='RUNNING' AND lease_until>clock_timestamp()")) {
            p.setObject(1,ticket.runId());p.setLong(2,ticket.epoch());try(var r=p.executeQuery()) {return r.next();}
        }
    }
    public void finish(Ticket ticket,InvestigationReport report) throws SQLException {
        GraphDocument graph=report.columnAnalysis()==null?null:new FactGraph(report.columnAnalysis().graph()).document();
        finishBody(ticket,report,Json.hash(report.request()),report.status().name(),graph);
    }
    /** 复用不可变报告及租约围栏；澄清是报告内状态，数据库仍保存为 PARTIAL。 */
    public void finishAgent(Ticket ticket,AgentContracts.Report report) throws SQLException {
        require(ticket.revision()==report.revision(),"STALE_RUN","Agent revision differs from stored run");
        var analysis=report.investigation();
        GraphDocument graph=analysis==null||analysis.columnAnalysis()==null?null:new FactGraph(analysis.columnAnalysis().graph()).document();
        finishBody(ticket,report,report.requestHash(),report.status()==AgentContracts.Status.CANCELLED?"CANCELLED":"PARTIAL",graph);
    }
    private void finishBody(Ticket ticket,Object report,String requestHash,String state,GraphDocument graph) throws SQLException {
        try(var c=config.connect();var p=c.prepareStatement("UPDATE archlens.investigation_run SET state=?,report_hash=?,report_json=?::jsonb,graph_hash=?,graph_json=?::jsonb,projection_state=?,finished_at=clock_timestamp() WHERE run_id=? AND epoch=? AND state='RUNNING' AND lease_until>clock_timestamp() AND request_hash=?")) {
            p.setString(1,state);p.setString(2,Json.hash(report));p.setString(3,Json.canonical(report));
            p.setString(4,graph==null?null:Json.hash(graph));p.setString(5,graph==null?null:Json.canonical(graph));
            p.setString(6,graph==null?"NOT_APPLICABLE":"PENDING");p.setObject(7,ticket.runId());p.setLong(8,ticket.epoch());p.setString(9,requestHash);
            require(p.executeUpdate()==1,"STALE_RUN","Run cancelled, expired, finalized or request changed");
        }
    }
    public void checkpoint(Ticket ticket,List<InvestigationReport.Source> sources) throws SQLException {
        try(var c=config.connect();var p=c.prepareStatement("UPDATE archlens.investigation_run SET checkpoint_hash=?,checkpoint_json=?::jsonb WHERE run_id=? AND epoch=? AND state='RUNNING' AND lease_until>clock_timestamp()")) {
            p.setString(1,Json.hash(sources));p.setString(2,Json.canonical(sources));p.setObject(3,ticket.runId());p.setLong(4,ticket.epoch());
            require(p.executeUpdate()==1,"STALE_RUN","Cannot checkpoint a cancelled or expired run");
        }
    }
    public void fail(Ticket ticket) throws SQLException {
        try(var c=config.connect();var p=c.prepareStatement("UPDATE archlens.investigation_run SET state='FAILED',error_code='INVESTIGATION_FAILED',epoch=epoch+1,finished_at=clock_timestamp() WHERE run_id=? AND epoch=? AND state='RUNNING'")) {
            p.setObject(1,ticket.runId());p.setLong(2,ticket.epoch());p.executeUpdate();
        }
    }
    public boolean cancel(UUID runId) throws SQLException {
        // Fence immediately. Any previously checkpointed report is retained, late results cannot be published.
        try(var c=config.connect();var p=c.prepareStatement("UPDATE archlens.investigation_run SET state='CANCELLED',epoch=epoch+1,finished_at=clock_timestamp() WHERE run_id=? AND state='RUNNING'")) {
            p.setObject(1,runId);return p.executeUpdate()==1;
        }
    }
    public int expire() throws SQLException {
        try(var c=config.connect();var p=c.prepareStatement("UPDATE archlens.investigation_run SET state='FAILED',epoch=epoch+1,error_code='LEASE_EXPIRED',finished_at=clock_timestamp() WHERE state='RUNNING' AND lease_until<=clock_timestamp()")) {return p.executeUpdate();}
    }
    public StoredRun load(UUID runId) throws SQLException {
        try(var c=config.connect();var p=c.prepareStatement("SELECT r.case_id,r.revision,r.state,r.projection_state,r.report_hash,r.report_json::text,r.graph_hash,r.graph_json::text,r.checkpoint_json::text,r.checkpoint_hash,(r.revision=c.latest_revision) FROM archlens.investigation_run r JOIN archlens.investigation_case c ON r.case_id=c.case_id WHERE r.run_id=?")) {
            p.setObject(1,runId);try(var r=p.executeQuery()) {
                require(r.next(),"RUN_NOT_FOUND","Run does not exist");
                StoredRun result=new StoredRun(r.getObject(1,UUID.class),runId,r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getBoolean(11));
                try {
                    if(result.reportJson()!=null) require(Json.hash(Json.MAPPER.readTree(result.reportJson())).equals(result.reportHash()),"STORAGE_CORRUPT","Report checksum mismatch");
                    if(result.graphJson()!=null) require(Json.hash(Json.MAPPER.readTree(result.graphJson())).equals(result.graphHash()),"STORAGE_CORRUPT","Graph checksum mismatch");
                    if(result.checkpointJson()!=null) require(Json.hash(Json.MAPPER.readTree(result.checkpointJson())).equals(r.getString(10)),"STORAGE_CORRUPT","Checkpoint checksum mismatch");
                } catch(IOException e) {throw new ContractException("STORAGE_CORRUPT","Stored JSON invalid");}
                return result;
            }
        }
    }
    public List<UUID> pending() throws SQLException {
        try(var c=config.connect();var p=c.prepareStatement("SELECT run_id FROM archlens.investigation_run WHERE projection_state='PENDING' ORDER BY created_at LIMIT 100");var r=p.executeQuery()) {
            List<UUID> ids=new ArrayList<>();while(r.next())ids.add(r.getObject(1,UUID.class));return ids;
        }
    }
    public void projected(UUID runId,String graphHash) throws SQLException {
        try(var c=config.connect();var p=c.prepareStatement("UPDATE archlens.investigation_run SET projection_state='READY',error_code=NULL WHERE run_id=? AND graph_hash=? AND projection_state IN ('PENDING','READY')")) {
            p.setObject(1,runId);p.setString(2,graphHash);require(p.executeUpdate()==1,"STALE_PROJECTION","Projection scope no longer matches");
        }
    }
}
