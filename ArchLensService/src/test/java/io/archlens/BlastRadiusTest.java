package io.archlens;

import io.archlens.analysis.*;
import io.archlens.contract.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static io.archlens.contract.Model.*;
import static io.archlens.analysis.BlastRadius.*;
import static org.junit.jupiter.api.Assertions.*;

class BlastRadiusTest {
    Result run(GraphFixture f,ChangeSpec c) { return new BlastRadius(()->0L).calculate(f.graph(),c,0,Limits.defaults()); }
    Impact impact(Result r,NodeIR n) { return r.impacts().stream().filter(i->i.nodeId().equals(n.nodeId())).findFirst().orElseThrow(); }
    @Test void fix01RenameIsYesAtSqlButUnknownForObjectCaller() {
        var f=new GraphFixture(); var mapper=f.node("Mapper",NodeType.METHOD); var service=f.node("Service",NodeType.METHOD);
        f.edge(mapper,f.column,EdgeKind.READS); f.edge(service,mapper,EdgeKind.CALLS);
        var r=run(f,f.change(ChangeSpec.Kind.COLUMN_RENAME));
        assertEquals(Required.YES,impact(r,mapper).changeRequired()); assertEquals(Required.UNKNOWN,impact(r,service).changeRequired());
        assertFalse(r.truncated()); assertEquals("PARTIAL",r.status());
        assertEquals(32,impact(r,mapper).risk().min()); assertEquals(92,impact(r,mapper).risk().max());
    }
    @Test void fix02ProposedAliasNeverBecomesVerifiedNo() {
        var f=new GraphFixture(); var mapper=f.node("Mapper",NodeType.METHOD); var service=f.node("Service",NodeType.METHOD);
        f.edge(mapper,f.column,EdgeKind.READS); f.edge(service,mapper,EdgeKind.CALLS);
        var c=f.change(ChangeSpec.Kind.COLUMN_RENAME);
        var compatibility=new ChangeSpec.Compatibility("SQL_ALIAS",List.of(mapper.nodeId()),ChangeSpec.CompatibilityState.PROPOSED,List.of(),List.of("Preserve JSON name and value semantics"));
        var r=run(f,new ChangeSpec(c.targetId(),c.kind(),c.before(),c.after(),c.stage(),List.of(compatibility),List.of()));
        assertEquals(Required.YES,impact(r,mapper).changeRequired());
        assertEquals(Required.UNKNOWN,impact(r,service).changeRequired()); assertEquals(Required.NO,impact(r,service).conditionedOutcome());
        ContractTest.assertCode("UNSUPPORTED_VERIFICATION",()->new ChangeSpec.Compatibility("SQL_ALIAS",List.of(mapper.nodeId()),ChangeSpec.CompatibilityState.VERIFIED,List.of(),List.of()));
    }
    @Test void fix08TextToUuidRetainsUnknownRisk() {
        var f=new GraphFixture(); var mapper=f.node("Mapper",NodeType.METHOD); f.edge(mapper,f.column,EdgeKind.READS);
        var i=impact(run(f,f.change(ChangeSpec.Kind.COLUMN_TYPE_CHANGE)),mapper);
        assertEquals(Required.UNKNOWN,i.changeRequired()); assertEquals(0,i.risk().min()); assertEquals(100,i.risk().max());
    }
    @Test void candidateChainCannotUpgradeToConfirmed() {
        var f=new GraphFixture(); var field=f.node("field",NodeType.FIELD); var derived=f.node("derived",NodeType.FIELD);
        f.edge(field,f.column,EdgeKind.MAPS_TO,"",Certainty.INFERRED); f.edge(derived,field,EdgeKind.DERIVED_FROM);
        var r=run(f,f.change(ChangeSpec.Kind.COLUMN_DROP));
        assertEquals(Required.UNKNOWN,impact(r,derived).changeRequired()); assertEquals(Certainty.INFERRED,impact(r,derived).certainty());
        assertEquals(new BigDecimal("0.6"),r.paths().stream().filter(p->p.nodeIds().getLast().equals(derived.nodeId())).findFirst().orElseThrow().confidence());
    }
    @Test void fix09FourthLowerStrengthPathStillMakesYesAndCycleTerminates() {
        var f=new GraphFixture(); var consumer=f.node("consumer",NodeType.FIELD); var helper=f.node("helper",NodeType.FIELD);
        for(int i=0;i<3;i++) f.edge(consumer,f.column,EdgeKind.MAPS_TO,"candidate-"+i,Certainty.INFERRED);
        f.edge(helper,f.column,EdgeKind.MAPS_TO); f.edge(consumer,helper,EdgeKind.DERIVED_FROM); f.edge(helper,consumer,EdgeKind.DERIVED_FROM);
        var r=run(f,f.change(ChangeSpec.Kind.COLUMN_DROP)); var i=impact(r,consumer);
        assertEquals(Required.YES,i.changeRequired()); assertEquals(3,i.pathIds().size());
        assertTrue(r.paths().stream().filter(p->i.pathIds().contains(p.pathId())).allMatch(p->p.changeRequired()==Required.UNKNOWN));
        assertTrue(r.cyclesSkipped()>0); assertFalse(r.truncated());
        assertEquals(r,run(f,f.change(ChangeSpec.Kind.COLUMN_DROP)));
    }
    @Test void sixHopObjectChainUsesUnroundedComputation() {
        var f=new GraphFixture(); var previous=f.node("m0",NodeType.METHOD); f.edge(previous,f.column,EdgeKind.READS);
        for(int i=1;i<=5;i++) { var next=f.node("m"+i,NodeType.METHOD); f.edge(next,previous,EdgeKind.CALLS); previous=next; }
        assertEquals(new BigDecimal("26.20"),impact(run(f,f.change(ChangeSpec.Kind.COLUMN_RENAME)),previous).impactScore());
    }
    @Test void depthLimitDoesNotMarkLeafAsTruncated() {
        var f=new GraphFixture(); var mapper=f.node("Mapper",NodeType.METHOD); f.edge(mapper,f.column,EdgeKind.READS);
        var limits=new Limits(1,10,100,3,Duration.ofSeconds(5));
        assertFalse(new BlastRadius(()->0).calculate(f.graph(),f.change(ChangeSpec.Kind.COLUMN_RENAME),0,limits).truncated());
        var caller=f.node("caller",NodeType.METHOD); f.edge(caller,mapper,EdgeKind.CALLS);
        assertTrue(new BlastRadius(()->0).calculate(f.graph(),f.change(ChangeSpec.Kind.COLUMN_RENAME),0,limits).truncationReasons().contains("MAX_DEPTH"));
    }
    @Test void allBudgetsAndContainsHaveObservableBoundaries() {
        var f=new GraphFixture(); var mapper=f.node("Mapper",NodeType.METHOD); var caller=f.node("caller",NodeType.METHOD);
        var owner=f.node("owner",NodeType.MODULE); f.edge(mapper,f.column,EdgeKind.READS); f.edge(caller,mapper,EdgeKind.CALLS); f.edge(owner,f.column,EdgeKind.CONTAINS);
        var r=run(f,f.change(ChangeSpec.Kind.COLUMN_RENAME)); assertFalse(r.impacts().stream().anyMatch(i->i.nodeId().equals(owner.nodeId())));
        assertTrue(new BlastRadius(()->0).calculate(f.graph(),f.change(ChangeSpec.Kind.COLUMN_RENAME),0,new Limits(8,1,100,3,Duration.ofSeconds(1))).truncationReasons().contains("MAX_NODES"));
        assertTrue(new BlastRadius(()->0).calculate(f.graph(),f.change(ChangeSpec.Kind.COLUMN_RENAME),0,new Limits(8,10,1,3,Duration.ofSeconds(1))).truncationReasons().contains("WORK_UNITS"));
        AtomicLong time=new AtomicLong();
        assertTrue(new BlastRadius(()->time.getAndAdd(1_000_000_000)).calculate(f.graph(),f.change(ChangeSpec.Kind.COLUMN_RENAME),0,Limits.defaults()).truncationReasons().contains("WALL_TIME"));
    }
    @Test void beforeMismatchAndSemanticDriftAreRejected() {
        var f=new GraphFixture(); var c=f.change(ChangeSpec.Kind.COLUMN_RENAME);
        var wrong=new ChangeSpec(c.targetId(),c.kind(),new ChangeSpec.ColumnState("different","text",false,"device-key"),c.after(),c.stage(),List.of(),List.of());
        ContractTest.assertCode("BEFORE_MISMATCH",()->run(f,wrong));
        ContractTest.assertCode("INVALID_CHANGE",()->new ChangeSpec(c.targetId(),c.kind(),c.before(),new ChangeSpec.ColumnState("global_id","uuid",false,"another-key"),c.stage(),List.of(),List.of()));
    }
    @Test void riskHalfUpAndUnknownIntervalsDoNotUseConfidence() {
        var b=new RiskScorer.Factor(BigDecimal.ONE,BigDecimal.ONE,RiskScorer.SourceKind.PROJECT_CONFIG,"approved-config",null,"reviewer");
        var k=new RiskScorer.Factor(new BigDecimal("0.8"),new BigDecimal("0.8"),RiskScorer.SourceKind.PROJECT_CONFIG,"approved-config",null,"reviewer");
        var d=new RiskScorer.Factor(new BigDecimal("0.9"),new BigDecimal("0.9"),RiskScorer.SourceKind.HUMAN_CONFIRMED,"recovery-review",null,"reviewer");
        assertEquals(91,RiskScorer.score(new RiskScorer.Inputs(b,k,d)).min());
        assertEquals(RiskScorer.Level.CRITICAL,RiskScorer.score(new RiskScorer.Inputs(b,k,d)).minLevel());
        assertEquals(0,RiskScorer.score(new RiskScorer.Inputs(null,null,null)).min());
        assertEquals(100,RiskScorer.score(new RiskScorer.Inputs(null,null,null)).max());
    }
    @Test void knownRenameAndUnresolvedConsumerExtendRiskUpperBound() {
        var f=new GraphFixture(); var mapper=f.node("Mapper",NodeType.METHOD);
        f.edge(mapper,f.column,EdgeKind.READS,"known",Certainty.CONFIRMED);
        f.edge(mapper,f.column,EdgeKind.READS,"candidate",Certainty.INFERRED);
        var i=impact(run(f,f.change(ChangeSpec.Kind.COLUMN_RENAME)),mapper);
        assertEquals(Required.YES,i.changeRequired()); assertEquals(32,i.risk().min()); assertEquals(100,i.risk().max());
    }
    @Test void nullabilityTighteningWithoutActualNullWriteEvidenceIsUnknown() {
        var f=new GraphFixture(); var original=f.column;
        var attrs=new HashMap<>(original.attributes()); attrs.put("nullable",true);
        f.nodes.set(0,new NodeIR(VERSION,f.scope.projectId(),f.scope.snapshotId(),original.nodeId(),original.logicalKey(),original.type(),original.name(),f.source,original.sourceRef(),attrs));
        var mapper=f.node("Mapper",NodeType.METHOD); f.edge(mapper,f.column,EdgeKind.WRITES);
        var change=new ChangeSpec(original.nodeId(),ChangeSpec.Kind.COLUMN_TYPE_CHANGE,new ChangeSpec.ColumnState("event_id","text",true,"device-key"),new ChangeSpec.ColumnState("event_id","text",false,"device-key"),ChangeSpec.Stage.PROPOSED,List.of(),List.of());
        var i=impact(run(f,change),mapper); assertEquals(Required.UNKNOWN,i.changeRequired()); assertEquals(100,i.risk().max());
    }
}
