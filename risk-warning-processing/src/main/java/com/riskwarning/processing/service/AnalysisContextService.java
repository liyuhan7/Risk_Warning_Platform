package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisTrace;
import com.riskwarning.common.dto.retrieval.RetrievalCandidateType;
import com.riskwarning.common.enums.analysis.AnalysisAuditStatus;
import com.riskwarning.common.enums.analysis.RetrievalAuditStatus;
import com.riskwarning.common.po.analysis.AnalysisResult;
import com.riskwarning.common.po.analysis.AnalysisRun;
import com.riskwarning.common.po.analysis.RetrievalAudit;
import com.riskwarning.common.po.behavior.Behavior;
import com.riskwarning.common.po.indicator.IndicatorResult;
import com.riskwarning.common.po.report.Assessment;
import com.riskwarning.processing.dto.analysis.AnalysisContextVO;
import com.riskwarning.processing.dto.analysis.BehaviorAnalysisContextVO;
import com.riskwarning.processing.dto.analysis.RetrievalCandidateVO;
import com.riskwarning.processing.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 后续分析上下文的只读聚合，作用域与概览保持一致的三重校验。
 * 只暴露已经持久化的事实、证据引用与检索候选，不做适用性判断，也不产生规则或风险结论。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AnalysisContextService {
    /** 上下文契约版本，与 {@link AnalysisContextVO#getContextVersion()} 对应。 */
    static final String CONTEXT_VERSION = "1.0";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AssessmentRepository assessments;
    private final AnalysisRunSelector runSelector;
    private final RetrievalAuditRepository audits;
    private final BehaviorDocumentRepository behaviors;
    private final AnalysisResultRepository results;
    private final IndicatorResultRepository indicatorResults;

    /** 指定 Run 时必须同时验证项目和评估归属，不回退到其他运行。 */
    @Transactional(readOnly = true)
    public AnalysisContextVO context(Long assessmentId, Long projectId, String analysisRunId) {
        if (projectId == null || projectId <= 0 || assessmentId == null || assessmentId <= 0) {
            throw new IllegalArgumentException("projectId 与 assessmentId 必须为正数");
        }
        Assessment assessment = assessments.findById(assessmentId)
                .orElseThrow(() -> new NoSuchElementException("评估不存在"));
        if (!projectId.equals(assessment.getProjectId())) {
            throw new SecurityException("项目与评估归属不匹配");
        }
        AnalysisRunSelector.Selection selection = runSelector.select(assessmentId, projectId, analysisRunId);
        if (selection.getRun() == null) {
            return AnalysisContextVO.builder().contextVersion(CONTEXT_VERSION).assessmentId(assessmentId)
                    .projectId(projectId).runStatus("NOT_STARTED").analysisMode(AnalysisModeResolver.NO_ARTIFACTS)
                    .generatedAt(time(LocalDateTime.now())).blockedReasons(new TreeMap<>())
                    .items(new ArrayList<>()).build();
        }
        AnalysisRun run = selection.getRun();
        List<RetrievalAudit> auditRows = audits.findByAssessmentIdAndAnalysisRunId(assessmentId, run.getAnalysisRunId());
        List<AnalysisResult> resultRows = results.findByAssessmentIdAndAnalysisRunId(assessmentId, run.getAnalysisRunId());
        Map<String, AnalysisTrace> traces = legacyTraces(projectId, assessmentId, run.getAnalysisRunId());
        Map<String, Behavior> descriptions = descriptions(projectId, assessmentId, run.getAnalysisRunId());
        Map<String, RetrievalAudit> auditsByBehavior = new TreeMap<>();
        for (RetrievalAudit audit : auditRows) { auditsByBehavior.put(audit.getBehaviorId(), audit); }
        Set<String> behaviorIds = new TreeSet<>(auditsByBehavior.keySet());
        behaviorIds.addAll(descriptions.keySet());
        Map<String, Integer> blockedReasons = new TreeMap<>();
        List<BehaviorAnalysisContextVO> items = new ArrayList<>();
        int ready = 0;
        for (String behaviorId : behaviorIds) {
            BehaviorAnalysisContextVO item = item(behaviorId, descriptions.get(behaviorId),
                    auditsByBehavior.get(behaviorId), hasDecision(resultRows, behaviorId), traces.get(behaviorId));
            if (item.isReadyForAnalysis()) { ready++; }
            else { blockedReasons.merge(item.getBlockedReason(), 1, Integer::sum); }
            items.add(item);
        }
        return AnalysisContextVO.builder().contextVersion(CONTEXT_VERSION).assessmentId(assessmentId)
                .projectId(projectId).analysisRunId(run.getAnalysisRunId()).runStatus(run.getStatus().name())
                .analysisMode(AnalysisModeResolver.resolve(resultRows, auditRows)).generatedAt(time(LocalDateTime.now()))
                .behaviorCount(items.size()).readyForAnalysisCount(ready).blockedCount(items.size() - ready)
                .blockedReasons(blockedReasons).items(items).build();
    }

    /** 旧链的指标结果按行为保存检索轨迹；真实 P2 检索链没有指标结果。 */
    private Map<String, AnalysisTrace> legacyTraces(Long projectId, Long assessmentId, String analysisRunId) {
        Map<String, AnalysisTrace> traces = new HashMap<>();
        for (IndicatorResult result : indicatorResults.findByAssessmentIdAndAnalysisRunId(assessmentId, analysisRunId)) {
            if (result.getCalculationDetails() == null || result.getCalculationDetails().getTraces() == null) { continue; }
            for (AnalysisTrace trace : result.getCalculationDetails().getTraces()) {
                if (trace != null && hasText(trace.getBehaviorId())) { traces.putIfAbsent(trace.getBehaviorId(), trace); }
            }
        }
        return traces;
    }

    /** ES 不可用只影响结构化字段，PostgreSQL 的审计与候选仍可回放。 */
    private Map<String, Behavior> descriptions(Long projectId, Long assessmentId, String analysisRunId) {
        Map<String, Behavior> descriptions = new HashMap<>();
        try {
            for (Behavior behavior : behaviors.findByScope(projectId, assessmentId, analysisRunId)) {
                descriptions.put(behavior.getId(), behavior);
            }
        } catch (RuntimeException e) {
            log.warn("分析上下文行为描述不可用: assessmentId={}, run={}", assessmentId, analysisRunId, e);
        }
        return descriptions;
    }

    private BehaviorAnalysisContextVO item(String behaviorId, Behavior behavior, RetrievalAudit audit,
                                           boolean decisionPresent, AnalysisTrace trace) {
        List<RetrievalCandidateVO> candidates = audit == null ? Collections.emptyList()
                : safe(audit.getCandidates()).stream().map(RetrievalCandidateVoMapper::toVo).collect(Collectors.toList());
        List<String> evidenceIds = behavior == null ? Collections.emptyList() : safe(behavior.getEvidenceIds());
        boolean evidenceComplete = !evidenceIds.isEmpty();
        boolean snapshotAvailable = audit != null && audit.getCandidates() != null;
        String blockedReason = blockedReason(audit, decisionPresent, evidenceIds, candidates, snapshotAvailable);
        return BehaviorAnalysisContextVO.builder().behaviorId(behaviorId)
                .description(behavior == null ? null : behavior.getDescription())
                .subject(behavior == null ? null : behavior.getSubject())
                .action(behavior == null ? null : behavior.getAction())
                .object(behavior == null ? null : behavior.getObject())
                .type(behavior == null ? null : behavior.getType())
                .dimension(behavior == null ? null : behavior.getDimension())
                .quantitativeData(behavior == null ? null : behavior.getQuantitativeData())
                .quantitativeUnit(behavior == null ? null : behavior.getQuantitativeUnit())
                .confidence(behavior == null ? null : behavior.getConfidence())
                .sourceDocumentId(behavior == null ? null : behavior.getSourceDocumentId())
                .evidenceIds(evidenceIds)
                .queryText(audit == null ? null : audit.getQueryText())
                .queryTemplateVersion(audit == null ? null : audit.getQueryTemplateVersion())
                .filterVersion(audit == null ? null : audit.getFilterVersion())
                .filterEnabled(audit == null ? null : audit.getFilterEnabled())
                .filterApplied(audit == null ? null : audit.getFilterApplied())
                .filterExpression(audit == null ? null : audit.getFilterExpression())
                .retrievalStatus(audit == null || audit.getRetrievalStatus() == null ? null : audit.getRetrievalStatus().name())
                .analysisStatus(audit == null || audit.getAnalysisStatus() == null ? null : audit.getAnalysisStatus().name())
                .embeddingModel(audit == null ? null : audit.getEmbeddingModel())
                .retrievalVersion(audit == null ? null : audit.getEmbeddingVersion())
                .errorCode(audit == null ? null : audit.getErrorCode())
                .errorMessage(audit == null ? null : audit.getErrorMessage())
                .snapshotAvailable(snapshotAvailable).evidenceComplete(evidenceComplete)
                .readyForAnalysis(blockedReason == null).blockedReason(blockedReason)
                .indicatorCandidates(ofType(candidates, RetrievalCandidateType.INDICATOR))
                .regulationCandidates(ofType(candidates, RetrievalCandidateType.REGULATION))
                .calculationDetails(trace)
                .build();
    }

    /**
     * 门禁按"先检索后证据"的顺序判定，任一未通过都不得进入后续分析。
     * 证据完整性以检索时的校验结果为准，此处只要求存在引用。
     */
    private static String blockedReason(RetrievalAudit audit, boolean decisionPresent, List<String> evidenceIds,
                                        List<RetrievalCandidateVO> candidates, boolean snapshotAvailable) {
        if (decisionPresent) { return "DECISION_PRESENT"; }
        if (audit == null) { return "NO_RETRIEVAL_AUDIT"; }
        RetrievalAuditStatus retrieval = audit.getRetrievalStatus();
        if (retrieval == RetrievalAuditStatus.FAILED) { return "RETRIEVAL_FAILED"; }
        if (retrieval == RetrievalAuditStatus.NO_CANDIDATES) { return "NO_CANDIDATES"; }
        if (retrieval == RetrievalAuditStatus.SNAPSHOT_UNAVAILABLE) { return "SNAPSHOT_UNAVAILABLE"; }
        AnalysisAuditStatus analysis = audit.getAnalysisStatus();
        if (analysis == AnalysisAuditStatus.INSUFFICIENT_EVIDENCE || evidenceIds.isEmpty()) { return "INSUFFICIENT_EVIDENCE"; }
        if (analysis == AnalysisAuditStatus.RECALL_GAP) { return "RECALL_GAP"; }
        if (analysis == AnalysisAuditStatus.NOT_DEMO_INPUT) { return "NOT_P2_RUN"; }
        if (analysis == AnalysisAuditStatus.NOT_ATTEMPTED) { return "NOT_ATTEMPTED"; }
        if (!snapshotAvailable || candidates.stream().noneMatch(RetrievalCandidateVO::isSnapshotAvailable)) {
            return "SNAPSHOT_UNAVAILABLE";
        }
        if (analysis != AnalysisAuditStatus.WAITING_P3) { return "NOT_WAITING_FOR_ANALYSIS"; }
        return null;
    }

    private static boolean hasDecision(List<AnalysisResult> resultRows, String behaviorId) {
        return resultRows.stream().anyMatch(r -> Objects.equals(behaviorId, r.getBehaviorId()));
    }

    private static List<RetrievalCandidateVO> ofType(List<RetrievalCandidateVO> candidates, RetrievalCandidateType type) {
        return candidates.stream().filter(c -> type.name().equals(c.getCandidateType())).collect(Collectors.toList());
    }

    private static String time(LocalDateTime value) { return value == null ? null : TIME.format(value); }

    private static boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }

    private static <T> List<T> safe(List<T> values) { return values == null ? Collections.emptyList() : values; }
}
