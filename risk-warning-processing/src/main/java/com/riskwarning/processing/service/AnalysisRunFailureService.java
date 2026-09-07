package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 处理服务失败时仅终止对应的运行记录。 */
@Service
public class AnalysisRunFailureService {

    private final AnalysisRunRepository analysisRunRepository;

    public AnalysisRunFailureService(AnalysisRunRepository analysisRunRepository) {
        this.analysisRunRepository = analysisRunRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(AnalysisScope scope, LocalDateTime finishedAt) {
        if (scope == null || finishedAt == null) {
            throw new BusinessException("失败运行的作用域和结束时间不能为空");
        }
        AnalysisRun run = analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                        scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new BusinessException("找不到待终止的分析运行"));
        if (run.getStatus() == AnalysisRunStatus.RUNNING) {
            run.fail(finishedAt);
            analysisRunRepository.saveAndFlush(run);
        }
    }
}
