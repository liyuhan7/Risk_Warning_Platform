package com.riskwarning.report.repository;

import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.enums.AnalysisRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 报告侧按完整运行作用域读取分析运行。 */
@Repository
public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, String> {

    Optional<AnalysisRun> findByAnalysisRunIdAndAssessmentIdAndProjectId(
            String analysisRunId, Long assessmentId, Long projectId);

    Optional<AnalysisRun> findFirstByAssessmentIdAndStatusOrderByFinishedAtDesc(
            Long assessmentId, AnalysisRunStatus status);
}
