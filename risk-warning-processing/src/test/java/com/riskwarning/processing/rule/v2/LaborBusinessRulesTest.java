package com.riskwarning.processing.rule.v2;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.ScoringRuleSet.*;
import com.riskwarning.common.dto.scoring.RequirementFinding.ConditionValue;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 当前劳务业务中的数值边界、法定例外与替代履行回归。 */
class LaborBusinessRulesTest {
    private final ScoringRuleRegistry registry = new ScoringRuleRegistry("classpath:rules/business-catalog-test-v2.json", true);
    private final ScoringDataset dataset;
    private static final String NONCOMPETE = "482dec23-9dd6-4273-a431-9c0c5fa75a95";
    private static final String OVERTIME = "4ad95dd2-bcfb-4c74-bbe0-2832045c9f84";
    private static final String OVERTIME_REQ = "req-22ea9d48093c36751d80b146";

    LaborBusinessRulesTest() throws Exception {
        dataset = new ScoringDataset((Files.exists(Paths.get("documents/data")) ? Paths.get("documents/data") : Paths.get("../documents/data")).toString());
    }

    @Test void postEmploymentNoncompeteTwentyFourMonthsPassesAndTwentyFiveMonthsFails() {
        String condition = "cond-a9f8acf51c525ef9d37a3554";
        assertEquals(100, score(NONCOMPETE, Collections.singletonMap(condition, 24), Collections.emptySet()).getCalculatedScore().doubleValue());
        ScoringDecision exceeded = score(NONCOMPETE, Collections.singletonMap(condition, 25), Collections.singleton("req-a9f8acf51c525ef9d37a3554"));
        assertEquals(100.0 * 0.2 / 1.7, exceeded.getCalculatedScore().doubleValue(), 0.000001);
        assertEquals(RiskLevelEnum.HIGH_RISK, exceeded.getRiskLevel());
        assertEquals(GapType.QUANTITATIVE_EXCESS, exceeded.getEvaluations().get(0).getGapType());
        assertEquals(1, exceeded.getEvaluations().get(0).getGapValue().intValue());
        assertEquals("MONTHS", exceeded.getEvaluations().get(0).getGapUnit());
    }

    @Test void overtimeUsesMonthlyLimitSpecialDailyPathAndLegalException() {
        Map<String, Object> values = ordinaryHours();
        assertEquals(100, score(OVERTIME, values, Collections.emptySet()).getCalculatedScore().doubleValue());
        values.put(OVERTIME_REQ + "-monthly-hours", 37);
        assertEquals(40, score(OVERTIME, values, Collections.singleton(OVERTIME_REQ)).getCalculatedScore().doubleValue());
        values.put(OVERTIME_REQ + "-emergency", true);
        assertEquals(100, score(OVERTIME, values, Collections.emptySet()).getCalculatedScore().doubleValue());
        values = ordinaryHours(); values.put(OVERTIME_REQ + "-daily-hours", 2.5); values.put(OVERTIME_REQ + "-ordinary-daily-hours", 2.5);
        assertEquals(40, score(OVERTIME, values, Collections.singleton(OVERTIME_REQ)).getCalculatedScore().doubleValue());
        values.put(OVERTIME_REQ + "-special-health", true);
        assertEquals(100, score(OVERTIME, values, Collections.emptySet()).getCalculatedScore().doubleValue());
    }

    @Test void dismissalNoticeAndExtraWageAreAlternativesRatherThanBothRequired() {
        String indicator = "45ca6126-1fcf-4332-8c40-2c1124fd6de9", requirement = "req-91665e5a51cf31c60b2afb14";
        Map<String, Object> values = new HashMap<>(); values.put(requirement + "-notice-days", 0); values.put(requirement + "-alternative-pay", true);
        ScoringDecision decision = score(indicator, values, Collections.emptySet());
        ScoringDecision.Atomic finding = decision.getEvaluations().stream().filter(a -> requirement.equals(a.getRequirementId())).findFirst().get();
        assertEquals(ScoringDecision.Judgment.SATISFIED, finding.getJudgment());
        assertEquals(100, decision.getCalculatedScore().doubleValue());
    }

    private Map<String, Object> ordinaryHours() {
        Map<String, Object> values = new HashMap<>(); values.put(OVERTIME_REQ + "-daily-hours", 1); values.put(OVERTIME_REQ + "-ordinary-daily-hours", 1);
        values.put(OVERTIME_REQ + "-special-health", false); values.put("req-52ee6692c6b903e58f6f53df-other-approved", false);
        values.put("req-52ee6692c6b903e58f6f53df-other-compliant", false); return values;
    }

    private ScoringDecision score(String indicator, Map<String, ?> overrides, Set<String> violated) {
        List<RequirementFinding> findings = new ArrayList<>();
        for (Requirement requirement : registry.require(indicator, bundle(indicator)).getRequirements()) {
            RequirementFinding finding = new RequirementFinding(); finding.setIndicatorId(indicator); finding.setRequirementId(requirement.getRequirementId()); finding.setBehaviorId("b");
            finding.setApplicability(Applicability.APPLICABLE); finding.setComplianceStatus(violated.contains(requirement.getRequirementId()) ? ComplianceStatus.NON_COMPLIANT : ComplianceStatus.COMPLIANT);
            finding.setReasoning("已审核规则的业务回归样例"); finding.setConfidence(BigDecimal.ONE); finding.setEvidenceIds(Collections.singletonList("e"));
            Map<String, ConditionValue> values = new HashMap<>(); facts(requirement.getCondition(), requirement.getValueType(), overrides, values, false);
            if (requirement.getExceptWhen() != null) facts(requirement.getExceptWhen(), requirement.getValueType(), overrides, values, true);
            finding.setConditionValues(values); findings.add(finding);
        }
        return new RequirementScoringEngine(registry, new UniversalConditionEvaluator()).evaluate(new AnalysisScope(1L, 2L, "labor-business-test"), indicator,
                bundle(indicator), Collections.singletonMap("b", Collections.singleton("e")), findings, Collections.emptyMap());
    }

    private void facts(Condition condition, ValueType inherited, Map<String, ?> overrides, Map<String, ConditionValue> values, boolean exception) {
        if (condition.getAllOf() != null) { condition.getAllOf().forEach(c -> facts(c, inherited, overrides, values, exception)); return; }
        if (condition.getAnyOf() != null) { condition.getAnyOf().forEach(c -> facts(c, inherited, overrides, values, exception)); return; }
        if (condition.getNotOf() != null) { facts(condition.getNotOf(), inherited, overrides, values, exception); return; }
        Object override = overrides.get(condition.getConditionId());
        ConditionValue value = new ConditionValue(); value.setEvidenceIds(Collections.singletonList("e"));
        if ((condition.getValueType() == null ? inherited : condition.getValueType()) == ValueType.NUMBER) {
            value.setObservedValue(override == null ? condition.getOperand() : new BigDecimal(override.toString())); value.setObservedUnit(condition.getUnit());
        } else value.setConditionMet(override == null ? !exception : (Boolean)override);
        values.put(condition.getConditionId(), value);
    }

    private ScoringSourceBundle bundle(String indicator) { return dataset.bundle((String)dataset.manifest().get("datasetVersion"), indicator); }
}
