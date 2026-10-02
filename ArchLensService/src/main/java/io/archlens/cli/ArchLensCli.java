package io.archlens.cli;

import io.archlens.analysis.*;
import io.archlens.contract.*;
import io.archlens.parser.*;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;
import static io.archlens.contract.Model.*;

public final class ArchLensCli {
    private ArchLensCli() {}
    public record Report(String schemaVersion,String mode,String requestHash,Map<String,String> sourceHashes,
                         String graphDigest,GraphDocument graph,ChangeSpec changeSpec,BlastRadius.Result analysis) {}
    public static void main(String[] args) { System.exit(run(args,System.out,System.err)); }
    public static Report analyze(Path root,OfflineRequest input,String requestHash) throws Exception {
        GraphDocument document=new MapperAnalyzer().analyze(root,input);
        FactGraph graph=new FactGraph(document);
        var columns=input.catalog().stream().filter(c->c.schema().equals(input.change().schema()) && c.table().equals(input.change().table())
                && c.name().equals(input.change().column())).toList();
        ContractException.require(columns.size()==1,"INVALID_TARGET","Target must exist uniquely in the offline catalog");
        var column=columns.getFirst();
        String target=Model.nodeId(graph.scope(),NodeType.COLUMN,MapperAnalyzer.columnKey(input,column));
        ChangeSpec change=new ChangeSpec(target,input.change().kind(),new ChangeSpec.ColumnState(column.name(),column.type(),column.nullable(),column.identityMeaning()),
                input.change().after(),ChangeSpec.Stage.PROPOSED,List.of(),List.of("OFFLINE_CATALOG_UNVERIFIED"));
        var result=new BlastRadius().calculate(graph,change,0,BlastRadius.Limits.defaults());
        Map<String,String> hashes=new TreeMap<>();
        document.evidence().forEach(e->hashes.put(e.location().path(),e.sourceHash()));
        return new Report(VERSION,"OFFLINE_DEVELOPER_ANALYSIS",requestHash,Map.copyOf(hashes),Json.hash(document),document,change,result);
    }
    public static int run(String[] args,PrintStream out,PrintStream err) {
        if(args.length>0 && args[0].equals("migration-request-check")) return MigrationRequestCli.run(args,out,err);
        if(args.length>0 && args[0].equals("migration-plan-check")) return MigrationPlanCli.run(args,out,err);
        if(args.length>0 && !args[0].equals("analyze")) return InvestigationCli.run(args,out,err);
        if(args.length!=3 || !args[0].equals("analyze")) {
            err.println("Usage: java -jar archlens-*-cli.jar analyze <request.json> <new-report.json>"); return 2;
        }
        try {
            Path request=Path.of(args[1]).toRealPath(), output=Path.of(args[2]).toAbsolutePath().normalize();
            SourceText raw=SourceText.read(request.getParent(),request.getFileName().toString());
            OfflineRequest input=Json.MAPPER.readValue(raw.text(),OfflineRequest.class);
            Report report=analyze(request.getParent(),input,raw.hash());
            GraphDocument document=report.graph();
            byte[] bytes=Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
            // CREATE_NEW prevents overwriting the request, source files or previous audit output.
            Files.createDirectories(output.getParent());
            Files.write(output,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            out.println("PARTIAL: "+document.nodes().size()+" nodes, "+document.edges().size()+" edges, "+document.diagnostics().size()+" diagnostics -> "+output);
            return 0;
        } catch(Exception e) {
            Throwable cause=e; while(cause.getCause()!=null) cause=cause.getCause();
            String code=cause instanceof ContractException c ? c.code() : e instanceof FileAlreadyExistsException ? "OUTPUT_EXISTS" : "INVALID_INPUT";
            err.println(code+": "+(cause instanceof ContractException ? cause.getMessage() : "Analysis failed; check paths, UTF-8 encoding, JSON contract and output permissions"));
            return 2;
        }
    }
}
