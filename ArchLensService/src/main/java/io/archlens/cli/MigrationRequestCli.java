package io.archlens.cli;

import io.archlens.contract.*;
import io.archlens.migration.MigrationRequest;
import io.archlens.parser.SourceText;
import java.io.PrintStream;
import java.nio.file.Path;

/** P0 contract probe: validates a fixed request and prints only its canonical identity. */
public final class MigrationRequestCli {
    private MigrationRequestCli() {}

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2 || !"migration-request-check".equals(args[0])) {
            err.println("USAGE: migration-request-check <request.json>");
            return 2;
        }
        try {
            Path path = Path.of(args[1]).toRealPath();
            String raw = SourceText.read(path.getParent(), path.getFileName().toString()).text();
            MigrationRequest request = Json.MAPPER.readValue(raw, MigrationRequest.class);
            out.println("VALID requestHash=" + Json.hash(request) + " mode=" + request.analysisMode()
                    + " level=" + request.acceptanceScope().level());
            return 0;
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            String code = cause instanceof ContractException c ? c.code() : "MIG_REQUEST_INVALID";
            // Deliberately avoid echoing malformed input, paths or parser text (which could contain secrets).
            err.println(code + ": request rejected; check v1 contract, source scope and UTF-8 JSON");
            return 2;
        }
    }
}
