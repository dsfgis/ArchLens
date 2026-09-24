package io.archlens.analysis;

import io.archlens.contract.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;
import static io.archlens.contract.Model.*;
import static io.archlens.contract.ContractException.require;

/** Traversal and judgments are independent of the capped explanation-path display. */
public final class BlastRadius {
    public static final String RULE_VERSION="archlens-rules-0.1-column-slice";
    private static final BigDecimal DECAY=new BigDecimal("0.85");
    public enum Required { YES, NO, UNKNOWN }
    public enum Semantic { PHYSICAL_SCHEMA, VALUE_TYPE, FIELD_CONTRACT, METHOD_SIGNATURE, OBJECT_DEPENDENCY }
    public record Limits(int maxDepth,int maxNodes,int maxWorkUnits,int pathsPerNode,Duration wallTime) {
        public Limits {
            require(maxDepth>=0 && maxDepth<=64 && maxNodes>=1 && maxNodes<=100000 && maxWorkUnits>=1
                    && maxWorkUnits<=1000000 && pathsPerNode>=1 && pathsPerNode<=10,
                    "INVALID_LIMIT","Traversal limit out of bounds");
            require(wallTime!=null && !wallTime.isNegative() && !wallTime.isZero() && wallTime.compareTo(Duration.ofMinutes(2))<=0,
                    "INVALID_LIMIT","Invalid wall-time budget");
        }
        public static Limits defaults() { return new Limits(8,5000,50000,3,Duration.ofSeconds(5)); }
    }
    public record Unknown(String unknownId,String nodeId,String code) {}
    public record Path(String pathId,List<String> nodeIds,List<String> orderedRelationIds,
                       List<String> evidenceIds,Semantic semanticState,Certainty certainty,
                       BigDecimal impactScore,BigDecimal confidence,Required changeRequired) {}
    public record Impact(String targetId,String nodeId,boolean dependencyPresent,Required changeRequired,
                         Certainty certainty,BigDecimal impactScore,RiskScorer.Score risk,List<String> pathIds,
                         List<String> evidenceIds,List<String> unknownIds,List<String> conditions,Required conditionedOutcome) {}
    public record Result(String ruleVersion,UUID projectId,UUID snapshotId,long overlayRevision,String status,
                         List<Impact> impacts,List<Path> paths,List<Unknown> unknowns,boolean truncated,
                         List<String> truncationReasons,int workUnits,int cyclesSkipped,List<String> limitations) {}
    private record State(String nodeId,List<String> nodes,List<EdgeIR> edges,BigDecimal strength,
                         BigDecimal confidence,Certainty certainty,Semantic semantic,Required judgment,
                         List<String> conditions,String unknownCode) {}
    private static final class Aggregate {
        final String nodeId;
        boolean yes,unknown,confirmedPath;
        BigDecimal strength=BigDecimal.ZERO;
        final Set<String> evidence=new TreeSet<>(), conditions=new TreeSet<>(), unknownIds=new TreeSet<>();
        final List<Path> paths=new ArrayList<>();
        Aggregate(String nodeId) { this.nodeId=nodeId; }
    }
    private final LongSupplier clock;
    public BlastRadius() { this(System::nanoTime); }
    public BlastRadius(LongSupplier clock) { this.clock=Objects.requireNonNull(clock); }
    public Result calculate(FactGraph graph,ChangeSpec change,long overlayRevision,Limits limits) {
        require(overlayRevision==0,"UNSUPPORTED_OVERLAY","Overlay materialization is not implemented in this slice");
        change.validate(graph);
        long started=clock.getAsLong();
        Map<String,Aggregate> merged=new TreeMap<>(); Map<String,Unknown> unknowns=new TreeMap<>();
        Set<String> truncation=new TreeSet<>(); int work=0,cycles=0;
        Comparator<State> order=Comparator.comparing(State::strength).reversed()
                .thenComparing(s->graph.node(s.nodeId).logicalKey(),Json::compareCodePoints)
                .thenComparing(s->Json.hash(s.edges.stream().map(EdgeIR::relationId).toList()));
        PriorityQueue<State> queue=new PriorityQueue<>(order);
        State root=new State(change.targetId(),List.of(change.targetId()),List.of(),new BigDecimal("100"),BigDecimal.ONE,
                Certainty.CONFIRMED,change.kind()==ChangeSpec.Kind.COLUMN_TYPE_CHANGE ? Semantic.VALUE_TYPE : Semantic.PHYSICAL_SCHEMA,
                Required.YES,List.of(),null);
        merge(graph,change,root,merged,unknowns,limits); queue.add(root);
        Set<String> discovered=new HashSet<>(); discovered.add(root.nodeId);
        outer: while(!queue.isEmpty()) {
            if(clock.getAsLong()-started>=limits.wallTime.toNanos()) { truncation.add("WALL_TIME"); break; }
            State current=queue.remove();
            for(EdgeIR edge:graph.incoming(current.nodeId)) {
                if(edge.kind()==EdgeKind.CONTAINS) continue;
                if(clock.getAsLong()-started>=limits.wallTime.toNanos()) { truncation.add("WALL_TIME"); break outer; }
                if(work>=limits.maxWorkUnits) { truncation.add("WORK_UNITS"); break outer; }
                work++;
                if(current.nodes.contains(edge.fromId())) { cycles++; continue; }
                if(current.edges.size()>=limits.maxDepth) { truncation.add("MAX_DEPTH"); continue; }
                if(!discovered.contains(edge.fromId()) && discovered.size()>=limits.maxNodes) { truncation.add("MAX_NODES"); continue; }
                State next=advance(current,edge,change);
                discovered.add(next.nodeId); merge(graph,change,next,merged,unknowns,limits); queue.add(next);
            }
        }
        List<Impact> impacts=new ArrayList<>(); List<Path> paths=new ArrayList<>();
        for(Aggregate a:merged.values()) {
            if(!truncation.isEmpty() && !a.yes) { a.unknown=true; addUnknown(a,"TRUNCATED",unknowns); }
            if(!graph.document().diagnostics().isEmpty() && !a.yes) { a.unknown=true; addUnknown(a,"COVERAGE_GAP",unknowns); }
            Required judgment=a.yes ? Required.YES : a.unknown ? Required.UNKNOWN : Required.NO;
            var breaking=RiskScorer.Factor.unknown();
            if(judgment==Required.YES && change.kind()!=ChangeSpec.Kind.COLUMN_TYPE_CHANGE) {
                BigDecimal lower=new BigDecimal(change.kind()==ChangeSpec.Kind.COLUMN_RENAME ? "0.8" : "1");
                breaking=new RiskScorer.Factor(lower,a.unknown ? BigDecimal.ONE : lower,RiskScorer.SourceKind.RULE,a.nodeId,"BR-COLUMN",null);
            }
            // No business criticality/data recovery configuration has been verified in this offline slice.
            var risk=RiskScorer.score(new RiskScorer.Inputs(breaking,null,null));
            paths.addAll(a.paths);
            impacts.add(new Impact(change.targetId(),a.nodeId,true,judgment,a.confirmedPath ? Certainty.CONFIRMED : Certainty.INFERRED,
                    display(a.strength),risk,a.paths.stream().map(Path::pathId).toList(),List.copyOf(a.evidence),List.copyOf(a.unknownIds),
                    List.copyOf(a.conditions),judgment==Required.UNKNOWN && !a.conditions.isEmpty() ? Required.NO : null));
        }
        impacts.sort(Comparator.comparing(Impact::impactScore).reversed().thenComparing(i->graph.node(i.nodeId).logicalKey(),Json::compareCodePoints));
        paths.sort(Comparator.comparing(Path::pathId));
        return new Result(RULE_VERSION,graph.scope().projectId(),graph.scope().snapshotId(),overlayRevision,"PARTIAL",List.copyOf(impacts),
                List.copyOf(paths),List.copyOf(unknowns.values()),!truncation.isEmpty(),List.copyOf(truncation),work,cycles,
                List.of("COLUMN_SLICE_ONLY","NO_VERIFIED_COMPATIBILITY_ENGINE","BUSINESS_AND_RECOVERY_FACTORS_UNKNOWN","FIELD_COVERAGE_NOT_PROVEN"));
    }
    private static State advance(State current,EdgeIR edge,ChangeSpec change) {
        boolean exact=current.certainty==Certainty.CONFIRMED && edge.certainty()==Certainty.CONFIRMED && edge.condition().equals("true");
        Certainty certainty=exact ? Certainty.CONFIRMED : Certainty.INFERRED;
        Semantic semantic=current.semantic;
        Required judgment=Required.UNKNOWN; String unknown="FIELD_LINEAGE_GAP";
        boolean columnReference=EnumSet.of(EdgeKind.READS,EdgeKind.WRITES,EdgeKind.MAPS_TO,EdgeKind.FK_TO).contains(edge.kind());
        boolean fieldFlow=EnumSet.of(EdgeKind.MAPS_TO,EdgeKind.DERIVED_FROM,EdgeKind.SERIALIZES_FROM,EdgeKind.READS_FIELD).contains(edge.kind());
        if(edge.kind()==EdgeKind.CALLS || edge.kind()==EdgeKind.HANDLED_BY || edge.kind()==EdgeKind.CONSUMES) {
            semantic=Semantic.OBJECT_DEPENDENCY;
        } else if(current.semantic!=Semantic.OBJECT_DEPENDENCY && exact) {
            if(change.kind()==ChangeSpec.Kind.COLUMN_RENAME && current.semantic==Semantic.PHYSICAL_SCHEMA
                    && (edge.kind()==EdgeKind.READS || edge.kind()==EdgeKind.WRITES)) {
                judgment=Required.YES; unknown=null;
            } else if(change.kind()==ChangeSpec.Kind.COLUMN_DROP && ((current.semantic==Semantic.PHYSICAL_SCHEMA && columnReference)
                    || (current.semantic==Semantic.FIELD_CONTRACT && fieldFlow))) {
                judgment=Required.YES; semantic=Semantic.FIELD_CONTRACT; unknown=null;
            } else if(change.kind()==ChangeSpec.Kind.COLUMN_TYPE_CHANGE) unknown="TYPE_COMPATIBILITY_UNVERIFIED";
        }
        if(!exact) { judgment=Required.UNKNOWN; unknown="CONDITIONAL_OR_CANDIDATE_PATH"; }
        List<String> conditions=new ArrayList<>(current.conditions);
        for(var compatibility:change.compatibility())
            if(compatibility.state()==ChangeSpec.CompatibilityState.PROPOSED && compatibility.scopeIds().contains(edge.fromId()))
                conditions.addAll(compatibility.conditions());
        List<String> nodes=new ArrayList<>(current.nodes); nodes.add(edge.fromId());
        List<EdgeIR> edges=new ArrayList<>(current.edges); edges.add(edge);
        BigDecimal strength=current.strength.multiply(weight(edge.kind()));
        if(!current.edges.isEmpty()) strength=strength.multiply(DECAY);
        return new State(edge.fromId(),List.copyOf(nodes),List.copyOf(edges),strength,current.confidence.min(edge.confidence()),
                certainty,semantic,judgment,conditions.stream().distinct().sorted().toList(),unknown);
    }
    private static BigDecimal weight(EdgeKind kind) {
        return switch(kind) { case CALLS,HANDLED_BY,CONSUMES -> new BigDecimal("0.9"); default -> BigDecimal.ONE; };
    }
    private static void merge(FactGraph graph,ChangeSpec change,State state,Map<String,Aggregate> merged,Map<String,Unknown> unknowns,Limits limits) {
        Aggregate a=merged.computeIfAbsent(state.nodeId,Aggregate::new);
        a.yes|=state.judgment==Required.YES && state.certainty==Certainty.CONFIRMED;
        a.confirmedPath|=state.certainty==Certainty.CONFIRMED;
        a.unknown|=state.judgment==Required.UNKNOWN;
        a.strength=a.strength.max(state.strength); a.conditions.addAll(state.conditions);
        List<String> evidence=state.edges.stream().flatMap(e->e.evidenceIds().stream()).distinct().sorted().toList();
        a.evidence.addAll(evidence);
        if(state.unknownCode!=null) addUnknown(a,state.unknownCode,unknowns);
        List<String> edgeIds=state.edges.stream().map(EdgeIR::relationId).toList();
        String id=Json.hashParts(graph.scope().snapshotId(),0,change.targetId(),edgeIds,state.semantic);
        Path path=new Path(id,state.nodes,edgeIds,evidence,state.semantic,state.certainty,display(state.strength),state.confidence,state.judgment);
        a.paths.add(path);
        a.paths.sort(Comparator.comparing(Path::impactScore).reversed().thenComparing(Path::pathId));
        if(a.paths.size()>limits.pathsPerNode) a.paths.removeLast();
    }
    private static void addUnknown(Aggregate a,String code,Map<String,Unknown> unknowns) {
        String id=Json.hashParts(a.nodeId,code); a.unknownIds.add(id); unknowns.putIfAbsent(id,new Unknown(id,a.nodeId,code));
    }
    private static BigDecimal display(BigDecimal n) { return n.setScale(2,RoundingMode.HALF_UP); }
}
