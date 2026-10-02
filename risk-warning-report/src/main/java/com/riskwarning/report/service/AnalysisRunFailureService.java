package com.riskwarning.report.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.report.repository.AnalysisRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 报告汇总失败时只终止对应的运行记录。 */
@Service
public class AnalysisRunFailureService {

    private final AnalysisRunRepository analysisRunRepository;

    public AnalysisRunFailureService(AnalysisRunRepository analysisRunRepository) {
        this.analysisRunRepository = analysisRunRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(AnalysisScope scope, LocalDateTime finishedAt) {
        // 与报告完成共用行锁；迟到的耗尽回调不得覆盖已提交的成功终态。
        AnalysisRun run = analysisRunRepository.lockScope(
                        scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new BusinessException("找不到待终止的分析运行"));
        if (run.getStatus() == AnalysisRunStatus.RUNNING) {
            run.fail(finishedAt);
            analysisRunRepository.saveAndFlush(run);
        }
    }
}
