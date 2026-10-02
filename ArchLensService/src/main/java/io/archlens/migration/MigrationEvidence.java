package io.archlens.migration;

import com.fasterxml.jackson.annotation.JsonProperty;
import static io.archlens.contract.ContractException.require;

/** Tool or explicitly unverified import observation, bound to one run revision and source snapshot. */
public record MigrationEvidence(String schemaVersion, String evidenceId, String runId, int revision,
                                String snapshotId, String snapshotHash, String subjectId, String sourceRef,
                                String sourceHash, Location location, String producerVersion,
                                Producer producer, Observation observation) {
    public static final String VERSION = "archlens.migration-evidence.v1";
    public enum Producer { TOOL, USER_IMPORT }
    public enum Observation { OBSERVED, CANDIDATE, EXTERNAL_UNVERIFIED }
    public enum Granularity { CODE_SPAN, FILE, DATABASE_OBJECT, UNKNOWN }

    public MigrationEvidence {
        require(VERSION.equals(schemaVersion), "MIG_SCHEMA_UNSUPPORTED", "Unsupported evidence version");
        evidenceId = MigrationContract.id(evidenceId, "MIG_EVIDENCE_INVALID");
        runId = MigrationContract.uuid(runId, "MIG_EVIDENCE_INVALID");
        require(revision >= 1 && revision <= 1_000_000, "MIG_EVIDENCE_INVALID", "Invalid evidence revision");
        snapshotId = MigrationContract.id(snapshotId, "MIG_EVIDENCE_INVALID");
        snapshotHash = MigrationContract.hash(snapshotHash, "MIG_EVIDENCE_INVALID");
        subjectId = MigrationContract.id(subjectId, "MIG_EVIDENCE_INVALID");
        sourceRef = MigrationContract.id(sourceRef, "MIG_EVIDENCE_INVALID");
        sourceHash = MigrationContract.hash(sourceHash, "MIG_EVIDENCE_INVALID");
        require(location != null, "MIG_EVIDENCE_LOCATION_INVALID", "Evidence location is required");
        producerVersion = MigrationContract.text(producerVersion, 100, "MIG_EVIDENCE_INVALID");
        require(producer != null && observation != null, "MIG_EVIDENCE_INVALID", "Producer and observation are required");
        require(producer != Producer.USER_IMPORT || observation == Observation.EXTERNAL_UNVERIFIED,
                "MIG_IMPORTED_EVIDENCE_UNVERIFIED", "User imports cannot claim a verified observation");
    }

    public record Location(Granularity granularity, String locator,
                           @JsonProperty(required = true) Integer startLine,
                           @JsonProperty(required = true) Integer endLine) {
        public Location {
            require(granularity != null, "MIG_EVIDENCE_LOCATION_INVALID", "Location granularity is required");
            locator = MigrationContract.text(locator, 1000, "MIG_EVIDENCE_LOCATION_INVALID");
            if (granularity == Granularity.CODE_SPAN) {
                require(startLine != null && endLine != null && startLine >= 1 && endLine >= startLine,
                        "MIG_EVIDENCE_LOCATION_INVALID", "Code span requires ordered positive lines");
            } else {
                require(startLine == null && endLine == null, "MIG_EVIDENCE_LOCATION_INVALID",
                        "Coarse locations cannot claim line precision");
            }
        }
    }
}
