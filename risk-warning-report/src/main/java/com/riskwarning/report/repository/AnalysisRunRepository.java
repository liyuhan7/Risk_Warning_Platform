package com.riskwarning.report.repository;

import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.enums.AnalysisRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import javax.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 报告侧按完整运行作用域读取分析运行。 */
@Repository
public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, String> {

    Optional<AnalysisRun> findByAnalysisRunIdAndAssessmentIdAndProjectId(
            String analysisRunId, Long assessmentId, Long projectId);

    /** 同运行的完成消息串行提交；重放读取首个事务提交的终态。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AnalysisRun r where r.analysisRunId = :runId and r.assessmentId = :assessmentId and r.projectId = :projectId")
    Optional<AnalysisRun> lockScope(@Param("runId") String runId, @Param("assessmentId") Long assessmentId, @Param("projectId") Long projectId);

    Optional<AnalysisRun> findFirstByAssessmentIdAndStatusOrderByFinishedAtDesc(
            Long assessmentId, AnalysisRunStatus status);
}
