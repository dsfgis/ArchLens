package io.archlens;

import com.fasterxml.jackson.databind.node.*;
import io.archlens.contract.*;
import io.archlens.migration.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationArtifactsTest {
    private static final Path ROOT = Path.of("examples/migration");

    private ObjectNode planJson() throws Exception {
        return (ObjectNode) Json.MAPPER.readTree(Files.readString(ROOT.resolve("plan-code-only.json")));
    }
    private MigrationRequest request() throws Exception {
        return Json.MAPPER.readValue(ROOT.resolve("request-code-only.json").toFile(), MigrationRequest.class);
    }
    private List<MigrationEvidence> evidence() throws Exception {
        return Arrays.asList(Json.MAPPER.readValue(ROOT.resolve("evidence-code-only.json").toFile(), MigrationEvidence[].class));
    }
    private MigrationPlan plan(ObjectNode json) throws Exception {
        return Json.MAPPER.treeToValue(json, MigrationPlan.class);
    }
    private void assertCode(String code, org.junit.jupiter.api.function.Executable action) {
        Throwable failure = assertThrows(Exception.class, action);
        while (failure.getCause() != null) failure = failure.getCause();
        assertInstanceOf(ContractException.class, failure);
        assertEquals(code, ((ContractException) failure).code());
    }

    @Test void syntheticPlanHasStableContentIdentityAndBindsToRequestEvidence() throws Exception {
        MigrationPlan plan = plan(planJson());
        plan.validateAgainst(request(), evidence());
        assertEquals(Json.hash(plan), plan.planHash());
        assertEquals(plan.planHash(), plan(planJson()).planHash());
        MigrationValidationRecord fixture = Json.MAPPER.readValue(ROOT.resolve("validation-not-run.json").toFile(),
                MigrationValidationRecord.class);
        MigrationEvent draftEvent = Json.MAPPER.readValue(ROOT.resolve("event-plan-drafted.json").toFile(), MigrationEvent.class);
        assertEquals(plan.planHash(), fixture.planHash());
        assertEquals(plan.planHash(), draftEvent.artifactHash());
        assertFalse(fixture.matches(plan, plan.validationPlan().get(1)));
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int exit = io.archlens.cli.MigrationPlanCli.run(new String[]{"migration-plan-check",
                ROOT.resolve("request-code-only.json").toString(), ROOT.resolve("evidence-code-only.json").toString(),
                ROOT.resolve("plan-code-only.json").toString()}, new PrintStream(out), new PrintStream(err));
        assertEquals(0, exit, err.toString());
        assertTrue(out.toString().contains("STRUCTURE_VALID planHash=" + plan.planHash()));
        assertFalse(out.toString().contains("订单"));
    }

    @Test void foreignRequestSnapshotAndMissingEvidenceCannotPass() throws Exception {
        ObjectNode wrongRequest = planJson();
        ((ObjectNode) wrongRequest.get("identity")).put("requestHash", "0".repeat(64));
        assertCode("MIG_PLAN_REQUEST_MISMATCH", () -> plan(wrongRequest).validateAgainst(request(), evidence()));
        ObjectNode missing = planJson();
        ((ArrayNode) missing.get("evidenceRefs")).add("evidence-missing");
        assertCode("MIG_EVIDENCE_NOT_FOUND", () -> plan(missing).validateAgainst(request(), evidence()));
        MigrationEvidence original = evidence().getFirst();
        ObjectNode foreign = (ObjectNode) Json.MAPPER.valueToTree(original);
        foreign.put("snapshotHash", "0".repeat(64));
        MigrationEvidence changed = Json.MAPPER.treeToValue(foreign, MigrationEvidence.class);
        assertCode("MIG_EVIDENCE_SCOPE_MISMATCH", () -> plan(planJson()).validateAgainst(request(), List.of(changed)));
        ObjectNode undeclared = planJson();
        ((ObjectNode) undeclared.get("currentArchitecture")).withArray("evidenceRefs").add("evidence-other");
        assertCode("MIG_PLAN_EVIDENCE_UNDECLARED", () -> plan(undeclared));
    }

    @Test void workItemsRejectMissingRequirementsDanglingLinksAndCycles() throws Exception {
        ObjectNode noPrerequisite = planJson();
        ((ObjectNode) noPrerequisite.withArray("workItems").get(0)).set("prerequisites", Json.MAPPER.createArrayNode());
        assertCode("MIG_WORK_ITEM_INCOMPLETE", () -> plan(noPrerequisite));
        ObjectNode dangling = planJson();
        ((ObjectNode) dangling.withArray("workItems").get(0)).withArray("dependsOn").add("work-not-found");
        assertCode("MIG_WORK_DEPENDENCY_UNKNOWN", () -> plan(dangling));
        ObjectNode badValidation = planJson();
        ((ObjectNode) badValidation.withArray("workItems").get(0)).withArray("validationRefs").add("validation-missing");
        assertCode("MIG_WORK_VALIDATION_UNKNOWN", () -> plan(badValidation));
        ObjectNode cycle = planJson();
        ObjectNode first = (ObjectNode) cycle.withArray("workItems").get(0);
        first.withArray("dependsOn").add("work-second");
        ObjectNode second = first.deepCopy();
        second.put("workItemId", "work-second");
        second.set("dependsOn", Json.MAPPER.createArrayNode().add("work-amount-contract"));
        cycle.withArray("workItems").add(second);
        assertCode("MIG_WORK_DEPENDENCY_CYCLE", () -> plan(cycle));
    }

    @Test void validationCannotPromoteExternalOrStaleResults() throws Exception {
        MigrationPlan plan = plan(planJson());
        MigrationPlan.ValidationItem item = plan.validationPlan().get(1);
        MigrationValidationRecord notRun = record(plan, item, MigrationValidationRecord.Status.NOT_RUN,
                MigrationValidationRecord.ExecutedBy.SYSTEM, plan.planHash(), null, List.of());
        assertFalse(notRun.matches(plan, item));
        MigrationValidationRecord passed = record(plan, item, MigrationValidationRecord.Status.PASSED,
                MigrationValidationRecord.ExecutedBy.SYSTEM, plan.planHash(), null, List.of("result-money"));
        assertTrue(passed.matches(plan, item));
        MigrationValidationRecord rerun = new MigrationValidationRecord(MigrationValidationRecord.VERSION,
                "record-money-rerun", item.validationId(), plan.runId(), plan.revision(), plan.snapshotId(),
                plan.snapshotHash(), plan.planHash(), null, item.testSpecHash(), item.level(),
                MigrationValidationRecord.Status.PASSED, MigrationValidationRecord.ExecutedBy.SYSTEM,
                List.of("result-money-rerun"));
        assertNotEquals(passed.validationId(), rerun.validationId());
        assertTrue(rerun.matches(plan, item));
        MigrationValidationRecord stale = record(plan, item, MigrationValidationRecord.Status.PASSED,
                MigrationValidationRecord.ExecutedBy.SYSTEM, "0".repeat(64), null, List.of("result-money"));
        assertFalse(stale.matches(plan, item));
        assertCode("MIG_EXTERNAL_RESULT_UNVERIFIED", () -> record(plan, item, MigrationValidationRecord.Status.PASSED,
                MigrationValidationRecord.ExecutedBy.EXTERNAL_IMPORT, plan.planHash(), null, List.of("result-money")));
        assertCode("MIG_VALIDATION_ENVIRONMENT_REQUIRED", () -> new MigrationValidationRecord(
                MigrationValidationRecord.VERSION, "record-v3", "validation-v3", plan.runId(), plan.revision(), plan.snapshotId(),
                plan.snapshotHash(), plan.planHash(), null, item.testSpecHash(), MigrationRequest.ValidationLevel.V3,
                MigrationValidationRecord.Status.PASSED, MigrationValidationRecord.ExecutedBy.SYSTEM, List.of("result-v3")));
    }

    private MigrationValidationRecord record(MigrationPlan plan, MigrationPlan.ValidationItem item,
                                             MigrationValidationRecord.Status status, MigrationValidationRecord.ExecutedBy actor,
                                             String planHash, String environmentHash, List<String> results) {
        return new MigrationValidationRecord(MigrationValidationRecord.VERSION, "record-" + item.validationId(), item.validationId(), plan.runId(),
                plan.revision(), plan.snapshotId(), plan.snapshotHash(), planHash, environmentHash, item.testSpecHash(),
                item.level(), status, actor, results);
    }

    @Test void eventIsCursorAddressableAndCannotCarryUncodedErrors() throws Exception {
        String runId = plan(planJson()).runId();
        MigrationEvent event = new MigrationEvent(MigrationEvent.VERSION, runId, 1, 1,
                "2026-10-02T00:00:00Z", MigrationEvent.Type.CREATED, null, null);
        assertEquals(event, Json.MAPPER.readValue(Json.MAPPER.writeValueAsString(event), MigrationEvent.class));
        assertCode("MIG_EVENT_ERROR_CODE_REQUIRED", () -> new MigrationEvent(MigrationEvent.VERSION, runId, 1, 2,
                "2026-10-02T00:00:00Z", MigrationEvent.Type.ERROR, null, null));
        assertCode("MIG_EVENT_INVALID", () -> new MigrationEvent(MigrationEvent.VERSION, runId, 1, 2,
                "2026-10-02T08:00:00+08:00", MigrationEvent.Type.CREATED, null, null));
        assertCode("MIG_EVENT_INVALID", () -> new MigrationEvent(MigrationEvent.VERSION, runId, 1,
                9_007_199_254_740_992L, "2026-10-02T00:00:00Z", MigrationEvent.Type.CREATED, null, null));
    }

    @Test void nullableArtifactFieldsMustBeExplicitForCrossLanguageHashing() throws Exception {
        ObjectNode plan = planJson();
        plan.remove("selectedAlternativeId");
        assertThrows(Exception.class, () -> plan(plan));
        ObjectNode validation = (ObjectNode) Json.MAPPER.readTree(ROOT.resolve("validation-not-run.json").toFile());
        validation.remove("environmentHash");
        assertThrows(Exception.class, () -> Json.MAPPER.treeToValue(validation, MigrationValidationRecord.class));
        ObjectNode event = (ObjectNode) Json.MAPPER.readTree(ROOT.resolve("event-plan-drafted.json").toFile());
        event.remove("errorCode");
        assertThrows(Exception.class, () -> Json.MAPPER.treeToValue(event, MigrationEvent.class));
    }

    @Test void importedEvidenceRemainsExplicitlyUnverified() throws Exception {
        ObjectNode json = (ObjectNode) Json.MAPPER.valueToTree(evidence().getFirst());
        json.put("producer", "USER_IMPORT");
        assertCode("MIG_IMPORTED_EVIDENCE_UNVERIFIED", () -> Json.MAPPER.treeToValue(json, MigrationEvidence.class));
        json.put("observation", "EXTERNAL_UNVERIFIED");
        assertEquals(MigrationEvidence.Observation.EXTERNAL_UNVERIFIED,
                Json.MAPPER.treeToValue(json, MigrationEvidence.class).observation());
    }
}
