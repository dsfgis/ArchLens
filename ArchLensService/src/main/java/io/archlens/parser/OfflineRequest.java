package io.archlens.parser;

import io.archlens.analysis.ChangeSpec;
import java.util.*;
import static io.archlens.contract.Model.text;
import static io.archlens.contract.ContractException.require;

/** Input catalog is an explicit offline assertion, never represented as live PG collection. */
public record OfflineRequest(UUID projectId,UUID snapshotId,UUID repositorySourceId,UUID databaseSourceId,
                             String module,String javaFile,String mapperFile,String namespace,
                             String databaseKey,String defaultSchema,List<Column> catalog,Change change) {
    public record Column(String schema,String table,String name,String type,Boolean nullable,String identityMeaning) {
        public Column {
            text(schema,"schema"); text(table,"table"); text(name,"name"); text(type,"type");
            Objects.requireNonNull(nullable); text(identityMeaning,"identityMeaning");
        }
    }
    public record Change(String schema,String table,String column,ChangeSpec.Kind kind,ChangeSpec.ColumnState after) {
        public Change { text(schema,"schema"); text(table,"table"); text(column,"column"); Objects.requireNonNull(kind); }
    }
    public OfflineRequest {
        Objects.requireNonNull(projectId); Objects.requireNonNull(snapshotId); Objects.requireNonNull(repositorySourceId); Objects.requireNonNull(databaseSourceId);
        require(!repositorySourceId.equals(databaseSourceId),"INVALID_SOURCE","Repository and database sources must differ");
        text(module,"module"); text(javaFile,"javaFile"); text(mapperFile,"mapperFile"); text(namespace,"namespace");
        text(databaseKey,"databaseKey"); text(defaultSchema,"defaultSchema"); catalog=List.copyOf(catalog);
        require(!catalog.isEmpty() && catalog.size()<=5000,"CATALOG_LIMIT","Catalog must have 1..5000 columns"); Objects.requireNonNull(change);
        Set<String> keys=new HashSet<>();
        for(Column c:catalog) require(keys.add(io.archlens.contract.Json.hashParts(c.schema,c.table,c.name)),"DUPLICATE_COLUMN","Duplicate catalog column");
    }
}
