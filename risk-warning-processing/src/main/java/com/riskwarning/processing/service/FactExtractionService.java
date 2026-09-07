package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.fact.ExtractedFact;
import com.riskwarning.common.dto.fact.FactExtractionCallMetadata;
import com.riskwarning.common.dto.fact.FactExtractionFailure;
import com.riskwarning.common.dto.fact.FactExtractionResult;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.provider.AiChatProvider;
import com.riskwarning.processing.fact.FactExtractionException;
import com.riskwarning.processing.fact.FactExtractionPromptRenderer;
import com.riskwarning.processing.fact.FactExtractionResponseValidator;
import com.riskwarning.processing.fact.FactExtractionValidationResult;
import com.riskwarning.processing.fact.StructuredBehaviorAssembler;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 从同一源文件的权威证据中抽取并装配 Structured Behavior。 */
@Service
public class FactExtractionService {

    static final int MAX_EVIDENCE_PER_BATCH = 40;
    static final int MAX_TEXT_CODE_POINTS_PER_BATCH = 20000;
    private static final int MODEL_VALIDATION_ATTEMPTS = 2;

    private final Optional<AiChatProvider> provider;
    private final FactExtractionPromptRenderer promptRenderer;
    private final FactExtractionResponseValidator responseValidator;
    private final StructuredBehaviorAssembler behaviorAssembler;

    public FactExtractionService(Optional<AiChatProvider> provider,
                                 FactExtractionPromptRenderer promptRenderer,
                                 FactExtractionResponseValidator responseValidator,
                                 StructuredBehaviorAssembler behaviorAssembler) {
        this.provider = provider;
        this.promptRenderer = promptRenderer;
        this.responseValidator = responseValidator;
        this.behaviorAssembler = behaviorAssembler;
    }

    /**
     * 稳定分批调用 Provider。Provider 失败直接上抛；模型响应连续两次不合法时，
     * 携带失败明细抛出 FactExtractionException，禁止返回部分成功结果。
     */
    public FactExtractionResult extract(AnalysisScope scope, List<EvidenceChunk> evidenceChunks) {
        long startedAt = System.currentTimeMillis();
        String modelId = requireModelId();
        List<List<EvidenceChunk>> batches = createBatches(scope, evidenceChunks);
        List<Behavior> behaviors = new ArrayList<>();
        List<FactExtractionFailure> failures = new ArrayList<>();
        int providerCallCount = 0;

        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            List<EvidenceChunk> batch = batches.get(batchIndex);
            Set<String> allowedEvidenceIds = new HashSet<>();
            for (EvidenceChunk chunk : batch) {
                allowedEvidenceIds.add(chunk.getId());
            }
            String prompt = promptRenderer.render(scope, batch);
            FactExtractionValidationResult validation = null;
            String rawResponse = null;
            for (int attempt = 1; attempt <= MODEL_VALIDATION_ATTEMPTS; attempt++) {
                rawResponse = provider().chat(prompt);
                providerCallCount++;
                validation = responseValidator.validate(rawResponse, allowedEvidenceIds);
                if (validation.isValid()) {
                    break;
                }
            }
            if (validation == null || !validation.isValid()) {
                failures.add(new FactExtractionFailure(batchIndex + 1,
                        validation == null ? Collections.singletonList("NO_RESPONSE")
                                : validation.errorCodes(), excerpt(rawResponse)));
                FactExtractionResult failedResult = new FactExtractionResult(
                        Collections.emptyList(), failures,
                        metadata(modelId, batches.size(), providerCallCount, startedAt));
                throw new FactExtractionException("事实抽取第 " + (batchIndex + 1)
                        + " 批响应连续两次未通过契约校验，错误码："
                        + failures.get(failures.size() - 1).getErrorCodes(), failedResult);
            }
            Long sourceDocumentId = batch.get(0).getSourceDocumentId();
            for (ExtractedFact fact : validation.getResponse().getFacts()) {
                behaviors.add(behaviorAssembler.assemble(scope, sourceDocumentId, fact,
                        modelId, LocalDateTime.now()));
            }
        }

        return new FactExtractionResult(behaviors, failures,
                metadata(modelId, batches.size(), providerCallCount, startedAt));
    }

    List<List<EvidenceChunk>> createBatches(AnalysisScope scope,
                                            List<EvidenceChunk> evidenceChunks) {
        if (scope == null || evidenceChunks == null) {
            throw new IllegalArgumentException("运行作用域和证据列表不能为空");
        }
        if (evidenceChunks.isEmpty()) {
            return Collections.emptyList();
        }
        Long sourceDocumentId = null;
        for (EvidenceChunk chunk : evidenceChunks) {
            if (chunk == null) {
                throw new IllegalArgumentException("证据列表不得包含 null");
            }
            chunk.verifyIntegrity();
            if (!scope.getProjectId().equals(chunk.getProjectId())
                    || !scope.getAssessmentId().equals(chunk.getAssessmentId())) {
                throw new IllegalArgumentException("EvidenceChunk 不属于当前项目或评估");
            }
            if (sourceDocumentId == null) {
                sourceDocumentId = chunk.getSourceDocumentId();
            } else if (!sourceDocumentId.equals(chunk.getSourceDocumentId())) {
                throw new IllegalArgumentException("单次 extract 只能处理同一源文件的证据");
            }
        }
        List<EvidenceChunk> sorted = new ArrayList<>(evidenceChunks);
        sorted.sort(Comparator
                .comparing(EvidenceChunk::getSourceDocumentId)
                .thenComparing(EvidenceChunk::getPageNumber,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(EvidenceChunk::getSegmentIndex)
                .thenComparing(EvidenceChunk::getId));
        List<List<EvidenceChunk>> batches = new ArrayList<>();
        List<EvidenceChunk> current = new ArrayList<>();
        int currentCodePoints = 0;
        for (EvidenceChunk chunk : sorted) {
            int textCodePoints = chunk.getText().codePointCount(0, chunk.getText().length());
            if (textCodePoints > MAX_TEXT_CODE_POINTS_PER_BATCH) {
                throw new IllegalArgumentException("单条 EvidenceChunk 超过 20000 字符，禁止静默截断");
            }
            if (!current.isEmpty() && (current.size() == MAX_EVIDENCE_PER_BATCH
                    || currentCodePoints + textCodePoints > MAX_TEXT_CODE_POINTS_PER_BATCH)) {
                batches.add(current);
                current = new ArrayList<>();
                currentCodePoints = 0;
            }
            current.add(chunk);
            currentCodePoints += textCodePoints;
        }
        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private String requireModelId() {
        String modelId = provider().modelId();
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalStateException("LLM Provider 未提供模型标识");
        }
        return modelId.trim();
    }

    private AiChatProvider provider() {
        return provider.orElseThrow(() -> new IllegalStateException(
                "事实抽取未启用：请配置 llm.enabled=true 及 Provider 参数"));
    }

    private FactExtractionCallMetadata metadata(String modelId, int batchCount,
                                                  int providerCallCount, long startedAt) {
        return new FactExtractionCallMetadata(modelId,
                StructuredBehaviorAssembler.PROMPT_VERSION, batchCount,
                providerCallCount, System.currentTimeMillis() - startedAt);
    }

    private String excerpt(String response) {
        if (response == null) {
            return "";
        }
        return response.length() <= 500 ? response : response.substring(0, 500) + "...(truncated)";
    }
}
