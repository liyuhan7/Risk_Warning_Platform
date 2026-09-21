package com.riskwarning.common.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.message.Message;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outbox 载荷信封编解码。body 用 Jackson 序列化，与生产端 JsonSerializer 的
 * 对象语义一致；messageType 记录具体消息类，发送时重建对象而非转发字符串，
 * 保证 topic 头与序列化行为与直发完全相同。
 */
public class KafkaOutboxCodec {

    /** 消息类白名单前缀：载荷来自数据库内部写入，仍拒绝反射加载任意类。 */
    private static final String MESSAGE_PACKAGE = "com.riskwarning.common.message.";

    private final ObjectMapper mapper;

    public KafkaOutboxCodec() {
        this(new ObjectMapper());
    }

    public KafkaOutboxCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 校验消息身份并编码为信封 JSON；messageId 即 Outbox 幂等 taskKey。 */
    public String encode(Message message) {
        requireSendable(message);
        Envelope envelope = new Envelope();
        envelope.setTopic(message.getTopic().name());
        envelope.setMessageType(message.getClass().getName());
        try {
            envelope.setBody(mapper.writeValueAsString(message));
        } catch (Exception exception) {
            throw new IllegalStateException("Outbox 消息序列化失败: " + message.getMessageId(), exception);
        }
        try {
            return mapper.writeValueAsString(envelope);
        } catch (Exception exception) {
            throw new IllegalStateException("Outbox 信封序列化失败: " + message.getMessageId(), exception);
        }
    }

    /** 解码并重建具体消息对象；信封 topic 与消息自身 topic 必须一致。 */
    public Message decode(String payload) {
        if (payload == null || payload.trim().isEmpty()) {
            throw new IllegalStateException("Outbox 载荷不能为空");
        }
        Envelope envelope;
        try {
            envelope = mapper.readValue(payload, Envelope.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Outbox 信封反序列化失败", exception);
        }
        if (envelope.getMessageType() == null || !envelope.getMessageType().startsWith(MESSAGE_PACKAGE)
                || envelope.getBody() == null || envelope.getBody().trim().isEmpty()) {
            throw new IllegalStateException("Outbox 载荷缺少消息类型或消息体");
        }
        Message message;
        try {
            Class<?> type = Class.forName(envelope.getMessageType());
            message = (Message) mapper.readValue(envelope.getBody(), type);
        } catch (Exception exception) {
            throw new IllegalStateException("Outbox 消息重建失败: " + envelope.getMessageType(), exception);
        }
        if (message.getTopic() == null || envelope.getTopic() == null
                || !envelope.getTopic().equals(message.getTopic().name())) {
            throw new IllegalStateException("Outbox 信封 topic 与消息不一致");
        }
        return message;
    }

    private void requireSendable(Message message) {
        if (message == null || message.getTopic() == null) {
            throw new IllegalArgumentException("Outbox 消息及其 topic 不能为空");
        }
        if (message.getMessageId() == null || message.getMessageId().trim().isEmpty()) {
            throw new IllegalArgumentException("Outbox 消息缺少 messageId");
        }
        if (message.getMessageId().length() > 160) {
            throw new IllegalArgumentException("Outbox 消息 messageId 超过 task_key 长度上限");
        }
    }

    /** t_durable_work.payload 中的信封结构。 */
    @Data
    @NoArgsConstructor
    public static class Envelope {
        private String topic;
        private String messageType;
        private String body;
    }
}
