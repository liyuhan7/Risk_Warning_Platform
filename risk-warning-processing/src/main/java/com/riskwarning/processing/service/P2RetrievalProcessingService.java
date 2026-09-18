package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.retrieval.*;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.message.IndicatorCalculationTaskMessage;
import com.riskwarning.common.message.NotificationMessage;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.analysis.RetrievalAudit;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.evidence.EvidenceChunk;
import com.riskwarning.common.utils.KafkaUtils;
import com.riskwarning.common.utils.StringUtils;
import com.riskwarning.processing.client.RetrievalClient;
import com.riskwarning.processing.config.P2ProcessingProperties;
import com.riskwarning.processing.repository.AnalysisRunRepository;
import com.riskwarning.processing.repository.BehaviorDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/** 真实 P2 只保存证据与检索上下文，不生成合规判断或风险结果。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class P2RetrievalProcessingService {
    private final BehaviorDocumentRepository behaviors;
    private final EvidenceQueryService evidenceQuery;
    private final RetrievalClient retrievalClient;
    private final RetrievalAuditService auditService;
    private final AnalysisRunRepository runs;
    private final AnalysisRunNoDecisionService noDecision;
    private final BehaviorQueryTextBuilder queryBuilder;
    private final P2ProcessingProperties properties;
    private final KafkaUtils kafka;

    /** 校验三重作用域后逐批检索；已结束运行重投不重写历史快照。 */
    public void process(IndicatorCalculationTaskMessage message, AnalysisScope scope) {
        if (!Objects.equals(message.getProjectId(), scope.getProjectId())
                || !Objects.equals(message.getAssessmentId(), scope.getAssessmentId())
                || !Objects.equals(message.getAnalysisRunId(), scope.getAnalysisRunId())) {
            throw new IllegalArgumentException("消息与运行作用域不一致");
        }
        AnalysisRun run = runs.findByAnalysisRunIdAndAssessmentIdAndProjectId(
                scope.getAnalysisRunId(), scope.getAssessmentId(), scope.getProjectId())
                .orElseThrow(() -> new IllegalStateException("分析运行不存在"));
        if (run.getStatus() != AnalysisRunStatus.RUNNING) { return; }
        List<Behavior> scoped = new ArrayList<>(behaviors.findByScope(
                scope.getProjectId(), scope.getAssessmentId(), scope.getAnalysisRunId()));
        if (scoped.isEmpty()) { throw new IllegalStateException("当前运行没有 Behavior"); }
        if (properties.getBatchSize() <= 0) { throw new IllegalArgumentException("检索批次大小必须为正数"); }
        Set<String> ids = new HashSet<>();
        for (Behavior behavior : scoped) {
            if (!Objects.equals(scope.getProjectId(), behavior.getProjectId())
                    || !Objects.equals(scope.getAssessmentId(), behavior.getAssessmentId())
                    || !Objects.equals(scope.getAnalysisRunId(), behavior.getAnalysisRunId())
                    || !hasText(behavior.getId()) || !ids.add(behavior.getId())) {
                throw new IllegalStateException("Behavior 身份或作用域不一致");
            }
        }
        scoped.sort(Comparator.comparing(Behavior::getId));
        boolean failed = false;
        for (int from = 0; from < scoped.size(); from += properties.getBatchSize()) {
            List<Behavior> batch = scoped.subList(from, Math.min(scoped.size(), from + properties.getBatchSize()));
            List<RetrievalAudit> prepared;
            try {
                prepared = retrieveBatch(scope, batch);
            } catch (RuntimeException failure) {
                failed = true;
                prepared = new ArrayList<>();
                // 失败只影响当前批次，已完成批次的候选与审计不被全局异常覆盖。
                for (Behavior behavior : batch) {
                    RetrievalAudit audit = baseAudit(scope, behavior);
                    audit.setRetrievalStatus(RetrievalAuditStatus.FAILED);
                    audit.setAnalysisStatus(AnalysisAuditStatus.NOT_ATTEMPTED);
                    audit.setErrorCode("P2_RETRIEVAL_FAILED");
                    audit.setErrorType(failure.getClass().getSimpleName());
                    audit.setErrorMessage("证据校验或检索失败，请核查服务与作用域");
                    prepared.add(audit);
                }
            }
            // 持久化异常由外层运行失败处理器接管，不能冒充检索异常重写审计。
            for (RetrievalAudit audit : prepared) { auditService.save(audit); }
        }
        if (failed) { throw new IllegalStateException("当前运行存在失败的检索批次"); }
        noDecision.complete(scope);
        notifyCompleted(message, scope);
    }

    private List<RetrievalAudit> retrieveBatch(AnalysisScope scope, List<Behavior> batch) {
        Map<String, Boolean> evidenceAvailable = new HashMap<>();
        List<RetrievalRequest> requests = new ArrayList<>();
        for (Behavior behavior : batch) {
            String query = queryBuilder.buildQueryText(behavior);
            if (!hasText(query)) { throw new IllegalStateException("事实查询文本为空"); }
            evidenceAvailable.put(behavior.getId(), verifyEvidence(scope, behavior));
            requests.add(RetrievalRequest.builder().behaviorId(behavior.getId()).queryText(query)
                    .assessmentId(scope.getAssessmentId()).analysisRunId(scope.getAnalysisRunId())
                    .filter(RetrievalFilter.none()).build());
        }
        RetrievalBatchResponse response = retrievalClient.retrieve(RetrievalBatchRequest.builder()
                .scope(scope).requests(requests).build());
        if (response == null || response.getItems() == null || response.getItems().size() != batch.size()) {
            throw new IllegalStateException("检索响应数量不匹配");
        }
        Map<String, RetrievalBatchItem> items = new HashMap<>();
        for (RetrievalBatchItem item : response.getItems()) {
            if (item == null || !evidenceAvailable.containsKey(item.getBehaviorId())
                    || items.put(item.getBehaviorId(), item) != null || item.getStatus() == null) {
                throw new IllegalStateException("检索响应身份不匹配");
            }
        }
        List<RetrievalAudit> output = new ArrayList<>();
        for (Behavior behavior : batch) {
            RetrievalBatchItem item = items.get(behavior.getId());
            List<RetrievalCandidateSnapshot> candidates = item.getCandidates();
            if (item.getStatus() == RetrievalBatchStatus.NO_CANDIDATES
                    && candidates != null && !candidates.isEmpty()) {
                throw new IllegalStateException("无候选状态与快照冲突");
            }
            RetrievalAudit audit = baseAudit(scope, behavior);
            if (!hasText(item.getEmbeddingModel()) || !hasText(item.getEmbeddingVersion())) {
                throw new IllegalStateException("检索响应缺少模型版本");
            }
            audit.setEmbeddingModel(item.getEmbeddingModel());
            audit.setEmbeddingVersion(item.getEmbeddingVersion());
            Map<String, String> itemFilters = item.getMatchedFilters();
            if (itemFilters != null) {
                audit.setFilterEnabled(Boolean.parseBoolean(itemFilters.get("enabled")));
                audit.setFilterApplied(Boolean.parseBoolean(itemFilters.get("applied")));
                audit.setFilterExpression(itemFilters.getOrDefault("expression", "[]"));
            }
            audit.setCandidates(candidates == null ? null : new ArrayList<>(candidates));
            boolean snapshotAvailable = candidates != null;
            Map<RetrievalCandidateType, Integer> ranks = new EnumMap<>(RetrievalCandidateType.class);
            Set<String> candidateIds = new HashSet<>();
            for (RetrievalCandidateSnapshot candidate : candidates == null
                    ? Collections.<RetrievalCandidateSnapshot>emptyList() : candidates) {
                if (candidate == null || candidate.getResult() == null) { snapshotAvailable = false; continue; }
                RetrievalResult result = candidate.getResult();
                if (!Objects.equals(behavior.getId(), result.getBehaviorId())
                        || !Objects.equals(scope.getAssessmentId(), result.getAssessmentId())
                        || !Objects.equals(scope.getAnalysisRunId(), result.getAnalysisRunId())) {
                    throw new IllegalStateException("候选作用域不匹配");
                }
                if (result.getCandidateType() == null || !hasText(result.getCandidateId())) {
                    snapshotAvailable = false;
                    continue;
                }
                if (!hasText(candidate.getName())) { snapshotAvailable = false; }
                int rank = ranks.getOrDefault(result.getCandidateType(), 0) + 1;
                ranks.put(result.getCandidateType(), rank);
                if (!Objects.equals(result.getRank(), rank) || !candidateIds.add(result.getCandidateType()+":"+result.getCandidateId())
                        || result.getScore() == null || !Double.isFinite(result.getScore())
                        || result.getScore() < 0 || result.getScore() > 1 || result.getScoreType() == null
                        || !hasText(result.getEmbeddingModel()) || !hasText(result.getEmbeddingVersion())) {
                    throw new IllegalStateException("候选评分或版本契约不完整");
                }
                if (!Objects.equals(item.getEmbeddingModel(), result.getEmbeddingModel())
                        || !Objects.equals(item.getEmbeddingVersion(), result.getEmbeddingVersion())) {
                    throw new IllegalStateException("候选与检索响应模型版本不一致");
                }
                audit.setEmbeddingModel(result.getEmbeddingModel());
                audit.setEmbeddingVersion(result.getEmbeddingVersion());
                Map<String, String> filters = result.getMatchedFilters();
                if (filters != null) {
                    audit.setFilterEnabled(Boolean.parseBoolean(filters.get("enabled")));
                    audit.setFilterApplied(Boolean.parseBoolean(filters.get("applied")));
                    audit.setFilterExpression(filters.getOrDefault("expression", "[]"));
                }
            }
            if (!snapshotAvailable || item.getStatus() == RetrievalBatchStatus.SNAPSHOT_UNAVAILABLE
                    || (item.getStatus() == RetrievalBatchStatus.SUCCESS && candidates.isEmpty())) {
                audit.setRetrievalStatus(RetrievalAuditStatus.SNAPSHOT_UNAVAILABLE);
            } else {
                audit.setRetrievalStatus(item.getStatus() == RetrievalBatchStatus.NO_CANDIDATES
                        ? RetrievalAuditStatus.NO_CANDIDATES : RetrievalAuditStatus.SUCCESS);
            }
            audit.setAnalysisStatus(!evidenceAvailable.get(behavior.getId()) ? AnalysisAuditStatus.INSUFFICIENT_EVIDENCE
                    : audit.getRetrievalStatus() == RetrievalAuditStatus.SUCCESS
                    ? AnalysisAuditStatus.WAITING_P3 : AnalysisAuditStatus.NOT_ATTEMPTED);
            output.add(audit);
        }
        return output;
    }

    private boolean verifyEvidence(AnalysisScope scope, Behavior behavior) {
        List<String> ids = behavior.getEvidenceIds();
        if (ids == null || ids.isEmpty()) { return false; }
        List<EvidenceChunk> evidence = evidenceQuery.listByIds(ids, scope.getProjectId(), scope.getAssessmentId());
        Set<String> found = new HashSet<>();
        for (EvidenceChunk chunk : evidence) {
            chunk.verifyIntegrity();
            if (!Objects.equals(chunk.getProjectId(), scope.getProjectId())
                    || !Objects.equals(chunk.getAssessmentId(), scope.getAssessmentId())
                    || !Objects.equals(chunk.getSourceDocumentId(), behavior.getSourceDocumentId())) {
                throw new IllegalStateException("Evidence 来源或作用域不一致");
            }
            found.add(chunk.getId());
        }
        return found.containsAll(ids);
    }

    private RetrievalAudit baseAudit(AnalysisScope scope, Behavior behavior) {
        return RetrievalAudit.builder().projectId(scope.getProjectId()).assessmentId(scope.getAssessmentId())
                .analysisRunId(scope.getAnalysisRunId()).behaviorId(behavior.getId())
                .queryText(queryBuilder.buildQueryText(behavior)).queryTemplateVersion(BehaviorQueryTextBuilder.QUERY_TEMPLATE_VERSION)
                .filterVersion(RetrievalFilter.FILTER_VERSION).filterEnabled(false).filterApplied(false)
                .filterExpression("[]").build();
    }

    private void notifyCompleted(IndicatorCalculationTaskMessage message, AnalysisScope scope) {
        try {
            NotificationMessage notification = new NotificationMessage(StringUtils.generateMessageId(),
                    LocalDateTime.now().toString(), StringUtils.generateTraceId(), message.getUserId(),
                    scope.getProjectId(), scope.getAssessmentId(), NotificationMessage.NotificationType.ASSESSMENT_COMPLETED,
                    "检索完成通知", "材料检索已完成，合规判断等待后续分析，请查看事实与候选依据。");
            notification.setExtraData("{\"displayStatus\":\"COMPLETED_WITHOUT_DECISION\",\"analysisStage\":\"P2\"}");
            kafka.sendMessage(notification);
        } catch (RuntimeException failure) {
            log.warn("检索完成通知发送失败: assessmentId={}, run={}", scope.getAssessmentId(), scope.getAnalysisRunId());
        }
    }

    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
