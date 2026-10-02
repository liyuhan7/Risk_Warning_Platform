package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.node.*;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.riskwarning.processing.rule.RuleTestData.*;

class RiskThresholdEvaluatorTest {
    private final RiskThresholdEvaluator evaluator = new RiskThresholdEvaluator();

    @ParameterizedTest
    @CsvSource({"0,HIGH_RISK", "0.2,HIGH_RISK", "0.4,MEDIUM_RISK", "0.8,MEDIUM_RISK", "1,LOW_RISK", "1.5,LOW_RISK", "1.6,NO_RISK", "2,NO_RISK"})
    void usesExplicitMultilevelIntervalsAndUncoveredDomain(double score, RiskLevelEnum expected) {
        ObjectNode r = rule("binary"); ArrayNode a = r.putArray("riskIntervals");
        interval(a, 0, 0.2, true, false, "HIGH_RISK");
        interval(a, 0.2, 0.5, true, false, "MEDIUM_RISK");
        interval(a, 0.5, 0.8, true, false, "LOW_RISK");
        RiskThresholdEvaluator.RiskDecision result = evaluator.evaluate(load(r).require("binary", source("binary")), score);
        assertEquals(expected, result.getRiskLevel());
        assertEquals(expected != RiskLevelEnum.NO_RISK, result.isTriggered());
        assertEquals(score / 2, result.getScoreRatio());
    }

    @Test void emptyIntervalsDoNotFallBackToHalfOrDefaultLevel() {
        ObjectNode r = rule("binary"); r.putArray("riskIntervals");
        assertFalse(evaluator.evaluate(load(r).require("binary", source("binary")), 0).isTriggered());
    }

    @Test void preciseBoundaryUsesMultiplicationAndSupportsClosedSingletonInterval() {
        ObjectNode r = rule("binary"); r.put("maxScore", 0.3); r.put("falseScore", 0.3);
        ArrayNode a = r.putArray("riskIntervals"); interval(a, 0.1, 0.1, true, true, "HIGH_RISK");
        VerifiedRuleDefinition def = load(r).require("binary", source("binary"));
        assertTrue(evaluator.evaluate(def, 0.03).isTriggered());
        assertFalse(evaluator.evaluate(def, Math.nextUp(0.03)).isTriggered());
        assertFalse(evaluator.evaluate(def, Math.nextDown(0.03)).isTriggered());
    }

    @Test void rejectsBadScoreMaximumAndAbsentThresholdVersion() {
        VerifiedRuleDefinition r = load(rule("binary")).require("binary", source("binary"));
        for (double bad : new double[]{-1, 3, Double.NaN, Double.POSITIVE_INFINITY})
            assertThrows(RuleContractException.class, () -> evaluator.evaluate(r, bad));
        r.setMaxScore(0.0); assertThrows(RuleContractException.class, () -> evaluator.evaluate(r, 0));
        r.setMaxScore(2.0); r.setThresholdVersion(null);
        assertThrows(RuleContractException.class, () -> evaluator.evaluate(r, 0));
    }

    private static void interval(ArrayNode a, double lower, double upper, boolean li, boolean ui, String level) {
        ObjectNode n = a.addObject(); n.put("lower", lower); n.put("upper", upper);
        n.put("lowerInclusive", li); n.put("upperInclusive", ui); n.put("riskLevel", level);
    }
}
