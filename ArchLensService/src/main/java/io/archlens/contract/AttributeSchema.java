package io.archlens.contract;

import java.util.*;
import static io.archlens.contract.Model.*;
import static io.archlens.contract.ContractException.require;

/** Attribute schemas implemented for the column/mapper slice; broader IR schemas are a later TASK02 deliverable. */
final class AttributeSchema {
    private AttributeSchema() {}
    static void validate(NodeType type,Map<String,Object> a) {
        if(type==NodeType.COLUMN) {
            allowed(a,Set.of("dbType","nullable","identityMeaning","schema","table","databaseKey","default","isUnique","constraintRefs","origin","diagnosticId"));
            required(a,"dbType",String.class); required(a,"nullable",Boolean.class); required(a,"identityMeaning",String.class);
        } else if(type==NodeType.METHOD) {
            allowed(a,Set.of("signature","declaringType","parameterTypes","returnType","returnTypeSyntax","module","diagnosticId"));
            required(a,"signature",String.class); required(a,"declaringType",String.class); required(a,"module",String.class);
            required(a,"parameterTypes",List.class);
            require(((List<?>)a.get("parameterTypes")).stream().allMatch(v->v instanceof String),"INVALID_ATTRIBUTES","parameterTypes must contain strings");
            require(a.containsKey("returnType") && (a.get("returnType") instanceof String || a.get("returnType")==null && a.get("diagnosticId") instanceof String),
                    "INVALID_ATTRIBUTES","Unknown returnType requires diagnosticId");
        }
    }
    private static void allowed(Map<String,Object> a,Set<String> keys) {
        require(keys.containsAll(a.keySet()),"INVALID_ATTRIBUTES","Unknown type-specific attribute");
    }
    private static void required(Map<String,Object> a,String name,Class<?> type) {
        require(type.isInstance(a.get(name)),"INVALID_ATTRIBUTES","Missing or invalid attribute: "+name);
    }
}
