package io.archlens.analysis;

import io.archlens.contract.FactGraph;
import java.util.*;
import static io.archlens.contract.Model.*;
import static io.archlens.contract.ContractException.require;

/** Column changes are the implemented first slice. Unsupported kinds fail explicitly. */
public record ChangeSpec(String targetId,Kind kind,ColumnState before,ColumnState after,
                         Stage stage,List<Compatibility> compatibility,List<String> assumptions) {
    public enum Kind { COLUMN_RENAME, COLUMN_DROP, COLUMN_TYPE_CHANGE, API_CONTRACT_CHANGE, METHOD_SIGNATURE_CHANGE }
    public enum Stage { PROPOSED, APPLIED }
    public enum CompatibilityState { PROPOSED, VERIFIED, REJECTED }
    public record ColumnState(String name,String type,Boolean nullable,String identityMeaning) {
        public ColumnState { text(name,"name"); text(type,"type"); Objects.requireNonNull(nullable); text(identityMeaning,"identityMeaning"); }
    }
    public record Compatibility(String kind,List<String> scopeIds,CompatibilityState state,
                                List<String> evidenceIds,List<String> conditions) {
        public Compatibility {
            text(kind,"kind"); scopeIds=List.copyOf(scopeIds); scopeIds.forEach(io.archlens.contract.Model::hash);
            require(!scopeIds.isEmpty(),"INVALID_COMPATIBILITY","Compatibility scope cannot be empty");
            Objects.requireNonNull(state); evidenceIds=List.copyOf(evidenceIds); evidenceIds.forEach(io.archlens.contract.Model::hash);
            conditions=List.copyOf(conditions); conditions.forEach(c->text(c,"condition"));
            require(state!=CompatibilityState.VERIFIED,"UNSUPPORTED_VERIFICATION","Linked-snapshot compatibility verifier is not implemented; VERIFIED is not accepted");
            require(state!=CompatibilityState.PROPOSED || !conditions.isEmpty(),"INVALID_COMPATIBILITY","Proposed compatibility needs conditions");
        }
    }
    public ChangeSpec {
        hash(targetId); Objects.requireNonNull(kind); Objects.requireNonNull(before); Objects.requireNonNull(stage);
        require(kind==Kind.COLUMN_RENAME || kind==Kind.COLUMN_DROP || kind==Kind.COLUMN_TYPE_CHANGE,
                "UNSUPPORTED_CHANGE_KIND","This slice implements column changes only");
        require(stage==Stage.PROPOSED,"UNSUPPORTED_VERIFICATION","APPLIED requires linked-snapshot verification, not available in this slice");
        require(kind==Kind.COLUMN_DROP ? after==null : after!=null,"INVALID_CHANGE","Invalid after for change kind");
        if (kind==Kind.COLUMN_RENAME) {
            require(!before.name.equals(after.name) && before.type.equals(after.type) && before.nullable.equals(after.nullable)
                    && before.identityMeaning.equals(after.identityMeaning),"INVALID_CHANGE","COLUMN_RENAME must be a pure physical rename");
        }
        if (kind==Kind.COLUMN_TYPE_CHANGE) {
            require(before.name.equals(after.name) && before.identityMeaning.equals(after.identityMeaning)
                    && (!before.type.equals(after.type) || !before.nullable.equals(after.nullable)),"INVALID_CHANGE","Type change must preserve identity and change type or nullability");
        }
        compatibility=List.copyOf(compatibility); assumptions=List.copyOf(assumptions);
    }
    public void validate(FactGraph graph) {
        NodeIR node=graph.node(targetId);
        require(node.type()==NodeType.COLUMN,"INVALID_TARGET","Column change requires COLUMN target");
        require(before.name.equals(node.name()) && before.type.equals(node.attributes().get("dbType"))
                && before.nullable.equals(node.attributes().get("nullable"))
                && before.identityMeaning.equals(node.attributes().get("identityMeaning")),"BEFORE_MISMATCH","before does not match fixed snapshot column");
        for (Compatibility c:compatibility) {
            c.scopeIds.forEach(graph::node);
            c.evidenceIds.forEach(id->require(graph.evidence(id)!=null,"EVIDENCE_NOT_FOUND","Compatibility evidence absent in this snapshot"));
        }
    }
}
