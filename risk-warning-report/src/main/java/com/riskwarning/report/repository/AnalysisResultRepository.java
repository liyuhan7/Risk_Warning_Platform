package com.riskwarning.report.repository;

import com.riskwarning.common.po.analysis.AnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, String> {
    @Transactional
    long deleteByAssessmentIdAndAnalysisRunIdNot(Long assessmentId, String analysisRunId);
}
