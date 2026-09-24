package io.archlens.investigation.rules;

import io.archlens.investigation.InvestigationRequest;
import io.archlens.parser.SourceText;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import static io.archlens.investigation.InvestigationReport.*;
import static io.archlens.investigation.rules.ScenarioRules.*;

/** 项目声明调查，不执行 MSBuild、不还原 NuGet；条件和动态表达式始终保留为未知。 */
final class CSharpProjectRules {
    static void analyze(SourceText s, InvestigationRequest request, Map<String,SourceText> sources, Context c) {
        if(s.text().length()>500_000)throw Lexical.error("CS_PROJECT_FILE_LIMIT");
        Element root;
        try {
            var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setXIncludeAware(false);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setFeature("http://xml.org/sax/features/external-general-entities",false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var b=f.newDocumentBuilder();b.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            root=b.parse(new InputSource(new StringReader(s.text()))).getDocumentElement();
        } catch(Exception e){throw Lexical.error("CS_PROJECT_XML_REJECTED");}
        if(!name(root).equals("Project") || root.getNamespaceURI()!=null&&!root.getNamespaceURI().equals("http://schemas.microsoft.com/developer/msbuild/2003"))
            throw Lexical.error("CS_PROJECT_XML_REJECTED");
        List<String> properties=new ArrayList<>();
        if(root.hasAttribute("Sdk"))properties.add("Sdk="+root.getAttribute("Sdk"));
        int ordinal=0;
        for(Element group:children(root)) {
            c.check();
            if(name(group).equals("Import")||name(group).equals("Target"))c.gap("CS_MSBUILD_UNEVALUATED",s.path());
            if(!Set.of("PropertyGroup","ItemGroup").contains(name(group)))continue;
            for(Element item:children(group)) {
                c.check();String kind=name(item),value=item.getTextContent().trim();
                boolean conditional=root.hasAttribute("Condition")||group.hasAttribute("Condition")||item.hasAttribute("Condition");
                if(conditional)c.gap("CS_CONDITIONAL_DECLARATION",s.path());
                if(name(group).equals("PropertyGroup")&&Set.of("TargetFramework","TargetFrameworks","LangVersion","OutputType","Nullable","UseWPF","UseWindowsForms","EnableDefaultCompileItems").contains(kind)) {
                    properties.add(kind+"="+value+(conditional?" (条件未求值)":""));
                    // 仅对直接、无条件、数字语言版本声明检测冲突，不推断 TFM 的默认语言版本。
                    if(kind.equals("LangVersion")&&!conditional&&value.matches("[0-9]+(?:\\.[0-9]+)*")&&!ScenarioRules.version(value,"12"))
                        c.gap("PROFILE_SOURCE_VERSION_CONFLICT",s.path()+": declared LangVersion differs from C# 12 request");
                }
                if(!name(group).equals("ItemGroup"))continue;
                if(Set.of("PackageReference","PackageVersion","Reference","FrameworkReference").contains(kind)) {
                    String id=item.getAttribute("Include");if(id.isBlank())id=item.getAttribute("Update");
                    String version=item.getAttribute("Version");
                    if(version.isBlank())for(Element child:children(item))if(name(child).equals("Version"))version=child.getTextContent().trim();
                    add(c,s,"CS_PROJECT_DEPENDENCY",ordinal++,"依赖声明："+kind+" "+id+(version.isBlank()?"（版本未在该项直接声明）":" "+version),
                        "为每个 .NET/NuGet 依赖确定保留协议、Java 替代或重写方案；验证 API、许可证、序列化及集成行为。未解析传递依赖或保证存在等价 Java 包。");
                } else if(kind.equals("ProjectReference")) {
                    String ref=item.getAttribute("Include");boolean present=false;
                    try {
                        String normalized=ref.replace('\\','/');Path p=Path.of(s.path()).resolveSibling(normalized).normalize();
                        if(!ref.isBlank()&&!ref.contains(":")&&!ref.contains("$")&&!ref.contains("*")&&!ref.contains(";")&&!p.isAbsolute()&&!p.startsWith(".."))
                            present=sources.containsKey(p.toString().replace('\\','/'));
                    }catch(Exception ignored){ /* 非法或动态引用仅记录缺口，不触发文件读取。 */ }
                    if(!present)c.gap("CS_PROJECT_REFERENCE_UNCOLLECTED",s.path()+": "+ref);
                    add(c,s,"CS_PROJECT_REFERENCE",ordinal++,"项目引用声明："+ref+(present?"（目标在来源清单中）":"（目标未采集或无法解析）"),
                        "按项目边界设计 Java 模块并验证公共契约、循环依赖和调用方；声明引用不等于已绑定调用图，条件与编译关系需另验。");
                } else if(kind.equals("Compile"))c.gap("CS_COMPILE_ITEMS_UNEVALUATED",s.path());
            }
        }
        add(c,s,"CS_PROJECT_PROFILE",0,"项目配置声明："+(properties.isEmpty()?"没有受支持的直接属性":String.join("；",properties)),
            "确认有效 C# 语言版本与 .NET 框架版本；为 Java 21 选择构建、宿主、UI/Web/持久化方案。WPF/Windows Forms 等平台能力需单独迁移，不能由 Java 版本替代声明。");
        c.gap("CS_PROJECT_DECLARATIONS_ONLY",s.path()+": imports, conditions, SDK defaults, central versions, transitive packages and generated code are not evaluated");
    }
    private static void add(Context c,SourceText s,String rule,int ordinal,String summary,String advice) {
        // XML 依据明确标为整个声明文件，不伪造未定位到的元素行号。
        c.add(rule,"UNKNOWN",s.path()+":"+rule+":"+ordinal,summary,List.of(c.evidence(s,0,s.text().length(),"CS_PROJECT_XML_DECLARATION")),
            List.of("仅静态 XML 声明，可能受条件、导入和 SDK 默认值影响"),List.of("未执行 MSBuild / restore / 编译 / 行为测试"),List.of(advice),
            List.of(new Impact(s.path(),"DECLARED_PROJECT_METADATA","UNKNOWN","迁移工作候选，未证明实际编译或运行依赖")));
    }
    private static String name(Element e){return e.getLocalName()==null?e.getTagName():e.getLocalName();}
    private static List<Element> children(Element e){var out=new ArrayList<Element>();var n=e.getChildNodes();for(int i=0;i<n.getLength();i++)if(n.item(i) instanceof Element child&&Objects.equals(e.getNamespaceURI(),child.getNamespaceURI()))out.add(child);return out;}
}
