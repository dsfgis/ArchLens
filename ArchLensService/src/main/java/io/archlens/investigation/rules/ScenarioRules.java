package io.archlens.investigation.rules;

import io.archlens.contract.*;
import io.archlens.parser.SourceText;
import io.archlens.investigation.InvestigationRequest;
import java.util.*;
import java.util.function.BooleanSupplier;
import static io.archlens.investigation.InvestigationReport.*;

/** 按场景和明确版本分派确定性规则；自然语言目标不能充当执行指令或重构对比计划。 */
public final class ScenarioRules {
    public record Result(List<Finding> findings,List<Gap> gaps,boolean supported) {
        public Result {findings=List.copyOf(findings);gaps=List.copyOf(gaps);}
    }
    enum Family { MYSQL, ORACLE, CSHARP, REFACTOR, BOOT, JDK, HTTPCLIENT, UNSUPPORTED }
    static boolean version(String value,String prefix) {
        // 只接受明确数字版本；版本区间、latest、预发行后缀均不能冒充已验证的适用版本。
        return value!=null && value.matches(java.util.regex.Pattern.quote(prefix)+"(?:\\.[0-9]+)*");
    }
    private static boolean product(InvestigationRequest.Profile p,String name) {return p!=null&&p.product().equalsIgnoreCase(name);}
    static Family family(InvestigationRequest r) {
        var s=r.sourceProfile();var t=r.targetProfile();if(t==null)return Family.UNSUPPORTED;
        return switch(r.scenario()) {
            case DATABASE_MIGRATION -> !product(t,"PostgreSQL")||!version(t.version(),"16")?Family.UNSUPPORTED:
                    product(s,"MySQL")&&version(s.version(),"8.0")?Family.MYSQL:
                    product(s,"Oracle")&&(version(s.version(),"19")||"19c".equals(s.version()))?Family.ORACLE:Family.UNSUPPORTED;
            case LANGUAGE_MIGRATION -> (product(s,"C#")||product(s,"CSharp"))&&version(s.version(),"12")&&product(t,"Java")&&version(t.version(),"21")?Family.CSHARP:Family.UNSUPPORTED;
            case REFACTORING -> product(s,"Java")&&version(s.version(),"21")&&product(t,"Java")&&version(t.version(),"21")?Family.REFACTOR:Family.UNSUPPORTED;
            case DEPENDENCY_UPGRADE -> product(s,"Spring Boot")&&version(s.version(),"2.7")&&product(t,"Spring Boot")&&version(t.version(),"3.0")?Family.BOOT:
                    product(s,"Java")&&version(s.version(),"8")&&product(t,"Java")&&(version(t.version(),"17")||version(t.version(),"21"))?Family.JDK:
                    product(s,"org.apache.httpcomponents:httpclient")&&version(s.version(),"4.5")&&product(t,"org.apache.httpcomponents.client5:httpclient5")&&version(t.version(),"5.2")?Family.HTTPCLIENT:Family.UNSUPPORTED;
            default -> Family.UNSUPPORTED;
        };
    }
    public Result analyze(InvestigationRequest request,Map<String,SourceText> sources,BooleanSupplier cancelled,long deadline) {
        return analyze(request,sources,cancelled,deadline,null);
    }
    /** 可选规则集合由 Agent 工具验证后传入；未选择的规则显式进入覆盖缺口。旧入口默认运行全部规则。 */
    public Result analyze(InvestigationRequest request,Map<String,SourceText> sources,BooleanSupplier cancelled,long deadline,Set<String> selectedRules) {
        Family family=family(request);Context c=new Context(cancelled,deadline,selectedRules);
        if(selectedRules!=null) {
            io.archlens.contract.ContractException.require(new HashSet<>(applicableRuleIds(request)).containsAll(selectedRules),"RULE_NOT_APPLICABLE","Rule selection outside target scope");
            var omitted=applicableRuleIds(request).stream().filter(id->!selectedRules.contains(id)).toList();
            if(!omitted.isEmpty())c.gap("RULES_NOT_SELECTED",String.join(",",omitted));
        }
        if(family==Family.UNSUPPORTED) return new Result(List.of(),List.of(new Gap("RULE_MATRIX_UNSUPPORTED_OR_VERSION_UNKNOWN",request.scenario().name())),false);
        try {
            if(family==Family.REFACTOR)JavaRules.refactor(sources,c);
            else for(SourceText source:sources.values()) {
                c.check();String path=source.path().toLowerCase(Locale.ROOT);
                int before=c.findings.size();
                try {
                    if((family==Family.MYSQL||family==Family.ORACLE)&&path.endsWith(".sql"))SqlRules.analyze(source,family,c);
                    else if(family==Family.CSHARP&&path.endsWith(".cs"))CSharpRules.analyze(source,c);
                    else if(family==Family.CSHARP&&(path.endsWith(".csproj")||path.endsWith(".props")||path.endsWith(".targets")))CSharpProjectRules.analyze(source,request,sources,c);
                    else if((family==Family.BOOT||family==Family.JDK||family==Family.HTTPCLIENT)&&path.endsWith(".java"))JavaRules.upgrade(source,family,c);
                    else if(family==Family.BOOT&&(path.equals("pom.xml")||path.endsWith("/pom.xml")))JavaRules.maven(source,request,c);
                    else c.gap("SCENARIO_FILE_UNSUPPORTED",source.path());
                } catch(ContractException e) {
                    // 文件解析失败时撤销该文件已经产生的发现，避免前半段匹配被误当完整有效分析。
                    while(c.findings.size()>before)c.findings.removeLast();
                    if(Set.of("RULE_CANCELLED","RULE_TIME_BUDGET","RULE_FINDING_LIMIT").contains(e.code()))throw e;
                    c.gap(e.code(),source.path());
                }
            }
        } catch(ContractException e) {
            if(!Set.of("RULE_CANCELLED","RULE_TIME_BUDGET","RULE_FINDING_LIMIT").contains(e.code()))throw e;
            c.findings.clear();c.gap(e.code(),"investigation");
        }
        if(c.gaps.stream().anyMatch(g->g.code().equals("PROFILE_SOURCE_VERSION_CONFLICT"))) {
            // 声明版本与采集到的 Maven 父版本冲突时，撤销本次所有规则结论，不能择优采信。
            c.findings.clear();c.gap("RULE_RESULTS_WITHHELD","Declared version conflicts with collected project metadata");
        }
        c.gap("DECLARED_PROFILES_UNVERIFIED","Source/target versions are request declarations, not live runtime observations");
        c.gap("SCENARIO_COVERAGE_LIMITED","Only registered source features; no whole-system compatibility or behavioral equivalence proof");
        return new Result(c.findings,c.gaps,true);
    }
    public static List<String> applicableRuleIds(InvestigationRequest request) {
        String prefix=switch(family(request)){case MYSQL->"MYSQL_";case ORACLE->"ORACLE_";case CSHARP->"CS_";
            case REFACTOR->"JAVA_";case BOOT->"BOOT_";case JDK->"JDK_";case HTTPCLIENT->"HTTPCLIENT_";default->"UNSUPPORTED_";};
        return RuleCatalog.all().stream().map(RuleBasis::ruleId).filter(id->id.startsWith(prefix)).toList();
    }
    static final class Context {
        final List<Finding> findings=new ArrayList<>();final List<Gap> gaps=new ArrayList<>();
        final BooleanSupplier cancelled;final long deadline;
        final Set<String> selectedRules;
        long lastCancellationPoll;boolean cancellationPolled;
        Context(BooleanSupplier cancelled,long deadline,Set<String> selectedRules){this.cancelled=cancelled;this.deadline=deadline;this.selectedRules=selectedRules==null?null:Set.copyOf(selectedRules);}
        void check() {
            long now=System.nanoTime();
            if(now>=deadline)throw Lexical.error("RULE_TIME_BUDGET");
            // 存储模式的取消检查会查询 PG。最多每 100ms 查询一次，避免按 token 产生网络往返。
            // 本地截止时间仍逐次检查；调查引擎在封存前还会独立复核取消状态。
            if(!cancellationPolled || now-lastCancellationPoll>=100_000_000L) {
                cancellationPolled=true;lastCancellationPoll=now;
                if(cancelled.getAsBoolean())throw Lexical.error("RULE_CANCELLED");
            }
        }
        void gap(String code,String source){Gap g=new Gap(code,source);if(!gaps.contains(g))gaps.add(g);}
        Evidence evidence(SourceText s,int start,int end,String kind) {
            // 证据绑定原始来源哈希和精确位置；后续文件改动会使身份失效。
            var location=Lexical.location(s,start,end);String sourceId=Json.hashParts(s.path(),s.hash());
            return new Evidence(Json.hashParts(sourceId,location,kind),sourceId,s.hash(),location,kind);
        }
        void add(String rule,String outcome,String subject,String summary,List<Evidence> evidence,List<String> conditions,
                 List<String> unknown,List<String> recommendations,List<Impact> impacts) {
            if(selectedRules!=null&&!selectedRules.contains(rule))return;
            check();if(findings.size()>=500)throw Lexical.error("RULE_FINDING_LIMIT");
            var basis=RuleCatalog.get(rule);var ids=evidence.stream().map(Evidence::evidenceId).toList();
            String subjectId=Json.hashParts(subject,evidence.stream().map(Evidence::sourceId).toList());
            findings.add(new Finding(Json.hashParts(basis,subjectId,ids,outcome),outcome,rule+"@"+RuleCatalog.VERSION,ids,
                    conditions,unknown,subjectId,summary,evidence,basis,recommendations,impacts));
        }
        void feature(SourceText s,Lexical.Token token,String rule,String outcome,String summary,String advice,String condition) {
            add(rule,outcome,s.path()+":"+token.start(),summary,List.of(evidence(s,token.start(),token.end(),"LEXICAL_FEATURE")),
                    condition==null?List.of():List.of(condition),outcome.equals("UNKNOWN")?List.of("Unresolved semantic binding or behavior"):List.of(),
                    List.of(advice),List.of(new Impact(s.path(),"CONFIRMED_TOKEN",outcome.equals("INCOMPATIBLE")?"YES":"UNKNOWN","Limited to the located source feature")));
        }
    }
}
