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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals("fact-extract-v1.2", result.getMetadata().getPromptVersion());
        assertTrue(result.getFailures().isEmpty());
        assertEquals(Long.valueOf(101L), result.getBehaviors().get(0).getSourceDocumentId());
        assertEquals("1.0", result.getBehaviors().get(0).getSchemaVersion());
        assertEquals(Collections.singletonList(chunk(101L, 10L, 20L, 0, "证据 0").getId()),
                result.getBehaviors().get(0).getEvidenceIds());
        assertEquals(Collections.singletonList(chunk(101L, 10L, 20L, 40, "证据 40").getId()),
                result.getBehaviors().get(1).getEvidenceIds());
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
    void retriesWithDateFieldFeedbackAndPreservesAllFactsAndOriginalPeriod() {
        List<String> prompts = new ArrayList<>();
        AiChatProvider provider = new AiChatProvider() {
            @Override
            public String chat(String prompt) {
                prompts.add(prompt);
                Matcher matcher = PromptDrivenProvider.FIRST_ID.matcher(prompt);
                assertTrue(matcher.find());
                String evidenceId = matcher.group(1);
                boolean corrected = prompt.contains("facts[1].behaviorDate")
                        && prompt.contains("省略 behaviorDate");
                String dated = "{\"subject\":\"甲公司\",\"action\":\"支付工资\","
                        + "\"status\":\"COMPLETED\",\"behaviorDate\":\"2026-09-10\","
                        + "\"description\":\"甲公司2026年9月10日支付工资8000元\","
                        + "\"confidence\":0.95,\"evidenceIds\":[\"" + evidenceId + "\"]}";
                String monthly = "{\"subject\":\"乙公司\",\"action\":\"记录月度加班\","
                        + "\"status\":\"COMPLETED\","
                        + (corrected ? "" : "\"behaviorDate\":\"2026-09\",")
                        + "\"description\":\"乙公司2026年9月员工累计加班37小时\","
                        + "\"confidence\":0.95,\"evidenceIds\":[\"" + evidenceId + "\"]}";
                return "{\"facts\":[" + dated + "," + monthly + "]}";
            }

            @Override
            public String modelId() {
                return "date-feedback-model";
            }
        };

        FactExtractionResult result = service(provider).extract(
                new AnalysisScope(10L, 20L, "run-date"), Collections.singletonList(
                        chunk(101L, 10L, 20L, 0,
                                "甲公司2026年9月10日支付工资8000元。乙公司2026年9月员工累计加班37小时。")));

        assertEquals(2, prompts.size());
        assertEquals(2, result.getBehaviors().size());
        assertEquals(2, result.getMetadata().getProviderCallCount());
        assertEquals(LocalDateTime.of(2026, 9, 10, 0, 0), result.getBehaviors().get(0).getBehaviorDate());
        assertNull(result.getBehaviors().get(1).getBehaviorDate());
        assertTrue(result.getBehaviors().get(1).getDescription().contains("2026年9月"));
        assertTrue(result.getBehaviors().get(1).getDescription().contains("37小时"));
        assertTrue(prompts.get(1).contains("2026-09"));
    }

    @Test
    void reportsExactDateFieldWhenRepairStillFails() {
        AiChatProvider provider = new AiChatProvider() {
            @Override
            public String chat(String prompt) {
                Matcher matcher = PromptDrivenProvider.FIRST_ID.matcher(prompt);
                assertTrue(matcher.find());
                return "{\"facts\":[{\"subject\":\"甲公司\",\"action\":\"记录工时\","
                        + "\"status\":\"COMPLETED\",\"behaviorDate\":\"2026-09\","
                        + "\"description\":\"甲公司记录2026年9月工时\",\"confidence\":0.9,"
                        + "\"evidenceIds\":[\"" + matcher.group(1) + "\"]}]}";
            }

            @Override
            public String modelId() {
                return "invalid-date-model";
            }
        };
        FactExtractionException failure = assertThrows(FactExtractionException.class,
                () -> service(provider).extract(new AnalysisScope(10L, 20L, "run-date"),
                        Collections.singletonList(chunk(101L, 10L, 20L, 0, "甲公司记录2026年9月工时"))));

        assertTrue(failure.getMessage().contains("facts[0].behaviorDate"), failure.getMessage());
        assertTrue(failure.getMessage().contains("yyyy-MM-dd"), failure.getMessage());
        assertEquals(2, failure.getResult().getMetadata().getProviderCallCount());
        assertTrue(failure.getResult().getBehaviors().isEmpty());
    }

    @Test
    void resolvesShortReferencesToExactOriginalEvidenceIds() {
        EvidenceChunk first = chunk(101L, 10L, 20L, 0, "甲公司提交工资明细");
        EvidenceChunk second = chunk(101L, 10L, 20L, 1, "乙公司核对工资明细");
        String prompt = new FactExtractionPromptRenderer().render(
                new AnalysisScope(10L, 20L, "run-reference"), Arrays.asList(first, second));
        assertTrue(prompt.contains("id: E-1\n"));
        assertTrue(prompt.contains("id: E-2\n"));
        assertFalse(prompt.contains(first.getId()));
        assertFalse(prompt.contains(second.getId()));
        assertFalse(prompt.contains("不得填写 E-n"));
        AiChatProvider provider = referenceProvider("E-2", "E-1", "E-2");

        FactExtractionResult result = service(provider).extract(
                new AnalysisScope(10L, 20L, "run-reference"), Arrays.asList(second, first));

        assertEquals(2, result.getBehaviors().size());
        assertEquals(Collections.singletonList(second.getId()), result.getBehaviors().get(0).getEvidenceIds());
        assertEquals(Arrays.asList(first.getId(), second.getId()), result.getBehaviors().get(1).getEvidenceIds());
        assertEquals(1, result.getMetadata().getProviderCallCount());
    }

    @Test
    void unknownReferenceRetryListsAllowedIdsAndMapsRepairedReference() {
        List<String> prompts = new ArrayList<>();
        EvidenceChunk evidence = chunk(101L, 10L, 20L, 0, "乙公司核对工资明细");
        AiChatProvider provider = new AiChatProvider() {
            @Override public String chat(String prompt) {
                prompts.add(prompt);
                boolean repaired = prompt.contains("本批允许的 evidenceIds：[E-1]");
                return referenceFact("乙公司", repaired ? "E-1" : "E-999");
            }
            @Override public String modelId() { return "reference-repair-model"; }
        };

        FactExtractionResult result = service(provider).extract(
                new AnalysisScope(10L, 20L, "run-reference"), Collections.singletonList(evidence));

        assertEquals(2, prompts.size());
        assertTrue(prompts.get(1).contains("facts[0].evidenceIds"));
        assertEquals(Collections.singletonList(evidence.getId()), result.getBehaviors().get(0).getEvidenceIds());
    }

    @Test
    void refusesReferenceThatOnlyExistsInPreviousBatch() {
        List<EvidenceChunk> chunks = new ArrayList<>();
        for (int index = 0; index < 41; index++) {
            chunks.add(chunk(101L, 10L, 20L, index, "公司" + index + "核对工资明细"));
        }
        AiChatProvider provider = new AiChatProvider() {
            @Override public String chat(String prompt) { return referenceFact("甲公司", "E-2"); }
            @Override public String modelId() { return "out-of-batch-model"; }
        };

        FactExtractionException failure = assertThrows(FactExtractionException.class,
                () -> service(provider).extract(new AnalysisScope(10L, 20L, "run-reference"), chunks));

        assertEquals(2, failure.getResult().getFailures().get(0).getBatchIndex());
        assertTrue(failure.getResult().getFailures().get(0).getErrorCodes().contains("UNKNOWN_REFERENCE"));
        assertTrue(failure.getResult().getBehaviors().isEmpty());
    }

    private AiChatProvider referenceProvider(String firstId, String... secondIds) {
        return new AiChatProvider() {
            @Override public String chat(String prompt) {
                String first = referenceFact("乙公司", firstId);
                String second = referenceFact("甲乙公司", secondIds);
                return "{\"facts\":[" + first.substring(10, first.length() - 2)
                        + "," + second.substring(10, second.length() - 2) + "]}";
            }
            @Override public String modelId() { return "short-reference-model"; }
        };
    }

    private String referenceFact(String subject, String... ids) {
        StringBuilder refs = new StringBuilder();
        for (String id : ids) {
            if (refs.length() > 0) refs.append(',');
            refs.append('"').append(id).append('"');
        }
        return "{\"facts\":[{\"subject\":\"" + subject + "\",\"action\":\"核对工资明细\","
                + "\"status\":\"COMPLETED\",\"description\":\"" + subject + "核对工资明细\","
                + "\"confidence\":0.95,\"evidenceIds\":[" + refs + "]}]}";
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
    void splitsBatchesByTextBudgetSoModelOutputStaysWithinProviderLimit() {
        // 批次正文上限决定模型输出长度：证据块变大后必须收紧，否则响应会被 max_tokens 截断
        FactExtractionService service = service(new PromptDrivenProvider(false));
        List<EvidenceChunk> chunks = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            chunks.add(chunk(101L, 10L, 20L, index, repeat("证据", 450)));
        }

        List<List<EvidenceChunk>> batches = service.createBatches(
                new AnalysisScope(10L, 20L, "run-budget"), chunks);

        assertEquals(3, batches.size(), "900 字/条应切成 4+4+2 三批");
        for (List<EvidenceChunk> batch : batches) {
            int codePoints = 0;
            for (EvidenceChunk item : batch) {
                codePoints += item.getText().codePointCount(0, item.getText().length());
            }
            assertTrue(codePoints <= FactExtractionService.MAX_TEXT_CODE_POINTS_PER_BATCH,
                    "单批证据总长不得超过批次上限，否则模型输出会被截断");
        }
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
        private static final Pattern FIRST_ID = Pattern.compile("(?m)^id: (E-[1-9][0-9]*)$");
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
            Matcher textMatcher = Pattern.compile("(?m)^text: (.+)$").matcher(prompt);
            assertTrue(textMatcher.find());
            String status = invalid ? "DONE" : "COMPLETED";
            return "{\"facts\":[{\"subject\":\"本公司\",\"action\":\"提交材料\","
                    + "\"status\":\"" + status + "\",\"description\":\"提交材料 "
                    + textMatcher.group(1) + "\",\"confidence\":0.9,\"evidenceIds\":[\""
                    + evidenceId + "\"]}]}";
        }

        @Override
        public String modelId() {
            return "stub-model";
        }
    }
}
