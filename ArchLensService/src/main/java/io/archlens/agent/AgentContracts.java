package io.archlens.agent;

import io.archlens.contract.Json;
import io.archlens.investigation.*;
import java.util.*;
import static io.archlens.contract.ContractException.require;
import static io.archlens.investigation.InvestigationRequest.*;

/** Agent 输入、可恢复澄清及审计报告；模型解释始终与确定性调查结果分开保存。 */
public final class AgentContracts {
    private AgentContracts() {}
    public static final String VERSION="archlens.agent.v1";
    public enum Status { NEEDS_CLARIFICATION, PARTIAL, CANCELLED }
    public record Target(Scenario scenario,Profile sourceProfile,Profile targetProfile) {}
    public record Limits(int maxModelCalls,int maxToolCalls,long timeoutMillis) {
        public Limits {require(maxModelCalls>=1&&maxModelCalls<=16&&maxToolCalls>=1&&maxToolCalls<=24
                &&timeoutMillis>=100&&timeoutMillis<=120000,"AGENT_BUDGET_INVALID","Agent budget outside limits");}
    }
    public record Request(String schemaVersion,String objective,Target target,List<String> constraints,List<String> invariants,
                          List<String> files,String columnRequest,Budget collectionBudget,Limits agentBudget) {
        public Request {
            require(VERSION.equals(schemaVersion),"UNSUPPORTED_SCHEMA","Unsupported Agent contract");
            require(objective!=null&&objective.length()>=5&&objective.length()<=4000,"AGENT_OBJECTIVE_INVALID","Objective requires 5..4000 characters");
            Objects.requireNonNull(collectionBudget);Objects.requireNonNull(agentBudget);
            // 复用明确文件清单的路径与数量校验，模型没有扩展文件范围的入口。
            var validated=new InvestigationRequest(InvestigationRequest.VERSION,columnRequest==null?Scenario.CURRENT_STATE:Scenario.COLUMN_CHANGE,
                    new Profile("UNSPECIFIED",null),null,objective,constraints,invariants,files,columnRequest,collectionBudget);
            files=validated.files();constraints=validated.constraints();invariants=validated.invariants();
            if(target!=null&&target.scenario()!=null)require((target.scenario()==Scenario.COLUMN_CHANGE)==(columnRequest!=null),
                    "AGENT_TARGET_INVALID","Column adapter must agree with declared scenario");
        }
    }
    public record Question(String questionId,String field,String prompt) {}
    public record Answers(String schemaVersion,String parentReportHash,Map<String,String> answers) {
        public Answers {
            require(VERSION.equals(schemaVersion),"UNSUPPORTED_SCHEMA","Unsupported answers contract");
            require(parentReportHash!=null&&parentReportHash.matches("[a-f0-9]{64}"),"STALE_ANSWERS","Missing parent report hash");
            answers=Map.copyOf(answers);require(!answers.isEmpty()&&answers.size()<=6,"INVALID_ANSWERS","Answer count outside limits");
            answers.values().forEach(v->bounded(v,500));
        }
    }
    public record Trace(int step,String tool,String argumentsHash,String resultCode,String resultHash,long elapsedMillis) {}
    public record Explanation(String findingId,List<String> evidenceIds,String outcome,String explanation,List<String> verificationSuggestions) {
        public Explanation {bounded(findingId,100);evidenceIds=List.copyOf(evidenceIds);require(evidenceIds.size()<=100,"AGENT_EXPLANATION_INVALID","Too many evidence references");
            bounded(outcome,30);bounded(explanation,2000);verificationSuggestions=List.copyOf(verificationSuggestions);
            require(verificationSuggestions.size()<=8,"AGENT_EXPLANATION_INVALID","Too many suggestions");verificationSuggestions.forEach(v->bounded(v,500));}
    }
    public record Report(String schemaVersion,String engineVersion,String requestHash,Request request,int revision,String parentReportHash,
                         Status status,String orchestration,String startedAt,String finishedAt,Target interpretedTarget,
                         Map<String,String> userAnswers,List<Question> questions,List<Trace> trace,List<String> selectedRuleIds,
                         InvestigationReport investigation,List<Explanation> explanations,String explanationStatus,List<String> diagnostics,int modelCalls) {
        public Report {
            userAnswers=Map.copyOf(userAnswers);questions=List.copyOf(questions);trace=List.copyOf(trace);selectedRuleIds=List.copyOf(selectedRuleIds);
            explanations=List.copyOf(explanations);diagnostics=List.copyOf(diagnostics);
            require(VERSION.equals(schemaVersion)&&Json.hash(request).equals(requestHash),"AGENT_REPORT_INVALID","Report does not match original request");
            require(revision>=1&&revision<=8&&modelCalls>=0,"AGENT_REPORT_INVALID","Invalid report revision or call count");
        }
    }
    static void bounded(String s,int max){require(s!=null&&!s.isBlank()&&s.length()<=max,"AGENT_ARGUMENT_INVALID","Text outside permitted limits");}
}
