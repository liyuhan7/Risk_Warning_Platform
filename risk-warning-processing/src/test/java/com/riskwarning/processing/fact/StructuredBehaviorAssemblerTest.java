package com.riskwarning.processing.fact;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.fact.ExtractedFact;
import com.riskwarning.common.po.behavior.Behavior;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StructuredBehaviorAssemblerTest {

    @Test
    void assemblesScopeAuditFieldsAndStableId() {
        ExtractedFact fact = new ExtractedFact(" 本公司 ", " 完成审查 ", null,
                "COMPLETED", "2025-11-30", 12D, " 家 ", " 完成 12 家审查。 ",
                0.95D, Collections.singletonList("ev-1"));
        AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 6, 10, 0);

        Behavior behavior = new StructuredBehaviorAssembler()
                .assemble(scope, 101L, fact, "model-a", createdAt);

        assertEquals("1.0", behavior.getSchemaVersion());
        assertEquals(StructuredBehaviorAssembler.stableBehaviorId(
                "run-1", 101L, "完成 12 家审查。"), behavior.getId());
        assertEquals(Long.valueOf(10L), behavior.getProjectId());
        assertEquals(Long.valueOf(20L), behavior.getAssessmentId());
        assertEquals("run-1", behavior.getAnalysisRunId());
        assertEquals(Long.valueOf(101L), behavior.getSourceDocumentId());
        assertEquals("本公司", behavior.getSubject());
        assertEquals("完成审查", behavior.getAction());
        assertNull(behavior.getObject());
        assertEquals(LocalDateTime.of(2025, 11, 30, 0, 0), behavior.getBehaviorDate());
        assertEquals("家", behavior.getQuantitativeUnit());
        assertEquals("model-a", behavior.getExtractionModel());
        assertEquals("fact-extract-v1.0", behavior.getExtractionPromptVersion());
        assertEquals(createdAt, behavior.getCreatedAt());
    }
}
