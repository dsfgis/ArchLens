package io.archlens.investigation.dotnet;

import io.archlens.contract.*;
import io.archlens.investigation.InvestigationReport.Gap;
import io.archlens.parser.SourceText;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import static io.archlens.investigation.dotnet.DotnetInventory.*;

/** 只分析已采集缓存；不读取引用路径，不执行工程求值，不导出配置正文。 */
public final class DotnetInventoryAnalyzer {
    private static final Set<String> PROPERTIES=Set.of("TargetFramework", "TargetFrameworks", "TargetFrameworkVersion",
            "TargetFrameworkIdentifier", "LangVersion", "OutputType", "UseWPF", "UseWindowsForms", "Nullable", "ProjectTypeGuids");
    private final Map<String,SourceText> sources;
    private final BooleanSupplier cancelled;
    private final long deadline;
    private final List<Gap> gaps=new ArrayList<>();
    private long lastPoll;
    private boolean polled;
    private int work;
    private DotnetInventoryAnalyzer(Map<String,SourceText> sources, BooleanSupplier cancelled,long deadline) {
        this.sources=sources;this.cancelled=cancelled;this.deadline=deadline;
    }
    public static boolean projectPath(String path) { return path.toLowerCase(Locale.ROOT).matches(".*\\.(csproj|vbproj|fsproj)$"); }
    public static boolean solutionPath(String path) { return path.toLowerCase(Locale.ROOT).matches(".*\\.(sln|slnx)$"); }
    public static DotnetInventory analyze(Map<String,SourceText> sources,int submittedFiles,BooleanSupplier cancelled,long deadline) {
        if(sources.keySet().stream().noneMatch(p->projectPath(p)||solutionPath(p)))return null;
        return new DotnetInventoryAnalyzer(sources,cancelled,deadline).analyze(submittedFiles);
    }
    private DotnetInventory analyze(int submittedFiles) {
        var projects=new ArrayList<Project>();var solutions=new ArrayList<Solution>();var support=new ArrayList<SupportFile>();
        int projectCount=0,solutionCount=0,codeCount=0;
        for(var s:sources.values().stream().sorted(Comparator.comparing(SourceText::path)).toList()) {
            check();String path=s.path().toLowerCase(Locale.ROOT), name=Path.of(path).getFileName().toString();
            if(path.matches(".*\\.(cs|vb|fs|fsx)$")){codeCount++;continue;}
            boolean project=projectPath(path),solution=solutionPath(path);
            if(project)projectCount++;if(solution)solutionCount++;
            boolean supporting=name.matches("directory\\.(build\\.(props|targets)|packages\\.props)")
                    ||Set.of("global.json","packages.config","web.config","app.config").contains(name)
                    ||name.matches("appsettings(?:\\.[a-z0-9_-]+)?\\.json");
            if(!project&&!solution&&!supporting)continue;
            try {
                if(s.text().length()>500_000)throw error("DOTNET_FILE_LIMIT");
                if(project)projects.add(project(s));
                else if(solution)solutions.add(solution(s));
                else support.add(support(s,name));
            } catch(ContractException e) {
                if(Set.of("DOTNET_CANCELLED","DOTNET_TIME_BUDGET","DOTNET_WORK_LIMIT").contains(e.code()))throw e;
                gap(e.code(),s.path());
            }
        }
        gap("DOTNET_DECLARATIONS_ONLY","Imports, conditions, SDK defaults and effective build configuration are not evaluated");
        gap("DOTNET_SEMANTIC_BINDING_UNAVAILABLE","Source code is collected but symbols, calls and runtime behavior are not bound");
        gap("DOTNET_COMPATIBILITY_UNASSESSED","Platform inventory does not establish Java or target-environment compatibility");
        check();
        return new DotnetInventory(VERSION,projects,solutions,support,
                new Coverage(submittedFiles,sources.size(),projectCount,projects.size(),solutionCount,solutions.size(),codeCount,0),gaps);
    }
    private Project project(SourceText s) {
        Element root=xml(s,"Project");var data=declarations(root,s);
        String path=s.path().toLowerCase(Locale.ROOT), language=path.endsWith(".csproj")?"C#":path.endsWith(".vbproj")?"VB.NET":"F#";
        if(language.equals("F#"))data.gaps.add("DOTNET_LANGUAGE_SEMANTICS_UNSUPPORTED");
        List<Framework> frameworks=new ArrayList<>();
        for(Declaration d:data.properties) {
            if(Set.of("TargetFramework","TargetFrameworks").contains(d.name())) {
                for(String tfm:d.value().split(";",-1)) {check();frameworks.add(framework(tfm.trim(),d.state()));}
            } else if(d.name().equals("TargetFrameworkVersion")) {
                boolean otherIdentifier=data.properties.stream().anyMatch(v->v.name().equals("TargetFrameworkIdentifier")
                        &&(!v.value().equals(".NETFramework")||!v.state().equals("DECLARED")));
                String v=d.value().replaceFirst("^[vV]","");
                frameworks.add(!otherIdentifier&&v.matches("[1-4]\\.[0-9]+(?:\\.[0-9]+)?")
                        ?new Framework(d.value(),".NET Framework",v,null,d.state())
                        :new Framework(d.value(),"UNKNOWN",null,null,"UNRESOLVED"));
            }
        }
        if(frameworks.isEmpty())data.gaps.add("DOTNET_TARGET_FRAMEWORK_UNKNOWN");
        if(frameworks.stream().anyMatch(f->f.family().equals("UNKNOWN")))data.gaps.add("DOTNET_TARGET_FRAMEWORK_UNRESOLVED");
        // 所有声明保留，不静默选择最后一项或以 SDK 推导有效目标。
        for(String property:PROPERTIES)if(data.properties.stream().filter(d->d.name().equals(property)).map(Declaration::value).distinct().count()>1)
            data.gaps.add("DOTNET_REPEATED_PROPERTY_DECLARATION");
        if(data.properties.stream().anyMatch(d->d.name().equals("TargetFramework"))&&data.properties.stream().anyMatch(d->d.name().equals("TargetFrameworks")))
            data.gaps.add("DOTNET_MULTIPLE_TARGET_PROPERTIES");
        for(String g:new LinkedHashSet<>(data.gaps))gap(g,s.path());
        return new Project(origin(s),language,root.hasAttribute("Sdk")?"SDK_STYLE_DECLARATION":"LEGACY_OR_UNSPECIFIED",
                frameworks,data.properties,new ArrayList<>(data.hints),data.dependencies,data.references,new ArrayList<>(new LinkedHashSet<>(data.gaps)));
    }
    private static final class Data {
        List<Declaration> properties=new ArrayList<>();List<Dependency> dependencies=new ArrayList<>();
        List<Reference> references=new ArrayList<>();List<String> gaps=new ArrayList<>();Set<String> hints=new TreeSet<>();
    }
    private Data declarations(Element root,SourceText s) {
        Data data=new Data();
        if(root.hasAttribute("Sdk"))data.properties.add(declaration("Sdk",root.getAttribute("Sdk"),conditional(root),data));
        for(Element group:children(root)) {
            check();String groupName=name(group);
            if(!Set.of("PropertyGroup","ItemGroup").contains(groupName)) {data.gaps.add("DOTNET_MSBUILD_UNEVALUATED");continue;}
            for(Element item:children(group)) {
                check();String kind=name(item);boolean cond=conditional(root)||conditional(group)||conditional(item);
                if(cond)data.gaps.add("DOTNET_CONDITIONAL_DECLARATION");
                if(groupName.equals("PropertyGroup")&&PROPERTIES.contains(kind))
                    data.properties.add(declaration(kind,item.getTextContent().trim(),cond,data));
                if(!groupName.equals("ItemGroup"))continue;
                if(Set.of("PackageReference","PackageVersion","Reference","FrameworkReference","COMReference").contains(kind)) {
                    String id=item.hasAttribute("Include")?item.getAttribute("Include"):item.getAttribute("Update");
                    String version=item.getAttribute("Version");
                    for(Element child:children(item))if(name(child).equals("Version")){version=child.getTextContent().trim();cond|=conditional(child);}
                    String safeId=safe(id),safeVersion=version.isBlank()?null:safe(version);
                    String state=cond?"CONDITIONAL":safeId==null||(!version.isBlank()&&safeVersion==null)?"UNRESOLVED":"DECLARED";
                    data.dependencies.add(new Dependency(kind,safeId==null?"UNRESOLVED":safeId,safeVersion,state));
                    if(!state.equals("DECLARED"))data.gaps.add("DOTNET_DEPENDENCY_UNRESOLVED");
                    if(safeId!=null)dependencyHints(safeId,data.hints);
                    if(kind.equals("COMReference"))data.hints.add("COM_REFERENCE_CANDIDATE");
                } else if(kind.equals("ProjectReference")) {
                    Reference ref=reference(s,item.getAttribute("Include"),cond);data.references.add(ref);
                    if(!ref.state().equals("COLLECTED_NOT_BOUND"))data.gaps.add("DOTNET_PROJECT_REFERENCE_UNRESOLVED");
                } else if(Set.of("Compile","Import","Analyzer").contains(kind))data.gaps.add("DOTNET_MSBUILD_UNEVALUATED");
            }
        }
        for(Declaration d:data.properties) {
            if(d.name().equals("Sdk")&&d.value().startsWith("Microsoft.NET.Sdk.Web"))data.hints.add("ASP_NET_CORE_SDK_CANDIDATE");
            if(d.name().equals("Sdk")&&d.value().startsWith("Microsoft.NET.Sdk.Worker"))data.hints.add("WORKER_SDK_CANDIDATE");
            if(d.name().equals("UseWPF")&&d.value().equalsIgnoreCase("true"))data.hints.add("WPF_DECLARATION");
            if(d.name().equals("UseWindowsForms")&&d.value().equalsIgnoreCase("true"))data.hints.add("WINFORMS_DECLARATION");
            if(d.name().equals("ProjectTypeGuids")&&d.value().toLowerCase(Locale.ROOT).contains("349c5851-65df-11da-9384-00065b846f21"))data.hints.add("ASP_NET_WEB_PROJECT_CANDIDATE");
        }
        return data;
    }
    private Declaration declaration(String name,String value,boolean conditional,Data data) {
        String literal=safe(value);String state=conditional?"CONDITIONAL":literal==null?"UNRESOLVED":"DECLARED";
        if(!state.equals("DECLARED"))data.gaps.add("DOTNET_PROPERTY_UNEVALUATED");
        return new Declaration(name,literal==null?"UNRESOLVED":literal,state);
    }
    private static String safe(String value) {
        // 只导出技术声明字面值；属性表达式、路径、URL 和正文不直接写入报告。
        return value!=null&&!value.isBlank()&&value.length()<=300&&value.matches("[A-Za-z0-9_.+;,{}()\\[\\] *=-]+")?value:null;
    }
    private static Framework framework(String tfm,String state) {
        var legacy=Pattern.compile("net(11|20|30|35|4[0-8][0-9]?)").matcher(tfm);
        if(legacy.matches())return new Framework(tfm,".NET Framework",String.join(".",legacy.group(1).split("")),null,state);
        var core=Pattern.compile("netcoreapp([1-3]\\.[0-9]+)").matcher(tfm);
        if(core.matches())return new Framework(tfm,".NET Core",core.group(1),null,state);
        var modern=Pattern.compile("net((?:[5-9]|[1-9][0-9]+)\\.[0-9]+)(?:-([a-z0-9.-]+))?").matcher(tfm);
        if(modern.matches())return new Framework(tfm,".NET",modern.group(1),modern.group(2),state);
        var standard=Pattern.compile("netstandard(1\\.[0-6]|2\\.[01])").matcher(tfm);
        if(standard.matches())return new Framework(tfm,".NET Standard",standard.group(1),null,state);
        return new Framework(tfm,"UNKNOWN",null,null,"UNRESOLVED");
    }
    private static void dependencyHints(String name,Set<String> hints) {
        String id=name.split(",",2)[0];
        if(id.equals("System.Web")||id.startsWith("Microsoft.AspNet.Mvc"))hints.add("ASP_NET_FRAMEWORK_CANDIDATE");
        if(id.equals("System.ServiceModel")||id.startsWith("System.ServiceModel."))hints.add("WCF_DEPENDENCY_CANDIDATE");
        if(id.equals("EntityFramework"))hints.add("EF6_DEPENDENCY_CANDIDATE");
        if(id.startsWith("Microsoft.EntityFrameworkCore"))hints.add("EF_CORE_DEPENDENCY_CANDIDATE");
        if(id.equals("Dapper"))hints.add("DAPPER_DEPENDENCY_CANDIDATE");
        if(id.equals("System.ServiceProcess"))hints.add("WINDOWS_SERVICE_DEPENDENCY_CANDIDATE");
    }
    private Reference reference(SourceText s,String value,boolean conditional) {
        String shown=value.length()>500?"UNRESOLVED":value;
        String normalized=null;
        try {
            if(value.isBlank()||value.matches(".*[:$*?;@%].*")||value.contains("\n"))throw new IllegalArgumentException();
            Path relative=Path.of(value.replace('\\','/'));
            if(relative.isAbsolute())throw new IllegalArgumentException();
            Path p=Path.of(s.path()).resolveSibling(relative).normalize();
            if(p.startsWith(".."))throw new IllegalArgumentException();
            normalized=p.toString().replace('\\','/');
        }catch(Exception ignored){ /* 不跟随非法、目录外或动态引用。 */ }
        String state=conditional?"CONDITIONAL":normalized==null?"UNRESOLVED":sources.containsKey(normalized)&&projectPath(normalized)?"COLLECTED_NOT_BOUND":"UNCOLLECTED";
        return new Reference(shown,normalized,state);
    }
    private Solution solution(SourceText s) {
        var refs=new ArrayList<Reference>();
        if(s.path().toLowerCase(Locale.ROOT).endsWith(".slnx"))solutionElements(xml(s,"Solution"),s,refs,0);
        else {
            if(!s.text().contains("Microsoft Visual Studio Solution File"))throw error("DOTNET_SOLUTION_REJECTED");
            Pattern line=Pattern.compile("^\\s*Project\\(\"[^\"]+\"\\)\\s*=\\s*\"[^\"]*\",\\s*\"([^\"]+)\",.*$");
            for(String row:s.text().split("\\R")) {
                check();var match=line.matcher(row);
                if(match.matches()&&projectPath(match.group(1)))refs.add(reference(s,match.group(1),false));
                else if(row.stripLeading().startsWith("Project("))gap("DOTNET_SOLUTION_ENTRY_UNSUPPORTED",s.path());
            }
        }
        for(Reference ref:refs)if(!ref.state().equals("COLLECTED_NOT_BOUND"))gap("DOTNET_SOLUTION_PROJECT_UNCOLLECTED",s.path());
        gap("DOTNET_SOLUTION_CONFIG_UNEVALUATED",s.path());
        return new Solution(origin(s),refs);
    }
    private void solutionElements(Element root,SourceText s,List<Reference> refs,int depth) {
        if(depth>20)throw error("DOTNET_XML_DEPTH_LIMIT");
        for(Element e:children(root)) {
            check();if(name(e).equals("Project"))refs.add(reference(s,e.getAttribute("Path"),conditional(e)));
            else if(name(e).equals("Folder"))solutionElements(e,s,refs,depth+1);
            else gap("DOTNET_SOLUTION_ENTRY_UNSUPPORTED",s.path());
        }
    }
    private SupportFile support(SourceText s,String name) {
        var declarations=new ArrayList<Declaration>();var dependencies=new ArrayList<Dependency>();var hints=new TreeSet<String>();
        String kind;
        if(name.equals("global.json")||name.startsWith("appsettings")) {
            com.fasterxml.jackson.databind.JsonNode tree;
            try {tree=Json.MAPPER.readTree(s.text());if(tree==null||!tree.isObject())throw error("DOTNET_JSON_REJECTED");}
            catch(Exception e){throw error("DOTNET_JSON_REJECTED");}
            kind=name.equals("global.json")?"SDK_CONFIGURATION":"APPLICATION_CONFIGURATION";
            if(name.equals("global.json")) {
                String version=tree.path("sdk").path("version").asText("");
                declarations.add(new Declaration("SdkVersion",version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")?version:"UNKNOWN","DECLARED_NOT_RESOLVED"));
            }
            gap("DOTNET_CONFIG_VALUES_WITHHELD",s.path());
        } else if(name.equals("packages.config")) {
            kind="LEGACY_PACKAGE_DECLARATIONS";
            for(Element e:children(xml(s,"packages"))) {
                check();if(!name(e).equals("package"))continue;
                String id=safe(e.getAttribute("id")),version=safe(e.getAttribute("version"));
                dependencies.add(new Dependency("packages.config",id==null?"UNRESOLVED":id,version,"DECLARED_NOT_RESOLVED"));
            }
            gap("DOTNET_PACKAGE_RESOLUTION_UNAVAILABLE",s.path());
        } else if(name.endsWith(".config")) {
            kind="APPLICATION_CONFIGURATION";
            for(Element e:children(xml(s,"configuration"))) {
                check();if(name(e).equals("system.web"))hints.add("ASP_NET_FRAMEWORK_CONFIGURATION");
                if(name(e).equals("system.serviceModel"))hints.add("WCF_CONFIGURATION");
            }
            gap("DOTNET_CONFIG_VALUES_WITHHELD",s.path());
        } else {
            kind="SHARED_BUILD_DECLARATIONS";var data=declarations(xml(s,"Project"),s);
            declarations.addAll(data.properties);dependencies.addAll(data.dependencies);hints.addAll(data.hints);
            for(String g:data.gaps)gap(g,s.path());
            gap("DOTNET_SHARED_PROPERTIES_NOT_APPLIED",s.path());
        }
        return new SupportFile(origin(s),kind,declarations,dependencies,new ArrayList<>(hints));
    }
    private Element xml(SourceText s,String expected) {
        try {
            var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setXIncludeAware(false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setFeature("http://xml.org/sax/features/external-general-entities",false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var b=f.newDocumentBuilder();b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            Element e=b.parse(new InputSource(new StringReader(s.text()))).getDocumentElement();
            if(!name(e).equals(expected)||e.getNamespaceURI()!=null&&!e.getNamespaceURI().equals("http://schemas.microsoft.com/developer/msbuild/2003"))throw error("DOTNET_XML_REJECTED");
            return e;
        }catch(Exception e){throw error("DOTNET_XML_REJECTED");}
    }
    private static Origin origin(SourceText s){return new Origin(s.path(),Json.hashParts(s.path(),s.hash()),s.hash(),"WHOLE_FILE");}
    private static String name(Element e){return e.getLocalName()==null?e.getTagName():e.getLocalName();}
    private static boolean conditional(Element e){return e.hasAttribute("Condition");}
    private List<Element> children(Element e) {
        var result=new ArrayList<Element>();var nodes=e.getChildNodes();
        for(int i=0;i<nodes.getLength();i++){check();if(nodes.item(i) instanceof Element child&&Objects.equals(e.getNamespaceURI(),child.getNamespaceURI()))result.add(child);}
        return result;
    }
    private void gap(String code,String source){Gap gap=new Gap(code,source);if(!gaps.contains(gap))gaps.add(gap);}
    private void check() {
        if(++work>50000)throw error("DOTNET_WORK_LIMIT");long now=System.nanoTime();
        if(now>=deadline)throw error("DOTNET_TIME_BUDGET");
        if(!polled||now-lastPoll>=100_000_000L){polled=true;lastPoll=now;if(cancelled.getAsBoolean())throw error("DOTNET_CANCELLED");}
    }
    private static ContractException error(String code){return new ContractException(code,".NET inventory could not cover the declared source");}
}
