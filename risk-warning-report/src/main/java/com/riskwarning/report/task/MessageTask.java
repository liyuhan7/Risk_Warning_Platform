package com.riskwarning.report.task;


import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.report.service.AnalysisRunCompletionService;
import com.riskwarning.report.service.AnalysisRunFailureService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;


/**
 * 兼容模式消费入口：可靠性开关关闭时保持既有直消费语义。
 * 本次失败立即标记运行失败并记录，重试恢复交给可靠链路承担。
 * 可靠模式由 DurableReportMessageTask 入箱，两个 listener 不会同时存在。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "assessment.reliability", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class MessageTask {

    @Autowired
    private AnalysisRunCompletionService analysisRunCompletionService;

    @Autowired
    private AnalysisRunFailureService analysisRunFailureService;

    @KafkaListener(topics = "assessment_completed_events", groupId = "test-consumer",
            containerFactory = "durableKafkaListenerContainerFactory")
    public void onMessage(AssessmentCompletedEventMessage message) {
        AnalysisScope scope = new AnalysisScope(
                message.getProjectId(), message.getAssessmentId(), message.getAnalysisRunId());
        log.info("接收评估结束任务，开始汇总信息");
        try {
            analysisRunCompletionService.aggregateAndComplete(message, scope);
        } catch (RuntimeException exception) {
            markRunFailed(scope, exception);
        }
    }

    private void markRunFailed(AnalysisScope scope, Exception cause) {
        try {
            analysisRunFailureService.markFailed(scope, java.time.LocalDateTime.now());
        } catch (Exception failureException) {
            log.error("标记失败运行异常: analysisRunId={}", scope.getAnalysisRunId(),
                    failureException);
        }
        log.error("报告汇总失败，运行已终止: analysisRunId={}", scope.getAnalysisRunId(), cause);
    }
}
