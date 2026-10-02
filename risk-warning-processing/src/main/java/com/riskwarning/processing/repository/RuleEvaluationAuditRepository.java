package com.riskwarning.processing.repository;

import com.riskwarning.common.po.analysis.RuleEvaluationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

/** 运行内的指标评估审计，包括没有形成分数的未决项。 */
public interface RuleEvaluationAuditRepository extends JpaRepository<RuleEvaluationAudit, Long> {
    Optional<RuleEvaluationAudit> findByAnalysisRunIdAndIndicatorId(String analysisRunId, String indicatorId);
    List<RuleEvaluationAudit> findByAssessmentIdAndAnalysisRunId(Long assessmentId, String analysisRunId);
}
