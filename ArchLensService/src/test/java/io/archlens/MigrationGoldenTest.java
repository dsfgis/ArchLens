package io.archlens;

import com.fasterxml.jackson.databind.JsonNode;
import io.archlens.contract.Json;
import io.archlens.migration.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Shared Java/Node v1 golden identities; changing canonical semantics requires an explicit contract version. */
class MigrationGoldenTest {
    @Test void allFixtureHashesMatchTheVersionedGoldenFile() throws Exception {
        Path root = Path.of("examples/migration");
        JsonNode goldens = Json.MAPPER.readTree(root.resolve("golden-hashes.json").toFile());
        for (String kind : new String[]{"request", "evidence", "plan", "validation", "event", "edge"}) {
            String expected = goldens.path(kind).path("sha256").asText();
            Path file = root.resolve(goldens.path(kind).path("file").asText());
            Object artifact = switch (kind) {
                case "request" -> Json.MAPPER.readValue(file.toFile(), MigrationRequest.class);
                case "evidence" -> Arrays.asList(Json.MAPPER.readValue(file.toFile(), MigrationEvidence[].class));
                case "plan" -> Json.MAPPER.readValue(file.toFile(), MigrationPlan.class);
                case "validation" -> Json.MAPPER.readValue(file.toFile(), MigrationValidationRecord.class);
                case "event" -> Json.MAPPER.readValue(file.toFile(), MigrationEvent.class);
                default -> Json.MAPPER.readTree(file.toFile());
            };
            assertEquals(expected, Json.hash(artifact), kind);
        }
    }

    @Test void canonicalHashRejectsUnpairedSurrogates() {
        assertEquals("INVALID_UNICODE", assertThrows(io.archlens.contract.ContractException.class,
                () -> Json.hash("\uD800")).code());
    }

    @Test void historicalAgentReportKeepsItsBytesAndCanonicalIdentity() throws Exception {
        // Freeze one pre-migration report without rewriting the archived fixture or assuming all old versions are covered.
        Path root = Path.of("examples/migration");
        JsonNode golden = Json.MAPPER.readTree(root.resolve("legacy-agent-golden.json").toFile());
        byte[] bytes = java.nio.file.Files.readAllBytes(root.resolve(golden.path("file").asText()));
        assertEquals(golden.path("byteSha256").asText(), Json.sha256(bytes));
        JsonNode raw = Json.MAPPER.readTree(bytes);
        var typed = Json.MAPPER.readValue(bytes, io.archlens.agent.AgentContracts.Report.class);
        assertEquals(golden.path("canonicalSha256").asText(), Json.hash(raw));
        assertEquals(Json.hash(raw), Json.hash(typed));
    }

    @Test void historicalStorageMigrationKeepsItsOriginalBytes() throws Exception {
        // V002 must be additive; changing V001 would break existing schema_version checksums.
        byte[] bytes = java.nio.file.Files.readAllBytes(Path.of("src/main/resources/db/V001__investigation_storage.sql"));
        assertEquals("f96e5873694434a2a2eb71dcc319019837618f0cbbb9add2d7f6e5a17c8150ba", Json.sha256(bytes));
    }
}
