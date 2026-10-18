package io.archlens.cli;

import io.archlens.contract.ContractException;
import io.archlens.contract.Json;
import io.archlens.investigation.rules.RuleCatalog;
import io.archlens.storage.PgMigrationRunStore;
import io.archlens.storage.PgMigrationToolStore;
import io.archlens.storage.StorageConfig;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static io.archlens.contract.ContractException.require;

/** One bounded UTF-8 JSON Lines request per process. Stderr never contains request or database details. */
public final class MigrationToolBridgeCli {
    public static final String VERSION = "archlens.tool-bridge.v1";
    public static final int MAX_LINE_BYTES = 16_384;
    private MigrationToolBridgeCli() {}

    public record Request(String protocolVersion, UUID requestId, String operation,
                          UUID caseId, UUID runId, int revision, String requestHash,
                          String workerId, long epoch, UUID taskId, String actionKey,
                          UUID invocationId, String snapshotHash, String toolId,
                          String toolVersion, Map<String, Object> arguments) {}
    public record ArtifactRef(UUID artifactId, String contentHash) {}
    public record Response(String protocolVersion, UUID requestId, String status,
                           UUID invocationId, String inputHash, String snapshotHash,
                           List<ArtifactRef> artifactRefs, List<String> diagnostics,
                           int coverageDelta, String retryability, long elapsedMillis,
                           String errorCode) {}

    public static int run(String[] args, InputStream in, PrintStream out, PrintStream err,
                          Map<String, String> env) {
        long started = System.nanoTime();
        UUID requestId = null;
        try {
            require(args.length == 1 && "migration-tool-bridge".equals(args[0]),
                    "MIG_BRIDGE_USAGE", "Expected migration-tool-bridge command");
            String raw = readLine(in);
            Request request = Json.MAPPER.readValue(raw, Request.class);
            requestId = request.requestId();
            validate(request);
            PgMigrationToolStore store = new PgMigrationToolStore(new StorageConfig(env));
            // The host identity is not a model argument. Every ledger transition checks it against PG.
            var ticket = new PgMigrationRunStore.Ticket(request.caseId(), request.runId(),
                    request.revision(), request.requestHash(), request.workerId(), request.epoch(), null);
            Response response;
            if ("REGISTER".equals(request.operation())) {
                var registered = store.registerRuleCatalog(ticket, request.taskId(), request.actionKey());
                response = new Response(VERSION, requestId, "REGISTERED", registered.invocationId(),
                        registered.inputHash(), registered.snapshotHash(), List.of(), List.of(), 0,
                        "REPLAY", elapsed(started), null);
            } else if ("INSPECT".equals(request.operation())) {
                var inspected = store.inspectRuleCatalog(ticket, request.invocationId(), request.snapshotHash());
                List<ArtifactRef> refs = inspected.artifactId() == null ? List.of()
                        : List.of(new ArtifactRef(inspected.artifactId(), inspected.contentHash()));
                response = new Response(VERSION, requestId, inspected.status(), inspected.invocationId(),
                        inspected.inputHash(), inspected.snapshotHash(), refs, List.of(), 0,
                        "REPLAY", elapsed(started), null);
            } else {
                var result = store.executeRuleCatalog(ticket, request.invocationId(), request.snapshotHash());
                response = new Response(VERSION, requestId, "SUCCEEDED", result.invocationId(),
                        null, result.contentHash(), List.of(new ArtifactRef(result.artifactId(), result.contentHash())),
                        List.of(), 0, "REPLAY", elapsed(started), null);
            }
            write(out, response);
            return 0;
        } catch (Exception failure) {
            String code = code(failure);
            // The error frame is bounded and contains no raw JSON, paths, SQL, credentials or stack trace.
            write(out, new Response(VERSION, requestId, "ERROR", null, null, null,
                    List.of(), List.of(), 0, "MIG_BRIDGE_STORAGE_ERROR".equals(code) ? "REPLAY" : "NEVER",
                    elapsed(started), code));
            err.println(code);
            return 2;
        }
    }

    private static void validate(Request request) {
        require(VERSION.equals(request.protocolVersion()) && request.requestId() != null,
                "MIG_BRIDGE_VERSION", "Unsupported bridge protocol or missing request ID");
        require(PgMigrationToolStore.TOOL_ID.equals(request.toolId())
                        && RuleCatalog.VERSION.equals(request.toolVersion())
                        && request.arguments() != null && request.arguments().isEmpty(),
                "MIG_BRIDGE_TOOL_INVALID", "Only the fixed, argument-free rule catalog is available");
        require(request.caseId() != null && request.runId() != null && request.revision() > 0
                        && request.requestHash() != null && request.requestHash().matches("[0-9a-f]{64}")
                        && request.workerId() != null && request.workerId().matches("[A-Za-z][A-Za-z0-9._:-]{0,99}")
                        && request.epoch() > 0,
                "MIG_BRIDGE_TICKET_INVALID", "Incomplete host worker identity");
        if ("REGISTER".equals(request.operation())) {
            require(request.taskId() != null && request.actionKey() != null
                            && request.invocationId() == null && request.snapshotHash() == null,
                    "MIG_BRIDGE_SHAPE_INVALID", "Register needs task and action identity only");
        } else if ("EXECUTE".equals(request.operation()) || "INSPECT".equals(request.operation())) {
            require(request.taskId() == null && request.actionKey() == null && request.invocationId() != null
                            && request.snapshotHash() != null && request.snapshotHash().matches("[0-9a-f]{64}"),
                    "MIG_BRIDGE_SHAPE_INVALID", "Execution or inspection needs a registered invocation and snapshot hash");
        } else throw new ContractException("MIG_BRIDGE_OPERATION_INVALID", "Unsupported bridge operation");
    }

    private static String readLine(InputStream in) throws Exception {
        byte[] bytes = in.readNBytes(MAX_LINE_BYTES + 1);
        require(bytes.length <= MAX_LINE_BYTES, "MIG_BRIDGE_TOO_LARGE", "Bridge request exceeds byte limit");
        require(bytes.length >= 2 && bytes[bytes.length - 1] == '\n',
                "MIG_BRIDGE_FRAMING", "Expected one newline-terminated JSON request");
        int length = bytes.length - 1;
        if (bytes[length - 1] == '\r') length--;
        for (int i = 0; i < length; i++) require(bytes[i] != '\n' && bytes[i] != '\r',
                "MIG_BRIDGE_FRAMING", "Only one JSON line is permitted");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
    }

    private static void write(PrintStream out, Response response) {
        byte[] bytes = (Json.canonical(response) + "\n").getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        out.flush();
    }
    private static long elapsed(long started) { return Math.max(0, (System.nanoTime() - started) / 1_000_000); }
    private static String code(Exception failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) cause = cause.getCause();
        if (cause instanceof ContractException contract) return contract.code();
        if (cause instanceof SQLException) return "MIG_BRIDGE_STORAGE_ERROR";
        return "MIG_BRIDGE_INVALID";
    }
}
