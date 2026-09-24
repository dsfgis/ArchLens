package io.archlens;

import io.archlens.contract.*;
import io.archlens.llm.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekGatewayTest {
    private static final String TARGET = "将 public.device.event_id 改名为 global_id";
    private static final String VALID = """
            {"kind":"COLUMN_RENAME","schema":"public","table":"device","column":"event_id",
            "newName":"global_id","newType":null,"constraints":[],"questions":[]}
            """;
    private DeepSeekGateway gateway(String content, String finish) {
        return new DeepSeekGateway("test-secret", null, req -> new DeepSeekGateway.Reply(200,
                Json.MAPPER.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", finish,
                        "message", Map.of("content", content)))))));
    }
    @Test void validProposalIsNeverVerifiedChangeSpec() {
        var result = gateway(VALID, "stop").parse(TARGET);
        assertEquals("UNVERIFIED_PROPOSAL", result.status());
        assertEquals(TargetProposal.Kind.COLUMN_RENAME, result.proposal().kind());
        assertEquals("global_id", result.proposal().newName());
        assertFalse(Json.canonical(result).contains("test-secret"));
    }
    @Test void inventedFactFieldsAndMalformedOrTruncatedOutputAreRejected() {
        for (String invalid : List.of(VALID.replace("\"kind\":", "\"snapshotId\":\"invented\",\"kind\":"),
                VALID.replace("COLUMN_RENAME", "EXECUTE_SQL"), "not json", "", "null")) {
            assertThrows(ContractException.class, () -> gateway(invalid, "stop").parse(TARGET));
        }
        assertThrows(ContractException.class, () -> gateway(VALID, "length").parse(TARGET));
    }
    @Test void missingTargetRequiresQuestions() {
        String missing = VALID.replace("\"public\"", "null");
        assertThrows(ContractException.class, () -> gateway(missing, "stop").parse(TARGET));
        var result = gateway(missing.replace("\"questions\":[]", "\"questions\":[\"请提供 schema\"]"), "stop").parse(TARGET);
        assertNull(result.proposal().schema());
    }
    @Test void providerErrorsAreSanitized() {
        var gateway = new DeepSeekGateway("test-secret", null, req -> new DeepSeekGateway.Reply(401, "test-secret raw body"));
        var error = assertThrows(ContractException.class, () -> gateway.parse(TARGET));
        assertEquals("MODEL_AUTH_FAILED", error.code());
        assertFalse(error.getMessage().contains("test-secret"));
    }
    @Test void missingKeyAndInvalidInputFailBeforeCallingProvider() {
        assertThrows(ContractException.class, () -> new DeepSeekGateway(null, null));
        var gateway = new DeepSeekGateway("test-secret", null, req -> { fail("Must not call provider"); return null; });
        assertThrows(ContractException.class, () -> gateway.parse("short"));
        assertThrows(ContractException.class, () -> gateway.parse("x".repeat(4001)));
    }
    @Test void credentialsGoOnlyToOfficialEndpointAndTimeoutIsBounded() {
        var gateway = new DeepSeekGateway("test-secret", null, req -> {
            assertEquals("https://api.deepseek.com/chat/completions", req.uri().toString());
            assertEquals("Bearer test-secret", req.headers().firstValue("Authorization").orElseThrow());
            assertTrue(req.timeout().orElseThrow().toSeconds() <= 60);
            throw new java.net.http.HttpTimeoutException("internal details");
        });
        assertEquals("MODEL_TIMEOUT", assertThrows(ContractException.class, () -> gateway.parse(TARGET)).code());
    }
}
