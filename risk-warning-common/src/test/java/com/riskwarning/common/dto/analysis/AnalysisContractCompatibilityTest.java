package com.riskwarning.common.dto.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.indicator.IndicatorResultDetail;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

/** 历史结果与 Reasoning 审计通过相同 JSON 消费边界回读。 */
class AnalysisContractCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void historicalResultKeepsAbsentBridgeFieldsNull() throws Exception {
        AnalysisResult old = mapper.readValue("{\"schemaVersion\":\"1.0\",\"confidence\":0.8}", AnalysisResult.class);
        assertEquals("1.0", old.getSchemaVersion());
        assertNull(old.getConditionMet());
        assertNull(old.getObservedValue());
        assertNull(old.getObservedUnit());
        assertNull(mapper.readValue("{\"schemaVersion\":\"1.0\"}", IndicatorResultDetail.class).getRuleCalculation());
        assertEquals("1.0", IndicatorResultDetail.builder().build().getSchemaVersion());
    }

    @Test
    void falseBinaryAndZeroRangeSurviveRoundTripWithoutGapSubstitution() throws Exception {
        AnalysisResult binary = AnalysisResult.builder().schemaVersion("1.1").conditionMet(false).build();
        assertEquals(Boolean.FALSE, copy(binary, AnalysisResult.class).getConditionMet());
        AnalysisResult range = AnalysisResult.builder().schemaVersion("1.1")
                .observedValue(0.0).observedUnit("day").gapValue(2.0).gapUnit("day").build();
        AnalysisResult restored = copy(range, AnalysisResult.class);
        assertEquals(0.0, restored.getObservedValue());
        assertEquals(2.0, restored.getGapValue());
        assertNull(restored.getConditionMet());
    }

    @Test
    void calculationReplayKeepsInputAggregationAndFinalDecision() throws Exception {
        RuleCalculationDetail detail = RuleCalculationDetail.builder().inputHash("hash")
                .ruleVersion("rule-v1").thresholdVersion("threshold-v1").aggregationOperator("SINGLE")
                .ruleInputs(Collections.singletonList(RuleInput.builder().conditionMet(false).build()))
                .aggregationDetails(Collections.singletonMap("count", 1)).conflict(false)
                .finalDecision(RuleEvaluationResult.builder().calculatedScore(2.0).maxPossibleScore(2.0)
                        .riskTriggered(false).riskLevel(RiskLevelEnum.NO_RISK).build()).build();
        IndicatorResultDetail result = IndicatorResultDetail.builder().schemaVersion("1.1")
                .analysisMode("REASONING").ruleCalculation(detail).build();
        IndicatorResultDetail restored = copy(result, IndicatorResultDetail.class);
        assertEquals(false, restored.getRuleCalculation().getRuleInputs().get(0).getConditionMet());
        assertEquals(RiskLevelEnum.NO_RISK, restored.getRuleCalculation().getFinalDecision().getRiskLevel());
        assertEquals("threshold-v1", restored.getRuleCalculation().getThresholdVersion());
    }

    @Test
    void auditKeepsProviderFailureAndUndecidedReasons() throws Exception {
        AnalysisAttempt attempt = AnalysisAttempt.builder().analysisRunId("run").attemptNo(1)
                .status(AnalysisAttemptStatus.PROVIDER_FAILED).errorCode("TIMEOUT").build();
        assertNull(copy(attempt, AnalysisAttempt.class).getRawResponse());
        assertEquals("TIMEOUT", copy(attempt, AnalysisAttempt.class).getErrorCode());
        RuleEvaluationAudit audit = RuleEvaluationAudit.builder().analysisRunId("run").indicatorId("indicator")
                .status(RuleEvaluationStatus.UNSUPPORTED_RULE).conflict(false).errorCode("UNREGISTERED").build();
        RuleEvaluationAudit restored = copy(audit, RuleEvaluationAudit.class);
        assertNull(restored.getRuleVersion());
        assertEquals(RuleEvaluationStatus.UNSUPPORTED_RULE, restored.getStatus());
        assertEquals("UNREGISTERED", restored.getErrorCode());
    }

    private <T> T copy(T value, Class<T> type) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(value), type);
    }
}
