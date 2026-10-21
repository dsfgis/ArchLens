package io.archlens.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.archlens.contract.*;
import io.archlens.investigation.*;
import io.archlens.investigation.rules.*;
import io.archlens.investigation.dotnet.DotnetInventory;
import io.archlens.investigation.dotnet.DotnetSemantics;
import io.archlens.investigation.database.*;
import io.archlens.parser.SourceText;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.Pattern;
import static io.archlens.agent.AgentContracts.*;
import static io.archlens.contract.ContractException.require;
import static io.archlens.investigation.InvestigationRequest.*;

/** 有限、可恢复的模型调查循环。模型选择动作，事实、文件范围、结果引用和终止条件由本地控制。 */
public final class AgentOrchestrator {
    public static final String ENGINE="archlens-agent-0.3";
    private static final Set<String> TOOLS=Set.of("inspect_repository","delegate_analysis","propose_target","list_rules","run_analysis","inspect_dotnet_semantics","read_evidence","draft_migration_plan","draft_feasibility_plan","ask_clarification","finish");
    private static final Set<String> FIELDS=Set.of("scenario","sourceProfile.product","sourceProfile.version","targetProfile.product","targetProfile.version","context");
    private final AgentModel model;
    private final BusinessConnection businessConnection;
    public AgentOrchestrator(AgentModel model){this(model,null);}
    public AgentOrchestrator(AgentModel model,BusinessConnection connection){this.model=model;this.businessConnection=connection;}
    public Report investigate(Path root,Request request,BooleanSupplier cancelled)throws Exception {
        return execute(root,request,null,null,cancelled,sources->{});
    }
    public Report execute(Path root,Request request,Report previous,Answers answers,BooleanSupplier cancelled,
                          Consumer<List<InvestigationReport.Source>> checkpoint)throws Exception {
        int revision=1;String parent=null;Map<String,String> userAnswers=new LinkedHashMap<>();
        if(previous!=null) {
            userAnswers.putAll(validateResume(request,previous,answers));
            revision=previous.revision()+1;parent=Json.hash(previous);
        } else require(answers==null,"INVALID_ANSWERS","Answers require a previous report");
        return new Run(root==null?null:root.toRealPath(),request,revision,parent,userAnswers,cancelled,checkpoint).run();
    }
    public static Map<String,String> validateResume(Request request,Report previous,Answers answers) {
            Map<String,String> userAnswers=new LinkedHashMap<>();
            require(previous.status()==Status.NEEDS_CLARIFICATION&&previous.revision()<8,"AGENT_NOT_RESUMABLE","Run is not waiting for clarification");
            require(previous.requestHash().equals(Json.hash(request)),"STALE_ANSWERS","Request changed; start a new investigation");
            require(answers!=null&&Json.hash(previous).equals(answers.parentReportHash()),"STALE_ANSWERS","Answers refer to a different report");
            require(previous.questions().size()<=6&&previous.userAnswers().size()<=FIELDS.size(),"AGENT_REPORT_INVALID","Invalid previous clarification state");
            userAnswers.putAll(previous.userAnswers());
            Map<String,Question> pending=new HashMap<>();
            for(var q:previous.questions()) {
                require(FIELDS.contains(q.field())&&q.questionId().equals(questionId(previous.requestHash(),previous.revision(),q.field())),"AGENT_REPORT_INVALID","Invalid saved question identity");
                require(pending.put(q.questionId(),q)==null,"AGENT_REPORT_INVALID","Duplicate question");
            }
            require(pending.keySet().equals(answers.answers().keySet()),"INVALID_ANSWERS","Answer exactly the pending question IDs");
            answers.answers().forEach((id,value)->{
                String field=pending.get(id).field();
                userAnswers.put(field,field.equals("context")&&userAnswers.containsKey(field)?userAnswers.get(field)+"\n"+value:value);
            });
            require(FIELDS.containsAll(userAnswers.keySet()),"AGENT_REPORT_INVALID","Invalid saved answer field");
            userAnswers.forEach((field,value)->AgentContracts.bounded(value,field.equals("context")?4000:500));
            // 先验证回答的技术字段，避免持久化入口因格式错误占用不可恢复的新修订。
            for(var entry:userAnswers.entrySet()) {
                String field=entry.getKey(),value=entry.getValue();
                if(field.equals("scenario"))try{Scenario.valueOf(value);}catch(Exception e){throw new ContractException("INVALID_ANSWERS","Invalid scenario answer");}
                if(field.endsWith(".product"))require(value.length()<=200,"INVALID_ANSWERS","Product answer too long");
                if(field.endsWith(".version"))require(value.length()<=100,"INVALID_ANSWERS","Version answer too long");
                String original=declaredField(request.target(),field);
                require(original==null||original.equals(value),"INVALID_ANSWERS","Answers cannot replace originally declared technical fields");
            }
            java.util.function.Function<String,String> field=k->userAnswers.getOrDefault(k,declaredField(request.target(),k));
            String scenario=field.apply("scenario"),sp=field.apply("sourceProfile.product"),tp=field.apply("targetProfile.product");
            AgentContracts.validateBusinessTarget(new Target(scenario==null?null:Scenario.valueOf(scenario),
                    sp==null?null:new Profile(sp,field.apply("sourceProfile.version")),
                    tp==null?null:new Profile(tp,field.apply("targetProfile.version"))),request.businessContext());
            return Map.copyOf(userAnswers);
    }
    private static String declaredField(Target t,String field) {
        if(t==null)return null;
        return switch(field){case "scenario"->t.scenario()==null?null:t.scenario().name();
            case "sourceProfile.product"->t.sourceProfile()==null?null:t.sourceProfile().product();case "sourceProfile.version"->t.sourceProfile()==null?null:t.sourceProfile().version();
            case "targetProfile.product"->t.targetProfile()==null?null:t.targetProfile().product();case "targetProfile.version"->t.targetProfile()==null?null:t.targetProfile().version();default->null;};
    }
    private static String questionId(String hash,int revision,String field){return Json.hashParts(hash,revision,field);}
    public record Empty() {}
    public record Rules(List<String> ruleIds) {}
    public record EvidenceQuery(List<String> findingIds) {}
    public record QuestionInput(String field,String prompt) {}
    public record Clarify(List<QuestionInput> questions) {}
    public record Finish(List<Explanation> explanations) {}

    private final class Run {
        final Path root;final Request req;final int revision;final String parent,requestHash,start=Instant.now().toString();
        final Map<String,String> answers;final BooleanSupplier cancelled;final Consumer<List<InvestigationReport.Source>> checkpoint;
        final long deadline;final List<Map<String,Object>> messages=new ArrayList<>();final List<Trace> trace=new ArrayList<>();
        final List<String> diagnostics=new ArrayList<>();final List<Report> subtasks=new ArrayList<>();boolean childMode=false;final Set<String> selected=new TreeSet<>(),readFindings=new HashSet<>(),callIds=new HashSet<>();
        List<Question> questions=List.of();List<Explanation> explanations=List.of();Target target;InvestigationReport analysis;RepositoryInspector.Inventory repositoryInventory;Profile observedDatabase;DotnetSemantics.Result dotnetSemantics;MigrationPlan migrationPlan;FeasibilityPlan feasibilityPlan;
        int calls=0,toolCount=0,invalid=0,analysisCalls=0;boolean finished=false,clarifying=false,targetChosen=false,rulesListed=false,fallback=false,wasCancelled=false;
        Run(Path root,Request req,int revision,String parent,Map<String,String> answers,BooleanSupplier cancelled,Consumer<List<InvestigationReport.Source>> checkpoint) {
            this.root=root;this.req=req;this.revision=revision;this.parent=parent;this.answers=Map.copyOf(answers);this.cancelled=cancelled;this.checkpoint=checkpoint;
            this.requestHash=Json.hash(req);this.deadline=System.nanoTime()+req.agentBudget().timeoutMillis()*1_000_000;
            this.target=declaredTarget();
            messages.add(Map.of("role","system","content",AgentTools.PROMPT));
            // 对外上下文由白名单重新构造。绝不直接序列化 Request、源码或 InvestigationReport。
            Map<String,Object> context=new LinkedHashMap<>();context.put("objective",req.objective());context.put("declaredTarget",target);
            context.put("constraints",req.constraints());context.put("invariants",req.invariants());context.put("answers",answers);
            // 业务地址、账户、库名与结构不加入模型上下文；只声明是否包含独立业务源。
            context.put("hasBusinessDatabase",req.businessContext()!=null&&req.businessContext().source()!=null);
            if(req.businessContext()!=null&&req.businessContext().targetEnvironment()!=null) {
                var environment=req.businessContext().targetEnvironment();
                var declared=new LinkedHashMap<String,Object>();
                if(environment.database()!=null)declared.put("database",environment.database());
                if(environment.runtime()!=null)declared.put("runtime",environment.runtime());
                if(environment.operatingSystem()!=null)declared.put("operatingSystem",environment.operatingSystem());
                if(environment.architecture()!=null)declared.put("architecture",environment.architecture());
                if(environment.compatibilityMode()!=null)declared.put("databaseCompatibilityMode",environment.compatibilityMode());
                context.put("targetEnvironment",declared);
            }
            context.put("authorizedFileCount",req.files().size());context.put("hasColumnAdapter",req.columnRequest()!=null);
            messages.add(Map.of("role","user","content",Json.canonical(context)));
        }
        Report run()throws Exception {
            try {
                if(model==null&&req.target()==null)repositoryInventory=RepositoryInspector.inspect(root,req,cancelled,Math.min(deadline,System.nanoTime()+req.collectionBudget().timeoutMillis()*1_000_000));
                require(model!=null,"MODEL_NOT_CONFIGURED","Model is unavailable");
                while(!finished&&!clarifying) {
                    active();require(calls<req.agentBudget().maxModelCalls(),"AGENT_MODEL_BUDGET","Model call budget exhausted");
                    calls++;AgentModel.Call call=invokeModel();active();
                    require(call!=null&&call.id()!=null&&call.id().matches("[A-Za-z0-9_-]{1,100}")&&callIds.add(call.id()),"INVALID_MODEL_OUTPUT","Missing or repeated tool call ID");
                    require(call.arguments()!=null&&call.arguments().isObject(),"INVALID_MODEL_OUTPUT","Invalid tool arguments");
                    require(toolCount<req.agentBudget().maxToolCalls(),"AGENT_TOOL_BUDGET","Tool budget exhausted");toolCount++;
                    String name=TOOLS.contains(call.name())?call.name():"UNREGISTERED";long before=System.nanoTime();Object result;String code="OK";
                    try {require(TOOLS.contains(call.name()),"TOOL_NOT_ALLOWED","Tool not registered");result=dispatch(call.name(),call.arguments());}
                    catch(ContractException e) {
                        if(Set.of("AGENT_CANCELLED","AGENT_TIME_BUDGET","PATH_OUTSIDE_ROOT").contains(e.code()))throw e;
                        code=e.code();result=Map.of("error",code,"instruction",correction(code));invalid++;
                    }
                    trace.add(new Trace(toolCount,name,Json.hash(call.arguments()),code,Json.hash(result),(System.nanoTime()-before)/1_000_000));
                    if(!code.equals("OK"))diagnostics.add(code);
                    // 只回传经过校验的工具结果；不保存或重放模型的思考内容。
                    messages.add(Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id",call.id(),"type","function","function",Map.of("name",call.name(),"arguments",Json.canonical(call.arguments()))))));
                    messages.add(Map.of("role","tool","tool_call_id",call.id(),"content",Json.canonical(result)));
                    require(invalid<3,"AGENT_INVALID_ACTION_LIMIT","Too many invalid tool actions");
                }
            }catch(Exception e) {
                String code=code(e);diagnostics.add(code);wasCancelled=code.equals("AGENT_CANCELLED");fallback=!wasCancelled;
                explanations=List.of();
                if(!wasCancelled&&analysis==null&&remaining()>0) {
                    // 模型不可用时保留确定性能力；缺少技术上下文只做来源清单，不猜迁移目标。
                    try {analysis=analyze(investigationTarget(),null);}catch(Exception failure){diagnostics.add(code(failure));}
                }
                if(!wasCancelled&&!complete(target)&&questions.isEmpty()&&subtasks.isEmpty()&&!code.startsWith("MODEL_"))questions=missingQuestions();
            }
            if(clarifying && analysis==null && target!=null && target.sourceProfile()!=null
                    && (DotnetInventory.platformProduct(target.sourceProfile().product())||req.businessContext()!=null) && remaining()>0) {
                try {analysis=analyze(new Target(Scenario.CURRENT_STATE,target.sourceProfile(),null),null);}
                catch(Exception e){diagnostics.add(code(e));}
            }
            if(cancelled.getAsBoolean()){wasCancelled=true;explanations=List.of();migrationPlan=null;diagnostics.add("AGENT_CANCELLED");}
            if(repositoryInventory!=null) {
                for(var entry:repositoryInventory.sourceHashes().entrySet()) {
                    try {active();require(SourceText.read(root,entry.getKey()).hash().equals(entry.getValue()),"AGENT_SOURCE_DRIFT","Inspection source changed");}
                    catch(Exception e){repositoryInventory=null;explanations=List.of();migrationPlan=null;dotnetSemantics=null;diagnostics.add(code(e));if(analysis!=null)analysis=invalidate(analysis,code(e));break;}
                }
            }
            if(dotnetSemantics!=null&&!dotnetSemantics.facts().isEmpty()) {
                Map<String,String> semanticSources=new LinkedHashMap<>();
                dotnetSemantics.facts().forEach(f->semanticSources.put(f.path(),f.sha256()));
                for(var entry:semanticSources.entrySet()) {
                    try {active();String inspected=repositoryInventory==null?null:repositoryInventory.sourceHashes().get(entry.getKey());
                        if(inspected==null)inspected=SourceText.read(root,entry.getKey()).hash();
                        require(inspected.equals(entry.getValue()),"AGENT_SOURCE_DRIFT","Semantic source changed");}
                    catch(Exception e){dotnetSemantics=null;migrationPlan=null;diagnostics.add(code(e));break;}
                }
            }
            if(analysis!=null) {
                String invalidation=null;
                if(wasCancelled)invalidation="AGENT_CANCELLED";
                else if(remaining()<=0)invalidation="AGENT_TIME_BUDGET";
                else for(var source:analysis.sources()) {
                    if(cancelled.getAsBoolean()){wasCancelled=true;invalidation="AGENT_CANCELLED";break;}
                    if(remaining()<=0){invalidation="AGENT_TIME_BUDGET";break;}
                    try{if(!SourceText.read(root,source.path()).hash().equals(source.sha256())){invalidation="AGENT_SOURCE_DRIFT";break;}}
                    catch(Exception e){invalidation="AGENT_SOURCE_RECHECK_FAILED";break;}
                }
                if(invalidation!=null){analysis=invalidate(analysis,invalidation);explanations=List.of();diagnostics.add(invalidation);}
            }
            if(!subtasks.isEmpty()) {
                String invalidation=wasCancelled?"AGENT_CANCELLED":diagnostics.contains("AGENT_SOURCE_DRIFT")?"AGENT_SOURCE_DRIFT":null;
                outer:for(var subtask:subtasks) {
                    if(invalidation!=null)break;
                    if(subtask.investigation()!=null)for(var source:subtask.investigation().sources()) {
                        try {active();require(SourceText.read(root,source.path()).hash().equals(source.sha256()),"AGENT_SOURCE_DRIFT","Subtask source changed");}
                        catch(Exception e){invalidation=code(e);break outer;}
                    }
                }
                if(invalidation!=null){subtasks.clear();explanations=List.of();diagnostics.add("SUBTASK_RESULTS_WITHHELD");diagnostics.add(invalidation);}
            }
            if(!wasCancelled&&questions.isEmpty()&&subtasks.stream().anyMatch(r->r.status()==Status.NEEDS_CLARIFICATION))questions=subtaskQuestions();
            Status status=wasCancelled?Status.CANCELLED:questions.isEmpty()?Status.PARTIAL:Status.NEEDS_CLARIFICATION;
            return new Report(req.schemaVersion(),ENGINE,requestHash,req,revision,parent,status,
                    wasCancelled?"MODEL_LOOP_CANCELLED":fallback?"DETERMINISTIC_FALLBACK":"MODEL_TOOL_LOOP",start,Instant.now().toString(),target,
                    answers,questions,trace,List.copyOf(selected),analysis,explanations,
                    explanations.isEmpty()?"NO_MODEL_EXPLANATION":"MODEL_EXPLANATION_UNVERIFIED",List.copyOf(new LinkedHashSet<>(diagnostics)),calls,repositoryInventory,subtasks,dotnetSemantics,migrationPlan,feasibilityPlan);
        }
        private List<Question> subtaskQuestions(){
            String prompt="请补充子任务所需信息："+subtasks.stream().flatMap(r->r.questions().stream()).map(Question::prompt).distinct().collect(java.util.stream.Collectors.joining("；"));
            if(prompt.length()>500)prompt=prompt.substring(0,470)+"…完整问题见子任务报告。";
            return List.of(new Question(questionId(requestHash,revision,"context"),"context",prompt));
        }
        private AgentModel.Call invokeModel()throws Exception {
            var executor=Executors.newVirtualThreadPerTaskExecutor();
            var future=executor.submit(()->model.next(List.copyOf(messages),AgentTools.schemas().stream().filter(t->!childMode||!((Map<?,?>)t.get("function")).get("name").equals("delegate_analysis")).toList(),Math.max(1,remaining())));
            try {
                while(true){active();try{return future.get(Math.min(200,Math.max(1,remaining())),TimeUnit.MILLISECONDS);}catch(TimeoutException e){active();}}
            }catch(ExecutionException e){if(e.getCause() instanceof Exception cause)throw cause;throw new ContractException("MODEL_UNAVAILABLE","Model failed");}
            finally {future.cancel(true);executor.shutdownNow();}
        }
        private Object dispatch(String name,JsonNode args)throws Exception {
            active();return switch(name) {
                case "inspect_repository" -> {
                    parse(args,Empty.class);
                    if(repositoryInventory==null)repositoryInventory=RepositoryInspector.inspect(root,req,cancelled,Math.min(deadline,System.nanoTime()+req.collectionBudget().timeoutMillis()*1_000_000));
                    var result=new LinkedHashMap<String,Object>();result.put("repository",repositoryInventory.projection());
                    var context=req.businessContext();
                    if(context!=null&&context.source()!=null) {
                        // Product/version only; never database names, addresses, object names or credentials.
                        var db=DatabaseCollectors.collect(context.source(),businessConnection,cancelled,Math.min(deadline,System.nanoTime()+10_000_000_000L),true);
                        String observed=null;
                        var version=Pattern.compile("(?<![0-9.])[0-9]+(?:\\.[0-9]+){0,3}(?:c|g)?(?![0-9.])").matcher(Objects.toString(db.observedVersion(),""));
                        if(version.find())observed=version.group();
                        result.put("database",Map.of("product",context.source().product(),"observedVersion",Objects.toString(observed,""),"gapCodes",db.coverageGaps()));
                        observedDatabase=new Profile(context.source().product(),observed);
                    }
                    yield result;
                }
                case "delegate_analysis" -> {
                    require(!childMode&&subtasks.size()<2,"AGENT_DELEGATION_LIMIT","Subtask count/depth exceeded");
                    require(repositoryInventory!=null,"REPOSITORY_INSPECTION_REQUIRED","Inspect the repository before delegation");
                    var taskTarget=parse(args,Target.class);validateTarget(taskTarget);
                    require(subtasks.stream().noneMatch(r->Objects.equals(r.request().target(),taskTarget)),"DUPLICATE_ANALYSIS","Target already delegated");
                    int available=Math.min(6,req.agentBudget().maxModelCalls()-calls-1);
                    int toolsAvailable=Math.min(8,req.agentBudget().maxToolCalls()-toolCount-1);
                    require(available>=5&&toolsAvailable>=5&&remaining()>=100,"AGENT_DELEGATION_BUDGET","Insufficient shared budget for a subtask");
                    var childRequest=new Request(req.schemaVersion(),req.objective(),taskTarget,req.constraints(),req.invariants(),req.files(),req.columnRequest(),req.collectionBudget(),
                            new Limits(available,toolsAvailable,Math.min(remaining(),120000)),req.businessContext());
                    AgentModel counted=(m,t,time)->{calls++;return model.next(m,t,time);};
                    var child=new AgentOrchestrator(counted,businessConnection).new Run(root,childRequest,1,null,answers,()->cancelled.getAsBoolean()||remaining()<=0,checkpoint);
                    child.childMode=true;child.repositoryInventory=repositoryInventory;child.observedDatabase=observedDatabase;
                    child.messages.add(Map.of("role","user","content",Json.canonical(Map.of("assignedTarget",taskTarget,"repository",repositoryInventory.projection(),"instruction","你是本次目标的子 Agent；技术事实已检查。直接 propose_target 后完成本目标，不再委派。"))));
                    var report=child.run();toolCount+=report.trace().size();subtasks.add(report);
                    yield Map.of("subtask",subtasks.size(),"status",report.status(),"target",taskTarget,"questions",report.questions(),"diagnostics",report.diagnostics(),
                            "result",report.investigation()==null?Map.of("status","NO_ANALYSIS"):projection(report.investigation(),false,report.investigation().findings().stream().limit(20).toList()));
                }
                case "propose_target" -> {
                    require(analysis==null,"TARGET_LOCKED","Cannot alter target after collecting evidence");
                    var proposal=parse(args,Target.class);validateTarget(proposal);target=proposal;targetChosen=true;rulesListed=false;selected.clear();
                    yield Map.of("status","PARSED_FROM_USER_INPUT","missingFields",missingFields());
                }
                case "list_rules" -> {
                    parse(args,Empty.class);var applicable=targetChosen?ScenarioRules.applicableRuleIds(toRequest(target)):List.<String>of();
                    var rules=targetChosen?RuleCatalog.all().stream().filter(r->applicable.contains(r.ruleId())).toList():RuleCatalog.all();
                    rulesListed=targetChosen;yield Map.of("rules",rules,"targetChosen",targetChosen,"support",targetChosen&&rules.isEmpty()?"NO_SUPPORTED_RULES":"DECLARED_VERSION_SCOPE");
                }
                case "run_analysis" -> {
                    var input=parse(args,Rules.class);require(targetChosen&&rulesListed,"TARGET_AND_RULES_REQUIRED","Parse target and list rules first");
                    require(complete(target),"TARGET_NEEDS_CLARIFICATION","Missing required target fields");
                    require(input.ruleIds()!=null&&input.ruleIds().size()<=100&&new HashSet<>(input.ruleIds()).size()==input.ruleIds().size(),"INVALID_RULE_SELECTION","Invalid rule IDs");
                    var applicable=ScenarioRules.applicableRuleIds(toRequest(target));require(applicable.containsAll(input.ruleIds()),"RULE_NOT_APPLICABLE","Rule outside declared target");
                    require(applicable.isEmpty()||!input.ruleIds().isEmpty(),"INVALID_RULE_SELECTION","Select at least one applicable rule");
                    require(analysisCalls<3,"AGENT_ANALYSIS_BUDGET","Analysis tool budget exhausted");
                    var union=new TreeSet<>(selected);union.addAll(input.ruleIds());
                    require(analysis==null||!union.equals(selected),"DUPLICATE_ANALYSIS","No new rules selected");analysisCalls++;
                    selected.clear();selected.addAll(union);analysis=analyze(target,selected);readFindings.clear();explanations=List.of();
                    yield projection(analysis,false,analysis.findings().stream().limit(40).toList());
                }
                case "inspect_dotnet_semantics" -> {
                    parse(args,Empty.class);
                    require(repositoryInventory!=null,"REPOSITORY_INSPECTION_REQUIRED","Inspect repository first");
                    if(dotnetSemantics==null)dotnetSemantics=DotnetSemantics.inspect(root,req.files(),cancelled,remaining());
                    var summary=new LinkedHashMap<String,Object>(dotnetSemantics.projection());
                    summary.put("authorizedFileCount",req.files().size());
                    summary.put("authorizedCsharpFileCount",req.files().stream().filter(f->f.toLowerCase(Locale.ROOT).endsWith(".cs")).count());
                    yield summary;
                }
                case "read_evidence" -> {
                    var input=parse(args,EvidenceQuery.class);require(analysis!=null,"ANALYSIS_REQUIRED","Run analysis first");
                    require(input.findingIds()!=null&&!input.findingIds().isEmpty()&&input.findingIds().size()<=40,"INVALID_EVIDENCE_QUERY","Request 1..40 findings");
                    var found=new ArrayList<InvestigationReport.Finding>();
                    for(String id:input.findingIds()) {var f=analysis.findings().stream().filter(x->x.findingId().equals(id)).findFirst().orElseThrow(()->new ContractException("EVIDENCE_NOT_FOUND","Finding not in this report"));found.add(f);}
                    readFindings.addAll(input.findingIds());yield projection(analysis,true,found);
                }
                case "draft_migration_plan" -> {
                    require(targetChosen&&target!=null&&target.scenario()==Scenario.LANGUAGE_MIGRATION,"TARGET_REQUIRED","Choose language migration target first");
                    require(dotnetSemantics!=null&&!dotnetSemantics.facts().isEmpty(),"SEMANTIC_EVIDENCE_REQUIRED","Inspect C# semantics first");
                    var proposed=parse(args,MigrationPlan.class);
                    Set<String> known=new HashSet<>();dotnetSemantics.exposedFacts().forEach(f->known.add(f.id()));
                    require(proposed.steps().stream().flatMap(s->s.factIds().stream()).allMatch(known::contains),"MIGRATION_FACT_REFERENCE_REJECTED","Plan references unknown facts");
                    require(proposed.steps().stream().anyMatch(s->!s.factIds().isEmpty()),"MIGRATION_PLAN_INVALID","At least one step must cite semantic facts");
                    String product=target.targetProfile()==null?"":target.targetProfile().product();
                    require(!product.isBlank()&&proposed.targetArchitecture().toLowerCase(Locale.ROOT).contains(product.toLowerCase(Locale.ROOT)),"TARGET_CONFLICT","Plan target differs from chosen target");
                    if(req.objective().toLowerCase(Locale.ROOT).contains("electron"))
                        require(proposed.targetArchitecture().toLowerCase(Locale.ROOT).contains("electron"),"TARGET_CONFLICT","Plan omitted declared UI target");
                    migrationPlan=proposed;
                    yield Map.of("status","UNVERIFIED_PROPOSAL","stepCount",proposed.steps().size(),"referencedFactCount",proposed.steps().stream().flatMap(s->s.factIds().stream()).distinct().count());
                }
                case "draft_feasibility_plan" -> {
                    require(analysis!=null,"ANALYSIS_REQUIRED","Run analysis before drafting a feasibility plan");
                    var proposed=parse(args,FeasibilityPlan.class);
                    Set<String> known=analysis.findings().stream().map(InvestigationReport.Finding::findingId).collect(java.util.stream.Collectors.toSet());
                    Set<String> refs=proposed.steps().stream().flatMap(s->s.evidenceRefs().stream()).collect(java.util.stream.Collectors.toSet());
                    require(!refs.isEmpty(),"FEASIBILITY_EVIDENCE_REQUIRED","At least one step must cite a finding");
                    require(known.containsAll(refs),"FEASIBILITY_EVIDENCE_REJECTED","Plan cites a finding absent from this analysis");
                    require(refs.stream().anyMatch(readFindings::contains),"FEASIBILITY_EVIDENCE_NOT_INSPECTED","Read the cited evidence before drafting the plan");
                    feasibilityPlan=proposed;
                    yield Map.of("status","UNVERIFIED_PROPOSAL","decision",proposed.decision(),
                            "stepCount",proposed.steps().size(),"riskCount",proposed.risks().size(),
                            "workload",Map.of("lowMonths",proposed.workload().lowMonths(),"highMonths",proposed.workload().highMonths(),"confidence",proposed.workload().confidence()));
                }
                case "ask_clarification" -> {
                    var input=parse(args,Clarify.class);require(input.questions()!=null&&!input.questions().isEmpty()&&input.questions().size()<=6,"INVALID_QUESTIONS","Ask 1..6 questions");
                    Set<String> fields=new HashSet<>();List<Question> pending=new ArrayList<>();
                    for(var q:input.questions()) {
                        require(q!=null&&FIELDS.contains(q.field())&&fields.add(q.field()),"INVALID_QUESTIONS","Question field not allowed or duplicated");
                        AgentContracts.bounded(q.prompt(),500);
                        require(q.field().equals("context")||declared(q.field())==null,"FIELD_ALREADY_DECLARED","Do not ask for already declared fields");
                        pending.add(new Question(questionId(requestHash,revision,q.field()),q.field(),q.prompt()));
                    }
                    questions=List.copyOf(pending);clarifying=true;yield Map.of("status","NEEDS_CLARIFICATION","questions",questions);
                }
                case "finish" -> {
                    var input=parse(args,Finish.class);
                    boolean dotnetMigration=target!=null&&target.scenario()==Scenario.LANGUAGE_MIGRATION&&target.sourceProfile()!=null
                            &&DotnetInventory.platformProduct(target.sourceProfile().product())
                            &&req.files().stream().anyMatch(f->f.toLowerCase(Locale.ROOT).endsWith(".cs"));
                    require(!dotnetMigration||dotnetSemantics!=null,"SEMANTIC_INSPECTION_REQUIRED","Inspect authorized C# semantics before finishing");
                    require(!dotnetMigration||dotnetSemantics.facts().isEmpty()||migrationPlan!=null,"SEMANTIC_PLAN_REQUIRED","Draft a fact-grounded migration plan before finishing");
                    if(migrationPlan!=null&&input.explanations()!=null&&input.explanations().isEmpty()) {
                        finished=true;yield Map.of("status","PARTIAL","planStatus","UNVERIFIED_PROPOSAL");
                    }
                    if(feasibilityPlan!=null&&input.explanations()!=null&&input.explanations().isEmpty()) {
                        finished=true;yield Map.of("status","PARTIAL","planStatus","UNVERIFIED_PROPOSAL","decision",feasibilityPlan.decision());
                    }
                    if(analysis==null&&!subtasks.isEmpty()&&input.explanations()!=null&&input.explanations().isEmpty()) {
                        if(subtasks.stream().anyMatch(r->r.status()==Status.NEEDS_CLARIFICATION)) {
                            questions=subtaskQuestions();
                        }
                        finished=true;yield Map.of("status",questions.isEmpty()?"PARTIAL":"NEEDS_CLARIFICATION","subtaskCount",subtasks.size());
                    }
                    require(analysis!=null,"ANALYSIS_REQUIRED","Cannot finish before investigation");
                    require(input.explanations()!=null&&!input.explanations().isEmpty()&&input.explanations().size()<=6,"AGENT_EXPLANATION_INVALID","Explain 1..6 inspected findings");
                    Set<String> seen=new HashSet<>();
                    for(var explanation:input.explanations()) {
                        require(explanation!=null&&readFindings.contains(explanation.findingId())&&seen.add(explanation.findingId()),"EVIDENCE_NOT_INSPECTED","Inspect unique findings before explaining");
                        var f=analysis.findings().stream().filter(x->x.findingId().equals(explanation.findingId())).findFirst().orElseThrow();
                        require(f.outcome().equals(explanation.outcome()),"OUTCOME_OVERRIDE_REJECTED","Model cannot change authoritative outcome");
                        require(f.evidenceIds().containsAll(explanation.evidenceIds())&&(f.evidenceIds().isEmpty()||!explanation.evidenceIds().isEmpty()),"EVIDENCE_REFERENCE_REJECTED","Invalid evidence references");
                    }
                    explanations=List.copyOf(input.explanations());finished=true;yield Map.of("status","PARTIAL","explanationStatus","MODEL_EXPLANATION_UNVERIFIED");
                }
                default -> throw new ContractException("TOOL_NOT_ALLOWED","Tool not registered");
            };
        }
        private InvestigationReport analyze(Target value,Set<String> ruleIds)throws Exception {
            active();var r=toRequest(value);long budget=Math.max(1,Math.min(r.budget().timeoutMillis(),remaining()));
            r=new InvestigationRequest(r.schemaVersion(),r.scenario(),r.sourceProfile(),r.targetProfile(),r.objective(),r.constraints(),r.invariants(),r.files(),r.columnRequest(),
                    new Budget(r.budget().maxFiles(),r.budget().maxBytes(),budget));
            // 使用较短的临时预算执行，但报告请求保持本次实际预算，方便复核时间限制。
            var result=new InvestigationEngine(businessConnection).investigate(root,r,()->cancelled.getAsBoolean()||remaining()<=0,checkpoint,ruleIds,req.businessContext());
            active();return result;
        }
        private Object projection(InvestigationReport result,boolean evidence,List<InvestigationReport.Finding> findings) {
            var items=new ArrayList<Map<String,Object>>();
            for(var f:findings) {
                Map<String,Object> item=new LinkedHashMap<>();item.put("findingId",f.findingId());item.put("ruleRef",f.ruleRef());item.put("outcome",f.outcome());
                item.put("evidenceIds",f.evidenceIds());
                if(evidence){item.put("evidenceKinds",f.evidence().stream().map(InvestigationReport.Evidence::kind).distinct().toList());
                    item.put("rule",f.rule());item.put("hasUnverifiedConditions",!f.conditions().isEmpty());item.put("hasUnknownReasons",!f.unknownReasons().isEmpty());}
                items.add(item);
            }
            // 不包含 summary、gap.source、impact.subject、源码位置或片段；这些字段可能携带业务名称/路径。
            return Map.of("status",result.status(),"findings",items,"totalFindings",result.findings().size(),"findingsTruncated",!evidence&&findings.size()<result.findings().size(),"sourceCount",result.sources().size(),"platformProjectCount",result.dotnetInventory()==null?0:result.dotnetInventory().projects().size(),
                    "gapCodes",result.coverageGaps().stream().map(InvestigationReport.Gap::code).distinct().toList(),"projection","ANONYMIZED_RULE_EVIDENCE_SUMMARY");
        }
        private void validateTarget(Target p) {
            AgentContracts.validateBusinessTarget(p,req.businessContext());
            if(p!=null&&p.scenario()==Scenario.LANGUAGE_MIGRATION&&p.targetProfile()!=null&&req.businessContext()!=null
                    &&req.businessContext().targetEnvironment()!=null&&req.businessContext().targetEnvironment().runtime()!=null) {
                Profile declared=req.businessContext().targetEnvironment().runtime();
                require(RepositoryInspector.sameProduct(declared.product(),p.targetProfile().product())
                        &&(declared.version()==null||declared.version().equals(p.targetProfile().version())),
                        "TARGET_CONTEXT_CONFLICT","Target runtime conflicts with user declaration");
            }
            require(p!=null&&p.scenario()!=null&&p.sourceProfile()!=null,"AGENT_TARGET_INVALID","Missing target structure");
            require((p.scenario()==Scenario.COLUMN_CHANGE)==(req.columnRequest()!=null),"AGENT_TARGET_INVALID","Column adapter is controlled by request");
            String declaredScenario=declared("scenario");if(declaredScenario!=null)require(declaredScenario.equals(p.scenario().name()),"TARGET_CONFLICT","Scenario conflicts with user input");
            boolean observed=repositoryInventory!=null&&repositoryInventory.supports(p.sourceProfile())
                    || observedDatabase!=null&&RepositoryInspector.sameProduct(observedDatabase.product(),p.sourceProfile().product())
                    && (p.sourceProfile().version()==null||Objects.equals(observedDatabase.version(),p.sourceProfile().version()));
            ground("sourceProfile.product",p.sourceProfile().product(),observed);ground("sourceProfile.version",p.sourceProfile().version(),observed);
            if(p.targetProfile()!=null){ground("targetProfile.product",p.targetProfile().product(),environmentDeclares(p.scenario(),p.targetProfile())||p.scenario()==Scenario.DEPENDENCY_UPGRADE&&RepositoryInspector.sameProduct(p.sourceProfile().product(),p.targetProfile().product()));ground("targetProfile.version",p.targetProfile().version(),environmentDeclares(p.scenario(),p.targetProfile()));}
            else require(declared("targetProfile.product")==null,"TARGET_CONFLICT","Target profile cannot be erased");
        }
        private boolean environmentDeclares(Scenario scenario,Profile target){
            if(req.businessContext()==null||req.businessContext().targetEnvironment()==null||target==null)return false;
            var e=req.businessContext().targetEnvironment();
            Profile declared=scenario==Scenario.DATABASE_MIGRATION?e.database():scenario==Scenario.LANGUAGE_MIGRATION||scenario==Scenario.DEPENDENCY_UPGRADE?e.runtime():null;
            return declared!=null&&RepositoryInspector.sameProduct(declared.product(),target.product())
                    && (target.version()==null||Objects.equals(declared.version(),target.version()));
        }
        private void ground(String field,String value,boolean observed) {
            String explicit=declared(field);
            if(explicit!=null){require(explicit.equalsIgnoreCase(value),"TARGET_CONFLICT","Model changed declared input");return;}
            if(value==null||observed)return;
            require(Pattern.compile((field.endsWith(".version")?"(?<![0-9.])":"(?<![A-Za-z0-9.])")+Pattern.quote(value)+"(?![A-Za-z0-9.])",Pattern.CASE_INSENSITIVE).matcher(req.objective()+" "+answers.getOrDefault("context","")).find() || field.endsWith(".product")&&productMentioned(value),
                    "UNGROUNDED_TARGET","Product/version must occur in declared input; otherwise clarify");
        }
        private boolean productMentioned(String product){
            String text=(req.objective()+" "+answers.getOrDefault("context","")).toLowerCase(Locale.ROOT).replace(" ","");
            return switch(product.toLowerCase(Locale.ROOT)){case "spring boot"->text.contains("springboot")||text.contains("spring-boot");case ".net"->text.contains(".net")||text.contains("dotnet");case "java"->text.contains("java")||text.contains("jdk");default->false;};
        }
        private String declared(String field) {
            if(answers.containsKey(field))return answers.get(field);
            return declaredField(req.target(),field);
        }
        private Target declaredTarget() {
            String scenario=declared("scenario"),sp=declared("sourceProfile.product"),tp=declared("targetProfile.product");
            try{return new Target(scenario==null?null:Scenario.valueOf(scenario),sp==null?null:new Profile(sp,declared("sourceProfile.version")),tp==null?null:new Profile(tp,declared("targetProfile.version")));}
            catch(Exception e){throw new ContractException("INVALID_ANSWERS","Invalid technical profile in user answers");}
        }
        private boolean complete(Target t) {
            return t!=null&&t.scenario()!=null&&t.sourceProfile()!=null&&(t.scenario()==Scenario.CURRENT_STATE||t.scenario()==Scenario.COLUMN_CHANGE
                    ||(t.sourceProfile().version()!=null||DotnetInventory.platformProduct(t.sourceProfile().product()))&&t.targetProfile()!=null&&t.targetProfile().version()!=null);
        }
        private List<String> missingFields() {
            List<String> fields=new ArrayList<>();if(target==null||target.scenario()==null)fields.add("scenario");
            if(target==null||target.sourceProfile()==null)fields.add("sourceProfile.product");
            if(target==null||target.scenario()!=Scenario.CURRENT_STATE&&target.scenario()!=Scenario.COLUMN_CHANGE) {
                if(target==null||target.sourceProfile()==null||target.sourceProfile().version()==null&&!DotnetInventory.platformProduct(target.sourceProfile().product()))fields.add("sourceProfile.version");
                if(target==null||target.targetProfile()==null)fields.add("targetProfile.product");
                if(target==null||target.targetProfile()==null||target.targetProfile().version()==null)fields.add("targetProfile.version");
            }
            return fields;
        }
        private List<Question> missingQuestions(){if(req.target()==null)return List.of(new Question(questionId(requestHash,revision,"context"),"context","请补充目标中缺失的技术对象、目标版本或业务约束。"));return missingFields().stream().map(field->new Question(questionId(requestHash,revision,field),field,"请补充 "+field+"，以便继续基于版本的调查。")).toList();}
        private Target investigationTarget(){return target!=null&&target.scenario()!=null&&target.sourceProfile()!=null?target:
                new Target(req.columnRequest()==null?Scenario.CURRENT_STATE:Scenario.COLUMN_CHANGE,new Profile("UNSPECIFIED",null),null);}
        private InvestigationRequest toRequest(Target t){return new InvestigationRequest(InvestigationRequest.VERSION,t.scenario(),t.sourceProfile(),t.targetProfile(),req.objective(),req.constraints(),req.invariants(),req.files(),req.columnRequest(),req.collectionBudget());}
        private long remaining(){return Math.max(0,(deadline-System.nanoTime())/1_000_000);}
        private void active(){require(!cancelled.getAsBoolean(),"AGENT_CANCELLED","Investigation cancelled");require(remaining()>0,"AGENT_TIME_BUDGET","Agent elapsed-time budget exhausted");}
    }
    private static <T>T parse(JsonNode args,Class<T> type) {
        try {T value=Json.MAPPER.treeToValue(args,type);require(value!=null,"AGENT_ARGUMENT_INVALID","Missing argument object");return value;}
        catch(Exception e){throw new ContractException("AGENT_ARGUMENT_INVALID","Tool arguments failed schema validation");}
    }
    private static String code(Exception e){return e instanceof ContractException c?c.code():"AGENT_OPERATION_FAILED";}
    /** 错误反馈仅来自本地常量，既让模型能够纠正参数，也避免将原始异常及路径发到服务端。 */
    private static String correction(String code) {
        return switch(code) {
            case "AGENT_EXPLANATION_INVALID"->"finish.explanations 必须为 1 至 6 条，只保留最关键的发现。每条 verificationSuggestions 至多 8 项。请缩短列表后重试 finish。";
            case "TARGET_NEEDS_CLARIFICATION"->"普通规则分析缺少必需版本。请询问用户，或对 .NET 语言迁移先使用 inspect_dotnet_semantics 与 draft_migration_plan 制定版本待确认的草案。";
            case "UNGROUNDED_TARGET"->"不要编造产品或版本。若用户要将 .NET 迁至 C++ 核心/Electron UI，可用源 .NET version=null、目标 C++ version=null 提议语言迁移，再做本地语义调查；未知版本留待方案 openQuestions。";
            case "OUTCOME_OVERRIDE_REJECTED","EVIDENCE_REFERENCE_REJECTED","EVIDENCE_NOT_INSPECTED"->"重新 read_evidence，原样复制其中的 findingId、evidenceIds、outcome 后提交 finish。";
            case "SEMANTIC_INSPECTION_REQUIRED"->"先调用 inspect_dotnet_semantics 获取本地 C# 符号事实及覆盖缺口。";
            case "SEMANTIC_PLAN_REQUIRED"->"根据 inspect_dotnet_semantics 返回的匿名事实编号调用 draft_migration_plan，再 finish。";
            case "MIGRATION_FACT_REFERENCE_REJECTED"->"draft_migration_plan 只能引用 inspect_dotnet_semantics 返回的前 60 条匿名事实编号。";
            default->"按工具 Schema 和状态要求纠正参数；本次错误未授权任何额外操作。";
        };
    }
    private static InvestigationReport invalidate(InvestigationReport r,String code) {
        var gaps=new ArrayList<>(r.coverageGaps());gaps.add(new InvestigationReport.Gap(code,"Agent finalization invalidated current-source findings"));
        var unknown=new InvestigationReport.Finding(Json.hashParts(r.inputFingerprint(),code),"UNKNOWN","agent-finalization",r.sources().stream().map(InvestigationReport.Source::sourceId).toList(),List.of(),List.of(code));
        return new InvestigationReport(r.schemaVersion(),r.engineVersion(),Json.hashParts(r.inputFingerprint(),code),r.request(),code.equals("AGENT_CANCELLED")?InvestigationReport.Status.CANCELLED:InvestigationReport.Status.PARTIAL,
                r.startedAt(),Instant.now().toString(),r.sources(),gaps,r.clarificationItems(),List.of(unknown),r.verificationSuggestions(),r.orchestration(),null);
    }
}
