package io.archlens;

import io.archlens.analysis.ChangeSpec;
import io.archlens.contract.*;
import java.math.BigDecimal;
import java.util.*;
import static io.archlens.contract.Model.*;

public final class GraphFixture {
    public final Scope scope=new Scope(UUID.fromString("10000000-0000-0000-0000-000000000001"),UUID.fromString("20000000-0000-0000-0000-000000000001"));
    public final UUID source=UUID.fromString("30000000-0000-0000-0000-000000000001");
    public final String sourceHash=Json.hash("fixture");
    public final EvidenceIR evidence=EvidenceIR.create(scope,source,sourceHash,"TEST_BINDING","fixture-v1",Location.metadata("fixture"),null);
    public final List<NodeIR> nodes=new ArrayList<>();
    public final List<EdgeIR> edges=new ArrayList<>();
    public final NodeIR column=node("event_id",NodeType.COLUMN);
    public NodeIR node(String name,NodeType type) {
        Map<String,Object> attributes=type==NodeType.COLUMN ? Map.of("dbType","text","nullable",false,"identityMeaning","device-key")
                : type==NodeType.METHOD ? Map.of("signature",name+"()","declaringType","test.Mapper","parameterTypes",List.of(),"returnType","void","module","test") : Map.of();
        NodeIR n=NodeIR.create(scope,source,type,source+"/"+name,name,new SourceRef(sourceHash,Location.metadata(name)),attributes);
        nodes.add(n); return n;
    }
    public EdgeIR edge(NodeIR from,NodeIR to,EdgeKind kind) { return edge(from,to,kind,"",Certainty.CONFIRMED); }
    public EdgeIR edge(NodeIR from,NodeIR to,EdgeKind kind,String binding,Certainty certainty) {
        var e=EdgeIR.create(scope,from.nodeId(),to.nodeId(),kind,binding,List.of(evidence.evidenceId()),certainty); edges.add(e); return e;
    }
    public GraphDocument document() { return new GraphDocument(VERSION,scope.projectId(),scope.snapshotId(),nodes,edges,List.of(evidence),List.of()); }
    public FactGraph graph() { return new FactGraph(document()); }
    public ChangeSpec change(ChangeSpec.Kind kind) {
        return new ChangeSpec(column.nodeId(),kind,new ChangeSpec.ColumnState("event_id","text",false,"device-key"),
                kind==ChangeSpec.Kind.COLUMN_DROP ? null : new ChangeSpec.ColumnState(kind==ChangeSpec.Kind.COLUMN_RENAME ? "global_id" : "event_id",kind==ChangeSpec.Kind.COLUMN_TYPE_CHANGE ? "uuid" : "text",false,"device-key"),
                ChangeSpec.Stage.PROPOSED,List.of(),List.of());
    }
}
