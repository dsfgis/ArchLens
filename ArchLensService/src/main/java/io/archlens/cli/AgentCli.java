package io.archlens.cli;

import io.archlens.agent.*;
import io.archlens.agent.AgentContracts.*;
import io.archlens.contract.*;
import io.archlens.parser.SourceText;
import io.archlens.storage.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static io.archlens.contract.ContractException.require;

/** Agent 文件与持久化入口；文件范围、密钥和存储票据均由本地宿主管理。 */
public final class AgentCli {
    private AgentCli() {}
    public static int run(String[] args,PrintStream out,PrintStream err)throws Exception {
        if(args.length==1&&args[0].equals("agent-tools")){out.println(Json.canonical(AgentTools.schemas()));return 0;}
        boolean resume=args[0].equals("agent-resume")||args[0].equals("agent-resume-store");
        boolean stored=args[0].endsWith("-store");
        require(Set.of("agent-investigate","agent-resume","agent-investigate-store","agent-resume-store").contains(args[0]),"USAGE","Unknown Agent command");
        require(args.length==(stored?(resume?4:2):(resume?5:3)),"USAGE","Invalid Agent arguments");
        Path path=Path.of(args[1]).toRealPath();Request request=read(path,Request.class);
        var model=model();var agent=new AgentOrchestrator(model);
        if(!stored) {
            Path output=Path.of(args[resume?4:2]).toAbsolutePath().normalize();
            require(!Files.exists(output),"OUTPUT_EXISTS","Output already exists");
            Report previous=resume?read(Path.of(args[2]).toRealPath(),Report.class):null;
            Answers answers=resume?read(Path.of(args[3]).toRealPath(),Answers.class):null;
            var report=agent.execute(path.getParent(),request,previous,answers,()->false,sources->{});
            Files.createDirectories(output.getParent());Files.write(output,Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
            out.println(Json.canonical(Map.of("status",report.status(),"revision",report.revision(),"reportHash",Json.hash(report),"output",output.toString())));return 0;
        }
        var config=new StorageConfig(System.getenv());var store=new PgInvestigationStore(config);
        PgInvestigationStore.StoredRun prior=resume?store.load(UUID.fromString(args[2])):null;
        Report previous=null;Answers answers=null;
        if(resume) {
            require(prior.latestRevision()&&prior.reportJson()!=null,"STALE_ANSWERS","Resume the latest sealed report");
            previous=Json.MAPPER.readValue(prior.reportJson(),Report.class);answers=read(Path.of(args[3]).toRealPath(),Answers.class);
            // 在分配数据库修订之前验证回答，不因明显无效的输入占用新修订。
            AgentOrchestrator.validateResume(request,previous,answers);
        }
        var ticket=store.beginAgent(prior==null?null:prior.caseId(),request,prior==null?null:prior.revision());
        out.println("RUNNING "+Json.canonical(ticket));out.flush();
        try {
            var report=agent.execute(path.getParent(),request,previous,answers,()->{
                try{return !store.active(ticket);}catch(java.sql.SQLException e){throw new ContractException("STORAGE_UNAVAILABLE","Cannot check Agent lease");}
            },sources->{try{store.checkpoint(ticket,sources);}catch(java.sql.SQLException e){throw new ContractException("STORAGE_UNAVAILABLE","Cannot checkpoint Agent sources");}});
            store.finishAgent(ticket,report);
            var saved=store.load(ticket.runId());
            if(saved.graphJson()!=null)try(var neo=new Neo4jProjection(config)){neo.project(saved);store.projected(saved.runId(),saved.graphHash());}
            catch(Exception e){err.println("PROJECTION_PENDING: PG Agent report retained; use projection-retry "+ticket.runId());return 3;}
            out.println("SEALED "+Json.canonical(Map.of("caseId",ticket.caseId(),"runId",ticket.runId(),"revision",ticket.revision(),"status",report.status(),"reportHash",saved.reportHash())));
            return 0;
        }catch(Exception e){try{store.fail(ticket);}catch(Exception ignored){}throw e;}
    }
    private static AgentModel model(){
        String key=System.getenv("DEEPSEEK_API_KEY");return key==null||key.isBlank()?null:new DeepSeekAgentModel(key,System.getenv("DEEPSEEK_MODEL"));
    }
    private static <T>T read(Path path,Class<T> type)throws IOException {
        return Json.MAPPER.readValue(SourceText.read(path.getParent(),path.getFileName().toString()).text(),type);
    }
}
