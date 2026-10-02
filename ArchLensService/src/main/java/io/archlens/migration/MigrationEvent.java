package io.archlens.migration;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import static io.archlens.contract.ContractException.require;

/** Cursor-addressable business event; payloads live in separately bound artifacts, not event text. */
public record MigrationEvent(String schemaVersion, String runId, int revision, long sequence,
                             String occurredAt, Type type, @JsonProperty(required = true) String artifactHash,
                             @JsonProperty(required = true) String errorCode) {
    public static final String VERSION = "archlens.migration-event.v1";
    public enum Type { CREATED, TASK_STARTED, TASK_COMPLETED, GAP_RECORDED, PLAN_DRAFTED,
        VALIDATION_RECORDED, NEEDS_INPUT, CANCELLED, SEALED, ERROR }

    public MigrationEvent {
        require(VERSION.equals(schemaVersion), "MIG_SCHEMA_UNSUPPORTED", "Unsupported event version");
        runId = MigrationContract.uuid(runId, "MIG_EVENT_INVALID");
        require(revision >= 1 && revision <= 1_000_000 && sequence >= 1 && sequence <= 9_007_199_254_740_991L && type != null,
                "MIG_EVENT_INVALID", "Event identity, sequence or type is invalid");
        try {
            require(occurredAt != null && occurredAt.endsWith("Z"), "MIG_EVENT_INVALID", "Expected a UTC event time");
            Instant.parse(occurredAt);
        }
        catch (DateTimeParseException | NullPointerException e) {
            throw new io.archlens.contract.ContractException("MIG_EVENT_INVALID", "Expected an ISO-8601 UTC event time");
        }
        if (artifactHash != null) artifactHash = MigrationContract.hash(artifactHash, "MIG_EVENT_INVALID");
        if (errorCode != null) errorCode = MigrationContract.id(errorCode, "MIG_EVENT_INVALID");
        require(type != Type.ERROR || errorCode != null, "MIG_EVENT_ERROR_CODE_REQUIRED", "Error events require a stable error code");
        require(type == Type.ERROR || errorCode == null, "MIG_EVENT_INVALID", "Only error events may carry an error code");
    }
}
