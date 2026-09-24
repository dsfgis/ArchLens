package io.archlens;

import com.fasterxml.jackson.databind.JsonNode;
import io.archlens.agent.*;
import io.archlens.agent.AgentContracts.*;
import io.archlens.contract.*;
import io.archlens.investigation.InvestigationRequest.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实解析器及可控模型响应检查编排边界，不把脚本模型冒充真实服务联调。 */
class AgentTest {
    @TempDir Path root;
    Request request;
    @BeforeEach void setup()throws Exception {
        Files.writeString(root.resolve("private-ledger.sql"),"CREATE TABLE secret_customer (id INT UNSIGNED, value INT); SELECT IFNULL(value, 0) FROM secret_customer; -- secret_token_42");
        request=req(new Target(Scenario.DATABASE_MIGRATION,new Profile("MySQL","8.0.36"),new Profile("PostgreSQL","16")),new Limits(12,16,30000));
    }
    Request req(Target target,Limits limits){return new Request(AgentContracts.VERSION,"调查 MySQL 8.0.36 到 PostgreSQL 16 的兼容性",target,List.of("只读"),List.of(),List.of("private-ledger.sql"),null,new Budget(10,100000,10000),limits);}
    static AgentModel.Call call(int id,String name,Object args){return new AgentModel.Call("call_"+id,name,Json.MAPPER.valueToTree(args));}
    static JsonNode last(List<Map<String,Object>> messages)throws Exception{return Json.MAPPER.readTree((String)messages.getLast().get("content"));}
    class Script implements AgentModel {
        int step;Target target;boolean subset;List<String> captured=new ArrayList<>();java.util.function.Consumer<Integer> before=s->{};
        String badOutcome,badEvidence;boolean unknownTool;
        Script(Target target){this.target=target;}
        public Call next(List<Map<String,Object>> messages,List<Map<String,Object>> tools,long timeout)throws Exception {
            captured.add(Json.canonical(messages));before.accept(step);int current=step++;
            if(unknownTool)return call(current,"exec_shell",Map.of("cmd","delete everything"));
            return switch(current) {
                case 0 -> call(current,"propose_target",target);
                case 1 -> call(current,"list_rules",Map.of());
                case 2 -> {List<String> ids=new ArrayList<>();for(var r:last(messages).path("rules"))ids.add(r.path("ruleId").asText());if(subset&&!ids.isEmpty())ids=ids.subList(0,1);yield call(current,"run_analysis",Map.of("ruleIds",ids));}
                case 3 -> call(current,"read_evidence",Map.of("findingIds",List.of(last(messages).path("findings").get(0).path("findingId").asText())));
                default -> {var f=last(messages).path("findings").get(0);List<String> ids=new ArrayList<>();f.path("evidenceIds").forEach(x->ids.add(x.asText()));
                    yield call(current,"finish",Map.of("explanations",List.of(new Explanation(f.path("findingId").asText(),badEvidence==null?ids:List.of(badEvidence),badOutcome==null?f.path("outcome").asText():badOutcome,"根据所列规则和证据引用，需要验证适用范围。",List.of("在隔离环境验证边界值")))));}
            };
        }
    }
    @Test void realToolsProduceGroundedReportAndKeepPrivateSourcesLocal()throws Exception {
        var model=new Script(request.target());var r=new AgentOrchestrator(model).investigate(root,request,()->false);
        assertEquals("MODEL_TOOL_LOOP",r.orchestration());assertEquals(Status.PARTIAL,r.status());assertEquals(5,r.modelCalls());
        assertEquals(List.of("propose_target","list_rules","run_analysis","read_evidence","finish"),r.trace().stream().map(Trace::tool).toList());
        assertEquals(5,r.selectedRuleIds().size());assertFalse(r.explanations().isEmpty());assertEquals("MODEL_EXPLANATION_UNVERIFIED",r.explanationStatus());
        assertTrue(r.investigation().findings().stream().anyMatch(f->f.outcome().equals("INCOMPATIBLE")));
        String sent=String.join("",model.captured);for(String secret:List.of("private-ledger.sql","secret_customer","secret_token_42",root.toString()))assertFalse(sent.contains(secret),secret);
        assertEquals(r,Json.MAPPER.readValue(Json.canonical(r),Report.class));
    }
    @Test void omittedRulesRemainVisibleAsCoverageGap()throws Exception {
        var model=new Script(request.target());model.subset=true;var r=new AgentOrchestrator(model).investigate(root,request,()->false);
        assertEquals(1,r.selectedRuleIds().size());assertTrue(r.investigation().coverageGaps().stream().anyMatch(g->g.code().equals("RULES_NOT_SELECTED")));
        assertTrue(r.investigation().findings().stream().filter(f->f.rule()!=null).allMatch(f->r.selectedRuleIds().contains(f.rule().ruleId())));
    }
    @Test void clarificationResumesAsNewRevisionAndRecollectsChangedFiles()throws Exception {
        request=req(new Target(Scenario.DATABASE_MIGRATION,new Profile("MySQL",null),new Profile("PostgreSQL","16")),new Limits(12,16,30000));
        var first=new AgentOrchestrator((m,t,time)->call(0,"ask_clarification",Map.of("questions",List.of(Map.of("field","sourceProfile.version","prompt","源库的具体版本是什么？"))))).investigate(root,request,()->false);
        assertEquals(Status.NEEDS_CLARIFICATION,first.status());assertNull(first.investigation());
        Files.writeString(root.resolve("private-ledger.sql"),"CREATE TABLE revised (value INT);");
        var answers=new Answers(AgentContracts.VERSION,Json.hash(first),Map.of(first.questions().getFirst().questionId(),"8.0.36"));
        var full=new Target(Scenario.DATABASE_MIGRATION,new Profile("MySQL","8.0.36"),new Profile("PostgreSQL","16"));
        var second=new AgentOrchestrator(new Script(full)).execute(root,request,first,answers,()->false,s->{});
        assertEquals(2,second.revision());assertEquals(Json.hash(first),second.parentReportHash());assertEquals(Status.PARTIAL,second.status());
        assertEquals(Json.sha256(Files.readAllBytes(root.resolve("private-ledger.sql"))),second.investigation().sources().getFirst().sha256());
        ContractTest.assertCode("STALE_ANSWERS",()->new AgentOrchestrator(null).execute(root,request,first,new Answers(AgentContracts.VERSION,"a".repeat(64),answers.answers()),()->false,s->{}));
        ContractTest.assertCode("INVALID_ANSWERS",()->new AgentOrchestrator(null).execute(root,request,first,new Answers(AgentContracts.VERSION,Json.hash(first),Map.of("wrong","8")),()->false,s->{}));
    }
    @Test void kingbaseDoesNotBorrowPostgresRules()throws Exception {
        request=req(new Target(Scenario.DATABASE_MIGRATION,new Profile("Oracle","19c"),new Profile("KingbaseES","V8R6")),new Limits(12,16,30000));
        var r=new AgentOrchestrator(new Script(request.target())).investigate(root,request,()->false);
        assertEquals("MODEL_TOOL_LOOP",r.orchestration());assertTrue(r.selectedRuleIds().isEmpty());
        assertTrue(r.investigation().findings().stream().allMatch(f->f.outcome().equals("UNKNOWN")));
    }
    @Test void unregisteredToolCannotExecuteAndFallsBackAfterThreeAttempts()throws Exception {
        var model=new Script(request.target());model.unknownTool=true;var r=new AgentOrchestrator(model).investigate(root,request,()->false);
        assertEquals(3,r.modelCalls());assertEquals("DETERMINISTIC_FALLBACK",r.orchestration());assertTrue(r.diagnostics().contains("TOOL_NOT_ALLOWED"));
        assertTrue(Files.exists(root.resolve("private-ledger.sql")));assertNotNull(r.investigation());
    }
    @Test void modelCannotChangeDeclaredVersion()throws Exception {
        var proposed=new Target(Scenario.DATABASE_MIGRATION,new Profile("MySQL","8.0.36"),new Profile("PostgreSQL","17"));
        AtomicInteger n=new AtomicInteger();var r=new AgentOrchestrator((m,t,time)->call(n.incrementAndGet(),"propose_target",proposed)).investigate(root,request,()->false);
        assertTrue(r.diagnostics().contains("TARGET_CONFLICT"));assertEquals("16",r.interpretedTarget().targetProfile().version());
    }
    @Test void modelCannotInventUndeclaredVersion()throws Exception {
        request=new Request(AgentContracts.VERSION,"调查 MySQL 到 PostgreSQL 的迁移",null,List.of(),List.of(),request.files(),null,request.collectionBudget(),request.agentBudget());
        AtomicInteger n=new AtomicInteger();var proposal=new Target(Scenario.DATABASE_MIGRATION,new Profile("MySQL","8.0"),new Profile("PostgreSQL","16"));
        var r=new AgentOrchestrator((m,t,time)->call(n.incrementAndGet(),"propose_target",proposal)).investigate(root,request,()->false);
        assertTrue(r.diagnostics().contains("UNGROUNDED_TARGET"));assertEquals(Status.NEEDS_CLARIFICATION,r.status());
    }
    @Test void changedOutcomeAndInventedEvidenceAreRejected()throws Exception {
        for(boolean outcome:List.of(true,false)) {
            var script=new Script(request.target());if(outcome)script.badOutcome="SAFE";else script.badEvidence="invented";
            var r=new AgentOrchestrator(script).investigate(root,request,()->false);
            assertTrue(r.explanations().isEmpty());assertTrue(r.diagnostics().contains(outcome?"OUTCOME_OVERRIDE_REJECTED":"EVIDENCE_REFERENCE_REJECTED"));
        }
    }
    @Test void sourceDriftDuringExplanationInvalidatesFindings()throws Exception {
        var script=new Script(request.target());script.before=n->{if(n==4)try{Files.writeString(root.resolve("private-ledger.sql"),"SELECT 42;");}catch(Exception e){throw new RuntimeException(e);}};
        var r=new AgentOrchestrator(script).investigate(root,request,()->false);
        assertTrue(r.diagnostics().contains("AGENT_SOURCE_DRIFT"));assertTrue(r.explanations().isEmpty());assertTrue(r.investigation().findings().stream().allMatch(f->f.outcome().equals("UNKNOWN")));
    }
    @Test void cancellationStopsBlockedModelAndDiscardsLateAnswer()throws Exception {
        AtomicBoolean cancel=new AtomicBoolean();
        var r=new AgentOrchestrator((m,t,time)->{cancel.set(true);Thread.sleep(10000);return call(0,"list_rules",Map.of());}).investigate(root,request,cancel::get);
        assertEquals(Status.CANCELLED,r.status());assertTrue(r.trace().isEmpty());assertTrue(r.explanations().isEmpty());
    }
    @Test void deadlineBoundsUnresponsiveModel()throws Exception {
        request=req(request.target(),new Limits(5,5,100));long start=System.nanoTime();
        var r=new AgentOrchestrator((m,t,time)->{Thread.sleep(10000);return call(0,"list_rules",Map.of());}).investigate(root,request,()->false);
        assertTrue((System.nanoTime()-start)/1_000_000<2500);assertTrue(r.diagnostics().contains("AGENT_TIME_BUDGET"));assertTrue(r.explanations().isEmpty());
    }
    @Test void callBudgetEndsLoopAndNoKeyUsesDeterministicFallback()throws Exception {
        request=req(request.target(),new Limits(2,5,30000));AtomicInteger n=new AtomicInteger();
        var r=new AgentOrchestrator((m,t,time)->call(n.incrementAndGet(),"list_rules",Map.of())).investigate(root,request,()->false);
        assertEquals(2,r.modelCalls());assertTrue(r.diagnostics().contains("AGENT_MODEL_BUDGET"));assertNotNull(r.investigation());
        var offline=new AgentOrchestrator(null).investigate(root,request,()->false);assertEquals(0,offline.modelCalls());assertTrue(offline.diagnostics().contains("MODEL_NOT_CONFIGURED"));
    }
    @Test void toolArgumentsCannotExpandSourceScope()throws Exception {
        AtomicInteger n=new AtomicInteger();var r=new AgentOrchestrator((m,t,time)->call(n.incrementAndGet(),"propose_target",Map.of("scenario","DATABASE_MIGRATION","sourceProfile",request.target().sourceProfile(),"targetProfile",request.target().targetProfile(),"files",List.of("../outside.sql")))).investigate(root,request,()->false);
        assertTrue(r.diagnostics().contains("AGENT_ARGUMENT_INVALID"));assertEquals(1,r.investigation().sources().size());
        ContractTest.assertCode("PATH_OUTSIDE_ROOT",()->new Request(AgentContracts.VERSION,request.objective(),request.target(),List.of(),List.of(),List.of("../outside.sql"),null,request.collectionBudget(),request.agentBudget()));
    }
    @Test void modelCanCorrectRejectedActionWithinSameLoop()throws Exception {
        var script=new Script(request.target());AtomicBoolean rejected=new AtomicBoolean();
        AgentModel model=(messages,tools,time)->{
            if(script.step==4&&!rejected.getAndSet(true)) {
                var f=last(messages).path("findings").get(0);var e=new Explanation(f.path("findingId").asText(),List.of(),f.path("outcome").asText(),"解释",List.of());
                return call(80,"finish",Map.of("explanations",Collections.nCopies(7,e)));
            }
            if(rejected.get()&&script.step==4) {
                assertTrue(last(messages).path("instruction").asText().contains("1 至 6"));
                // 删除测试中的错误往返，读取前一次真实证据，模拟模型按提示纠正。
                return script.next(messages.subList(0,messages.size()-2),tools,time);
            }
            return script.next(messages,tools,time);
        };
        var r=new AgentOrchestrator(model).investigate(root,request,()->false);
        assertEquals("MODEL_TOOL_LOOP",r.orchestration());assertEquals(6,r.modelCalls());assertFalse(r.explanations().isEmpty());assertTrue(r.diagnostics().contains("AGENT_EXPLANATION_INVALID"));
    }
    @Test void toolBudgetIsIndependentOfModelBudget()throws Exception {
        request=req(request.target(),new Limits(10,1,30000));var r=new AgentOrchestrator(new Script(request.target())).investigate(root,request,()->false);
        assertEquals(1,r.trace().size());assertTrue(r.diagnostics().contains("AGENT_TOOL_BUDGET"));
    }
}
