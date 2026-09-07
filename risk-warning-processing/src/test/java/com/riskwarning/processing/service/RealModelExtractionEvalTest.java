package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskwarning.common.config.LlmProviderProperties;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.fact.FactExtractionResult;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.provider.AiChatProvider;
import com.riskwarning.common.provider.OpenAiCompatibleChatProvider;
import com.riskwarning.processing.fact.FactExtractionException;
import com.riskwarning.processing.fact.FactExtractionPromptRenderer;
import com.riskwarning.processing.fact.FactExtractionResponseValidator;
import com.riskwarning.processing.fact.StructuredBehaviorAssembler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 DeepSeek 模型上的 P1-05 抽取测评：契约通过率、字段保真、两轮稳定性。
 *
 * 需要环境变量 LLM_ENABLED=true 与 LLM_BASE_URL/LLM_MODEL/LLM_API_KEY，缺失时整类跳过；
 * 稳定性阈值可用 P1_STABILITY_MIN（0~1，缺省 0.5）收紧为断言，缺省仅输出指标供决策。
 * 调用成本：约 8 次小请求。运行示例见 run-processing.ps1 的 .env 加载方式。
 */
class RealModelExtractionEvalTest {

    private static final AnalysisScope SCOPE = new AnalysisScope(10001L, 20001L, "eval-run-1");
    private static final long SOURCE_DOCUMENT_ID = 3001L;

    private static FactExtractionService service;
    private static boolean stabilityAsserted;
    private static double stabilityMin;
    private static boolean extraBodyActive;

    @BeforeAll
    static void setupProvider() {
        String enabled = System.getenv("LLM_ENABLED");
        String apiKey = System.getenv("LLM_API_KEY");
        String baseUrl = System.getenv("LLM_BASE_URL");
        String model = System.getenv("LLM_MODEL");
        String extraBody = System.getenv("LLM_EXTRA_BODY");
        assumeTrue("true".equalsIgnoreCase(enabled) && hasText(apiKey)
                        && hasText(baseUrl) && hasText(model),
                "RealModelExtractionEvalTest 需要 LLM_ENABLED=true 与 "
                        + "LLM_BASE_URL/LLM_MODEL/LLM_API_KEY 环境变量");

        LlmProviderProperties properties = new LlmProviderProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(baseUrl);
        properties.setModel(model);
        properties.setApiKey(apiKey);
        if (hasText(extraBody)) {
            properties.setExtraBody(extraBody);
            extraBodyActive = true;
        }
        AiChatProvider provider = new OpenAiCompatibleChatProvider(properties);
        service = new FactExtractionService(Optional.of(provider),
                new FactExtractionPromptRenderer(),
                new FactExtractionResponseValidator(new ObjectMapper()),
                new StructuredBehaviorAssembler());

        String stabilityEnv = System.getenv("P1_STABILITY_MIN");
        if (hasText(stabilityEnv)) {
            stabilityAsserted = true;
            stabilityMin = Double.parseDouble(stabilityEnv.trim());
        } else {
            stabilityAsserted = false;
            stabilityMin = 0.5;
        }
    }

    @Test
    void evaluateRealModelExtraction() {
        Map<String, List<Behavior>> firstRound = new HashMap<>();
        int contractFailures = 0;
        int validationRetries = 0;

        List<Case> cases = cases();
        for (Case current : cases) {
            CaseRun run1 = runOnce(current);
            CaseRun run2 = runOnce(current);
            firstRound.put(current.name, run1.behaviors);

            String contract = "pass";
            if (run1.failureCodes != null) {
                contract = "fail:" + String.join(",", run1.failureCodes);
                contractFailures++;
            } else if (run1.behaviors.isEmpty() && current.requireFact) {
                contract = "empty-facts-despite-extractable";
                contractFailures++;
            }
            if (run1.validationRetried || run2.validationRetried) {
                validationRetries++;
            }

            // 字段保真断言：核心数值/单位/状态必须原样保留（防止模型编造或丢值）
            if (current.fidelityCheck != null && run1.failureCodes == null) {
                boolean fidelity = current.fidelityCheck.check(run1.behaviors);
                assertTrue(fidelity,
                        "fidelity failed for " + current.name + " behaviors=" + describe(run1.behaviors));
            }

            String statuses = run1.behaviors.stream()
                    .map(Behavior::getStatus).collect(Collectors.joining(","));
            double stability = stabilityRatio(run1.behaviors, run2.behaviors);
            if (stabilityAsserted) {
                assertTrue(stability >= stabilityMin,
                        "stability below threshold for " + current.name
                                + " ratio=" + stability + " round1=" + describe(run1.behaviors)
                                + " round2=" + describe(run2.behaviors));
            }
            List<String> notes = new ArrayList<>();
            if (run1.failureCodes == null) {
                if (current.expectedStatus != null && run1.behaviors.stream()
                        .noneMatch(behavior -> current.expectedStatus.equals(behavior.getStatus()))) {
                    notes.add("missing-status:" + current.expectedStatus);
                }
                if (current.expectedDate != null && run1.behaviors.stream()
                        .noneMatch(behavior -> behavior.getBehaviorDate() != null
                                && current.expectedDate.equals(
                                behavior.getBehaviorDate().toLocalDate().toString()))) {
                    notes.add("missing-date:" + current.expectedDate);
                }
                if (current.expectEmpty && !run1.behaviors.isEmpty()) {
                    notes.add("over-extracted");
                }
            }
            System.out.println("[EVAL] case=" + current.name
                    + " contract=" + contract
                    + " facts_r1=" + run1.behaviors.size()
                    + " facts_r2=" + run2.behaviors.size()
                    + " statuses=" + statuses
                    + " stability=" + stability
                    + " provider_calls_r1=" + run1.providerCalls
                    + (run1.validationRetried ? " [retried]" : "")
                    + (notes.isEmpty() ? "" : " notes=" + String.join(",", notes)));
        }

        assertTrue(contractFailures == 0,
                "contract failures total=" + contractFailures);
        System.out.println("[EVAL] summary contract_failures=" + contractFailures
                + " validation_retries=" + validationRetries
                + " stability_asserted=" + stabilityAsserted
                + " extra_body_active=" + extraBodyActive);
    }

    private CaseRun runOnce(Case current) {
        EvidenceChunk chunk = EvidenceChunk.create(SOURCE_DOCUMENT_ID,
                SCOPE.getProjectId(), SCOPE.getAssessmentId(),
                "eval-source.pdf", 1, current.segmentIndex, null, null,
                current.text, LocalDateTime.now());
        try {
            FactExtractionResult result = service.extract(SCOPE, Collections.singletonList(chunk));
            boolean retried = result.getMetadata().getProviderCallCount()
                    > result.getMetadata().getBatchCount();
            return new CaseRun(result.getBehaviors(), null, retried,
                    result.getMetadata().getProviderCallCount());
        } catch (FactExtractionException exception) {
            List<String> codes = exception.getResult().getFailures().isEmpty()
                    ? Collections.singletonList("UNKNOWN")
                    : exception.getResult().getFailures().get(0).getErrorCodes();
            return new CaseRun(Collections.emptyList(), codes, false,
                    exception.getResult().getMetadata().getProviderCallCount());
        }
    }

    private List<Case> cases() {
        List<Case> result = new ArrayList<>();
        result.add(new Case("supplier-review", 0,
                "本公司于2025年11月30日完成对12家一级供应商的年度审查，全部审查项均已通过，未发现需要整改的事项。",
                true, "COMPLETED", "2025-11-30", false, behaviors -> behaviors.stream().anyMatch(behavior ->
                        "COMPLETED".equals(behavior.getStatus())
                                && (containsData(behavior, 12.0, "家")
                                || containsTokens(behavior.getDescription(), "12", "家")))));
        result.add(new Case("remedy-in-progress", 1,
                "3家供应商仍在补交环境许可证明，公司已书面要求其在2026年1月31日前完成补交。",
                true, "IN_PROGRESS", null, false, behaviors -> behaviors.stream().anyMatch(behavior ->
                        "IN_PROGRESS".equals(behavior.getStatus())
                                && containsTokens(behavior.getDescription(), "3", "许可"))));
        result.add(new Case("unknown-status-evidence", 2,
                "一份往来材料记载境外子公司曾向集团总部提交年度经营报告，材料未说明该报告是否已经完成或仍在编制。",
                true, "UNKNOWN", null, false, null));
        result.add(new Case("no-fact-probe", 3,
                "本页为材料目录：一、公司概况；二、组织架构；三、附录。",
                false, null, null, true, null));
        return result;
    }

    private boolean containsData(Behavior behavior, double value, String unit) {
        return behavior.getQuantitativeData() != null
                && Math.abs(behavior.getQuantitativeData() - value) < 0.0001
                && unit.equals(behavior.getQuantitativeUnit());
    }

    private boolean containsTokens(String text, String... tokens) {
        if (text == null) {
            return false;
        }
        for (String token : tokens) {
            if (!text.contains(token)) {
                return false;
            }
        }
        return true;
    }

    /** 两轮抽取的归一化描述多重集合一致性（0~1）。 */
    private double stabilityRatio(List<Behavior> first, List<Behavior> second) {
        if (first.isEmpty() && second.isEmpty()) {
            return 1.0;
        }
        Map<String, Integer> counts = new HashMap<>();
        for (Behavior behavior : first) {
            String key = EvidenceChunk.normalizeText(behavior.getDescription());
            counts.put(key, counts.getOrDefault(key, 0) + 1);
        }
        int matched = 0;
        for (Behavior behavior : second) {
            String key = EvidenceChunk.normalizeText(behavior.getDescription());
            Integer remaining = counts.get(key);
            if (remaining != null && remaining > 0) {
                counts.put(key, remaining - 1);
                matched++;
            }
        }
        int total = Math.max(first.size(), second.size());
        return total == 0 ? 1.0 : (double) matched / total;
    }

    private String describe(List<Behavior> behaviors) {
        return behaviors.stream()
                .map(behavior -> behavior.getStatus() + ":"
                        + truncate(behavior.getDescription(), 60))
                .collect(Collectors.joining(" | "));
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private interface FidelityCheck {
        boolean check(List<Behavior> behaviors);
    }

    private static final class Case {
        private final String name;
        private final int segmentIndex;
        private final String text;
        private final boolean requireFact;
        private final String expectedStatus;
        private final String expectedDate;
        private final boolean expectEmpty;
        private final FidelityCheck fidelityCheck;

        private Case(String name, int segmentIndex, String text,
                     boolean requireFact, String expectedStatus, String expectedDate,
                     boolean expectEmpty, FidelityCheck fidelityCheck) {
            this.name = name;
            this.segmentIndex = segmentIndex;
            this.text = text;
            this.requireFact = requireFact;
            this.expectedStatus = expectedStatus;
            this.expectedDate = expectedDate;
            this.expectEmpty = expectEmpty;
            this.fidelityCheck = fidelityCheck;
        }
    }

    private static final class CaseRun {
        private final List<Behavior> behaviors;
        private final List<String> failureCodes;
        private final boolean validationRetried;
        private final int providerCalls;

        private CaseRun(List<Behavior> behaviors, List<String> failureCodes,
                        boolean validationRetried, int providerCalls) {
            this.behaviors = behaviors;
            this.failureCodes = failureCodes;
            this.validationRetried = validationRetried;
            this.providerCalls = providerCalls;
        }
    }
}
