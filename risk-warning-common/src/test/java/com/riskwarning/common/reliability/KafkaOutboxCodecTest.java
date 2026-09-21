package com.riskwarning.common.reliability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.enums.KafkaTopic;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.message.Message;
import com.riskwarning.common.message.NotificationMessage;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KafkaOutboxCodecTest {

    private final KafkaOutboxCodec codec = new KafkaOutboxCodec();

    @Test
    void roundTripsBehaviorMessageWithDocumentRefs() {
        SourceDocumentRef document = new SourceDocumentRef(101L, "source.pdf");
        BehaviorProcessingTaskMessage message = BehaviorProcessingTaskMessage.forDocuments(
                "msg_1", "timestamp", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD, Collections.singletonList(document));

        Message decoded = codec.decode(codec.encode(message));

        assertThat(decoded).isInstanceOf(BehaviorProcessingTaskMessage.class);
        BehaviorProcessingTaskMessage behaviorMessage = (BehaviorProcessingTaskMessage) decoded;
        assertThat(behaviorMessage.getMessageId()).isEqualTo("msg_1");
        assertThat(behaviorMessage.getTopic()).isEqualTo(KafkaTopic.BEHAVIOR_PROCESSING_TASKS);
        assertThat(behaviorMessage.getAnalysisRunId()).isEqualTo("run-1");
        assertThat(behaviorMessage.getDocuments()).containsExactly(document);
    }

    @Test
    void roundTripsIndicatorMessageWithDocuments() {
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
                "msg_1:indicator", "timestamp", "trace", 1L, 10L, 20L, "run-1");
        message.withDocuments(Collections.singletonList(new SourceDocumentRef(101L, "source.pdf")));

        Message decoded = codec.decode(codec.encode(message));

        assertThat(decoded).isInstanceOf(IndicatorCalculationTaskMessage.class);
        IndicatorCalculationTaskMessage indicatorMessage = (IndicatorCalculationTaskMessage) decoded;
        assertThat(indicatorMessage.getTopic()).isEqualTo(KafkaTopic.INDICATOR_CALCULATION_TASKS);
        assertThat(indicatorMessage.getDocuments()).hasSize(1);
        assertThat(indicatorMessage.getDocuments().get(0).getFilePath()).isEqualTo("source.pdf");
    }

    @Test
    void roundTripsNotificationAndAssessmentCompletedMessages() {
        NotificationMessage notification = new NotificationMessage(
                "run-1:p2-completed-notification", "timestamp", "trace", 1L, 10L, 20L,
                NotificationMessage.NotificationType.ASSESSMENT_COMPLETED, "标题", "内容");
        notification.setExtraData("{\"displayStatus\":\"COMPLETED_WITHOUT_DECISION\"}");

        Message decodedNotification = codec.decode(codec.encode(notification));
        assertThat(decodedNotification).isInstanceOf(NotificationMessage.class);
        NotificationMessage decoded = (NotificationMessage) decodedNotification;
        assertThat(decoded.getNotificationType()).isEqualTo(NotificationMessage.NotificationType.ASSESSMENT_COMPLETED);
        assertThat(decoded.getExtraData()).contains("COMPLETED_WITHOUT_DECISION");

        AssessmentCompletedEventMessage completed = new AssessmentCompletedEventMessage(
                "msg_1:assessment-completed", "timestamp", "trace", 1L, 10L, 20L, "run-1");
        Message decodedCompleted = codec.decode(codec.encode(completed));
        assertThat(decodedCompleted).isInstanceOf(AssessmentCompletedEventMessage.class);
        assertThat(decodedCompleted.getTopic()).isEqualTo(KafkaTopic.ASSESSMENT_COMPLETED_EVENTS);
    }

    @Test
    void rejectsMessageWithoutTopicOrMessageId() {
        Message noTopic = new Message("msg_1", "timestamp", "trace", 1L, 10L, 20L);
        assertThatThrownBy(() -> codec.encode(noTopic))
                .isInstanceOf(IllegalArgumentException.class);

        NotificationMessage noMessageId = new NotificationMessage();
        assertThatThrownBy(() -> codec.encode(noMessageId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
    }

    @Test
    void rejectsMessageIdLongerThanTaskKeyLimit() {
        NotificationMessage message = new NotificationMessage(
                repeat("a", 161), "timestamp", "trace", 1L, 10L, 20L);

        assertThatThrownBy(() -> codec.encode(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长度上限");
    }

    @Test
    void rejectsForeignMessageTypeAndBlankPayload() {
        String foreignEnvelope = envelope("com.example.foreign.Message", "{}");
        assertThatThrownBy(() -> codec.decode(foreignEnvelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("消息类型");

        assertThatThrownBy(() -> codec.decode(envelope(
                "com.riskwarning.common.message.NotificationMessage", " ")))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> codec.decode(" "))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> codec.decode("{not-json"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsEnvelopeTopicInconsistentWithMessage() throws Exception {
        // 直接构造信封，令声明的 topic 与消息自身 topic 不一致
        NotificationMessage message = new NotificationMessage(
                "msg_1", "timestamp", "trace", 1L, 10L, 20L);
        ObjectMapper mapper = new ObjectMapper();
        KafkaOutboxCodec.Envelope envelope = new KafkaOutboxCodec.Envelope();
        envelope.setTopic(KafkaTopic.BEHAVIOR_PROCESSING_TASKS.name());
        envelope.setMessageType(NotificationMessage.class.getName());
        envelope.setBody(mapper.writeValueAsString(message));

        assertThatThrownBy(() -> codec.decode(mapper.writeValueAsString(envelope)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topic");
    }

    private String envelope(String messageType, String body) {
        return "{\"topic\":\"NOTIFICATION_TASKS\",\"messageType\":\"" + messageType
                + "\",\"body\":" + quote(body) + "}";
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String repeat(String value, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) {
            builder.append(value);
        }
        return builder.toString();
    }
}
