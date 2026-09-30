package io.archlens.investigation.database;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.archlens.contract.Json;
import io.archlens.contract.ContractException;
import java.util.function.BooleanSupplier;
import io.archlens.parser.SourceText;
import java.util.*;
import java.util.regex.*;

/** 语法线索关联：只表示代码文本出现了可匹配的 SQL 对象引用，不表示运行时依赖已证实。 */
public record CodeDatabaseAssociation(String schemaVersion,List<Link> links,List<String> coverageGaps,
                                      @JsonInclude(JsonInclude.Include.NON_NULL) List<AccessPath> accessPaths) {
    public static final String VERSION="archlens.code-database-association.v2";
    public CodeDatabaseAssociation(String schemaVersion,List<Link> links,List<String> coverageGaps){
        this(schemaVersion,links,coverageGaps,null);
    }
    /** 每条路径的边仍是静态候选；步骤证据只证明相应声明或线索出现在源码中。 */
    public record Step(String kind,String name,int line,String evidenceId) {}
    public record AccessPath(String pathId,String sourcePath,String sourceHash,List<Step> steps,String state,List<String> unknownReasons) {
        public AccessPath {steps=List.copyOf(steps);unknownReasons=List.copyOf(unknownReasons);}
    }
    public record Link(String evidenceId,String sourcePath,String sourceHash,int line,String operation,String token,
                       String state,@JsonInclude(JsonInclude.Include.NON_NULL) String databaseObject,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String objectEvidenceId,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Integer characterOffset) {
        public Link(String evidenceId,String sourcePath,String sourceHash,int line,String operation,String token,
                    String state,String databaseObject,String objectEvidenceId){
            this(evidenceId,sourcePath,sourceHash,line,operation,token,state,databaseObject,objectEvidenceId,null);
        }
    }
    public CodeDatabaseAssociation {
        links=List.copyOf(links);coverageGaps=List.copyOf(coverageGaps);
        if(accessPaths!=null)accessPaths=List.copyOf(accessPaths);
    }
    private static final Pattern SQL=Pattern.compile("(?i)\\b(FROM|JOIN|UPDATE|INTO|CALL|EXEC(?:UTE)?)\\s+([\\p{L}\\p{N}_$\"`\\[\\].]{1,130})");
    private static final Pattern LITERAL=Pattern.compile("(?s)(?:@?\"(?:\"\"|\\\\.|[^\"])*\"|'(?:''|\\\\.|[^'])*')");
    private static String withoutSqlComments(String input){
        char[] out=input.toCharArray();boolean line=false,block=false;char quoted=0;
        for(int i=0;i<out.length;i++){
            char c=input.charAt(i),next=i+1<out.length?input.charAt(i+1):0;
            if(line){if(c=='\n')line=false;else out[i]=' ';continue;}
            if(block){if(c=='*'&&next=='/'){out[i++]=' ';out[i]=' ';block=false;}else if(c!='\n')out[i]=' ';continue;}
            if(quoted!=0){if(c==quoted){if(next==quoted){i++;continue;}quoted=0;}continue;}
            if(c=='\''||c=='"'||c=='`'){quoted=c;continue;}
            if(c=='-'&&next=='-'){out[i++]=' ';out[i]=' ';line=true;continue;}
            if(c=='/'&&next=='*'){out[i++]=' ';out[i]=' ';block=true;}
        }
        return new String(out);
    }
    /** 将 XML 标签与注释遮蔽为空格，保留源码偏移供证据链定位。 */
    private static String withoutXmlMarkup(String input){
        char[] out=input.toCharArray();boolean tag=false,comment=false;char quote=0;
        for(int i=0;i<out.length;i++){
            char c=input.charAt(i);
            if(comment){if(input.startsWith("-->",i)){for(int j=0;j<3;j++)out[i+j]=' ';i+=2;comment=false;}
                else if(c!='\n')out[i]=' ';continue;}
            if(tag){if(quote!=0){if(c==quote)quote=0;}else if(c=='\''||c=='"')quote=c;else if(c=='>')tag=false;
                if(c!='\n')out[i]=' ';continue;}
            if(input.startsWith("<!--",i)){for(int j=0;j<4;j++)out[i+j]=' ';i+=3;comment=true;continue;}
            if(c=='<'){out[i]=' ';tag=true;}
        }
        return new String(out);
    }
    public static CodeDatabaseAssociation analyze(Map<String,SourceText> sources,DatabaseInventory db){
        return analyze(sources,db,()->false,Long.MAX_VALUE);
    }
    static void active(BooleanSupplier cancelled,long deadline){
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())
            throw new ContractException("DB_CODE_ASSOCIATION_CANCELLED","Code association cancelled");
        if(System.nanoTime()>=deadline)
            throw new ContractException("DB_CODE_ASSOCIATION_TIME_BUDGET","Code association budget exhausted");
    }
    public static CodeDatabaseAssociation analyze(Map<String,SourceText> sources,DatabaseInventory db,
                                                  BooleanSupplier cancelled,long deadline){
        List<Link> links=new ArrayList<>();List<String> gaps=new ArrayList<>(List.of("DB_CODE_STATIC_CANDIDATES_ONLY","DB_DYNAMIC_SQL_UNRESOLVED"));
        if(db==null||!"PARTIAL".equals(db.status()))return new CodeDatabaseAssociation(VERSION,links,List.of("DB_INVENTORY_UNAVAILABLE_FOR_ASSOCIATION"));
        Map<String,List<DatabaseInventory.Table>> objects=new HashMap<>();
        for(var t:db.tables())objects.computeIfAbsent(t.name().toLowerCase(Locale.ROOT),k->new ArrayList<>()).add(t);
        Map<String,List<DatabaseInventory.ProgramObject>> routines=new HashMap<>();
        if(db.extendedMetadata()!=null)for(var program:db.extendedMetadata().programs())
            if(program.name()!=null&&Set.of("PROCEDURE","FUNCTION").contains(program.kind()))
                routines.computeIfAbsent(program.name().toLowerCase(Locale.ROOT),k->new ArrayList<>()).add(program);
        outer:for(var source:sources.values()){
            active(cancelled,deadline);
            String path=source.path().toLowerCase(Locale.ROOT);
            boolean sqlFile=path.endsWith(".sql"),sourceFile=path.endsWith(".cs")||path.endsWith(".java")||path.endsWith(".xml")||path.endsWith(".kt");
            if(!sqlFile&&!sourceFile){
                if(path.endsWith(".vb")||path.endsWith(".fs")||path.endsWith(".fsx"))
                    if(!gaps.contains("DB_CODE_LANGUAGE_UNSUPPORTED"))gaps.add("DB_CODE_LANGUAGE_UNSUPPORTED");
                continue;
            }
            String content=source.text();boolean xml=path.endsWith(".xml");
            String scanned=sqlFile?withoutSqlComments(content):xml?withoutXmlMarkup(content):CodeAccessPathAnalyzer.withoutCodeComments(content);
            int nextLine=0,line=1;Matcher regions=sqlFile||xml?Pattern.compile("(?s)\\A.*\\z").matcher(scanned):LITERAL.matcher(scanned);
            while(regions.find()){
                active(cancelled,deadline);
                String region=regions.group();Matcher match=SQL.matcher(region);
                while(match.find()){
                    active(cancelled,deadline);
                    if(links.size()>=500){gaps.add("DB_CODE_ASSOCIATION_LIMIT");break outer;}
                    int offset=regions.start()+match.start();
                    while(nextLine<offset){if(content.charAt(nextLine++)=='\n')line++;}
                    String raw=match.group(2),token=raw.replaceAll("^[\"`\\[]+|[\"`\\],;]+$","");
                    if(token.isEmpty())continue;
                    String[] parts=token.split("\\.");String name=parts[parts.length-1].replaceAll("^[\"`\\[]+|[\"`\\]]+$","");
                    if(name.isEmpty()||name.length()>128)continue;
                    boolean otherSchema=parts.length>1&&!parts[parts.length-2].replaceAll("[\"`\\[\\]]","").equalsIgnoreCase(db.database());
                    boolean routineCall=Set.of("CALL","EXEC","EXECUTE").contains(match.group(1).toUpperCase(Locale.ROOT));
                    var tableCandidates=otherSchema||routineCall?List.<DatabaseInventory.Table>of():objects.getOrDefault(name.toLowerCase(Locale.ROOT),List.of());
                    var programCandidates=otherSchema||!routineCall?List.<DatabaseInventory.ProgramObject>of():routines.getOrDefault(name.toLowerCase(Locale.ROOT),List.of());
                    int count=tableCandidates.size()+programCandidates.size();
                    String state=count==1?"CANDIDATE":count>1?"AMBIGUOUS":"UNMATCHED";
                    var table=tableCandidates.size()==1?tableCandidates.getFirst():null;
                    var program=programCandidates.size()==1?programCandidates.getFirst():null;
                    String objectName=count==1?(table!=null?table.name():program.name()):null;
                    String objectEvidence=count==1?(table!=null?table.evidenceId():Json.hashParts(db.sourceFingerprint(),db.database(),program.kind(),program.name())):null;
                    links.add(new Link(Json.hashParts(source.hash(),offset,match.group(1),name),source.path(),source.hash(),line,
                            match.group(1).toUpperCase(Locale.ROOT),name,state,objectName,objectEvidence,offset));
                }
            }
        }
        if(links.isEmpty())gaps.add("DB_CODE_SQL_REFERENCES_NOT_FOUND");
        // 方法/Mapper 容器和数据访问调用另行采集，不能由对象同名推断完整运行时调用图。
        var paths=CodeAccessPathAnalyzer.analyze(sources,links,cancelled,deadline);
        if(!paths.isEmpty())gaps.add("DB_METHOD_CALL_GRAPH_UNAVAILABLE");
        if(paths.stream().anyMatch(p->p.steps().stream().anyMatch(step->step.kind().equals("MYBATIS_STATEMENT"))))
            gaps.add("DB_MAPPER_INTERFACE_UNBOUND");
        if(paths.stream().anyMatch(p->p.unknownReasons().contains("DATA_ACCESS_CALL_UNRESOLVED")))gaps.add("DB_DATA_ACCESS_CALL_UNRESOLVED");
        return new CodeDatabaseAssociation(VERSION,links,gaps,paths);
    }
}
