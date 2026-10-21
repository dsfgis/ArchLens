package io.archlens.investigation.dotnet;

import io.archlens.contract.Json;
import io.archlens.contract.ContractException;
import io.archlens.parser.SourceText;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Isolated Roslyn process over the request's explicit .cs texts; never evaluates a project. */
public final class DotnetSemantics {
    private DotnetSemantics() {}
    public record Fact(String id,String kind,String path,String project,String sha256,int line,int column,int endLine,int endColumn,long startByte,long endByte,String symbol,String target,String binding) {}
    public record Result(String schemaVersion,String status,List<Fact> facts,List<String> gaps) {
        public Result {facts=List.copyOf(facts);gaps=List.copyOf(gaps);}
        public List<Fact> exposedFacts() {
            var groups=new LinkedHashMap<String,List<Fact>>();
            for(var f:facts)groups.computeIfAbsent(Objects.toString(f.project(),f.path()),k->new ArrayList<>()).add(f);
            var representatives=new ArrayList<List<Fact>>();
            for(var group:groups.values()) {
                var byKind=new LinkedHashMap<String,Fact>();
                for(var f:group)byKind.putIfAbsent(f.kind()+":"+f.binding(),f);
                representatives.add(new ArrayList<>(byKind.values()));
            }
            var selected=new ArrayList<Fact>();var seen=new HashSet<String>();
            for(int row=0;selected.size()<60;row++) {
                boolean any=false;
                for(var group:representatives)if(row<group.size()) {
                    any=true;var f=group.get(row);if(seen.add(f.id()))selected.add(f);
                    if(selected.size()==60)break;
                }
                if(!any)break;
            }
            if(selected.size()<60)for(var f:facts){if(seen.add(f.id()))selected.add(f);if(selected.size()==60)break;}
            return List.copyOf(selected);
        }
        public Map<String,Object> projection() {
            var counts=new TreeMap<String,Integer>();
            for(var f:facts)counts.merge(f.kind()+":"+f.binding(),1,Integer::sum);
            return Map.of("status",status,"factCount",facts.size(),"categories",counts,"gaps",gaps,
                    "facts",exposedFacts().stream().map(f->Map.of("id",f.id(),"kind",f.kind(),"binding",f.binding())).toList(),
                    "factsTruncated",facts.size()>60,"projection","ANONYMIZED_SEMANTIC_EVIDENCE");
        }
    }
    private record InputSource(String path,String text,String sha256,String project) {}
    private record InputProject(String path,String text,String sha256) {}
    private record Input(List<InputSource> sources,List<InputProject> projects) {}
    public static Result inspect(Path root,List<String> authorizedFiles,BooleanSupplier cancelled,long timeoutMillis) {
        var sources=new ArrayList<InputSource>();
        var projectDocuments=new ArrayList<InputProject>();
        Process process=null;
        try {
            List<String> projects=authorizedFiles.stream().filter(p->p.toLowerCase(Locale.ROOT).endsWith(".csproj"))
                    .map(p->p.replace('\\','/')).toList();
            for(String path:projects) {
                if(cancelled.getAsBoolean())return gap("CANCELLED");
                var project=SourceText.read(root,path);
                projectDocuments.add(new InputProject(project.path(),project.text(),project.hash()));
            }
            for(String path:authorizedFiles)if(path.toLowerCase(Locale.ROOT).endsWith(".cs")) {
                if(cancelled.getAsBoolean())return gap("CANCELLED");
                var source=SourceText.read(root,path);
                String project=projects.stream().filter(p->{int slash=p.lastIndexOf('/');String dir=slash<0?"":p.substring(0,slash+1);return source.path().startsWith(dir);})
                        .max(Comparator.comparingInt(p->p.lastIndexOf('/'))).orElse(null);
                sources.add(new InputSource(source.path(),source.text(),source.hash(),project));
            }
            if(sources.isEmpty())return gap("NO_AUTHORIZED_CSHARP_SOURCES");
            String configured=System.getenv("ARCHLENS_DOTNET_SEMANTIC_TOOL");
            Path dll=configured!=null&&!configured.isBlank()?Path.of(configured):defaultDll();
            if(!Files.isRegularFile(dll))return gap("ROSLYN_TOOL_NOT_BUILT");
            String host=System.getenv("ARCHLENS_DOTNET_HOST");
            if(host==null||host.isBlank())host=defaultHost();
            Process child=new ProcessBuilder(host,dll.toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            process=child;
            byte[] request=Json.canonical(new Input(sources,projectDocuments)).getBytes(StandardCharsets.UTF_8);
            byte[] response;
            try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
                Future<?> writer=executor.submit(()->{
                    try(var output=child.getOutputStream()){output.write(request);}
                    catch(IOException ignored){}
                });
                Future<byte[]> reader=executor.submit(()->{
                    try(var in=child.getInputStream()){return in.readNBytes(18_000_001);}
                    catch(IOException e){return new byte[0];}
                });
                long deadline=System.nanoTime()+Math.max(1,timeoutMillis)*1_000_000;
                while(child.isAlive()&&System.nanoTime()<deadline&&!cancelled.getAsBoolean())child.waitFor(100,TimeUnit.MILLISECONDS);
                if(child.isAlive()){
                    child.destroyForcibly();
                    return gap(cancelled.getAsBoolean()?"CANCELLED":"ROSLYN_TIMEOUT");
                }
                if(child.exitValue()!=0)return gap("ROSLYN_PROCESS_FAILED");
                writer.get(Math.max(1,timeoutMillis),TimeUnit.MILLISECONDS);
                response=reader.get(Math.max(1,timeoutMillis),TimeUnit.MILLISECONDS);
            }
            if(response.length>18_000_000)return gap("ROSLYN_OUTPUT_LIMIT");
            Result result=Json.MAPPER.readValue(response,Result.class);
            if(!"archlens.dotnet-semantics.v1".equals(result.schemaVersion())||result.facts().size()>20000)return gap("ROSLYN_OUTPUT_INVALID");
            var hashes=new HashMap<String,String>();var projectByPath=new HashMap<String,String>();
            for(var source:sources){hashes.put(source.path(),source.sha256());projectByPath.put(source.path(),source.project());}
            var ids=new HashSet<String>();
            for(var fact:result.facts())if(!Objects.equals(hashes.get(fact.path()),fact.sha256())
                    ||!Objects.equals(projectByPath.get(fact.path()),fact.project())||!ids.add(fact.id())
                    ||fact.line()<1||fact.column()<1||fact.endLine()<fact.line()||fact.endColumn()<1
                    ||fact.startByte()<0||fact.endByte()<=fact.startByte()||fact.symbol()==null)return gap("ROSLYN_OUTPUT_INVALID");
            for(var source:sources)if(!SourceText.read(root,source.path()).hash().equals(source.sha256()))return gap("SOURCE_DRIFT");
            for(var project:projectDocuments)if(!SourceText.read(root,project.path()).hash().equals(project.sha256()))return gap("SOURCE_DRIFT");
            return result;
        }catch(ContractException e){throw e;}
        catch(Exception e){return gap("ROSLYN_TOOL_UNAVAILABLE");}
        finally {if(process!=null&&process.isAlive())process.destroyForcibly();}
    }
    private static Path defaultDll(){
        Path local = Path.of("analyzers/dotnet/bin/Release/net10.0/ArchLens.Semantics.dll");
        for (Path candidate : List.of(local, Path.of("ArchLensService").resolve(local),
                Path.of("..", "ArchLensService").resolve(local))) {
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        return local;
    }
    private static String defaultHost(){
        for(var candidate:List.of(Path.of(".local/toolchains/dotnet/dotnet"),Path.of("../.local/toolchains/dotnet/dotnet")))
            if(Files.isExecutable(candidate))return candidate.toAbsolutePath().normalize().toString();
        return "dotnet";
    }
    private static Result gap(String code){return new Result("archlens.dotnet-semantics.v1","UNAVAILABLE",List.of(),List.of(code));}
}
