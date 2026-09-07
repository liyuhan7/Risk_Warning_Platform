package com.riskwarning.common.message;

import com.alibaba.fastjson2.JSON;
import com.riskwarning.common.enums.DataSourceTypeEnum;
import com.riskwarning.common.dto.analysis.SourceDocumentRef;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MessageAnalysisRunIdTest {

    @Test
    void preservesAnalysisRunIdAcrossJsonRoundTrip() {
        BehaviorProcessingTaskMessage message = new BehaviorProcessingTaskMessage(
                "message-1", "timestamp", "trace-1", 1L, 2L, 3L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD, Collections.singletonList("document.pdf"));

        BehaviorProcessingTaskMessage restored = JSON.parseObject(
                message.toJson(), BehaviorProcessingTaskMessage.class);

        assertEquals("run-1", restored.getAnalysisRunId());
        assertEquals(Long.valueOf(3L), restored.getAssessmentId());
        assertEquals(Collections.singletonList("document.pdf"), restored.getFilePaths());
    }

    @Test
    void stillDeserializesLegacyMessageWithoutAnalysisRunId() {
        String json = "{\"messageId\":\"old\",\"projectId\":2,\"assessmentId\":3,"
                + "\"type\":\"FILE_UPLOAD\",\"filePaths\":[]}";

        BehaviorProcessingTaskMessage restored = JSON.parseObject(
                json, BehaviorProcessingTaskMessage.class);

        assertNull(restored.getAnalysisRunId());
        assertEquals(Long.valueOf(3L), restored.getAssessmentId());
    }

    @Test
    void preservesScopedDocumentsAndLegacyPathsInJson() {
        BehaviorProcessingTaskMessage message = BehaviorProcessingTaskMessage.forDocuments(
                "message", "timestamp", "trace", 1L, 2L, 3L, "run-1",
                DataSourceTypeEnum.FILE_UPLOAD,
                Collections.singletonList(new SourceDocumentRef(11L, "document.pdf")));

        BehaviorProcessingTaskMessage restored = JSON.parseObject(
                message.toJson(), BehaviorProcessingTaskMessage.class);

        assertEquals(Long.valueOf(11L), restored.getDocuments().get(0).getSourceDocumentId());
        assertEquals("document.pdf", restored.getDocuments().get(0).getFilePath());
        assertEquals(Collections.singletonList("document.pdf"), restored.getFilePaths());
    }
}
