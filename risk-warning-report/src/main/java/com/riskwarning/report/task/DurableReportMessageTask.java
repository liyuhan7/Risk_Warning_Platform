package com.riskwarning.report.task;

import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.reliability.DurableWorkStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 可靠模式 Kafka 入箱 listener：只做结构校验并写入 Durable Inbox，
 * 不在 listener 中运行报告汇总、结果清理或运行状态推进。
 * 容器使用 durable 工厂逐条提交位点，listener 返回即代表任务所有权
 * 已持久化到 t_durable_work；重复 messageId 由唯一键去重，视为交接成功。
 * 兼容消费由 MessageTask 承担，两个 listener 不会同时存在。
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "assessment.reliability", name = "enabled", havingValue = "true")
public class DurableReportMessageTask {

    private final DurableWorkStore store;
    private final ReportMessageCodec codec;

    public DurableReportMessageTask(DurableWorkStore store, ReportMessageCodec codec) {
        this.store = store;
        this.codec = codec;
    }

    @KafkaListener(topics = "assessment_completed_events", groupId = "test-consumer",
            containerFactory = "durableKafkaListenerContainerFactory")
    public void onMessage(AssessmentCompletedEventMessage message) {
        String payload = codec.encode(message);
        if (store.enqueue(ReportAggregationWorkHandler.KIND, message.getMessageId(), payload)) {
            log.info("[Durable Inbox] 报告任务已入箱: messageId={}", message.getMessageId());
        } else {
            // 重复投递命中唯一键，保留既有任务，避免同一消息重复执行
            log.info("[Durable Inbox] 报告任务已存在，幂等去重: messageId={}", message.getMessageId());
        }
    }
}
