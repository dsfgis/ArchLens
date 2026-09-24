package io.archlens.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.util.*;
import static io.archlens.contract.ContractException.require;

public final class Model {
    public static final String VERSION = "archlens-contract-v1";
    private Model() {}
    public enum NodeType { SYSTEM, MODULE, CLASS, METHOD, FIELD, TABLE, COLUMN, VIEW,
        SQL_STATEMENT, API, VUE_PAGE, COMPONENT, FUNCTION, CONFIG, JSON_FIELD, FRONTEND_ACCESS }
    public enum EdgeKind { CALLS, READS, WRITES, HANDLED_BY, CONSUMES, MAPS_TO, DERIVED_FROM,
        SERIALIZES_FROM, READS_FIELD, FK_TO, CONTAINS, DESERIALIZES_TO }
    public enum Certainty { CONFIRMED, INFERRED }
    public record Scope(UUID projectId, UUID snapshotId) {
        public Scope { Objects.requireNonNull(projectId); Objects.requireNonNull(snapshotId); }
    }
    public interface Scoped {
        UUID projectId(); UUID snapshotId();
        @JsonIgnore default Scope scope() { return new Scope(projectId(), snapshotId()); }
    }
    /** End is exclusive. A source location is either a byte range or a metadata object. */
    public record Location(String path, Integer startLine, Integer startColumn, Integer endLine,
                           Integer endColumn, Long byteOffset, Long byteLength, String metadataKey) {
        public Location {
            if (metadataKey != null) {
                text(metadataKey, "metadataKey");
                require(path == null && startLine == null && startColumn == null && endLine == null
                        && endColumn == null && byteOffset == null && byteLength == null,
                        "INVALID_LOCATION", "Metadata locations cannot contain source offsets");
            } else {
                text(path, "path");
                require(startLine != null && startColumn != null && endLine != null && endColumn != null
                        && byteOffset != null && byteLength != null, "INVALID_LOCATION", "Missing range");
                require(startLine >= 1 && startColumn >= 1 && endLine >= startLine && endColumn >= 1
                        && (endLine > startLine || endColumn >= startColumn) && byteOffset >= 0 && byteLength >= 0,
                        "INVALID_LOCATION", "Invalid source range");
            }
        }
        public static Location metadata(String key) { return new Location(null,null,null,null,null,null,null,key); }
    }
    public record SourceRef(String sourceHash, Location location) {
        public SourceRef { hash(sourceHash); Objects.requireNonNull(location); }
    }
    public record NodeIR(String schemaVersion, UUID projectId, UUID snapshotId, String nodeId,
                         String logicalKey, NodeType type, String name, UUID sourceId,
                         SourceRef sourceRef, Map<String,Object> attributes) implements Scoped {
        public NodeIR {
            common(schemaVersion, projectId, snapshotId); hash(nodeId); text(logicalKey,"logicalKey");
            Objects.requireNonNull(type); text(name,"name"); Objects.requireNonNull(sourceId); Objects.requireNonNull(sourceRef);
            require(logicalKey.startsWith(sourceId + "/"), "INVALID_LOGICAL_KEY", "logicalKey must start with sourceId/");
            require(nodeId.equals(Model.nodeId(new Scope(projectId,snapshotId),type,logicalKey)), "INVALID_ID", "Node identity mismatch");
            attributes = Json.freeze(attributes);
            AttributeSchema.validate(type,attributes);
        }
        public static NodeIR create(Scope s, UUID source, NodeType type, String key, String name, SourceRef ref, Map<String,Object> attrs) {
            return new NodeIR(VERSION,s.projectId(),s.snapshotId(),Model.nodeId(s,type,key),key,type,name,source,ref,attrs);
        }
    }
    public record EvidenceIR(String schemaVersion, UUID projectId, UUID snapshotId, String evidenceId,
                             UUID sourceId, String sourceHash, String kind, String producerVersion,
                             Location location, String validation, Certainty certainty, String condition,
                             String snippet, String snippetHash) implements Scoped {
        public EvidenceIR {
            common(schemaVersion,projectId,snapshotId); hash(evidenceId); Objects.requireNonNull(sourceId);
            hash(sourceHash); text(kind,"kind"); text(producerVersion,"producerVersion"); Objects.requireNonNull(location);
            text(validation,"validation"); Objects.requireNonNull(certainty); text(condition,"condition");
            require(evidenceId.equals(Model.evidenceId(new Scope(projectId,snapshotId),sourceId,sourceHash,location,kind,producerVersion)),
                    "INVALID_ID", "Evidence identity mismatch");
            require((snippet == null) == (snippetHash == null), "INVALID_SNIPPET", "Snippet and hash must occur together");
            if (snippet != null) require(Json.sha256(snippet.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(snippetHash),
                    "INVALID_SNIPPET", "Snippet hash mismatch");
        }
        public static EvidenceIR create(Scope s, UUID source, String sourceHash, String kind, String producer,
                                        Location location, String snippet) {
            return new EvidenceIR(VERSION,s.projectId(),s.snapshotId(),Model.evidenceId(s,source,sourceHash,location,kind,producer),
                    source,sourceHash,kind,producer,location,"HASH_VERIFIED",Certainty.CONFIRMED,"true",snippet,
                    snippet == null ? null : Json.sha256(snippet.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
    public record EdgeIR(String schemaVersion, UUID projectId, UUID snapshotId, String relationId,
                         String fromId, String toId, EdgeKind kind, String condition, String bindingKey,
                         List<String> evidenceIds, Certainty certainty, BigDecimal confidence,
                         String producerVersion) implements Scoped {
        public EdgeIR {
            common(schemaVersion,projectId,snapshotId); hash(relationId); hash(fromId); hash(toId);
            Objects.requireNonNull(kind); text(condition,"condition"); Objects.requireNonNull(bindingKey);
            evidenceIds = Objects.requireNonNull(evidenceIds).stream().distinct().sorted().toList();
            evidenceIds.forEach(Model::hash); Objects.requireNonNull(certainty); unit(confidence);
            text(producerVersion,"producerVersion");
            require(certainty != Certainty.CONFIRMED || !evidenceIds.isEmpty(), "EVIDENCE_REQUIRED", "Confirmed edge requires evidence");
            require(confidence.compareTo(certainty == Certainty.CONFIRMED ? BigDecimal.ONE : new BigDecimal("0.6")) == 0,
                    "INVALID_CONFIDENCE", "v1 confidence must match the certainty rule");
            require(relationId.equals(Model.relationId(new Scope(projectId,snapshotId),fromId,toId,kind,condition,bindingKey)),
                    "INVALID_ID", "Relation identity mismatch");
        }
        public static EdgeIR create(Scope s, String from, String to, EdgeKind kind, String binding, List<String> evidence, Certainty certainty) {
            return new EdgeIR(VERSION,s.projectId(),s.snapshotId(),Model.relationId(s,from,to,kind,"true",binding),from,to,kind,"true",binding,
                    evidence,certainty,certainty == Certainty.CONFIRMED ? BigDecimal.ONE : new BigDecimal("0.6"),"archlens-core-0.1");
        }
    }
    public record DiagnosticIR(String schemaVersion, UUID projectId, UUID snapshotId, String diagnosticId,
                               UUID sourceId, String code, String message, Location location,
                               List<String> candidateNodeIds, String capability) implements Scoped {
        public DiagnosticIR {
            common(schemaVersion,projectId,snapshotId); hash(diagnosticId); Objects.requireNonNull(sourceId);
            text(code,"code"); text(message,"message"); Objects.requireNonNull(location);
            candidateNodeIds = List.copyOf(candidateNodeIds); candidateNodeIds.forEach(Model::hash); text(capability,"capability");
        }
        public static DiagnosticIR create(Scope s, UUID source, String code, String message, Location location, String capability) {
            return new DiagnosticIR(VERSION,s.projectId(),s.snapshotId(),Json.hashParts(s.projectId(),s.snapshotId(),source,code,location,message),
                    source,code,message,location,List.of(),capability);
        }
    }
    public record GraphDocument(String schemaVersion, UUID projectId, UUID snapshotId, List<NodeIR> nodes,
                                List<EdgeIR> edges, List<EvidenceIR> evidence, List<DiagnosticIR> diagnostics) implements Scoped {
        public GraphDocument {
            common(schemaVersion,projectId,snapshotId);
            nodes=List.copyOf(nodes); edges=List.copyOf(edges); evidence=List.copyOf(evidence); diagnostics=List.copyOf(diagnostics);
        }
    }
    public static String nodeId(Scope s, NodeType type, String key) { return Json.hashParts(s.projectId(),s.snapshotId(),type,key); }
    public static String evidenceId(Scope s, UUID source, String sourceHash, Location location, String kind, String producer) {
        return Json.hashParts(s.projectId(),s.snapshotId(),source,sourceHash,location,kind,producer);
    }
    public static String relationId(Scope s,String from,String to,EdgeKind kind,String condition,String binding) {
        return Json.hashParts(s.projectId(),s.snapshotId(),from,to,kind,Json.hash(condition),binding);
    }
    static void common(String version,UUID project,UUID snapshot) {
        require(VERSION.equals(version),"UNSUPPORTED_SCHEMA","Unsupported schemaVersion"); new Scope(project,snapshot);
    }
    public static void hash(String value) { require(value != null && value.matches("[a-f0-9]{64}"),"INVALID_ID","Expected lowercase SHA-256"); }
    public static void text(String value,String name) { require(value != null && !value.isBlank(),"REQUIRED",name+" is required"); }
    public static void unit(BigDecimal value) { require(value != null && value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0,"INVALID_RANGE","Expected value in [0,1]"); }
}
