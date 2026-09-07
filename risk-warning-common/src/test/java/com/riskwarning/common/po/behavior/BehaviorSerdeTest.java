package com.riskwarning.common.po.behavior;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BehaviorSerdeTest {

    @Test
    void retainsNewScopeEvidenceAndExtractionFieldsAfterJsonRoundTrip() {
        Behavior behavior = Behavior.builder()
                .id("behavior-1")
                .projectId(10L)
                .assessmentId(20L)
                .analysisRunId("run-1")
                .sourceDocumentId(101L)
                .description("企业保存培训记录")
                .subject("企业")
                .action("保存")
                .object("培训记录")
                .quantitativeData(12.0)
                .quantitativeUnit("份")
                .confidence(0.91D)
                .evidenceIds(Arrays.asList("evidence-1", "evidence-2"))
                .extractionModel("model-v1")
                .extractionPromptVersion("prompt-v1")
                .build();

        Behavior restored = JSON.parseObject(JSON.toJSONString(behavior), Behavior.class);

        assertEquals(20L, restored.getAssessmentId());
        assertEquals("run-1", restored.getAnalysisRunId());
        assertEquals(101L, restored.getSourceDocumentId());
        assertEquals("企业", restored.getSubject());
        assertEquals("保存", restored.getAction());
        assertEquals("培训记录", restored.getObject());
        assertEquals("份", restored.getQuantitativeUnit());
        assertEquals(0.91D, restored.getConfidence());
        assertEquals(Arrays.asList("evidence-1", "evidence-2"), restored.getEvidenceIds());
        assertEquals("model-v1", restored.getExtractionModel());
        assertEquals("prompt-v1", restored.getExtractionPromptVersion());
    }

    @Test
    void parsesLegacyDocumentWithoutNewFields() {
        Behavior restored = JSON.parseObject(
                "{\"id\":\"legacy-1\",\"projectId\":10,\"description\":\"旧行为\",\"status\":\"\"}",
                Behavior.class);

        assertEquals("legacy-1", restored.getId());
        assertEquals(10L, restored.getProjectId());
        assertNull(restored.getAssessmentId());
        assertNull(restored.getAnalysisRunId());
        assertNull(restored.getSourceDocumentId());
        assertNull(restored.getEvidenceIds());
        assertNull(restored.getConfidence());
    }
}
