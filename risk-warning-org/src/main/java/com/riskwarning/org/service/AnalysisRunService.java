package com.riskwarning.org.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.org.repository.AnalysisRunRepository;
import com.riskwarning.org.repository.AssessmentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 创建运行并维护消息投递失败状态。 */
@Service
public class AnalysisRunService {

    private final AnalysisRunRepository analysisRunRepository;
    private final AssessmentRepository assessmentRepository;

    public AnalysisRunService(AnalysisRunRepository analysisRunRepository,
                              AssessmentRepository assessmentRepository) {
        this.analysisRunRepository = analysisRunRepository;
        this.assessmentRepository = assessmentRepository;
    }

    @Transactional
    public AnalysisRun start(AnalysisScope scope, LocalDateTime startedAt) {
        if (scope == null || startedAt == null) {
            throw new BusinessException("分析运行作用域和开始时间不能为空");
        }
        Assessment assessment = assessmentRepository.findById(scope.getAssessmentId().longValue());
        if (assessment == null || !scope.getProjectId().equals(assessment.getProjectId())) {
            throw new BusinessException("评估不属于指定项目");
        }
        if (analysisRunRepository.existsByAssessmentIdAndStatus(
                scope.getAssessmentId(), AnalysisRunStatus.RUNNING)) {
            throw new BusinessException("该评估已有运行中的分析任务");
        }
        try {
            return analysisRunRepository.saveAndFlush(AnalysisRun.start(scope, startedAt));
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException("该评估已有运行中的分析任务或运行身份冲突");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDispatchFailed(AnalysisScope scope, LocalDateTime finishedAt) {
        AnalysisRun run = analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                        scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new BusinessException("找不到待更新的分析运行"));
        if (run.getStatus() == AnalysisRunStatus.RUNNING) {
            run.fail(finishedAt);
            analysisRunRepository.saveAndFlush(run);
        }
    }
}
