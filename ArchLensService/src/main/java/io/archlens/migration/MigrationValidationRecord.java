package io.archlens.migration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import static io.archlens.contract.ContractException.require;

/** Immutable result reference; a passing record is only eligible after host provenance and plan binding checks. */
public record MigrationValidationRecord(String schemaVersion, String validationId, String validationItemRef,
                                        String runId, int revision,
                                        String snapshotId, String snapshotHash, String planHash,
                                        @JsonProperty(required = true) String environmentHash, String testSpecHash,
                                        MigrationRequest.ValidationLevel level, Status status,
                                        ExecutedBy executedBy, List<String> resultRefs) {
    public static final String VERSION = "archlens.migration-validation.v1";
    public enum Status { NOT_RUN, RUNNING, PASSED, FAILED, INCONCLUSIVE, EXTERNAL_UNVERIFIED }
    public enum ExecutedBy { SYSTEM, EXTERNAL_IMPORT }

    public MigrationValidationRecord {
        require(VERSION.equals(schemaVersion), "MIG_SCHEMA_UNSUPPORTED", "Unsupported validation version");
        validationId = MigrationContract.id(validationId, "MIG_VALIDATION_INVALID");
        validationItemRef = MigrationContract.id(validationItemRef, "MIG_VALIDATION_INVALID");
        runId = MigrationContract.uuid(runId, "MIG_VALIDATION_INVALID");
        require(revision >= 1 && revision <= 1_000_000, "MIG_VALIDATION_INVALID", "Invalid validation revision");
        snapshotId = MigrationContract.id(snapshotId, "MIG_VALIDATION_INVALID");
        snapshotHash = MigrationContract.hash(snapshotHash, "MIG_VALIDATION_INVALID");
        planHash = MigrationContract.hash(planHash, "MIG_VALIDATION_INVALID");
        if (environmentHash != null) environmentHash = MigrationContract.hash(environmentHash, "MIG_VALIDATION_INVALID");
        testSpecHash = MigrationContract.hash(testSpecHash, "MIG_VALIDATION_INVALID");
        require(level != null && status != null && executedBy != null, "MIG_VALIDATION_INVALID", "Validation status and provenance are required");
        resultRefs = MigrationContract.ids(resultRefs, 256, "MIG_VALIDATION_INVALID");
        require(level != MigrationRequest.ValidationLevel.V3 || status == Status.NOT_RUN || environmentHash != null,
                "MIG_VALIDATION_ENVIRONMENT_REQUIRED", "Executed V3 validation requires an environment hash");
        require(executedBy != ExecutedBy.EXTERNAL_IMPORT || status == Status.EXTERNAL_UNVERIFIED,
                "MIG_EXTERNAL_RESULT_UNVERIFIED", "External results cannot claim system validation");
        require((status != Status.PASSED && status != Status.FAILED && status != Status.INCONCLUSIVE) || !resultRefs.isEmpty(),
                "MIG_VALIDATION_RESULT_REQUIRED", "Finished validation requires result references");
        require(status != Status.NOT_RUN || resultRefs.isEmpty(), "MIG_VALIDATION_INVALID", "NOT_RUN cannot carry results");
    }

    /** Structural eligibility only; persisted validation must also be authenticated by the Java authority. */
    public boolean matches(MigrationPlan plan, MigrationPlan.ValidationItem item) {
        return status == Status.PASSED && executedBy == ExecutedBy.SYSTEM
                && runId.equals(plan.runId()) && revision == plan.revision()
                && snapshotId.equals(plan.snapshotId()) && snapshotHash.equals(plan.snapshotHash())
                && planHash.equals(plan.planHash()) && validationItemRef.equals(item.validationId())
                && level == item.level() && testSpecHash.equals(item.testSpecHash())
                && (level != MigrationRequest.ValidationLevel.V3 || environmentHash != null);
    }
}
