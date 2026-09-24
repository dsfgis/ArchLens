package io.archlens.investigation;

import io.archlens.cli.ArchLensCli;
import io.archlens.contract.Model.Location;
import java.util.*;

public record InvestigationReport(String schemaVersion, String engineVersion, String inputFingerprint,
        InvestigationRequest request, Status status, String startedAt, String finishedAt,
        List<Source> sources, List<Gap> coverageGaps, List<Clarification> clarificationItems,
        List<Finding> findings, List<String> verificationSuggestions, String orchestration,
        ArchLensCli.Report columnAnalysis) {
    public enum Status { PARTIAL, CANCELLED }
    public record Source(String sourceId,String path,String sha256,long bytes,String collectedAt,
                         Location location,String producerVersion) {}
    public record Gap(String code,String source) {}
    public record Clarification(String field,String blocks) {}
    public record RuleBasis(String ruleId,String version,String sourceRange,String targetRange,
                            List<String> requiredFacts,List<String> officialSources,String reviewedAt) {
        public RuleBasis { requiredFacts=List.copyOf(requiredFacts); officialSources=List.copyOf(officialSources); }
    }
    public record Evidence(String evidenceId,String sourceId,String sourceHash,Location location,String kind) {}
    /** 影响项只描述已定位的声明或候选调用；不会据此伪造事实图中的依赖边。 */
    public record Impact(String subject,String certainty,String changeRequired,String reason) {}
    public record Finding(String findingId,String outcome,String ruleRef,List<String> evidenceIds,
                          List<String> conditions,List<String> unknownReasons,String subjectId,String summary,
                          List<Evidence> evidence,RuleBasis rule,List<String> recommendations,List<Impact> impacts) {
        public Finding(String id,String outcome,String ref,List<String> ids,List<String> conditions,List<String> unknown) {
            this(id,outcome,ref,ids,conditions,unknown,null,null,List.of(),null,List.of(),List.of());
        }
        public Finding {
            io.archlens.contract.ContractException.require(Set.of("COMPATIBLE","INCOMPATIBLE","CONDITIONAL","UNKNOWN").contains(outcome),
                    "INVALID_OUTCOME","Unsupported compatibility outcome");
            evidenceIds=List.copyOf(evidenceIds); conditions=List.copyOf(conditions); unknownReasons=List.copyOf(unknownReasons);
            // 新字段缺失时按空集合读取，兼容已封存的 v1 报告；新报告统一输出 v2。
            evidence=evidence==null?List.of():List.copyOf(evidence);
            recommendations=recommendations==null?List.of():List.copyOf(recommendations);
            impacts=impacts==null?List.of():List.copyOf(impacts);
        }
    }
    public InvestigationReport {
        sources=List.copyOf(sources); coverageGaps=List.copyOf(coverageGaps);
        clarificationItems=List.copyOf(clarificationItems); findings=List.copyOf(findings);
        verificationSuggestions=List.copyOf(verificationSuggestions);
    }
}
