package io.archlens;

import com.fasterxml.jackson.databind.node.*;
import io.archlens.contract.*;
import io.archlens.migration.MigrationRequest;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class MigrationRequestTest {
    private static final Path EXAMPLE = Path.of("examples/migration/request-code-only.json");

    private ObjectNode example() throws Exception {
        return (ObjectNode) Json.MAPPER.readTree(Files.readString(EXAMPLE));
    }
    private MigrationRequest parse(ObjectNode json) throws Exception {
        return Json.MAPPER.treeToValue(json, MigrationRequest.class);
    }
    private void assertCode(String code, ObjectNode json) {
        Exception failure = assertThrows(Exception.class, () -> parse(json));
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        assertInstanceOf(ContractException.class, cause);
        assertEquals(code, ((ContractException) cause).code());
    }
    private ObjectNode offlineSource() throws Exception {
        ObjectNode source = (ObjectNode) example().withArray("sourceRefs").get(0).deepCopy();
        source.put("sourceRef", "orders-schema");
        source.put("kind", "OFFLINE_SCHEMA");
        source.put("locatorRef", "registered-orders-schema");
        source.putNull("credentialRef");
        source.put("offlineUnverified", true);
        return source;
    }

    @Test void validCodeDatabaseAndJointRequestsHaveDistinctStableHashes() throws Exception {
        ObjectNode code = example();
        MigrationRequest first = parse(code);
        assertEquals(MigrationRequest.VERSION, first.schemaVersion());
        assertEquals(Json.hash(first), Json.hash(parse(code)));
        ObjectNode database = example();
        database.put("analysisMode", "DATABASE_ONLY");
        database.set("sourceRefs", Json.MAPPER.createArrayNode().add(offlineSource()));
        ((ObjectNode) database.get("acceptanceScope")).set("requiredSourceRefs", Json.MAPPER.createArrayNode().add("orders-schema"));
        assertEquals(MigrationRequest.AnalysisMode.DATABASE_ONLY, parse(database).analysisMode());
        ObjectNode joint = example();
        joint.put("analysisMode", "JOINT");
        ((ArrayNode) joint.get("sourceRefs")).add(offlineSource());
        ((ArrayNode) joint.get("acceptanceScope").get("requiredSourceRefs")).add("orders-schema");
        assertEquals(MigrationRequest.AnalysisMode.JOINT, parse(joint).analysisMode());
        assertNotEquals(Json.hash(first), Json.hash(parse(joint)));
    }

    @Test void modeAndScopeCannotClaimMissingSources() throws Exception {
        ObjectNode codeMissing = example();
        codeMissing.set("sourceRefs", Json.MAPPER.createArrayNode().add(offlineSource()));
        assertCode("MIG_CODE_SOURCE_REQUIRED", codeMissing);
        ObjectNode databaseMissing = example();
        databaseMissing.put("analysisMode", "DATABASE_ONLY");
        assertCode("MIG_DATABASE_SOURCE_REQUIRED", databaseMissing);
        ObjectNode joint = example();
        joint.put("analysisMode", "JOINT");
        assertCode("MIG_JOINT_SOURCES_REQUIRED", joint);
        ObjectNode omitted = example();
        omitted.put("analysisMode", "JOINT");
        ((ArrayNode) omitted.get("sourceRefs")).add(offlineSource());
        assertCode("MIG_SCOPE_SOURCE_REQUIRED", omitted);
        ObjectNode unknown = example();
        ((ArrayNode) unknown.get("acceptanceScope").get("requiredSourceRefs")).add("missing");
        assertCode("MIG_SCOPE_SOURCE_UNKNOWN", unknown);
    }

    @Test void a2AndV3RequireExplicitValidationScopeAndPolicy() throws Exception {
        ObjectNode missingV1 = example();
        ((ObjectNode) missingV1.get("acceptanceScope")).set("requiredValidationLevels", Json.MAPPER.createArrayNode().add("V2"));
        assertCode("MIG_DESIGN_REQUIRES_V1", missingV1);
        ObjectNode missingV3 = example();
        ((ObjectNode) missingV3.get("acceptanceScope")).put("level", "A2");
        assertCode("MIG_A2_REQUIRES_V3", missingV3);
        ObjectNode readOnly = example();
        ((ObjectNode) readOnly.get("acceptanceScope")).withArray("requiredValidationLevels").add("V3");
        assertCode("MIG_VALIDATION_POLICY_CONFLICT", readOnly);
        ((ObjectNode) readOnly.get("policyRefs")).put("executionPolicy", "ISOLATED_VALIDATION");
        assertNotNull(parse(readOnly));
    }

    @Test void unknownFieldsAndMissingOfflineMarkerAreRejected() throws Exception {
        ObjectNode secret = example();
        ((ObjectNode) secret.get("sourceRefs").get(0)).put("password", "synthetic-secret");
        assertThrows(Exception.class, () -> parse(secret));
        ObjectNode marker = example();
        ((ObjectNode) marker.get("sourceRefs").get(0)).remove("offlineUnverified");
        assertCode("MIG_OFFLINE_MARKER_INVALID", marker);
        ObjectNode unsupported = example();
        unsupported.put("schemaVersion", "archlens.migration-request.v9");
        assertCode("MIG_SCHEMA_UNSUPPORTED", unsupported);
        ObjectNode omittedNullable = example();
        ((ObjectNode) omittedNullable.get("sourceRefs").get(0)).remove("credentialRef");
        assertThrows(Exception.class, () -> parse(omittedNullable));
    }

    @Test void cliReturnsHashWithoutEchoingInput() throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int result = io.archlens.cli.MigrationRequestCli.run(
                new String[]{"migration-request-check", EXAMPLE.toString()}, new PrintStream(out), new PrintStream(err));
        assertEquals(0, result, err.toString());
        assertTrue(out.toString().contains("requestHash="));
        assertFalse(out.toString().contains("订单"));
    }
}
