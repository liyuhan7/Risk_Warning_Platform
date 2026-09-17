package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
public class AnalysisRunNoDecisionService {
    private final AnalysisRunRepository repository;
    public AnalysisRunNoDecisionService(AnalysisRunRepository repository) { this.repository = repository; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(AnalysisScope scope) {
        AnalysisRun run = repository.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new IllegalStateException("找不到待结束的分析运行"));
        if (run.getStatus() == AnalysisRunStatus.RUNNING) {
            run.completeWithoutDecision(LocalDateTime.now());
            repository.saveAndFlush(run);
        }
    }
}
