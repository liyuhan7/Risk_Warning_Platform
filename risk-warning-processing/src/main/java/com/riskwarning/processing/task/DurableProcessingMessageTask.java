package com.riskwarning.processing.task;

import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowStage;
import com.riskwarning.common.observability.AssessmentFlowStatus;
import com.riskwarning.common.reliability.DurableWorkStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 可靠模式 Kafka 入箱 listener：只做结构校验并写入 Durable Inbox，
 * 不在 listener 中运行文档处理、Embedding、检索或 LLM。
 * 容器使用 durable 工厂逐条提交位点，listener 返回即代表任务所有权
 * 已持久化到 t_durable_work；重复 messageId 由唯一键去重，视为交接成功。
 * 兼容消费由 MessageTask 承担，两个 listener 不会同时存在。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "assessment.reliability", name = "enabled", havingValue = "true")
public class DurableProcessingMessageTask {

    public static final String BEHAVIOR_KIND = "BEHAVIOR_PROCESSING";
    public static final String INDICATOR_KIND = "INDICATOR_CALCULATION";

    private final DurableWorkStore store;
    private final ProcessingMessageCodec codec;
    private final AssessmentFlowLogger flowLogger;

    public DurableProcessingMessageTask(DurableWorkStore store, ProcessingMessageCodec codec,
                                        AssessmentFlowLogger flowLogger) {
        this.store = store;
        this.codec = codec;
        this.flowLogger = flowLogger;
    }

    @KafkaListener(topics = "behavior_processing_tasks", groupId = "test-consumer",
            containerFactory = "durableKafkaListenerContainerFactory")
    public void onBehaviorMessage(BehaviorProcessingTaskMessage message) {
        AssessmentFlowEvent base = inboxEvent(AssessmentFlowStage.BEHAVIOR_INBOX, message);
        enqueue(BEHAVIOR_KIND, message.getMessageId(), codec.encodeBehavior(message), base);
    }

    @KafkaListener(topics = "indicator_calculation_tasks", groupId = "indicator-calculation-consumer",
            containerFactory = "durableKafkaListenerContainerFactory")
    public void onIndicatorMessage(IndicatorCalculationTaskMessage message) {
        AssessmentFlowEvent base = inboxEvent(AssessmentFlowStage.INDICATOR_INBOX, message);
        enqueue(INDICATOR_KIND, message.getMessageId(), codec.encodeIndicator(message), base);
    }

    private AssessmentFlowEvent inboxEvent(AssessmentFlowStage stage,
                                           BehaviorProcessingTaskMessage message) {
        return AssessmentFlowEvent.builder(stage, AssessmentFlowStatus.SUCCEEDED)
                .projectId(message.getProjectId()).assessmentId(message.getAssessmentId())
                .analysisRunId(message.getAnalysisRunId()).messageId(message.getMessageId())
                .traceId(message.getTraceId()).kind(BEHAVIOR_KIND)
                .build();
    }

    private AssessmentFlowEvent inboxEvent(AssessmentFlowStage stage,
                                           IndicatorCalculationTaskMessage message) {
        return AssessmentFlowEvent.builder(stage, AssessmentFlowStatus.SUCCEEDED)
                .projectId(message.getProjectId()).assessmentId(message.getAssessmentId())
                .analysisRunId(message.getAnalysisRunId()).messageId(message.getMessageId())
                .traceId(message.getTraceId()).kind(INDICATOR_KIND)
                .build();
    }

    private void enqueue(String kind, String messageId, String payload, AssessmentFlowEvent success) {
        try {
            if (store.enqueue(kind, messageId, payload)) {
                log.info("[Durable Inbox] 任务已入箱: kind={}, messageId={}", kind, messageId);
                flowLogger.info(success);
            } else {
                // 重复投递命中唯一键，保留既有任务，避免同一消息重复执行
                log.info("[Durable Inbox] 任务已存在，幂等去重: kind={}, messageId={}", kind, messageId);
                flowLogger.info(success.toBuilder()
                        .status(AssessmentFlowStatus.DUPLICATE).build());
            }
        } catch (RuntimeException failure) {
            // 入箱失败交给容器错误处理按位点重投，不吞异常
            flowLogger.error(success.toBuilder()
                    .status(AssessmentFlowStatus.FAILED).failure(failure).build());
            throw failure;
        }
    }
}
