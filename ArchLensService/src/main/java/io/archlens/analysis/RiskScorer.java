package io.archlens.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import static io.archlens.contract.Model.*;
import static io.archlens.contract.ContractException.require;

public final class RiskScorer {
    private RiskScorer() {}
    public enum SourceKind { RULE, PROJECT_CONFIG, HUMAN_CONFIRMED }
    public enum Level { LOW, MEDIUM, HIGH, CRITICAL }
    public record Factor(BigDecimal min, BigDecimal max, SourceKind sourceKind,
                         String sourceRef, String ruleId, String confirmedBy) {
        public Factor {
            unit(min); unit(max); require(min.compareTo(max)<=0,"INVALID_RANGE","min exceeds max");
            Objects.requireNonNull(sourceKind); text(sourceRef,"sourceRef");
            if (sourceKind==SourceKind.RULE) text(ruleId,"ruleId");
            else text(confirmedBy,"confirmedBy");
        }
        public static Factor unknown() { return new Factor(BigDecimal.ZERO,BigDecimal.ONE,SourceKind.RULE,"missing-input","RISK-UNKNOWN",null); }
        public static Factor rule(String value,String ref,String rule) {
            return new Factor(new BigDecimal(value),new BigDecimal(value),SourceKind.RULE,ref,rule,null);
        }
    }
    public record Inputs(Factor breaking,Factor criticality,Factor dataRecovery) {
        public Inputs {
            breaking=breaking==null ? Factor.unknown() : breaking;
            criticality=criticality==null ? Factor.unknown() : criticality;
            dataRecovery=dataRecovery==null ? Factor.unknown() : dataRecovery;
        }
    }
    public record Score(int min,int max,Level minLevel,Level maxLevel,Inputs inputs) {}
    public static Score score(Inputs input) {
        Objects.requireNonNull(input);
        int lo=weighted(input.breaking.min,input.criticality.min,input.dataRecovery.min);
        int hi=weighted(input.breaking.max,input.criticality.max,input.dataRecovery.max);
        return new Score(lo,hi,level(lo),level(hi),input);
    }
    private static int weighted(BigDecimal b,BigDecimal k,BigDecimal d) {
        return b.multiply(new BigDecimal("0.40")).add(k.multiply(new BigDecimal("0.35")))
                .add(d.multiply(new BigDecimal("0.25"))).multiply(new BigDecimal("100"))
                .setScale(0,RoundingMode.HALF_UP).intValueExact();
    }
    private static Level level(int score) { return score>=80 ? Level.CRITICAL : score>=60 ? Level.HIGH : score>=35 ? Level.MEDIUM : Level.LOW; }
}
