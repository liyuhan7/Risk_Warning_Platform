package com.riskwarning.processing.repository;

import com.riskwarning.common.po.analysis.AnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, String> {
    Optional<AnalysisResult> findByAnalysisRunIdAndBehaviorIdAndIndicatorId(
            String analysisRunId, String behaviorId, String indicatorId);
}
