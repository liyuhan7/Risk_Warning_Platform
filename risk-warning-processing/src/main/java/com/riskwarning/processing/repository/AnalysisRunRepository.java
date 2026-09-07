package com.riskwarning.processing.repository;

import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 运行记录存储；状态查询不能代替数据库的在途运行唯一约束。 */
@Repository
public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, String> {

    Optional<AnalysisRun> findByAnalysisRunIdAndAssessmentIdAndProjectId(
            String analysisRunId, Long assessmentId, Long projectId);

    boolean existsByAssessmentIdAndStatus(Long assessmentId, AnalysisRunStatus status);
}
