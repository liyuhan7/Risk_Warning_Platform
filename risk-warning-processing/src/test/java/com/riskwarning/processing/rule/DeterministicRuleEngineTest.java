package com.riskwarning.processing.rule;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.AnalysisResult;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.riskwarning.processing.rule.RuleTestData.*;

class DeterministicRuleEngineTest {
    @Test void inverseBinaryAndVersionsProduceCompleteRiskDecision() {
        AnalysisResult a = result("a"); a.setConditionMet(true); a.setComplianceStatus(ComplianceStatus.NON_COMPLIANT);
        DeterministicRuleEngine.Evaluation out = evaluate(rule("binary"), Collections.singletonList(a), null);
        assertEquals(RuleEvaluationStatus.DECIDED, out.getDecision().getStatus());
        assertEquals(0, out.getDecision().getCalculatedScore()); assertTrue(out.getDecision().getRiskTriggered());
        assertEquals("rule-v1", out.getDecision().getRuleVersion()); assertEquals("threshold-v1", out.getDecision().getThresholdVersion());
        assertEquals(out.getCalculation().getInputHash(), out.getDecision().getInputHash());
        RuleEvaluationContracts.validate(out.getDecision());
    }
    @Test void forwardBinaryIsNotAssumedInverse() {
        ObjectNode r = rule("binary"); r.put("trueScore", 2); r.put("falseScore", 0);
        AnalysisResult a = result("a"); a.setConditionMet(true);
        assertFalse(evaluate(r, Collections.singletonList(a), null).getDecision().getRiskTriggered());
    }
    @Test void semanticConflictRetainsRuleDecisionAndNamesFact() {
        AnalysisResult a = result("a"); a.setConditionMet(true);
        DeterministicRuleEngine.Evaluation out = evaluate(rule("binary"), Collections.singletonList(a), null);
        assertEquals(RuleEvaluationStatus.DECIDED_WITH_CONFLICT, out.getDecision().getStatus());
        assertTrue(out.getDecision().getRiskTriggered()); assertEquals(0, out.getDecision().getCalculatedScore());
        assertEquals(Collections.singletonList("result-a"), out.getConflictingResultIds());
        a.setConditionMet(false); a.setComplianceStatus(ComplianceStatus.NON_COMPLIANT);
        assertEquals(RuleEvaluationStatus.DECIDED_WITH_CONFLICT, evaluate(rule("binary"), Collections.singletonList(a), null).getDecision().getStatus());
    }
    @Test void mixedFactsResolveByRegisteredOperatorWithoutArtificialConflict() {
        AnalysisResult a = result("a"), b = result("b"); b.setConditionMet(true); b.setComplianceStatus(ComplianceStatus.NON_COMPLIANT);
        ObjectNode r = rule("binary");
        assertEquals(RuleEvaluationStatus.DECIDED, evaluate(r, Arrays.asList(a, b), null).getDecision().getStatus());
        r.put("aggregationOperator", "ALL");
        assertFalse(evaluate(r, Arrays.asList(a, b), null).getDecision().getRiskTriggered());
        assertEquals(RuleEvaluationStatus.DECIDED, evaluate(r, Arrays.asList(a, b), null).getDecision().getStatus());
    }
    @Test void latestIgnoresOlderSemanticConflictButConflictingTiesRemainUndecided() {
        ObjectNode r = rule("binary"); r.put("aggregationOperator", "LATEST");
        AnalysisResult a = result("a"), b = result("b"); a.setConditionMet(true);
        Map<String, LocalDateTime> times = new HashMap<>(); times.put("a", LocalDateTime.of(2026, 9, 29, 0, 0)); times.put("b", times.get("a").plusDays(1));
        assertEquals(RuleEvaluationStatus.DECIDED, evaluate(r, Arrays.asList(a,b), times).getDecision().getStatus());
        times.put("a", times.get("b"));
        DeterministicRuleEngine.Evaluation out = evaluate(r, Arrays.asList(a,b), times);
        assertEquals(RuleEvaluationStatus.NEEDS_REVIEW, out.getDecision().getStatus());
        assertNull(out.getCalculation()); assertNull(out.getDecision().getCalculatedScore()); assertNull(out.getDecision().getRiskLevel());
        RuleEvaluationContracts.validate(out.getDecision());
    }
    @Test void rangeRulesUseSameThresholdsAndDomainFailureHasNoFakeScore() {
        AnalysisResult a = result("a"); a.setIndicatorId("range"); a.setConditionMet(null); a.setObservedUnit("day"); a.setObservedValue(2.0);
        a.setComplianceStatus(ComplianceStatus.NON_COMPLIANT);
        assertTrue(evaluate(range(), Collections.singletonList(a), null).getDecision().getRiskTriggered());
        a.setObservedValue(0.0); a.setComplianceStatus(ComplianceStatus.COMPLIANT);
        assertFalse(evaluate(range(), Collections.singletonList(a), null).getDecision().getRiskTriggered());
        a.setObservedValue(4.0);
        DeterministicRuleEngine.Evaluation out = evaluate(range(), Collections.singletonList(a), null);
        assertEquals(RuleEvaluationStatus.INVALID_INPUT, out.getDecision().getStatus());
        assertNull(out.getDecision().getMaxPossibleScore()); assertNull(out.getDecision().getRiskTriggered());
    }
    @Test void halfBoundaryAndEmptyIntervalsHaveNoFallback() {
        ObjectNode r = rule("binary"); r.put("falseScore", 1);
        assertFalse(evaluate(r, Collections.singletonList(result("a")), null).getDecision().getRiskTriggered());
        r.putArray("riskIntervals"); AnalysisResult a = result("a"); a.setConditionMet(true);
        assertFalse(evaluate(r, Collections.singletonList(a), null).getDecision().getRiskTriggered());
    }
    @Test void sourceAndInputDriftCannotBeConvertedToSuccessfulDecision() {
        RuleInputAssembler assembler = new RuleInputAssembler(load(rule("binary")));
        RuleInputAssembler.PreparedInputs p = assembler.prepare(SCOPE, "binary", Collections.singletonList(result("a")), source("binary"), null);
        ObjectNode changed = source("binary"); changed.put("maxScore", 3);
        assertThrows(RuleContractException.class, () -> engine(assembler).evaluate(SCOPE, p, changed));
        p.getInputs().get(0).setConditionMet(true);
        assertThrows(RuleContractException.class, () -> engine(assembler).evaluate(SCOPE, p, source("binary")));
    }
    private static DeterministicRuleEngine.Evaluation evaluate(ObjectNode r, List<AnalysisResult> rows, Map<String, LocalDateTime> times) {
        String id = r.path("indicatorId").asText(); RuleInputAssembler assembler = new RuleInputAssembler(load(r));
        return engine(assembler).evaluate(SCOPE, assembler.prepare(SCOPE, id, rows, source(id), times), source(id));
    }
    private static DeterministicRuleEngine engine(RuleInputAssembler assembler) {
        return new DeterministicRuleEngine(assembler, new BinaryRuleCalculator(assembler), new RangeRuleCalculator(assembler), new RiskThresholdEvaluator());
    }
}
