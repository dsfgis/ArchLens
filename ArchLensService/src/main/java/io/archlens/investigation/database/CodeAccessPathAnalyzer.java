package io.archlens.investigation.database;

import io.archlens.contract.Json;
import io.archlens.parser.SourceText;
import java.util.*;
import java.util.regex.*;
import java.util.function.BooleanSupplier;
import static io.archlens.investigation.database.CodeDatabaseAssociation.*;

/** 有界静态证据路径：只做容器归属与同一容器内调用定位，不宣称 SQL 值流或运行时调用已绑定。 */
final class CodeAccessPathAnalyzer {
    private CodeAccessPathAnalyzer(){}
    private static final Pattern METHOD=Pattern.compile("(?m)^\\s*(?:(?:public|private|protected|internal|static|async|final|synchronized|override|virtual|sealed|partial|abstract)\\s+)*[\\w<>?,.\\[\\]]+\\s+([A-Za-z_][A-Za-z_0-9]*)\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w., ]+)?\\s*\\{");
    private static final Pattern ACCESS=Pattern.compile("\\b(?:new\\s+(?:SqlCommand|OracleCommand|MySqlCommand|DbCommand|PreparedStatement)|(?:prepareStatement|ExecuteReader|ExecuteNonQuery|ExecuteScalar|QueryAsync|ExecuteAsync|queryForObject|createNativeQuery)\\s*\\()");
    private static final Pattern DIRECT=Pattern.compile("(?is)\\b(new\\s+(?:SqlCommand|OracleCommand|MySqlCommand)|prepareStatement|createNativeQuery)\\s*\\(\\s*(@?\"(?:\\\\.|\"\"|[^\"])*\"|'(?:''|\\\\.|[^'])*')");
    private static final Pattern MAPPER=Pattern.compile("(?is)<(select|insert|update|delete)\\b([^>]*)>(.*?)</\\1\\s*>");
    private static final Pattern ATTRIBUTE=Pattern.compile("(?i)\\bid\\s*=\\s*([\"'])([^\"']{1,128})\\1");
    private static final Pattern NAMESPACE=Pattern.compile("(?i)<mapper\\b[^>]*\\bnamespace\\s*=\\s*([\"'])([^\"']{1,256})\\1");
    private static final Pattern ENTRY=Pattern.compile("(?m)(?:\\[(?:HttpGet|HttpPost|HttpPut|HttpDelete|Route)(?:\\([^\\]]*\\))?\\]|@(?:GetMapping|PostMapping|PutMapping|DeleteMapping|RequestMapping)(?:\\([^)]*\\))?)\\s*$");
    private record Container(String kind,String name,int start,int end,int declarationOffset,String accessName,int accessOffset,String entryName,int entryOffset) {}
    private record Mask(String declarations,String commentsRemoved) {}
    private record Lines(int[] breaks) {
        int at(int offset){int n=Arrays.binarySearch(breaks,offset);return n>=0?n+2:-n;}
    }
    private static Lines lines(String text){int[] tmp=new int[Math.max(16,text.length()/48+1)];int count=0;
        for(int i=0;i<text.length();i++)if(text.charAt(i)=='\n'){if(count==tmp.length)tmp=Arrays.copyOf(tmp,tmp.length*2);tmp[count++]=i;}
        return new Lines(Arrays.copyOf(tmp,count));
    }
    /** 同时生成“去注释原文”和“去注释及字面量的声明文本”，偏移长度保持一致。 */
    private static Mask mask(String text){
        char[] declarations=text.toCharArray(),comments=text.toCharArray();int state=0;boolean verbatim=false;
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i),next=i+1<text.length()?text.charAt(i+1):0;
            if(state==1){if(c=='\n')state=0;else{declarations[i]=' ';comments[i]=' ';}continue;}
            if(state==2){if(c=='*'&&next=='/'){declarations[i]=comments[i]=' ';i++;declarations[i]=comments[i]=' ';state=0;}
                else if(c!='\n')declarations[i]=comments[i]=' ';continue;}
            if(state==3||state==4){
                if(c!='\n')declarations[i]=' ';
                if(state==3&&c=='"'){
                    if(verbatim&&next=='"'){i++;declarations[i]=' ';continue;}
                    state=0;verbatim=false;
                }else if(state==4&&c=='\'')state=0;
                else if(!verbatim&&c=='\\'&&next!=0){i++;if(text.charAt(i)!='\n')declarations[i]=' ';}
                continue;
            }
            if(c=='/'&&next=='/'){declarations[i]=comments[i]=' ';i++;declarations[i]=comments[i]=' ';state=1;continue;}
            if(c=='/'&&next=='*'){declarations[i]=comments[i]=' ';i++;declarations[i]=comments[i]=' ';state=2;continue;}
            if(c=='"'){declarations[i]=' ';state=3;verbatim=i>0&&text.charAt(i-1)=='@';continue;}
            if(c=='\''){declarations[i]=' ';state=4;continue;}
        }
        return new Mask(new String(declarations),new String(comments));
    }
    static String withoutCodeComments(String text){return mask(text).commentsRemoved();}
    private static List<Container> methods(String original,String declarations,BooleanSupplier cancelled,long deadline){
        List<Container> found=new ArrayList<>();Matcher matcher=METHOD.matcher(declarations);
        while(matcher.find()&&found.size()<5000){
            CodeDatabaseAssociation.active(cancelled,deadline);
            int open=matcher.end()-1,depth=1,end=open+1;
            while(end<declarations.length()&&depth>0){
                if((end&8191)==0)CodeDatabaseAssociation.active(cancelled,deadline);
                char c=declarations.charAt(end++);if(c=='{')depth++;else if(c=='}')depth--;}
            if(depth!=0)continue;
            String name=matcher.group(1);if(Set.of("if","for","while","switch","catch","using","lock").contains(name))continue;
            String body=declarations.substring(open,end);Matcher access=ACCESS.matcher(body);
            String accessName=null;int accessOffset=-1;if(access.find()){accessName=access.group();accessOffset=open+access.start();}
            // 仅把紧邻方法声明的路由注解视为入口声明，不从命名约定猜测请求入口。
            int previous=Math.max(0,matcher.start()-320),from=matcher.start();
            String prefix=original.substring(previous,from);Matcher entry=ENTRY.matcher(prefix);
            String entryName=null;int entryOffset=-1;
            if(entry.find()&&prefix.substring(entry.end()).isBlank()){entryName=entry.group().replaceAll("\\(.*?\\)","").trim();entryOffset=previous+entry.start();}
            found.add(new Container("CODE_METHOD",name,open,end,matcher.start(),accessName,accessOffset,entryName,entryOffset));
        }
        return found;
    }
    private static List<Container> mapperStatements(String text,BooleanSupplier cancelled,long deadline){
        List<Container> found=new ArrayList<>();Matcher ns=NAMESPACE.matcher(text);String namespace=ns.find()?ns.group(2):null;
        Matcher m=MAPPER.matcher(text);
        while(m.find()&&found.size()<5000){CodeDatabaseAssociation.active(cancelled,deadline);Matcher id=ATTRIBUTE.matcher(m.group(2));if(!id.find())continue;
            String name=(namespace==null?"":namespace+".")+id.group(2);
            found.add(new Container("MYBATIS_STATEMENT",name,m.start(3),m.end(3),m.start(),"MyBatis statement",m.start(),null,-1));
        }
        return found;
    }
    static List<AccessPath> analyze(Map<String,SourceText> sources,List<Link> links,BooleanSupplier cancelled,long deadline){
        Map<String,List<Link>> bySource=new LinkedHashMap<>();for(Link l:links)bySource.computeIfAbsent(l.sourcePath(),k->new ArrayList<>()).add(l);
        List<AccessPath> result=new ArrayList<>();
        for(var entry:bySource.entrySet()){
            CodeDatabaseAssociation.active(cancelled,deadline);
            SourceText source=sources.get(entry.getKey());if(source==null)continue;
            String text=source.text(),path=source.path().toLowerCase(Locale.ROOT);Lines lineIndex=lines(text);
            List<Container> containers=path.endsWith(".xml")?mapperStatements(text,cancelled,deadline):
                    path.endsWith(".cs")||path.endsWith(".java")||path.endsWith(".kt")?methods(text,mask(text).declarations(),cancelled,deadline):List.of();
            for(Link link:entry.getValue()){
                CodeDatabaseAssociation.active(cancelled,deadline);
                List<Step> steps=new ArrayList<>();List<String> unknown=new ArrayList<>();
                int offset=link.characterOffset()==null?-1:link.characterOffset();Container owner=null;
                for(Container c:containers)if(offset>=c.start()&&offset<c.end()&&(owner==null||c.end()-c.start()<owner.end()-owner.start()))owner=c;
                if(owner!=null){
                    if(owner.entryOffset()>=0)steps.add(step(source,"ENTRY_DECLARATION",owner.entryName(),owner.entryOffset(),lineIndex));
                    steps.add(step(source,owner.kind(),owner.name(),owner.declarationOffset(),lineIndex));
                    boolean direct=false;String accessName=owner.accessName();int accessOffset=owner.accessOffset();
                    if("CODE_METHOD".equals(owner.kind())){
                        // 只有 SQL 线索落在数据访问 API 的第一个字面量实参内，才建立直接语法边。
                        Matcher directCall=DIRECT.matcher(text.substring(owner.start(),owner.end()));
                        while(directCall.find())if(offset>=owner.start()+directCall.start(2)&&offset<owner.start()+directCall.end(2)){
                            direct=true;accessName=directCall.group(1);accessOffset=owner.start()+directCall.start();break;
                        }
                    }
                    if(accessOffset>=0){steps.add(step(source,"DATA_ACCESS_SYNTAX",accessName,accessOffset,lineIndex));
                        if("CODE_METHOD".equals(owner.kind())&&!direct)unknown.add("SQL_TO_INVOCATION_NOT_PROVEN");
                    }else unknown.add("DATA_ACCESS_CALL_UNRESOLVED");
                }else{
                    steps.add(step(source,path.endsWith(".sql")?"SQL_FILE":"UNRESOLVED_CONTAINER",source.path(),Math.max(0,offset),lineIndex));
                    unknown.add("METHOD_OR_STATEMENT_UNRESOLVED");
                }
                steps.add(new Step("SQL_REFERENCE",link.operation()+" "+link.token(),link.line(),link.evidenceId()));
                if(link.objectEvidenceId()!=null)steps.add(new Step("DATABASE_OBJECT",link.databaseObject(),0,link.objectEvidenceId()));
                else unknown.add("DATABASE_OBJECT_UNRESOLVED");
                unknown.add("RUNTIME_EXECUTION_UNVERIFIED");
                boolean directArgument=owner!=null&&"CODE_METHOD".equals(owner.kind())&&
                        steps.stream().anyMatch(x->x.kind().equals("DATA_ACCESS_SYNTAX"))&&!unknown.contains("SQL_TO_INVOCATION_NOT_PROVEN");
                String state=owner==null?"UNRESOLVED_CONTAINER":owner.kind().equals("MYBATIS_STATEMENT")?"MAPPER_STATEMENT_CANDIDATE":
                        directArgument?"DIRECT_ARGUMENT_CANDIDATE":owner.accessOffset()>=0?"SAME_METHOD_CANDIDATE":"METHOD_SQL_CANDIDATE";
                result.add(new AccessPath(Json.hashParts(source.hash(),link.evidenceId(),steps),source.path(),source.hash(),steps,state,unknown));
            }
        }
        return List.copyOf(result);
    }
    private static Step step(SourceText source,String kind,String name,int offset,Lines lines){
        return new Step(kind,name,lines.at(offset),Json.hashParts(source.hash(),kind,name,offset));
    }
}
