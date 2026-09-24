package io.archlens.cli;

import io.archlens.agent.*;
import io.archlens.agent.AgentContracts.*;
import io.archlens.contract.*;
import io.archlens.storage.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static io.archlens.contract.ContractException.require;

/** 本机网页的 JSON 行协议。根目录只交给采集器，绝不进入模型 Request。 */
public final class WebAgentCli {
    public record Input(String action,String sourceRoot,Request request,UUID runId,Answers answers,UUID caseId,Integer offset) {}
    public static void main(String[] args) {
        try {
            byte[] bytes=System.in.readNBytes(262145);
            require(bytes.length<=262144,"INPUT_LIMIT","Web input too large");
            var input=Json.MAPPER.readValue(bytes,Input.class);
            var config=new StorageConfig(System.getenv());
            String key=System.getenv("DEEPSEEK_API_KEY");
            AgentModel model=key==null||key.isBlank()?null:new DeepSeekAgentModel(key,System.getenv("DEEPSEEK_MODEL"));
            execute(input,new PgInvestigationStore(config),model,System.out);
        } catch(Exception e) {
            Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();
            String code=e instanceof ContractException c?c.code():cause instanceof ContractException c?c.code():
                    e instanceof java.sql.SQLException?"STORAGE_UNAVAILABLE":e instanceof com.fasterxml.jackson.core.JacksonException?"INVALID_REQUEST":"WEB_BACKEND_FAILED";
            System.out.println(Json.canonical(Map.of("event","error","error",code)));
            System.exit(2);
        }
    }
    public static void execute(Input in,PgInvestigationStore store,AgentModel model,PrintStream out)throws Exception {
        require(in!=null&&in.action()!=null,"INVALID_REQUEST","Missing action");
        switch(in.action()) {
            case "list" -> {
                require(in.sourceRoot()==null&&in.request()==null&&in.runId()==null&&in.answers()==null,"INVALID_REQUEST","Invalid list fields");
                emit(out,"result",Map.of("runs",store.listAgentRuns(in.caseId(),in.offset()==null?0:in.offset())));
            }
            case "get", "cancel" -> {
                require(in.runId()!=null&&in.sourceRoot()==null&&in.request()==null&&in.answers()==null&&in.caseId()==null&&in.offset()==null,"INVALID_REQUEST","Invalid run fields");
                var run=store.load(in.runId());
                if(in.action().equals("cancel")) {emit(out,"result",Map.of("cancelled",store.cancel(in.runId())));return;}
                Map<String,Object> value=new LinkedHashMap<>();
                value.put("caseId",run.caseId());value.put("runId",run.runId());value.put("revision",run.revision());
                value.put("latestRevision",run.latestRevision());value.put("state",run.state());value.put("projectionState",run.projectionState());
                value.put("reportHash",run.reportHash());value.put("report",run.reportJson()==null?null:Json.MAPPER.readTree(run.reportJson()));
                value.put("checkpoint",run.checkpointJson()==null?null:Json.MAPPER.readTree(run.checkpointJson()));
                emit(out,"result",value);
            }
            case "start", "resume" -> investigate(in,store,model,out);
            default -> throw new ContractException("INVALID_REQUEST","Unknown web operation");
        }
    }
    private static void investigate(Input in,PgInvestigationStore store,AgentModel model,PrintStream out)throws Exception {
        require(in.sourceRoot()!=null&&Path.of(in.sourceRoot()).isAbsolute()&&in.caseId()==null&&in.offset()==null,"INVALID_REQUEST","Absolute root required");
        Path root=Path.of(in.sourceRoot()).toRealPath();require(Files.isDirectory(root),"SOURCE_ROOT_INVALID","Root must be a directory");
        Report previous=null;Request request=in.request();PgInvestigationStore.StoredRun prior=null;
        if(in.action().equals("resume")) {
            require(in.runId()!=null&&request==null&&in.answers()!=null,"INVALID_REQUEST","Invalid resume fields");
            prior=store.load(in.runId());require(prior.latestRevision()&&prior.reportJson()!=null,"STALE_ANSWERS","Resume latest sealed report");
            previous=Json.MAPPER.readValue(prior.reportJson(),Report.class);request=previous.request();
            AgentOrchestrator.validateResume(request,previous,in.answers());
        } else require(request!=null&&in.runId()==null&&in.answers()==null,"INVALID_REQUEST","Invalid start fields");
        require(request.columnRequest()==null,"COLUMN_WEB_UNSUPPORTED","Use the existing column CLI adapter");
        // 校验先于分配修订；网页不能覆写父请求或提交任意存储票据。
        var ticket=store.beginAgent(prior==null?null:prior.caseId(),request,prior==null?null:prior.revision());
        emit(out,"started",ticket);
        try {
            var report=new AgentOrchestrator(model).execute(root,request,previous,in.answers(),()->{
                try{return !store.active(ticket);}catch(java.sql.SQLException e){throw new ContractException("STORAGE_UNAVAILABLE","Lease check failed");}
            },sources->{try{store.checkpoint(ticket,sources);}catch(java.sql.SQLException e){throw new ContractException("STORAGE_UNAVAILABLE","Checkpoint failed");}});
            store.finishAgent(ticket,report);
            // 网页首版不支持列适配；其他场景没有事实图，不制造 Neo4j 投影。
            emit(out,"sealed",Map.of("runId",ticket.runId(),"status",report.status()));
        } catch(Exception e) {try{store.fail(ticket);}catch(Exception ignored){}throw e;}
    }
    private static void emit(PrintStream out,String event,Object value){out.println(Json.canonical(Map.of("event",event,"value",value)));out.flush();}
}
