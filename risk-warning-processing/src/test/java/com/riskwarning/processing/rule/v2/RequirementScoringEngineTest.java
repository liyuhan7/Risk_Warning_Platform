package com.riskwarning.processing.rule.v2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.RequirementFinding.ConditionValue;
import com.riskwarning.common.dto.scoring.ScoringDecision.Judgment;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.riskwarning.processing.rule.v2.ScoringTestData.*;

class RequirementScoringEngineTest {
    private RequirementFinding fact(String behavior, String requirement, Boolean condition, String number, boolean violation) {
        RequirementFinding finding = new RequirementFinding();
        finding.setIndicatorId("i"); finding.setBehaviorId(behavior); finding.setRequirementId(requirement);
        finding.setApplicability(Applicability.APPLICABLE);
        finding.setComplianceStatus(violation ? ComplianceStatus.NON_COMPLIANT : ComplianceStatus.COMPLIANT);
        finding.setEvidenceIds(Collections.singletonList("e-" + behavior)); finding.setReasoning("根据本次材料记录判断");
        finding.setConfidence(new BigDecimal("0.95"));
        ConditionValue value = new ConditionValue(); value.setConditionMet(condition);
        if (number != null) { value.setObservedValue(new BigDecimal(number)); value.setObservedUnit("DAYS"); }
        value.setEvidenceIds(finding.getEvidenceIds());
        finding.setConditionValues(Collections.singletonMap("c-" + requirement, value));
        return finding;
    }

    private ScoringDecision score(ObjectNode[] requirements, RequirementFinding... facts) {
        ScoringSourceBundle bundle = bundle(Arrays.stream(requirements).map(r -> r.path("requirementId").asText()).toArray(String[]::new));
        ScoringRuleRegistry registry = ScoringRuleRegistry.fromJson(registry(bundle, requirements).toString(), true);
        Map<String, Set<String>> evidence = new TreeMap<>();
        for (RequirementFinding fact : facts) evidence.put(fact.getBehaviorId(), Collections.singleton("e-" + fact.getBehaviorId()));
        return new RequirementScoringEngine(registry, new UniversalConditionEvaluator()).evaluate(
                new AnalysisScope(1L, 2L, "run"), "i", bundle,
                evidence, Arrays.asList(facts), Collections.emptyMap());
    }

    private static void scoreEquals(String expected, ScoringDecision actual) {
        assertNotNull(actual.getCalculatedScore());
        assertEquals(0, new BigDecimal(expected).compareTo(actual.getCalculatedScore()));
    }

    @Test void prohibitionUsesCompliantConditionWithoutDoubleInversion() {
        ObjectNode rule = requirement("r", "FORBIDDEN");
        scoreEquals("100", score(new ObjectNode[]{rule}, fact("b", "r", false, null, false)));
        ScoringDecision violated = score(new ObjectNode[]{rule}, fact("b", "r", true, null, true));
        scoreEquals("0", violated);
        assertEquals(Judgment.VIOLATED, violated.getEvaluations().get(0).getJudgment());
    }

    @Test void registeredQuantitativeBandsProduceFullPartialAndZeroScores() {
        ObjectNode rule = numeric("r");
        scoreEquals("100", score(new ObjectNode[]{rule}, fact("b", "r", null, "6", false)));
        scoreEquals("100", score(new ObjectNode[]{rule}, fact("b", "r", null, "5", false)));
        ScoringDecision partial = score(new ObjectNode[]{rule}, fact("b", "r", null, "4", true));
        scoreEquals("60", partial);
        assertEquals(GapType.QUANTITATIVE_SHORTFALL, partial.getEvaluations().get(0).getGapType());
        assertEquals(0, BigDecimal.ONE.compareTo(partial.getEvaluations().get(0).getGapValue()));
        assertEquals("DAYS", partial.getEvaluations().get(0).getGapUnit());
        scoreEquals("0", score(new ObjectNode[]{rule}, fact("b", "r", null, "3", true)));
    }

    @Test void pendingRequirementBlocksFinalScoreButKeepsCompletedAtomicScore() {
        RequirementFinding missing = fact("b", "r2", null, null, false);
        missing.setComplianceStatus(ComplianceStatus.INSUFFICIENT_EVIDENCE);
        ScoringDecision decision = score(new ObjectNode[]{requirement("r1", "REQUIRED"), requirement("r2", "REQUIRED")},
                fact("b", "r1", true, null, false), missing);
        assertEquals("NEEDS_REVIEW", decision.getStatus());
        assertNull(decision.getCalculatedScore()); assertNull(decision.getRiskLevel());
        assertEquals(0, new BigDecimal("50").compareTo(decision.getEvaluations().get(0).getEarned()));
        assertNull(decision.getEvaluations().get(1).getEarned());
    }

    @Test void unknownApplicabilityHoldsFinalScoreWhileProvenNonApplicabilityRemovesWeight() {
        RequirementFinding second = fact("b", "r2", true, null, false);
        second.setApplicability(Applicability.UNKNOWN);
        ObjectNode[] rules = {requirement("r1", "REQUIRED"), requirement("r2", "REQUIRED")};
        assertNull(score(rules, fact("b", "r1", true, null, false), second).getCalculatedScore());
        second.setApplicability(Applicability.NOT_APPLICABLE);
        scoreEquals("100", score(rules, fact("b", "r1", true, null, false), second));
    }

    @Test void severeProhibitionKeeps625ScoreAndRaisesRiskToRegisteredFloor() {
        ObjectNode r1 = requirement("r1", "FORBIDDEN").put("weight", 1.5).put("minimumRiskLevel", "HIGH_RISK");
        ObjectNode r2 = requirement("r2", "FORBIDDEN").put("weight", 1.5);
        ObjectNode r3 = requirement("r3", "REQUIRED").put("weight", 1);
        ScoringDecision decision = score(new ObjectNode[]{r1, r2, r3}, fact("b", "r1", true, null, true),
                fact("b", "r2", false, null, false), fact("b", "r3", true, null, false));
        scoreEquals("62.5", decision);
        assertEquals(RiskLevelEnum.HIGH_RISK, decision.getRiskLevel());
        assertTrue(decision.getRiskTriggered());
    }

    @Test void optionalFailureCanReduceScoreButCannotCreateViolationRisk() {
        ScoringDecision decision = score(new ObjectNode[]{requirement("r", "OPTIONAL")}, fact("b", "r", false, null, false));
        scoreEquals("0", decision);
        assertEquals(Judgment.UNSATISFIED_OPTIONAL, decision.getEvaluations().get(0).getJudgment());
        assertNull(decision.getEvaluations().get(0).getGapType());
        assertEquals(RiskLevelEnum.NO_RISK, decision.getRiskLevel()); assertFalse(decision.getRiskTriggered());
    }

    @Test void oneForbiddenOccurrenceCannotBeDilutedByNineCompliantBehaviors() {
        ObjectNode rule = requirement("r", "FORBIDDEN").put("crossBehaviorAggregation", "ANY_VIOLATION");
        List<RequirementFinding> facts = new ArrayList<>();
        for (int i = 0; i < 10; i++) facts.add(fact("b" + i, "r", i == 0, null, i == 0));
        ScoringDecision decision = score(new ObjectNode[]{rule}, facts.toArray(new RequirementFinding[0]));
        scoreEquals("0", decision); assertEquals(10, decision.getEvaluations().size());
        assertEquals(Judgment.VIOLATED, decision.getRequirements().get(0).getJudgment());
    }

    @Test void oneTimeRequiredActionAndContinuousRequiredActionHaveDifferentBusinessSemantics() {
        RequirementFinding done = fact("b1", "r", true, null, false);
        RequirementFinding absent = fact("b2", "r", false, null, true);
        ObjectNode rule = requirement("r", "REQUIRED").put("crossBehaviorAggregation", "EXISTS");
        scoreEquals("100", score(new ObjectNode[]{rule}, done, absent));
        rule.put("crossBehaviorAggregation", "ALL");
        scoreEquals("0", score(new ObjectNode[]{rule}, done, absent));
    }

    @Test void compoundConditionsAreEvaluatedPerLeafAndExceptionsExcludeOnlyMatchingBehavior() {
        ObjectNode rule = requirement("r", "REQUIRED");
        ObjectNode leaf = ((ObjectNode)rule.get("condition")).deepCopy();
        ObjectNode second = leaf.deepCopy().put("conditionId", "second");
        ObjectNode tree = M.createObjectNode(); tree.putArray("allOf").add(leaf).add(second); rule.set("condition", tree);
        RequirementFinding finding = fact("b", "r", true, null, false);
        Map<String, ConditionValue> values = new HashMap<>(finding.getConditionValues());
        ConditionValue secondValue = new ConditionValue(); secondValue.setConditionMet(false); secondValue.setEvidenceIds(finding.getEvidenceIds());
        values.put("second", secondValue); finding.setConditionValues(values);
        scoreEquals("0", score(new ObjectNode[]{rule}, finding));
        rule.set("exceptWhen", leaf.deepCopy().put("conditionId", "exception"));
        values.put("exception", finding.getConditionValues().get("c-r"));
        ScoringDecision excepted = score(new ObjectNode[]{rule}, finding);
        assertEquals("NOT_APPLICABLE", excepted.getStatus()); assertNull(excepted.getCalculatedScore());
    }
}
