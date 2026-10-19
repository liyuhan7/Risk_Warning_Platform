package com.riskwarning.processing.repository;

import com.riskwarning.common.po.analysis.RequirementEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RequirementEvaluationRepository extends JpaRepository<RequirementEvaluation, String> {
    List<RequirementEvaluation> findByProjectIdAndAssessmentIdAndAnalysisRunId(Long projectId, Long assessmentId, String analysisRunId);
}
