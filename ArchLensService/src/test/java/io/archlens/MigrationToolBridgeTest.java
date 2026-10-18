package io.archlens;

import io.archlens.cli.MigrationToolBridgeCli;
import io.archlens.contract.Json;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Malformed bridge input is rejected before backend configuration or a database is opened. */
class MigrationToolBridgeTest {
    @Test void rejectsFramingEncodingDuplicateKeysAndUnknownToolWithoutEchoingInput() throws Exception {
        String valid = requestJson();
        assertError(valid + "\n" + valid + "\n", "MIG_BRIDGE_FRAMING");
        assertError(valid, "MIG_BRIDGE_FRAMING");
        assertError(valid.replace("\"toolId\":\"list_rules\"", "\"toolId\":\"arbitrary_sql\"") + "\n",
                "MIG_BRIDGE_TOOL_INVALID");
        assertError(valid.replaceFirst("\\{", "{\"toolId\":\"arbitrary_sql\",") + "\n",
                "MIG_BRIDGE_INVALID");
        byte[] invalidUtf8 = new byte[]{(byte) 0xc3, (byte) 0x28, '\n'};
        assertError(invalidUtf8, "MIG_BRIDGE_INVALID");
        assertError((" ".repeat(MigrationToolBridgeCli.MAX_LINE_BYTES) + "\n").getBytes(StandardCharsets.UTF_8),
                "MIG_BRIDGE_TOO_LARGE");
    }

    private static String requestJson() {
        return Json.canonical(new MigrationToolBridgeCli.Request(MigrationToolBridgeCli.VERSION, UUID.randomUUID(),
                "REGISTER", UUID.randomUUID(), UUID.randomUUID(), 1, "a".repeat(64), "workerA", 1,
                UUID.randomUUID(), "list-first", null, null, "list_rules", "archlens-rules-1.1", Map.of()));
    }

    private static void assertError(String input, String expectedCode) throws Exception {
        assertError(input.getBytes(StandardCharsets.UTF_8), expectedCode);
    }
    private static void assertError(byte[] input, String expectedCode) throws Exception {
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int exit = MigrationToolBridgeCli.run(new String[]{"migration-tool-bridge"},
                new ByteArrayInputStream(input), new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), Map.of());
        assertEquals(2, exit);
        String line = stdout.toString(StandardCharsets.UTF_8);
        assertEquals(1, line.chars().filter(c -> c == '\n').count());
        assertEquals(expectedCode, Json.MAPPER.readTree(line).get("errorCode").asText());
        assertEquals(expectedCode + "\n", stderr.toString(StandardCharsets.UTF_8));
        assertFalse(line.contains("arbitrary_sql"));
    }
}
