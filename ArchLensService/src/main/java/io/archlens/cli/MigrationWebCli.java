package io.archlens.cli;

import io.archlens.contract.ContractException;
import io.archlens.contract.Json;
import io.archlens.migration.MigrationRequest;
import io.archlens.migration.MigrationSourceRegistry;
import io.archlens.storage.PgMigrationRunStore;
import io.archlens.storage.PgMigrationRuntimeStore;
import io.archlens.storage.StorageConfig;
import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.*;
import static io.archlens.contract.ContractException.require;

/**
 * Local web host protocol for migration source registration and run lifecycle.
 * One bounded JSON line in, one bounded JSON line out. The request never carries
 * an absolute source root or a credential value; only registry references do.
 */
public final class MigrationWebCli {
    public record Input(String action, String sourceRef, MigrationRequest.SourceKind kind,
                        String sourceRoot, String locator, List<String> files,
                        String declaredProduct, String declaredVersion,
                        String authorizationRef, String credentialRef,
                        String submissionKey, MigrationRequest request, UUID runId) {}

    private MigrationWebCli() {}

    public static void main(String[] args) {
        System.exit(run(System.in, System.out, System.err, System.getenv()));
    }

    public static int run(InputStream in, PrintStream out, PrintStream err, Map<String, String> env) {
        try {
            byte[] bytes = in.readNBytes(262_145);
            require(bytes.length <= 262_144, "INPUT_LIMIT", "Web input too large");
            Input input = Json.MAPPER.readValue(bytes, Input.class);
            require(input != null && input.action() != null, "INVALID_REQUEST", "Missing action");
            Path directory = MigrationSourceRegistry.directory(env);
            Object value = switch (input.action()) {
                case "register" -> register(directory, input);
                case "start" -> start(directory, input, env);
                case "get" -> get(input, env);
                case "cancel" -> cancel(input, env);
                default -> throw new ContractException("INVALID_REQUEST", "Unknown migration web operation");
            };
            emit(out, "result", value);
            return 0;
        } catch (Exception failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            String code = cause instanceof ContractException contract ? contract.code()
                    : cause instanceof SQLException ? "STORAGE_UNAVAILABLE"
                    : cause instanceof IOException ? "MIG_SOURCE_UNAVAILABLE"
                    : "MIG_BACKEND_FAILED";
            out.println(Json.canonical(Map.of("event", "error", "error", code)));
            out.flush();
            err.print(code + "\n");
            return 2;
        }
    }

    private static Object register(Path directory, Input input) throws Exception {
        require(input.sourceRef() != null && input.kind() != null, "MIG_SOURCE_REQUIRED", "sourceRef and kind are required");
        require(input.runId() == null && input.request() == null && input.submissionKey() == null,
                "INVALID_REQUEST", "Registration cannot carry run fields");
        Path root = input.sourceRoot() == null || input.sourceRoot().isBlank() ? null : Path.of(input.sourceRoot());
        var binding = MigrationSourceRegistry.register(directory, input.sourceRef(), input.kind(), root,
                input.locator(), input.files(), input.declaredProduct(), input.declaredVersion(),
                input.authorizationRef(), input.credentialRef());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sourceRef", binding.sourceRef());
        value.put("kind", binding.kind());
        value.put("manifestHash", binding.manifestHash());
        value.put("fileCount", binding.fileCount());
        value.put("bytes", binding.bytes());
        value.put("declaredProduct", binding.declaredProduct());
        value.put("declaredVersion", binding.declaredVersion());
        value.put("observedProfiles", binding.observed());
        return value;
    }

    private static Object start(Path directory, Input input, Map<String, String> env) throws Exception {
        require(input.request() != null && input.submissionKey() != null,
                "MIG_REQUEST_REQUIRED", "submissionKey and request are required");
        require(input.sourceRef() == null && input.kind() == null && input.sourceRoot() == null
                        && input.locator() == null && input.files() == null && input.runId() == null,
                "INVALID_REQUEST", "Start cannot carry source-registration fields");
        Set<String> sourceIds = new HashSet<>();
        for (var source : input.request().sourceRefs()) {
            require(sourceIds.add(source.sourceRef()), "MIG_SOURCE_DUPLICATE", "Duplicate request source");
            MigrationSourceRegistry.resolve(directory, source.sourceRef(), input.request(), env);
        }
        var queued = new PgMigrationRunStore(new StorageConfig(env))
                .enqueue(input.submissionKey(), input.request());
        return Map.of("caseId", queued.caseId(), "runId", queued.runId(),
                "revision", queued.revision(), "requestHash", queued.requestHash());
    }

    private static Object get(Input input, Map<String, String> env) throws Exception {
        require(input.runId() != null && input.request() == null && input.submissionKey() == null
                        && input.sourceRef() == null && input.kind() == null && input.sourceRoot() == null
                        && input.locator() == null && input.files() == null,
                "INVALID_REQUEST", "Get requires only runId");
        var config = new StorageConfig(env);
        var run = new PgMigrationRunStore(config).load(input.runId());
        var receipt = new PgMigrationRuntimeStore(config).receipt(input.runId());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("caseId", run.caseId());
        value.put("runId", run.runId());
        value.put("revision", run.revision());
        value.put("requestHash", run.requestHash());
        value.put("state", run.state());
        value.put("latestRevision", run.latestRevision());
        value.put("epoch", run.epoch());
        value.put("leaseUntil", run.leaseUntil() == null ? null : run.leaseUntil().toString());
        value.put("finishedAt", run.finishedAt() == null ? null : run.finishedAt().toString());
        value.put("receiptHash", receipt == null ? null : receipt.receiptHash());
        value.put("receipt", receipt == null ? null : receipt.receipt());
        return value;
    }

    private static Object cancel(Input input, Map<String, String> env) throws Exception {
        require(input.runId() != null && input.request() == null && input.submissionKey() == null
                        && input.sourceRef() == null && input.kind() == null && input.sourceRoot() == null
                        && input.locator() == null && input.files() == null,
                "INVALID_REQUEST", "Cancel requires only runId");
        var receipt = new PgMigrationRunStore(new StorageConfig(env)).cancel(input.runId());
        return Map.of("runId", receipt.runId(), "epoch", receipt.epoch(),
                "cancelledAt", receipt.cancelledAt().toString());
    }

    private static void emit(PrintStream out, String event, Object value) {
        out.println(Json.canonical(Map.of("event", event, "value", value)));
        out.flush();
    }
}
