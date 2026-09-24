package io.archlens;

import io.archlens.contract.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static io.archlens.contract.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractTest {
    @Test void canonicalHashIsStableAndUnicodeCodePointSorted() {
        assertEquals("{\"a\":1,\"z\":2}",Json.canonical(Map.of("z",2,"a",new BigDecimal("1.00"))));
        assertEquals(Json.hash(Map.of("z",2,"a",1)),Json.hash(Map.of("a",1,"z",2)));
        assertNotEquals(Json.hashParts("ab","c"),Json.hashParts("a","bc"));
        assertNotEquals(Json.hashParts("A"),Json.hashParts("a"));
        assertTrue(Json.canonical(Map.of("\uE000",1,"😀",2)).indexOf("\uE000")<Json.canonical(Map.of("\uE000",1,"😀",2)).indexOf("😀"));
    }
    @Test void sameIdDifferentContentIsRejected() {
        var f=new GraphFixture(); var original=f.column;
        f.nodes.add(new NodeIR(VERSION,f.scope.projectId(),f.scope.snapshotId(),original.nodeId(),original.logicalKey(),original.type(),
                "different",f.source,original.sourceRef(),original.attributes()));
        assertCode("IDENTITY_CONFLICT",f::graph);
    }
    @Test void foreignSnapshotIsRejectedEvenWithValidForeignId() {
        var f=new GraphFixture(); var other=new Scope(f.scope.projectId(),UUID.randomUUID());
        f.nodes.add(NodeIR.create(other,f.source,NodeType.COLUMN,f.source+"/foreign","foreign",f.column.sourceRef(),f.column.attributes()));
        assertCode("SCOPE_MISMATCH",f::graph);
    }
    @Test void confirmedEdgeMustHaveEvidence() {
        var f=new GraphFixture(); var method=f.node("mapper",NodeType.METHOD);
        assertCode("EVIDENCE_REQUIRED",()->EdgeIR.create(f.scope,method.nodeId(),f.column.nodeId(),EdgeKind.READS,"",List.of(),Certainty.CONFIRMED));
    }
    @Test void referencedEvidenceAndEndpointsMustExist() {
        var f=new GraphFixture(); var method=f.node("mapper",NodeType.METHOD);
        f.edges.add(EdgeIR.create(f.scope,method.nodeId(),f.column.nodeId(),EdgeKind.READS,"",List.of(Json.hash("missing")),Certainty.CONFIRMED));
        assertCode("EVIDENCE_NOT_FOUND",f::graph);
        f.edges.clear(); f.edges.add(EdgeIR.create(f.scope,method.nodeId(),Json.hash("missing"),EdgeKind.READS,"",List.of(f.evidence.evidenceId()),Certainty.CONFIRMED));
        assertCode("UNRESOLVED_ENDPOINT",f::graph);
    }
    @Test void endpointTypeAndConfidenceCannotBeSilentlyCorrected() {
        var f=new GraphFixture(); var page=f.node("page",NodeType.VUE_PAGE); f.edge(page,f.column,EdgeKind.READS);
        assertCode("INVALID_ENDPOINT_TYPE",f::graph);
        var e=f.edges.getFirst();
        assertCode("INVALID_CONFIDENCE",()->new EdgeIR(VERSION,f.scope.projectId(),f.scope.snapshotId(),e.relationId(),e.fromId(),e.toId(),e.kind(),e.condition(),e.bindingKey(),e.evidenceIds(),Certainty.CONFIRMED,new BigDecimal("0.6"),e.producerVersion()));
    }
    @Test void unknownEnumsFieldsDuplicateJsonAndTypeCoercionAreRejected() throws Exception {
        var f=new GraphFixture(); String json=Json.MAPPER.writeValueAsString(f.column);
        assertThrows(Exception.class,()->Json.MAPPER.readValue(json.replace("\"COLUMN\"","\"PAGE\""),NodeIR.class));
        assertThrows(Exception.class,()->Json.MAPPER.readValue(json.replaceFirst("\\{","{\"surprise\":1,"),NodeIR.class));
        assertThrows(Exception.class,()->Json.MAPPER.readTree("{\"x\":1,\"x\":2}"));
        assertThrows(Exception.class,()->Json.MAPPER.readValue("\"1\"",Integer.class));
        assertEquals(f.column,Json.MAPPER.readValue(json,NodeIR.class));
    }
    @Test void invalidLocationAndSnippetHashAreRejected() {
        assertCode("INVALID_LOCATION",()->new Location("f",2,4,1,1,0L,4L,null));
        var f=new GraphFixture(); var e=f.evidence;
        assertCode("INVALID_SNIPPET",()->new EvidenceIR(VERSION,f.scope.projectId(),f.scope.snapshotId(),e.evidenceId(),f.source,e.sourceHash(),e.kind(),e.producerVersion(),e.location(),e.validation(),e.certainty(),e.condition(),"content",Json.hash("wrong")));
    }
    @Test void attributesAndGraphCannotBeMutated() {
        var f=new GraphFixture(); var g=f.graph(); f.nodes.clear();
        assertEquals(1,g.document().nodes().size());
        assertThrows(UnsupportedOperationException.class,()->g.node(f.column.nodeId()).attributes().put("dbType","uuid"));
    }
    @Test void typeSpecificAttributesCannotInventValues() {
        var f=new GraphFixture();
        assertCode("INVALID_ATTRIBUTES",()->NodeIR.create(f.scope,f.source,NodeType.COLUMN,f.source+"/bad","bad",f.column.sourceRef(),Map.of("dbType","text","nullable","false","identityMeaning","key")));
        assertCode("INVALID_ATTRIBUTES",()->NodeIR.create(f.scope,f.source,NodeType.COLUMN,f.source+"/bad","bad",f.column.sourceRef(),Map.of("dbType","text","nullable",false,"identityMeaning","key","modelScore",1)));
    }
    @Test void duplicateRelationsMergeEvidenceWithoutChangingFacts() {
        var f=new GraphFixture(); var method=f.node("mapper",NodeType.METHOD); var edge=f.edge(method,f.column,EdgeKind.READS);
        var second=EvidenceIR.create(f.scope,f.source,f.sourceHash,"TEST_BINDING","fixture-v2",Location.metadata("other"),null);
        var duplicate=EdgeIR.create(f.scope,method.nodeId(),f.column.nodeId(),EdgeKind.READS,"",List.of(second.evidenceId()),Certainty.CONFIRMED);
        var graph=new FactGraph(new GraphDocument(VERSION,f.scope.projectId(),f.scope.snapshotId(),f.nodes,List.of(edge,duplicate),List.of(f.evidence,second),List.of()));
        assertEquals(1,graph.document().edges().size()); assertEquals(2,graph.incoming(f.column.nodeId()).getFirst().evidenceIds().size());
        var inferred=EdgeIR.create(f.scope,method.nodeId(),f.column.nodeId(),EdgeKind.READS,"",List.of(second.evidenceId()),Certainty.INFERRED);
        assertCode("IDENTITY_CONFLICT",()->new FactGraph(new GraphDocument(VERSION,f.scope.projectId(),f.scope.snapshotId(),f.nodes,List.of(edge,inferred),List.of(f.evidence,second),List.of())));
    }
    static void assertCode(String expected,org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected,assertThrows(ContractException.class,action).code());
    }
}
