package com.riskwarning.report.service.impl;

import com.riskwarning.common.enums.risk.RiskLevelEnum;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.indicator.IndicatorResultDetail;
import com.riskwarning.common.po.risk.Risk;
import com.riskwarning.common.po.risk.RelatedIndicator;
import com.riskwarning.common.dto.analysis.AnalysisTrace;
import com.riskwarning.common.dto.analysis.RuleEvaluation;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssessmentServiceRunScopeTest {

    @Test
    void buildsStableRiskBoundToItsAnalysisRun() {
        IndicatorResult indicator = IndicatorResult.builder()
                .indicatorEsId("indicator-a").indicatorName("指标 A").dimension("管理")
                .calculationDetails(IndicatorResultDetail.builder()
                        .relatedIndicators(Collections.singletonList(RelatedIndicator.builder().build()))
                        .build())
                .build();

        Risk first = AssessmentServiceImpl.buildRisk(10L, 20L, "run-a", indicator, RiskLevelEnum.LOW_RISK);
        Risk retry = AssessmentServiceImpl.buildRisk(10L, 20L, "run-a", indicator, RiskLevelEnum.LOW_RISK);
        Risk anotherRun = AssessmentServiceImpl.buildRisk(10L, 20L, "run-b", indicator, RiskLevelEnum.LOW_RISK);

        assertEquals("run-a", first.getAnalysisRunId());
        assertEquals(first.getId(), retry.getId());
        assertEquals(32, first.getId().length());
        assertNotEquals(first.getId(), anotherRun.getId());
    }

    @Test
    void readsFixtureRiskLevelFromProcessingTrace() {
        IndicatorResult indicator = IndicatorResult.builder()
                .calculationDetails(IndicatorResultDetail.builder()
                        .traces(Collections.singletonList(AnalysisTrace.builder()
                                .ruleEvaluation(RuleEvaluation.builder().riskTriggered(true)
                                        .riskLevel(RiskLevelEnum.MEDIUM_RISK).build())
                                .build()))
                        .build())
                .build();

        assertEquals(RiskLevelEnum.MEDIUM_RISK, AssessmentServiceImpl.fixtureRiskLevel(indicator));
        indicator.getCalculationDetails().setTraces(Collections.emptyList());
        assertThrows(IllegalStateException.class, () -> AssessmentServiceImpl.fixtureRiskLevel(indicator));
    }
}
