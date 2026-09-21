package com.riskwarning.processing.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.message.Message;
import org.springframework.stereotype.Component;

/**
 * Durable Inbox 载荷编解码。listener 收到 Kafka 消息后立即完成结构校验，
 * 并把完整消息快照写入 Durable Work，执行阶段不回读 Kafka。
 * payload 直接保存消息对象 JSON，kind 已确定具体消息类型，无需信封包装。
 */
@Component
public class ProcessingMessageCodec {

    /** task_key 数据库上限 160 字符，与 KafkaOutboxCodec 的幂等键规则一致。 */
    private static final int TASK_KEY_LIMIT = 160;

    private final ObjectMapper mapper;

    public ProcessingMessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 校验消息身份与作用域后编码载荷；messageId 即 Inbox 幂等 taskKey。 */
    public String encodeBehavior(BehaviorProcessingTaskMessage message) {
        requireIdentity(message);
        if (message.getType() == null) {
            throw new IllegalArgumentException("Behavior 任务缺少消息类型");
        }
        if (message.getType() == DataSourceTypeEnum.FILE_UPLOAD) {
            MessageTask.requireDocuments(message);
        }
        return writeValue(message, "Behavior");
    }

    /** 校验消息身份与作用域后编码载荷。 */
    public String encodeIndicator(IndicatorCalculationTaskMessage message) {
        requireIdentity(message);
        return writeValue(message, "Indicator");
    }

    /** 解码 Behavior 载荷并复核结构，损坏载荷进入 Durable Work 重试。 */
    public BehaviorProcessingTaskMessage decodeBehavior(String payload) {
        BehaviorProcessingTaskMessage message =
                readValue(payload, BehaviorProcessingTaskMessage.class, "Behavior");
        requireIdentity(message);
        if (message.getType() == null) {
            throw new IllegalStateException("Behavior 任务载荷缺少消息类型");
        }
        if (message.getType() == DataSourceTypeEnum.FILE_UPLOAD) {
            MessageTask.requireDocuments(message);
        }
        return message;
    }

    /** 解码 Indicator 载荷并复核作用域身份。 */
    public IndicatorCalculationTaskMessage decodeIndicator(String payload) {
        IndicatorCalculationTaskMessage message =
                readValue(payload, IndicatorCalculationTaskMessage.class, "Indicator");
        requireIdentity(message);
        return message;
    }

    /**
     * messageId 即幂等 taskKey，必须非空且不超过数据库列宽；
     * 作用域身份复用 requireScope，由 AnalysisScope 构造器强制完整。
     */
    private void requireIdentity(com.riskwarning.common.message.Message message) {
        if (message == null || message.getMessageId() == null
                || message.getMessageId().trim().isEmpty()) {
            throw new IllegalArgumentException("Inbox 消息缺少 messageId");
        }
        if (message.getMessageId().length() > TASK_KEY_LIMIT) {
            throw new IllegalArgumentException("Inbox 消息 messageId 超过 task_key 长度上限");
        }
        AnalysisScope scope = MessageTask.requireScope(message);
        if (scope == null) {
            throw new IllegalArgumentException("Inbox 消息缺少分析作用域");
        }
    }

    private String writeValue(Object message, String kind) {
        try {
            return mapper.writeValueAsString(message);
        } catch (Exception exception) {
            throw new IllegalStateException(kind + " 任务载荷序列化失败", exception);
        }
    }

    private <T> T readValue(String payload, Class<T> type, String kind) {
        if (payload == null || payload.trim().isEmpty()) {
            throw new IllegalStateException(kind + " 任务载荷不能为空");
        }
        try {
            return mapper.readValue(payload, type);
        } catch (Exception exception) {
            throw new IllegalStateException(kind + " 任务载荷反序列化失败", exception);
        }
    }
}
