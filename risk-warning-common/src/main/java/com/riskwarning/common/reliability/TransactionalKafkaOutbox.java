package com.riskwarning.common.reliability;

import com.riskwarning.common.message.Message;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowStage;
import com.riskwarning.common.observability.AssessmentFlowStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务性 Outbox：消息以 KAFKA_OUTBOX 任务写入 t_durable_work，
 * 与调用方业务更新在同一个数据库事务内提交；taskKey 即 messageId，
 * 同一逻辑消息重复入箱由唯一约束去重。
 */
@Slf4j
public class TransactionalKafkaOutbox implements KafkaOutbox {

    private final DurableWorkStore store;
    private final KafkaOutboxCodec codec;
    private final AssessmentFlowLogger flowLogger;

    public TransactionalKafkaOutbox(DurableWorkStore store, KafkaOutboxCodec codec,
                                    AssessmentFlowLogger flowLogger) {
        this.store = store;
        this.codec = codec;
        this.flowLogger = flowLogger;
    }

    @Override
    public void enqueue(Message message) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 消息本身仍然持久可靠，但与业务更新不原子，等待 Durable Inbox 阶段消除该窗口
            log.warn("Outbox 消息在事务外写入，与业务更新不原子: messageId={}", message.getMessageId());
        }
        String payload = codec.encode(message);
        try {
            boolean inserted = store.enqueue(KafkaOutboxHandler.KIND, message.getMessageId(), payload);
            if (inserted) {
                flowLogger.info(outboxEvent(message, AssessmentFlowStatus.SUCCEEDED, null));
            } else {
                // 同一逻辑消息已入箱，业务重复执行不再产生重复投递
                log.debug("Outbox 消息已存在，跳过重复入箱: messageId={}", message.getMessageId());
                flowLogger.info(outboxEvent(message, AssessmentFlowStatus.DUPLICATE, null));
            }
        } catch (RuntimeException failure) {
            flowLogger.error(outboxEvent(message, AssessmentFlowStatus.FAILED, failure));
            throw failure;
        }
    }

    private AssessmentFlowEvent outboxEvent(Message message, AssessmentFlowStatus status,
                                            Throwable failure) {
        return AssessmentFlowEvent.builder(
                        AssessmentFlowStage.KAFKA_OUTBOX, status)
                .projectId(message.getProjectId()).assessmentId(message.getAssessmentId())
                .analysisRunId(message.getAnalysisRunId()).messageId(message.getMessageId())
                .traceId(message.getTraceId()).kind(KafkaOutboxHandler.KIND)
                .failure(failure)
                .build();
    }

    @Override
    public boolean isDurable() {
        return true;
    }
}
