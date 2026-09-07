package com.riskwarning.org.repository;

import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, String> {

    boolean existsByAssessmentIdAndStatus(Long assessmentId, AnalysisRunStatus status);

    Optional<AnalysisRun> findByAnalysisRunIdAndAssessmentIdAndProjectId(
            String analysisRunId, Long assessmentId, Long projectId);
}
