package com.riskwarning.processing.service;

import com.riskwarning.common.dto.analysis.AnalysisScope;
import com.riskwarning.common.dto.analysis.RuleEvaluationResult;
import com.riskwarning.common.enums.AnalysisRunStatus;
import com.riskwarning.common.enums.analysis.*;
import com.riskwarning.common.po.analysis.*;
import com.riskwarning.processing.dto.analysis.AnalysisSummaryVO;
import com.riskwarning.processing.rule.RuleEvaluationContracts;
import lombok.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

/** Reasoning 终态策略只读当前运行的权威记录，不结束历史运行或发布中间结果。 */
@Component
public class AnalysisCompletionPolicy {
    /** 行为清单必须在本 Run 冻结；Provider 故障优先于所有已形成决定。 */
    public Completion resolve(AnalysisScope scope, Set<String> expectedBehaviors, List<RetrievalAudit> retrievals,
                              List<AnalysisResult> results, List<RuleEvaluationAudit> rules,
                              List<AnalysisAttempt> attempts, boolean executionFailed) {
        require(scope != null && expectedBehaviors != null && expectedBehaviors.stream().allMatch(AnalysisCompletionPolicy::text)
                && retrievals != null && results != null && rules != null && attempts != null, "运行清单与审计不能为空");
        Map<String, RetrievalAudit> byBehavior = new HashMap<>();
        for (RetrievalAudit row : retrievals) {
            require(row != null && scoped(scope, row.getProjectId(), row.getAssessmentId(), row.getAnalysisRunId())
                    && expectedBehaviors.contains(row.getBehaviorId()) && row.getRetrievalStatus() != null && row.getAnalysisStatus() != null
                    && byBehavior.put(row.getBehaviorId(), row) == null, "检索审计跨作用域、重复或缺失状态");
        }
        Set<String> resultIds = new HashSet<>(), resultKeys = new HashSet<>(), targets = new HashSet<>();
        for (AnalysisResult row : results) {
            require(row != null && "1.1".equals(row.getSchemaVersion()) && scope.getAssessmentId().equals(row.getAssessmentId())
                    && scope.getAnalysisRunId().equals(row.getAnalysisRunId()) && expectedBehaviors.contains(row.getBehaviorId())
                    && text(row.getId()) && resultIds.add(row.getId()) && row.getApplicable() != null && row.getComplianceStatus() != null,
                    "分析结果跨作用域、版本无效或重复");
            require(resultKeys.add(row.getBehaviorId() + ":" + row.getIndicatorId()), "同事实与指标结果重复");
            if (text(row.getIndicatorId())) targets.add(row.getIndicatorId());
        }
        Map<String, RuleEvaluationAudit> byIndicator = new HashMap<>();
        for (RuleEvaluationAudit row : rules) {
            require(row != null && scoped(scope, row.getProjectId(), row.getAssessmentId(), row.getAnalysisRunId())
                    && targets.contains(row.getIndicatorId()) && byIndicator.put(row.getIndicatorId(), row) == null
                    && row.getStatus() != null, "规则审计跨作用域、无目标来源或重复");
            require(row.getCalculationDetails() != null && row.getCalculationDetails().getRuleCalculation() != null,
                    "规则审计缺失强类型最终决定");
            RuleEvaluationResult decision = row.getCalculationDetails().getRuleCalculation().getFinalDecision();
            RuleEvaluationContracts.validate(decision);
            require(row.getIndicatorId().equals(decision.getIndicatorId()) && row.getStatus() == decision.getStatus()
                    && Objects.equals(row.getInputHash(), decision.getInputHash())
                    && Objects.equals(row.getRuleVersion(), decision.getRuleVersion())
                    && Objects.equals(row.getThresholdVersion(), decision.getThresholdVersion())
                    && Objects.equals(row.getAggregationOperator(), decision.getAggregationOperator() == null ? null : decision.getAggregationOperator().name())
                    && (decided(row.getStatus()) ? Objects.equals(row.getConflict(), decision.getConflict()) : !Boolean.TRUE.equals(row.getConflict())),
                    "规则审计与最终决定不一致");
            if (decided(row.getStatus())) {
                require(results.stream().filter(result -> row.getIndicatorId().equals(result.getIndicatorId()))
                        .allMatch(result -> result.getApplicable() == Applicability.APPLICABLE
                                && (result.getComplianceStatus() == ComplianceStatus.COMPLIANT || result.getComplianceStatus() == ComplianceStatus.NON_COMPLIANT)),
                        "未决或不适用分析结果不能提供有效指标决定");
            }
        }
        boolean failed = executionFailed;
        Set<String> attemptKeys = new HashSet<>();
        for (AnalysisAttempt attempt : attempts) {
            require(attempt != null && scoped(scope, attempt.getProjectId(), attempt.getAssessmentId(), attempt.getAnalysisRunId())
                    && expectedBehaviors.contains(attempt.getBehaviorId()) && attempt.getStatus() != null && attempt.getAttemptNo() != null
                    && attempt.getAttemptNo() > 0 && attemptKeys.add(attempt.getBehaviorId() + ":" + attempt.getAttemptNo()),
                    "调用审计跨作用域、序号无效或重复");
            if (attempt.getStatus() == AnalysisAttemptStatus.PROVIDER_FAILED) failed = true;
        }
        AnalysisSummaryVO summary = new AnalysisSummaryVO(); summary.setBehaviorCount(expectedBehaviors.size());
        summary.setConclusionCount(results.size());
        summary.setDecisionCount((int) rules.stream().filter(row -> decided(row.getStatus())).count());
        summary.setUnsupportedRuleCount((int) rules.stream().filter(row -> row.getStatus() == RuleEvaluationStatus.UNSUPPORTED_RULE).count());
        summary.setConflictCount((int) rules.stream().filter(row -> row.getStatus() == RuleEvaluationStatus.DECIDED_WITH_CONFLICT).count());
        Set<String> needsReview = new HashSet<>();
        for (RetrievalAudit row : retrievals) {
            switch (row.getRetrievalStatus()) {
                case SUCCESS: summary.setRetrievalSuccessCount(summary.getRetrievalSuccessCount() + 1); break;
                case NO_CANDIDATES: summary.setNoCandidateCount(summary.getNoCandidateCount() + 1); break;
                case FAILED: summary.setRetrievalFailedCount(summary.getRetrievalFailedCount() + 1); failed = true; break;
                case SNAPSHOT_UNAVAILABLE: summary.setSnapshotUnavailableCount(summary.getSnapshotUnavailableCount() + 1); break;
                default: break;
            }
            switch (row.getAnalysisStatus()) {
                case ANALYZED: summary.setAnalyzedCount(summary.getAnalyzedCount() + 1); break;
                case NO_APPLICABLE_CANDIDATE: summary.setNoApplicableCandidateCount(summary.getNoApplicableCandidateCount() + 1); break;
                case INSUFFICIENT_EVIDENCE: summary.setInsufficientEvidenceCount(summary.getInsufficientEvidenceCount() + 1); break;
                case NEEDS_REVIEW: needsReview.add(row.getBehaviorId()); break;
                case WAITING_ANALYSIS: summary.setWaitingForAnalysisCount(summary.getWaitingForAnalysisCount() + 1); break;
                case NOT_ATTEMPTED: summary.setNotAttemptedCount(summary.getNotAttemptedCount() + 1); break;
                case RECALL_GAP: summary.setRecallGapCount(summary.getRecallGapCount() + 1); break;
                default: throw new IllegalArgumentException("Reasoning 不消费历史演示分析审计状态");
            }
        }
        Set<String> reviewIndicators = rules.stream().filter(row -> row.getStatus() == RuleEvaluationStatus.NEEDS_REVIEW)
                .map(RuleEvaluationAudit::getIndicatorId).collect(Collectors.toSet());
        results.stream().filter(row -> reviewIndicators.contains(row.getIndicatorId())).forEach(row -> needsReview.add(row.getBehaviorId()));
        summary.setNeedsReviewCount(needsReview.size());
        if (failed) return new Completion(AnalysisRunStatus.FAILED, summary);
        require(byBehavior.keySet().equals(expectedBehaviors), "行为审计未齐，不能结束运行");
        require(byIndicator.keySet().equals(targets), "目标指标规则审计未齐，不能结束运行");
        for (RetrievalAudit row : retrievals) {
            require(row.getAnalysisStatus() != AnalysisAuditStatus.WAITING_ANALYSIS, "分析仍在等待，不能结束运行");
            require(row.getRetrievalStatus() != RetrievalAuditStatus.SUCCESS || row.getAnalysisStatus() != AnalysisAuditStatus.NOT_ATTEMPTED,
                    "就绪分析尚未执行，不能结束运行");
            boolean hasResult = results.stream().anyMatch(result -> row.getBehaviorId().equals(result.getBehaviorId()));
            require(row.getAnalysisStatus() != AnalysisAuditStatus.ANALYZED || hasResult, "已分析审计缺失分析结果");
            require((row.getAnalysisStatus() != AnalysisAuditStatus.ANALYZED && row.getAnalysisStatus() != AnalysisAuditStatus.NO_APPLICABLE_CANDIDATE)
                    || row.getRetrievalStatus() == RetrievalAuditStatus.SUCCESS, "语义分析完成必须有成功检索");
            require(row.getAnalysisStatus() != AnalysisAuditStatus.NO_APPLICABLE_CANDIDATE || !hasResult,
                    "无适用候选审计不能携带指标结论");
            require(row.getAnalysisStatus() != AnalysisAuditStatus.RECALL_GAP || !hasResult,
                    "未通过检索质量门禁的行为不能携带分析结论");
        }
        if (summary.getDecisionCount() == 0) {
            summary.setCompletionKind(CompletionKind.NONE);
            return new Completion(AnalysisRunStatus.COMPLETED_WITHOUT_DECISION, summary);
        }
        boolean allBehaviorDecided = expectedBehaviors.stream().allMatch(behavior -> {
            if (byBehavior.get(behavior).getAnalysisStatus() != AnalysisAuditStatus.ANALYZED) return false;
            List<AnalysisResult> rows = results.stream().filter(row -> behavior.equals(row.getBehaviorId())).collect(Collectors.toList());
            return !rows.isEmpty() && rows.stream().allMatch(row -> text(row.getIndicatorId()) && row.getApplicable() == Applicability.APPLICABLE
                    && (row.getComplianceStatus() == ComplianceStatus.COMPLIANT || row.getComplianceStatus() == ComplianceStatus.NON_COMPLIANT)
                    && byIndicator.get(row.getIndicatorId()).getStatus() == RuleEvaluationStatus.DECIDED);
        });
        summary.setCompletionKind(allBehaviorDecided && summary.getDecisionCount() == targets.size() ? CompletionKind.FULL : CompletionKind.PARTIAL);
        return new Completion(AnalysisRunStatus.SUCCEEDED, summary);
    }
    private static boolean scoped(AnalysisScope scope, Long project, Long assessment, String run) {
        return scope.getProjectId().equals(project) && scope.getAssessmentId().equals(assessment) && scope.getAnalysisRunId().equals(run);
    }
    private static boolean decided(RuleEvaluationStatus status) { return status == RuleEvaluationStatus.DECIDED || status == RuleEvaluationStatus.DECIDED_WITH_CONFLICT; }
    private static boolean text(String value) { return value != null && !value.trim().isEmpty(); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }

    @Value
    public static class Completion {
        AnalysisRunStatus status;
        AnalysisSummaryVO summary;
        public boolean isPublishable() { return status == AnalysisRunStatus.SUCCEEDED; }
    }
}
