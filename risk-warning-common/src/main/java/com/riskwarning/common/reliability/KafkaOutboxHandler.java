package com.riskwarning.common.reliability;

import com.riskwarning.common.message.Message;
import com.riskwarning.common.observability.AssessmentFlowContext;
import com.riskwarning.common.utils.KafkaUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * KAFKA_OUTBOX 持久任务：重建具体消息对象后同步发送并等待 Broker 确认，
 * 确认后任务 DONE。发送失败交给 Durable Work 重试，Broker 不可用不丢消息。
 */
@Slf4j
public class KafkaOutboxHandler implements DurableWorkHandler {

    public static final String KIND = "KAFKA_OUTBOX";

    /** 生产端 delivery.timeout 默认 120s，等待上限需覆盖重试窗口。 */
    static final long SEND_TIMEOUT_MILLIS = 180_000L;

    /** Broker 不可用时持续重试；耗尽后任务 FAILED，消息仍保留在载荷中可人工重放。 */
    static final int MAX_ATTEMPTS = 20;

    private final KafkaUtils kafkaUtils;
    private final KafkaOutboxCodec codec;

    public KafkaOutboxHandler(KafkaUtils kafkaUtils, KafkaOutboxCodec codec) {
        this.kafkaUtils = kafkaUtils;
        this.codec = codec;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public int maxAttempts() {
        return MAX_ATTEMPTS;
    }

    @Override
    public void execute(DurableWorkContext context, String payload) throws Exception {
        Message message = codec.decode(payload);
        kafkaUtils.sendMessageAndWait(message, SEND_TIMEOUT_MILLIS);
        log.info("Outbox 消息已确认: messageId={}, topic={}, attempt={}",
                message.getMessageId(), message.getTopic(), context.getAttempt());
    }

    /** 重试耗尽后输出可接入告警的稳定错误标记；消息保留在载荷中可人工重放。 */
    @Override
    public void onExhausted(DurableWorkContext context, String payload, Throwable failure) {
        String messageId = "unavailable";
        String topic = "unavailable";
        try {
            Message message = codec.decode(payload);
            messageId = message.getMessageId();
            topic = message.getTopic() == null ? "unavailable" : message.getTopic().name();
        } catch (Exception decodeException) {
            log.error("[可靠链失败] Outbox 载荷解析失败，无法提取消息身份: workId={}",
                    context.getWorkId(), decodeException);
        }
        log.error("[可靠链失败] Outbox 消息投递最终失败: messageId={}, topic={}, workId={}, attempt={}",
                messageId, topic, context.getWorkId(), context.getAttempt(), failure);
    }

    /** Outbox 载荷自带消息作用域，可完整恢复链路身份。 */
    @Override
    public AssessmentFlowContext flowContext(DurableWorkContext context, String payload) {
        Message message = codec.decode(payload);
        return AssessmentFlowContext.fromWork(context)
                .toBuilder()
                .projectId(message.getProjectId()).assessmentId(message.getAssessmentId())
                .analysisRunId(message.getAnalysisRunId()).traceId(message.getTraceId())
                .build();
    }
}
