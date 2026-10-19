package com.riskwarning.report.service.impl;

import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.indicator.IndicatorResultDetail;
import com.riskwarning.common.dto.analysis.AnalysisTrace;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.po.analysis.AnalysisResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementReportProjectionTest {
    @Test void dimensionEqualWeightsDoNotTurnIntoIndicatorCountWeights() {
        IndicatorResult a1 = IndicatorResult.builder().dimension("劳务合规风险").calculatedScore(100.0).build();
        IndicatorResult a2 = IndicatorResult.builder().dimension("劳务合规风险").calculatedScore(100.0).build();
        IndicatorResult a3 = IndicatorResult.builder().dimension("劳务合规风险").calculatedScore(100.0).build();
        IndicatorResult b = IndicatorResult.builder().dimension("产品合规风险").calculatedScore(0.0).build();
        assertEquals(50.0, RequirementReportProjection.overallScore(Arrays.asList(a1, a2, a3, b)));
    }

    @Test void oneDimensionUsesItsOwnIndicatorMean() {
        assertEquals(62.5, RequirementReportProjection.overallScore(Arrays.asList(
                IndicatorResult.builder().dimension("劳务合规风险").calculatedScore(100.0).build(),
                IndicatorResult.builder().dimension("劳务合规风险").calculatedScore(25.0).build())));
    }

    @Test void firstProfileRecordsAllSixConfirmedWeights() {
        ReportAggregationProfile profile = ReportAggregationProfile.firstVersion();
        assertEquals("report-aggregation-v1", profile.getProfileVersion()); assertEquals(6, profile.getDimensionWeights().size());
        assertTrue(profile.getDimensionWeights().values().stream().allMatch(weight -> weight.compareTo(BigDecimal.ONE) == 0));
    }

    @Test void reportAttachesOnlyViolatedRequirementsToTheirLawAndBehavior() {
        ObjectMapper mapper = new ObjectMapper();
        ScoringSourceBundle source = new ScoringSourceBundle();
        ScoringSourceBundle.Source violated = new ScoringSourceBundle.Source(); violated.setRegulationId("law1");
        violated.setRegulation(mapper.createObjectNode().put("name", "禁止安排特定作业"));
        violated.setRelation(mapper.createObjectNode().put("supportingText", "人员属于保护范围时不得安排该项作业"));
        ScoringSourceBundle.Source compliant = new ScoringSourceBundle.Source(); compliant.setRegulationId("law2");
        compliant.setRegulation(mapper.createObjectNode().put("name", "已履行的培训义务"));
        compliant.setRelation(mapper.createObjectNode().put("supportingText", "上岗前完成培训"));
        source.setRelations(Arrays.asList(violated, compliant));
        ScoringDecision.Atomic a1 = new ScoringDecision.Atomic(); a1.setBehaviorId("b"); a1.setRegulationId("law1");
        a1.setJudgment(ScoringDecision.Judgment.VIOLATED); a1.setEvidenceIds(Collections.singletonList("e"));
        ScoringDecision.Atomic a2 = new ScoringDecision.Atomic(); a2.setBehaviorId("b"); a2.setRegulationId("law2");
        a2.setJudgment(ScoringDecision.Judgment.SATISFIED); a2.setEvidenceIds(Collections.singletonList("e"));
        ScoringDecision decision = new ScoringDecision(); decision.setEvaluations(Arrays.asList(a1, a2));
        IndicatorResult indicator = IndicatorResult.builder().projectId(1L).indicatorEsId("i").indicatorName("保护人员作业安排")
                .calculatedScore(62.5).riskTriggered(true).calculationDetails(IndicatorResultDetail.builder().analysisMode("REASONING_RULES_V2")
                        .scoringDecision(decision).scoringSource(source).traces(Collections.singletonList(AnalysisTrace.builder().behaviorId("b")
                                .evidenceIds(Collections.singletonList("e")).analysisResult(AnalysisResult.builder().enterpriseFact("安排保护人员从事禁忌作业").build()).build())).build()).build();
        java.util.List<com.riskwarning.common.po.risk.RelatedBehavior> behaviors = FrozenReportEvidence.project(indicator).get(0).getRelatedBehaviors();
        assertEquals(1, behaviors.size()); assertEquals("安排保护人员从事禁忌作业", behaviors.get(0).getDescription());
        assertEquals(1, behaviors.get(0).getRelatedRegulations().size());
        assertEquals("law1", behaviors.get(0).getRelatedRegulations().get(0).getRegulationId());
    }
}
