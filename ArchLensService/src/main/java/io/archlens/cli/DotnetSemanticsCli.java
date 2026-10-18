package io.archlens.cli;

import io.archlens.contract.Json;
import io.archlens.investigation.dotnet.DotnetSemantics;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Local smoke/debug entry point: explicit file manifest, no model or storage. */
public final class DotnetSemanticsCli {
    private DotnetSemanticsCli() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Usage: DotnetSemanticsCli <root> <files.json>");
        Path root=Path.of(args[0]).toRealPath(),manifest=Path.of(args[1]);
        if(Files.size(manifest)>262144)throw new IllegalArgumentException("Manifest exceeds 256 KiB");
        @SuppressWarnings("unchecked") List<String> files=Json.MAPPER.readValue(Files.readString(manifest,StandardCharsets.UTF_8),
                Json.MAPPER.getTypeFactory().constructCollectionType(List.class,String.class));
        if(files==null||files.isEmpty()||files.size()>1000)throw new IllegalArgumentException("Expected 1..1000 authorized files");
        System.out.println(Json.canonical(DotnetSemantics.inspect(root,files,()->false,120000)));
    }
}
