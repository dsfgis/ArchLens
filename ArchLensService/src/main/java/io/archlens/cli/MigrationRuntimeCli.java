package io.archlens.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.archlens.contract.*;
import io.archlens.migration.MigrationRequest;
import io.archlens.storage.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.sql.SQLException;
import java.util.*;
import static io.archlens.contract.ContractException.require;

/** Local trusted-host control protocol, deliberately separate from tools and model arguments. */
public final class MigrationRuntimeCli {
    public static final String VERSION="archlens.runtime-bridge.v1";
    private MigrationRuntimeCli() {}
    public record Request(String protocolVersion,UUID requestId,String operation,Map<String,JsonNode> arguments) {}
    public record Identity(UUID caseId,UUID runId,int revision,String requestHash,String workerId,long epoch) {
        PgMigrationRunStore.Ticket ticket() {
            require(caseId!=null && runId!=null && revision>0 && requestHash!=null && requestHash.matches("[0-9a-f]{64}")
                    && workerId!=null && workerId.matches("[A-Za-z][A-Za-z0-9._:-]{0,99}") && epoch>0,"STALE_RUN","Invalid ticket");
            return new PgMigrationRunStore.Ticket(caseId,runId,revision,requestHash,workerId,epoch,null);
        }
        static Identity from(PgMigrationRunStore.Ticket t){return t==null?null:new Identity(t.caseId(),t.runId(),t.revision(),t.requestHash(),t.workerId(),t.epoch());}
    }
    public record Response(String protocolVersion,UUID requestId,String status,Object result,String errorCode) {}
    public static int run(String[] args,InputStream in,PrintStream out,PrintStream err,Map<String,String> env) {
        UUID id=null;
        try {
            require(args.length==1,"MIG_RUNTIME_USAGE","Expected one command");
            byte[] bytes=in.readNBytes(16385);
            require(bytes.length>=2 && bytes.length<=16384 && bytes[bytes.length-1]=='\n',"MIG_RUNTIME_FRAMING","One bounded JSON line required");
            for(int i=0;i<bytes.length-1;i++)require(bytes[i]!='\n' && bytes[i]!='\r',"MIG_RUNTIME_FRAMING","One JSON line required");
            String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Request r=Json.MAPPER.readValue(raw,Request.class);id=r.requestId();
            require(VERSION.equals(r.protocolVersion()) && id!=null && r.arguments()!=null,"MIG_RUNTIME_VERSION","Invalid protocol");
            StorageConfig config=new StorageConfig(env);var runtime=new PgMigrationRuntimeStore(config);var runs=new PgMigrationRunStore(config);
            Map<String,JsonNode> a=r.arguments();
            Object result=switch(r.operation()) {
                case "INITIALIZE" -> { keys(a);new PgInvestigationStore(config).initialize();yield Map.of("initialized",true); }
                case "ENQUEUE" -> {keys(a,"submissionKey","request");yield runtime.enqueue(text(a,"submissionKey"),convert(a,"request",MigrationRequest.class));}
                case "CLAIM_NEXT" -> {keys(a,"workerId");yield Identity.from(runtime.claimNext(text(a,"workerId")));}
                case "RENEW" -> {keys(a,"ticket");yield Identity.from(runs.renew(ticket(a)));}
                case "CONTEXT" -> {keys(a,"ticket");yield runtime.context(ticket(a));}
                case "ACK" -> {keys(a,"ticket","checkpointId","expectedSequence","expectedStateHash","stateHash");
                    require(a.get("expectedSequence").isIntegralNumber() && a.get("expectedSequence").canConvertToLong(),"MIG_RUNTIME_ARGUMENTS","Expected integer sequence");
                    yield runtime.acknowledge(ticket(a),text(a,"checkpointId"),a.get("expectedSequence").longValue(),nullable(a,"expectedStateHash"),text(a,"stateHash"));}
                case "SEAL" -> {keys(a,"ticket","checkpointHash","reason");yield runtime.seal(ticket(a),nullable(a,"checkpointHash"),text(a,"reason"));}
                case "CANCEL" -> {keys(a,"runId");var receipt=runs.cancel(UUID.fromString(text(a,"runId")));yield Map.of("runId",receipt.runId(),"epoch",receipt.epoch(),"cancelledAt",receipt.cancelledAt().toString());}
                case "GET" -> {keys(a,"runId");UUID run=UUID.fromString(text(a,"runId"));var view=runs.load(run);var value=new LinkedHashMap<String,Object>();
                    value.put("runId",run);value.put("state",view.state());value.put("epoch",view.epoch());value.put("latestRevision",view.latestRevision());value.put("receipt",runtime.receipt(run));yield value;}
                default -> throw new ContractException("MIG_RUNTIME_OPERATION","Unsupported runtime operation");
            };
            write(out,new Response(VERSION,id,"OK",result,null));return 0;
        }catch(Exception e){
            Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();
            String code=cause instanceof ContractException ce?ce.code():cause instanceof SQLException?"MIG_RUNTIME_STORAGE":"MIG_RUNTIME_INVALID";
            write(out,new Response(VERSION,id,"ERROR",null,code));err.print(code+"\n");return 2;
        }
    }
    private static PgMigrationRunStore.Ticket ticket(Map<String,JsonNode>a){return convert(a,"ticket",Identity.class).ticket();}
    private static void keys(Map<String,JsonNode>a,String...keys){require(a.keySet().equals(Set.of(keys)),"MIG_RUNTIME_ARGUMENTS","Unexpected runtime fields");}
    private static String text(Map<String,JsonNode>a,String key){require(a.get(key)!=null && a.get(key).isTextual(),"MIG_RUNTIME_ARGUMENTS","Text field required");return a.get(key).textValue();}
    private static String nullable(Map<String,JsonNode>a,String key){return a.get(key).isNull()?null:text(a,key);}
    private static <T>T convert(Map<String,JsonNode>a,String key,Class<T>t){require(a.get(key)!=null && a.get(key).isObject(),"MIG_RUNTIME_ARGUMENTS","Object required");return Json.MAPPER.convertValue(a.get(key),t);}
    private static void write(PrintStream out,Response r){byte[]bytes=(Json.canonical(r)+"\n").getBytes(StandardCharsets.UTF_8);require(bytes.length<=16384,"MIG_RUNTIME_RESPONSE_LIMIT","Response too large");out.write(bytes,0,bytes.length);out.flush();}
}
