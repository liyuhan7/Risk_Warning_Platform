package com.riskwarning.processing.service;

import com.fasterxml.jackson.databind.*;
import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.provider.*;
import com.riskwarning.processing.analysis.*;
import com.riskwarning.processing.dto.analysis.*;
import com.riskwarning.processing.repository.*;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** 逐事实合规分析；合法结构化结果交给规则引擎，不决定分数或风险。 */
@Service
public class ComplianceAnalysisService {
    private final Optional<AiChatProvider> provider;
    private final AnalysisContextService contexts;
    private final AnalysisRunRepository runs;
    private final EvidenceChunkRepository evidence;
    private final CompliancePromptRenderer prompts;
    private final ComplianceResponseValidator validator;
    private final ComplianceAuditWriter writer;
    private final ObjectMapper mapper = new ObjectMapper();

    public ComplianceAnalysisService(Optional<AiChatProvider> provider, AnalysisContextService contexts,
            AnalysisRunRepository runs, EvidenceChunkRepository evidence, CompliancePromptRenderer prompts,
            ComplianceResponseValidator validator, ComplianceAuditWriter writer) {
        this.provider = provider; this.contexts = contexts; this.runs = runs; this.evidence = evidence;
        this.prompts = prompts; this.validator = validator; this.writer = writer;
    }

    /**
     * 只消费当前 RUNNING Run 内 ready 的事实。规则输入要求由受控注册表提供。
     * 首次非法响应只修复一次；Provider 内部重试耗尽后保留审计并向编排层上抛。
     */
    public ComplianceAnalysisOutcome analyze(AnalysisScope scope, String behaviorId,
            Map<String, RuleInputRequirement> requirements, Map<String, String> background) {
        if (scope == null || !hasText(behaviorId)) throw new IllegalArgumentException("分析作用域和事实 ID 必填");
        AnalysisRun run = runs.findByAnalysisRunIdAndAssessmentIdAndProjectId(scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new IllegalArgumentException("当前分析运行不存在"));
        if (run.getStatus() != AnalysisRunStatus.RUNNING) throw new IllegalStateException("只允许分析新的 RUNNING Run");
        AnalysisContextVO all = contexts.context(scope.getAssessmentId(), scope.getProjectId(), scope.getAnalysisRunId());
        if (!scope.getAnalysisRunId().equals(all.getAnalysisRunId()) || !scope.getProjectId().equals(all.getProjectId())
                || !scope.getAssessmentId().equals(all.getAssessmentId()) || !"RUNNING".equals(all.getRunStatus()))
            throw new IllegalStateException("分析上下文与当前运行不匹配");
        List<BehaviorAnalysisContextVO> matched = all.getItems() == null ? Collections.emptyList() : all.getItems().stream()
                .filter(b -> behaviorId.equals(b.getBehaviorId())).collect(Collectors.toList());
        if (matched.size() != 1) throw new IllegalArgumentException("当前运行必须有唯一的目标事实");
        BehaviorAnalysisContextVO context = matched.get(0);
        if (!context.isReadyForAnalysis() || !context.isEvidenceComplete() || !context.isSnapshotAvailable())
            throw new IllegalStateException("事实未通过分析门禁: " + context.getBlockedReason());
        if (context.getEvidenceIds() == null || context.getEvidenceIds().isEmpty() || context.getSourceDocumentId() == null
                || !hasText(context.getEmbeddingModel()) || !hasText(context.getRetrievalVersion()))
            throw new IllegalArgumentException("原文引用、来源或检索版本缺失");
        if (!"SUCCESS".equals(context.getRetrievalStatus()) || !"WAITING_ANALYSIS".equals(context.getAnalysisStatus()))
            throw new IllegalStateException("事实不处于检索成功且等待分析状态");
        validateCandidates(scope, context, context.getIndicatorCandidates(), "INDICATOR");
        validateCandidates(scope, context, context.getRegulationCandidates(), "REGULATION");
        if (!hasSnapshot(context.getIndicatorCandidates()) && !hasSnapshot(context.getRegulationCandidates()))
            throw new IllegalArgumentException("当前事实没有可用候选快照");
        Set<String> candidateIds = context.getIndicatorCandidates() == null ? Collections.emptySet() : context.getIndicatorCandidates()
                .stream().map(RetrievalCandidateVO::getCandidateId).collect(Collectors.toSet());
        Map<String, RuleInputRequirement> rules = requirements == null ? Collections.emptyMap() : new TreeMap<>(requirements);
        for (Map.Entry<String, RuleInputRequirement> entry : rules.entrySet()) {
            RuleInputRequirement rule = entry.getValue();
            if (!candidateIds.contains(entry.getKey()) || rule == null || !Arrays.asList("BINARY", "RANGE").contains(rule.getRuleType())
                    || "BINARY".equals(rule.getRuleType()) && !hasText(rule.getCondition())
                    || "RANGE".equals(rule.getRuleType()) && (!hasText(rule.getInputUnit()) || !hasText(rule.getObservation())))
                throw new IllegalArgumentException("受控输入要求不属于当前候选或配置不完整");
        }
        List<EvidenceChunk> chunks = evidence.findByIdInOrderBySourceDocumentIdAscPageNumberAscSegmentIndexAsc(context.getEvidenceIds());
        validateEvidence(scope, context, chunks);
        AiChatProvider chat = provider.orElseThrow(() -> new IllegalStateException("LLM Provider 未启用"));
        String model = chat.modelId();
        if (!hasText(model)) throw new IllegalStateException("LLM 模型版本缺失");
        String contextJson = prompts.contextJson(scope, context, chunks, rules, background);
        String hash = EvidenceChunk.sha256(contextJson);
        String original = prompts.render(contextJson);
        String prompt = original;
        for (int attempt = 1; attempt <= 2; attempt++) {
            String raw;
            try { raw = chat.chat(prompt); }
            catch (LlmProviderException failure) {
                writer.attempt(scope, behaviorId, hash, model, CompliancePromptRenderer.VERSION, null,
                        AnalysisAttemptStatus.PROVIDER_FAILED, Collections.emptyList(), "LLM_PROVIDER_FAILED", failure.getMessage());
                throw failure;
            }
            ComplianceValidation checked = validator.validate(raw, context, rules);
            writer.attempt(scope, behaviorId, hash, model, CompliancePromptRenderer.VERSION, raw,
                    checked.isValid() ? AnalysisAttemptStatus.VALID : AnalysisAttemptStatus.INVALID,
                    checked.getErrors(), checked.isValid() ? null : "INVALID_MODEL_RESPONSE",
                    checked.isValid() ? null : String.join("; ", checked.getErrors()));
            if (checked.isValid()) {
                JsonNode response = checked.getResponse();
                boolean selected = response.hasNonNull("indicatorId");
                AnalysisResult result = selected ? assemble(scope, context, response, model) : null;
                AnalysisAuditStatus status = selected ? AnalysisAuditStatus.ANALYZED : AnalysisAuditStatus.NO_APPLICABLE_CANDIDATE;
                if (selected && result.getComplianceStatus() == ComplianceStatus.INSUFFICIENT_EVIDENCE)
                    status = AnalysisAuditStatus.INSUFFICIENT_EVIDENCE;
                if (selected && result.getComplianceStatus() == ComplianceStatus.NEEDS_REVIEW)
                    status = AnalysisAuditStatus.NEEDS_REVIEW;
                writer.complete(scope, behaviorId, status, result, null, null);
                return new ComplianceAnalysisOutcome(status, result);
            }
            if (attempt == 1) prompt = prompts.repair(original, raw, checked.getErrors());
            else {
                writer.complete(scope, behaviorId, AnalysisAuditStatus.NEEDS_REVIEW, null,
                        "MODEL_REPAIR_EXHAUSTED", String.join("; ", checked.getErrors()));
                return new ComplianceAnalysisOutcome(AnalysisAuditStatus.NEEDS_REVIEW, null);
            }
        }
        throw new IllegalStateException("分析调用次数越界");
    }

    private AnalysisResult assemble(AnalysisScope scope, BehaviorAnalysisContextVO context, JsonNode response, String model) {
        try {
            AnalysisResult result = mapper.treeToValue(response, AnalysisResult.class);
            result.setId(EvidenceChunk.sha256(scope.getAnalysisRunId() + "|" + context.getBehaviorId() + "|" + result.getIndicatorId()));
            result.setAnalysisRunId(scope.getAnalysisRunId()); result.setAssessmentId(scope.getAssessmentId());
            result.setBehaviorId(context.getBehaviorId()); result.setModelVersion(model);
            result.setPromptVersion(CompliancePromptRenderer.VERSION); result.setAnalyzedAt(LocalDateTime.now());
            return result;
        } catch (Exception e) { throw new IllegalStateException("合法分析结果装配失败", e); }
    }

    private void validateEvidence(AnalysisScope scope, BehaviorAnalysisContextVO context, List<EvidenceChunk> chunks) {
        if (context.getEvidenceIds() == null || context.getEvidenceIds().isEmpty() || chunks == null
                || new HashSet<>(context.getEvidenceIds()).size() != context.getEvidenceIds().size())
            throw new IllegalArgumentException("事实证据引用为空或重复");
        Set<String> found = new HashSet<>();
        for (EvidenceChunk chunk : chunks) {
            chunk.verifyIntegrity();
            if (!scope.getProjectId().equals(chunk.getProjectId()) || !scope.getAssessmentId().equals(chunk.getAssessmentId())
                    || !Objects.equals(context.getSourceDocumentId(), chunk.getSourceDocumentId()) || !found.add(chunk.getId()))
                throw new IllegalArgumentException("原文证据归属或唯一性校验失败");
        }
        if (!found.equals(new HashSet<>(context.getEvidenceIds()))) throw new IllegalArgumentException("事实引用的原文证据不完整");
    }

    private void validateCandidates(AnalysisScope scope, BehaviorAnalysisContextVO context, List<RetrievalCandidateVO> candidates, String type) {
        Set<String> ids = new HashSet<>();
        if (candidates == null) return;
        for (RetrievalCandidateVO candidate : candidates) {
            if (candidate == null || !hasText(candidate.getCandidateId()) || !ids.add(candidate.getCandidateId())
                    || !type.equals(candidate.getCandidateType()) || !context.getBehaviorId().equals(candidate.getBehaviorId())
                    || !scope.getAnalysisRunId().equals(candidate.getAnalysisRunId()) || !scope.getAssessmentId().equals(candidate.getAssessmentId())
                    || !Objects.equals(context.getEmbeddingModel(), candidate.getEmbeddingModel())
                    || !Objects.equals(context.getRetrievalVersion(), candidate.getEmbeddingVersion()))
                throw new IllegalArgumentException("候选快照跨事实、运行或检索版本");
        }
    }
    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
    private static boolean hasSnapshot(List<RetrievalCandidateVO> candidates) {
        return candidates != null && candidates.stream().anyMatch(RetrievalCandidateVO::isSnapshotAvailable);
    }
}
