package com.riskwarning.processing.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.message.BehaviorProcessingTaskMessage;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Inbox 载荷编解码测试：往返语义与基础结构校验边界。
 */
class ProcessingMessageCodecTest {

    private final ProcessingMessageCodec codec = new ProcessingMessageCodec(new ObjectMapper());

    @Test
    void behaviorPayloadRoundTripPreservesScopeAndDocuments() {
        BehaviorProcessingTaskMessage message = BehaviorProcessingTaskMessage.forDocuments(
                "msg-1", "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD,
                java.util.Collections.singletonList(new SourceDocumentRef(101L, "source.pdf")));

        BehaviorProcessingTaskMessage decoded =
                codec.decodeBehavior(codec.encodeBehavior(message));

        assertEquals(message, decoded);
        assertEquals(new AnalysisScope(10L, 20L, "run-1"), MessageTask.requireScope(decoded));
        assertEquals(message.getDocuments(), decoded.getDocuments());
    }

    @Test
    void indicatorPayloadRoundTripPreservesDocuments() {
        IndicatorCalculationTaskMessage message = new IndicatorCalculationTaskMessage(
                "msg-2", "ts", "trace", 1L, 10L, 20L, "run-1")
                .withDocuments(java.util.Collections.singletonList(new SourceDocumentRef(101L, "a.pdf")));

        IndicatorCalculationTaskMessage decoded =
                codec.decodeIndicator(codec.encodeIndicator(message));

        assertEquals(message, decoded);
        assertEquals("msg-2:assessment-completed", com.riskwarning.common.utils.StringUtils
                .deriveMessageId(decoded.getMessageId(), "assessment-completed"));
    }

    @Test
    void rejectsBlankMessageId() {
        BehaviorProcessingTaskMessage message = new BehaviorProcessingTaskMessage(
                null, "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD, java.util.Collections.emptyList());
        message.setDocuments(java.util.Collections.singletonList(new SourceDocumentRef(101L, "a.pdf")));

        assertThrows(IllegalArgumentException.class, () -> codec.encodeBehavior(message));
    }

    @Test
    void rejectsMessageIdLongerThanTaskKeyLimit() {
        StringBuilder messageId = new StringBuilder();
        for (int index = 0; index < 161; index++) { messageId.append('a'); }
        BehaviorProcessingTaskMessage message = new BehaviorProcessingTaskMessage(
                messageId.toString(), "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD, java.util.Collections.emptyList());
        message.setDocuments(java.util.Collections.singletonList(new SourceDocumentRef(101L, "a.pdf")));

        assertThrows(IllegalArgumentException.class, () -> codec.encodeBehavior(message));
    }

    @Test
    void rejectsBehaviorWithoutAnalysisRunId() {
        BehaviorProcessingTaskMessage legacy = new BehaviorProcessingTaskMessage(
                "msg-1", "ts", "trace", 1L, 10L, 20L,
                DataSourceTypeEnum.FILE_UPLOAD, java.util.Collections.emptyList());

        assertThrows(IllegalArgumentException.class, () -> codec.encodeBehavior(legacy));
    }

    @Test
    void rejectsFileUploadWithoutScopedDocuments() {
        BehaviorProcessingTaskMessage message = new BehaviorProcessingTaskMessage(
                "msg-1", "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD, java.util.Collections.emptyList());

        assertThrows(IllegalArgumentException.class, () -> codec.encodeBehavior(message));
    }

    @Test
    void rejectsInvalidDocumentIdentity() {
        BehaviorProcessingTaskMessage message = new BehaviorProcessingTaskMessage(
                "msg-1", "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD, java.util.Collections.emptyList());
        message.setDocuments(java.util.Collections.singletonList(new SourceDocumentRef(101L, " ")));

        assertThrows(IllegalArgumentException.class, () -> codec.encodeBehavior(message));
    }

    @Test
    void rejectsEmptyOrCorruptPayload() {
        assertThrows(IllegalStateException.class, () -> codec.decodeBehavior(null));
        assertThrows(IllegalStateException.class, () -> codec.decodeBehavior("   "));
        assertThrows(IllegalStateException.class, () -> codec.decodeBehavior("not-json"));
        // 合法 JSON 但缺少消息身份：按结构校验拒绝
        assertThrows(IllegalArgumentException.class, () -> codec.decodeIndicator("{}"));
    }

    @Test
    void decodedPayloadKeepsTopicContract() {
        BehaviorProcessingTaskMessage message = BehaviorProcessingTaskMessage.forDocuments(
                "msg-1", "ts", "trace", 1L, 10L, 20L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD,
                java.util.Collections.singletonList(new SourceDocumentRef(101L, "source.pdf")));

        BehaviorProcessingTaskMessage decoded =
                codec.decodeBehavior(codec.encodeBehavior(message));

        assertEquals(message.getTopic(), decoded.getTopic());
    }
}
