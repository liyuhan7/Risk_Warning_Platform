package com.riskwarning.common.reliability;

import com.riskwarning.common.message.Message;
import com.riskwarning.common.observability.AssessmentFlowEvent;
import com.riskwarning.common.observability.AssessmentFlowLogger;
import com.riskwarning.common.observability.AssessmentFlowStage;
import com.riskwarning.common.observability.AssessmentFlowStatus;
import com.riskwarning.common.utils.KafkaUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 兼容 Outbox：未启用可靠链时保持既有投递语义。
 * 处于业务事务内则提交后投递，避免事务回滚产生幻影消息；事务外直接投递。
 * SKIPPED 事件用于区分当前运行在非持久兼容模式。
 */
@Slf4j
public class AfterCommitKafkaOutbox implements KafkaOutbox {

    private final KafkaUtils kafkaUtils;
    private final AssessmentFlowLogger flowLogger;

    public AfterCommitKafkaOutbox(KafkaUtils kafkaUtils, AssessmentFlowLogger flowLogger) {
        this.kafkaUtils = kafkaUtils;
        this.flowLogger = flowLogger;
    }

    @Override
    public void enqueue(Message message) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            kafkaUtils.sendMessage(message);
            return;
        }
        flowLogger.info(compatEvent(message));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    kafkaUtils.sendMessage(message);
                } catch (RuntimeException exception) {
                    log.error("事务提交后投递消息失败: messageId={}", message.getMessageId(), exception);
                }
            }
        });
    }

    private AssessmentFlowEvent compatEvent(Message message) {
        return AssessmentFlowEvent.builder(
                        AssessmentFlowStage.KAFKA_OUTBOX, AssessmentFlowStatus.SKIPPED)
                .projectId(message.getProjectId()).assessmentId(message.getAssessmentId())
                .analysisRunId(message.getAnalysisRunId()).messageId(message.getMessageId())
                .traceId(message.getTraceId())
                .build();
    }

    @Override
    public boolean isDurable() {
        return false;
    }
}
