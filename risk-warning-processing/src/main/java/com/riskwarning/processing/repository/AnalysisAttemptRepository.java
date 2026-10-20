package com.riskwarning.processing.repository;

import com.riskwarning.common.po.analysis.AnalysisAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

/** 模型调用序号在同一 Run/Behavior 中递增。 */
public interface AnalysisAttemptRepository extends JpaRepository<AnalysisAttempt, Long> {
    java.util.List<AnalysisAttempt> findByAnalysisRunIdAndBehaviorIdOrderByAttemptNoAsc(String runId, String behaviorId);
    Optional<AnalysisAttempt> findFirstByAnalysisRunIdAndBehaviorIdOrderByAttemptNoDesc(String runId, String behaviorId);
    java.util.List<AnalysisAttempt> findByAssessmentIdAndAnalysisRunId(Long assessmentId, String analysisRunId);
}
