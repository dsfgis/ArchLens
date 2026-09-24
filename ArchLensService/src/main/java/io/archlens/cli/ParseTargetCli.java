package io.archlens.cli;

import io.archlens.contract.*;
import io.archlens.llm.DeepSeekGateway;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.io.PrintStream;

/** Separate JSON stdin/stdout adapter; the deterministic offline analyze command is unchanged. */
public final class ParseTargetCli {
    public record Request(String description) {}
    public static void main(String[] args) {
        // Windows redirected stdout may otherwise use a legacy console code page.
        var output = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        try {
            byte[] input = System.in.readNBytes(24001);
            ContractException.require(input.length <= 24000, "INVALID_TARGET_DESCRIPTION", "Input exceeds limit");
            var request = Json.MAPPER.readValue(input, Request.class);
            var gateway = new DeepSeekGateway(System.getenv("DEEPSEEK_API_KEY"), System.getenv("DEEPSEEK_MODEL"));
            output.println(Json.MAPPER.writeValueAsString(gateway.parse(request.description())));
        } catch (Exception e) {
            String code = e instanceof ContractException c ? c.code() : "INVALID_REQUEST";
            output.println(Json.canonical(Map.of("error", code)));
            System.exit(2);
        }
    }
}
