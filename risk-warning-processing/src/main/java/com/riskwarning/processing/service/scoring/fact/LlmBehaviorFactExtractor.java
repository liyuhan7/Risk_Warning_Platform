package com.riskwarning.processing.service.scoring.fact;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.scoring.*;
import com.riskwarning.common.dto.scoring.FactExtractionPlan.*;
import com.riskwarning.common.enums.analysis.AnalysisAttemptStatus;
import com.riskwarning.common.po.analysis.AnalysisAttempt;
import com.riskwarning.common.provider.*;
import com.riskwarning.processing.repository.AnalysisAttemptRepository;
import com.riskwarning.processing.service.ComplianceAuditWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/** 每组只修复一次缺漏；已有原文不足答案不再次询问。 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "analysis.rules", name = "v2-enabled", havingValue = "true")
public class LlmBehaviorFactExtractor implements BehaviorFactExtractor {
    private final Optional<AiChatProvider> provider;
    private final ComplianceAuditWriter audit;
    private final AnalysisAttemptRepository attempts;
    private final BehaviorFactResponseValidator validator = new BehaviorFactResponseValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Map<String, Answer> extract(AnalysisScope scope, FactExtractionPlan settings, BehaviorPlan behavior) {
        AiChatProvider chat = provider.orElseThrow(() -> new IllegalStateException("事实模型未启用"));
        if (!settings.getModelVersion().equals(chat.modelId()) || !BehaviorFactPlanner.PROMPT_VERSION.equals(settings.getPromptVersion()))
            throw new IllegalStateException("本次运行的事实模型或提示词版本不能变更");
        List<AnalysisAttempt> history = attempts.findByAnalysisRunIdAndBehaviorIdOrderByAttemptNoAsc(scope.getAnalysisRunId(), behavior.getBehaviorId());
        Map<String, Answer> results = new TreeMap<>();
        for (Batch batch : behavior.getBatches()) {
            List<Question> expected = behavior.getQuestions().stream().filter(question -> batch.getQuestionIds().contains(question.getId())).collect(Collectors.toList());
            String prompt = batch.getPrompt();
            for (int phase = 0; phase < 2 && !expected.isEmpty(); phase++) {
                String key = ScoringJson.hash(Arrays.asList(batch.getContextHash(), phase, prompt));
                Optional<AnalysisAttempt> cached = history.stream().filter(item -> scope.getProjectId().equals(item.getProjectId())
                        && scope.getAssessmentId().equals(item.getAssessmentId()) && key.equals(item.getContextHash())
                        && settings.getPromptVersion().equals(item.getPromptVersion()) && settings.getModelVersion().equals(item.getModelVersion())
                        && item.getRawResponse() != null && item.getStatus() != AnalysisAttemptStatus.PROVIDER_FAILED).findFirst();
                long started = System.nanoTime(); String raw;
                if (cached.isPresent()) raw = cached.get().getRawResponse();
                else {
                    try { raw = chat.chat(prompt); }
                    catch (LlmProviderException failure) {
                        audit.attempt(scope, behavior.getBehaviorId(), key, chat.modelId(), settings.getPromptVersion(), null,
                                AnalysisAttemptStatus.PROVIDER_FAILED, Collections.emptyList(), "LLM_PROVIDER_FAILED", failure.getMessage());
                        throw failure;
                    }
                }
                Map<String, Answer> valid = validator.validate(raw, expected, behavior.getEvidenceReferences().keySet());
                results.putAll(valid);
                List<Question> missing = expected.stream().filter(question -> !valid.containsKey(question.getId())).collect(Collectors.toList());
                if (!cached.isPresent()) audit.attempt(scope, behavior.getBehaviorId(), key, chat.modelId(), settings.getPromptVersion(), raw,
                        missing.isEmpty() ? AnalysisAttemptStatus.VALID : AnalysisAttemptStatus.INVALID,
                        missing.stream().map(Question::getId).collect(Collectors.toList()), missing.isEmpty() ? null : "INVALID_FACT_ANSWERS",
                        missing.isEmpty() ? null : "部分问题漏答或契约无效，已保留有效事实");
                log.info("[BehaviorFacts] run={} behavior={} batch={} phase={} questions={} valid={} missing={} reused={} inputCodePoints={} outputCodePoints={} elapsedMs={}",
                        scope.getAnalysisRunId(), behavior.getBehaviorId(), batch.getNumber(), phase, expected.size(), valid.size(), missing.size(), cached.isPresent(),
                        BehaviorFactPlanner.codePoints(prompt), raw == null ? 0 : BehaviorFactPlanner.codePoints(raw), (System.nanoTime() - started) / 1000000);
                expected = missing;
                if (phase == 0 && !missing.isEmpty()) prompt = repair(batch, missing);
                if (BehaviorFactPlanner.codePoints(prompt) > settings.getMaxInputCodePoints()) break;
            }
        }
        for (Question question : behavior.getQuestions()) if (!results.containsKey(question.getId())) {
            Answer unknown = new Answer(); unknown.setQuestionId(question.getId()); unknown.setStatus("UNCERTAIN");
            unknown.setReason(behavior.getOversizedQuestionIds().contains(question.getId()) ? "单个问题所需原文超过请求预算" : "问题修复后仍无有效答案");
            results.put(question.getId(), unknown);
        }
        return results;
    }

    private String repair(Batch batch, List<Question> missing) {
        try {
            ObjectNode input = (ObjectNode)mapper.readTree(batch.getPrompt().substring(BehaviorFactPlanner.INSTRUCTIONS.length()));
            Set<String> ids = missing.stream().map(Question::getId).collect(Collectors.toSet()); ArrayNode questions = mapper.createArrayNode();
            for (JsonNode question : input.path("questions")) if (ids.contains(question.path("id").asText())) questions.add(question);
            input.set("questions", questions);
            return BehaviorFactPlanner.INSTRUCTIONS + "仅修复这些漏答或无效问题，返回完整规定字段。\n" + mapper.writeValueAsString(input);
        } catch (Exception error) { throw new IllegalStateException("无法构造事实修复请求", error); }
    }
}
