package io.archlens.cli;

import io.archlens.contract.*;
import io.archlens.migration.*;
import io.archlens.parser.SourceText;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;

/** P0 structural probe. Provenance and actual source snapshot authenticity require the later host registry. */
public final class MigrationPlanCli {
    private MigrationPlanCli() {}

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 4 || !"migration-plan-check".equals(args[0])) {
            err.println("USAGE: migration-plan-check <request.json> <evidence-array.json> <plan.json>");
            return 2;
        }
        try {
            MigrationRequest request = Json.MAPPER.readValue(read(args[1]), MigrationRequest.class);
            MigrationEvidence[] evidence = Json.MAPPER.readValue(read(args[2]), MigrationEvidence[].class);
            MigrationPlan plan = Json.MAPPER.readValue(read(args[3]), MigrationPlan.class);
            plan.validateAgainst(request, Arrays.asList(evidence));
            out.println("STRUCTURE_VALID planHash=" + plan.planHash() + " workItems=" + plan.workItems().size()
                    + " evidence=" + plan.evidenceRefs().size());
            return 0;
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            String code = cause instanceof ContractException c ? c.code() : "MIG_PLAN_INPUT_INVALID";
            // Do not echo user JSON, paths, parser messages, or evidence content into diagnostics.
            err.println(code + ": structure rejected; check contract versions, identity and references");
            return 2;
        }
    }

    private static String read(String filename) throws Exception {
        Path path = Path.of(filename).toRealPath();
        return SourceText.read(path.getParent(), path.getFileName().toString()).text();
    }
}
