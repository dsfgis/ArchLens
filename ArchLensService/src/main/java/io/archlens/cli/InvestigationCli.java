package io.archlens.cli;

import io.archlens.contract.*;
import io.archlens.investigation.*;
import io.archlens.investigation.rules.RuleCatalog;
import io.archlens.parser.SourceText;
import io.archlens.storage.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static io.archlens.contract.ContractException.require;

public final class InvestigationCli {
    private InvestigationCli() {}
    public static int run(String[] args,PrintStream out,PrintStream err) {
        try {
            if(args.length>0&&args[0].startsWith("agent-"))return AgentCli.run(args,out,err);
            // 规则清单来自打包时固定的本地注册表，便于核对支持版本和官方依据。
            if(args.length==1 && args[0].equals("rules")) {
                out.println(Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(RuleCatalog.all()));return 0;
            }
            if(args.length==3 && args[0].equals("investigate")) {
                var path=Path.of(args[1]).toRealPath(); var request=read(path);
                var report=new InvestigationEngine().investigate(path.getParent(),request,()->false);
                write(Path.of(args[2]),report);out.println(report.status()+": "+report.sources().size()+" source(s); "+report.findings().stream().filter(f->f.rule()!=null).count()+" rule finding(s); "+report.coverageGaps().size()+" coverage gap(s)");return 0;
            }
            Set<String> operations=Set.of("storage-check","storage-init","investigate-store","run-export","run-status","run-cancel","run-expire","projection-retry");
            require(args.length>0 && operations.contains(args[0]),"USAGE","Unknown command");
            int expected=switch(args[0]) {case "investigate-store","run-export"->3;case "run-status","run-cancel","projection-retry"->2;default->1;};
            require(args.length==expected,"USAGE","Invalid argument count");
            var config=new StorageConfig(System.getenv());var store=new PgInvestigationStore(config);
            switch(args[0]) {
                case "storage-check" -> {out.println("PostgreSQL: "+store.check());try(var neo=new Neo4jProjection(config)) {out.println("Neo4j: "+neo.check());}}
                case "storage-init" -> {store.initialize();try(var neo=new Neo4jProjection(config)) {neo.initialize();}out.println("ArchLens storage initialized");}
                case "investigate-store" -> {
                    Path path=Path.of(args[1]).toRealPath();var request=read(path);
                    var ticket=store.begin(args[2].equals("new")?null:UUID.fromString(args[2]),request);
                    out.println("RUNNING caseId="+ticket.caseId()+" runId="+ticket.runId()+" revision="+ticket.revision());out.flush();
                    try {
                        var report=new InvestigationEngine().investigate(path.getParent(),request,()->{
                            try{return !store.active(ticket);}catch(java.sql.SQLException e){throw new ContractException("STORAGE_UNAVAILABLE","Cannot verify run lease");}
                        },sources->{
                            try{store.checkpoint(ticket,sources);}catch(java.sql.SQLException e){throw new ContractException("STORAGE_UNAVAILABLE","Cannot save checkpoint");}
                        });
                        store.finish(ticket,report);
                    } catch(Exception e) {try{store.fail(ticket);}catch(Exception ignored){}throw e;}
                    var run=store.load(ticket.runId());
                    if(run.graphJson()!=null) try(var neo=new Neo4jProjection(config)) {neo.project(run);store.projected(run.runId(),run.graphHash());}
                    catch(Exception e) {err.println("PROJECTION_PENDING: PG report retained; use projection-retry "+ticket.runId());return 3;}
                    out.println("SEALED "+Json.canonical(Map.of("caseId",ticket.caseId(),"runId",ticket.runId(),"revision",ticket.revision(),"state",run.state(),"projectionState",store.load(ticket.runId()).projectionState())));
                }
                case "run-export" -> {
                    var run=store.load(UUID.fromString(args[1]));require(run.reportJson()!=null || run.checkpointJson()!=null,"NO_REPORT","Run has no report or checkpoint");
                    if(run.reportJson()!=null) write(Path.of(args[2]),Json.MAPPER.readTree(run.reportJson()));
                    else write(Path.of(args[2]),Map.of("runId",run.runId(),"state",run.state(),"quality","INCOMPLETE_CHECKPOINT","sources",Json.MAPPER.readTree(run.checkpointJson())));
                    out.println("Exported run="+run.runId()+" latestRevision="+run.latestRevision()+" state="+run.state());
                }
                case "run-status" -> {var run=store.load(UUID.fromString(args[1]));out.println(Json.canonical(Map.of("caseId",run.caseId(),"runId",run.runId(),"revision",run.revision(),"latestRevision",run.latestRevision(),"state",run.state(),"projectionState",run.projectionState())));}
                case "run-cancel" -> {boolean changed=store.cancel(UUID.fromString(args[1]));out.println(changed?"CANCELLED":"NOT_RUNNING");}
                case "run-expire" -> out.println("Expired runs: "+store.expire());
                case "projection-retry" -> {
                    var ids=args[1].equals("pending")?store.pending():List.of(UUID.fromString(args[1]));
                    try(var neo=new Neo4jProjection(config)) {for(UUID id:ids) {var run=store.load(id);neo.project(run);store.projected(id,run.graphHash());out.println("Projection READY "+id);}}
                }
                default -> throw new IllegalStateException();
            }
            return 0;
        } catch(Exception e) {
            Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();
            String code=e instanceof ContractException c?c.code():cause instanceof ContractException c?c.code():
                    e instanceof FileAlreadyExistsException?"OUTPUT_EXISTS":e instanceof java.sql.SQLException s?sqlCode(s):"INVESTIGATION_FAILED";
            err.println(code+": operation failed; check contract, access, configuration and service availability. No raw server errors are logged.");return 2;
        }
    }
    private static String sqlCode(java.sql.SQLException e) {
        String state=e.getSQLState();
        if(state==null || !state.matches("[A-Z0-9]{5}")) return "STORAGE_FAILED";
        return "STORAGE_SQLSTATE_"+state;
    }
    private static InvestigationRequest read(Path path) throws IOException {
        return Json.MAPPER.readValue(SourceText.read(path.getParent(),path.getFileName().toString()).text(),InvestigationRequest.class);
    }
    private static void write(Path output,Object body) throws IOException {
        Path target=output.toAbsolutePath().normalize();Files.createDirectories(target.getParent());
        Files.write(target,Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(body),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
    }
}
