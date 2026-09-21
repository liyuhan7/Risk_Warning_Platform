package com.riskwarning.report.service;

import com.alibaba.fastjson2.JSON;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.exception.BusinessException;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.message.NotificationMessage;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowStage;
import com.riskwarning.common.observability.AssessmentFlowStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.report.repository.AnalysisRunRepository;
import com.riskwarning.report.repository.AssessmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * LEGACY 报告完成链：汇总、旧结果清理、运行成功与完成通知入箱共用同一
 * 数据库事务。严格顺序 aggregate → cleanup → run.succeed → 通知入箱，
 * cleanup 失败时运行不得提前进入 SUCCEEDED；任何一步失败整体回滚，
 * 由 Durable Work 重试，本服务不负责终止运行。
 */
@Service
@Slf4j
public class AnalysisRunCompletionService {

    private final AnalysisRunRepository analysisRunRepository;
    private final AssessmentService assessmentService;
    private final AssessmentRepository assessmentRepository;
    private final AnalysisRunResultCleanupService cleanupService;
    private final KafkaOutbox outbox;
    private final AssessmentFlowLogger flowLogger;

    public AnalysisRunCompletionService(AnalysisRunRepository analysisRunRepository,
                                        AssessmentService assessmentService,
                                        AssessmentRepository assessmentRepository,
                                        AnalysisRunResultCleanupService cleanupService,
                                        KafkaOutbox outbox,
                                        AssessmentFlowLogger flowLogger) {
        this.analysisRunRepository = analysisRunRepository;
        this.assessmentService = assessmentService;
        this.assessmentRepository = assessmentRepository;
        this.cleanupService = cleanupService;
        this.outbox = outbox;
        this.flowLogger = flowLogger;
    }

    /**
     * 校验事件与运行作用域一致后，在同一事务内完成聚合、清理、
     * 运行成功标记与完成通知入箱；任何一步失败整体回滚并向上抛出。
     */
    @Transactional
    public void aggregateAndComplete(AssessmentCompletedEventMessage message, AnalysisScope scope) {
        requireConsistent(message, scope);
        AnalysisRun run;
        try {
            run = analysisRunRepository.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                            scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                    .orElseThrow(() -> new BusinessException("找不到匹配的分析运行"));
            if (run.getStatus() == AnalysisRunStatus.SUCCEEDED) {
                log.info("[报告完成重放] analysisRunId={} 已成功，跳过重复处理", run.getAnalysisRunId());
                flowLogger.info(flowEvent(message, scope, AssessmentFlowStatus.DUPLICATE, null));
                return;
            }
            if (run.getStatus() != AnalysisRunStatus.RUNNING) {
                throw new BusinessException("已结束的分析运行不能生成报告");
            }
            assessmentService.aggregateInformation(message.getUserId(), scope.getProjectId(),
                    scope.getAssessmentId(), scope.getAnalysisRunId());
            flowLogger.info(aggregateEvent(message, scope));
            try {
                cleanupService.cleanupAfterSuccess(scope.getAssessmentId(), scope.getAnalysisRunId());
            } catch (Exception exception) {
                // 清理失败时运行保持 RUNNING，任务交还 Durable Work 重试
                throw new IllegalStateException("旧结果清理失败，运行保持 RUNNING 待重试", exception);
            }
            run.succeed(LocalDateTime.now());
            analysisRunRepository.saveAndFlush(run);
            outbox.enqueue(buildNotification(message, scope));
        } catch (RuntimeException failure) {
            // 事务回滚与 Durable Work 重试由外层接管，日志动作不得吞异常
            flowLogger.error(flowEvent(message, scope, AssessmentFlowStatus.FAILED, failure));
            throw failure;
        }
        flowLogger.info(flowEvent(message, scope, AssessmentFlowStatus.SUCCEEDED, null));
        log.info("[报告完成] analysisRunId={}, runStatus=SUCCEEDED，完成通知已同事务入箱",
                scope.getAnalysisRunId());
    }

    private AssessmentFlowEvent aggregateEvent(AssessmentCompletedEventMessage message,
                                               AnalysisScope scope) {
        return AssessmentFlowEvent.builder(
                        AssessmentFlowStage.REPORT_AGGREGATE, AssessmentFlowStatus.SUCCEEDED)
                .projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId())
                .analysisRunId(scope.getAnalysisRunId()).messageId(message.getMessageId())
                .traceId(message.getTraceId())
                .build();
    }

    private AssessmentFlowEvent flowEvent(AssessmentCompletedEventMessage message,
                                          AnalysisScope scope, AssessmentFlowStatus status,
                                          Throwable failure) {
        return AssessmentFlowEvent.builder(AssessmentFlowStage.REPORT_COMPLETE, status)
                .projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId())
                .analysisRunId(scope.getAnalysisRunId()).messageId(message.getMessageId())
                .traceId(message.getTraceId())
                .failure(failure)
                .build();
    }

    /** 消息与运行作用域必须三重一致，防止跨运行误生成报告。 */
    private void requireConsistent(AssessmentCompletedEventMessage message, AnalysisScope scope) {
        if (message == null || scope == null) {
            throw new BusinessException("报告汇总所需的用户和运行作用域不能为空");
        }
        if (message.getUserId() == null || message.getUserId() <= 0) {
            throw new BusinessException("报告汇总所需的用户和运行作用域不能为空");
        }
        if (!Objects.equals(message.getProjectId(), scope.getProjectId())
                || !Objects.equals(message.getAssessmentId(), scope.getAssessmentId())
                || !Objects.equals(message.getAnalysisRunId(), scope.getAnalysisRunId())) {
            throw new BusinessException("消息与运行作用域不一致");
        }
    }

    /**
     * 完成通知身份由 analysisRunId 稳定派生，重试与重放入箱按唯一约束去重；
     * traceId 优先沿用上游事件，缺失时生成新值，保证链路可追踪。
     */
    private NotificationMessage buildNotification(AssessmentCompletedEventMessage message,
                                                  AnalysisScope scope) {
        Assessment assessment = assessmentRepository.findById(scope.getAssessmentId())
                .orElseThrow(() -> new IllegalStateException("汇总后找不到评估记录"));
        NotificationMessage notification = new NotificationMessage();
        notification.setMessageId(StringUtils.deriveMessageId(scope.getAnalysisRunId(),
                "assessment-completed-notification"));
        notification.setTimestamp(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        notification.setTraceId(hasText(message.getTraceId())
                ? message.getTraceId() : StringUtils.generateTraceId());
        notification.setUserId(message.getUserId());
        notification.setProjectId(scope.getProjectId());
        notification.setAssessmentId(scope.getAssessmentId());
        notification.setAnalysisRunId(scope.getAnalysisRunId());
        notification.setNotificationType(NotificationMessage.NotificationType.ASSESSMENT_COMPLETED);
        notification.setTitle("评估完成通知");
        notification.setContent(String.format("项目评估已完成，总体风险等级：%s，评估得分：%.2f",
                requireRiskLevel(assessment).getDescription(), assessment.getOverallScore()));
        Map<String, Object> extraData = new LinkedHashMap<>();
        extraData.put("assessmentId", scope.getAssessmentId());
        extraData.put("projectId", scope.getProjectId());
        extraData.put("riskLevel", assessment.getOverallRiskLevel().name());
        extraData.put("overallScore", assessment.getOverallScore());
        notification.setExtraData(JSON.toJSONString(extraData));
        return notification;
    }

    private com.riskwarning.common.enums.risk.RiskLevelEnum requireRiskLevel(Assessment assessment) {
        if (assessment.getOverallRiskLevel() == null) {
            throw new IllegalStateException("汇总后评估缺少总体风险等级");
        }
        return assessment.getOverallRiskLevel();
    }

    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
