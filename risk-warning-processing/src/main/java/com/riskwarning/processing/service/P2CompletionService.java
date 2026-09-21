package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.message.NotificationMessage;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowStage;
import com.riskwarning.common.observability.AssessmentFlowStatus;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.reliability.KafkaOutbox;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * P2 完成原子化：AnalysisRun 状态推进与完成通知 Outbox 入箱共用同一事务。
 * 通知入箱失败时整体回滚，运行保持 RUNNING，由 Durable Work 重试；
 * 已完成运行的重复调用按幂等重放处理，不重复入箱。
 */
@Service
@Slf4j
public class P2CompletionService {

    private final AnalysisRunRepository runs;
    private final KafkaOutbox outbox;
    private final AssessmentFlowLogger flowLogger;

    public P2CompletionService(AnalysisRunRepository runs, KafkaOutbox outbox,
                               AssessmentFlowLogger flowLogger) {
        this.runs = runs;
        this.outbox = outbox;
        this.flowLogger = flowLogger;
    }

    /** 校验作用域后原子完成：状态更新与 Outbox 入箱一次提交，异常整体回滚。 */
    @Transactional
    public void complete(IndicatorCalculationTaskMessage message, AnalysisScope scope) {
        requireConsistentScope(message, scope);
        AnalysisRun run;
        try {
            run = runs.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                            scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                    .orElseThrow(() -> new IllegalStateException("找不到待结束的分析运行"));
            if (run.getStatus() == AnalysisRunStatus.COMPLETED_WITHOUT_DECISION) {
                log.info("[P2 完成重放] analysisRunId={} 已完成，跳过重复状态变更与入箱",
                        run.getAnalysisRunId());
                flowLogger.info(flowEvent(message, scope, AssessmentFlowStatus.DUPLICATE, null));
                return;
            }
            if (run.getStatus() != AnalysisRunStatus.RUNNING) {
                // 终态不可逆：其他终态不覆盖也不补发通知，交由人工核查链路
                log.warn("[P2 完成跳过] analysisRunId={}, status={} 非进行中状态，不覆盖终态",
                        run.getAnalysisRunId(), run.getStatus());
                flowLogger.info(flowEvent(message, scope, AssessmentFlowStatus.SKIPPED, null));
                return;
            }
            run.completeWithoutDecision(LocalDateTime.now());
            runs.saveAndFlush(run);
            outbox.enqueue(buildNotification(message, scope));
        } catch (RuntimeException failure) {
            // 事务回滚与 Durable Work 重试由既有机制处理，此处只补观测事实
            flowLogger.error(flowEvent(message, scope, AssessmentFlowStatus.FAILED, failure));
            throw failure;
        }
        flowLogger.info(flowEvent(message, scope, AssessmentFlowStatus.SUCCEEDED, null));
        log.info("[P2 Run Completed] projectId={}, assessmentId={}, analysisRunId={}, "
                        + "runStatus=COMPLETED_WITHOUT_DECISION，完成通知已同事务入箱",
                scope.getProjectId(), scope.getAssessmentId(), scope.getAnalysisRunId());
    }

    private AssessmentFlowEvent flowEvent(IndicatorCalculationTaskMessage message, AnalysisScope scope,
                                          AssessmentFlowStatus status, Throwable failure) {
        return AssessmentFlowEvent.builder(AssessmentFlowStage.P2_COMPLETE, status)
                .projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId())
                .analysisRunId(scope.getAnalysisRunId()).messageId(message.getMessageId())
                .traceId(message.getTraceId())
                .failure(failure)
                .build();
    }

    /** 消息与运行作用域必须三重一致，防止跨运行误完成。 */
    private void requireConsistentScope(IndicatorCalculationTaskMessage message, AnalysisScope scope) {
        if (message == null || scope == null) {
            throw new IllegalArgumentException("完成消息与运行作用域不能为空");
        }
        if (!Objects.equals(message.getProjectId(), scope.getProjectId())
                || !Objects.equals(message.getAssessmentId(), scope.getAssessmentId())
                || !Objects.equals(message.getAnalysisRunId(), scope.getAnalysisRunId())) {
            throw new IllegalArgumentException("消息与运行作用域不一致");
        }
    }

    /**
     * 完成通知身份由 analysisRunId 稳定派生，重试与重放入箱按唯一约束去重；
     * traceId 优先沿用上游消息，缺失时生成新值，保证链路可追踪。
     */
    private NotificationMessage buildNotification(IndicatorCalculationTaskMessage message,
                                                  AnalysisScope scope) {
        NotificationMessage notification = new NotificationMessage(
                StringUtils.deriveMessageId(scope.getAnalysisRunId(), "p2-completed-notification"),
                LocalDateTime.now().toString(),
                hasText(message.getTraceId()) ? message.getTraceId() : StringUtils.generateTraceId(),
                message.getUserId(),
                scope.getProjectId(),
                scope.getAssessmentId(),
                NotificationMessage.NotificationType.ASSESSMENT_COMPLETED,
                "检索完成通知",
                "材料检索已完成，合规判断等待后续分析，请查看事实与候选依据。");
        notification.setAnalysisRunId(scope.getAnalysisRunId());
        notification.setExtraData("{\"displayStatus\":\"COMPLETED_WITHOUT_DECISION\",\"analysisStage\":\"P2\"}");
        return notification;
    }

    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
