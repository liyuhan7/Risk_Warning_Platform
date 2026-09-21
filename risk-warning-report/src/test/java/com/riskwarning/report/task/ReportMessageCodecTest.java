package com.riskwarning.report.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.message.AssessmentCompletedEventMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Report Durable Inbox 编解码测试：消息身份、作用域与载荷损坏边界。
 */
class ReportMessageCodecTest {

    private final ReportMessageCodec codec = new ReportMessageCodec(new ObjectMapper());

    @Test
    void roundTripKeepsIdentityAndScope() {
        AssessmentCompletedEventMessage message = message();

        String payload = codec.encode(message);
        AssessmentCompletedEventMessage decoded = codec.decode(payload);

        assertEquals(message.getMessageId(), decoded.getMessageId());
        assertEquals(message.getTraceId(), decoded.getTraceId());
        assertEquals(message.getUserId(), decoded.getUserId());
        assertEquals(message.getProjectId(), decoded.getProjectId());
        assertEquals(message.getAssessmentId(), decoded.getAssessmentId());
        assertEquals(message.getAnalysisRunId(), decoded.getAnalysisRunId());
    }

    @Test
    void rejectsBlankMessageId() {
        AssessmentCompletedEventMessage message = message();
        message.setMessageId(" ");

        assertThrows(IllegalArgumentException.class, () -> codec.encode(message));
    }

    @Test
    void rejectsOversizedMessageId() {
        AssessmentCompletedEventMessage message = message();
        StringBuilder oversized = new StringBuilder();
        for (int index = 0; index < 161; index++) { oversized.append('m'); }
        message.setMessageId(oversized.toString());

        assertThrows(IllegalArgumentException.class, () -> codec.encode(message));
    }

    @Test
    void rejectsInvalidUserId() {
        AssessmentCompletedEventMessage message = message();
        message.setUserId(0L);

        assertThrows(IllegalArgumentException.class, () -> codec.encode(message));
    }

    @Test
    void rejectsIncompleteScope() {
        AssessmentCompletedEventMessage message = message();
        message.setAnalysisRunId("");

        assertThrows(IllegalArgumentException.class, () -> codec.encode(message));
    }

    @Test
    void rejectsWrongTopic() {
        AssessmentCompletedEventMessage message = message();
        message.setTopic(null);

        assertThrows(IllegalArgumentException.class, () -> codec.encode(message));
    }

    @Test
    void rejectsEmptyAndBrokenPayload() {
        assertThrows(IllegalStateException.class, () -> codec.decode(" "));
        assertThrows(IllegalStateException.class, () -> codec.decode("{broken"));
    }

    @Test
    void decodedBrokenMessageFailsIdentityValidation() {
        // 缺少身份字段的消息虽然能反序列化，但复核必须拒绝
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{}"));
    }

    private AssessmentCompletedEventMessage message() {
        return new AssessmentCompletedEventMessage(
                "msg-1", "ts", "trace", 5L, 10L, 20L, "run-1");
    }
}
