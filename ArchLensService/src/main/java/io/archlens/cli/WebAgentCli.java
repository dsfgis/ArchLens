package io.archlens.cli;

import io.archlens.agent.*;
import io.archlens.agent.AgentContracts.*;
import io.archlens.contract.*;
import io.archlens.storage.*;
import io.archlens.investigation.*;
import io.archlens.investigation.dotnet.DotnetInventoryAnalyzer;
import io.archlens.investigation.database.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static io.archlens.contract.ContractException.require;

/** 本机网页的 JSON 行协议。根目录只交给采集器，绝不进入模型 Request。 */
public final class WebAgentCli {
    public record Input(String action,String sourceRoot,Request request,UUID runId,Answers answers,UUID caseId,Integer offset,BusinessConnection connection) {
        public Input(String action,String sourceRoot,Request request,UUID runId,Answers answers,UUID caseId,Integer offset){this(action,sourceRoot,request,runId,answers,caseId,offset,null);}
    }
    public static void main(String[] args) {
        try {
            byte[] bytes=System.in.readNBytes(262145);
            require(bytes.length<=262144,"INPUT_LIMIT","Web input too large");
            var input=Json.MAPPER.readValue(bytes,Input.class);
            // 本地现状预览不初始化存储、不创建模型客户端或历史记录。
            if(input!=null&&Set.of("preview-dotnet","preview-joint","test-database").contains(input.action())) {execute(input,null,null,System.out);return;}
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
        require(in.connection()==null||Set.of("start","resume","preview-joint","test-database").contains(in.action()),"INVALID_REQUEST","Connection not allowed for this operation");
        switch(in.action()) {
            case "preview-joint", "test-database" -> businessPreview(in,out);
            case "preview-dotnet" -> previewDotnet(in,out);
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
    private static Request bind(Request request,BusinessConnection connection) {
        if(request.businessContext()==null||request.businessContext().source()==null){require(connection==null,"INVALID_REQUEST","Connection requires an explicit source scope");return request;}
        require(connection!=null,"DATASOURCE_CREDENTIALS_REQUIRED","Enter credentials for this invocation");
        var context=request.businessContext();connection.verify(context.source());
        var bound=new BusinessContext(context.schemaVersion(),context.source().bind(connection.fingerprint()),context.targetEnvironment());
        return new Request(request.schemaVersion(),request.objective(),request.target(),request.constraints(),request.invariants(),request.files(),request.columnRequest(),request.collectionBudget(),request.agentBudget(),bound);
    }
    private static void businessPreview(Input in,PrintStream out)throws Exception {
        require(in.request()!=null&&in.runId()==null&&in.answers()==null&&in.caseId()==null&&in.offset()==null,"INVALID_REQUEST","Invalid preview fields");
        Request request=bind(in.request(),in.connection());require(request.columnRequest()==null,"COLUMN_WEB_UNSUPPORTED","Local preview does not accept column adapter");
        require(request.businessContext()!=null&&request.businessContext().source()!=null,"INVALID_REQUEST","Business database source required");
        if(in.action().equals("test-database")) {
            require(request.businessContext().source()!=null,"INVALID_REQUEST","Source required");
            var result=DatabaseCollectors.collect(request.businessContext().source(),in.connection(),()->false,System.nanoTime()+10_000_000_000L,true);
            emit(out,"result",Map.of("connectionTest",result));return;
        }
        Path root=sourceRoot(in.sourceRoot(),request);
        var b=request.collectionBudget();var r=new InvestigationRequest(InvestigationRequest.VERSION,InvestigationRequest.Scenario.CURRENT_STATE,new InvestigationRequest.Profile("UNSPECIFIED",null),null,request.objective(),request.constraints(),request.invariants(),request.files(),null,new InvestigationRequest.Budget(b.maxFiles(),b.maxBytes(),Math.min(25000,b.timeoutMillis())));
        var report=new InvestigationEngine(in.connection()).investigate(root,r,()->false,sources->{},null,request.businessContext());
        emit(out,"result",Map.of("mode",request.files().isEmpty()?"LOCAL_DATABASE_PREVIEW":"LOCAL_JOINT_PREVIEW",
                "persisted",false,"report",report,"reportHash",Json.hash(report)));
    }
    private static void previewDotnet(Input in,PrintStream out)throws Exception {
        require(in.sourceRoot()!=null&&Path.of(in.sourceRoot()).isAbsolute()&&in.request()!=null
                &&in.runId()==null&&in.answers()==null&&in.caseId()==null&&in.offset()==null,"INVALID_REQUEST","Invalid preview fields");
        var request=in.request();
        require(request.columnRequest()==null,"COLUMN_WEB_UNSUPPORTED","Preview only accepts local source files");
        require(request.target()!=null&&request.target().scenario()==InvestigationRequest.Scenario.CURRENT_STATE,
                "INVALID_REQUEST","Preview is a current-state analysis");
        require(request.files().stream().anyMatch(p->DotnetInventoryAnalyzer.projectPath(p)||DotnetInventoryAnalyzer.solutionPath(p)),
                "DOTNET_PROJECT_REQUIRED","Include a project or solution in the manifest");
        Path root=Path.of(in.sourceRoot()).toRealPath();require(Files.isDirectory(root),"SOURCE_ROOT_INVALID","Root must be a directory");
        var budget=request.collectionBudget();
        var analysisRequest=new InvestigationRequest(InvestigationRequest.VERSION,InvestigationRequest.Scenario.CURRENT_STATE,
                new InvestigationRequest.Profile(".NET",null),null,"本地 .NET 平台现状预览",List.of(),List.of(),request.files(),null,
                new InvestigationRequest.Budget(budget.maxFiles(),budget.maxBytes(),Math.min(budget.timeoutMillis(),25000)));
        var report=new InvestigationEngine().investigate(root,analysisRequest,()->Thread.currentThread().isInterrupted());
        emit(out,"result",Map.of("mode","LOCAL_PREVIEW","persisted",false,"report",report,"reportHash",Json.hash(report)));
    }
    private static void investigate(Input in,PgInvestigationStore store,AgentModel model,PrintStream out)throws Exception {
        require(in.caseId()==null&&in.offset()==null,"INVALID_REQUEST","Invalid investigation fields");
        Report previous=null;Request request=in.request();PgInvestigationStore.StoredRun prior=null;
        if(in.action().equals("resume")) {
            require(in.runId()!=null&&request==null&&in.answers()!=null,"INVALID_REQUEST","Invalid resume fields");
            prior=store.load(in.runId());require(prior.latestRevision()&&prior.reportJson()!=null,"STALE_ANSWERS","Resume latest sealed report");
            previous=Json.MAPPER.readValue(prior.reportJson(),Report.class);request=previous.request();
            AgentOrchestrator.validateResume(request,previous,in.answers());
        } else require(request!=null&&in.runId()==null&&in.answers()==null,"INVALID_REQUEST","Invalid start fields");
        Path root=sourceRoot(in.sourceRoot(),request);
        request=bind(request,in.connection());
        require(request.columnRequest()==null,"COLUMN_WEB_UNSUPPORTED","Use the existing column CLI adapter");
        // 校验先于分配修订；网页不能覆写父请求或提交任意存储票据。
        var ticket=store.beginAgent(prior==null?null:prior.caseId(),request,prior==null?null:prior.revision());
        emit(out,"started",ticket);
        try {
            var report=new AgentOrchestrator(model,in.connection()).execute(root,request,previous,in.answers(),()->{
                try{return !store.active(ticket);}catch(java.sql.SQLException e){throw new ContractException("STORAGE_UNAVAILABLE","Lease check failed");}
            },sources->{try{store.checkpoint(ticket,sources);}catch(java.sql.SQLException e){throw new ContractException("STORAGE_UNAVAILABLE","Checkpoint failed");}});
            store.finishAgent(ticket,report);
            // 网页首版不支持列适配；其他场景没有事实图，不制造 Neo4j 投影。
            emit(out,"sealed",Map.of("runId",ticket.runId(),"status",report.status()));
        } catch(Exception e) {try{store.fail(ticket);}catch(Exception ignored){}throw e;}
    }
    private static Path sourceRoot(String value,Request request)throws Exception {
        require(request!=null&&(!request.files().isEmpty()||request.businessContext()!=null&&request.businessContext().source()!=null),
                "INVALID_REQUEST","Select code files or a business database");
        if(value==null||value.isBlank()) {
            require(request!=null&&request.files().isEmpty()&&request.columnRequest()==null&&request.businessContext()!=null&&request.businessContext().source()!=null,"SOURCE_ROOT_INVALID","Code inputs require an authorized root");
            return null;
        }
        require(Path.of(value).isAbsolute(),"SOURCE_ROOT_INVALID","Absolute root required");
        Path root=Path.of(value).toRealPath();require(Files.isDirectory(root),"SOURCE_ROOT_INVALID","Directory required");return root;
    }
    private static void emit(PrintStream out,String event,Object value){out.println(Json.canonical(Map.of("event",event,"value",value)));out.flush();}
}
