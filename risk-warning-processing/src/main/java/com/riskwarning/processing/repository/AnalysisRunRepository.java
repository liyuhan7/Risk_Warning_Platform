package com.riskwarning.processing.repository;

import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import javax.persistence.LockModeType;

import java.util.Collection;
import java.util.Optional;

/** 运行记录存储；状态查询不能代替数据库的在途运行唯一约束。 */
@Repository
public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, String> {

    /** 只在审计写入短事务内锁定 Run，串行分配调用序号，不跨模型请求持锁。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AnalysisRun r where r.analysisRunId = :runId and r.assessmentId = :assessmentId and r.projectId = :projectId")
    Optional<AnalysisRun> lockScope(@Param("runId") String runId, @Param("assessmentId") Long assessmentId,
                                    @Param("projectId") Long projectId);

    Optional<AnalysisRun> findByAnalysisRunIdAndAssessmentIdAndProjectId(
            String analysisRunId, Long assessmentId, Long projectId);

    boolean existsByAssessmentIdAndStatus(Long assessmentId, AnalysisRunStatus status);

    /** 最近一次按指定状态结束的运行；用于解析"当前有效结果"的读取依据。 */
    Optional<AnalysisRun> findFirstByAssessmentIdAndStatusOrderByFinishedAtDesc(
            Long assessmentId, AnalysisRunStatus status);
    Optional<AnalysisRun> findFirstByAssessmentIdAndProjectIdOrderByStartedAtDescAnalysisRunIdDesc(Long assessmentId, Long projectId);

    Optional<AnalysisRun> findFirstByAssessmentIdAndProjectIdAndStatusInOrderByStartedAtDescAnalysisRunIdDesc(
            Long assessmentId, Long projectId, Collection<AnalysisRunStatus> statuses);
}
