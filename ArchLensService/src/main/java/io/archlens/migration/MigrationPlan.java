package io.archlens.migration;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.archlens.contract.Json;
import java.util.*;
import static io.archlens.contract.ContractException.require;

/** Versioned design artifact. Structural validity does not mean the design is complete or verified. */
public record MigrationPlan(String schemaVersion, Identity identity, ObjectiveAndScope objectiveAndScope,
                            Section currentArchitecture, List<Alternative> alternatives,
                            @JsonProperty(required = true) String selectedAlternativeId,
                            Section targetArchitecture, List<Mapping> mappings,
                            List<WorkItem> workItems, List<ValidationItem> validationPlan,
                            RolloutAndRollback rolloutAndRollback, List<Assumption> assumptionsAndGaps,
                            List<String> evidenceRefs, List<String> decisionRefs, Estimate estimates) {
    public static final String VERSION = "archlens.migration-plan.v1";
    public enum Disposition { KEEP, REPLACE, REWRITE, SPLIT, RETIRE, UNDECIDED }

    public MigrationPlan {
        require(VERSION.equals(schemaVersion), "MIG_SCHEMA_UNSUPPORTED", "Unsupported migration plan version");
        require(identity != null && objectiveAndScope != null && currentArchitecture != null && targetArchitecture != null
                        && rolloutAndRollback != null && estimates != null,
                "MIG_PLAN_INCOMPLETE", "Required plan sections are absent");
        alternatives = MigrationContract.list(alternatives, 32, "MIG_PLAN_INVALID");
        mappings = MigrationContract.list(mappings, 512, "MIG_PLAN_INVALID");
        workItems = MigrationContract.list(workItems, 512, "MIG_PLAN_INVALID");
        validationPlan = MigrationContract.list(validationPlan, 512, "MIG_PLAN_INVALID");
        assumptionsAndGaps = MigrationContract.list(assumptionsAndGaps, 256, "MIG_PLAN_INVALID");
        evidenceRefs = MigrationContract.ids(evidenceRefs, 2048, "MIG_PLAN_INVALID");
        decisionRefs = MigrationContract.ids(decisionRefs, 256, "MIG_PLAN_INVALID");
        MigrationContract.unique(alternatives.stream().map(Alternative::alternativeId).toList(), "MIG_ALTERNATIVE_DUPLICATE");
        MigrationContract.unique(mappings.stream().map(Mapping::subjectId).toList(), "MIG_MAPPING_DUPLICATE");
        MigrationContract.unique(workItems.stream().map(WorkItem::workItemId).toList(), "MIG_WORK_ITEM_DUPLICATE");
        MigrationContract.unique(validationPlan.stream().map(ValidationItem::validationId).toList(), "MIG_VALIDATION_DUPLICATE");
        MigrationContract.unique(assumptionsAndGaps.stream().map(Assumption::assumptionId).toList(), "MIG_ASSUMPTION_DUPLICATE");
        if (selectedAlternativeId != null) {
            selectedAlternativeId = MigrationContract.id(selectedAlternativeId, "MIG_ALTERNATIVE_UNKNOWN");
            String selected = selectedAlternativeId;
            require(alternatives.stream().anyMatch(a -> a.alternativeId().equals(selected)),
                    "MIG_ALTERNATIVE_UNKNOWN", "Selected alternative is absent");
        }
        Set<String> workIds = new HashSet<>(workItems.stream().map(WorkItem::workItemId).toList());
        Set<String> validationIds = new HashSet<>(validationPlan.stream().map(ValidationItem::validationId).toList());
        Set<String> assumptionIds = new HashSet<>(assumptionsAndGaps.stream().map(Assumption::assumptionId).toList());
        for (WorkItem item : workItems) {
            require(workIds.containsAll(item.dependsOn()), "MIG_WORK_DEPENDENCY_UNKNOWN", "Work item dependency is absent");
            require(validationIds.containsAll(item.validationRefs()), "MIG_WORK_VALIDATION_UNKNOWN", "Work item validation is absent");
            require(assumptionIds.containsAll(item.assumptionRefs()), "MIG_WORK_ASSUMPTION_UNKNOWN", "Work item assumption is absent");
        }
        checkAcyclic(workItems);
        Set<String> declaredEvidence = new HashSet<>(evidenceRefs);
        Set<String> usedEvidence = new HashSet<>();
        usedEvidence.addAll(currentArchitecture.evidenceRefs());
        usedEvidence.addAll(targetArchitecture.evidenceRefs());
        alternatives.forEach(a -> usedEvidence.addAll(a.evidenceRefs()));
        mappings.forEach(m -> usedEvidence.addAll(m.evidenceRefs()));
        workItems.forEach(w -> usedEvidence.addAll(w.evidenceRefs()));
        validationPlan.forEach(v -> usedEvidence.addAll(v.evidenceRefs()));
        require(declaredEvidence.containsAll(usedEvidence), "MIG_PLAN_EVIDENCE_UNDECLARED",
                "Plan sections reference evidence absent from the evidence manifest");
    }

    public String runId() { return identity.runId(); }
    public int revision() { return identity.revision(); }
    public String snapshotId() { return identity.snapshotId(); }
    public String snapshotHash() { return identity.snapshotHash(); }
    /** Hash excludes no mutable state because the plan contains no self-referential planHash field. */
    public String planHash() { return Json.hash(this); }

    public void validateAgainst(MigrationRequest request, List<MigrationEvidence> evidence) {
        require(Json.hash(request).equals(identity.requestHash()), "MIG_PLAN_REQUEST_MISMATCH", "Plan request hash differs");
        require(request.objective().equals(objectiveAndScope.objective())
                        && request.analysisMode() == objectiveAndScope.analysisMode()
                        && request.acceptanceScope().equals(objectiveAndScope.acceptanceScope()),
                "MIG_PLAN_SCOPE_MISMATCH", "Plan objective or acceptance scope differs from fixed request");
        Set<String> invariantIds = new HashSet<>(request.invariants().stream().map(MigrationRequest.Condition::id).toList());
        require(validationPlan.stream().allMatch(item -> invariantIds.containsAll(item.invariantRefs())),
                "MIG_PLAN_INVARIANT_UNKNOWN", "Validation item cites an absent business invariant");
        List<MigrationEvidence> catalog = MigrationContract.list(evidence, 2048, "MIG_EVIDENCE_CATALOG_INVALID");
        Map<String, MigrationEvidence> byId = new HashMap<>();
        Set<String> authorizedSources = new HashSet<>(request.sourceRefs().stream().map(MigrationRequest.SourceRef::sourceRef).toList());
        for (MigrationEvidence item : catalog) {
            require(byId.putIfAbsent(item.evidenceId(), item) == null, "MIG_EVIDENCE_DUPLICATE", "Duplicate evidence ID");
            require(runId().equals(item.runId()) && revision() == item.revision()
                            && snapshotId().equals(item.snapshotId()) && snapshotHash().equals(item.snapshotHash()),
                    "MIG_EVIDENCE_SCOPE_MISMATCH", "Evidence belongs to another run, revision or snapshot");
            require(authorizedSources.contains(item.sourceRef()), "MIG_EVIDENCE_SOURCE_UNKNOWN", "Evidence source is outside request");
        }
        require(byId.keySet().containsAll(evidenceRefs), "MIG_EVIDENCE_NOT_FOUND", "Plan cites absent evidence");
    }

    private static void checkAcyclic(List<WorkItem> items) {
        Map<String, List<String>> edges = new HashMap<>();
        items.forEach(item -> edges.put(item.workItemId(), item.dependsOn()));
        Map<String, Integer> marks = new HashMap<>();
        for (String id : edges.keySet()) visit(id, edges, marks);
    }
    private static void visit(String id, Map<String, List<String>> edges, Map<String, Integer> marks) {
        int mark = marks.getOrDefault(id, 0);
        require(mark != 1, "MIG_WORK_DEPENDENCY_CYCLE", "Work item dependency cycle");
        if (mark == 2) return;
        marks.put(id, 1);
        edges.get(id).forEach(next -> visit(next, edges, marks));
        marks.put(id, 2);
    }

    public record Identity(String planId, String caseId, String runId, int revision,
                           String requestHash, String snapshotId, String snapshotHash) {
        public Identity {
            planId = MigrationContract.id(planId, "MIG_PLAN_IDENTITY_INVALID");
            caseId = MigrationContract.id(caseId, "MIG_PLAN_IDENTITY_INVALID");
            runId = MigrationContract.uuid(runId, "MIG_PLAN_IDENTITY_INVALID");
            require(revision >= 1 && revision <= 1_000_000, "MIG_PLAN_IDENTITY_INVALID", "Invalid plan revision");
            requestHash = MigrationContract.hash(requestHash, "MIG_PLAN_IDENTITY_INVALID");
            snapshotId = MigrationContract.id(snapshotId, "MIG_PLAN_IDENTITY_INVALID");
            snapshotHash = MigrationContract.hash(snapshotHash, "MIG_PLAN_IDENTITY_INVALID");
        }
    }
    public record ObjectiveAndScope(String objective, MigrationRequest.AnalysisMode analysisMode,
                                    MigrationRequest.AcceptanceScope acceptanceScope) {
        public ObjectiveAndScope {
            objective = MigrationContract.text(objective, 4000, "MIG_PLAN_SCOPE_INVALID");
            require(analysisMode != null && acceptanceScope != null, "MIG_PLAN_SCOPE_INVALID", "Plan mode and scope are required");
        }
    }
    public record Section(String summary, List<String> evidenceRefs, List<String> limitations) {
        public Section {
            summary = MigrationContract.text(summary, 4000, "MIG_PLAN_SECTION_INVALID");
            evidenceRefs = MigrationContract.ids(evidenceRefs, 512, "MIG_PLAN_SECTION_INVALID");
            limitations = MigrationContract.texts(limitations, 128, 1000, "MIG_PLAN_SECTION_INVALID");
        }
    }
    public record Alternative(String alternativeId, String description, String rationale, List<String> evidenceRefs) {
        public Alternative {
            alternativeId = MigrationContract.id(alternativeId, "MIG_ALTERNATIVE_INVALID");
            description = MigrationContract.text(description, 4000, "MIG_ALTERNATIVE_INVALID");
            rationale = MigrationContract.text(rationale, 4000, "MIG_ALTERNATIVE_INVALID");
            evidenceRefs = MigrationContract.ids(evidenceRefs, 256, "MIG_ALTERNATIVE_INVALID");
        }
    }
    public record Mapping(String subjectId, Disposition disposition, String rationale, List<String> evidenceRefs) {
        public Mapping {
            subjectId = MigrationContract.id(subjectId, "MIG_MAPPING_INVALID");
            require(disposition != null, "MIG_MAPPING_INVALID", "Mapping disposition is required");
            rationale = MigrationContract.text(rationale, 4000, "MIG_MAPPING_INVALID");
            evidenceRefs = MigrationContract.ids(evidenceRefs, 256, "MIG_MAPPING_INVALID");
        }
    }
    public record WorkItem(String workItemId, String title, List<String> sourceSubjectRefs,
                           String changeDescription, List<String> evidenceRefs, List<String> assumptionRefs,
                           List<String> dependsOn, List<String> prerequisites, List<String> deliverables,
                           List<String> acceptanceCriteria, List<String> validationRefs, String priorityBasis) {
        public WorkItem {
            workItemId = MigrationContract.id(workItemId, "MIG_WORK_ITEM_INVALID");
            title = MigrationContract.text(title, 200, "MIG_WORK_ITEM_INVALID");
            sourceSubjectRefs = MigrationContract.ids(sourceSubjectRefs, 128, "MIG_WORK_ITEM_INVALID");
            require(!sourceSubjectRefs.isEmpty(), "MIG_WORK_ITEM_INVALID", "Work item requires a source subject");
            changeDescription = MigrationContract.text(changeDescription, 4000, "MIG_WORK_ITEM_INVALID");
            evidenceRefs = MigrationContract.ids(evidenceRefs, 256, "MIG_WORK_ITEM_INVALID");
            assumptionRefs = MigrationContract.ids(assumptionRefs, 128, "MIG_WORK_ITEM_INVALID");
            require(!evidenceRefs.isEmpty() || !assumptionRefs.isEmpty(), "MIG_WORK_REASON_REQUIRED", "Work item needs evidence or an explicit assumption");
            dependsOn = MigrationContract.ids(dependsOn, 128, "MIG_WORK_ITEM_INVALID");
            prerequisites = MigrationContract.texts(prerequisites, 128, 1000, "MIG_WORK_ITEM_INVALID");
            deliverables = MigrationContract.texts(deliverables, 128, 1000, "MIG_WORK_ITEM_INVALID");
            acceptanceCriteria = MigrationContract.texts(acceptanceCriteria, 128, 1000, "MIG_WORK_ITEM_INVALID");
            validationRefs = MigrationContract.ids(validationRefs, 128, "MIG_WORK_ITEM_INVALID");
            require(!prerequisites.isEmpty() && !deliverables.isEmpty() && !acceptanceCriteria.isEmpty() && !validationRefs.isEmpty(),
                    "MIG_WORK_ITEM_INCOMPLETE", "Work item lacks prerequisites, deliverables, acceptance or validation");
            priorityBasis = MigrationContract.text(priorityBasis, 1000, "MIG_WORK_ITEM_INVALID");
        }
    }
    public record ValidationItem(String validationId, MigrationRequest.ValidationLevel level,
                                 List<String> invariantRefs, List<String> evidenceRefs,
                                 String method, String expectedCriteria,
                                 @JsonProperty(required = true) String testSpecHash, Boolean required) {
        public ValidationItem {
            validationId = MigrationContract.id(validationId, "MIG_VALIDATION_ITEM_INVALID");
            require(level != null, "MIG_VALIDATION_ITEM_INVALID", "Validation level is required");
            invariantRefs = MigrationContract.ids(invariantRefs, 128, "MIG_VALIDATION_ITEM_INVALID");
            evidenceRefs = MigrationContract.ids(evidenceRefs, 256, "MIG_VALIDATION_ITEM_INVALID");
            method = MigrationContract.text(method, 2000, "MIG_VALIDATION_ITEM_INVALID");
            expectedCriteria = MigrationContract.text(expectedCriteria, 2000, "MIG_VALIDATION_ITEM_INVALID");
            if (testSpecHash != null) testSpecHash = MigrationContract.hash(testSpecHash, "MIG_VALIDATION_ITEM_INVALID");
            require(required != null, "MIG_VALIDATION_ITEM_INVALID", "Validation requirement must be explicit");
        }
    }
    public record RolloutAndRollback(List<String> rolloutSteps, List<String> rollbackSteps, List<String> irreversibleLimits) {
        public RolloutAndRollback {
            rolloutSteps = MigrationContract.texts(rolloutSteps, 128, 1000, "MIG_ROLLOUT_INVALID");
            rollbackSteps = MigrationContract.texts(rollbackSteps, 128, 1000, "MIG_ROLLOUT_INVALID");
            irreversibleLimits = MigrationContract.texts(irreversibleLimits, 128, 1000, "MIG_ROLLOUT_INVALID");
        }
    }
    public record Assumption(String assumptionId, String claim, String verificationNeeded, Boolean blocking) {
        public Assumption {
            assumptionId = MigrationContract.id(assumptionId, "MIG_ASSUMPTION_INVALID");
            claim = MigrationContract.text(claim, 2000, "MIG_ASSUMPTION_INVALID");
            verificationNeeded = MigrationContract.text(verificationNeeded, 2000, "MIG_ASSUMPTION_INVALID");
            require(blocking != null, "MIG_ASSUMPTION_INVALID", "Blocking status must be explicit");
        }
    }
    public record Estimate(@JsonProperty(required = true) Integer minDays,
                           @JsonProperty(required = true) Integer maxDays,
                           @JsonProperty(required = true) String basis,
                           @JsonProperty(required = true) String missingReason) {
        public Estimate {
            require((minDays == null) == (maxDays == null), "MIG_ESTIMATE_INVALID", "Estimate interval must be complete");
            if (minDays != null) {
                require(minDays >= 0 && maxDays >= minDays && maxDays <= 3650,
                        "MIG_ESTIMATE_INVALID", "Estimate interval is invalid");
                basis = MigrationContract.text(basis, 2000, "MIG_ESTIMATE_INVALID");
                require(missingReason == null, "MIG_ESTIMATE_INVALID", "Estimated and missing states conflict");
            } else {
                require(basis == null, "MIG_ESTIMATE_INVALID", "Missing estimate cannot claim a basis");
                missingReason = MigrationContract.text(missingReason, 2000, "MIG_ESTIMATE_INVALID");
            }
        }
    }
}
