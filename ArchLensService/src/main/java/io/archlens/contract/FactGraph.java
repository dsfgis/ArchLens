package io.archlens.contract;

import java.util.*;
import java.util.function.Function;
import static io.archlens.contract.Model.*;
import static io.archlens.contract.ContractException.require;

/** Validated immutable snapshot view. No database, model or mutation API. */
public final class FactGraph {
    private final GraphDocument document;
    private final Map<String, NodeIR> nodes;
    private final Map<String, EvidenceIR> evidence;
    private final Map<String, List<EdgeIR>> incoming;
    public FactGraph(GraphDocument document) {
        nodes = index(document.nodes(), NodeIR::nodeId);
        evidence = index(document.evidence(), EvidenceIR::evidenceId);
        var edges = mergeEdges(document.edges());
        var diagnostics=index(document.diagnostics(), DiagnosticIR::diagnosticId);
        List<Scoped> scoped = new ArrayList<>(); scoped.addAll(nodes.values()); scoped.addAll(edges.values());
        scoped.addAll(evidence.values()); scoped.addAll(document.diagnostics());
        scoped.forEach(x -> require(x.scope().equals(document.scope()),"SCOPE_MISMATCH","Record belongs to another snapshot"));
        nodes.values().forEach(n->{
            Object diagnostic=n.attributes().get("diagnosticId");
            require(diagnostic==null || diagnostics.containsKey(diagnostic),"DIAGNOSTIC_NOT_FOUND","Node references an absent diagnostic");
        });
        Map<String,List<EdgeIR>> incoming = new HashMap<>();
        for (EdgeIR edge : edges.values()) {
            NodeIR from=nodes.get(edge.fromId()), to=nodes.get(edge.toId());
            require(from != null && to != null,"UNRESOLVED_ENDPOINT","Edge endpoint is absent in this snapshot");
            require(allowed(edge.kind(),from.type(),to.type()),"INVALID_ENDPOINT_TYPE","Illegal endpoints for "+edge.kind());
            for (String id : edge.evidenceIds()) {
                EvidenceIR item = evidence.get(id);
                require(item != null,"EVIDENCE_NOT_FOUND","Edge references absent evidence");
                require(edge.certainty() != Certainty.CONFIRMED || item.certainty() == Certainty.CONFIRMED,
                        "EVIDENCE_NOT_CONFIRMED","Confirmed relation requires confirmed evidence");
            }
            incoming.computeIfAbsent(edge.toId(),k -> new ArrayList<>()).add(edge);
        }
        document.diagnostics().forEach(d -> d.candidateNodeIds().forEach(id ->
                require(nodes.containsKey(id),"UNRESOLVED_ENDPOINT","Diagnostic candidate is absent")));
        incoming.replaceAll((id,list) -> list.stream().sorted(Comparator
                .comparing((EdgeIR e) -> nodes.get(e.fromId()).logicalKey(),Json::compareCodePoints)
                .thenComparing(EdgeIR::relationId)).toList());
        this.incoming=Map.copyOf(incoming);
        this.document=new GraphDocument(document.schemaVersion(),document.projectId(),document.snapshotId(),
                nodes.values().stream().sorted(Comparator.comparing(NodeIR::nodeId)).toList(),
                edges.values().stream().sorted(Comparator.comparing(EdgeIR::relationId)).toList(),
                evidence.values().stream().sorted(Comparator.comparing(EvidenceIR::evidenceId)).toList(),document.diagnostics());
    }
    private static Map<String,EdgeIR> mergeEdges(List<EdgeIR> values) {
        Map<String,EdgeIR> result=new HashMap<>();
        for(EdgeIR edge:values) {
            EdgeIR old=result.get(edge.relationId());
            if(old==null) { result.put(edge.relationId(),edge); continue; }
            EdgeIR sameEvidence=new EdgeIR(edge.schemaVersion(),edge.projectId(),edge.snapshotId(),edge.relationId(),edge.fromId(),edge.toId(),
                    edge.kind(),edge.condition(),edge.bindingKey(),old.evidenceIds(),edge.certainty(),edge.confidence(),edge.producerVersion());
            require(Json.canonical(old).equals(Json.canonical(sameEvidence)),"IDENTITY_CONFLICT","Same relation ID has conflicting facts");
            List<String> evidence=new ArrayList<>(old.evidenceIds()); evidence.addAll(edge.evidenceIds());
            result.put(edge.relationId(),new EdgeIR(edge.schemaVersion(),edge.projectId(),edge.snapshotId(),edge.relationId(),edge.fromId(),edge.toId(),
                    edge.kind(),edge.condition(),edge.bindingKey(),evidence,edge.certainty(),edge.confidence(),edge.producerVersion()));
        }
        return Map.copyOf(result);
    }
    private static <T> Map<String,T> index(List<T> values,Function<T,String> id) {
        Map<String,T> result=new HashMap<>();
        for (T value:values) {
            T old=result.putIfAbsent(id.apply(value),value);
            require(old==null || Json.canonical(old).equals(Json.canonical(value)),"IDENTITY_CONFLICT","Same ID has different content");
        }
        return Map.copyOf(result);
    }
    private static boolean allowed(EdgeKind kind,NodeType from,NodeType to) {
        return switch(kind) {
            case CALLS -> (from==NodeType.METHOD && to==NodeType.METHOD)
                    || ((from==NodeType.FUNCTION || from==NodeType.VUE_PAGE) && to==NodeType.FUNCTION);
            case READS, WRITES -> from==NodeType.METHOD && to==NodeType.COLUMN;
            case HANDLED_BY -> from==NodeType.API && to==NodeType.METHOD;
            case CONSUMES -> from==NodeType.FUNCTION && to==NodeType.API;
            case MAPS_TO -> from==NodeType.FIELD && to==NodeType.COLUMN;
            case DERIVED_FROM -> from==NodeType.FIELD && to==NodeType.FIELD;
            case SERIALIZES_FROM -> from==NodeType.JSON_FIELD && to==NodeType.FIELD;
            case READS_FIELD -> from==NodeType.FRONTEND_ACCESS && to==NodeType.JSON_FIELD;
            case FK_TO -> from==NodeType.COLUMN && to==NodeType.COLUMN;
            case DESERIALIZES_TO -> from==NodeType.FIELD && to==NodeType.JSON_FIELD;
            case CONTAINS -> true;
        };
    }
    public Scope scope() { return document.scope(); }
    public GraphDocument document() { return document; }
    public NodeIR node(String id) {
        NodeIR value=nodes.get(id); require(value!=null,"NODE_NOT_FOUND","Node not in snapshot"); return value;
    }
    public List<EdgeIR> incoming(String id) { return incoming.getOrDefault(id,List.of()); }
    public EvidenceIR evidence(String id) { return evidence.get(id); }
}
