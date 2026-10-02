package com.riskwarning.org.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.AnalysisExecutionMode;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

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
            throw new BusinessException(400, "分析运行作用域和开始时间不能为空");
        }
        Assessment assessment = assessmentRepository.findById(scope.getAssessmentId().longValue());
        if (assessment == null) {
            throw new BusinessException(404, "评估不存在");
        }
        if (!scope.getProjectId().equals(assessment.getProjectId())) {
            throw new BusinessException(403, "评估不属于指定项目");
        }
        if (analysisRunRepository.existsByAssessmentIdAndStatus(
                scope.getAssessmentId(), AnalysisRunStatus.RUNNING)) {
            throw new BusinessException(409, "该评估已有运行中的分析任务");
        }
        try {
            return analysisRunRepository.saveAndFlush(AnalysisRun.start(scope, startedAt, AnalysisExecutionMode.REASONING));
        } catch (DataIntegrityViolationException exception) {
            if (isRunIdentityConflict(exception)) {
                throw new BusinessException(409, "分析运行身份冲突");
            }
            throw exception;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDispatchFailed(AnalysisScope scope, LocalDateTime finishedAt) {
        if (scope == null || finishedAt == null) {
            throw new BusinessException(400, "分析运行作用域和结束时间不能为空");
        }
        AnalysisRun run = analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                        scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new BusinessException(404, "找不到待更新的分析运行"));
        if (run.getStatus() == AnalysisRunStatus.RUNNING) {
            run.fail(finishedAt);
            analysisRunRepository.saveAndFlush(run);
        }
    }

    private boolean isRunIdentityConflict(Throwable failure) {
        // 只有已知运行身份唯一约束的违反才属于业务冲突，外键和检查约束仍是系统错误。
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException) {
                org.hibernate.exception.ConstraintViolationException violation =
                        (org.hibernate.exception.ConstraintViolationException) cause;
                String constraint = violation.getConstraintName();
                if ("23505".equals(violation.getSQLState()) && ("t_analysis_run_pkey".equals(constraint)
                        || "uq_analysis_run_scope".equals(constraint)
                        || "uq_analysis_run_assessment".equals(constraint))) {
                    return true;
                }
            }
        }
        return false;
    }
}
