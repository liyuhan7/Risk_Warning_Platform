package com.riskwarning.processing.controller;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.RequirementFinding;
import com.riskwarning.common.dto.scoring.ScoringDecision.Judgment;
import com.riskwarning.common.enums.AnalysisExecutionMode;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.processing.repository.*;
import com.riskwarning.processing.service.AnalysisRunSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 同一批评分从三个业务角度查询；低置信度只筛选复核事项，不改变原得分。 */
class RequirementEvaluationControllerTest {
    private MockMvc mvc;

    @BeforeEach void prepare() {
        AssessmentRepository assessments = mock(AssessmentRepository.class);
        when(assessments.findById(2L)).thenReturn(Optional.of(Assessment.builder().id(2L).projectId(1L).build()));
        AnalysisRunRepository runs = mock(AnalysisRunRepository.class);
        when(runs.findByAnalysisRunIdAndAssessmentIdAndProjectId("run", 2L, 1L))
                .thenReturn(Optional.of(AnalysisRun.start(new AnalysisScope(1L, 2L, "run"), LocalDateTime.now(), AnalysisExecutionMode.REASONING)));
        RequirementEvaluation low = row("low", "b1", "i1", "law1", Judgment.VIOLATED, false);
        RequirementEvaluation conflict = row("conflict", "b2", "i2", "law2", Judgment.SATISFIED, true);
        RequirementEvaluation high = row("high", "b3", "i3", "law3", Judgment.VIOLATED, false);
        RequirementEvaluationRepository evaluations = mock(RequirementEvaluationRepository.class);
        when(evaluations.findByProjectIdAndAssessmentIdAndAnalysisRunId(1L, 2L, "run")).thenReturn(Arrays.asList(low, conflict, high));
        AnalysisResultRepository analysis = mock(AnalysisResultRepository.class);
        when(analysis.findByAssessmentIdAndAnalysisRunId(2L, "run")).thenReturn(Arrays.asList(result(low, "0.6"), result(conflict, "0.95"), result(high, "0.95")));
        RequirementEvaluationController controller = new RequirementEvaluationController(assessments, new AnalysisRunSelector(runs), evaluations,
                mock(ScoringRunSnapshotRepository.class), mock(RuleEvaluationAuditRepository.class), analysis);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void behaviorIndicatorAndLawFiltersReturnTheirOwnRequirement() throws Exception {
        for (String filter : Arrays.asList("behaviorId", "indicatorId", "regulationId")) {
            String value = "behaviorId".equals(filter) ? "b1" : "indicatorId".equals(filter) ? "i1" : "law1";
            mvc.perform(get("/analysis/scoring/2").param("projectId", "1").param("analysisRunId", "run").param(filter, value))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.evaluations.length()").value(1))
                    .andExpect(jsonPath("$.data.evaluations[0].id").value("low"));
        }
    }

    @Test void reviewListContainsLowConfidenceViolationsAndSemanticConflictsWithoutChangingScores() throws Exception {
        mvc.perform(get("/analysis/scoring/2/review").param("projectId", "1").param("analysisRunId", "run").param("confidenceBelow", "0.8"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.reviewItems.length()").value(2))
                .andExpect(jsonPath("$.data.reviewItems[0].confidence").value(0.6))
                .andExpect(jsonPath("$.data.reviewItems[0].evaluation.earned").value(0))
                .andExpect(jsonPath("$.data.evaluations.length()").value(3));
    }

    private static RequirementEvaluation row(String id, String behavior, String indicator, String law, Judgment judgment, boolean conflict) {
        RequirementEvaluation row = new RequirementEvaluation(); row.setId(id); row.setBehaviorId(behavior); row.setIndicatorId(indicator);
        row.setRegulationId(law); row.setRequirementId("r"); row.setJudgment(judgment); row.setEarned(BigDecimal.ZERO);
        row.setConflict(conflict); row.setEvidenceIds(Collections.singletonList("e")); return row;
    }
    private static AnalysisResult result(RequirementEvaluation row, String confidence) {
        RequirementFinding finding = new RequirementFinding(); finding.setRequirementId("r"); finding.setConfidence(new BigDecimal(confidence));
        return AnalysisResult.builder().behaviorId(row.getBehaviorId()).indicatorId(row.getIndicatorId()).enterpriseFact(row.getBehaviorId())
                .requirementFindings(Collections.singletonList(finding)).build();
    }
}
