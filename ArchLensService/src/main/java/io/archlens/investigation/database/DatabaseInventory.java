package io.archlens.investigation.database;

import io.archlens.contract.Json;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.*;

/** 只读结构观察；未读取业务行，不声明与代码原子一致或迁移兼容。 */
public record DatabaseInventory(String schemaVersion,String product,String observedVersion,String database,String sourceFingerprint,
        String startedAt,String finishedAt,String status,String metadataHash,List<Table> tables,Map<String,String> settings,List<String> coverageGaps,
        @JsonInclude(JsonInclude.Include.NON_NULL) ExtendedMetadata extendedMetadata) {
    public static final String VERSION="archlens.database-inventory.v2";
    public DatabaseInventory(String schemaVersion,String product,String observedVersion,String database,String sourceFingerprint,
            String startedAt,String finishedAt,String status,String metadataHash,List<Table> tables,Map<String,String> settings,List<String> coverageGaps) {
        this(schemaVersion,product,observedVersion,database,sourceFingerprint,startedAt,finishedAt,status,metadataHash,tables,settings,coverageGaps,null);
    }
    public record Column(String name,int ordinal,String dataType,String columnType,boolean nullable,String extra,String charset,String collation) {}
    public record IndexPart(String name,boolean unique,int ordinal,String column,String type) {}
    public record KeyPart(String name,String type,int ordinal,String column,String referencedSchema,String referencedTable,String referencedColumn) {}
    public record Table(String evidenceId,String name,String kind,String engine,String collation,List<Column> columns,List<IndexPart> indexes,List<KeyPart> keys) {
        public Table {columns=List.copyOf(columns);indexes=List.copyOf(indexes);keys=List.copyOf(keys);}
    }
    /** 描述可见结构和定义大小，不保存可能含凭据或业务常量的原始定义。 */
    public record ColumnDetail(String table,String column,boolean defaultPresent,boolean defaultExpression,
            boolean commentPresent,boolean generatedExpressionPresent) {}
    public record TableDetail(String name,boolean commentPresent) {}
    public record ViewDetail(String name,boolean definitionVisible,Integer definitionLength,boolean updatable,
            String checkOption,String securityType) {}
    public record CheckDetail(String table,String name,boolean clauseVisible,Integer clauseLength,boolean enforced) {}
    public record ProgramObject(String kind,String name,String table,String returnType,String securityType,
            String dataAccess,String event,String timing,boolean bodyVisible,Integer bodyLength) {}
    public record ProgramParameter(String program,int ordinal,String mode,String name,String dataType) {}
    public record ExtendedMetadata(List<ColumnDetail> columns,List<TableDetail> tables,List<ViewDetail> views,
            List<CheckDetail> checks,List<ProgramObject> programs,List<ProgramParameter> parameters) {
        public ExtendedMetadata {
            columns=List.copyOf(columns);tables=List.copyOf(tables);views=List.copyOf(views);
            checks=List.copyOf(checks);programs=List.copyOf(programs);parameters=List.copyOf(parameters);
        }
    }
    public DatabaseInventory {tables=List.copyOf(tables);settings=Map.copyOf(settings);coverageGaps=List.copyOf(coverageGaps);}
    public static String hash(List<Table> tables,Map<String,String> settings){return Json.hashParts(tables,settings);}
    public static String hash(List<Table> tables,Map<String,String> settings,ExtendedMetadata details){return Json.hashParts(tables,settings,details);}
}
