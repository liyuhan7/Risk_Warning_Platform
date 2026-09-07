package com.riskwarning.report.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.report.repository.AnalysisRunRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 校验运行归属后汇总报告，并只将对应运行标记为成功。 */
@Service
public class AnalysisRunCompletionService {

    private final AnalysisRunRepository analysisRunRepository;
    private final AssessmentService assessmentService;
    private final AnalysisRunFailureService analysisRunFailureService;
    private final AnalysisRunResultCleanupService cleanupService;

    public AnalysisRunCompletionService(AnalysisRunRepository analysisRunRepository,
                                        AssessmentService assessmentService,
                                        AnalysisRunFailureService analysisRunFailureService,
                                        AnalysisRunResultCleanupService cleanupService) {
        this.analysisRunRepository = analysisRunRepository;
        this.assessmentService = assessmentService;
        this.analysisRunFailureService = analysisRunFailureService;
        this.cleanupService = cleanupService;
    }

    public void aggregateAndComplete(Long userId, AnalysisScope scope) {
        if (scope == null || userId == null || userId <= 0) {
            throw new BusinessException("报告汇总所需的用户和运行作用域不能为空");
        }
        AnalysisRun run = analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                        scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new BusinessException("找不到匹配的分析运行"));
        if (run.getStatus() == AnalysisRunStatus.SUCCEEDED) {
            return;
        }
        if (run.getStatus() != AnalysisRunStatus.RUNNING) {
            throw new BusinessException("已失败的分析运行不能生成报告");
        }
        try {
            assessmentService.aggregateInformation(userId, scope.getProjectId(), scope.getAssessmentId(),
                    scope.getAnalysisRunId());
        } catch (RuntimeException exception) {
            markRunFailed(scope, exception);
            throw exception;
        }
        run.succeed(LocalDateTime.now());
        analysisRunRepository.saveAndFlush(run);
        try {
            cleanupService.cleanupAfterSuccess(scope.getAssessmentId(), scope.getAnalysisRunId());
        } catch (Exception exception) {
            throw new IllegalStateException("分析运行已成功，但旧结果清理失败", exception);
        }
    }

    private void markRunFailed(AnalysisScope scope, RuntimeException originalException) {
        try {
            analysisRunFailureService.markFailed(scope, LocalDateTime.now());
        } catch (RuntimeException failureException) {
            originalException.addSuppressed(failureException);
        }
    }
}
