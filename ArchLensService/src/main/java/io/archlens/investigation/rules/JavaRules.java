package io.archlens.investigation.rules;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import io.archlens.contract.*;
import io.archlens.parser.SourceText;
import io.archlens.investigation.InvestigationRequest;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import static io.archlens.investigation.InvestigationReport.*;
import static io.archlens.investigation.rules.ScenarioRules.*;

/** 基于 JavaParser AST 的导入调查与显式前后对比；不编译被分析项目，不解析外部依赖。 */
final class JavaRules {
    private static CompilationUnit parse(SourceText s) {
        if(s.text().length()>200_000)throw Lexical.error("JAVA_RULE_FILE_LIMIT");
        // Java Unicode 预处理会改变原文偏移；未实现映射时停止分析，不能输出错误证据位置。
        if(s.text().contains("\\u"))throw Lexical.error("JAVA_UNICODE_ESCAPE_UNSUPPORTED");
        var parser=new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21).setTabSize(1));
        var result=parser.parse(s.text());if(!result.isSuccessful()||result.getResult().isEmpty())throw Lexical.error("JAVA_PARSE_FAILED");
        return result.getResult().orElseThrow();
    }
    private static int offset(String text,Position p) {
        int i=0,line=1;
        while(line<p.line&&i<text.length()) {
            char ch=text.charAt(i++);
            if(ch=='\r'){if(i<text.length()&&text.charAt(i)=='\n')i++;line++;}else if(ch=='\n')line++;
        }
        return Math.min(text.length(),i+p.column-1);
    }
    private static Evidence evidence(Context c,SourceText s,Node n,String kind) {
        var range=n.getRange().orElseThrow();return c.evidence(s,offset(s.text(),range.begin),Math.min(s.text().length(),offset(s.text(),range.end)+1),kind);
    }
    static void upgrade(SourceText s,Family family,Context c) {
        var unit=parse(s);
        for(var imp:unit.getImports()) {
            c.check();String name=imp.getNameAsString();String rule=null,summary=null,advice=null,outcome="CONDITIONAL";
            if(family==Family.BOOT&&List.of("javax.servlet","javax.persistence","javax.validation").stream().anyMatch(p->name.equals(p)||name.startsWith(p+"."))) {
                rule="BOOT_JAKARTA";outcome="INCOMPATIBLE";summary="该 Java EE 导入与 Spring Boot 3 的 Jakarta API 命名空间不一致。";
                advice="按实际 API 改为 Jakarta 依赖及导入，验证容器/JPA/校验集成；不要全局替换 javax.*，JDK 的 javax.xml 等不在此规则内。";
            } else if(family==Family.JDK&&(name.equals("javax.xml.bind")||name.startsWith("javax.xml.bind."))) {
                rule="JDK_JAXB";summary="目标 JDK 不再内置 JAXB；发现 JAXB 导入。";advice="核对显式 JAXB API/实现依赖及模块设置，再验证 XML 往返；依赖可能已提供，不能仅凭导入判定编译失败。";
            } else if(family==Family.HTTPCLIENT&&(name.equals("org.apache.http")||name.startsWith("org.apache.http."))) {
                rule="HTTPCLIENT_NAMESPACE";summary="发现 HttpComponents 4 命名空间，HttpClient 5 使用独立命名空间。";advice="若替换而非并存，逐 API 迁移到 org.apache.hc，并验证超时、连接池、TLS 和响应资源关闭。";
            }
            if(rule!=null)c.add(rule,outcome,name,summary,List.of(evidence(c,s,imp,"JAVA_IMPORT_AST")),
                    List.of("按请求声明的升级目标评估；有效依赖图、类路径及旧新依赖并存情况未解析"),List.of(),List.of(advice),
                    List.of(new Impact(name,"CONFIRMED_IMPORT",outcome.equals("INCOMPATIBLE")?"YES":"UNKNOWN","声明处直接影响；下游调用与运行行为未推断")));
        }
        c.gap("JAVA_UPGRADE_IMPORTS_ONLY",s.path()+": fully qualified uses, API overloads, reflection, config and transitive dependencies unchecked");
    }
    static void maven(SourceText s,InvestigationRequest request,Context c) {
        // 仅读取直接 XML 声明；禁用 DTD/外部实体，不执行 Maven、不下载父 POM 或扩展。
        Element root;
        try {
            var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setFeature("http://xml.org/sax/features/external-general-entities",false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var b=f.newDocumentBuilder();b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            root=b.parse(new InputSource(new StringReader(s.text()))).getDocumentElement();
        } catch(Exception e){throw Lexical.error("MAVEN_XML_UNSUPPORTED");}
        if(!local(root).equals("project")||root.getNamespaceURI()!=null&&!root.getNamespaceURI().equals("http://maven.apache.org/POM/4.0.0"))throw Lexical.error("MAVEN_XML_UNSUPPORTED");
        var evidence=List.of(c.evidence(s,0,s.text().length(),"MAVEN_DIRECT_XML"));
        Element parent=child(root,"parent");
        if(parent!=null&&"org.springframework.boot".equals(value(parent,"groupId"))&&"spring-boot-starter-parent".equals(value(parent,"artifactId"))) {
            String v=value(parent,"version");
            if(v==null||!v.matches("[0-9]+(?:\\.[0-9]+)*"))c.gap("MAVEN_VERSION_UNRESOLVED",s.path());
            else if(!version(v,"2.7") || request.sourceProfile().version().split("\\.").length>=3&&!v.equals(request.sourceProfile().version()))
                c.gap("PROFILE_SOURCE_VERSION_CONFLICT",s.path());
        }
        Element properties=child(root,"properties");
        if(properties!=null)for(String property:List.of("java.version","maven.compiler.release")) {
            String value=value(properties,property);if(value==null)continue;
            if(!value.matches("(?:1\\.)?[0-9]{1,2}")){c.gap("MAVEN_PROPERTY_UNRESOLVED",s.path()+":"+property);continue;}
            int jdk=Integer.parseInt(value.startsWith("1.")?value.substring(2):value);
            if(jdk<17)c.add("BOOT_JAVA17","CONDITIONAL",s.path()+":"+property,"Maven 的 "+property+" 声明低于 Spring Boot 3 的 Java 17 基线。",evidence,
                    List.of("需确认该属性是否进入有效编译/运行配置；未执行 Maven 或解析父 POM/profiles"),List.of(),
                    List.of("核对 effective POM、CI JDK、编译 release 和部署运行时，统一升级后验证。"),List.of(new Impact(property,"CONFIRMED_LITERAL","UNKNOWN","静态属性不等于实际运行 JDK")));
        }
        Element deps=child(root,"dependencies");
        if(deps!=null)for(Element dep:children(deps,"dependency")) {
            if("javax.servlet".equals(value(dep,"groupId"))&&"javax.servlet-api".equals(value(dep,"artifactId")))
                c.add("BOOT_SERVLET_DEPENDENCY","CONDITIONAL",s.path()+":javax.servlet:javax.servlet-api","直接依赖仍声明旧 Servlet API 坐标。",evidence,
                        List.of("应确认依赖用途、scope、排除项和实际容器；旧新 API 可并存但不能相互替代"),List.of(),
                        List.of("按 Spring Boot 3 的依赖管理评估 jakarta.servlet:jakarta.servlet-api，检查过滤器、监听器及容器集成。"),
                        List.of(new Impact("javax.servlet:javax.servlet-api","CONFIRMED_DECLARATION","UNKNOWN","有效依赖解析未执行")));
        }
        c.gap("MAVEN_EFFECTIVE_MODEL_UNRESOLVED",s.path());
    }
    private static String local(Element e){return e.getLocalName()==null?e.getTagName():e.getLocalName();}
    private static List<Element> children(Element e,String name) {
        List<Element> out=new ArrayList<>();var nodes=e.getChildNodes();
        for(int i=0;i<nodes.getLength();i++)if(nodes.item(i) instanceof Element el&&local(el).equals(name)&&Objects.equals(e.getNamespaceURI(),el.getNamespaceURI()))out.add(el);
        return out;
    }
    private static Element child(Element e,String name){var list=children(e,name);if(list.size()>1)throw Lexical.error("MAVEN_AMBIGUOUS_ELEMENT");return list.isEmpty()?null:list.getFirst();}
    private static String value(Element e,String name){Element ch=child(e,name);return ch==null?null:ch.getTextContent().trim();}

    public record Pair(String before,String after) {}
    public record Plan(String schemaVersion,List<Pair> pairs) {}
    static void refactor(Map<String,SourceText> sources,Context c) {
        // 前后文件必须来自本次已采集的明确清单。计划中的路径不触发额外文件读取。
        var plans=sources.values().stream().filter(s->s.path().endsWith(".refactor.json")).toList();
        if(plans.size()!=1){c.gap("REFACTOR_PLAN_REQUIRED","Exactly one explicitly listed *.refactor.json is required");return;}
        SourceText planSource=plans.getFirst();Plan plan;
        try {plan=Json.MAPPER.readValue(planSource.text(),Plan.class);}catch(Exception e){c.gap("REFACTOR_PLAN_INVALID",planSource.path());return;}
        if(!"archlens.refactor-plan.v1".equals(plan.schemaVersion())||plan.pairs()==null||plan.pairs().isEmpty()||plan.pairs().size()>20){c.gap("REFACTOR_PLAN_INVALID",planSource.path());return;}
        Set<String> beforeNames=new HashSet<>(),afterNames=new HashSet<>();
        for(Pair pair:plan.pairs()) {
            if(pair==null||!safePath(pair.before())||!safePath(pair.after())||pair.before().equals(pair.after())||
                    !beforeNames.add(pair.before())||!afterNames.add(pair.after())){c.gap("REFACTOR_PLAN_INVALID",planSource.path());return;}
        }
        Set<String> overlap=new HashSet<>(beforeNames);overlap.retainAll(afterNames);
        if(!overlap.isEmpty()){c.gap("REFACTOR_PLAN_INVALID",planSource.path());return;}
        for(Pair pair:plan.pairs()) {
            c.check();SourceText before=sources.get(pair.before()),after=sources.get(pair.after());
            if(before==null||after==null){c.gap("REFACTOR_PAIR_SOURCE_MISSING",pair.before()+" -> "+pair.after());continue;}
            int count=c.findings.size();
            try {compare(before,after,planSource,c);}
            catch(ContractException e) {
                while(c.findings.size()>count)c.findings.removeLast();
                if(Set.of("RULE_CANCELLED","RULE_TIME_BUDGET","RULE_FINDING_LIMIT").contains(e.code()))throw e;
                c.gap(e.code(),pair.before()+" -> "+pair.after());
            }
        }
        c.gap("REFACTOR_BINDING_LIMITED","Explicit pairs only; no symbol resolver, hierarchy, constructors, module graph, reflection or external consumers");
    }
    private static boolean safePath(String path) {
        try{return path!=null&&!path.isBlank()&&!path.contains(":")&&!path.contains("\\")&&!Path.of(path).isAbsolute()
                &&!Path.of(path).normalize().startsWith("..")&&Path.of(path).normalize().toString().replace('\\','/').equals(path);}
        catch(Exception e){return false;}
    }
    private static ClassOrInterfaceDeclaration flat(CompilationUnit unit) {
        // 没有符号求解器时，继承/泛型/嵌套类不能使用“方法消失即 API 删除”的简化规则。
        if(unit.getTypes().size()!=1||!(unit.getType(0) instanceof ClassOrInterfaceDeclaration type)||type.isInterface()
                ||!type.isPublic()||!type.getExtendedTypes().isEmpty()||!type.getImplementedTypes().isEmpty()
                ||!type.getTypeParameters().isEmpty()||type.getMembers().stream().anyMatch(m->m instanceof TypeDeclaration<?>))throw Lexical.error("REFACTOR_CLASS_SHAPE_UNSUPPORTED");
        return type;
    }
    private static String owner(CompilationUnit u,ClassOrInterfaceDeclaration t){return u.getPackageDeclaration().map(p->p.getNameAsString()+".").orElse("")+t.getNameAsString();}
    private static boolean primitive(com.github.javaparser.ast.type.Type t){return t.isPrimitiveType()||t.isVoidType()||t.isArrayType()&&primitive(t.asArrayType().getComponentType());}
    private static String key(MethodDeclaration m){return m.getNameAsString()+"("+String.join(",",m.getParameters().stream().map(p->p.getType().asString()+(p.isVarArgs()?"[]":"")).toList())+")";}
    private static boolean supported(MethodDeclaration m){return primitive(m.getType())&&m.getParameters().stream().allMatch(p->primitive(p.getType()))&&m.getTypeParameters().isEmpty();}
    private static String ast(Node node){Node copy=node.clone();copy.getAllContainedComments().forEach(Node::remove);copy.removeComment();return copy.toString();}
    private static void compare(SourceText before,SourceText after,SourceText plan,Context c) {
        var b=parse(before);var a=parse(after);var bt=flat(b);var at=flat(a);String oldOwner=owner(b,bt),newOwner=owner(a,at);
        var pairEvidence=List.of(evidence(c,before,bt.getName(),"JAVA_TYPE_AST"),evidence(c,after,at.getName(),"JAVA_TYPE_AST"),c.evidence(plan,0,plan.text().length(),"EXPLICIT_COMPARISON_PLAN"));
        if(!oldOwner.equals(newOwner)) {
            c.add("JAVA_API_CHANGE","UNKNOWN",oldOwner,"公共类型全名发生变化："+oldOwner+" → "+newOwner,pairEvidence,
                    List.of("旧类是否仍由其他文件或兼容门面提供未知"),List.of("Explicit pair does not establish removal of the old class from the whole project"),
                    List.of("核对包/模块迁移、外部消费者、反射类名、序列化类型名和兼容门面。"),List.of(new Impact(oldOwner,"CONFIRMED_DECLARATION_CHANGE","UNKNOWN","全项目是否保留旧类型未核实")));
            return;
        }
        Map<String,MethodDeclaration> target=new LinkedHashMap<>();
        for(var m:at.getMethods())if(target.put(key(m),m)!=null)throw Lexical.error("REFACTOR_AMBIGUOUS_METHOD");
        Set<String> oldKeys=new HashSet<>();
        for(var old:bt.getMethods()) {
            c.check();if(!oldKeys.add(key(old)))throw Lexical.error("REFACTOR_AMBIGUOUS_METHOD");
            if(!old.isPublic()&&!old.isProtected())continue;
            if(Set.of("hashCode","toString","equals","clone","finalize","wait","notify","notifyAll","getClass").contains(old.getNameAsString())) {
                c.gap("REFACTOR_INHERITED_OBJECT_METHOD",before.path()+":"+key(old));continue;
            }
            if(!supported(old)){c.gap("REFACTOR_SIGNATURE_UNRESOLVED",before.path()+":"+key(old));continue;}
            var next=target.get(key(old));String outcome,summary;List<String> unknown=List.of();
            List<Evidence> ev=new ArrayList<>(pairEvidence);ev.add(evidence(c,before,old.getName(),"JAVA_METHOD_AST"));
            if(next!=null)ev.add(evidence(c,after,next.getName(),"JAVA_METHOD_AST"));
            boolean breaking=next==null||!next.getType().asString().equals(old.getType().asString())||next.isStatic()!=old.isStatic()
                    ||old.isPublic()&&!next.isPublic()||old.isProtected()&&!next.isPublic()&&!next.isProtected();
            if(breaking){outcome="INCOMPATIBLE";summary="原公共/受保护方法描述符被删除或其返回类型、可见性、static 性质发生破坏性变化："+key(old);}
            else if(!supported(next)||!old.getModifiers().equals(next.getModifiers())||!old.getThrownExceptions().equals(next.getThrownExceptions())||!bt.getModifiers().equals(at.getModifiers())) {
                outcome="UNKNOWN";summary="描述符保留，但修饰符/异常契约需要进一步检查："+key(old);unknown=List.of("Additional declaration changes outside descriptor rules");
            } else {outcome="COMPATIBLE";summary="仅该方法的原始类型描述符、可见性及已检查修饰符保持不变："+key(old);}
            List<Impact> impacts=new ArrayList<>();impacts.add(new Impact(oldOwner+"#"+key(old),"CONFIRMED_DECLARATION",breaking?"YES":"UNKNOWN","只覆盖指定前后类的已检查方法契约"));
            // 同名同参数数量只形成候选调用；未绑定接收者/重载前，不写依赖边或确认必改。
            for(MethodCallExpr call:b.findAll(MethodCallExpr.class))if(call.getNameAsString().equals(old.getNameAsString())&&call.getArguments().size()==old.getParameters().size()) {
                if(impacts.size()>=21){c.gap("REFACTOR_CALL_CANDIDATES_TRUNCATED",before.path());break;}
                ev.add(evidence(c,before,call,"JAVA_CALL_CANDIDATE_AST"));
                impacts.add(new Impact(before.path()+":"+call.getBegin().orElseThrow().line,"CANDIDATE","UNKNOWN","同名同实参数量调用；接收者/重载尚未绑定"));
            }
            c.add("JAVA_API_CHANGE",outcome,oldOwner+"#"+key(old),summary,ev,
                    List.of("仅单个平坦类中的 primitive/primitive-array 方法；公共契约检查不证明业务行为等价"),unknown,
                    List.of("核对直接及外部调用者；保持兼容入口或安排调用适配；由用户执行 API/二进制及业务回归验证。"),impacts);
            if(next!=null&&!old.getBody().map(JavaRules::ast).equals(next.getBody().map(JavaRules::ast)))
                c.add("JAVA_BODY_CHANGE","UNKNOWN",oldOwner+"#"+key(old),"方法体 AST 发生变化，声明相同不能证明行为等价。",ev,List.of(),List.of("Behavior, side effects and invariants not verified"),
                        List.of("验证请求中的业务不变量、边界值、异常、状态变化和副作用顺序。"),List.of());
        }
        String oldState=bt.getMembers().stream().filter(m->m instanceof FieldDeclaration||m instanceof InitializerDeclaration).map(JavaRules::ast).toList().toString();
        String newState=at.getMembers().stream().filter(m->m instanceof FieldDeclaration||m instanceof InitializerDeclaration).map(JavaRules::ast).toList().toString();
        if(!oldState.equals(newState))c.add("JAVA_STATE_CHANGE","UNKNOWN",oldOwner,"字段或初始化块 AST 发生变化。",pairEvidence,List.of(),List.of("State initialization, persistence and concurrency effects not verified"),
                List.of("验证初值、构造流程、序列化、共享状态与并发不变量；不把字段变化自动传播为所有方法必改。"),List.of());
    }
}
