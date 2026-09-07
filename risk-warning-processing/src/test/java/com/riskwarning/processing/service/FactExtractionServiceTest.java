package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.fact.FactExtractionResult;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.provider.AiChatProvider;
import com.riskwarning.processing.fact.FactExtractionException;
import com.riskwarning.processing.fact.FactExtractionPromptRenderer;
import com.riskwarning.processing.fact.FactExtractionResponseValidator;
import com.riskwarning.processing.fact.StructuredBehaviorAssembler;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactExtractionServiceTest {

    @Test
    void sortsBatchesAndReturnsAuditableBehaviors() {
        PromptDrivenProvider provider = new PromptDrivenProvider(false);
        FactExtractionService service = service(provider);
        List<EvidenceChunk> chunks = new ArrayList<>();
        for (int index = 40; index >= 0; index--) {
            chunks.add(chunk(101L, 10L, 20L, index, "证据 " + index));
        }

        FactExtractionResult result = service.extract(
                new AnalysisScope(10L, 20L, "run-1"), chunks);

        assertEquals(2, result.getBehaviors().size());
        assertEquals(2, result.getMetadata().getBatchCount());
        assertEquals(2, result.getMetadata().getProviderCallCount());
        assertEquals("stub-model", result.getMetadata().getModelId());
        assertEquals("fact-extract-v1.0", result.getMetadata().getPromptVersion());
        assertTrue(result.getFailures().isEmpty());
        assertEquals(Long.valueOf(101L), result.getBehaviors().get(0).getSourceDocumentId());
        assertEquals("1.0", result.getBehaviors().get(0).getSchemaVersion());
    }

    @Test
    void retriesInvalidModelOutputOnceThenFailsWithoutPartialResults() {
        PromptDrivenProvider provider = new PromptDrivenProvider(true);
        FactExtractionService service = service(provider);

        FactExtractionException failure = assertThrows(FactExtractionException.class,
                () -> service.extract(new AnalysisScope(10L, 20L, "run-1"),
                        Collections.singletonList(chunk(101L, 10L, 20L, 0, "证据"))));

        assertEquals(2, failure.getResult().getMetadata().getProviderCallCount());
        assertTrue(failure.getResult().getBehaviors().isEmpty());
        assertEquals(1, failure.getResult().getFailures().size());
        assertTrue(failure.getResult().getFailures().get(0).getErrorCodes().contains("BAD_ENUM"));
    }

    @Test
    void rejectsCrossScopeAndOversizedEvidenceBeforeProviderCall() {
        PromptDrivenProvider provider = new PromptDrivenProvider(false);
        FactExtractionService service = service(provider);
        AnalysisScope scope = new AnalysisScope(10L, 20L, "run-1");

        assertThrows(IllegalArgumentException.class, () -> service.extract(scope,
                Collections.singletonList(chunk(101L, 10L, 99L, 0, "错误评估"))));
        assertThrows(IllegalArgumentException.class, () -> service.extract(scope,
                Collections.singletonList(chunk(101L, 10L, 20L, 0,
                        repeat("长", 20001)))));
        assertEquals(0, provider.calls);
    }

    @Test
    void emptyEvidenceProducesAuditableEmptyResultWithoutCallingProvider() {
        PromptDrivenProvider provider = new PromptDrivenProvider(false);

        FactExtractionResult result = service(provider).extract(
                new AnalysisScope(10L, 20L, "run-1"), Collections.emptyList());

        assertTrue(result.getBehaviors().isEmpty());
        assertEquals(0, result.getMetadata().getBatchCount());
        assertEquals(0, result.getMetadata().getProviderCallCount());
        assertEquals(0, provider.calls);
    }

    private FactExtractionService service(AiChatProvider provider) {
        return new FactExtractionService(Optional.of(provider), new FactExtractionPromptRenderer(),
                new FactExtractionResponseValidator(new ObjectMapper()),
                new StructuredBehaviorAssembler());
    }

    private EvidenceChunk chunk(Long sourceDocumentId, Long projectId, Long assessmentId,
                                int segmentIndex, String text) {
        return EvidenceChunk.create(sourceDocumentId, projectId, assessmentId,
                "source.pdf", 1, segmentIndex, null, null, text, LocalDateTime.now());
    }

    private String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static class PromptDrivenProvider implements AiChatProvider {
        private static final Pattern FIRST_ID = Pattern.compile("(?m)^id: ([a-f0-9]{32})$");
        private final boolean invalid;
        private int calls;

        private PromptDrivenProvider(boolean invalid) {
            this.invalid = invalid;
        }

        @Override
        public String chat(String prompt) {
            calls++;
            Matcher matcher = FIRST_ID.matcher(prompt);
            if (!matcher.find()) {
                throw new AssertionError("Prompt 未包含 Evidence ID");
            }
            String evidenceId = matcher.group(1);
            String status = invalid ? "DONE" : "COMPLETED";
            return "{\"facts\":[{\"subject\":\"本公司\",\"action\":\"提交材料\","
                    + "\"status\":\"" + status + "\",\"description\":\"提交材料 "
                    + evidenceId + "\",\"confidence\":0.9,\"evidenceIds\":[\""
                    + evidenceId + "\"]}]}";
        }

        @Override
        public String modelId() {
            return "stub-model";
        }
    }
}
