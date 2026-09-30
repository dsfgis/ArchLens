package io.archlens.investigation;

import io.archlens.cli.ArchLensCli;
import io.archlens.contract.*;
import io.archlens.parser.*;
import io.archlens.investigation.rules.*;
import io.archlens.investigation.dotnet.*;
import io.archlens.investigation.database.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static io.archlens.investigation.InvestigationReport.*;
import static io.archlens.contract.ContractException.require;

/** 确定性显式来源调查：不执行 shell、模型、任意 SQL 或被分析源码的写入操作。 */
public final class InvestigationEngine {
    public static final String VERSION="archlens-investigation-0.8";
    private final BusinessConnection businessConnection;
    public InvestigationEngine(){this(null);}
    public InvestigationEngine(BusinessConnection connection){this.businessConnection=connection;}
    public InvestigationReport investigate(Path root,InvestigationRequest request,BooleanSupplier cancelled) throws Exception {
        return investigate(root,request,cancelled,sources->{});
    }
    public InvestigationReport investigate(Path root,InvestigationRequest request,BooleanSupplier cancelled,Consumer<List<Source>> checkpoint) throws Exception {
        return investigate(root,request,cancelled,checkpoint,null);
    }
    /** Agent 可以选择规则子集；来源采集、权限、漂移与报告质量校验仍由引擎统一执行。 */
    public InvestigationReport investigate(Path root,InvestigationRequest request,BooleanSupplier cancelled,Consumer<List<Source>> checkpoint,Set<String> selectedRules) throws Exception {
        return investigate(root,request,cancelled,checkpoint,selectedRules,null);
    }
    public InvestigationReport investigate(Path root,InvestigationRequest request,BooleanSupplier cancelled,Consumer<List<Source>> checkpoint,Set<String> selectedRules,BusinessContext context) throws Exception {
        String start=Instant.now().toString(); long deadline=System.nanoTime()+request.budget().timeoutMillis()*1_000_000;
        require(root!=null || (request.files().isEmpty()&&request.columnRequest()==null&&context!=null&&context.source()!=null),"SOURCE_ROOT_INVALID","Code inputs require an authorized root");
        Path base=root==null?null:root.toRealPath(); List<Source> sources=new ArrayList<>(); List<Gap> gaps=new ArrayList<>();
        Map<String,SourceText> collected=new LinkedHashMap<>();
        List<Clarification> clarification=new ArrayList<>(); long bytes=0;
        var paths=new TreeSet<>(request.files()); OfflineRequest columnInput=null; SourceText columnRaw=null;
        if(request.columnRequest()!=null && !cancelled.getAsBoolean() && System.nanoTime()<deadline) {
            paths.add(request.columnRequest());
            Path columnPath=base.resolve(request.columnRequest()).normalize();
            try {
                require(columnPath.toRealPath().startsWith(base),"PATH_OUTSIDE_ROOT","Column request outside authorized directory");
                if(Files.size(columnPath)>request.budget().maxBytes()) gaps.add(new Gap("BYTE_BUDGET",request.columnRequest()));
                else {
                    columnRaw=SourceText.read(base,request.columnRequest());
                    columnInput=Json.MAPPER.readValue(columnRaw.text(),OfflineRequest.class);
                    Path columnBase=columnPath.getParent();
                    for(String file:List.of(columnInput.javaFile(),columnInput.mapperFile())) {
                        InvestigationRequest.relative(file);
                        String relative=base.relativize(columnBase.resolve(file).normalize()).toString();
                        InvestigationRequest.relative(relative); paths.add(relative);
                    }
                }
            } catch(NoSuchFileException|AccessDeniedException e) {gaps.add(new Gap("SOURCE_UNAVAILABLE",request.columnRequest()));}
        }
        if(columnRaw!=null) {
            // 请求文件也纳入来源清单，检测请求解析与正式采集之间发生的漂移。
            require(columnRaw.location().byteLength()<=request.budget().maxBytes(),"INPUT_LIMIT","Column request exceeds budget");
        }
        boolean wasCancelled=false; int attempted=0;
        for(String path:paths) {
            if(cancelled.getAsBoolean()) { wasCancelled=true; gaps.add(new Gap("CANCELLED",path)); break; }
            if(System.nanoTime()>=deadline) { gaps.add(new Gap("TIME_BUDGET",path)); break; }
            if(attempted++>=request.budget().maxFiles()) { gaps.add(new Gap("FILE_BUDGET",path)); break; }
            Path candidate=base.resolve(path).normalize();
            require(candidate.startsWith(base),"PATH_OUTSIDE_ROOT","Source outside authorized directory");
            try {
                Path real=candidate.toRealPath(); require(real.startsWith(base),"PATH_OUTSIDE_ROOT","Source outside authorized directory");
                if(Files.size(real)>request.budget().maxBytes()-bytes) { gaps.add(new Gap("BYTE_BUDGET",path)); break; }
                SourceText text=SourceText.read(base,path);
                if(text.location().byteLength()>request.budget().maxBytes()-bytes) { gaps.add(new Gap("BYTE_BUDGET",path)); break; }
                bytes+=text.location().byteLength();
                collected.put(text.path(),text);
                sources.add(new Source(Json.hashParts(text.path(),text.hash()),text.path(),text.hash(),
                        text.location().byteLength(),Instant.now().toString(),text.location(),VERSION));
            }
            catch(ContractException e) { if(e.code().equals("PATH_OUTSIDE_ROOT")) throw e; gaps.add(new Gap(e.code(),path)); }
            catch(IOException e) { gaps.add(new Gap(e instanceof java.nio.charset.CharacterCodingException ? "INVALID_UTF8" : "SOURCE_UNAVAILABLE",path)); }
            checkpoint.accept(List.copyOf(sources));
        }
        if(columnRaw!=null) {
            String parsedHash=columnRaw.hash(),parsedPath=columnRaw.path();
            if(sources.stream().noneMatch(s->s.path().equals(parsedPath)&&s.sha256().equals(parsedHash))) gaps.add(new Gap("SOURCE_DRIFT_OR_UNCOLLECTED",parsedPath));
        }
        ArchLensCli.Report analysis=null;
        if(columnInput!=null && gaps.isEmpty() && !cancelled.getAsBoolean() && System.nanoTime()<deadline) {
            analysis=ArchLensCli.analyze(base.resolve(request.columnRequest()).getParent(),columnInput,columnRaw.hash());
            // 旧适配器会重读文件；如果观察到不同字节，禁止把其结果附在本次来源清单下。
            for(var entry:analysis.sourceHashes().entrySet()) {
                String path=base.relativize(base.resolve(request.columnRequest()).getParent().resolve(entry.getKey()).toRealPath()).toString().replace('\\','/');
                if(sources.stream().noneMatch(s->s.path().equals(path) && s.sha256().equals(entry.getValue()))) {
                    gaps.add(new Gap("SOURCE_DRIFT",path)); analysis=null; break;
                }
            }
        }
        if(cancelled.getAsBoolean()) {wasCancelled=true;analysis=null;gaps.add(new Gap("CANCELLED","investigation"));}
        if(System.nanoTime()>=deadline) {analysis=null;gaps.add(new Gap("TIME_BUDGET","investigation"));}
        ScenarioRules.Result scenarioResult=null;
        if(request.scenario()!=InvestigationRequest.Scenario.CURRENT_STATE && request.scenario()!=InvestigationRequest.Scenario.COLUMN_CHANGE
                && !wasCancelled && System.nanoTime()<deadline) {
            scenarioResult=new ScenarioRules().analyze(request,collected,cancelled,deadline,selectedRules);
            gaps.addAll(scenarioResult.gaps());
        }
        // 现状清单独立于兼容规则选择；版本未知和无匹配规则也能输出已采集的项目事实。
        DotnetInventory inventory=null;
        if(!wasCancelled && System.nanoTime()<deadline) {
            try {
                inventory=DotnetInventoryAnalyzer.analyze(collected,paths.size(),cancelled,deadline);
                if(inventory!=null)gaps.addAll(inventory.gaps());
                else if(DotnetInventory.platformProduct(request.sourceProfile().product()))gaps.add(new Gap("DOTNET_PROJECTS_UNCOLLECTED","No project or solution metadata was collected"));
            } catch(ContractException e) {
                gaps.add(new Gap(e.code(),".NET inventory"));
                if(e.code().equals("DOTNET_CANCELLED"))wasCancelled=true;
            }
        }
        DatabaseInventory database=null;
        if(context!=null&&context.source()!=null&&!wasCancelled&&System.nanoTime()<deadline) {
            database=DatabaseCollectors.collect(context.source(),businessConnection,cancelled,Math.min(deadline,System.nanoTime()+15_000_000_000L),false);
            for(String code:database.coverageGaps())gaps.add(new Gap(code,"business-database"));
            String observed=database.observedVersion(),declared=request.sourceProfile().version();
            boolean profileConflict=request.scenario()==InvestigationRequest.Scenario.DATABASE_MIGRATION&&observed!=null&&declared!=null
                    &&(!request.sourceProfile().product().equalsIgnoreCase(context.source().product())||!observed.equals(declared)&&!observed.startsWith(declared+"."));
            if(profileConflict)gaps.add(new Gap("DB_DECLARED_VERSION_CONFLICT","Main source profile conflicts with observed server"));
            if(profileConflict||database.coverageGaps().contains("DB_DECLARED_VERSION_CONFLICT")) {
                scenarioResult=null;gaps.add(new Gap("RULE_RESULTS_WITHHELD","Declared database version conflicts with observed server"));
            }
        }
        if(context!=null&&context.targetEnvironment()!=null)gaps.add(new Gap("TARGET_ENVIRONMENT_DECLARED_NOT_ASSESSED","Target environment is user-declared, not a verified compatibility result"));
        // 最终复核可检测一般文件漂移，但不构成跨文件原子快照。
        for(Source source:sources) {
            if(cancelled.getAsBoolean()) { wasCancelled=true; gaps.add(new Gap("CANCELLED",source.path())); analysis=null; break; }
            if(System.nanoTime()>=deadline) { gaps.add(new Gap("TIME_BUDGET",source.path())); analysis=null; break; }
            try { if(!SourceText.read(base,source.path()).hash().equals(source.sha256())) { gaps.add(new Gap("SOURCE_DRIFT",source.path())); analysis=null; } }
            catch(IOException|ContractException e) { gaps.add(new Gap("SOURCE_RECHECK_FAILED",source.path())); analysis=null; }
        }
        if(cancelled.getAsBoolean()) {wasCancelled=true;analysis=null;gaps.add(new Gap("CANCELLED","finalization"));}
        if(System.nanoTime()>=deadline) {analysis=null;gaps.add(new Gap("TIME_BUDGET","finalization"));}
        if(request.scenario()!=InvestigationRequest.Scenario.CURRENT_STATE && request.scenario()!=InvestigationRequest.Scenario.COLUMN_CHANGE) {
            if(request.sourceProfile().version()==null && !DotnetInventory.platformProduct(request.sourceProfile().product())) clarification.add(new Clarification("sourceProfile.version","Source-version compatibility"));
            if(request.targetProfile()==null || request.targetProfile().version()==null) clarification.add(new Clarification("targetProfile.version","Target compatibility"));
            if(scenarioResult==null||!scenarioResult.supported())gaps.add(new Gap("SCENARIO_RULES_UNAVAILABLE",request.scenario().name()));
        }
        if(!request.files().isEmpty())gaps.add(new Gap("EXPLICIT_FILES_ONLY",context==null?"No recursive/full-project or business-database collection":"Code collection is limited to explicitly listed files; business-database coverage is reported separately"));
        if(!request.files().isEmpty())gaps.add(new Gap("NON_ATOMIC_SOURCES","Source collection and recheck are not an atomic snapshot"));
        List<Finding> findings=new ArrayList<>();
        boolean unstable=wasCancelled || gaps.stream().anyMatch(g->Set.of("SOURCE_DRIFT","SOURCE_RECHECK_FAILED","TIME_BUDGET","RULE_TIME_BUDGET","RULE_CANCELLED","DOTNET_TIME_BUDGET","DOTNET_CANCELLED").contains(g.code()));
        if(unstable && inventory!=null) {
            inventory=null;gaps.add(new Gap("DOTNET_INVENTORY_WITHHELD","Source drift, cancellation or elapsed budget invalidated platform inventory"));
        }
        if(unstable&&database!=null){database=null;gaps.add(new Gap("DB_INVENTORY_WITHHELD","Investigation was invalidated"));}
        CodeDatabaseAssociation association=null;
        if(database!=null&&"PARTIAL".equals(database.status())&&!collected.isEmpty()){
            try {
                // 关联使用剩余调查预算中的最多 5 秒；超时只撤销关联，取消撤销本次调查的事实结果。
                association=CodeDatabaseAssociation.analyze(collected,database,cancelled,Math.min(deadline,System.nanoTime()+5_000_000_000L));
                for(String code:association.coverageGaps())gaps.add(new Gap(code,"code-database-association"));
            }catch(ContractException e){
                gaps.add(new Gap(e.code(),"code-database-association"));association=null;
                if("DB_CODE_ASSOCIATION_CANCELLED".equals(e.code())){
                    wasCancelled=true;unstable=true;database=null;inventory=null;analysis=null;
                    gaps.add(new Gap("DB_INVENTORY_WITHHELD","Investigation was cancelled during code association"));
                }
            }
        }
        // 规则只分析采集时缓存的原文字节。漂移/取消/超时后撤销发现，仍保留来源和覆盖缺口。
        if(scenarioResult!=null && !unstable)findings.addAll(scenarioResult.findings());
        else if(scenarioResult!=null)gaps.add(new Gap("RULE_RESULTS_WITHHELD","Source drift, cancellation or elapsed budget invalidated rule findings"));
        if(analysis==null&&findings.isEmpty()) gaps.add(new Gap("CONTENT_INVENTORY_ONLY","No supported semantic findings were published; inspect gaps"));
        var evidenceIds=sources.stream().map(Source::sourceId).toList();
        var finding=new Finding(Json.hashParts(request.scenario(),evidenceIds),"UNKNOWN",findings.isEmpty()?"no-supported-compatibility-rule":"unassessed-scope",evidenceIds,List.of(),
                List.of("Unchecked scope remains UNKNOWN; individual rules do not establish whole-project compatibility"));
        findings.add(finding);
        String fingerprint=Json.hashParts(VERSION,RuleCatalog.all(),request,context,database==null?null:database.metadataHash(),sources.stream().map(s->List.of(s.path(),s.sha256())).toList(),gaps);
        return new InvestigationReport(association!=null?"archlens.investigation-report.v5":context!=null?"archlens.investigation-report.v4":inventory==null?"archlens.investigation-report.v2":"archlens.investigation-report.v3",VERSION,fingerprint,request,
                wasCancelled?Status.CANCELLED:Status.PARTIAL,start,Instant.now().toString(),sources,gaps,clarification,findings,
                request.scenario()==InvestigationRequest.Scenario.LANGUAGE_MIGRATION ? List.of(
                        "按项目声明划分 Java 模块，逐项确认框架与直接/传递依赖的替代及未采集引用",
                        "建立金额精度、舍入、无符号上界、溢出及 JSON 字段/null/日期的输入输出黄金样例",
                        "对异步取消、异常传播、执行顺序、资源释放及平台/UI 能力制定独立回归用例",
                        "人工在隔离环境比较原实现与迁移实现；本次未生成 Java 代码或执行任何构建/行为验证") : List.of("Review declared coverage and resolve unknown versions before making compatibility decisions",
                        "Validate invariants in a user-controlled isolated environment; no verification commands were executed"),
                "DETERMINISTIC_ONLY_NO_MODEL_ORCHESTRATION",analysis,inventory,database,context==null?null:context.targetEnvironment(),association);
    }
}
