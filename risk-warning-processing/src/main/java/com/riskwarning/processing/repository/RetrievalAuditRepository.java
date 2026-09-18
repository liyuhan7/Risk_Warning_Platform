package com.riskwarning.processing.repository;

import com.riskwarning.common.po.analysis.RetrievalAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RetrievalAuditRepository extends JpaRepository<RetrievalAudit, Long> {
    Optional<RetrievalAudit> findByAnalysisRunIdAndBehaviorId(String analysisRunId, String behaviorId);
    java.util.List<RetrievalAudit> findByAssessmentIdAndAnalysisRunId(Long assessmentId, String analysisRunId);
}
