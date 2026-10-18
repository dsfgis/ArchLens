package io.archlens.agent;

import io.archlens.contract.*;
import io.archlens.parser.SourceText;
import io.archlens.investigation.InvestigationRequest.Profile;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import static io.archlens.contract.ContractException.require;

/** Local declaration inventory. Only allowlisted products and literal versions leave the host. */
public final class RepositoryInspector {
    public record Signal(String product,String version,String kind,String path,String sourceHash) {}
    public record Inventory(List<Signal> signals,Map<String,String> sourceHashes,List<String> gaps) {
        public Inventory { signals=List.copyOf(signals);sourceHashes=Map.copyOf(sourceHashes);gaps=List.copyOf(gaps); }
        public boolean supports(Profile profile) {
            return profile!=null && (profile.version()==null || signals.stream().filter(s->sameProduct(s.product(),profile.product())&&s.version()!=null).map(Signal::version).distinct().count()<=1) && signals.stream().anyMatch(s->sameProduct(s.product(),profile.product())
                    && (profile.version()==null || profile.version().equals(s.version())));
        }
        public Object projection() {
            var profiles=new LinkedHashMap<String,Map<String,Object>>();
            for(var s:signals) {
                var p=new LinkedHashMap<String,Object>();p.put("product",s.product());p.put("version",s.version());p.put("kind",s.kind());
                profiles.putIfAbsent(Json.canonical(p),p);
            }
            return Map.of("profiles",profiles.values().stream().limit(100).toList(),"profilesTruncated",profiles.size()>100,
                    "sourceCount",sourceHashes.size(),"gapCodes",gaps,"status","DECLARATIONS_ONLY");
        }
    }
    public static boolean sameProduct(String a,String b){return normalized(a).equals(normalized(b));}
    private static String normalized(String s){return switch(s.toLowerCase(Locale.ROOT).replace(" ","")){
        case "jdk","java"->"java";case "springboot","spring-boot"->"springboot";case "dotnet",".net"->".net";case "csharp","c#"->"c#";default->s.toLowerCase(Locale.ROOT);};}
    public static Inventory inspect(Path root,AgentContracts.Request request,BooleanSupplier cancelled,long deadline)throws Exception {
        var signals=new ArrayList<Signal>();var hashes=new TreeMap<String,String>();var gaps=new TreeSet<String>();long bytes=0;int count=0;
        for(String file:new TreeSet<>(request.files())) {
            require(!cancelled.getAsBoolean(),"AGENT_CANCELLED","Investigation cancelled");
            require(System.nanoTime()<deadline,"AGENT_TIME_BUDGET","Repository inspection timed out");
            if(++count>request.collectionBudget().maxFiles()){gaps.add("FILE_BUDGET");break;}
            require(root!=null,"SOURCE_ROOT_INVALID","Code requires a root");
            try {
                Path candidate=root.resolve(file).normalize().toRealPath();
                require(candidate.startsWith(root.toRealPath()),"PATH_OUTSIDE_ROOT","Source outside root");
                if(Files.size(candidate)>request.collectionBudget().maxBytes()-bytes){gaps.add("BYTE_BUDGET");break;}
                var source=SourceText.read(root,file);bytes+=source.location().byteLength();
                if(bytes>request.collectionBudget().maxBytes()){gaps.add("BYTE_BUDGET");break;}
                hashes.put(source.path(),source.hash());String name=Path.of(file).getFileName().toString().toLowerCase(Locale.ROOT);
                String language=language(name);if(language!=null)add(signals,source,language,null,"FILE_EXTENSION");
                try {
                    if(name.equals("pom.xml"))maven(signals,source);
                    else if(name.matches(".*\\.(csproj|vbproj|fsproj|props|targets)$"))dotnet(signals,source);
                    else if(name.equals("package.json"))node(signals,source);
                    else if(name.equals("go.mod")) {
                        add(signals,source,"Go",null,"MODULE_DECLARATION");
                        var m=java.util.regex.Pattern.compile("(?m)^go\\s+(\\d+(?:\\.\\d+){1,2})\\s*$").matcher(source.text());
                        if(m.find())add(signals,source,"Go",m.group(1),"MODULE_DECLARATION");
                    } else if(name.equals("pyproject.toml"))add(signals,source,"Python",null,"PROJECT_DECLARATION");
                    else if(name.equals("cargo.toml"))add(signals,source,"Rust",null,"PROJECT_DECLARATION");
                    else if(name.endsWith(".gradle")||name.endsWith(".gradle.kts")) {
                        add(signals,source,"Java",null,"BUILD_DECLARATION");gaps.add("GRADLE_VERSION_REQUIRES_RESOLUTION");
                    }
                } catch(Exception e){gaps.add("PROJECT_DECLARATION_UNRESOLVED");}
            } catch(ContractException e){if(e.code().equals("PATH_OUTSIDE_ROOT"))throw e;gaps.add(e.code());}
            catch(java.io.IOException e){gaps.add("SOURCE_UNAVAILABLE");}
        }
        gaps.add("DECLARATIONS_NOT_RUNTIME_PROOF");gaps.add("SEMANTIC_COVERAGE_DEPENDS_ON_ANALYZER");
        return new Inventory(signals,hashes,new ArrayList<>(gaps));
    }
    private static String language(String n){
        int i=n.lastIndexOf('.');String ext=i<0?"":n.substring(i+1);
        return switch(ext){case "java"->"Java";case "kt","kts"->"Kotlin";case "cs"->"C#";case "vb"->"VB.NET";case "fs","fsx"->"F#";
            case "js","jsx","mjs","cjs"->"JavaScript";case "ts","tsx"->"TypeScript";case "py"->"Python";case "go"->"Go";case "rs"->"Rust";
            case "rb"->"Ruby";case "php"->"PHP";case "c","h"->"C";case "cpp","hpp","cc"->"C++";case "swift"->"Swift";case "sql"->"SQL";default->null;};
    }
    private static void add(List<Signal> into,SourceText s,String product,String version,String kind){
        if(version!=null&&!version.matches("[0-9]{1,4}(?:\\.[0-9]{1,4}){0,3}(?:[-+][A-Za-z0-9.]{1,30})?"))version=null;
        var signal=new Signal(product,version,kind,s.path(),s.hash());if(!into.contains(signal))into.add(signal);
    }
    private static Element xml(SourceText source)throws Exception {
        var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setXIncludeAware(false);f.setExpandEntityReferences(false);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        var builder=f.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return builder.parse(new org.xml.sax.InputSource(new java.io.StringReader(source.text().replaceFirst("^\\uFEFF","")))).getDocumentElement();
    }
    private static List<Element> children(Element e,String name){
        var result=new ArrayList<Element>();if(e==null)return result;
        for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element child&&(name==null||name.equals(child.getLocalName())))result.add(child);
        return result;
    }
    private static Element child(Element e,String n){return children(e,n).stream().findFirst().orElse(null);}
    private static String value(Element e,String n){var c=child(e,n);return c==null?null:c.getTextContent().strip();}
    private static String resolve(String v,Element props){
        if(v!=null&&v.matches("\\$\\{[A-Za-z0-9_.-]+}"))return value(props,v.substring(2,v.length()-1));return v;
    }
    private static void maven(List<Signal> into,SourceText s)throws Exception {
        var project=xml(s);if(!"project".equals(project.getLocalName()))return;
        add(into,s,"Java",null,"MAVEN_DECLARATION");var props=child(project,"properties");
        for(String key:List.of("java.version","maven.compiler.release","maven.compiler.source")){
            String v=resolve(value(props,key),props);if(v!=null){if(v.startsWith("1."))v=v.substring(2);add(into,s,"Java",v,"MAVEN_DECLARATION");}
        }
        var deps=new ArrayList<Element>();var parent=child(project,"parent");if(parent!=null)deps.add(parent);
        deps.addAll(children(child(project,"dependencies"),"dependency"));
        deps.addAll(children(child(child(project,"dependencyManagement"),"dependencies"),"dependency"));
        for(var dep:deps){String group=value(dep,"groupId"),artifact=value(dep,"artifactId"),product=null;
            if("org.springframework.boot".equals(group)&&Set.of("spring-boot-starter-parent","spring-boot-dependencies","spring-boot").contains(Objects.toString(artifact,"")))product="Spring Boot";
            if("org.apache.httpcomponents".equals(group)&&"httpclient".equals(artifact))product="org.apache.httpcomponents:httpclient";
            if(product!=null)add(into,s,product,resolve(value(dep,"version"),props),"MAVEN_DECLARATION");
        }
    }
    private static void dotnet(List<Signal> into,SourceText s)throws Exception {
        var project=xml(s);if(!"Project".equals(project.getLocalName()))return;
        add(into,s,".NET",null,"PROJECT_DECLARATION");
        for(var group:children(project,"PropertyGroup")){
            if(group.hasAttribute("Condition"))continue;
            String lang=child(group,"LangVersion")!=null&&!child(group,"LangVersion").hasAttribute("Condition")?value(group,"LangVersion"):null;if(lang!=null&&s.path().toLowerCase(Locale.ROOT).endsWith(".csproj"))add(into,s,"C#",lang,"LANGUAGE_DECLARATION");
            for(var property:children(group,null)){
                if(property.hasAttribute("Condition"))continue;
                String key=property.getLocalName();
                if("UseWPF".equals(key)&&"true".equalsIgnoreCase(property.getTextContent().strip()))add(into,s,"WPF",null,"PROJECT_DECLARATION");
                if("UseWindowsForms".equals(key)&&"true".equalsIgnoreCase(property.getTextContent().strip()))add(into,s,"Windows Forms",null,"PROJECT_DECLARATION");
                if(!Set.of("TargetFramework","TargetFrameworks","TargetFrameworkVersion").contains(key))continue;
                for(String tfm:property.getTextContent().strip().split(";")){
                    if(tfm.matches("net(?:[5-9]|[1-9][0-9]+)\\.[0-9]+(?:-[A-Za-z][A-Za-z0-9.]*)?"))
                        add(into,s,".NET",tfm.substring(3).split("-",2)[0],"TARGET_FRAMEWORK_DECLARATION");
                    else if(tfm.matches("netcoreapp[0-9]+\\.[0-9]+"))add(into,s,".NET Core",tfm.substring(10),"TARGET_FRAMEWORK_DECLARATION");
                    else if(tfm.matches("v[0-9]+(?:\\.[0-9]+)+"))add(into,s,".NET Framework",tfm.substring(1),"TARGET_FRAMEWORK_DECLARATION");
                }
            }
        }
        for(var group:children(project,"ItemGroup"))for(var item:children(group,"PackageReference")) {
            if(group.hasAttribute("Condition")||item.hasAttribute("Condition"))continue;
            String packageId=item.getAttribute("Include"),product=switch(packageId){
                case "Microsoft.Data.Sqlite", "Microsoft.Data.Sqlite.Core"->"SQLite";
                case "SQLitePCLRaw.bundle_e_sqlcipher"->"SQLCipher";
                default->null;
            };
            if(product!=null)add(into,s,product,null,"PACKAGE_DECLARATION");
        }
    }
    private static void node(List<Signal> into,SourceText s)throws Exception {
        var json=Json.MAPPER.readTree(s.text());add(into,s,"JavaScript",null,"PACKAGE_DECLARATION");
        for(String kind:List.of("dependencies","devDependencies"))for(var dep:Map.of("react","React","vue","Vue","typescript","TypeScript","next","Next.js").entrySet()){
            var version=json.path(kind).path(dep.getKey());if(version.isTextual())add(into,s,dep.getValue(),version.asText(),"PACKAGE_DECLARATION");
        }
    }
    private RepositoryInspector(){}
}
